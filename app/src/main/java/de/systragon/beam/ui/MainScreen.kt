package de.systragon.beam.ui

import android.content.Context
import android.widget.Toast
import de.systragon.beam.core.TorrentEntry
import de.systragon.beam.core.TorrentState
import de.systragon.beam.media.VideoCompressor
import de.systragon.beam.transfer.PendingSummary
import de.systragon.beam.transfer.PrepareInfo
import de.systragon.beam.transfer.TorrentViewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: TorrentViewModel,
    onShareApp: () -> Unit = {},
    onSharePcApp: () -> Unit = {},
    onBeamIt: () -> Unit = {},
    onImportBeam: () -> Unit = {},
    onRequestFolderAccess: () -> Unit = {},
    onChronologyToggled: (Boolean) -> Unit = {},
    onRequestAllFiles: () -> Unit = {}
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    // Wenn gesetzt, liegt der Vollbild-Streaming-Player über der App.
    var streamingHash by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    // Einmal pro App-Start prüfen: ist Beam von der Akku-Optimierung ausgenommen? Wenn nicht,
    // drosselt Android die Übertragung im Hintergrund → Hinweis-Prompt zeigen.
    var showBatteryPrompt by remember { mutableStateOf(!isIgnoringBatteryOptimizations(context)) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Beam!", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = Color.White
                    ),
                    actions = {
                        IconButton(onClick = { showHelp = true }) {
                            Icon(Icons.Default.Info, contentDescription = "Help", tint = Color.White)
                        }
                    }
                )
            },
            floatingActionButton = {
                if (selectedTab == 0) {
                    // Links: .beam aus dem Dateimanager importieren (zuverlässig, unabhängig von der
                    // Datei-Verknüpfung). Rechts: Magnet/Link aus der Zwischenablage einfügen.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExtendedFloatingActionButton(
                            onClick = onImportBeam,
                            icon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                            text = { Text("Import") }
                        )
                        Spacer(Modifier.width(12.dp))
                        ExtendedFloatingActionButton(
                            onClick = { viewModel.pasteLinkFromClipboard(context) },
                            icon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                            text = { Text("Paste link") }
                        )
                    }
                }
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {

                // Tab-Leiste (die Konfig-Leiste steht jetzt IM Transfers-Reiter, bei den Torrent-Karten)
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Transfers") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Settings") }
                    )
                }

                when (selectedTab) {
                    0 -> TransferScreen(
                        viewModel = viewModel,
                        context = context,
                        onStream = { entry ->
                            viewModel.startStreaming(entry.infoHash)
                            streamingHash = entry.infoHash
                        },
                        onRequestFolderAccess = onRequestFolderAccess,
                        onBeamIt = onBeamIt,
                        onChronologyToggled = onChronologyToggled,
                        onRequestAllFiles = onRequestAllFiles
                    )
                    1 -> SettingsScreen(viewModel, onShareApp, onSharePcApp)
                }
            }
        }

        // Vollbild-Streaming-Player als Overlay
        streamingHash?.let { hash ->
            VideoPlayerScreen(infoHash = hash, onClose = { streamingHash = null })
        }

        // Fortschritts-Overlay beim Vorbereiten großer Sende-Dateien
        val prepare by viewModel.prepare.collectAsState()
        prepare?.let { info -> PrepareOverlay(info) }

        if (showHelp) HelpDialog(onClose = { showHelp = false })
        if (showBatteryPrompt) BatteryOptimizationDialog(
            onEnable = {
                requestIgnoreBatteryOptimizations(context)
                showBatteryPrompt = false
            },
            onDismiss = { showBatteryPrompt = false }
        )
    }
}

/**
 * Hinweis, dass Beam von der Akku-Optimierung ausgenommen werden sollte — sonst drosselt/pausiert
 * Android die Übertragung im Hintergrund/bei ausgeschaltetem Bildschirm. „Aktivieren" öffnet den
 * System-Dialog zum Freischalten.
 */
