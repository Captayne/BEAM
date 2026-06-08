package de.systragon.beam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import de.systragon.beam.core.TorrentEntry
import de.systragon.beam.core.TorrentManager
import de.systragon.beam.core.TorrentState
import de.systragon.beam.media.FileCrypto
import de.systragon.beam.media.MediaStoreSaver
import de.systragon.beam.transfer.TorrentViewModel
import java.io.File
import javax.crypto.AEADBadTagException

class SeedingService : Service() {

    companion object {
        const val ACTION_ADD_TORRENT = "action_add_torrent"
        const val ACTION_ADD_MAGNET  = "action_add_magnet"
        const val ACTION_PAUSE       = "action_pause"
        const val ACTION_RESUME      = "action_resume"
        const val ACTION_STOP        = "action_stop"
        const val ACTION_RETRY       = "action_retry"
        const val ACTION_RESTART     = "action_restart"
        const val ACTION_RELAY       = "action_relay"

        const val EXTRA_TORRENT_PATH = "torrent_path"
        const val EXTRA_FILE_DIR     = "file_dir"
        const val EXTRA_FILE_NAME    = "file_name"
        const val EXTRA_FILE_SIZE    = "file_size"
        const val EXTRA_INFO_HASH    = "info_hash"
        const val EXTRA_MAGNET_URI   = "magnet_uri"

        const val CHANNEL_ID        = "beam_seeding"
        const val CHANNEL_DONE_ID   = "beam_done"
        const val NOTIFICATION_ID   = 1
    }

    // Verhindert doppeltes Veröffentlichen desselben Downloads.
    private val publishing = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Halten CPU und WLAN-Radio während aktiver Transfers wach (sonst drosselt Android bei Screen-off).
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    // LSD (lokale Peer-Discovery) braucht eingehende Multicast-Pakete — Android filtert die
    // ohne MulticastLock weg. Ohne den finden sich zwei Handys im selben WLAN/Hotspot nicht.
    private var multicastLock: WifiManager.MulticastLock? = null

    // Netzwechsel-Erkennung (WLAN↔Mobilfunk / IP-Wechsel) → Sockets neu öffnen + neu announcen.
    private var connectivityManager: android.net.ConnectivityManager? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var netChangeRunnable: Runnable? = null
    private var firstNetworkEvent = true

    // TCP-first / µTP-Fallback: Zeitpunkt, seit dem ein aktiver Download keinen Peer hat (0 = nicht).
    // Nach der in Settings eingestellten Sekundenzahl wird ausgehendes µTP zugeschaltet; sobald
    // wieder Peers da sind, zurück auf TCP-only.
    private var stuckSinceMs = 0L
    private var utpFallbackActive = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        TorrentManager.startSession()
        startForeground(NOTIFICATION_ID, buildNotification("Beam! ready"))
        acquireLocks()
        registerNetworkCallback()

