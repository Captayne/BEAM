package de.systragon.beam.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import de.systragon.beam.core.*
import de.systragon.beam.media.FileCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    // „Senden an → BEAM!" übergibt eine oder mehrere markierte Dateien als Argumente.
    val initialPaths = args.filter { it.isNotBlank() }
    application {
        // Gleiches Icon wie die Android-App (liegt als Classpath-Ressource beam.png).
        val beamIcon = remember { useResource("beam.png") { BitmapPainter(loadImageBitmap(it)) } }
        Window(onCloseRequest = ::exitApplication, title = "Beam $BEAM_VERSION", icon = beamIcon) {
            BeamApp(initialPaths)
        }
    }
}

/** Versionsnummer (vom Build via -Dbeam.version gesetzt; sonst "dev"). Für die Titelzeile. */
private val BEAM_VERSION: String = System.getProperty("beam.version") ?: "dev"
private val downloadDir = File(System.getProperty("user.home"), "Downloads/Beam").apply { mkdirs() }
private val workDir = File(System.getProperty("java.io.tmpdir"), "beam-desktop").apply { mkdirs() }
private fun trackers() = Trackers.parseTrackers(Trackers.DEFAULT_TRACKERS)

private val Blue = Color(0xFF2196F3)
private val Green = Color(0xFF4CAF50)

/** UI-Schnappschuss eines Transfers (aus TorrentManager + libtorrent-Status gepollt). */
private data class UiTransfer(
    val infoHash: String,
    val name: String,
    val isDownload: Boolean,
    val statusText: String,
    val progress: Float,      // 0..1, oder -1 = unbestimmt/seeding
    val showProgress: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BeamApp(initialPaths: List<String>) {
    val scheme = lightColorScheme(primary = Color(0xFF1976D2), onPrimary = Color.White)
    var status by remember { mutableStateOf("Starting session…") }
    var transfers by remember { mutableStateOf<List<UiTransfer>>(emptyList()) }
    var beamLinks by remember { mutableStateOf<Map<String, String>>(emptyMap()) } // infoHash → .beam-Pfad
    var passphrase by remember { mutableStateOf("") }                              // für verschlüsselte (.beamenc) Transfers
    var encryptOn by remember { mutableStateOf(false) }                            // Senden verschlüsseln?
    var compressionLevel by remember { mutableStateOf(VideoCompressor.CompressionLevel.ORIGINAL) } // Video-Qualität
    var backupMode by remember { mutableStateOf(false) }                           // .beam als Backup taggen → Empfänger wählt Zielordner
    val saveDirs = remember { mutableMapOf<String, File>() }                        // infoHash → Zielordner (Backup wählbar)
    val backupHashes = remember { mutableSetOf<String>() }                          // infoHash der Backup-Empfänge (Top-Ordner flach auflösen)
    val renamedBackup = remember { mutableSetOf<String>() }                         // Backup-Downloads, schon flach umbenannt
    val backupTopFolder = remember { mutableMapOf<String, Set<String>>() }          // infoHash → entfernte Top-Ordner (leere Reste aufräumen)
    var lastBackupDir by remember { mutableStateOf(downloadDir) }                   // zuletzt gewählter Backup-Zielordner (gemerkt)
    val resolved = remember { mutableSetOf<String>() }                             // bereits nachbearbeitete Downloads
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
                // Optionally compress videos before sending (like Android: only videos, only if a
                // level != Original is chosen; on error the original is sent). Off in Chronology mode.
                val level = if (backupMode) VideoCompressor.CompressionLevel.ORIGINAL else compressionLevel
                val prepared: List<File> = if (level == VideoCompressor.CompressionLevel.ORIGINAL) sourceFiles
                    else sourceFiles.map { f ->
                        if (!VideoCompressor.isVideo(f)) return@map f
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
                        stageBundle(prepared, encrypt, passphrase)
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
                val entry = TorrentEntry(
                    infoHash = created.infoHash,
                    fileName = if (bundle) "${prepared.size} files$mark"
                               else prepared[0].name + mark,
                    fileSize = prepared.sumOf { it.length() },
                    cachedFile = target,                 // Datei/Container ODER Bündel-Ordner (parent = savePath)
                    torrentFile = created.torrentFile,
                    isDownload = false
                )
                if (!TorrentManager.addAndStart(entry, trackers())) { status = "Failed to start seeding."; return@launch }
                val beam = BeamLink(target.name, created.infoHash, PeerHint.localSegment(), created.magnet, backup = backupMode).writeTo(downloadDir)
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
        val entry = TorrentManager.addAndStartDownload(link.magnet ?: BeamLink.toMagnet(link), saveDir, trackers())
            ?: run { status = "Failed to start download."; return }
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
                openFolder(dir)   // den vom Nutzer gewählten Ordner, nicht den (alten) bundle_…-Pfad
            } else {
                openFolder(revealTarget?.parentFile ?: dir)
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
                        UiTransfer(
                            infoHash = e.infoHash,
                            name = e.fileName,
                            isDownload = e.isDownload,
                            statusText = statusText(e.isDownload, meta, finished, peers, rate, prog, e.trackerWorking),
                            progress = prog,
                            showProgress = e.isDownload && !finished
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
                        if (fin && resolveDownload(e.infoHash)) resolved += e.infoHash
                    }
                }
            }.onFailure { beamLog("poll loop error: ${it.stackTraceToString()}") }
            delay(1000)
        }
    }

    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Beam!   v$BEAM_VERSION", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = Color.White
                    )
                )
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = backupMode, onCheckedChange = { backupMode = it })
                    Text("🗓️  Send Chronology  —  originals 1:1, receiver picks the destination (e.g. NAS)")
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = encryptOn && !backupMode, enabled = !backupMode, onCheckedChange = { encryptOn = it })
                    Text(
                        "Encrypt when sending (passphrase required)",
                        color = if (backupMode) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
                    )
                }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase (encrypt when sending / decrypt when receiving)") },
                    singleLine = true,
                    enabled = !backupMode,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Video quality:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    var menuCompress by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { menuCompress = true }, enabled = !backupMode) {
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
                // Pending files → here (after options) the .beam/.beamenc is created + seeded.
                if (pendingSend.isNotEmpty()) {
                    Text(
                        "${pendingSend.size} file(s) ready to send" +
                            (if (backupMode) " 🗓️" else if (encryptOn) " 🔒" else ""),
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

@Composable
private fun TransferCard(t: UiTransfer, beamPath: String?) {
    Card(shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
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
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { TorrentManager.stop(t.infoHash) }) { Text("Remove") }
            }
        }
    }
}