@Composable
private fun BatteryOptimizationDialog(onEnable: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    "Akku-Optimierung ausnehmen",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "Damit Übertragungen nicht ausgebremst oder unterbrochen werden, sollte Beam von " +
                        "der Akku-Optimierung ausgenommen werden. Sonst drosselt Android die Verbindung, " +
                        "sobald der Bildschirm aus ist oder die App im Hintergrund läuft.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Später") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onEnable) { Text("Aktivieren") }
                }
            }
        }
    }
}

/** True, wenn Beam von der Akku-Optimierung ausgenommen ist (oder die Prüfung fehlschlägt → nicht nerven). */
private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean =
    runCatching {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(true)

/** Öffnet den System-Dialog „Akku-Optimierung ignorieren?" für Beam (Fallback: allgemeine Liste). */
@android.annotation.SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizations(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${context.packageName}")
            )
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }
    }
}

@Composable
private fun HelpDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val appVersion = remember { appVersionName(context) }
    // Inhalt frei editierbar in app/src/main/assets/help.md
    val helpText = remember {
        runCatching {
            context.assets.open("help.md").bufferedReader().use { it.readText() }
        }.getOrDefault("Help is currently unavailable.")
    }

    // Platzhalter im Hilfetext durch echte Icons ersetzen: "(V)" → Share, "(R)" → Reload.
    val annotatedHelp = remember(helpText) {
        val tokenRegex = Regex("\\((V|R)\\)")
        buildAnnotatedString {
            var last = 0
            for (m in tokenRegex.findAll(helpText)) {
                append(helpText.substring(last, m.range.first))
                appendInlineContent(if (m.value == "(V)") "shareIcon" else "reloadIcon", m.value)
                last = m.range.last + 1
            }
            append(helpText.substring(last))
        }
    }
    val iconPlaceholder = Placeholder(
        width = 1.3.em,
        height = 1.3.em,
        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
    )
    val inlineContent = mapOf(
        "shareIcon" to InlineTextContent(iconPlaceholder) {
            Icon(Icons.Default.Share, contentDescription = "Re-Share", modifier = Modifier.fillMaxSize())
        },
        "reloadIcon" to InlineTextContent(iconPlaceholder) {
            Icon(Icons.Default.Refresh, contentDescription = "Reconnect/Retry", modifier = Modifier.fillMaxSize())
        }
    )

    Dialog(onDismissRequest = onClose) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    annotatedHelp,
                    style = MaterialTheme.typography.bodyMedium,
                    inlineContent = inlineContent
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Version $appVersion",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                    Text("Got it")
                }
            }
        }
    }
}