        // Status alle 5 Sekunden: Downloads prüfen + Notification aktualisieren.
        // Gibt es nichts mehr zu tun, beendet sich der Dienst (App kann schlafen).
        Thread {
            var tick = 0
            while (true) {
                Thread.sleep(5000)
                if (TorrentManager.getAll().isEmpty()) {
                    stopForegroundAndSelf()
                    break
                }
                checkDownloads()
                updateLocks()
                maybeAdjustTransport()
                maybeRelayRedial()
                TorrentManager.refreshTrackerStatus()
                logConnections()
                if (tick % 6 == 0) logTrackers()   // ~alle 30 s
                updateAggregateNotification()
                tick++
            }
        }.start()
    }

    /**
     * TCP-first / µTP-Fallback: hängt ein aktiver Download ~25 s ohne jeden Peer, wird µTP
     * zugeschaltet (letzter Strohhalm fürs NAT-Durchschlagen). Sobald wieder Peers da sind,
     * zurück auf TCP-only — damit der schnelle TCP-Pfad der Normalfall bleibt.
     */
    /**
     * Bei jedem neuen Transfer den Transport auf die TCP-only-Baseline zurücksetzen. Schützt gegen
     * eine **veraltete Session** (z. B. nach APK-Update lief eine alte µTP-Session weiter) — so
     * startet jeder Transfer garantiert auf dem schnellen TCP-Pfad, bevor der Fallback greifen darf.
     */
    private fun resetTransportBaseline() {
        stuckSinceMs = 0L
        utpFallbackActive = false
        TorrentManager.setOutgoingUtpEnabled(false)
    }

    /**
     * Relay-Rendezvous frisch halten. Problem: wer allein am Relay ankommt, dessen libtorrent bricht den
     * Handshake nach ~15 s ab (das Relay bleibt stumm, bis BEIDE da sind). Damit sich die kurzen Fenster
     * beider Seiten überlappen, re-dialen wir alle ~12 s, solange KEIN Peer verbunden ist:
     *  - EMPFÄNGER (Download) horcht IMMER am Relay (nicht-exklusiv → direkt/DHT/Tracker bleiben aktiv).
     *  - SENDER, der „Relay NOW" gedrückt hat (relayEngaged), hält seine exklusive Relay-Verbindung frisch.
     * Sobald ein Peer steht (numPeers>0), läuft's → kein Re-Dial mehr.
     */
    private fun maybeRelayRedial() {
        val now = System.currentTimeMillis()
        val ep = de.systragon.beam.core.RelayConfig.parse(
            runCatching { java.io.File(getExternalFilesDir(null), "relay.conf").takeIf { it.exists() }?.readText() }.getOrNull()
        )
        TorrentManager.getAll().forEach { e ->
            val st = e.handle?.takeIf { it.isValid }?.status() ?: return@forEach
            if (st.isFinished) return@forEach
            val wantRelay = e.isDownload || e.relayEngaged
            // Re-Dial NUR, wenn das Relay noch nicht als Peer hängt (Anti-Churn) und seit dem letzten
            // Versuch genug Zeit war, dass die Röhre paaren + Handshake/Metadaten austauschen konnte.
            if (wantRelay && !TorrentManager.isRelayConnected(e.infoHash, ep.host) && now - e.lastRelayDial > 15_000L) {
                e.lastRelayDial = now
                val exclusive = e.relayEngaged   // Sender bleibt exklusiv; Empfänger non-exklusiv
                Thread { TorrentManager.engageRelay(e.infoHash, ep.host, ep.port, exclusive = exclusive) }.start()
            }
        }
    }

    private fun maybeAdjustTransport() {
        val needyNoPeer = TorrentManager.getAll().any { e ->
            e.isDownload &&
                (e.state == TorrentState.FETCHING_METADATA || e.state == TorrentState.DOWNLOADING) &&
                ((e.handle?.takeIf { it.isValid }?.status()?.numPeers() ?: 0) == 0)
        }
        if (needyNoPeer) {
            val now = System.currentTimeMillis()
            if (stuckSinceMs == 0L) stuckSinceMs = now
            val thresholdMs = getSharedPreferences("beam", Context.MODE_PRIVATE)
                .getInt(TorrentViewModel.PREF_UTP_FALLBACK_SECONDS, TorrentViewModel.UTP_FALLBACK_DEFAULT) * 1000L
            if (!utpFallbackActive && now - stuckSinceMs >= thresholdMs) {
                Log.i("SeedingService", "Kein Peer seit ${(now - stuckSinceMs) / 1000}s → ausgehendes µTP AN (Fallback)")
                TorrentManager.setOutgoingUtpEnabled(true)
                utpFallbackActive = true
            }
        } else {
            stuckSinceMs = 0L
            if (utpFallbackActive) {
                Log.i("SeedingService", "Peer(s) verbunden → ausgehend zurück auf TCP-only")
                TorrentManager.setOutgoingUtpEnabled(false)
                utpFallbackActive = false
            }
        }
    }

    /** Diagnose-Log (per `adb logcat -s BeamNet`): Peers, Raten, DHT-Nodes + Transport pro Peer. */
    private fun logConnections() {
        val dht = try { TorrentManager.dhtNodes() } catch (_: Exception) { -1L }
        TorrentManager.getAll().forEach { e ->
            val h = e.handle ?: return@forEach
            if (!h.isValid) return@forEach
            val st = h.status()
            Log.i(
                "BeamNet",
                "${e.fileName} state=${e.state} peers=${st.numPeers()} " +
                    "down=${st.downloadRate()}B/s up=${st.uploadRate()}B/s dht=$dht"
            )
            // Pro Peer: Transport (µTP vs TCP), Richtung (out=wir verbunden / in=eingehend),
            // ob Hole-Punch, IP, Raten und Client. Zeigt, ob lokal über TCP oder µTP gefahren wird.
            try {
                val utp = org.libtorrent4j.swig.peer_info.utp_socket.to_int()
                val local = org.libtorrent4j.swig.peer_info.local_connection.to_int()
                val holepunched = org.libtorrent4j.swig.peer_info.holepunched.to_int()
                h.peerInfo().forEach { p ->
                    val f = p.flags()
                    val transport = if (f and utp != 0) "uTP" else "TCP"
                    val dir = if (f and local != 0) "out" else "in"
                    val hp = if (f and holepunched != 0) " holepunched" else ""
                    Log.i(
                        "BeamNet",
                        "  peer ${p.ip()} $transport/$dir " +
                            "down=${p.downSpeed()}B/s up=${p.upSpeed()}B/s ${p.client()}$hp"
                    )
                }
            } catch (_: Exception) {}
        }
    }

    /** Tracker-Health (per `adb logcat -s BeamTrk`): welcher Tracker funktioniert, Fails, Fehler. */
    private fun logTrackers() {
        TorrentManager.getAll().forEach { e ->
            val h = e.handle ?: return@forEach
            if (!h.isValid) return@forEach
            try {
                h.trackers().forEach { t ->
                    var working = false
                    var fails = 0
                    var msg = ""
                    t.endpoints().forEach { ep ->
                        ep.infohashV1()?.let { ih ->
                            if (ih.isWorking) working = true
                            fails = maxOf(fails, ih.fails().toInt())
                            if (ih.message().isNotEmpty()) msg = ih.message()
                        }
                    }
                    Log.i(
                        "BeamTrk",
                        "${t.url()} working=$working verified=${t.isVerified} fails=$fails" +
                            if (msg.isNotEmpty()) " msg=\"$msg\"" else ""
                    )
                }
            } catch (_: Exception) {}
        }
    }

    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i("SeedingService", "Keine Aktivitäten mehr — Dienst beendet")
    }

    /**
     * Locks nur bei AKTIVEM Transfer halten (Download läuft, oder ein Seed hat verbundene Peers/
     * Upload). Im Leerlauf (fertige Downloads, wartende Seeds ohne Peers) werden sie freigegeben →
     * Handy kann schlafen, bis sich wieder ein Client verbindet. Spart Akku.
     */
    private fun updateLocks() {
        val active = TorrentManager.getAll().any { e ->
            when (e.state) {
                TorrentState.DOWNLOADING, TorrentState.FETCHING_METADATA -> true
                TorrentState.SEEDING -> {
                    val h = e.handle
                    h != null && h.isValid && (h.status().numPeers() > 0 || h.status().uploadRate() > 0)
                }
                else -> false
            }
        }
        if (active) acquireLocks() else releaseLocks()
    }

    /** Hält CPU (PARTIAL_WAKE_LOCK) und WLAN-Radio (FULL_HIGH_PERF) wach. Idempotent. */
    private fun acquireLocks() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "beam:transfer").apply {
                setReferenceCounted(false)
                acquire()
            }
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "beam:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
            // Multicast durchlassen → LSD kann Peers im lokalen Netz/Hotspot finden.
            multicastLock = wm.createMulticastLock("beam:lsd").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w("SeedingService", "Locks konnten nicht angefordert werden: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
        multicastLock = null
    }

    /**
     * Lauscht auf das aktive Standardnetz. Bei WLAN↔Mobilfunk-Wechsel ODER IP-Wechsel
     * (`onLinkPropertiesChanged`) wird — entprellt — die Session neu aufgesetzt, damit sie nicht
     * im alten Netz-Stand hängenbleibt (sonst: Tracker/DHT announcen noch über das alte Netz).
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        connectivityManager = cm
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = scheduleNetworkChange()
            override fun onLost(network: android.net.Network) = scheduleNetworkChange()
            override fun onLinkPropertiesChanged(
                network: android.net.Network, lp: android.net.LinkProperties
            ) = scheduleNetworkChange()
        }
        networkCallback = cb
        try {
            cm.registerDefaultNetworkCallback(cb)
        } catch (e: Exception) {
            Log.w("SeedingService", "NetworkCallback nicht registriert: ${e.message}")
        }
    }

    /** Entprellt Netz-Events (~1,5 s) und löst dann genau einmal das Re-Announce aus. */
    private fun scheduleNetworkChange() {
        // Das allererste Event kommt direkt bei der Registrierung (aktuelles Netz) — ignorieren.
        if (firstNetworkEvent) { firstNetworkEvent = false; return }
        netChangeRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            Log.i("SeedingService", "Netzwechsel erkannt → Sockets neu öffnen + Re-Announce")
            TorrentManager.onNetworkChanged()
        }
        netChangeRunnable = r
        mainHandler.postDelayed(r, 1500)
    }

    private fun unregisterNetworkCallback() {
        netChangeRunnable?.let { mainHandler.removeCallbacks(it) }
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        networkCallback = null
        connectivityManager = null
    }

    /** Lädt die in den Einstellungen gespeicherte (editierbare) Tracker-Liste. */
    private fun loadTrackers(): List<String> {
        val prefs = getSharedPreferences("beam", Context.MODE_PRIVATE)
        // Effektive Liste = manuelle (migriert) ∪ automatisch geladene Top-Tracker (ngosang).
        return TorrentViewModel.combinedTrackers(prefs)
    }

    private fun formatRate(bytesPerSec: Int): String {
        return when {
            bytesPerSec < 1_024 -> "$bytesPerSec B/s"
            bytesPerSec < 1_048_576 -> "%.1f KB/s".format(bytesPerSec / 1_024.0)
            else -> "%.1f MB/s".format(bytesPerSec / 1_048_576.0)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {

            ACTION_ADD_TORRENT -> {
                val torrentPath = intent.getStringExtra(EXTRA_TORRENT_PATH) ?: return START_STICKY
                val fileDir     = intent.getStringExtra(EXTRA_FILE_DIR) ?: return START_STICKY
                val fileName    = intent.getStringExtra(EXTRA_FILE_NAME) ?: "unknown"
                val fileSize    = intent.getLongExtra(EXTRA_FILE_SIZE, 0L)
                val infoHash    = intent.getStringExtra(EXTRA_INFO_HASH) ?: return START_STICKY

                val entry = TorrentEntry(
                    infoHash   = infoHash,
                    fileName   = fileName,
                    fileSize   = fileSize,
                    cachedFile = File(fileDir, fileName),
                    torrentFile= File(torrentPath),
                    isEncrypted = FileCrypto.isEncryptedName(fileName)
                )

                resetTransportBaseline()
                val success = TorrentManager.addAndStart(entry, loadTrackers())
                val count = TorrentManager.activeCount()

                updateNotification(
                    if (success) "⬆ $count active seeds"
                    else "❌ Error: ${fileName}"
                )
            }

            ACTION_ADD_MAGNET -> {
                val magnetUri = intent.getStringExtra(EXTRA_MAGNET_URI) ?: return START_STICKY
                val fileDir   = intent.getStringExtra(EXTRA_FILE_DIR)
                    ?: File(filesDir, "incoming").absolutePath

                resetTransportBaseline()
                val entry = TorrentManager.addAndStartDownload(magnetUri, File(fileDir), loadTrackers())
                updateNotification(
                    if (entry != null) "⬇ Download starting: ${entry.fileName}"
                    else "❌ Invalid link"
                )
            }

            ACTION_PAUSE -> {
                val hash = intent.getStringExtra(EXTRA_INFO_HASH) ?: return START_STICKY
                TorrentManager.pause(hash)
                updateNotification("⏸ ${TorrentManager.activeCount()} active seeds")
            }

            ACTION_RESUME -> {
                val hash = intent.getStringExtra(EXTRA_INFO_HASH) ?: return START_STICKY
                TorrentManager.resume(hash)
                updateNotification("⬆ ${TorrentManager.activeCount()} active seeds")
            }

            ACTION_STOP -> {
                // Mit Hash: einzelnen Torrent stoppen. Ohne Hash: nur neu bewerten (Idle-Check).
                intent.getStringExtra(EXTRA_INFO_HASH)?.let { TorrentManager.stop(it) }
                if (TorrentManager.getAll().isEmpty()) {
                    stopForegroundAndSelf()
                } else {
                    updateAggregateNotification()
                }
            }

            ACTION_RETRY -> {
                // Entschlüsselung mit (korrigierter) Passphrase erneut versuchen.
                val hash = intent.getStringExtra(EXTRA_INFO_HASH) ?: return START_STICKY
                TorrentManager.get(hash)?.let { entry ->
                    entry.errorMessage = null
                    entry.state = TorrentState.DOWNLOADING
                    publishDownload(entry)
                }
            }

            ACTION_RESTART -> {
                // „Start over": Discovery für diesen Transfer frisch aufsetzen (Sockets neu öffnen,
                // Tracker/DHT neu announcen). Hilft, wenn nach Netzwechsel/Backoff nichts mehr connectet.
                val hash = intent.getStringExtra(EXTRA_INFO_HASH) ?: return START_STICKY
                // Blockierend (Hard-Restart bei Empfängern) → eigener Thread.
                Thread { TorrentManager.restartTransfer(hash, loadTrackers()) }.start()
            }

            ACTION_RELAY -> {
                // „Relay NOW!": die Beam-Relay-Station als festen Peer zuschalten (connectPeer). Adresse
                // aus relay.conf (Betreiber-Konfig im App-files-Ordner) oder Default. Beide Enden müssen
                // das tun, damit die Byte-Pipe sie paart.
                val hash = intent.getStringExtra(EXTRA_INFO_HASH) ?: return START_STICKY
                Thread {
                    val ep = de.systragon.beam.core.RelayConfig.parse(
                        runCatching { java.io.File(getExternalFilesDir(null), "relay.conf").takeIf { it.exists() }?.readText() }.getOrNull()
                    )
                    TorrentManager.engageRelay(hash, ep.host, ep.port)
                }.start()
            }
        }

        return START_STICKY
    }

    /** Aktualisiert Download-Status, erkennt Fertigstellung und veröffentlicht die Datei. */
    private fun checkDownloads() {
        TorrentManager.getAll().filter { it.isDownload }.forEach { entry ->
            val handle = entry.handle ?: return@forEach
            if (!handle.isValid) return@forEach

            // Tracker garantiert ans Handle hängen — Magnetlinks enthalten keine, und über
            // verschiedene Netze (Mobilfunk ↔ WLAN) gibt es kein LSD → ohne Tracker findet der
            // Empfänger den Seeder oft nicht. Im WLAN klappte es bisher nur dank lokaler Suche.
            try {
                if (handle.trackers().isEmpty()) {
                    loadTrackers().forEach { handle.addTracker(org.libtorrent4j.AnnounceEntry(it)) }
                    handle.forceReannounce()
                    Log.i("SeedingService", "Tracker nachgetragen für ${entry.fileName}")
                }
            } catch (_: Exception) {}

            val status = handle.status()

            // Metadaten eingetroffen → echten Namen/Größe/Pfad setzen, Zustand wechseln
            if (status.hasMetadata() && entry.state == TorrentState.FETCHING_METADATA) {
                try {
                    val ti = handle.torrentFile()
                    if (ti != null) {
                        val relPath = ti.files().filePath(0)
                        if (ti.numFiles() > 1) {
                            entry.fileName = "${ti.numFiles()} files"
                        } else {
                            entry.fileName = File(relPath).name
                        }
                        entry.fileSize = ti.totalSize()
                        entry.cachedFile = File(handle.savePath(), relPath)
                        entry.isEncrypted = FileCrypto.isEncryptedName(File(relPath).name)
                    }
                } catch (e: Exception) {
                    Log.w("SeedingService", "Metadaten lesen fehlgeschlagen: ${e.message}")
                }
                entry.state = TorrentState.DOWNLOADING
            }

            entry.progress = status.progress()
            entry.downloadRate = status.downloadRate()
            entry.downloadedBytes = status.totalDone()
            entry.currentPeers = status.numPeers()

            // Streambares Video (unverschlüsselt, Einzeldatei): Anfang + Ende vorziehen und
            // melden, sobald beide da sind → der Play-Button wechselt von ⏳ auf ▶.
            if (!entry.isEncrypted &&
                MediaStoreSaver.guessMime(entry.fileName).startsWith("video/")
            ) {
                val ti = handle.torrentFile()
                if (ti != null && ti.numFiles() <= 1) {
                    val numPieces = ti.numPieces()
                    val n = 4
                    if (!entry.streamReady) {
                        try {
                            for (i in 0 until minOf(n, numPieces)) handle.setPieceDeadline(i, 0)
                            for (i in maxOf(0, numPieces - n) until numPieces) handle.setPieceDeadline(i, 0)
                        } catch (_: Exception) {}
                    }
                    val firstOk = (0 until minOf(n, numPieces)).all { handle.havePiece(it) }
                    val lastOk = (maxOf(0, numPieces - n) until numPieces).all { handle.havePiece(it) }
                    entry.streamReady = firstOk && lastOk
                }
            }

            // Fertig → einmalig veröffentlichen
            if (status.isFinished && entry.state != TorrentState.COMPLETED &&
                publishing.add(entry.infoHash)
            ) {
                publishDownload(entry)
            }
        }
    }

    private fun publishDownload(entry: TorrentEntry) {
        Thread {
            try {
                val handle = entry.handle
                val ti = handle?.torrentFile()
                val numFiles = ti?.numFiles() ?: 1

                if (ti != null && numFiles > 1) {
                    // --- Multi-File-Bündel: jede Datei einzeln entschlüsseln + veröffentlichen ---
                    val base = handle.savePath()
                    val pass = loadPassphrase()
                    var anyEncrypted = false
                    var anyNonMedia = false   // nur ERFOLGREICH gespeicherte Nicht-Medien zählen
                    var lastMediaUri: String? = null
                    var lastMediaMime: String? = null
                    var savedCount = 0
                    val files = ti.files()
                    for (i in 0 until numFiles) {
                        // BitTorrent-Padding-Dateien (0 Byte, Block-Ausrichtung) überspringen — sie sind
                        // keine echten Nutzdateien (sonst „Phantom"-.pending-Leichen + falsche Zählung).
                        if (files.padFileAt(i)) continue
                        val rel = files.filePath(i)
                        val f = File(base, rel)
                        var pf = f
                        var pn = File(rel).name
                        if (FileCrypto.isEncryptedName(f.name)) {
                            anyEncrypted = true
                            if (pass.isBlank()) {
                                entry.state = TorrentState.ERROR
                                entry.errorMessage = "Passphrase required"
                                return@Thread
                            }
                            val (rn, plain) = FileCrypto.decrypt(f, pass, f.parentFile!!) { }
                            pf = plain
                            pn = rn
                        }
                        val pub = MediaStoreSaver.publish(applicationContext, pf, pn)
                        // Wahrheit = wohin die Datei tatsächlich ging (Galerie images/video vs. Download).
                        // Fehlgeschlagene Speicherungen (pub == null) ignorieren → kippen „nur Medien" NICHT.
                        val pubStr = pub?.toString() ?: ""
                        val isMedia = pubStr.contains("/images/") || pubStr.contains("/video/")
                        if (pub != null && !isMedia) anyNonMedia = true
                        if (isMedia) { lastMediaUri = pubStr; lastMediaMime = MediaStoreSaver.guessMime(pn) }
                        if (pub != null) savedCount++
                        if (pf != f && pf.exists()) pf.delete()
                    }
                    entry.isEncrypted = anyEncrypted
                    // „Nur Medien", wenn mind. ein Medium gespeichert wurde und KEINE Nicht-Mediendatei
                    // erfolgreich gespeichert wurde (fehlgeschlagene Phantom-Dateien zählen nicht).
                    val mediaOnly = lastMediaUri != null && !anyNonMedia
                    entry.mediaOnly = mediaOnly
                    entry.fileName = "${if (savedCount > 0) savedCount else numFiles} files"
                    // Bei reinem Medien-Bündel auf das zuletzt empfangene Element verweisen
                    // (öffnet direkt die Galerie auf dem neuesten Bild/Video = „Aktuelles").
                    entry.savedUri = if (mediaOnly) lastMediaUri else null
                    entry.galleryMime = lastMediaMime
                    entry.state = TorrentState.COMPLETED
                    entry.progress = 1f
                    val shown = if (savedCount > 0) savedCount else numFiles
                    updateNotification("⬇ Received $shown files")
                    Log.i("SeedingService", "Bündel empfangen: $shown Dateien")
                    return@Thread
                }

                // --- Einzeldatei ---
                var fileToPublish = entry.cachedFile
                var nameToPublish = entry.fileName

                // Verschlüsselte Übertragung → vor dem Veröffentlichen entschlüsseln.
                if (FileCrypto.isEncryptedName(entry.cachedFile.name)) {
                    val pass = loadPassphrase()
                    if (pass.isBlank()) {
                        entry.state = TorrentState.ERROR
                        entry.errorMessage = "Passphrase required"
                        return@Thread   // kein publishing.remove → kein Auto-Retry; manuell via ACTION_RETRY
                    }
                    val (realName, plain) = FileCrypto.decrypt(
                        entry.cachedFile, pass, entry.cachedFile.parentFile!!
                    ) { }
                    fileToPublish = plain
                    nameToPublish = realName
                    entry.fileName = realName   // echten Namen anzeigen, Schloss bleibt
                }

                val singleMime = MediaStoreSaver.guessMime(nameToPublish)
                val uri = MediaStoreSaver.publish(applicationContext, fileToPublish, nameToPublish)
                // Wahrheit = wohin die Datei ging (Galerie images/video) → bestimmt „in Galerie öffnen".
                val singleStr = uri?.toString() ?: ""
                entry.mediaOnly = singleStr.contains("/images/") || singleStr.contains("/video/")
                if (entry.mediaOnly) entry.galleryMime = singleMime
                if (uri != null) {
                    entry.savedUri = uri.toString()
                    entry.state = TorrentState.COMPLETED
                    entry.progress = 1f
                    // Entschlüsseltes Temp entfernen; den .beamenc-Container räumt stopTorrent.
                    if (fileToPublish != entry.cachedFile && fileToPublish.exists()) fileToPublish.delete()
                    notifyDownloadDone(entry, uri)
                    Log.i("SeedingService", "Empfang fertig + gespeichert: ${entry.fileName}")
                } else {
                    entry.state = TorrentState.ERROR
                    entry.errorMessage = "Saving failed"
                    Log.e("SeedingService", "Publish fehlgeschlagen: ${entry.fileName}")
                }
            } catch (e: Exception) {
                entry.state = TorrentState.ERROR
                entry.errorMessage =
                    if (e is AEADBadTagException || e.cause is AEADBadTagException) "Wrong passphrase"
                    else "Decryption failed"
                Log.e("SeedingService", "Entschlüsseln/Publish fehlgeschlagen: ${e.message}")
            }
        }.start()
    }

    /** Lädt die gespeicherte Passphrase (für das Entschlüsseln empfangener Dateien). */
    private fun loadPassphrase(): String {
        return getSharedPreferences("beam", Context.MODE_PRIVATE).getString("passphrase", "") ?: ""
    }

    private fun updateAggregateNotification() {
        val seeds = TorrentManager.activeCount()
        val downloads = TorrentManager.getAll().filter {
            it.state == TorrentState.DOWNLOADING || it.state == TorrentState.FETCHING_METADATA
        }

        if (seeds == 0 && downloads.isEmpty()) {
            updateNotification("Beam! — no active transfers")
            return
        }

        val parts = mutableListOf<String>()
        if (seeds > 0) {
            val totalUp = TorrentManager.getAll()
                .filter { it.state == TorrentState.SEEDING }
                .sumOf { it.uploadRate }
            parts += "⬆ $seeds seeds (${formatRate(totalUp)})"
        }
        if (downloads.isNotEmpty()) {
            val totalDown = downloads.sumOf { it.downloadRate }
            val avg = (downloads.map { it.progress }.average() * 100).toInt()
            parts += "⬇ ${downloads.size} downloading $avg% (${formatRate(totalDown)})"
        }
        updateNotification(parts.joinToString("  |  "))
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseLocks()
        unregisterNetworkCallback()
        // Alles sauber freigeben — kein Müll
        TorrentManager.stopAll()
        Log.i("SeedingService", "Service beendet, alle Ressourcen freigegeben")
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Beam! Seeding", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Active file transfers" }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE_ID, "Beam! Downloads", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Completed downloads" }
        )
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Beam!")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    /** Eigene Notification mit „Öffnen"-Tap für einen fertigen Download. */
    private fun notifyDownloadDone(entry: TorrentEntry, uri: Uri) {
        val mime = MediaStoreSaver.guessMime(entry.fileName)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            this, entry.infoHash.hashCode(), viewIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, CHANNEL_DONE_ID)
            .setContentTitle("Received: ${entry.fileName}")
            .setContentText("Saved — tap to open")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(entry.infoHash.hashCode(), notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
