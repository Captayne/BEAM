package de.systragon.beam.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import de.systragon.beam.core.*
import de.systragon.beam.media.FileCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.File

/** Log-Datei (die installierte App hat keine Konsole) → für Diagnose bei Abstürzen.
 *  `lazy`, da `downloadDir` weiter unten deklariert ist (Init-Reihenfolge). */
val beamLogFile: File by lazy {
    File(System.getProperty("user.home"), "Downloads/Beam").apply { mkdirs() }.let { File(it, "beam.log") }
}
fun beamLog(s: String) {
    val line = "${java.time.LocalTime.now()} $s"
    runCatching { beamLogFile.appendText(line + System.lineSeparator()) }
    println(line)
}

fun main(args: Array<String>) {
    // Alle BeamLog-Ausgaben UND unbehandelte Exceptions in eine Datei schreiben (Diagnose).
    runCatching { if (beamLogFile.exists() && beamLogFile.length() > 2_000_000) beamLogFile.delete() }
    beamLog("=== Beam $BEAM_VERSION start ===")
    BeamLog.sink = { level, tag, msg -> beamLog("[$level] $tag: $msg") }
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        beamLog("!!! UNCAUGHT in thread '${t.name}': ${e.stackTraceToString()}")
    }
    ensureNativeLib() // vor dem ersten libtorrent-Zugriff!
    registerSendTo()  // „Senden an → Beam!" bei jedem Start sicherstellen (frische Installationen!)

    // „Senden an → BEAM!" übergibt eine oder mehrere markierte Dateien als Argumente.
    val initialPaths = args.filter { it.isNotBlank() }
    application {
        // Gleiches Icon wie die Android-App (liegt als Classpath-Ressource beam.png).
        val beamIcon = remember { useResource("beam.png") { BitmapPainter(loadImageBitmap(it)) } }
        // Kompakte „kleine, aber leistungsstarke" Box: moderate Standardgröße statt riesigem Fenster
        // (auf 4K-Monitoren sonst übergroß). Bleibt frei skalierbar.
        val winState = androidx.compose.ui.window.rememberWindowState(
            width = 500.dp, height = 720.dp,
            position = androidx.compose.ui.window.WindowPosition(androidx.compose.ui.Alignment.Center)
        )
        Window(onCloseRequest = ::exitApplication, state = winState, title = "Beam $BEAM_VERSION", icon = beamIcon) {
            // Beam-Fenster nach vorn holen (Windows hält es sonst im Hintergrund, Fokus-Klau-Schutz).
            // Genutzt beim Start mit Datei-Argument UND bei Download-Abschluss („Done" sichtbar machen,
            // falls Beam hinter dem Explorer-Fenster verborgen war).
            val winScope = rememberCoroutineScope()
            val bringToFront: () -> Unit = {
                winScope.launch {
                    // Windows blockt „nach vorn" für Hintergrund-Apps. Trick: kurz als Topmost setzen
                    // (Z-Order erlaubt Windows) und erst nach ~1,2 s wieder lösen → kommt sichtbar nach vorn.
                    window.toFront()
                    window.isAlwaysOnTop = true
                    window.requestFocus()
                    kotlinx.coroutines.delay(1200)
                    window.isAlwaysOnTop = false
                }
                Unit
            }
            if (initialPaths.isNotEmpty()) {
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(400); bringToFront() }
            }
            // Kompaktere UI: Density runterskalieren → Schrift UND Abstände gleichmäßig kleiner.
            // Compose erbt sonst die (auf großen 4K-Monitoren oft hohe) Windows-Skalierung. Faktor
            // UI_SCALE justierbar (1.0 = Windows-Standard; kleiner = kompakter).
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit.Density(base.density * UI_SCALE, base.fontScale)
            ) {
                BeamApp(initialPaths, bringToFront)
            }
        }
    }
}

/** Versionsnummer (vom Build via -Dbeam.version gesetzt; sonst "dev"). Für die Titelzeile. */
private val BEAM_VERSION: String = System.getProperty("beam.version") ?: "dev"

/** Globaler UI-Skalierungsfaktor (auf die geerbte Windows-Density). <1 = kompakter (Schrift+Abstände).
 *  Für große, hoch skalierte 4K-Monitore. Hier zentral justierbar. */
private const val UI_SCALE = 0.8f

/**
 * Legt – nur im installierten Windows-Build – die „Senden an → Beam!"-Verknüpfung im SendTo-Ordner des
 * Nutzers an (falls sie fehlt). Damit funktioniert Mehrfach-Auswahl → EIN Beam mit ALLEN Dateien (statt
 * „Öffnen mit" = eine Instanz pro Datei) auf JEDER Installation. jpackage liefert den eigenen exe-Pfad
 * via System-Property `jpackage.app-path` (im Dev-Run null → übersprungen).
 */
private fun registerSendTo() {
    runCatching {
        val appPath = System.getProperty("jpackage.app-path") ?: return   // nur installierter Build
        val exe = File(appPath)
        if (!exe.exists()) return
        val p = exe.absolutePath.replace("'", "''")                       // PowerShell-Single-Quote-Escape
        val cmd = "\$l=Join-Path ([Environment]::GetFolderPath('SendTo')) 'Beam!.lnk'; " +
            "if(-not(Test-Path \$l)){\$s=(New-Object -ComObject WScript.Shell).CreateShortcut(\$l);" +
            "\$s.TargetPath='$p';\$s.IconLocation='$p,0';\$s.Description='Send file(s) to Beam!';\$s.Save()}"
        ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", cmd)
            .redirectErrorStream(true).start().waitFor()
        beamLog("SendTo: 'Beam!' geprüft/angelegt (exe=$appPath)")
    }.onFailure { beamLog("SendTo-Registrierung fehlgeschlagen: ${it.message}") }
}

/** Registriert die eigene IP token-gated als SENDER für [hash] bei der Station (Rollen-Signal der Pipe). */
private fun registerSeeder(host: String, hash: String) {
    runCatching {
        val conn = java.net.URL(de.systragon.beam.core.RelayConfig.seedUrl(hash, host)).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        beamLog("Relay: als Sender registriert (${hash.take(8)})")
    }.onFailure { beamLog("Relay /seed fehlgeschlagen: ${it.message}") }
}

/** Eindeutige Kennung dieser Instanz (zählt gleiche-NAT-Empfänger einzeln in der Stuck-Zahl). */
private val relayClientId = java.util.UUID.randomUUID().toString().take(12)

/** Empfänger-Heartbeat „ich hänge noch" für [hash] (speist die Stuck-Zahl beim Relay). */
private fun pingWaiting(host: String, hash: String) {
    runCatching {
        (java.net.URL(de.systragon.beam.core.RelayConfig.waitingUrl(hash, relayClientId, host))
            .openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 6000; inputStream.use { it.readBytes() }; disconnect()
        }
    }
}

