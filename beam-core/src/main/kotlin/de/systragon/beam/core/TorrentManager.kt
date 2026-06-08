package de.systragon.beam.core

import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.TorrentFlags
import java.io.File

object TorrentManager {

    private const val TAG = "TorrentManager"
    private val session = SessionManager()
    private val torrents = mutableMapOf<String, TorrentEntry>()

    /** Setzt pro Torrent [TorrentEntry.trackerWorking] (mind. ein Tracker-Endpoint „working").
     *  Vom SeedingService alle 5 s getickt; die UI liest es für die granulare Statuszeile. */
    fun refreshTrackerStatus() {
        for (entry in torrents.values) {
            val h = entry.handle?.takeIf { it.isValid }
            if (h == null) { entry.trackerWorking = false; continue }
            var working = false
            try {
                outer@ for (t in h.trackers()) {
                    for (ep in t.endpoints()) {
                        if (ep.infohashV1()?.isWorking == true) { working = true; break@outer }
                    }
                }
            } catch (_: Exception) {}
            entry.trackerWorking = working
        }
    }

    fun startSession() {
        if (!session.isRunning) {
            val settings = org.libtorrent4j.SettingsPack()

            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_dht.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_lsd.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_upnp.swigValue(), true)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_natpmp.swigValue(), true)