/** Granulare Statuszeile — wie in der Android-App. */
private fun statusText(isDownload: Boolean, meta: Boolean, finished: Boolean, peers: Int, rate: Int, progress: Float, trackerWorking: Boolean): String {
    if (isDownload) {
        return when {
            finished -> "✓ done"
            !meta && peers == 0 -> if (trackerWorking) "Searching for sender…" else "Connecting to trackers…"
            !meta -> "Fetching file info… ($peers peers)"
            else -> "⬇ ${humanBytes(rate.toLong())}/s · ${(progress * 100).toInt()} % · $peers peers"
        }
    }
    return when {
        peers > 0 -> "⬆ ${humanBytes(rate.toLong())}/s · $peers peers"
        trackerWorking -> "Waiting for receiver…"
        else -> "Connecting to trackers…"
    }
}

/** Öffnet den Explorer und markiert die Datei → bereit zum Weiterteilen (Drag in Messenger/Mail). */
private fun revealInExplorer(file: File) {
    runCatching { ProcessBuilder("explorer.exe", "/select,${file.absolutePath}").start() }
        .onFailure { println("[W] Explorer reveal failed: ${it.message}") }
}

/** True, wenn der Ordner (rekursiv) mindestens eine echte Datei enthält. */
private fun containsAnyFile(dir: File): Boolean = dir.walkTopDown().any { it.isFile }

/**
 * PC-Chronologie-Expansion: aus den ausgewählten Anker-Dateien älteste/neueste Änderungszeit nehmen
 * und ALLE Dateien dazwischen in denselben Ordner(n) ergänzen (nicht rekursiv). Leer/ungültig → Anker.
 */
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
private fun stageBundle(files: List<File>, encrypt: Boolean, passphrase: String): File {
    val dir = File(workDir, "Beam_${System.currentTimeMillis()}").apply { mkdirs() }
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