@Composable
private fun PrepareOverlay(info: PrepareInfo) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    "Preparing link…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    info.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (info.progress < 0f) {
                    // Hashing-Phase: unbestimmt
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Generating torrent…", style = MaterialTheme.typography.labelSmall)
                } else {
                    LinearProgressIndicator(
                        progress = { info.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("${info.phase}… ${(info.progress * 100).toInt()} %", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun EncryptionBar(
    viewModel: TorrentViewModel,
    onBeamIt: () -> Unit,
    onChronologyToggled: (Boolean) -> Unit = {},
    onRequestAllFiles: () -> Unit = {}
) {
    val enabled by viewModel.encryptEnabled.collectAsState()
    val passphrase by viewModel.passphrase.collectAsState()
    val level by viewModel.compressionLevel.collectAsState()
    val pending by viewModel.pendingSend.collectAsState()
    val chronologyOn by viewModel.chronologyMode.collectAsState()
    val directOn by viewModel.directAccess.collectAsState()
    val summary by viewModel.pendingSummary.collectAsState()
    val context = LocalContext.current
    // Originale 1:1 (Chronologie ODER Direct File Access) → Verschlüsseln/Qualität deaktiviert.
    val optionsLocked = chronologyOn || directOn
    var visible by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    // „Was wird gesendet?"-Übersicht im Hintergrund berechnen, wenn sich Auswahl/Modus ändert.
    LaunchedEffect(pending, chronologyOn) { viewModel.refreshPendingSummary(context, pending) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        // Verschlüsselung (im Chronologie-Modus deaktiviert/grau — der „Chrono"-Schalter sitzt jetzt
        // in der „Ready to send"-Vorschaukarte unten).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (enabled && !optionsLocked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Checkbox(
                checked = enabled && !optionsLocked,
                enabled = !optionsLocked,
                onCheckedChange = { viewModel.setEncryptEnabled(it) }
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { viewModel.setPassphrase(it) },
                modifier = Modifier.weight(1f),
                enabled = !optionsLocked,
                singleLine = true,
                label = { Text("Encryption passphrase") },
                textStyle = MaterialTheme.typography.bodySmall,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle passphrase visibility"
                        )
                    }
                }
            )
        }

        // Video-Komprimierungsstufe (links) + „BEAM! it" (rechts)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Video quality:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box {
                TextButton(onClick = { menuOpen = true }, enabled = !optionsLocked) {
                    Text(if (level == VideoCompressor.CompressionLevel.ORIGINAL) "Original" else level.tag)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    VideoCompressor.CompressionLevel.entries.forEach { l ->
                        DropdownMenuItem(
                            text = { Text(l.label) },
                            onClick = {
                                viewModel.setCompressionLevel(l)
                                menuOpen = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (pending != null) {
                Button(onClick = onBeamIt) { Text("BEAM! it") }
            }
        }

        // „Was wird gesendet?"-Übersicht — verschwindet, sobald die Transfer-Karte erscheint.
        pending?.let { p ->
            PendingSummaryCard(
                label = p.label,
                chronology = chronologyOn,
                direct = directOn,
                summary = summary,
                onChronologyChange = { viewModel.setChronologyMode(it); onChronologyToggled(it) },
                onDirectChange = { viewModel.setDirectAccess(it); if (it && !viewModel.hasAllFilesAccess()) onRequestAllFiles() },
                onCancel = { viewModel.clearPendingSend() }
            )
        }
    }
}

/** Kurze Übersicht der bereitgestellten Auswahl (Typ + Quelle) vor „BEAM! it" — inkl. „Chrono"-Schalter. */
@Composable
private fun PendingSummaryCard(
    label: String,
    chronology: Boolean,
    direct: Boolean,
    summary: PendingSummary?,
    onChronologyChange: (Boolean) -> Unit,
    onDirectChange: (Boolean) -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Kopfzeile: Titel + „Chrono"-Schalter (hier logischer aufgehoben als oben).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (chronology) "🗓️ Chronology — ready to send" else "Ready to send",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text("Chrono", style = MaterialTheme.typography.bodySmall)
                Checkbox(checked = chronology, onCheckedChange = onChronologyChange)
            }
            // „Direct File Access" (No-Copy): Originale direkt seeden statt kopieren — wie Chrono
            // schließt es Verschlüsseln/Komprimieren aus (Originale 1:1). Braucht „Alle Dateien".
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.weight(1f))
                Text("Direct File Access", style = MaterialTheme.typography.bodySmall)
                Checkbox(checked = direct, onCheckedChange = onDirectChange)
            }
            if (summary == null) {
                Text("Scanning $label…", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "${summary.total} items · ${formatSize(summary.totalBytes)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                val types = buildList {
                    if (summary.photos > 0) add("📷 ${summary.photos} photos")
                    if (summary.videos > 0) add("🎬 ${summary.videos} videos")
                    if (summary.others > 0) add("📄 ${summary.others} other")
                }.joinToString("    ")
                if (types.isNotBlank()) Text(types, style = MaterialTheme.typography.bodySmall)
                val src = buildList {
                    if (summary.camera > 0) add("Camera ${summary.camera}")
                    if (summary.whatsapp > 0) add("WhatsApp ${summary.whatsapp}")
                    if (summary.screenshots > 0) add("Screenshots ${summary.screenshots}")
                    if (summary.social > 0) add("Social ${summary.social}")
                    if (summary.otherSource > 0) add("Other ${summary.otherSource}")
                }.joinToString(" · ")
                if (src.isNotBlank()) Text(
                    src,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Abbrechen: verwirft die bereitgestellte Aufgabe komplett (Karte verschwindet).
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
fun TransferScreen(
    viewModel: TorrentViewModel,
    context: Context,
    onStream: (TorrentEntry) -> Unit,
    onRequestFolderAccess: () -> Unit = {},
    onBeamIt: () -> Unit = {},
    onChronologyToggled: (Boolean) -> Unit = {},
    onRequestAllFiles: () -> Unit = {}
) {
    val torrents by viewModel.torrents.collectAsState()
    val welcomePrefs = remember { context.getSharedPreferences("beam_prefs", Context.MODE_PRIVATE) }
    var welcomeSeen by remember { mutableStateOf(welcomePrefs.getBoolean("welcomeSeen", false)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Höfliche Welcome-Karte beim ersten Start (X = weg, gemerkt).
        if (!welcomeSeen) item {
            WelcomeCard(onDismiss = {
                welcomePrefs.edit().putBoolean("welcomeSeen", true).apply(); welcomeSeen = true
            })
        }
        // Konfig-Leiste (Sende-Optionen + „Ready to send"-Karte) als ERSTES Listenelement — genau da,
        // wo auch die Torrent-Karten stehen; scrollt mit.
        item { EncryptionBar(viewModel, onBeamIt, onChronologyToggled, onRequestAllFiles) }

        if (torrents.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🧲", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "No active transfers",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Share a file from the gallery",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(torrents, key = { it.infoHash }) { entry ->
                TorrentCard(
                    entry = entry,
                    onCopyClick = {
                        viewModel.copyMagnetLink(context, entry)
                        Toast.makeText(context, "Magnet link copied!", Toast.LENGTH_SHORT).show()
                    },
                    onShareClick = { viewModel.shareTorrentLink(context, entry) },
                    onStartOverClick = {
                        viewModel.restartTransfer(context, entry.infoHash)
                        Toast.makeText(context, "Restarting transfer…", Toast.LENGTH_SHORT).show()
                    },
                    onRelayClick = {
                        viewModel.engageRelay(context, entry.infoHash)
                        Toast.makeText(context, "Relay engaged 📡", Toast.LENGTH_SHORT).show()
                    },
                    onPlayClick = {
                        when (downloadCardAction(entry)) {
                            // Verschlüsselt + fehlgeschlagen → mit aktueller Passphrase erneut entschlüsseln
                            CardAction.RETRY_DECRYPT -> {
                                viewModel.retryDecrypt(context, entry.infoHash)
                                Toast.makeText(context, "Retrying…", Toast.LENGTH_SHORT).show()
                            }
                            // Abspielen: unverschlüsseltes Video → interner Player (streamt/spielt),
                            // sonst (verschlüsseltes Video u. ä.) → externer Standard-Player.
                            CardAction.PLAY -> {
                                val mime = de.systragon.beam.media.MediaStoreSaver.guessMime(entry.fileName)
                                if (!entry.isEncrypted && mime.startsWith("video/") &&
                                    (entry.state == TorrentState.DOWNLOADING || entry.state == TorrentState.COMPLETED)) {
                                    onStream(entry)
                                } else {
                                    viewModel.openFile(context, entry)
                                }
                            }
                            // Fertig, kein Einzelvideo → Ordner/Galerie öffnen. Bei Daten ohne SAF-
                            // Berechtigung einmalig den Ordner-Freigabe-Dialog auslösen (danach öffnet
                            // er den echten Download/Beam-Ordner direkt).
                            CardAction.OPEN_FOLDER ->
                                if (!entry.mediaOnly && !viewModel.hasBeamFolderAccess(context)) {
                                    onRequestFolderAccess()
                                } else {
                                    viewModel.openFolder(context, entry)
                                }
                            CardAction.HOURGLASS -> { /* deaktiviert, nichts zu tun */ }
                        }
                    },
                    onThrowClick = { viewModel.throwTransfer(context, entry.infoHash) },
                    onDismissClick = { viewModel.dismissTransfer(context, entry.infoHash) }
                )
            }
        }
    }
}

/** Welche Aktion der EINE Empfangs-Button anbietet — eine Wahrheit für Icon UND Klick. */
private enum class CardAction { HOURGLASS, PLAY, OPEN_FOLDER, RETRY_DECRYPT }

private fun downloadCardAction(entry: TorrentEntry): CardAction {
    val isVideo = de.systragon.beam.media.MediaStoreSaver.guessMime(entry.fileName).startsWith("video/")
    return when {
        // Verschlüsselt + fehlgeschlagen (falsche Passphrase) → erneut entschlüsseln
        entry.isEncrypted && entry.state == TorrentState.ERROR -> CardAction.RETRY_DECRYPT
        // Fertig: Einzelvideo → abspielen; alles andere → Ordner/Galerie öffnen
        entry.state == TorrentState.COMPLETED ->
            if (isVideo) CardAction.PLAY else CardAction.OPEN_FOLDER
        // Läuft noch: nur ein streambares Einzelvideo bietet etwas an
        // (▶ sobald die Eckblöcke da sind, bis dahin ⏳)
        isVideo && !entry.isEncrypted && entry.state == TorrentState.DOWNLOADING ->
            if (entry.streamReady) CardAction.PLAY else CardAction.HOURGLASS
        // Sonst (PDF/Bild/Bündel im Download, Metadaten holen …) → noch nichts abspielbar
        else -> CardAction.HOURGLASS
    }
}

@Composable
fun TorrentCard(
    entry: TorrentEntry,
    onCopyClick: () -> Unit,
    onShareClick: () -> Unit,
    onStartOverClick: () -> Unit,
    onRelayClick: () -> Unit,
    onPlayClick: () -> Unit,
    onThrowClick: () -> Unit,
    onDismissClick: () -> Unit
) {
    val showProgress = entry.state == TorrentState.DOWNLOADING ||
            entry.state == TorrentState.FETCHING_METADATA

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            // Kopfzeile: Richtungspfeil + Name + kompakte Statuszeile
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (entry.isDownload) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                    contentDescription = if (entry.isDownload) "Download" else "Upload",
                    tint = if (entry.isDownload) Color(0xFF2196F3) else Color(0xFF4CAF50),
                    modifier = Modifier.padding(end = 10.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.fileName,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = statusLine(entry),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.state == TorrentState.ERROR)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (entry.isEncrypted) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Encrypted",
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Fortschrittsbalken beim Empfang
            if (showProgress) {
                Spacer(modifier = Modifier.height(6.dp))
                if (entry.state == TorrentState.FETCHING_METADATA) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { entry.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Relay-Empfehlung: Gegenüber bekannt, aber direkte Verbindung kommt nicht durch. Beide
            // Nutzer sehen das ~gleichzeitig → beide tippen 📡 → Paarung an der Beam-Relaystation.
            if (entry.directBlocked) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "⚠ Direct connection blocked — tap 📡 to send via the Beam-Relay-Station",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Button-Reihe: links Link kopieren (+ Play beim Empfänger),
            // rechts Mülltonne (alles löschen) und ✕ (nur Karte entfernen)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(onClick = onCopyClick) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy link")
                }
                FilledTonalIconButton(onClick = onShareClick) {
                    Icon(Icons.Default.Share, contentDescription = "Reshare")
                }
                FilledTonalIconButton(onClick = onStartOverClick) {
                    Icon(Icons.Default.Refresh, contentDescription = "Start over")
                }
                // „Relay NOW!": NUR beim SENDER (Upload). Der Empfänger lauscht ohnehin automatisch am
                // Relay und eskaliert per A2 selbst → dort KEIN Button (User-Entscheid). Beim Sender
                // müssen beide Enden zuschalten, damit die Byte-Pipe paart.
                if (!entry.isDownload) {
                    // Relay-ON gilt für die ganze Karte (bedient alle Empfänger nacheinander); grün = aktiv.
                    // Badge = wie viele Empfänger gerade hängen (auch wenn Relay aus → „drück mich"-Signal).
                    val relayOn = entry.relayEngaged
                    BadgedBox(badge = {
                        if (entry.relayWaiting > 0) Badge { Text("${entry.relayWaiting}") }
                    }) {
                        FilledTonalIconButton(
                            onClick = onRelayClick,
                            colors = if (relayOn)
                                IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color(0xFF2E7D32), contentColor = Color.White
                                )
                            else IconButtonDefaults.filledTonalIconButtonColors()
                        ) {
                            Icon(Icons.Default.CellTower, contentDescription = if (relayOn) "Relay ON" else "Relay NOW!")
                        }
                    }
                }
                if (entry.isDownload) {
                    // Kontextabhängig: ⏳ solange nichts abspielbar ist, ▶ für (streambares/fertiges)
                    // Einzelvideo, 📂 für fertige Nicht-Videos/Bündel.
                    val (icon, desc) = when (downloadCardAction(entry)) {
                        CardAction.HOURGLASS -> Icons.Default.HourglassEmpty to "Preparing…"
                        CardAction.PLAY -> Icons.Default.PlayArrow to "Play"
                        CardAction.OPEN_FOLDER -> Icons.Default.FolderOpen to "Open folder"
                        CardAction.RETRY_DECRYPT -> Icons.Default.PlayArrow to "Retry decryption"
                    }
                    val enabled = downloadCardAction(entry) != CardAction.HOURGLASS
                    FilledTonalIconButton(onClick = onPlayClick, enabled = enabled) {
                        Icon(icon, contentDescription = desc)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = onThrowClick) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete everything",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                FilledTonalIconButton(onClick = onDismissClick) {
                    Icon(Icons.Default.Close, contentDescription = "Remove card")
                }
            }
        }
    }
}