/** Sender fragt die Anzahl gerade hängender Empfänger ab (−1 bei Fehler). */
private fun fetchStatus(host: String, hash: String): Int = runCatching {
    val c = java.net.URL(de.systragon.beam.core.RelayConfig.statusUrl(hash, host))
        .openConnection() as java.net.HttpURLConnection
    c.connectTimeout = 6000; c.readTimeout = 6000
    val s = c.inputStream.use { it.readBytes() }.toString(Charsets.US_ASCII).trim()
    c.disconnect()
    s.toIntOrNull() ?: -1
}.getOrDefault(-1)
private val downloadDir = File(System.getProperty("user.home"), "Downloads/Beam").apply { mkdirs() }
private val workDir = File(System.getProperty("java.io.tmpdir"), "beam-desktop").apply { mkdirs() }
// Editierbare, persistente Tracker-Liste (Datei `Downloads/Beam/trackers.txt`); Default = unsere Liste
// (eigene Station ganz oben). Nutzer kann sie im UI bearbeiten / öffentliche strippen.
private fun trackersFile() = File(downloadDir, "trackers.txt")
private fun loadTrackerText(): String =
    runCatching { trackersFile().takeIf { it.exists() }?.readText() }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: Trackers.DEFAULT_TRACKERS
private fun saveTrackerText(text: String) {
    runCatching { trackersFile().also { it.parentFile?.mkdirs() }.writeText(text) }
}
private fun trackers() = Trackers.parseTrackers(loadTrackerText())

// Beam-Relay-Station-Adresse: Betreiber-Konfig (nicht im UI editierbar). Default einkompiliert,
// überschreibbar via Datei `Downloads/Beam/relay.conf` (eine Zeile `host` oder `host:port`).
private fun relayEndpoint(): RelayConfig.Endpoint =
    RelayConfig.parse(runCatching { File(downloadDir, "relay.conf").takeIf { it.exists() }?.readText() }.getOrNull())

private val Blue = Color(0xFF2196F3)
private val Green = Color(0xFF4CAF50)

/** UI-Schnappschuss eines Transfers (aus TorrentManager + libtorrent-Status gepollt). */
private data class UiTransfer(
    val infoHash: String,
    val name: String,
    val isDownload: Boolean,
    val statusText: String,
    val progress: Float,      // 0..1, oder -1 = unbestimmt/seeding
    val showProgress: Boolean,
    val directBlocked: Boolean = false,  // Gegenüber bekannt, aber keine direkte Verbindung → Relay anbieten
    val relayEngaged: Boolean = false,   // Sender hat Relay-ON für diese Karte → Button grün
    val relayWaiting: Int = 0,           // wie viele Empfänger gerade hängen → Badge am Relay-Knopf
    val tag: String? = null              // optionales Info-Tag → oben auf der Karte
)

