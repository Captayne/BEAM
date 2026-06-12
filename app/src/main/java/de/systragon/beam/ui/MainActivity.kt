package de.systragon.beam.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import de.systragon.beam.media.FileCrypto
import de.systragon.beam.media.MediaStoreSaver
import de.systragon.beam.media.VideoCompressor
import de.systragon.beam.service.SeedingService
import de.systragon.beam.transfer.PendingSend
import de.systragon.beam.transfer.TorrentViewModel
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: TorrentViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* ignoriert */ }

    // Expliziter Import: Datei aus dem System-Dateiwähler holen (funktioniert immer, unabhängig
    // davon, ob Android die .beam-Verknüpfung anbietet). `*/*`, da .beam keinen registrierten MIME hat.
    private val importBeamLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) Thread { onImportedFile(uri) }.start()
        }

    // Einmalige SAF-Freigabe für Download/Beam → danach öffnet 📂 den echten Ordner im Dateimanager.
    private val openTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                viewModel.saveBeamFolderAccess(this, uri)
                viewModel.openBeamFolderTree(this)   // direkt nach dem Grant öffnen
            } else {
                Toast.makeText(this, "Ordner-Zugriff nicht erteilt", Toast.LENGTH_SHORT).show()
            }
        }

    // Medien-Lese-Berechtigung für den Chronologie-Modus (MediaStore-Abfrage der Zeitspanne).
    private val mediaReadLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Nach (ggf.) Erteilung die Übersicht neu berechnen → Chronologie kann jetzt auffüllen.
            viewModel.refreshPendingSummary(this, viewModel.pendingSend.value)
        }

    /** Fragt Lese-Rechte für Bilder/Videos an (für die Chronologie-Zeitspanne). */
    private fun requestMediaRead() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) mediaReadLauncher.launch(missing.toTypedArray())
        else viewModel.refreshPendingSummary(this, viewModel.pendingSend.value)
    }

    /** Öffnet den System-Screen für „Alle Dateien"-Zugriff (Direct-Backup / No-Copy-Seeden). */
    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) {}
        }
    }

    /** Öffnet den SAF-Ordnerwähler, vornavigiert auf Download/Beam. */
    private fun requestBeamFolder() {
        try {
            openTreeLauncher.launch(viewModel.beamFolderInitialUri())
        } catch (e: Exception) {
            Toast.makeText(this, "Konnte Ordner-Dialog nicht öffnen", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRuntimePermissions()

        // Datei-Share/Magnetlink nur beim ersten Start verarbeiten — nicht bei Neuerstellung
        // (sonst taucht ein bereits gelöschter Transfer nach z. B. Drehung wieder auf).
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            MaterialTheme {
                MainScreen(
                    viewModel = viewModel,
                    onShareApp = { shareApp() },
                    onSharePcApp = { shareAppPc() },
                    onBeamIt = { beamPending() },
                    onImportBeam = { importBeamLauncher.launch(arrayOf("*/*")) },
                    onRequestFolderAccess = { requestBeamFolder() },
                    onChronologyToggled = { on -> if (on) requestMediaRead() },
                    onRequestAllFiles = { requestAllFilesAccess() }
                )
            }
        }
    }

    /** Teilt die eigene installierte APK als Datei (zum Weitergeben an Freunde). */
    private fun shareApp() {
        Thread {
            try {
                val srcApk = File(applicationInfo.sourceDir)
                val outDir = File(cacheDir, "beamlinks").apply { mkdirs() }
                val outApk = File(outDir, "Beam.apk")
                srcApk.copyTo(outApk, overwrite = true)

                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outApk)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Beam! app")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "This is BEAM! the application to share files without a 3rd Party in " +
                            "between, it uses the torrent network to connect sender and receiver " +
                            "but then transfers the data from the senders phone to the receiver " +
                            "directly, privately, no unwanted listeners."
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runOnUiThread { startActivity(Intent.createChooser(share, "Share Beam app via…")) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Could not share app", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    /** Holt die AKTUELLE PC-Beam-MSI token-gated vom VPS und teilt sie als Datei (OS-Teilen-Leiste) →
     *  „Share PC-Beam!". So gibt das Handy auch die Windows-Version weiter, ohne sie ins APK zu bündeln. */
    private fun shareAppPc() {
        Thread {
            try {
                runOnUiThread { Toast.makeText(this, "Fetching PC-Beam… (~114 MB, best on Wi-Fi)", Toast.LENGTH_LONG).show() }
                val ep = de.systragon.beam.core.RelayConfig.parse(
                    runCatching { File(getExternalFilesDir(null), "relay.conf").takeIf { it.exists() }?.readText() }.getOrNull()
                )
                val outDir = File(cacheDir, "beamlinks").apply { mkdirs() }
                val outMsi = File(outDir, "Beam.msi")
                val conn = java.net.URL(de.systragon.beam.core.RelayConfig.msiUrl(ep.host)).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 60000
                if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
                conn.inputStream.use { ins -> outMsi.outputStream().use { ins.copyTo(it, 1 shl 16) } }
                if (outMsi.length() < 1_000_000) throw java.io.IOException("MSI too small (${outMsi.length()} B)")

                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outMsi)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Beam! for Windows (PC)")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "This is BEAM! for Windows — run the .msi to install. Beam shares files in full " +
                            "original quality, peer-to-peer, no cloud, no account."
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runOnUiThread { startActivity(Intent.createChooser(share, "Share PC-Beam! via…")) }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "shareAppPc failed", e)
                runOnUiThread { Toast.makeText(this, "Could not fetch PC-Beam: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /** POST_NOTIFICATIONS (API 33+) und WRITE_EXTERNAL_STORAGE (nur API < 29) anfragen. */
    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when {
            intent?.action == Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) Thread { onIncomingSend(uri) }.start()
            }
            intent?.action == Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) {
                    if (uris.size == 1) {
                        Thread { onIncomingSend(uris[0]) }.start()
                    } else {
                        runOnUiThread {
                            viewModel.setPendingSend(PendingSend(uris.toList(), "${uris.size} files"))
                        }
                    }
                }
            }
            intent?.action == Intent.ACTION_VIEW &&
                    intent.data?.scheme == "magnet" -> {
                intent.data?.toString()?.let { startDownload(it) }
            }
            // content:// oder file:// → vermutlich eine .beam-Datei (z. B. Messenger-Anhang)
            intent?.action == Intent.ACTION_VIEW && intent.data != null -> {
                val uri = intent.data!!
                Thread { processBeamFile(uri) }.start()
            }
        }
    }

    /** Geteilte Inhalte: .beam → sofort Download; sonst auf der Hauptseite bereitstellen
     *  (Einstellungen änderbar), das Senden startet erst per „BEAM! it". */
    private fun onIncomingSend(uri: Uri) {
        val (fileName, _) = readFileMetadata(uri)
        if (fileName.endsWith(".beam", ignoreCase = true)) {
            processBeamFile(uri)
            return
        }
        runOnUiThread { viewModel.setPendingSend(PendingSend(listOf(uri), fileName)) }
    }

    /** „BEAM! it": die bereitgestellte(n) Datei(en) mit den aktuellen Einstellungen senden. */
    private fun beamPending() {
        val pending = viewModel.pendingSend.value ?: return
        val chrono = viewModel.chronologyMode.value
        val direct = viewModel.directAccess.value
        viewModel.clearPendingSend()
        if (chrono || direct) {
            // Chronologie und/oder Direct File Access laufen über einen gemeinsamen Vorbereitungs-Thread:
            // (1) bei Chrono die Anker zur vollen Zeitspanne auffüllen, (2) bei Direct die Originale
            // ohne Kopie in place seeden (Fallback: bestehende Bündel-Pipeline). Beide Modi senden 1:1
            // (Verschlüsseln/Komprimieren sind hier ohnehin aus).
            Toast.makeText(this, if (chrono) "Building chronology…" else "Preparing…", Toast.LENGTH_SHORT).show()
            Thread {
                val uris = if (chrono) viewModel.expandChronology(this, pending.uris) else pending.uris
                if (uris.isEmpty()) {
                    runOnUiThread { Toast.makeText(this, "No media found in that time range", Toast.LENGTH_LONG).show() }
                    return@Thread
                }
                if (direct) {
                    val (ok, files) = viewModel.canDirectSeed(this, uris)
                    if (ok) { directSeedInPlace(uris, files); return@Thread }
                    runOnUiThread {
                        Toast.makeText(this, "Direct access not possible for these files — copying instead", Toast.LENGTH_LONG).show()
                    }
                }
                runOnUiThread { bundleAndSend(uris) }
            }.start()
            return
        }
        if (pending.uris.size == 1) {
            val uri = pending.uris[0]
            val (fileName, _) = readFileMetadata(uri)
            val mime = contentResolver.getType(uri) ?: MediaStoreSaver.guessMime(fileName)
            val level = viewModel.effectiveCompressionLevel()
            if (mime.startsWith("video/") && VideoCompressor.needsCompression(this, uri, level)) {
                compressThenSend(uri, fileName, level)   // Main-Thread (Transformer braucht Looper)
            } else {
                Thread { processSendFile(uri) }.start()
            }
        } else {
            bundleAndSend(pending.uris)
        }
    }

    /** Mehrere Dateien in einen Ordner bündeln (komprimieren/verschlüsseln je Datei), dann ein
     *  einziger Multi-File-Torrent. Sequentiell, da Komprimierung den Main-Looper braucht. */
    private fun bundleAndSend(uris: List<Uri>) {
        val bundleDir = File(cacheDir, "bundle_${System.currentTimeMillis()}").apply { mkdirs() }
        viewModel.prepareStart("${uris.size} files", "Preparing")
        processBundleItem(uris, 0, bundleDir)
    }

    /**
     * Direct File Access: die Originale OHNE Cache-Kopie direkt von der Platte seeden (No-Copy). Der
     * Torrent wird relativ zu /storage/emulated/0 gebaut (Pfade `DCIM/Camera/…`), save_path = derselbe
     * Wurzelpfad → libtorrent hasht/serviert die Originale in place. Muss auf einem Hintergrund-Thread
     * laufen (Hashing). `.bk`-getaggt (Backup) → PC fragt nach Zielordner. Fehler → Kopie-Pipeline.
     */
    private fun directSeedInPlace(uris: List<Uri>, files: List<File>) {
        val parent = Environment.getExternalStorageDirectory()   // /storage/emulated/0
        val displayName = "${files.size} files"
        viewModel.prepareStart(displayName, "Hashing")
        viewModel.prepareHashing()
        val created = try {
            de.systragon.beam.core.TorrentFactory.createInPlace(parent, files, displayName, cacheDir)
        } catch (e: Exception) {
            null
        } finally {
            viewModel.prepareDone()
        }
        if (created == null) {
            runOnUiThread {
                Toast.makeText(this, "Direct seeding failed — copying instead", Toast.LENGTH_LONG).show()
                bundleAndSend(uris)
            }
            return
        }
        val totalBytes = files.sumOf { it.length() }
        val serviceIntent = Intent(this, SeedingService::class.java).apply {
            action = SeedingService.ACTION_ADD_TORRENT
            putExtra(SeedingService.EXTRA_TORRENT_PATH, created.torrentFile.absolutePath)
            putExtra(SeedingService.EXTRA_FILE_DIR, parent.absolutePath)
            putExtra(SeedingService.EXTRA_FILE_NAME, displayName)
            putExtra(SeedingService.EXTRA_FILE_SIZE, totalBytes)
            putExtra(SeedingService.EXTRA_INFO_HASH, created.infoHash)
        }
        startForegroundService(serviceIntent)
        shareBeamLink(displayName, created.infoHash, created.magnet)
    }

    private fun processBundleItem(uris: List<Uri>, index: Int, bundleDir: File) {
        if (index >= uris.size) {
            Thread {
                try { shareLocalFile(bundleDir, bundleDir.name, alreadyPrepared = true) }
                finally { viewModel.prepareDone() }
            }.start()
            return
        }
        viewModel.prepareProgress(index.toFloat() / uris.size)

        val uri = uris[index]
        val (name, _) = readFileMetadata(uri)
        val mime = contentResolver.getType(uri) ?: MediaStoreSaver.guessMime(name)
        val level = viewModel.effectiveCompressionLevel()

        if (mime.startsWith("video/") && VideoCompressor.needsCompression(this, uri, level)) {
            val base = name.substringBeforeLast('.', name)
            val out = File(bundleDir, "${base}_${level.tag}.mp4")
            VideoCompressor.compress(
                context = this,
                source = uri,
                level = level,
                outFile = out,
                onProgress = { },
                onSuccess = { f ->
                    Thread {
                        keepCompressedIfEnabled(f)
                        finalizeBundleFile(f)
                        runOnUiThread { processBundleItem(uris, index + 1, bundleDir) }
                    }.start()
                },
                onError = { msg ->
                    viewModel.prepareDone()
                    Toast.makeText(this, "Compression failed: $msg", Toast.LENGTH_LONG).show()
                }
            )
        } else {
            Thread {
                val copied = File(bundleDir, name)
                copyUriToFile(uri, copied)
                finalizeBundleFile(copied)
                runOnUiThread { processBundleItem(uris, index + 1, bundleDir) }
            }.start()
        }
    }

    /** Verschlüsselt eine Bündel-Datei in-place (falls aktiv): <name> → <name>.beamenc. */
    private fun finalizeBundleFile(file: File) {
        if (viewModel.encryptionActive()) {
            val enc = File(file.parentFile, file.name + ".beamenc")
            FileCrypto.encrypt(file, file.name, viewModel.passphrase.value, enc) { }
            file.delete()
        }
    }

    private fun copyUriToFile(uri: Uri, dest: File) {
        contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /** Video transcodieren (gewählte Stufe), Ausgabename mit Stufen-Suffix, dann senden. */
    private fun compressThenSend(uri: Uri, fileName: String, level: VideoCompressor.CompressionLevel) {
        val baseName = fileName.substringBeforeLast('.', fileName)
        val outFile = File(cacheDir, "${baseName}_${level.tag}.mp4")
        viewModel.prepareStart(outFile.name, "Compressing")
        VideoCompressor.compress(
            context = this,
            source = uri,
            level = level,
            outFile = outFile,
            onProgress = { f -> viewModel.prepareProgress(f) },
            onSuccess = { file ->
                Thread {
                    keepCompressedIfEnabled(file)
                    try { shareLocalFile(file, file.name) } finally { viewModel.prepareDone() }
                }.start()
            },
            onError = { msg ->
                viewModel.prepareDone()
                Toast.makeText(this, "Compression failed: $msg", Toast.LENGTH_LONG).show()
            }
        )
    }

    /** Komprimiertes Video behalten: Kopie nach Download/Beam, wenn die Einstellung aktiv ist. */
    private fun keepCompressedIfEnabled(file: File) {
        if (viewModel.keepCompressed.value) {
            MediaStoreSaver.publishToDownloads(this, file, file.name)
        }
    }

    /**
     * Per „Import"-Button gewählte Datei verarbeiten:
     *  - `.beamenc`  → lokal mit der aktuellen Passphrase entschlüsseln und in die Galerie/Downloads
     *                  veröffentlichen (gleiche Logik wie beim Empfang).
     *  - sonst        → als `.beam`-Link behandeln und den Download starten.
     */
    private fun onImportedFile(uri: Uri) {
        val (name, _) = readFileMetadata(uri)
        if (FileCrypto.isEncryptedName(name)) {
            importEncrypted(uri, name)
        } else {
            processBeamFile(uri)
        }
    }

    /** Einen `.beamenc`-Container lokal entschlüsseln (Passphrase aus der Schloss-Leiste). */
    private fun importEncrypted(uri: Uri, displayName: String) {
        val pass = viewModel.passphrase.value
        if (pass.isBlank()) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Erst die Passphrase oben eingeben, dann importieren",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        val tmpDir = File(cacheDir, "import_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val container = File(tmpDir, displayName)
            copyUriToFile(uri, container)
            val (realName, plain) = FileCrypto.decrypt(container, pass, tmpDir) { }
            val saved = MediaStoreSaver.publish(applicationContext, plain, realName)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (saved != null) "Entschlüsselt: $realName" else "Speichern fehlgeschlagen",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Falsche Passphrase oder keine Beam-Datei", Toast.LENGTH_LONG).show()
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /** Liest eine .beam-Datei und startet den Download. */
    private fun processBeamFile(uri: Uri) {
        // 1) Primär: Hash direkt aus dem Dateinamen (<originalname>.<hash>.beam) — kein Stream nötig.
        val (displayName, _) = readFileMetadata(uri)
        // 2) Fallback: Inhalt lesen (falls ein Messenger den Namen umbenannt/gekürzt hat).
        val magnet = magnetFromBeamFileName(displayName)
            ?: readBeamFileContent(uri)?.let { magnetFromBeamContent(it) }

        if (magnet != null) {
            startDownload(magnet)
        } else {
            runOnUiThread {
                Toast.makeText(this, "Not a valid Beam file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Liest die ersten 4 KB der Datei (winzig); schützt vor OOM bei Fehlöffnung großer Dateien. */
    private fun readBeamFileContent(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(4096)
                val n = stream.read(buf)
                if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extrahiert Hash + optionalen Peer-Hint aus `<originalname>.<40-hex-hash>[.pe<peer>].beam`
     * und baut daraus einen Magnetlink. Peer-Hints werden als `&x.pe=ip:port` ergänzt → der
     * Empfänger verbindet direkt (nur intern, wird nirgends geteilt).
     */
    private fun magnetFromBeamFileName(name: String): String? {
        if (!name.endsWith(".beam", ignoreCase = true)) return null
        val core = name.substring(0, name.length - ".beam".length)
        val segments = core.split('.')
        // Hash-Segment suchen (statt fix „letztes" — danach können pe-Segmente folgen).
        val hashIdx = segments.indexOfFirst { Regex("^[A-Fa-f0-9]{40}$").matches(it) }
        if (hashIdx < 0) return null
        val hash = segments[hashIdx]
        val original = segments.subList(0, hashIdx).joinToString(".")
        val peers = segments.drop(hashIdx + 1).mapNotNull { de.systragon.beam.core.PeerHint.decode(it) }
        var magnet = viewModel.buildMagnetLink(hash.lowercase(), original)
        peers.forEach { magnet += "&x.pe=$it" }
        return magnet
    }

    /** Akzeptiert sowohl einen vollständigen magnet:-Link als auch einen reinen InfoHash. */
    private fun magnetFromBeamContent(content: String): String? {
        val t = content.trim()
        return when {
            t.startsWith("magnet:") -> t.substringBefore('\n').trim()
            Regex("^[A-Fa-f0-9]{40}$").matches(t) -> "magnet:?xt=urn:btih:${t.lowercase()}"
            else -> null
        }
    }

    /** Magnetlink an den Service übergeben — Download landet erst in einem app-privaten Ordner. */
    private fun startDownload(magnetUri: String) {
        val saveDir = File(filesDir, "incoming").apply { mkdirs() }
        val serviceIntent = Intent(this, SeedingService::class.java).apply {
            action = SeedingService.ACTION_ADD_MAGNET
            putExtra(SeedingService.EXTRA_MAGNET_URI, magnetUri)
            putExtra(SeedingService.EXTRA_FILE_DIR, saveDir.absolutePath)
        }
        startForegroundService(serviceIntent)
    }

    private fun processSendFile(uri: Uri) {
        val (fileName, fileSize) = readFileMetadata(uri)
        // Overlay zeigen bei großen Dateien ODER wenn verschlüsselt wird (beides dauert).
        val showPrepare = fileSize > 50L * 1024 * 1024 || viewModel.encryptionActive()
        if (showPrepare) viewModel.prepareStart(fileName, "Copying")

        try {
            val cachedFile = copyToCache(
                uri, fileName, fileSize,
                onProgress = if (showPrepare) { f -> viewModel.prepareProgress(f) } else null
            ) ?: return

            shareLocalFile(cachedFile, fileName)
        } finally {
            if (showPrepare) viewModel.prepareDone()
        }
    }

    /** Baut aus einer bereits lokal vorliegenden Datei den Torrent, startet den Seed-Service
     *  und öffnet den .beam-Teilen-Dialog. Optional wird vorher verschlüsselt.
     *  (Genutzt für Original-Kopie UND komprimierte Videos.) */
    private fun shareLocalFile(file: File, originalName: String, alreadyPrepared: Boolean = false) {
        // Optional verschlüsseln: echten Inhalt + Namen in einen .beamenc-Container packen.
        // (Beim Bündel ist bereits jede Datei einzeln verschlüsselt → alreadyPrepared.)
        val toShare = if (!alreadyPrepared && viewModel.encryptionActive()) {
            viewModel.prepareStart(originalName, "Encrypting")
            val container = File(cacheDir, "Beam_${System.currentTimeMillis()}.beamenc")
            try {
                FileCrypto.encrypt(file, originalName, viewModel.passphrase.value, container) { f ->
                    viewModel.prepareProgress(f)
                }
                container
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Encryption failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return
            }
        } else {
            file
        }

        viewModel.prepareHashing()
        val (magnetLink, torrentFile, infoHash) = buildTorrentMagnet(toShare)
        if (magnetLink == null || torrentFile == null || infoHash == null) return

        val serviceIntent = Intent(this, SeedingService::class.java).apply {
            action = SeedingService.ACTION_ADD_TORRENT
            putExtra(SeedingService.EXTRA_TORRENT_PATH, torrentFile.absolutePath)
            putExtra(SeedingService.EXTRA_FILE_DIR, toShare.parent)
            putExtra(SeedingService.EXTRA_FILE_NAME, toShare.name)
            putExtra(SeedingService.EXTRA_FILE_SIZE, toShare.length())
            putExtra(SeedingService.EXTRA_INFO_HASH, infoHash)
        }
        startForegroundService(serviceIntent)

        // Als .beam-Datei verschicken (in Messengern als Anhang tippbar)
        shareBeamLink(toShare.name, infoHash, magnetLink)
    }

    /**
     * Schreibt eine `<name>.<hash>[.pe<peer>].beam`-Datei (Hash steckt im Namen → Empfänger liest
     * nur den Dateinamen) und öffnet das Share-Sheet. Inhalt = portabler Magnetlink (ohne Peer-Hint).
     * Der optionale `pe…`-Teil kodiert die aktuelle LAN-/Hotspot-IP für direktes Verbinden.
     */
    private fun shareBeamLink(fileName: String, infoHash: String, magnetLink: String) {
        val linksDir = File(cacheDir, "beamlinks").apply { mkdirs() }
        val hint = de.systragon.beam.core.PeerHint.localSegment()
        var core = if (hint != null) "$fileName.$infoHash.$hint" else "$fileName.$infoHash"
        // Chronologie- ODER Direct-Access-Backup → .bk-Token anhängen, damit der PC-Empfänger nach dem
        // Zielordner (z. B. NAS-Bildersammlung) fragt, statt stumpf in Download/Beam zu legen.
        if (viewModel.chronologyMode.value || viewModel.directAccess.value) core += ".bk"
        val beamFile = File(linksDir, "$core.beam")
        beamFile.writeText(magnetLink)

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", beamFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Beam!: $fileName")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runOnUiThread {
            startActivity(Intent.createChooser(shareIntent, "Share Beam file via…"))
        }
    }

    private fun buildTorrentMagnet(file: File): Triple<String?, File?, String?> {
        return try {
            val builder = TorrentBuilder()
            builder.path(file)
            builder.pieceSize(0)
            // V1_ONLY: sonst Hybrid-Torrent (v1+v2) → Seeder & Magnet-Empfänger zeigen verschiedene
            // Infohashes im Handshake → die Relay-Pipe paart sie nie. v1-only = ein Infohash für alle.
            builder.flags(TorrentBuilder.V1_ONLY)

            val result = builder.generate()
            val bencode = result.entry().bencode()

            val torrentFile = File(cacheDir, "${file.nameWithoutExtension}.torrent")
            torrentFile.writeBytes(bencode)

            val torrentInfo = TorrentInfo(bencode)
            val infoHash = torrentInfo.infoHash().toString()

            // Keine Tracker im Link — App ergänzt sie automatisch.
            // Einheitliche Magnet-Erzeugung über das ViewModel (keine Duplikation).
            val magnetLink = viewModel.buildMagnetLink(infoHash, file.name)

            Triple(magnetLink, torrentFile, infoHash)
        } catch (e: Exception) {
            Triple(null, null, null)
        }
    }

    private fun copyToCache(
        uri: Uri,
        fileName: String,
        totalSize: Long,
        onProgress: ((Float) -> Unit)? = null
    ): File? {
        return try {
            val cacheFile = File(cacheDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    if (onProgress == null || totalSize <= 0) {
                        input.copyTo(output)
                    } else {
                        val buf = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastReported = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            copied += n
                            // ~alle 2 MB melden, um die UI nicht zu fluten
                            if (copied - lastReported >= 2L * 1024 * 1024) {
                                lastReported = copied
                                onProgress(copied.toFloat() / totalSize)
                            }
                        }
                        onProgress(1f)
                    }
                }
            }
            cacheFile
        } catch (e: Exception) {
            null
        }
    }

    private fun readFileMetadata(uri: Uri): Pair<String, Long> {
        var fileName = "unbekannt"
        var fileSize = -1L
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) fileName = cursor.getString(nameIndex)
                if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex)
            }
        }
        return Pair(fileName, fileSize)
    }
}