/**
 * Kompakte einzeilige Statuszeile — so granular wie möglich, damit man sofort sieht, WAS gerade
 * läuft und WO es ggf. hängt:
 *  - „Connecting to trackers…" bleibt stehen → Tracker-Problem.
 *  - „Searching for sender…" → Tracker ok, aber der Sender wird (noch) nicht gefunden/erreicht.
 *  - „Connecting to sender…" → Peer da, Datei-Info wird geladen.
 */
private fun statusLine(entry: TorrentEntry): String {
    val size = formatSize(entry.fileSize)
    return when (entry.state) {
        TorrentState.HASHING ->
            "Preparing…"
        TorrentState.FETCHING_METADATA -> when {
            entry.currentPeers > 0 -> "Connecting to sender…"
            entry.trackerWorking    -> "Searching for sender…"
            else                    -> "Connecting to trackers…"
        }
        TorrentState.DOWNLOADING ->
            "$size · ⬇ ${formatSize(entry.downloadRate.toLong())}/s · ${(entry.progress * 100).toInt()} %"
        TorrentState.SEEDING -> when {
            entry.currentPeers > 0 ->
                "$size · ⬆ ${formatSize(entry.uploadRate.toLong())}/s · ${entry.currentPeers} peers"
            entry.trackerWorking -> "$size · Waiting for receiver…"
            else                 -> "$size · Connecting to trackers…"
        }
        TorrentState.COMPLETED ->
            "$size · ✓ saved"
        TorrentState.ERROR ->
            entry.errorMessage ?: "Error"
        else -> size
    }
}

