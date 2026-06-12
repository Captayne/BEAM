package de.systragon.beam.transfer

import android.app.Application
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import de.systragon.beam.core.TorrentEntry
import de.systragon.beam.core.TorrentManager
import de.systragon.beam.core.Trackers
import de.systragon.beam.core.TorrentState
import de.systragon.beam.media.MediaStoreSaver
import de.systragon.beam.media.VideoCompressor
import de.systragon.beam.service.SeedingService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Fortschritt beim Vorbereiten eines großen Sende-Vorgangs. progress: 0..1, oder -1 = unbestimmt. */
data class PrepareInfo(val fileName: String, val progress: Float, val phase: String = "Preparing")

/** Eine oder mehrere geteilte Dateien, die auf „BEAM! it" warten (Einstellungen vorher änderbar). */
data class PendingSend(val uris: List<Uri>, val label: String)

/** Übersicht der bereitgestellten Auswahl (vor „BEAM! it"): nach Typ UND Quelle aufgeschlüsselt. */
data class PendingSummary(
    val total: Int,
    val photos: Int,
    val videos: Int,
    val others: Int,
    val camera: Int,
    val whatsapp: Int,
    val screenshots: Int,
    val social: Int,
    val otherSource: Int,
    val totalBytes: Long
)

/**
 * Verbindet TorrentManager mit der Compose-UI.
 * Aktualisiert die Liste automatisch jede Sekunde.
 */
class TorrentViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("beam", Context.MODE_PRIVATE)

    // Liste aller aktiven Torrents — UI reagiert automatisch auf Änderungen
    private val _torrents = MutableStateFlow<List<TorrentEntry>>(emptyList())
    val torrents: StateFlow<List<TorrentEntry>> = _torrents

    // Tracker-Liste aus Einstellungen
    private val _trackers = MutableStateFlow(loadTrackers())
    val trackers: StateFlow<String> = _trackers

    // Optionale E2E-Verschlüsselung — Checkbox + Passphrase, dauerhaft gespeichert.
    private val _encryptEnabled = MutableStateFlow(prefs.getBoolean("encrypt_enabled", false))
    val encryptEnabled: StateFlow<Boolean> = _encryptEnabled
    private val _passphrase = MutableStateFlow(prefs.getString("passphrase", "") ?: "")
    val passphrase: StateFlow<String> = _passphrase

    fun setEncryptEnabled(enabled: Boolean) {
        _encryptEnabled.value = enabled
        prefs.edit().putBoolean("encrypt_enabled", enabled).apply()
    }

    fun setPassphrase(value: String) {
        _passphrase.value = value
        prefs.edit().putString("passphrase", value).apply()
    }

    // Chronologie-Modus: markierte Dateien → älteste/neueste = Spanne, alles dazwischen senden.
    // Originale 1:1 → in diesem Modus kein Verschlüsseln/Komprimieren (UI grau). Nicht persistent.
    private val _chronologyMode = MutableStateFlow(false)
    val chronologyMode: StateFlow<Boolean> = _chronologyMode
    fun setChronologyMode(on: Boolean) { _chronologyMode.value = on }

    // Optionales Info-Tag fürs nächste Senden (max BeamLink.TAG_MAX): im .beam-Namen kodiert,
    // beim Empfänger oben auf der Karte angezeigt. Nicht persistent; nach dem Senden geleert.
    private val _sendTag = MutableStateFlow("")
    val sendTag: StateFlow<String> = _sendTag
    fun setSendTag(s: String) { _sendTag.value = s.take(de.systragon.beam.core.BeamLink.TAG_MAX) }
    fun clearSendTag() { _sendTag.value = "" }

    /** True, wenn verschlüsselt werden soll — nicht im Chronologie- oder Direct-File-Access-Modus. */
    fun encryptionActive(): Boolean =
        _encryptEnabled.value && _passphrase.value.isNotBlank() &&
            !_chronologyMode.value && !_directAccess.value

    /** Wirksame Komprimierungsstufe — im Chronologie-/Direct-Modus immer ORIGINAL. */
    fun effectiveCompressionLevel(): VideoCompressor.CompressionLevel =
        if (_chronologyMode.value || _directAccess.value) VideoCompressor.CompressionLevel.ORIGINAL
        else _compressionLevel.value

    // --- Direct File Access (No-Copy): Originale in place seeden statt in den Cache zu kopieren ------
    // Per SENDE-Aktion wählbar (Checkbox auf der Karte, wie Chrono) — NICHT persistent. Originale 1:1
    // → schließt Verschlüsseln/Komprimieren aus.
    private val _directAccess = MutableStateFlow(false)
    val directAccess: StateFlow<Boolean> = _directAccess
    fun setDirectAccess(on: Boolean) { _directAccess.value = on }

    /** True, wenn „Alle Dateien"-Zugriff erteilt ist (für Direkt-Seeden per echtem Pfad). */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** Löst content-URIs in echte Dateipfade auf (MediaStore DATA). Nicht auflösbare → übersprungen. */
    fun resolveToFiles(context: Context, uris: List<Uri>): List<java.io.File> =
        uris.mapNotNull { uri ->
            runCatching {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getColumnIndex(MediaStore.MediaColumns.DATA).let { if (it >= 0) c.getString(it) else null } else null
                }
            }.getOrNull()?.let { java.io.File(it) }
        }

    /**
     * Direkt-Seeden möglich? Nur wenn „Alle Dateien" erteilt UND alle URIs auf echte, lesbare Dateien
     * unter dem primären Speicher (/storage/emulated/0) zeigen (keine SD-Karte/Provider-URIs).
     */
    fun canDirectSeed(context: Context, uris: List<Uri>): Pair<Boolean, List<java.io.File>> {
        if (!_directAccess.value || !hasAllFilesAccess()) return false to emptyList()
        val primary = Environment.getExternalStorageDirectory().absolutePath
        val files = resolveToFiles(context, uris)
        val ok = files.isNotEmpty() && files.size == uris.size &&
            files.all { it.absolutePath.startsWith(primary) && it.isFile && it.canRead() }
        return ok to files
    }

    // Vorbereitung beim Senden großer Dateien (Cache-Kopie + Hashing); null = nichts läuft.
    private val _prepare = MutableStateFlow<PrepareInfo?>(null)
    val prepare: StateFlow<PrepareInfo?> = _prepare

    fun prepareStart(fileName: String, phase: String = "Preparing") {
        _prepare.value = PrepareInfo(fileName, 0f, phase)
    }
    fun prepareProgress(fraction: Float) {
        _prepare.value = _prepare.value?.copy(progress = fraction.coerceIn(0f, 1f))
    }
    fun prepareHashing() { _prepare.value = _prepare.value?.copy(progress = -1f) }
    fun prepareDone() { _prepare.value = null }

    // Gewählte Video-Komprimierungsstufe (dauerhaft gespeichert); gilt für Videos beim Senden.
    private val _compressionLevel = MutableStateFlow(loadCompressionLevel())
    val compressionLevel: StateFlow<VideoCompressor.CompressionLevel> = _compressionLevel

    fun setCompressionLevel(level: VideoCompressor.CompressionLevel) {
        _compressionLevel.value = level
        prefs.edit().putString("comp_level", level.name).apply()
    }

    // Komprimierte Videos zusätzlich in Download/Beam behalten (dauerhaft gespeichert).
    private val _keepCompressed = MutableStateFlow(prefs.getBoolean("keep_compressed", false))
    val keepCompressed: StateFlow<Boolean> = _keepCompressed

    fun setKeepCompressed(enabled: Boolean) {
        _keepCompressed.value = enabled
        prefs.edit().putBoolean("keep_compressed", enabled).apply()
    }

    // Sekunden ohne Peer, nach denen ausgehendes µTP als Fallback zugeschaltet wird (3–60).
    private val _utpFallbackSeconds = MutableStateFlow(
        prefs.getInt(PREF_UTP_FALLBACK_SECONDS, UTP_FALLBACK_DEFAULT)
    )
    val utpFallbackSeconds: StateFlow<Int> = _utpFallbackSeconds

    fun setUtpFallbackSeconds(value: Int) {
        val v = value.coerceIn(UTP_FALLBACK_MIN, UTP_FALLBACK_MAX)
        _utpFallbackSeconds.value = v
        prefs.edit().putInt(PREF_UTP_FALLBACK_SECONDS, v).apply()
    }

    private fun loadCompressionLevel(): VideoCompressor.CompressionLevel =
        runCatching {
            VideoCompressor.CompressionLevel.valueOf(prefs.getString("comp_level", "ORIGINAL")!!)
        }.getOrDefault(VideoCompressor.CompressionLevel.ORIGINAL)

    // Geteilte Datei, die auf den „BEAM! it"-Knopf wartet; null = nichts bereitgestellt.
    private val _pendingSend = MutableStateFlow<PendingSend?>(null)
    val pendingSend: StateFlow<PendingSend?> = _pendingSend

    fun setPendingSend(pending: PendingSend) { _pendingSend.value = pending }
    fun clearPendingSend() { _pendingSend.value = null; _pendingSummary.value = null }

    // Übersicht der bereitgestellten Auswahl (für die „Was wird gesendet?"-Karte vor BEAM! it).
    private val _pendingSummary = MutableStateFlow<PendingSummary?>(null)
    val pendingSummary: StateFlow<PendingSummary?> = _pendingSummary
    // Im Chronologie-Modus: die aufgefüllte Datei-Liste (älteste→neueste, alles dazwischen).
    @Volatile private var _chronologyUris: List<Uri>? = null

    /** Berechnet (im Hintergrund) die Aufschlüsselung — im Chronologie-Modus über die aufgefüllte Spanne. */
    fun refreshPendingSummary(context: Context, pending: PendingSend?) {
        if (pending == null) { _pendingSummary.value = null; _chronologyUris = null; return }
        _pendingSummary.value = null   // sofort „Scanning…" zeigen, nie veraltete (invertiert wirkende) Zahlen
        viewModelScope.launch(Dispatchers.IO) {
            val uris = if (_chronologyMode.value) {
                val expanded = runCatching { expandChronology(context, pending.uris) }.getOrDefault(pending.uris)
                _chronologyUris = expanded
                expanded
            } else {
                _chronologyUris = null
                pending.uris
            }
            _pendingSummary.value = runCatching { computeSummary(context, uris) }.getOrNull()
        }
    }

    /**
     * Chronologie-Expansion: aus den markierten Ankern älteste/neueste Zeit bestimmen und ALLE
     * Medien (Bilder+Videos) dazwischen aus dem MediaStore holen. Zeit = DATE_TAKEN (ms), sonst
     * DATE_MODIFIED (s); Bereichs-Query über DATE_MODIFIED (s) → erfasst auch WhatsApp/Social ohne
     * EXIF. Braucht Medien-Lese-Berechtigung; ohne sie (oder leer) → Anker zurückgeben.
     */
    fun expandChronology(context: Context, anchors: List<Uri>): List<Uri> {
        val cr = context.contentResolver
        val times = anchors.mapNotNull { anchorMillis(cr, it) }
        if (times.isEmpty()) { Log.i("Beam", "chrono: keine Anker-Zeit auflösbar (${anchors.size} Anker) → nur Anker"); return anchors }
        // ±1 s Polster; Fenster IN MILLIS, gleiches „effektives" Zeitkonzept wie unten beim Filtern.
        val minMs = times.min() - 1000L
        val maxMs = times.max() + 1000L
        val hits = ArrayList<Pair<Long, Uri>>()   // (effektive Zeit, Uri) → am Ende chronologisch sortieren
        // Wichtig: NICHT in SQL auf DATE_MODIFIED filtern — DATE_TAKEN (Aufnahme) und DATE_MODIFIED
        // (Änderung/Kopie aufs Handy) können weit auseinanderliegen. Wir holen beide Spalten und
        // berechnen pro Datei die effektive Zeit (taken, sonst modified*1000) konsistent zu anchorMillis.
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED)
        for (col in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            runCatching {
                cr.query(col, cols, null, null, null)?.use { c ->
                    val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val takenIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    val modIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    while (c.moveToNext()) {
                        val taken = if (takenIdx >= 0 && !c.isNull(takenIdx)) c.getLong(takenIdx) else 0L
                        val eff = when {
                            taken > 0 -> taken
                            modIdx >= 0 && !c.isNull(modIdx) -> c.getLong(modIdx) * 1000L
                            else -> continue
                        }
                        if (eff in minMs..maxMs) hits += eff to ContentUris.withAppendedId(col, c.getLong(idIdx))
                    }
                }
            }.onFailure { Log.w("Beam", "expandChronology query: ${it.message}") }
        }
        val out = hits.sortedBy { it.first }.map { it.second }
        Log.i("Beam", "chrono: ${anchors.size} Anker, Fenster ${minMs}..${maxMs} ms → ${out.size} Dateien")
        return if (out.isEmpty()) anchors else out
    }

    private fun anchorMillis(cr: ContentResolver, uri: Uri): Long? = runCatching {
        cr.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val takenIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            val modIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val taken = if (takenIdx >= 0 && !c.isNull(takenIdx)) c.getLong(takenIdx) else 0L
            when {
                taken > 0 -> taken
                modIdx >= 0 && !c.isNull(modIdx) -> c.getLong(modIdx) * 1000L
                else -> null
            }
        }
    }.getOrNull()

    /** Im Chronologie-Modus die aufgefüllte Sende-Liste (oder null, wenn nicht berechnet). */
    fun chronologySendUris(): List<Uri>? = _chronologyUris

    private val WA_NAME = Regex("(?i)^(IMG|VID)-\\d{8}-WA\\d+")

    private fun computeSummary(context: Context, uris: List<Uri>): PendingSummary {
        var photos = 0; var videos = 0; var others = 0
        var camera = 0; var whatsapp = 0; var screenshots = 0; var social = 0; var otherSource = 0
        var bytes = 0L
        val cr = context.contentResolver
        for (uri in uris) {
            var name = ""; var size = 0L
            runCatching {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).let { if (it >= 0) name = c.getString(it) ?: "" }
                        c.getColumnIndex(OpenableColumns.SIZE).let { if (it >= 0) size = c.getLong(it) }
                    }
                }
            }
            var relPath = ""
            runCatching {
                cr.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH).let { if (it >= 0) relPath = c.getString(it) ?: "" }
                    }
                }
            }
            val mime = cr.getType(uri) ?: MediaStoreSaver.guessMime(name)
            when {
                mime.startsWith("image/") -> photos++
                mime.startsWith("video/") -> videos++
                else -> others++
            }
            if (size > 0) bytes += size
            val hay = "$relPath/$name".lowercase()
            when {
                hay.contains("screenshot") -> screenshots++
                hay.contains("whatsapp") || WA_NAME.containsMatchIn(name) -> whatsapp++
                hay.contains("instagram") || hay.contains("telegram") || hay.contains("snapchat") ||
                    hay.contains("/facebook") || hay.contains("messenger") || hay.contains("signal") -> social++
                hay.contains("dcim") || hay.contains("/camera") -> camera++
                else -> otherSource++
            }
        }
        return PendingSummary(uris.size, photos, videos, others, camera, whatsapp, screenshots, social, otherSource, bytes)
    }

    init {
        refreshAutoTrackers()
        viewModelScope.launch {
            while (true) {
                // Status für jeden aktiven Torrent aktualisieren
                TorrentManager.getAll().forEach { entry ->
                    val handle = entry.handle ?: return@forEach
                    if (!handle.isValid) return@forEach
                    val status = handle.status()
                    // „Direkt blockiert"-Erkennung für die Relay-Empfehlung — NUR beim SENDER:
                    // Gegenüber bekannt (listPeers>0), aber keine Verbindung (numPeers==0) seit N s.
                    // Der EMPFÄNGER lauscht ohnehin automatisch am Relay (Re-Dial im SeedingService) →
                    // bei ihm KEIN Button/Hinweis (User-Entscheid: nur der Sender entscheidet).
                    val nowMs = System.currentTimeMillis()
                    if (status.listPeers() > 0 && entry.peerSeenAt == 0L) entry.peerSeenAt = nowMs
                    entry.directBlocked = !entry.isDownload && status.numPeers() == 0 && status.listPeers() > 0 &&
                        entry.peerSeenAt > 0L && nowMs - entry.peerSeenAt > 25_000L &&  // ~Geduld; später konfigurierbar
                        entry.state != TorrentState.COMPLETED
                    when (entry.state) {
                        TorrentState.SEEDING -> {
                            entry.uploadedBytes = status.totalUpload()
                            entry.currentPeers = status.numPeers()
                            entry.uploadRate = status.uploadRate()
                        }
                        TorrentState.FETCHING_METADATA, TorrentState.DOWNLOADING -> {
                            // Übergang META → DL hier UND im Service (idempotent) — so hängt die
                            // Anzeige nicht am 5-s-Service-Tick fest.
                            if (entry.state == TorrentState.FETCHING_METADATA && status.hasMetadata()) {
                                try {
                                    handle.torrentFile()?.let { ti ->
                                        val relPath = ti.files().filePath(0)
                                        entry.fileName = java.io.File(relPath).name
                                        entry.fileSize = ti.totalSize()
                                        entry.cachedFile = java.io.File(handle.savePath(), relPath)
                                    }
                                } catch (_: Exception) {}
                                entry.state = TorrentState.DOWNLOADING
                            }
                            entry.progress = status.progress()
                            entry.downloadRate = status.downloadRate()
                            entry.downloadedBytes = status.totalDone()
                            entry.currentPeers = status.numPeers()
                        }
                        else -> {}
                    }
                }
                // Unveränderliche Kopien (Snapshots) ausgeben — sonst dedupliziert StateFlow,
                // weil die mutierten Originalobjekte per equals „gleich" bleiben (kein Update).
                _torrents.value = TorrentManager.getAll()
                    .sortedByDescending { it.createdAt }   // neueste Karte zuoberst
                    .map { it.copy() }
                delay(1000)
            }
        }
    }
    fun updateTrackers(newTrackers: String) {
        _trackers.value = newTrackers
        prefs.edit().putString("trackers", newTrackers).apply()
    }

    fun getTrackerList(): List<String> = combinedTrackers(prefs)

    /**
     * Lädt beim Start die aktuell zuverlässigsten Tracker (Top 10 aus ngosang/trackerslist) und legt
     * sie in den Prefs ab (`auto_trackers`, wird bei jedem Start ersetzt → veraltet nicht). Die
     * effektive Liste = manuelle Tracker ∪ Auto-Tracker (siehe [combinedTrackers]). Fehlschlag ist
     * unkritisch — dann gelten nur die manuellen.
     */
    private fun refreshAutoTrackers() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val url = java.net.URL("https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val top = parseTrackers(text).take(10)
                if (top.isNotEmpty()) {
                    prefs.edit().putString("auto_trackers", top.joinToString("\n")).apply()
                }
            }
        }
    }

    fun copyMagnetLink(context: Context, entry: TorrentEntry) {
        val magnet = buildMagnetLink(entry.infoHash, entry.fileName)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Magnetlink", magnet))
    }

    fun buildMagnetLink(infoHash: String, fileName: String): String {
        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8")
        return "magnet:?xt=urn:btih:$infoHash&dn=$encodedName"
        // Keine &tr= nötig — Empfänger hat die gleiche App mit gleichen Trackern
    }

    /**
     * Teilt diesen Torrent erneut: schreibt eine `<name>.<hash>.beam`-Datei (Hash steckt im
     * Namen, Inhalt = Magnetlink als Fallback) und öffnet das System-Share-Sheet — damit lässt
     * sich genau dieser Torrent nochmal in Messenger/Social Media verschicken. Gleiches Format
     * und gleicher Cache-Ordner wie der ursprüngliche Sende-Weg (siehe FileProvider `beamlinks/`).
     */
    fun shareTorrentLink(context: Context, entry: TorrentEntry) {
        val magnet = buildMagnetLink(entry.infoHash, entry.fileName)
        val linksDir = java.io.File(context.cacheDir, "beamlinks").apply { mkdirs() }
        // Peer-Hint (aktuelle LAN-/Hotspot-IP) nur in den Dateinamen, nicht in den Magnet-Inhalt.
        val hint = de.systragon.beam.core.PeerHint.localSegment()
        val base = if (hint != null) "${entry.fileName}.${entry.infoHash}.$hint"
                   else "${entry.fileName}.${entry.infoHash}"
        val beamFile = java.io.File(linksDir, "$base.beam")
        beamFile.writeText(magnet)

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", beamFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Beam!: ${entry.fileName}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Vom Application-Context aus gestartet → NEW_TASK nötig.
        val chooser = Intent.createChooser(shareIntent, "Share Beam file via…")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /**
     * Nimmt einen Link aus der Zwischenablage auf (Magnetlink oder unsere .beam-Kurzform) und
     * startet den Download. Gibt false zurück, wenn nichts Verwertbares gefunden wurde.
     */
    fun pasteLinkFromClipboard(context: Context): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()

        val magnet = text?.let { parseClipboardToMagnet(it) }
        if (magnet == null) {
            Toast.makeText(context, "No Beam link in clipboard", Toast.LENGTH_SHORT).show()
            return false
        }

        startDownloadFromMagnet(context, magnet)
        Toast.makeText(context, "Link accepted – downloading", Toast.LENGTH_SHORT).show()
        return true
    }

    /** Liberaler Parser: erkennt Magnetlink, rohen 40-Hex-Hash oder .beam-Kurzform (Hash + Name). */
    private fun parseClipboardToMagnet(raw: String): String? {
        val t = raw.trim()
        // 1) Vollständiger Magnetlink irgendwo im Text
        Regex("magnet:\\?\\S+", RegexOption.IGNORE_CASE).find(t)?.let { return it.value }
        // 2) 40-stelliger InfoHash irgendwo im Text
        val hash = Regex("[A-Fa-f0-9]{40}").find(t)?.value ?: return null
        // 3) Name aus dem Rest ableiten (z. B. "Name.HASH.beam" oder "HASH-Name.beam")
        val name = t.replace(hash, " ", ignoreCase = true)
            .replace(".beam", " ", ignoreCase = true)
            .trim(' ', '.', '-', '_')
        return buildMagnetLink(hash.lowercase(), name)
    }

    /** Bereitet einen laufenden Download fürs Streamen vor (sequenziell + Anfang/Ende zuerst). */
    fun startStreaming(infoHash: String) {
        TorrentManager.enableStreaming(infoHash)
    }

    /** Entschlüsselung mit der aktuellen Passphrase erneut versuchen (nach falscher Eingabe). */
    fun retryDecrypt(context: Context, infoHash: String) {
        try {
            context.startService(Intent(context, SeedingService::class.java).apply {
                action = SeedingService.ACTION_RETRY
                putExtra(SeedingService.EXTRA_INFO_HASH, infoHash)
            })
        } catch (_: Exception) {}
    }

    /** „Start over": Discovery für diesen Transfer frisch aufsetzen (Sockets/Tracker/DHT neu). */
    fun restartTransfer(context: Context, infoHash: String) {
        try {
            context.startService(Intent(context, SeedingService::class.java).apply {
                action = SeedingService.ACTION_RESTART
                putExtra(SeedingService.EXTRA_INFO_HASH, infoHash)
            })
        } catch (_: Exception) {}
    }

    /** „Relay NOW!": die Beam-Relay-Station für diesen Transfer sofort zuschalten (Fallback-Zwilling). */
    fun engageRelay(context: Context, infoHash: String) {
        try {
            context.startService(Intent(context, SeedingService::class.java).apply {
                action = SeedingService.ACTION_RELAY
                putExtra(SeedingService.EXTRA_INFO_HASH, infoHash)
            })
        } catch (_: Exception) {}
    }

    private fun startDownloadFromMagnet(context: Context, magnet: String) {
        val saveDir = java.io.File(context.filesDir, "incoming").apply { mkdirs() }
        val intent = Intent(context, SeedingService::class.java).apply {
            action = SeedingService.ACTION_ADD_MAGNET
            putExtra(SeedingService.EXTRA_MAGNET_URI, magnet)
            putExtra(SeedingService.EXTRA_FILE_DIR, saveDir.absolutePath)
        }
        context.startForegroundService(intent)
    }

    /** Öffnet eine fertig empfangene Datei mit der passenden App. */
    fun openFile(context: Context, entry: TorrentEntry) {
        val uriStr = entry.savedUri ?: return
        val mime = MediaStoreSaver.guessMime(entry.fileName)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uriStr), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    /** Öffnet (best-effort) den Speicherort, passend zum Inhalt — zeigt NIE eine Datei mit einer
     *  verknüpften App (kein „Öffnen mit"-Dialog), sondern immer nur eine Übersicht:
     *  - Medien (Einzel & Bündel) → die Galerie-Übersicht/„Recent" (Vorschau-Icons, neueste oben);
     *    der Nutzer wählt selbst aus.
     *  - Dokumente/gemischt → das VERZEICHNIS (mit SAF-Berechtigung Download/Beam, sonst die
     *    System-Downloads-Ansicht). */
    fun openFolder(context: Context, entry: TorrentEntry) {
        if (entry.mediaOnly) {
            // Bevorzugt die GERÄTE-Galerie öffnen — sie zeigt die lokal gespeicherten Dateien sofort
            // als Vorschau-Icons (neueste oben). Google Photos zeigt frisch Empfangenes ggf. nicht
            // (nicht in die Cloud gesynct), daher gezielt die Hersteller-Galerie zuerst.
            val deviceGalleries = listOf(
                "com.sec.android.gallery3d",    // Samsung Galerie
                "com.samsung.android.gallery",  // Samsung (alternativer Paketname)
                "com.miui.gallery",             // Xiaomi
                "com.huawei.photos",            // Huawei
                "com.coloros.gallery3d"         // Oppo/Realme
            )
            for (pkg in deviceGalleries) {
                val li = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (tryStart(context, li)) return
            }
            // Sonst die als Standard registrierte Galerie (kann je nach Gerät Google Photos sein).
            if (tryStart(context, Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_APP_GALLERY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ) return
            // Letzter Fallback: das neueste Element direkt.
            val mediaUri = entry.savedUri
            if (mediaUri != null && tryStart(context,
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(mediaUri), entry.galleryMime ?: "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
            ) return
        }
        // Dokumente/gemischt: NUR das Verzeichnis zeigen — nie die Datei selbst öffnen (kein
        // „Öffnen mit"-Dialog). Mit SAF-Berechtigung der echte Download/Beam-Ordner, sonst die
        // System-Downloads-Ansicht (beide ohne App-Auswahl und ohne eine Datei zu öffnen).
        if (openBeamFolderTree(context)) return
        if (tryStart(context, Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ) return
        Toast.makeText(context, "Saved to Download/Beam", Toast.LENGTH_SHORT).show()
    }

    /**
     * Räumt Temp-/Cache-Reste auf: alles im App-Cache und im Empfangs-Cache (filesDir/incoming),
     * das NICHT zu einem aktuell gelisteten Transfer gehört, plus verwaiste MediaStore-`.pending`-
     * Einträge. Laufende/sichtbare Übertragungen bleiben unangetastet.
     * Gibt (Anzahl gelöschter Einträge, freigegebene Bytes) zurück. Auf Background-Thread aufrufen.
     */
    fun cleanupTempAndLogs(context: Context): Pair<Int, Long> {
        // Pfade, die zu einem gelisteten Transfer gehören → nicht anfassen (auch fertige Karten).
        val inUse = HashSet<String>()
        for (e in TorrentManager.getAll()) {
            inUse.add(e.cachedFile.absolutePath)
            e.torrentFile?.let { inUse.add(it.absolutePath) }
        }
        fun protects(f: java.io.File): Boolean = inUse.any {
            it == f.absolutePath || it.startsWith(f.absolutePath + java.io.File.separator)
        }
        fun sizeOf(f: java.io.File): Long =
            if (f.isDirectory) (f.listFiles()?.sumOf { sizeOf(it) } ?: 0L) else f.length()

        var count = 0
        var bytes = 0L
        val roots = listOf(context.cacheDir, java.io.File(context.filesDir, "incoming"))
        for (root in roots) {
            root.listFiles()?.forEach { f ->
                if (protects(f)) return@forEach
                val sz = sizeOf(f)
                if (f.deleteRecursively()) { count++; bytes += sz }
            }
        }
        count += MediaStoreSaver.cleanupOrphanPending(context)   // verwaiste .pending-Reste
        return count to bytes
    }

    /** Startet ein Intent best-effort; gibt false zurück, wenn keine App es behandeln kann. */
    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    // --- SAF: einmalige Ordner-Berechtigung für Download/Beam ----------------------------------
    private fun safPrefs(context: Context) =
        context.getSharedPreferences("beam_saf", Context.MODE_PRIVATE)

    /** Vorab-URI, die den SAF-Ordnerwähler direkt auf Download/Beam navigiert. */
    fun beamFolderInitialUri(): Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:${android.os.Environment.DIRECTORY_DOWNLOADS}/${MediaStoreSaver.SUBDIR}"
    )

    /** True, wenn der Nutzer Download/Beam schon einmal freigegeben hat (Berechtigung noch gültig). */
    fun hasBeamFolderAccess(context: Context): Boolean {
        val uriStr = safPrefs(context).getString("tree", null) ?: return false
        val uri = Uri.parse(uriStr)
        return context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }

    /** Merkt sich die vom Nutzer freigegebene Ordner-URI dauerhaft. */
    fun saveBeamFolderAccess(context: Context, treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) { Log.w("Beam", "takePersistable failed: ${e.message}") }
        safPrefs(context).edit().putString("tree", treeUri.toString()).apply()
    }

    /** Öffnet den freigegebenen Ordner im Dateimanager. False, wenn keine gültige Berechtigung. */
    fun openBeamFolderTree(context: Context): Boolean {
        if (!hasBeamFolderAccess(context)) return false
        val tree = Uri.parse(safPrefs(context).getString("tree", null) ?: return false)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        return tryStart(context, Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** „Throw": stoppt und löscht ALLES — Karte, Temp-Datei und die gespeicherte Datei. */
    fun throwTransfer(context: Context, infoHash: String) =
        removeTransfer(context, infoHash, deleteSaved = true)

    /** „X": entfernt nur die Karte (stoppt + räumt Temp auf), behält die gespeicherte Datei. */
    fun dismissTransfer(context: Context, infoHash: String) =
        removeTransfer(context, infoHash, deleteSaved = false)

    private fun removeTransfer(context: Context, infoHash: String, deleteSaved: Boolean) {
        val entry = TorrentManager.get(infoHash)
        // Sofort aus der UI entfernen — kein Warten auf den Service.
        _torrents.value = _torrents.value.filterNot { it.infoHash == infoHash }

        viewModelScope.launch(Dispatchers.IO) {
            TorrentManager.stop(infoHash)   // entfernt aus Session + Map (idempotent)
            entry?.let {
                // App-interne Temp-/Torrent-Dateien immer aufräumen (kein Müll).
                if (it.cachedFile.exists()) it.cachedFile.delete()
                it.torrentFile?.let { tf -> if (tf.exists()) tf.delete() }
                // Nur beim „Throw": ALLE bereits gespeicherten Dateien löschen (Bündel = viele URIs,
                // nicht nur die repräsentative). savedUri als Fallback für ältere Einträge.
                if (deleteSaved) {
                    val uris = (it.savedUris + listOfNotNull(it.savedUri)).distinct()
                    var deleted = 0
                    uris.forEach { uriStr ->
                        try {
                            deleted += context.contentResolver.delete(Uri.parse(uriStr), null, null)
                        } catch (_: Exception) {}
                    }
                    Log.i("TorrentViewModel", "Throw: $deleted/${uris.size} gespeicherte Dateien gelöscht")
                }
            }
            _torrents.value = TorrentManager.getAll()
                .sortedByDescending { it.state == TorrentState.SEEDING || it.state == TorrentState.DOWNLOADING }
                .map { it.copy() }

            // Service neu bewerten (Notification aktualisieren / bei leerer Liste beenden)
            try {
                context.startService(Intent(context, SeedingService::class.java).apply {
                    action = SeedingService.ACTION_STOP   // ohne EXTRA_INFO_HASH → nur Idle-Check
                })
            } catch (_: Exception) {}
        }
    }

    private fun loadTrackers(): String = loadAndMigrateTrackers(prefs)

    companion object {
        // µTP-Fallback: Sekunden ohne Peer bis ausgehendes µTP zugeschaltet wird (einstellbar in Settings).
        const val PREF_UTP_FALLBACK_SECONDS = "utp_fallback_seconds"
        const val UTP_FALLBACK_DEFAULT = 15
        const val UTP_FALLBACK_MIN = 3
        const val UTP_FALLBACK_MAX = 60

        // Tracker-Logik liegt plattformneutral in beam-core (Trackers). Hier nur dünne Delegates
        // mit SharedPreferences-Adapter, damit App-Aufrufer (ViewModel, SeedingService) unverändert
        // bleiben.
        fun parseTrackers(raw: String): List<String> = Trackers.parseTrackers(raw)

        fun combinedTrackers(prefs: android.content.SharedPreferences): List<String> =
            Trackers.combinedTrackers(SharedPrefsStore(prefs))

        fun loadAndMigrateTrackers(prefs: android.content.SharedPreferences): String =
            Trackers.loadAndMigrateTrackers(SharedPrefsStore(prefs))
    }
}