            // TCP-FIRST mit µTP als Reserve:
            //  - AUSGEHEND µTP AUS: wir wählen immer per TCP raus (sonst bevorzugt libtorrent µTP,
            //    und µTP/LEDBAT bremst auf WLAN/Mobilfunk). Wird beim hängenden DOWNLOADER per
            //    setOutgoingUtpEnabled zugeschaltet (siehe SeedingService).
            //  - EINGEHEND µTP AN: jeder lauscht dauerhaft auf µTP. So kann ein festhängender
            //    Downloader, der auf µTP eskaliert, in JEDEN Seeder hinein-µTP-en — der Seeder muss
            //    selbst nichts umschalten. Lokal bleibt's TCP (Downloader wählt ausgehend TCP),
            //    also kein Tempoverlust. DHT (UDP) ist davon unabhängig und bleibt aktiv.
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_outgoing_utp.swigValue(), false)
            settings.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_incoming_utp.swigValue(), true)

            // --- µTP/LEDBAT-Tuning: gilt nur, wenn µTP per Fallback zugeschaltet wird ---
            // Standardmäßig gibt µTP bewusst nach (LEDBAT): sobald die Latenz über das Ziel
            // steigt, bremst es → der „Sägezahn" im Durchsatz. Da wir hier *die* Übertragung
            // sind und nicht im Hintergrund nachgeben wollen, drehen wir die Staukontrolle auf.
            // utp_target_delay: mehr Queueing-Latenz tolerieren, bevor µTP zurückregelt (Default 100 ms).
            settings.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.utp_target_delay.swigValue(), 300)
            // utp_gain_factor: wie viele Bytes das Congestion-Window pro RTT wachsen darf
            // (Default 3000) → höher = schnellerer Ramp-up.
            settings.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.utp_gain_factor.swigValue(), 16000)
            // utp_loss_multiplier: wie stark das Fenster bei Paketverlust gekürzt wird
            // (Default 50 %) → kleiner = aggressivere Erholung.
            settings.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.utp_loss_multiplier.swigValue(), 20)

            // ut_metadata explizit aktivieren — damit Peers Metadaten von uns laden können
            settings.setBoolean(
                org.libtorrent4j.swig.settings_pack.bool_types.support_share_mode.swigValue(), true)

            // Anonymen Modus deaktivieren — sonst werden manche Extensions unterdrückt
            settings.setBoolean(
                org.libtorrent4j.swig.settings_pack.bool_types.anonymous_mode.swigValue(), false)

            // User-Agent setzen — manche Clients vertrauen nur bekannten Clients
            settings.setString(
                org.libtorrent4j.swig.settings_pack.string_types.user_agent.swigValue(),
                "libtorrent/2.0.0")

            // IPv4 UND IPv6 lauschen — über Mobilfunk vergeben viele Provider öffentliche IPv6,
            // dann klappt eine direkte Verbindung ohne NAT.
            settings.setString(
                org.libtorrent4j.swig.settings_pack.string_types.listen_interfaces.swigValue(),
                "0.0.0.0:6881,[::]:6881")

            settings.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.connections_limit.swigValue(), 200)
            settings.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.active_seeds.swigValue(), 10)

            // --- Performance: eine einzelne schnelle Verbindung besser ausreizen ---
            // Upload des Senders: größerer Sende-Puffer (Default ~500 KB).
            settings.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.send_buffer_watermark.swigValue(),
                3 * 1024 * 1024)
            settings.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.send_buffer_watermark_factor.swigValue(),
                150)
            // Größere Socket-Puffer (0 = OS-Default).
            settings.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.send_socket_buffer_size.swigValue(),
                1024 * 1024)
            settings.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.recv_socket_buffer_size.swigValue(),
                1024 * 1024)
            // Download: mehr ausstehende Block-Anfragen (wichtig bei höherer Latenz, z. B. Mobilfunk).
            settings.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.max_out_request_queue.swigValue(),
                3000)
            settings.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.request_queue_time.swigValue(), 5)
            // Mehr parallele Disk-I/O-Threads (große Dateien).
            settings.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.aio_threads.swigValue(), 8)
            // Mehr Daten dürfen Richtung Disk in der Warteschlange stehen → glättet den Durchsatz.
            settings.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.max_queued_disk_bytes.swigValue(),
                16 * 1024 * 1024)

            // BitTorrent-Protokollverschlüsselung (MSE/PE) AUS → Klartext-Handshake. Nötig, damit die
            // Beam-Relay-Pipe den Infohash aus dem Handshake lesen und A↔B paaren kann. Der INHALT
            // bleibt geschützt: er hängt an der Passphrase (.beamenc), nicht an MSE.
            settings.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.out_enc_policy.swigValue(),
                org.libtorrent4j.swig.settings_pack.enc_policy.pe_disabled.swigValue())
            settings.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.in_enc_policy.swigValue(),
                org.libtorrent4j.swig.settings_pack.enc_policy.pe_disabled.swigValue())

            // Relay-Rendezvous: Wer sich am Relay anmeldet, bekommt erst einen Handshake zurück, wenn
            // die ZWEITE Seite auch da ist. Ohne langen Timeout verwirft libtorrent die wartende
            // Verbindung nach ~15 s als „toten Peer". Hochsetzen → das Warten am Relay hält ~2 min,
            // bis die andere Seite zuschaltet (CGNAT-Fallback ohne sekundengenaue Synchronisation).
            settings.setInteger(
                org.libtorrent4j.swig.settings_pack.int_types.peer_connect_timeout.swigValue(), 120)

            session.applySettings(settings)
            session.start()

            BeamLog.i(TAG, "Session gestartet mit LSD/UPnP/NAT-PMP/DHT/ut_metadata (MSE aus)")

            Thread {
                Thread.sleep(3000)
                BeamLog.i(TAG, "DHT nodes nach 3s: ${session.stats().dhtNodes()}")
                Thread.sleep(10000)
                BeamLog.i(TAG, "DHT nodes nach 13s: ${session.stats().dhtNodes()}")
            }.start()
        }
    }
    fun addAndStart(entry: TorrentEntry, trackers: List<String>): Boolean {
        if (!session.isRunning) {
            BeamLog.e(TAG, "Session läuft nicht")
            return false
        }
        if (torrents.containsKey(entry.infoHash)) {
            BeamLog.w(TAG, "Torrent bereits vorhanden: ${entry.infoHash}")
            return false
        }

        return try {
            entry.state = TorrentState.HASHING
            torrents[entry.infoHash] = entry

            val torrentInfo = TorrentInfo(entry.torrentFile!!)
            val savePath = entry.cachedFile.parentFile!!

            // seed_mode: Daten liegen bereits vollständig vor — kein erneutes Hashing
            session.download(torrentInfo, savePath, null, null, null, TorrentFlags.SEED_MODE)
            val handle = session.find(torrentInfo.infoHash())
            // Tracker aus den Einstellungen hinzufügen, damit das Handy sich dort anmeldet
            if (handle != null && handle.isValid) {
                trackers.forEach { url ->
                    handle.addTracker(AnnounceEntry(url))
                }

                handle.resume()
                // Extensions explizit aktivieren
                BeamLog.i(TAG, "Is valid: ${handle.isValid}")
                BeamLog.i(TAG, "Has metadata: ${torrentInfo.isValid}")
                entry.handle = handle
                entry.state = TorrentState.SEEDING
                BeamLog.i(TAG, "Seeding gestartet: ${entry.fileName}")

                // Status-Logging im Hintergrund — blockiert nicht den Main Thread
                Thread {
                    repeat(6) { i ->
                        Thread.sleep(5000) // alle 5 Sekunden
                        if (handle.isValid) {
                            val status = handle.status()
                            BeamLog.i(TAG, "=== Status nach ${(i+1)*5}s ===")
                            BeamLog.i(TAG, "State: ${status.state()}")
                            BeamLog.i(TAG, "Peers: ${status.numPeers()}")
                            BeamLog.i(TAG, "Upload: ${status.uploadRate()} B/s")
                            BeamLog.i(TAG, "DHT nodes: ${session.stats().dhtNodes()}")
                            BeamLog.i(TAG, "Total up: ${session.stats().totalUpload()}")
                        }
                    }
                }.start()

                true
            } else {
                entry.state = TorrentState.ERROR
                entry.errorMessage = "Handle ungültig nach Start"
                false
            }
        } catch (e: Exception) {
            entry.state = TorrentState.ERROR
            entry.errorMessage = e.message
            BeamLog.e(TAG, "Fehler: ${e.message}")
            false
        }
    }

    /**
     * Startet einen Download aus einem Magnetlink (Empfang).
     * Anders als [addAndStart] ohne SEED_MODE — die Daten werden ja erst geladen.
     * Tracker werden aus den Einstellungen ergänzt, da unsere Magnetlinks keine `&tr=` enthalten.
     */
    fun addAndStartDownload(magnetUri: String, saveDir: File, trackers: List<String>): TorrentEntry? {
        if (!session.isRunning) {
            BeamLog.e(TAG, "Session läuft nicht")
            return null
        }

        val params = try {
            AddTorrentParams.parseMagnetUri(magnetUri)
        } catch (e: Exception) {
            BeamLog.e(TAG, "Magnetlink nicht parsebar: ${e.message}")
            return null
        }
        val sha1 = params.infoHashes?.best
        if (sha1 == null) {
            BeamLog.e(TAG, "Kein gültiger InfoHash im Magnetlink")
            return null
        }
        val infoHash = sha1.toString().lowercase()
        if (torrents.containsKey(infoHash)) {
            BeamLog.w(TAG, "Torrent bereits vorhanden: $infoHash")
            return torrents[infoHash]
        }

        return try {
            if (!saveDir.exists()) saveDir.mkdirs()

            val displayName = params.name?.takeIf { it.isNotBlank() } ?: infoHash
            val entry = TorrentEntry(
                infoHash = infoHash,
                fileName = displayName,
                fileSize = -1L,
                cachedFile = saveDir,          // Platzhalter — echter Pfad nach Metadaten
                isDownload = true,
                state = TorrentState.FETCHING_METADATA,
                isEncrypted = displayName.endsWith(".beamenc", ignoreCase = true)
            )
            torrents[infoHash] = entry

            // UPDATE_SUBSCRIBE: aktiv, nicht pausiert, nicht auto-managed → lädt sofort.
            session.download(magnetUri, saveDir, TorrentFlags.UPDATE_SUBSCRIBE)

            // Handle erscheint direkt nach download() — kurz warten, dann Tracker ergänzen.
            var handle = session.find(sha1)
            var waited = 0
            while ((handle == null || !handle.isValid) && waited < 2000) {
                Thread.sleep(100)
                waited += 100
                handle = session.find(sha1)
            }

            if (handle != null && handle.isValid) {
                trackers.forEach { url -> handle.addTracker(AnnounceEntry(url)) }
                handle.resume()
                entry.handle = handle
                BeamLog.i(TAG, "Download gestartet: $displayName ($infoHash)")
            } else {
                BeamLog.w(TAG, "Handle nach Download-Start noch ungültig")
            }
            entry
        } catch (e: Exception) {
            BeamLog.e(TAG, "Fehler beim Download-Start: ${e.message}")
            torrents.remove(infoHash)
            null
        }
    }

    /**
     * Bereitet einen Download fürs Streamen vor: sequenzieller Download + erste und letzte Blöcke
     * zuerst (bei MP4 liegt das `moov`-Atom meist am Dateiende). Dazwischen läuft es sequenziell.
     */
    fun enableStreaming(infoHash: String) {
        val handle = torrents[infoHash]?.handle ?: return
        if (!handle.isValid) return
        try {
            // KEIN globales SEQUENTIAL_DOWNLOAD — das drosselt den Gesamtdurchsatz.
            // Nur Anfang + Ende vorziehen (moov bei MP4); die laufende Position priorisiert die
            // Datenquelle (TorrentDataSource) dynamisch per setPieceDeadline. So bleibt der Rest
            // mit voller Geschwindigkeit (rarest-first).
            val ti = handle.torrentFile() ?: return
            val numPieces = ti.numPieces()
            val n = 4
            for (i in 0 until minOf(n, numPieces)) handle.setPieceDeadline(i, 0)
            for (i in maxOf(0, numPieces - n) until numPieces) handle.setPieceDeadline(i, 0)
            BeamLog.i(TAG, "Streaming-Priorisierung gesetzt: $infoHash ($numPieces Blöcke)")
        } catch (e: Exception) {
            BeamLog.e(TAG, "enableStreaming Fehler: ${e.message}")
        }
    }

    /**
     * Nach Netzwechsel (WLAN↔Mobilfunk) oder IP-Wechsel: Listen-Sockets neu öffnen und ALLE
     * Torrents frisch announcen — sonst bleibt die Session im alten Netz-Stand hängen.
     */
    fun onNetworkChanged() {
        if (!session.isRunning) return
        try {
            session.reopenNetworkSockets()
            torrents.values.forEach { e ->
                e.handle?.takeIf { it.isValid }?.let { h ->
                    try { h.forceReannounce() } catch (_: Exception) {}
                }
            }
            BeamLog.i(TAG, "Netzwechsel: Sockets neu geöffnet + Re-Announce (${torrents.size} Torrents)")
        } catch (e: Exception) {
            BeamLog.w(TAG, "onNetworkChanged Fehler: ${e.message}")
        }
    }

    /**
     * „Start over" für genau einen Transfer (manuell aus der Karte). BLOCKIEREND (Sleeps + Metadaten-
     * Wartezeit) → auf einem Hintergrund-Thread aufrufen.
     *
     * - **Empfänger (Download, noch nicht fertig): HARTER Neustart.** Das alte Handle wird verworfen
     *   und der Torrent frisch aus dem Magnet neu hinzugefügt → komplett neue Peer-Discovery. Das ist
     *   das Gegenstück zu „Karte löschen + Link neu öffnen" (genau das half, wenn ein bloßes
     *   Re-Announce nicht reichte — z. B. wenn der Sender erst nachträglich erreichbar wurde und der
     *   Empfänger ihn als tot vermerkt hatte). Teil-Daten bleiben im Zielordner → libtorrent prüft
     *   sie und setzt fort, kein Fortschrittsverlust.
     * - **Seeder / fertig: weiches Re-Announce** (Sockets neu öffnen + announcen), denn dort war
     *   nur die Erreichbarkeit das Thema.
     */
    fun restartTransfer(infoHash: String, trackers: List<String>) {
        val entry = torrents[infoHash] ?: return
        val handle = entry.handle?.takeIf { it.isValid }

        if (entry.isDownload && entry.state != TorrentState.COMPLETED) {
            val saveDir = handle?.let { runCatching { File(it.savePath()) }.getOrNull() }
                ?: entry.cachedFile.takeIf { it.isDirectory }
                ?: entry.cachedFile.parentFile
            if (saveDir == null) {
                BeamLog.w(TAG, "Hard-Restart: kein saveDir für ${entry.fileName}")
                return
            }
            val name = entry.fileName
            try { handle?.let { session.remove(it) } } catch (_: Exception) {}   // ohne DELETE_FILES → Daten bleiben
            torrents.remove(infoHash)
            // libtorrent entfernt asynchron — kurz warten, sonst hängt sich das Re-Add ans sterbende Handle.
            try { Thread.sleep(600) } catch (_: InterruptedException) {}
            try { session.reopenNetworkSockets() } catch (_: Exception) {}
            val dn = runCatching { java.net.URLEncoder.encode(name, "UTF-8") }.getOrDefault(name)
            val magnet = "magnet:?xt=urn:btih:$infoHash&dn=$dn"
            val fresh = addAndStartDownload(magnet, saveDir, trackers)
            BeamLog.i(TAG, "Hard-Restart Download: ${fresh?.fileName ?: name} ($infoHash)")
            return
        }

        if (handle == null) return
        try {
            session.reopenNetworkSockets()
            if (entry.state == TorrentState.PAUSED) {
                handle.resume()
                entry.state = TorrentState.SEEDING
            }
            handle.forceReannounce()
            BeamLog.i(TAG, "Start-over (Seed): Re-Announce für ${entry.fileName}")
        } catch (e: Exception) {
            BeamLog.w(TAG, "restartTransfer Fehler: ${e.message}")
        }
    }

    /**
     * Schaltet nur das **ausgehende** µTP zur Laufzeit an/aus (TCP-first: Default aus, als Fallback
     * an, wenn ein Download keinen Peer findet). Eingehendes µTP bleibt immer an (Reservespur), darum
     * hier nicht angefasst. Beim Einschalten werden Sockets neu geöffnet + neu announced, damit der
     * hängende Transfer sofort einen µTP-Versuch bekommt.
     */
    fun setOutgoingUtpEnabled(enabled: Boolean) {
        if (!session.isRunning) return
        try {
            val s = org.libtorrent4j.SettingsPack()
            s.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_outgoing_utp.swigValue(), enabled)
            session.applySettings(s)
            if (enabled) {
                session.reopenNetworkSockets()
                torrents.values.forEach { e ->
                    e.handle?.takeIf { it.isValid }?.let { h -> try { h.forceReannounce() } catch (_: Exception) {} }
                }
            }
            BeamLog.i(TAG, "Ausgehendes µTP ${if (enabled) "aktiviert (Fallback)" else "aus (TCP-only)"}")
        } catch (e: Exception) {
            BeamLog.w(TAG, "setOutgoingUtpEnabled Fehler: ${e.message}")
        }
    }

    /**
     * IPv6 zur Laufzeit an/aus (steuert `listen_interfaces`). IPv4-only entfernt den IPv6-Listen-Socket
     * → libtorrent baut dann auch KEINE ausgehenden IPv6-Verbindungen mehr auf (es fehlt die v6-Quelle).
     * Zweck: erzwingen, dass eine Übertragung WIRKLICH übers Relay geht, statt heimlich über einen
     * direkten IPv6-Pfad (Mobilfunk-IPv6) — sauberer Relay-Test. Sockets neu öffnen, damit es sofort greift.
     */
    fun setIpv6Enabled(enabled: Boolean) {
        if (!session.isRunning) return
        try {
            val s = org.libtorrent4j.SettingsPack()
            s.setString(
                org.libtorrent4j.swig.settings_pack.string_types.listen_interfaces.swigValue(),
                if (enabled) "0.0.0.0:6881,[::]:6881" else "0.0.0.0:6881")
            session.applySettings(s)
            session.reopenNetworkSockets()
            BeamLog.i(TAG, "IPv6 ${if (enabled) "AN (v4+v6)" else "AUS — nur IPv4 (Relay-Testmodus)"}")
        } catch (e: Exception) {
            BeamLog.w(TAG, "setIpv6Enabled Fehler: ${e.message}")
        }
    }

    fun pause(infoHash: String) {
        torrents[infoHash]?.let { entry ->
            if (entry.state == TorrentState.SEEDING) {
                entry.handle?.pause()
                entry.state = TorrentState.PAUSED
                BeamLog.i(TAG, "Pausiert: ${entry.fileName}")
            }
        }
    }

    fun resume(infoHash: String) {
        torrents[infoHash]?.let { entry ->
            if (entry.state == TorrentState.PAUSED) {
                entry.handle?.resume()
                entry.state = TorrentState.SEEDING
                BeamLog.i(TAG, "Fortgesetzt: ${entry.fileName}")
            }
        }
    }

    fun stop(infoHash: String) {
        torrents[infoHash]?.let { entry ->
            try {
                entry.handle?.let { handle ->
                    if (handle.isValid) session.remove(handle)
                }
                entry.handle = null
                entry.state = TorrentState.STOPPED
                BeamLog.i(TAG, "Gestoppt: ${entry.fileName}")
            } catch (e: Exception) {
                BeamLog.e(TAG, "Fehler beim Stoppen: ${e.message}")
            }
        }
        // Komplett aus der Liste entfernen
        torrents.remove(infoHash)
    }

    fun stopAll() {
        BeamLog.i(TAG, "stopAll — ${torrents.size} Torrents")
        torrents.keys.toList().forEach { stop(it) }
        torrents.clear()
        if (session.isRunning) {
            session.stop()
            BeamLog.i(TAG, "Session gestoppt")
        }
    }

    fun dhtNodes(): Long = if (session.isRunning) session.stats().dhtNodes() else -1L

    fun getAll(): List<TorrentEntry> = torrents.values.toList()

    /**
     * Beam-Relay-Station als festen Peer zuschalten (Fallback bei Timeout oder „Relay NOW!").
     * Die App wählt das Relay direkt an (`connectPeer`) — kein Tracker/DHT nötig. BEIDE Enden müssen
     * das tun, damit die Byte-Pipe sie per Infohash paaren kann. Liefert false, wenn kein Handle (noch).
     */
    /**
     * Beam-Relay-Station zuschalten.
     *  - [exclusive] = true (SENDER, „Relay NOW!"): „wenn Relay, dann richtig" — direkte Wege für DIESEN
     *    Transfer abschalten (DHT/LSD/PEX aus, Tracker weg, bestehende Peers trennen) → nur noch Relay.
     *    NUR EINMAL (idempotent), sonst killt jeder weitere Druck via clear_peers die frische Verbindung.
     *  - [exclusive] = false (EMPFÄNGER, automatisch+still): NUR `connect_peer` aufs Relay als
     *    Parallel-Lauscher — direkt/DHT/Tracker bleiben aktiv. Der Empfänger horcht „immer auch am Relay",
     *    paart sich aber erst, wenn der Sender exklusiv zuschaltet.
     */
    fun engageRelay(infoHash: String, host: String, port: Int, exclusive: Boolean = true): Boolean {
        val entry = torrents[infoHash.lowercase()] ?: return false
        val h = entry.handle?.takeIf { it.isValid } ?: return false
        return try {
            if (exclusive && !entry.relayEngaged) {
                h.setFlags(org.libtorrent4j.TorrentFlags.DISABLE_DHT)
                h.setFlags(org.libtorrent4j.TorrentFlags.DISABLE_LSD)
                h.setFlags(org.libtorrent4j.TorrentFlags.DISABLE_PEX)
                // WICHTIG: NUR den Beam-Station-Tracker behalten (öffentliche raus). Sonst meldet der
                // Sender den Hash nicht mehr an → das Relay-Gate (`knows(hash)`) kennt ihn nicht mehr →
                // die Byte-Pipe WEIST AB. Mit angemeldetem Hash bleibt das Gate zufrieden.
                h.replaceTrackers(listOf(AnnounceEntry(Trackers.BEAM_STATION_TRACKER)))
                h.swig().clear_peers()
                try { h.forceReannounce() } catch (_: Exception) {}   // sofort beim Station-Tracker melden
                entry.relayEngaged = true
                BeamLog.i(TAG, "Relay EXKLUSIV (Sender): $host:$port für $infoHash (Station-Tracker behalten)")
            }
            h.swig().connect_peer(org.libtorrent4j.TcpEndpoint(host, port).swig())
            true
        } catch (e: Exception) {
            BeamLog.e(TAG, "engageRelay fehlgeschlagen: ${e.message}")
            false
        }
    }
    /**
     * Ist die Relay-Station ([host]) gerade als Peer dieses Torrents verbunden (oder im Handshake)?
     * → Anti-Churn fürs Re-Dial: ist die Röhre schon da, NICHT erneut `connect_peer` aufrufen, sonst
     * zerschießt libtorrents Ein-Verbindung-pro-Endpunkt die gerade gepaarte Relay-Verbindung.
     */
    fun isRelayConnected(infoHash: String, host: String): Boolean {
        val h = torrents[infoHash.lowercase()]?.handle?.takeIf { it.isValid } ?: return false
        return try {
            h.peerInfo().any { it.ip().toString().contains(host) }
        } catch (_: Exception) { false }
    }

    fun get(infoHash: String): TorrentEntry? = torrents[infoHash]
    fun activeCount(): Int = torrents.values.count { it.state == TorrentState.SEEDING }
    fun downloadingCount(): Int = torrents.values.count {
        it.state == TorrentState.DOWNLOADING || it.state == TorrentState.FETCHING_METADATA
    }
    /** Seeds + laufende Downloads — Service läuft weiter, solange dies > 0 ist. */
    fun busyCount(): Int = activeCount() + downloadingCount()
}