@Composable
fun StateIndicator(state: TorrentState) {
    val (label, color) = when (state) {
        TorrentState.IDLE              -> "IDLE"  to Color.Gray
        TorrentState.HASHING           -> "HASH"  to Color(0xFFFFA000)
        TorrentState.FETCHING_METADATA -> "META"  to Color(0xFFFFA000)
        TorrentState.DOWNLOADING       -> "DL"    to Color(0xFF2196F3)
        TorrentState.SEEDING           -> "SEED"  to Color(0xFF4CAF50)
        TorrentState.PAUSED            -> "PAUSE" to Color(0xFFFFA000)
        TorrentState.COMPLETED         -> "OK"    to Color(0xFF4CAF50)
        TorrentState.STOPPED           -> "STOP"  to Color.Gray
        TorrentState.ERROR             -> "ERROR" to Color.Red
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.padding(end = 8.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SettingsScreen(viewModel: TorrentViewModel, onShareApp: () -> Unit = {}, onSharePcApp: () -> Unit = {}) {
    val trackers by viewModel.trackers.collectAsState()
    var editedTrackers by remember { mutableStateOf(trackers) }
    val context = LocalContext.current
    val appVersion = remember { appVersionName(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Trackers",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            "One tracker per line. Sender and receiver must use the same trackers. " +
                "(See the help page ? for details.)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = editedTrackers,
            onValueChange = { editedTrackers = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            label = { Text("Tracker list") },
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
        )

        Button(
            onClick = { viewModel.updateTrackers(editedTrackers) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save")
        }

        HorizontalDivider()

        val keepCompressed by viewModel.keepCompressed.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = keepCompressed,
                onCheckedChange = { viewModel.setKeepCompressed(it) }
            )
            Text(
                "Keep compressed videos on this device (Download/Beam)",
                style = MaterialTheme.typography.bodySmall
            )
        }

        HorizontalDivider()

        // µTP-Fallback-Zeit
        Text(
            "Connectivity",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Switch from TCP to µTP for better connectivity after Seconds:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val utpSeconds by viewModel.utpFallbackSeconds.collectAsState()
        var utpText by remember { mutableStateOf(utpSeconds.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = utpText,
                onValueChange = { new ->
                    val digits = new.filter { it.isDigit() }.take(2)
                    utpText = digits
                    digits.toIntOrNull()?.let { viewModel.setUtpFallbackSeconds(it) }
                },
                singleLine = true,
                label = { Text("Seconds") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.width(120.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "(3–60)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()

        Text(
            "Share Beam!",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Send the Beam! app itself to friends (e.g. via WhatsApp) so they can install it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onShareApp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Share Beam! v$appVersion  (Android)")
        }
        Text(
            "Or send the PC version (Windows installer) — fetched fresh from the Beam station, so it's always the latest.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = onSharePcApp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Share PC-Beam!  (Windows .msi)")
        }

        HorizontalDivider()

        // Aufräumen: temporäre Cache-/Link-/Bündel-Reste + verwaiste .pending-Dateien entfernen.
        Text(
            "Maintenance",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Free space by removing leftover temp files (link/bundle caches, orphaned .pending files). " +
                "Active transfers are kept.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val scope = rememberCoroutineScope()
        var cleaning by remember { mutableStateOf(false) }
        Button(
            onClick = {
                cleaning = true
                scope.launch {
                    val (n, bytes) = withContext(Dispatchers.IO) { viewModel.cleanupTempAndLogs(context) }
                    cleaning = false
                    Toast.makeText(
                        context,
                        if (n == 0) "Nothing to clean up" else "Cleaned $n items · freed ${formatSize(bytes)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            enabled = !cleaning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Delete, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (cleaning) "Cleaning…" else "Clean up temp files")
        }
    }
}

/** Versionsname aus dem installierten APK (siehe Auto-Versionierung in build.gradle.kts). */
private fun appVersionName(context: android.content.Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

private fun formatSize(bytes: Long): String {
    if (bytes < 0) return "?"
    return when {
        bytes < 1_024 -> "$bytes B"
        bytes < 1_048_576 -> "%.1f KB".format(bytes / 1_024.0)
        bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576.0)
        else -> "%.2f GB".format(bytes / 1_073_741_824.0)
    }
}

/** Höfliche Willkommens-Karte beim ersten Start. Ton der Marke — jeder Satz bewusst gewählt. */
@Composable
private fun WelcomeCard(onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Welcome to the community of Beam! users.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 36.dp)   // Platz fürs X oben rechts
                )
                Spacer(Modifier.height(10.dp))
                Text(WELCOME_BODY, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private const val WELCOME_BODY =
    "Send any number of original files, any size, straight from the sender's device to the receivers " +
    "— with no nosy service in between. No big player gets your data, because there simply is none in " +
    "the middle. Just Beam! to Beam!\n\n" +
    "For even more safety, add a private passphrase, shared with your receivers over a separate channel.\n\n" +
    "You can pass Beam on — PC and Android — right from the Settings tab. " +
    "(Apple's off-shore island isn't reached yet, sorry.)"