// Geduld, bevor „direkt blockiert" gemeldet wird (ab dem Moment, wo das Gegenüber bekannt wurde).
private const val RELAY_HINT_MS = 25_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BeamApp(initialPaths: List<String>, bringToFront: () -> Unit = {}) {
    val scheme = lightColorScheme(primary = Color(0xFF1976D2), onPrimary = Color.White)
    var status by remember { mutableStateOf("Starting session…") }
    var transfers by remember { mutableStateOf<List<UiTransfer>>(emptyList()) }
    var beamLinks by remember { mutableStateOf<Map<String, String>>(emptyMap()) } // infoHash → .beam-Pfad
    var passphrase by remember { mutableStateOf("") }                              // für verschlüsselte (.beamenc) Transfers
    var encryptOn by remember { mutableStateOf(false) }                            // Senden verschlüsseln?
    var compressionLevel by remember { mutableStateOf(VideoCompressor.CompressionLevel.ORIGINAL) } // Video-Qualität
    var backupMode by remember { mutableStateOf(false) }                           // .beam als Backup taggen → Empfänger wählt Zielordner
    var sendTag by remember { mutableStateOf("") }                                 // optionales Info-Tag fürs nächste Senden (max BeamLink.TAG_MAX)
    val saveDirs = remember { mutableMapOf<String, File>() }                        // infoHash → Zielordner (Backup wählbar)
    val backupHashes = remember { mutableSetOf<String>() }                          // infoHash der Backup-Empfänge (Top-Ordner flach auflösen)
    val renamedBackup = remember { mutableSetOf<String>() }                         // Backup-Downloads, schon flach umbenannt
    val backupTopFolder = remember { mutableMapOf<String, Set<String>>() }          // infoHash → entfernte Top-Ordner (leere Reste aufräumen)
    var lastBackupDir by remember { mutableStateOf(downloadDir) }                   // zuletzt gewählter Backup-Zielordner (gemerkt)
    val resolved = remember { mutableSetOf<String>() }                             // bereits nachbearbeitete Downloads
    val peerSeenAt = remember { mutableMapOf<String, Long>() }                      // infoHash → wann Gegenüber zuerst bekannt (Grace-Timer)
    val selectedFiles = remember { mutableMapOf<String, MutableSet<String>>() }     // infoHash → schon im Explorer markierte (fertige) Dateien
    val scope = rememberCoroutineScope()
    var pendingSend by remember { mutableStateOf<List<File>>(emptyList()) }        // vorgemerkt, wartet auf BEAM!-Knopf

    // Eine Datei → einzeln; mehrere → EIN Bündel (Multi-File-Torrent, ein .beam). Android-kompatibel
    // (Empfänger entpackt numFiles>1 automatisch in Einzeldateien).
    fun doSendFiles(files: List<File>, reveal: Boolean = false) {
        val valid = files.filter { it.isFile }
        if (valid.isEmpty()) { status = "No valid files."; return }
        // Chronology mode sends originals 1:1 → never encrypt or compress (regardless of the disabled fields).
        val encrypt = encryptOn && !backupMode
        if (encrypt && passphrase.isBlank()) { status = "Encryption on — please enter a passphrase."; return }
        // Schwere Arbeit (Verschlüsseln/Hashing) im Hintergrund, sonst friert die UI ein.
        scope.launch(Dispatchers.IO) {
            try {
                // PC-Chronologie: aus der Auswahl älteste/neueste Datei → alle Dateien dazwischen
                // (gleicher Ordner, nach Änderungsdatum) auffüllen. (Android füllt aus der Galerie.)
                val sourceFiles = if (backupMode) {
                    status = "Building chronology…"
                    expandChronologyPc(valid).also { status = "Chronology: ${it.size} files between oldest and newest" }
                } else valid
                // Optionally compress videos before sending (only videos, only if a level != Original is
                // chosen; on error the original is sent). Auch in Chronologie erlaubt: VIDEOS werden in
                // Temp-Dateien (workDir) komprimiert (gepuffert), Fotos bleiben 1:1. Default = ORIGINAL.
                val level = compressionLevel
                val prepared: List<File> = if (level == VideoCompressor.CompressionLevel.ORIGINAL) sourceFiles
                    else sourceFiles.map { f ->
                        // Nur komprimieren, wenn die Stufe kleiner als das vorliegende Video ist.
                        if (!VideoCompressor.isVideo(f) || !VideoCompressor.needsCompression(f, level)) return@map f
                        val out = File(workDir, "${f.nameWithoutExtension}_${level.tag}.mp4")
                        status = "Compressing ${f.name}…"
                        val ok = VideoCompressor.compress(f, level, out) { p ->
                            status = "Compressing ${f.name}… ${(p * 100).toInt()} %"
                        }
                        if (ok && out.exists() && out.length() > 0) out else f
                    }

                val bundle = prepared.size > 1
                val target: File = when {
                    bundle -> {
                        status = if (encrypt) "Encrypting & bundling ${prepared.size} files…" else "Bundling ${prepared.size} files…"
                        stageBundle(prepared, encrypt, passphrase, sendTag)
                    }
                    encrypt -> {
                        // Encrypt a single file → opaque container (the real name is hidden inside).
                        val c = File(workDir, "Beam_${System.currentTimeMillis()}.beamenc")
                        FileCrypto.encrypt(prepared[0], prepared[0].name, passphrase, c) { p ->
                            status = "Encrypting… ${(p * 100).toInt()} %"
                        }
                        c
                    }
                    else -> prepared[0]
                }
                status = if (bundle) "Creating bundle torrent…" else "Creating torrent…"
                val created = TorrentFactory.createTorrent(target, workDir)
                    ?: run { status = "Torrent creation failed."; return@launch }
                val mark = if (backupMode) " 🗓️" else if (encrypt) " 🔒" else ""
                val cleanTag = sendTag.trim().take(BeamLink.TAG_MAX).ifBlank { null }
                val entry = TorrentEntry(
                    infoHash = created.infoHash,
                    fileName = if (bundle) "${prepared.size} files$mark"
                               else prepared[0].name + mark,
                    fileSize = prepared.sumOf { it.length() },
                    cachedFile = target,                 // Datei/Container ODER Bündel-Ordner (parent = savePath)
                    torrentFile = created.torrentFile,
                    isDownload = false,
                    tag = cleanTag                       // optionales Info-Tag → auch auf der Sender-Karte sichtbar
                )
                if (!TorrentManager.addAndStart(entry, trackers())) { status = "Failed to start seeding."; return@launch }
                val beam = BeamLink(target.name, created.infoHash, PeerHint.localSegment(), created.magnet, backup = backupMode, tag = cleanTag).writeTo(downloadDir)
                sendTag = ""                             // Tag ist pro Transfer → nach dem Senden zurücksetzen
                beamLinks = beamLinks + (created.infoHash to beam.absolutePath)
                status = (if (bundle) "Bundle (${prepared.size} files) seeding" else "Seeding") +
                    (if (backupMode) " 🗓️ chronology" else if (encrypt) " 🔒 encrypted" else "") + " → ${beam.absolutePath}"
                if (reveal) revealInExplorer(beam)
            } catch (e: Exception) {
                status = "Send failed: ${e.message}"
            }
        }
    }

    fun doReceive(beam: File) {
        val link = BeamLink.parseFile(beam) ?: run { status = "Not a valid .beam file: ${beam.name}"; return }
        // Chronology backup (.bk token in the name) → ask for the destination folder BEFORE
        // downloading. Wir laden DIREKT in den gewählten Ordner (kein Extra-Unterordner) und lösen
        // nach Abschluss den Torrent-eigenen Top-Ordner flach auf → Dateien liegen direkt im Zielordner.
        val saveDir: File = if (link.backup) {
            val root = chooseFolder("Save chronology to… (e.g. your NAS photo collection)", lastBackupDir)
                ?: run { status = "Chronology receive cancelled."; return }
            lastBackupDir = root
            root.apply { mkdirs() }
        } else downloadDir
        // Portablen (geteilten) Magnet behalten, aber den Peer-Hint aus dem Dateinamen als
        // &x.pe=ip:port dranhängen → direktes LAN-connect_peer (sonst wartet der PC-Empfänger
        // rein auf Tracker/DHT/LSD und hängt im selben WLAN, bis man „Reconnect" drückt).
        val base = link.magnet ?: BeamLink.toMagnet(link)
        val magnet = link.peerHint
            ?.let { seg -> PeerHint.decode(seg)?.let { "$base&x.pe=$it" } }
            ?: base
        val entry = TorrentManager.addAndStartDownload(magnet, saveDir, trackers())
            ?: run { status = "Failed to start download."; return }
        entry.tag = link.tag        // optionales Info-Tag (aus .beam-Name) → oben auf der Empfänger-Karte
        saveDirs[entry.infoHash] = saveDir
        if (link.backup) backupHashes += entry.infoHash
        status = (if (link.backup) "Chronology → ${saveDir.absolutePath}: " else "Receiving: ") +
            link.displayName.ifBlank { entry.fileName }
    }

    /**
     * Nach Abschluss: Bündel auflösen (alle Dateien liegen schon im Zielordner) und `.beamenc`
     * entschlüsseln (wie Android). Gibt true zurück, wenn fertig verarbeitet (sonst Retry beim
     * nächsten Poll, z. B. sobald die Passphrase eingegeben ist).
     */
    fun resolveDownload(infoHash: String): Boolean {
        val handle = TorrentManager.getAll().firstOrNull { it.infoHash == infoHash }?.handle?.takeIf { it.isValid }
            ?: return true
        val ti = handle.torrentFile() ?: return true
        val files = ti.files()
        val n = files.numFiles()
        val dir = saveDirs[infoHash] ?: downloadDir   // Backup-Empfang ggf. in gewählten Zielordner
        var allOk = true
        var encryptedSeen = false
        var revealTarget: File? = null
        for (i in 0 until n) {
            val f = File(dir, files.filePath(i))
            if (revealTarget == null) revealTarget = f
            if (f.name.endsWith(".beamenc", true) && f.exists()) {
                encryptedSeen = true
                if (passphrase.isBlank()) { allOk = false; continue }
                runCatching { FileCrypto.decrypt(f, passphrase, f.parentFile!!) { } }
                    .onSuccess { (_, out) -> f.delete(); revealTarget = out }
                    .onFailure { allOk = false }
            }
        }
        status = when {
            !allOk && encryptedSeen && passphrase.isBlank() -> "Encrypted — enter the passphrase to decrypt automatically."
            !allOk -> "Decryption failed — wrong passphrase?"
            n > 1 -> "Bundle received ✓ ($n files) → ${dir.absolutePath}"
            else -> "Received ✓ → ${dir.absolutePath}"
        }
        // Bei Abschluss den Ziel-Ordner öffnen → User sieht alle Dateien und kann sie frei verschieben.
        if (allOk) {
            if (infoHash in backupHashes) {
                // Leere Reste der entfernten Top-Ordner (bundle_…, DCIM, Pictures, …) aufräumen —
                // robust im Hintergrund (Umbenennen settlen lassen), NUR wenn keine echte Datei mehr drin.
                backupTopFolder[infoHash]?.takeIf { it.isNotEmpty() }?.let { tops ->
                    val leftovers = tops.map { File(dir, it) }
                    Thread {
                        repeat(6) {
                            try { Thread.sleep(700) } catch (_: InterruptedException) {}
                            leftovers.forEach { lo ->
                                if (lo.exists() && !containsAnyFile(lo)) runCatching { lo.deleteRecursively() }
                            }
                            if (leftovers.none { it.exists() }) return@Thread
                        }
                    }.start()
                }
                openFolder(dir)   // Backup: flach in den GEWÄHLTEN Zielordner aufgelöst → dieses Fenster zeigt das Endergebnis.
            } else {
                // Normalfall: v2 hat den Ordner beim Empfang schon offen + die fertigen Dateien markiert
                // → KEIN zweites Fenster am Ende (User-Wunsch).
            }
        }
        return allOk
    }

    LaunchedEffect(Unit) {
        runCatching { TorrentManager.startSession() }
            .onSuccess {
                status = "Ready."
                val sendFiles = mutableListOf<File>()
                initialPaths.forEach { p ->
                    val f = File(p)
                    if (f.name.endsWith(".beam", true)) doReceive(f) else sendFiles += f
                }
                if (sendFiles.isNotEmpty()) {
                    pendingSend = sendFiles
                    status = "${sendFiles.size} file(s) ready — set options, then press BEAM!"
                }
            }
            .onFailure { status = "Failed to start session: ${it.message}" }
    }

    // Status-Poll (1 s): Tracker-Health + Karten aus allen Transfers bauen, neueste zuoberst.
    LaunchedEffect(Unit) {
        while (true) {
            runCatching {
                TorrentManager.refreshTrackerStatus()
                transfers = TorrentManager.getAll()
                    .sortedByDescending { it.createdAt }
                    .map { e ->
                        val st = e.handle?.takeIf { it.isValid }?.status()
                        val meta = st?.hasMetadata() ?: false
                        val peers = st?.numPeers() ?: 0
                        val prog = st?.progress() ?: 0f
                        val rate = if (e.isDownload) st?.downloadRate() ?: 0 else st?.uploadRate() ?: 0
                        val finished = st?.isFinished ?: false
                        // Seeder: insgesamt ausgelieferte Menge als % der Dateigröße (kann >100% sein).
                        val sentPct = if (!e.isDownload && e.fileSize > 0) ((st?.totalUpload() ?: 0L) * 100 / e.fileSize).toInt() else 0
                        // Empfang: fertige Dateien live im Explorer markieren (eintrudeln + sofort wegsortieren).
                        // Backup-Empfänge ausgenommen — die werden am Ende flach in den Zielordner aufgelöst
                        // (anderer Ordner) → dort öffnet resolveDownload das Endfenster.
                        if (e.isDownload && meta && e.infoHash !in backupHashes) maybeSelectCompleted(e, selectedFiles)
                        // „Direkt blockiert"-Erkennung: Gegenüber bekannt (listPeers>0), aber keine
                        // Verbindung (numPeers==0) seit N s ab Auftauchen → Relay anbieten.
                        val listPeers = st?.listPeers() ?: 0
                        val nowMs = System.currentTimeMillis()
                        if (listPeers > 0 && e.infoHash !in peerSeenAt) peerSeenAt[e.infoHash] = nowMs
                        // NUR der SENDER (Seeding) entscheidet übers Relay. Der EMPFÄNGER lauscht ohnehin
                        // automatisch am Relay (s. Re-Dial unten) → bei ihm KEIN Button/Hinweis.
                        val blocked = !e.isDownload && !finished && peers == 0 && listPeers > 0 &&
                            (peerSeenAt[e.infoHash]?.let { nowMs - it > RELAY_HINT_MS } ?: false)
                        // Relay-Rendezvous frisch halten: wer allein am Relay ankommt, dessen libtorrent
                        // bricht den Handshake nach ~15 s ab (Relay bleibt stumm bis BEIDE da sind). Darum
                        // alle ~12 s re-dialen, solange KEIN Peer verbunden ist, damit sich die Fenster
                        // beider Seiten überlappen. Empfänger (Download) lauscht IMMER (nicht-exklusiv);
                        // Sender mit gedrücktem „Relay NOW" (relayEngaged) hält seine Relay-Verbindung frisch.
                        if ((e.isDownload || e.relayEngaged) && !finished) {
                            val ep = relayEndpoint()
                            // A2: hängender Empfänger (kein Datenfluss seit ~20 s) eskaliert EINMAL auf
                            // relay-EXKLUSIV — exakt die SpliceTest-Konfig (clear_peers + DHT/LSD/PEX aus,
                            // nur Station-Tracker). Greift NUR bei echtem Hänger (rate==0) → Direkt/LAN bleibt.
                            val stuckReceiver = e.isDownload && rate == 0 && nowMs - e.createdAt > 20_000L
                            if (stuckReceiver && !e.relayEngaged) {
                                e.lastRelayDial = nowMs
                                Thread { TorrentManager.engageRelay(e.infoHash, ep.host, ep.port, exclusive = true) }.start()
                            } else if (!TorrentManager.isRelayConnected(e.infoHash, ep.host) &&
                                       nowMs - e.lastRelayDial > 15_000L) {
                                e.lastRelayDial = nowMs
                                val isSender = !e.isDownload && e.relayEngaged
                                Thread {
                                    if (isSender) registerSeeder(ep.host, e.infoHash)   // Reg frisch halten
                                    TorrentManager.engageRelay(e.infoHash, ep.host, ep.port, exclusive = e.relayEngaged)
                                }.start()
                            }
                        }
                        // Badge/Auto-Off: Empfänger pingt „ich hänge" solange kein Datenfluss; Sender pollt
                        // die Wartenden-Zahl + schaltet Relay automatisch ab, wenn niemand hängt UND kein Upload.
                        run {
                            val ep = relayEndpoint()
                            if (e.isDownload) {
                                val stuck = !finished && rate == 0 && nowMs - e.createdAt > 15_000L
                                if (stuck && nowMs - e.lastWaitingPing > 9_000L) {
                                    e.lastWaitingPing = nowMs
                                    Thread { pingWaiting(ep.host, e.infoHash) }.start()
                                }
                            } else if (nowMs - e.lastStatusPoll > 6_000L) {
                                e.lastStatusPoll = nowMs
                                val uploading = rate > 0
                                Thread {
                                    val n = fetchStatus(ep.host, e.infoHash)
                                    if (n >= 0) e.relayWaiting = n
                                    if (e.relayEngaged) {
                                        if (n == 0 && !uploading) {
                                            if (e.relayIdleSince == 0L) e.relayIdleSince = nowMs
                                            else if (nowMs - e.relayIdleSince > 30_000L) {
                                                e.relayEngaged = false; e.relayIdleSince = 0L
                                                beamLog("Relay auto-off (${e.infoHash.take(8)}): niemand wartet")
                                            }
                                        } else e.relayIdleSince = 0L
                                    }
                                }.start()
                            }
                        }
                        val (lt, lu, lr) = if (!e.isDownload) TorrentManager.leechBreakdown(e.infoHash) else Triple(0, 0, 0)
                        UiTransfer(
                            infoHash = e.infoHash,
                            name = e.fileName,
                            isDownload = e.isDownload,
                            statusText = statusText(e.isDownload, meta, finished, peers, rate, prog, e.trackerWorking, lt, lu, lr, sentPct),
                            progress = prog,
                            showProgress = e.isDownload && !finished,
                            directBlocked = blocked,
                            relayEngaged = e.relayEngaged,
                            relayWaiting = e.relayWaiting,
                            tag = e.tag
                        )
                    }
                // Backup-Downloads: ALLE Dateien flach in den gewählten Ordner (User-Wunsch:
                // „flach im Zielordner", keine Origin-Unterordner). Jede Datei auf ihren Basisnamen
                // umbenennen (mit Kollisions-Dedup), sobald Metadaten da sind — im HINTERGRUND (großes
                // Paket = viele Dateien; darf den Poll-/UI-Thread NICHT blockieren), einmalig, mit Log.
                TorrentManager.getAll().forEach { e ->
                    if (e.isDownload && e.infoHash in backupHashes && e.infoHash !in renamedBackup) {
                        val h = e.handle?.takeIf { it.isValid }
                        val ti = h?.torrentFile()
                        if (h != null && ti != null) {
                            renamedBackup += e.infoHash   // sofort markieren → nur EIN Versuch
                            val hash = e.infoHash
                            Thread {
                                runCatching {
                                    val fs = ti.files()
                                    val cnt = fs.numFiles()
                                    val used = HashSet<String>()        // schon vergebene flache Namen (Kollisions-Schutz)
                                    val tops = HashSet<String>()        // entfernte Top-Ordner → leere Reste aufräumen
                                    for (i in 0 until cnt) {
                                        val rel = fs.filePath(i).replace('\\', '/')
                                        if (rel.contains('/')) tops += rel.substringBefore('/')
                                        var base = rel.substringAfterLast('/')
                                        if (base.isBlank()) continue
                                        if (base in used) {            // gleicher Dateiname aus anderem Ordner → _1, _2, …
                                            val stem = base.substringBeforeLast('.', base)
                                            val ext = base.substringAfterLast('.', "")
                                            var k = 1
                                            var cand: String
                                            do { cand = if (ext.isEmpty()) "${stem}_$k" else "${stem}_$k.$ext"; k++ } while (cand in used)
                                            base = cand
                                        }
                                        used += base
                                        if (base != rel) h.renameFile(i, base)
                                    }
                                    backupTopFolder[hash] = tops
                                    beamLog("backup flatten: $cnt files → flat (tops=$tops) for $hash")
                                }.onFailure { beamLog("backup flatten FAILED for $hash: ${it.stackTraceToString()}") }
                            }.start()
                        }
                    }
                }
                // Fertige Downloads nachbearbeiten (Bündel auflösen + entschlüsseln), je Transfer einmal.
                TorrentManager.getAll().forEach { e ->
                    if (e.isDownload && e.infoHash !in resolved) {
                        val fin = e.handle?.takeIf { it.isValid }?.status()?.isFinished ?: false
                        if (fin && resolveDownload(e.infoHash)) { resolved += e.infoHash; bringToFront() }   // „Done" nach vorn holen
                    }
                }
            }.onFailure { beamLog("poll loop error: ${it.stackTraceToString()}") }
            delay(1000)
        }
    }

    // Kurz-Hilfe (aus dem App-assets-Ordner: beamDesk_help_<lang>.md), Dialog über „❓ Help".
    // Beim Öffnen immer zuerst Englisch; per Klick auf DE | EN umschaltbar.
    var showHelp by remember { mutableStateOf(false) }
    var helpLang by remember { mutableStateOf("en") }
    val helpBlocks = remember(helpLang) {
        val raw = runCatching {
            useResource("beamDesk_help_$helpLang.md") { it.readBytes().decodeToString() }
        }.getOrDefault("Help file not found.")
        // Marker-Zeile (# EN | DE) raus — den Umschalter bauen wir selbst.
        val marker = Regex("""^\s*#?\s*(EN|DE)\s*\|\s*(EN|DE)\s*$""")
        val body = raw.lines().filterNot { marker.matches(it) }.joinToString("\n").trimStart('\n')
        parseMarkdown(body)
    }

    // In-App-Update: null = aktuell/kein Hinweis; sonst der Versionsname (z. B. "1.0.52") → kleiner
    // Button unten rechts. Einmal beim Start gegen das Stations-Manifest prüfen.
    var updateName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                val mineBuild = BEAM_VERSION.substringAfterLast('.').toIntOrNull() ?: return@runCatching
                val host = relayEndpoint().host
                val txt = java.net.URL(RelayConfig.versionUrl(host)).openStream().bufferedReader().use { it.readText() }
                // Kleines, selbst kontrolliertes JSON → ohne JSON-Lib per Regex (beam-desktop hat kein org.json).
                val deskBlock = Regex(""""desktop"\s*:\s*\{([^}]*)\}""").find(txt)?.groupValues?.get(1) ?: return@runCatching
                val latest = Regex(""""build"\s*:\s*(\d+)""").find(deskBlock)?.groupValues?.get(1)?.toIntOrNull() ?: return@runCatching
                val name = Regex(""""name"\s*:\s*"([^"]*)"""").find(deskBlock)?.groupValues?.get(1) ?: "1.0.$latest"
                if (latest > mineBuild) updateName = name
            }
        }
    }

    // Lädt das aktuelle MSI von der Station und startet den Windows-Installer (Major-Upgrade).
    fun runUpdate() {
        status = "Downloading update…"
        Thread {
            runCatching {
                val host = relayEndpoint().host
                val tmp = File(downloadDir, "Beam-update.msi")
                java.net.URL(RelayConfig.msiUrl(host)).openStream().use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                ProcessBuilder("msiexec", "/i", tmp.absolutePath).start()
            }.onFailure { beamLog("[W] update failed: ${it.message}"); status = "Update download failed." }
        }.start()
    }

    // Verteilen: lädt APK/MSI token-gated von der Station nach Downloads/Beam/installer/ und öffnet
    // den Explorer mit der MARKIERTEN Datei → der Nutzer leitet sie beliebig weiter (WhatsApp/Mail/Stick).
    fun shareBinary(remoteUrl: String, fileName: String) {
        status = "Fetching $fileName…"
        Thread {
            runCatching {
                val dir = File(downloadDir, "installer").apply { mkdirs() }
                val out = File(dir, fileName)
                java.net.URL(remoteUrl).openStream().use { ins -> out.outputStream().use { ins.copyTo(it) } }
                if (out.length() < 1_000_000) error("download too small (${out.length()} B)")
                status = "Ready: $fileName  →  ${dir.absolutePath}"
                selectInExplorer(dir, listOf(fileName))
            }.onFailure { beamLog("[W] shareBinary failed: ${it.message}"); status = "Download failed: ${it.message}" }
        }.start()
    }

    MaterialTheme(colorScheme = scheme) {
        if (showHelp) {
            AlertDialog(
                onDismissRequest = { showHelp = false },
                confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Close") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Beam! — Help", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(
                            "DE",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (helpLang == "de") FontWeight.Bold else FontWeight.Normal,
                            color = if (helpLang == "de") MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { helpLang = "de" }
                        )
                        Text("  |  ", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "EN",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (helpLang == "en") FontWeight.Bold else FontWeight.Normal,
                            color = if (helpLang == "en") MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { helpLang = "en" }
                        )
                    }
                },
                text = {
                    Box(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                        MarkdownHelp(helpBlocks)
                    }
                }
            )
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Beam!   v$BEAM_VERSION", fontWeight = FontWeight.Bold) },
                    actions = {
                        TextButton(onClick = { showHelp = true }) { Text("❓ Help", color = Color.White) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = Color.White
                    )
                )
            },
            bottomBar = {
                // Verteil-Footer: Beam an andere weitergeben (APK/MSI von der Station) + Selbst-Update.
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { shareBinary(RelayConfig.apkUrl(relayEndpoint().host), "Beam.apk") }) { Text("📱 Share Android") }
                        OutlinedButton(onClick = { shareBinary(RelayConfig.msiUrl(relayEndpoint().host), "Beam.msi") }) { Text("🖥 Share PC") }
                        Spacer(Modifier.weight(1f))
                        updateName?.let { name ->
                            Button(onClick = { runUpdate() }) { Text("⬆ Update v$name") }
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { chooseFiles()?.let { pendingSend = it } }) { Text("📤  Choose file(s)") }
                    OutlinedButton(onClick = { chooseFile(extension = "beam")?.let { doReceive(it) } }) { Text("📥  Open .beam") }
                }
                // Chronology backup (directly under "Choose file(s)"): tags the .beam (.bk) → the
                // receiver is asked for a destination folder (e.g. a NAS photo collection). Originals
                // are sent 1:1 → encryption and video quality are disabled in this mode.
                // Kompakter Sende-Block: beide Checkboxen in EINER Zeile (Encrypt, dann Chrono), enge Felder.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = encryptOn && !backupMode, enabled = !backupMode, onCheckedChange = { encryptOn = it })
                        Text(
                            "Encrypt using passphrase",
                            color = if (backupMode) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
                        )
                        Spacer(Modifier.width(16.dp))
                        Checkbox(checked = backupMode, onCheckedChange = { backupMode = it })
                        Text("🗓️  Chrono")
                    }
                    CompactField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        placeholder = "Passphrase",
                        enabled = !backupMode,
                        modifier = Modifier.fillMaxWidth()
                    )
                    CompactField(
                        value = sendTag,
                        onValueChange = { sendTag = it.take(BeamLink.TAG_MAX) },
                        placeholder = "Tag (optional, on receiver's card)",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Quality:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    var menuCompress by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { menuCompress = true }) {   // auch in Chronologie: Videos komprimierbar
                            Text(if (compressionLevel == VideoCompressor.CompressionLevel.ORIGINAL) "Original" else compressionLevel.tag)
                            Text("  ▾")
                        }
                        DropdownMenu(expanded = menuCompress, onDismissRequest = { menuCompress = false }) {
                            VideoCompressor.CompressionLevel.entries.forEach { l ->
                                DropdownMenuItem(
                                    text = { Text(l.label) },
                                    onClick = { compressionLevel = l; menuCompress = false }
                                )
                            }
                        }
                    }
                }
                // Editierbare Tracker-Liste (ausklappbar). Unsere private Station steht ganz oben;
                // zum reinen Privat-Test die öffentlichen Zeilen löschen und speichern.
                var showTrackers by remember { mutableStateOf(false) }
                var trackerText by remember { mutableStateOf(loadTrackerText()) }
                TextButton(onClick = { showTrackers = !showTrackers }) {
                    Text((if (showTrackers) "▾" else "▸") + "  🛰️  Trackers (advanced)")
                }
                if (showTrackers) {
                    OutlinedTextField(
                        value = trackerText,
                        onValueChange = { trackerText = it },
                        label = { Text("Tracker list — one URL per line (our private station is on top)") },
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().height(150.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { saveTrackerText(trackerText); status = "Tracker list saved." }) {
                            Text("Save")
                        }
                        OutlinedButton(onClick = {
                            trackerText = Trackers.DEFAULT_TRACKERS
                            saveTrackerText(trackerText); status = "Trackers reset to defaults."
                        }) { Text("Reset to defaults") }
                    }
                }

                // Live-Vorschau (wie Android): Dateianzahl + Gesamtgröße, sofort aktualisiert — bei
                // Chronologie inkl. erweiterter Menge + Zeitraum. Berechnung im Hintergrund (Dateisystem).
                val sendInfo by produceState<SendPreview?>(null, pendingSend, backupMode) {
                    value = null
                    if (pendingSend.isNotEmpty()) value = withContext(Dispatchers.IO) {
                        val files = if (backupMode) expandChronologyPc(pendingSend) else pendingSend
                        val times = files.map { it.lastModified() }.filter { it > 0 }
                        SendPreview(
                            count = files.size,
                            bytes = files.sumOf { it.length() },
                            expanded = backupMode && files.size > pendingSend.size,
                            range = if (backupMode && times.size >= 2) {
                                val fmt = java.text.SimpleDateFormat("dd.MM. HH:mm")
                                "${fmt.format(java.util.Date(times.min()))} – ${fmt.format(java.util.Date(times.max()))}"
                            } else ""
                        )
                    }
                }

                // Pending files → here (after options) the .beam/.beamenc is created + seeded.
                if (pendingSend.isNotEmpty()) {
                    val info = sendInfo
                    Text(
                        buildString {
                            if (info == null) { append("${pendingSend.size} file(s) — computing…") }
                            else {
                                if (info.expanded) append("🗓️ Chronology: ")
                                append("${info.count} file(s) · ${humanSize(info.bytes)}")
                                if (info.range.isNotEmpty()) append("   (${info.range})")
                                if (encryptOn && !backupMode) append("   🔒")
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = { doSendFiles(pendingSend, reveal = true); pendingSend = emptyList() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🚀  BEAM! send") }
                }

                if (transfers.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🧲", style = MaterialTheme.typography.headlineLarge)
                            Spacer(Modifier.height(8.dp))
                            Text("No transfers", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Send a file or open a .beam", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    transfers.forEach { t -> TransferCard(t, beamLinks[t.infoHash]) }
                }

                Spacer(Modifier.height(8.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Clickable: opens the receive folder in Windows Explorer.
                Text(
                    "Received → ${downloadDir.absolutePath}",
                    style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { openFolder(downloadDir) }
                )
            }
        }
    }
}

/**
 * Schlankes einzeiliges Eingabefeld — wie ein OutlinedTextField, aber mit selbst gesetztem (kleinem)
 * Innen-Padding, damit es nicht so viel Platz frisst. Placeholder wird bei leerem Wert gezeigt.
 */
@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TransferCard(t: UiTransfer, beamPath: String?) {
    Card(shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            t.tag?.takeIf { it.isNotBlank() }?.let { tg ->
                Text("🏷  $tg", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 2)
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (t.isDownload) "⬇" else "⬆", color = if (t.isDownload) Blue else Green,
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(t.statusText, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (t.showProgress) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { t.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!t.isDownload) {
                    if (beamPath != null) TextButton(onClick = { copyFileToClipboard(File(beamPath)) }) {
                        Text("Share (copy .beam)")
                    }
                    if (beamPath != null) TextButton(onClick = { revealInExplorer(File(beamPath)) }) {
                        Text("Show in Explorer")
                    }
                }
                TextButton(onClick = { Thread { TorrentManager.restartTransfer(t.infoHash, trackers()) }.start() }) { Text("Reconnect") }
                // Relay-Button NUR beim SENDER. Der Empfänger lauscht ohnehin automatisch am Relay
                // (Re-Dial in der Poll-Schleife), sobald er den Hash kennt → kein Button (User-Entscheid).
                if (!t.isDownload) {
                    // Relay-ON gilt für die ganze Karte (bedient alle Empfänger nacheinander), bis „Remove".
                    val engage = {
                        Thread {
                            val ep = relayEndpoint()
                            registerSeeder(ep.host, t.infoHash)   // Sender-Rolle anmelden, DANN verbinden
                            TorrentManager.engageRelay(t.infoHash, ep.host, ep.port)
                        }.start(); Unit
                    }
                    val waitBadge = if (t.relayWaiting > 0) "  (${t.relayWaiting} waiting)" else ""
                    when {
                        t.relayEngaged -> Button(
                            onClick = engage,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) { Text("📡 Relay ON$waitBadge") }                         // grün = aktiv
                        t.relayWaiting > 0 || t.directBlocked ->                    // jemand hängt → „drück mich"
                            Button(onClick = engage) { Text("📡 Relay NOW!$waitBadge") }
                        else -> TextButton(onClick = engage) { Text("📡 Relay NOW!") }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { TorrentManager.stop(t.infoHash) }) { Text("Remove") }
            }
        }
    }
}

/** Granulare Statuszeile — wie in der Android-App. */
private fun statusText(isDownload: Boolean, meta: Boolean, finished: Boolean, peers: Int, rate: Int, progress: Float, trackerWorking: Boolean, tcp: Int = 0, utp: Int = 0, relay: Int = 0, sentPct: Int = 0): String {
    if (isDownload) {
        return when {
            finished -> "✓ done"
            !meta && peers == 0 -> if (trackerWorking) "Searching for sender…" else "Connecting to trackers…"
            !meta -> "Fetching file info… ($peers peers)"
            else -> "⬇ ${humanBytes(rate.toLong())}/s · ${(progress * 100).toInt()} % · $peers peers"
        }
    }
    // Seeder: Transport-Aufschlüsselung der saugenden Empfänger — TCP(#) µTP(#) Relay(#).
    val ways = buildList {
        if (tcp > 0) add("TCP($tcp)")
        if (utp > 0) add("µTP($utp)")
        if (relay > 0) add("Relay($relay)")
    }.joinToString(" ")
    val sent = if (sentPct > 0) " · ${sentPct}%" else ""
    return when {
        ways.isNotEmpty() -> "⬆ ${humanBytes(rate.toLong())}/s · $ways$sent"
        sentPct > 0 -> "✓ ${sentPct}%"
        trackerWorking -> "Waiting for receiver…"
        else -> "Connecting…"
    }
}

/** Öffnet den Explorer und markiert die Datei → bereit zum Weiterteilen (Drag in Messenger/Mail). */
private fun revealInExplorer(file: File) {
    runCatching { ProcessBuilder("explorer.exe", "/select,${file.absolutePath}").start() }
        .onFailure { println("[W] Explorer reveal failed: ${it.message}") }
}

/**
 * PowerShell-Skript (via -EncodedCommand) zum Markieren von Dateien in einem offenen Explorer-Fenster.
 * Ordner + Dateinamen kommen über die Datei in $env:BEAM_SEL_FILE (UTF-8, Zeile 1 = Ordner, Rest = Namen).
 * SelectItem-Flag 9 = SVSI_SELECT|SVSI_ENSUREVISIBLE → ADDIERT zur Auswahl (stört die manuelle nicht).
 */
private const val EXPLORER_SELECT_PS =
    "\$ErrorActionPreference='SilentlyContinue';" +
    "\$f=\$env:BEAM_SEL_FILE;" +
    "\$lines=Get-Content -LiteralPath \$f -Encoding UTF8;" +
    "\$folder=\$lines[0];" +
    "\$names=@(\$lines|Select-Object -Skip 1|Where-Object{\$_});" +
    "\$sh=New-Object -ComObject Shell.Application;" +
    "\$win=\$sh.Windows()|Where-Object{try{\$_.Document.Folder.Self.Path -eq \$folder}catch{\$false}}|Select-Object -First 1;" +
    "if(-not \$win){explorer.exe \$folder;Start-Sleep -Milliseconds 900;\$win=\$sh.Windows()|Where-Object{try{\$_.Document.Folder.Self.Path -eq \$folder}catch{\$false}}|Select-Object -First 1};" +
    "if(\$win){foreach(\$n in \$names){\$it=\$win.Document.Folder.ParseName(\$n);if(\$it){[void]\$win.Document.SelectItem(\$it,9)}}};" +
    "Remove-Item -LiteralPath \$f -Force"

/** Öffnet/findet ein Explorer-Fenster auf [dir] und markiert die [names] (additiv) — fire-and-forget. */
private fun selectInExplorer(dir: File, names: List<String>) {
    if (names.isEmpty()) return
    Thread {
        runCatching {
            val data = File.createTempFile("beamsel_", ".txt")
            data.writeText((listOf(dir.absolutePath) + names).joinToString("\n"), Charsets.UTF_8)
            val b64 = java.util.Base64.getEncoder().encodeToString(EXPLORER_SELECT_PS.toByteArray(Charsets.UTF_16LE))
            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", b64)
                .apply { environment()["BEAM_SEL_FILE"] = data.absolutePath; redirectErrorStream(true) }
                .start()
        }.onFailure { beamLog("[W] selectInExplorer: ${it.message}") }
    }.start()
}

/**
 * Empfang: Dateien live im Explorer markieren, sobald genau SIE fertig sind (per-Datei-Fortschritt) —
 * der Anwender sieht sie eintrudeln (mit Thumbnail-Ansicht als Vorschau) und kann fertige sofort per
 * Strg-X/C wegsortieren. Markiert nur NEU fertige (kumulativ), in der echten Datei-Location (Bündel
 * liegen evtl. im Torrent-Top-Unterordner).
 */
private fun maybeSelectCompleted(e: de.systragon.beam.core.TorrentEntry, selected: MutableMap<String, MutableSet<String>>) {
    val h = e.handle?.takeIf { it.isValid } ?: return
    val ti = h.torrentFile() ?: return
    val files = ti.files()
    val total = files.numFiles()
    val already = selected.getOrPut(e.infoHash) { mutableSetOf() }
    if (already.size >= total) return                       // alles schon markiert → fertig
    val fp = runCatching { h.fileProgress() }.getOrNull() ?: return
    val savePath = runCatching { File(h.savePath()) }.getOrNull() ?: return
    val byFolder = HashMap<File, MutableList<String>>()
    for (i in 0 until total) {
        val size = files.fileSize(i)
        if (size > 0 && i < fp.size && fp[i] >= size) {
            val rel = files.filePath(i)
            if (already.add(rel)) {                          // nur NEU fertige
                val onDisk = File(savePath, rel)
                byFolder.getOrPut(onDisk.parentFile ?: savePath) { mutableListOf() }.add(onDisk.name)
            }
        }
    }
    for ((folder, names) in byFolder) selectInExplorer(folder, names)
}

/** True, wenn der Ordner (rekursiv) mindestens eine echte Datei enthält. */
private fun containsAnyFile(dir: File): Boolean = dir.walkTopDown().any { it.isFile }

/**
 * PC-Chronologie-Expansion: aus den ausgewählten Anker-Dateien älteste/neueste Änderungszeit nehmen
 * und ALLE Dateien dazwischen in denselben Ordner(n) ergänzen (nicht rekursiv). Leer/ungültig → Anker.
 */
/** Live-Vorschau der zu sendenden Menge (für die UI). */
private data class SendPreview(val count: Int, val bytes: Long, val expanded: Boolean, val range: String)

/** Menschenlesbare Größe (B/KB/MB/GB). */
private fun humanSize(b: Long): String {
    if (b < 1024) return "$b B"
    val kb = b / 1024.0; if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0; if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

private fun expandChronologyPc(anchors: List<File>): List<File> {
    val times = anchors.map { it.lastModified() }.filter { it > 0 }
    if (times.isEmpty()) return anchors
    val min = times.min()
    val max = times.max()
    val dirs = anchors.mapNotNull { it.parentFile }.toSet()
    val all = dirs.flatMap { d ->
        (d.listFiles()?.toList() ?: emptyList()).filter { it.isFile && it.lastModified() in min..max }
    }.distinctBy { it.absolutePath }.sortedBy { it.lastModified() }
    return all.ifEmpty { anchors }
}

/** Öffnet einen Ordner im Explorer → User sieht alle (empfangenen) Dateien und kann sie verschieben. */
private fun openFolder(dir: File) {
    runCatching { ProcessBuilder("explorer.exe", dir.absolutePath).start() }
        .onFailure { println("[W] Explorer open failed: ${it.message}") }
}

/** Legt die DATEI (nicht Text) in die Zwischenablage → in WhatsApp/Mail mit Strg+V als Anhang einfügen. */
private class FileTransferable(private val file: File) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any = listOf(file)
}
private fun copyFileToClipboard(file: File) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(FileTransferable(file), null) }
        .onFailure { println("[W] Copy file to clipboard failed: ${it.message}") }
}

/**
 * Stellt mehrere Dateien in einen Bündel-Ordner für einen Multi-File-Torrent. Bei [encrypt] wird
 * jede Datei als `<name>.beamenc` verschlüsselt (wie Android), sonst Hardlink (bzw. Kopie).
 */
private fun stageBundle(files: List<File>, encrypt: Boolean, passphrase: String, tag: String = ""): File {
    // Tag (falls gesetzt) als Bündel-Ordnername statt kryptischer Nummer → Empfänger sieht „BEAM_<Tag>".
    val safe = tag.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(40)
    val dirName = if (safe.isNotEmpty()) "BEAM_$safe" else "Beam_${System.currentTimeMillis()}"
    val dir = File(workDir, dirName).apply { if (exists()) deleteRecursively(); mkdirs() }
    for (f in files) {
        if (encrypt) {
            FileCrypto.encrypt(f, f.name, passphrase, File(dir, f.name + ".beamenc")) { }
        } else {
            val dest = File(dir, f.name)
            runCatching { java.nio.file.Files.createLink(dest.toPath(), f.toPath()) }
                .onFailure { runCatching { f.copyTo(dest, overwrite = true) } }
        }
    }
    return dir
}

/** Multi-file selection (for bundles). */
private fun chooseFiles(): List<File>? {
    val dialog = FileDialog(null as Frame?, "Choose file(s)", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    val files = dialog.files
    return if (files.isNullOrEmpty()) null else files.toList()
}

private fun chooseFile(extension: String? = null): File? {
    val dialog = FileDialog(null as Frame?, "Choose file", FileDialog.LOAD)
    if (extension != null) dialog.file = "*.$extension"
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(dir, file)
}

/** Folder picker (for the chronology destination). AWT FileDialog can't pick folders on Windows →
 *  Swing JFileChooser on the EDT, parented to an always-on-top frame so it surfaces in front. */
private fun chooseFolder(title: String, initial: File?): File? {
    var result: File? = null
    val task = Runnable {
        val parent = javax.swing.JFrame().apply { isAlwaysOnTop = true; setLocationRelativeTo(null) }
        val chooser = javax.swing.JFileChooser().apply {
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            dialogTitle = title
            if (initial != null && initial.exists()) currentDirectory = initial
        }
        if (chooser.showDialog(parent, "Save here") == javax.swing.JFileChooser.APPROVE_OPTION) {
            result = chooser.selectedFile
        }
        parent.dispose()
    }
    runCatching {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) task.run()
        else javax.swing.SwingUtilities.invokeAndWait(task)
    }.onFailure { println("[W] Folder selection failed: ${it.message}") }
    return result
}

private fun sanitizeFolderName(s: String): String =
    s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Beam_Chronology" }

private fun ensureNativeLib() {
    if (!System.getProperty("libtorrent4j.jni.path", "").isNullOrEmpty()) return
    val os = System.getProperty("os.name", "").lowercase()
    val ext = when {
        os.contains("win") -> "dll"
        os.contains("mac") || os.contains("darwin") -> "dylib"
        else -> "so"
    }
    val resource = "lib/x86_64/libtorrent4j.$ext"
    val cl = Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
    val input = cl.getResourceAsStream(resource) ?: run { println("[E] Native-Lib nicht im Classpath: $resource"); return }
    val tmp = File.createTempFile("libtorrent4j", ".$ext")
    tmp.deleteOnExit()
    input.use { ins -> tmp.outputStream().use { out -> ins.copyTo(out) } }
    System.setProperty("libtorrent4j.jni.path", tmp.absolutePath)
    println("[I] Native-Lib extrahiert: ${tmp.absolutePath}")
}

private fun humanBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = n.toDouble() / 1024.0
    var idx = 0
    while (value >= 1024.0 && idx < units.lastIndex) { value /= 1024.0; idx++ }
    return "%.1f %s".format(value, units[idx])
}

// --- Leichtgewichtiger Markdown-Renderer (kein externer Renderer, 0 neue Deps) ---------------
// Unterstützt: # / ## Überschriften, **fett**, _kursiv_, `code`, - Aufzählung, --- Trennlinie,
// | Tabellen | (zweispaltig) und <br/> als echten Umbruch. Spiegelbild der Android-Variante.

private sealed interface MdBlock {
    data class Heading(val text: String, val level: Int) : MdBlock
    data class Para(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    object Rule : MdBlock
    object Space : MdBlock
}

private fun parseMarkdown(md: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = md.lines()
    var i = 0
    val sepCell = Regex("""^:?-{2,}:?$""")
    while (i < lines.size) {
        val line = lines[i].trim()
        when {
            line.isEmpty() -> { out += MdBlock.Space; i++ }
            line == "---" || line == "***" -> { out += MdBlock.Rule; i++ }
            line.startsWith("## ") -> { out += MdBlock.Heading(line.removePrefix("## ").trim(), 2); i++ }
            line.startsWith("# ") -> { out += MdBlock.Heading(line.removePrefix("# ").trim(), 1); i++ }
            line.startsWith("- ") || line.startsWith("* ") -> { out += MdBlock.Bullet(line.drop(2).trim()); i++ }
            line.startsWith("|") -> {
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                    if (cells.none { sepCell.matches(it) }) rows += cells
                    i++
                }
                if (rows.isNotEmpty()) out += MdBlock.Table(rows)
            }
            else -> { out += MdBlock.Para(line); i++ }
        }
    }
    return out
}

/** Inline-Formatierung: **fett**, _kursiv_, `code`, <br/> → echter Umbruch. */
private fun inlineMd(src: String): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    val s = src.replace(Regex("""<br\s*/?>"""), "\n")
    val rx = Regex("""\*\*(.+?)\*\*|_(.+?)_|`(.+?)`""")
    var last = 0
    for (m in rx.findAll(s)) {
        append(s.substring(last, m.range.first))
        when {
            m.groupValues[1].isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            m.groupValues[2].isNotEmpty() ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[2]) }
            else ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.9.em)) { append(m.groupValues[3]) }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}

@androidx.compose.runtime.Composable
private fun MarkdownHelp(blocks: List<MdBlock>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Space -> Spacer(Modifier.height(6.dp))
                is MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 6.dp))
                is MdBlock.Heading -> Text(
                    inlineMd(b.text),
                    style = if (b.level == 1) MaterialTheme.typography.titleLarge
                            else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
                is MdBlock.Para -> Text(
                    inlineMd(b.text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
                is MdBlock.Bullet -> Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                    Text(inlineMd(b.text), style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Table -> Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    b.rows.forEachIndexed { idx, cells ->
                        val bold = idx == 0
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                cells.getOrElse(0) { "" }.let { inlineMd(it) },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(0.42f)
                            )
                            Text(
                                inlineMd(cells.drop(1).joinToString("  ")),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (idx == 0) HorizontalDivider()
                    }
                }
            }
        }
    }
}
