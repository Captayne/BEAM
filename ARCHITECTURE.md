# Beam! — Interne Architektur-Doku

> Aktueller, autoritativer Stand der Codebasis (Android + Windows-Desktop).
> Die ältere `Beam_Projektuebergabe.md` ist die *historische* Erst-Übergabe und teils überholt.
> Tiefere Hintergründe zu Einzelthemen stehen im Claude-Memory unter `memory/` (verlinkt unten).

Beam ist eine **P2P-Filesharing-App** auf Basis von **libtorrent4j 2.1.0-31**. Dateien gehen direkt
von Gerät zu Gerät (BitTorrent), kein Server dazwischen. Geteilt wird ein **`.beam`-Link** (der
InfoHash steckt im Dateinamen), z. B. per WhatsApp. Package: `de.systragon.beam`.

---

## 1. Modul-Struktur (Gradle Multi-Modul)

`settings.gradle.kts` bindet drei Module ein:

| Modul | Typ | Inhalt |
|---|---|---|
| **`:beam-core`** | `kotlin-jvm` + `java-library` (Java-11-Bytecode) | **Plattformfreier** Kern: Torrent-Engine-Wrapper, Datenmodell, Krypto, Tracker, `.beam`-Format. Hängt an `api("org.libtorrent4j:libtorrent4j:2.1.0-31")`. KEINE Android-Abhängigkeit. |
| **`:app`** | Android (`com.android.application`) | Android-App: UI (Compose), Foreground-Service, MediaStore, Intents. Hängt an `:beam-core`. |
| **`:beam-desktop`** | `kotlin-jvm` + `jetbrains-compose` | Windows/Linux/macOS-Desktop (Compose Desktop). Hängt an `:beam-core`. In-Process-Session (kein Service/MediaStore). |

**Designprinzip:** Alles Plattformfreie lebt in `beam-core` und wird von Android UND Desktop geteilt.
Android-only ist alles mit `android.*`-Abhängigkeit (Service, MediaStore, Notifications, Intents).
**Die Android-App ist der Schatz** — jede Änderung wird inkrementell mit Build-Check abgesichert,
damit sie nie kippt.

---

## 2. `:beam-core` — der geteilte Kern

Package `de.systragon.beam.core` (Ausnahme: `media.FileCrypto` liegt in `de.systragon.beam.media`).

| Datei | Verantwortung |
|---|---|
| **`TorrentManager.kt`** | Singleton über **einer** libtorrent4j-Session. `startSession()`, `addAndStart()` (Seed), `addAndStartDownload()` (Empfang), `getAll()`, `enableStreaming()`, `restartTransfer()`, `onNetworkChanged()`, `setOutgoingUtpEnabled()`, `refreshTrackerStatus()`. Logt über **`BeamLog`** (nicht `android.util.Log`). |
| **`TorrentEntry.kt`** | Datenmodell pro Transfer + `enum TorrentState` (IDLE, HASHING, FETCHING_METADATA, DOWNLOADING, SEEDING, PAUSED, COMPLETED, STOPPED, ERROR). Felder u. a.: `infoHash`, `fileName`, `cachedFile`, `torrentFile?`, `isDownload`, `progress`, `savedUri`, `isEncrypted`, `streamReady`, `mediaOnly`, `galleryMime`, `trackerWorking`, `createdAt`. |
| **`BeamLog.kt`** | Logger-Shim: `var sink: (Char,String,String)->Unit`. Android setzt den Sink auf `android.util.Log`, Desktop auf `println`. So bleibt `beam-core` android-frei. |
| **`FileCrypto.kt`** | Optionale E2E-Verschlüsselung per Passphrase. **Gechunktes AES-256-GCM (Format `BEAMENC2`)**: 1-MB-Klartext-Chunks, je Chunk eigenes GCM (sonst OOM bei großen Dateien). Liest auch Alt-Format `BEAMENC1`. PBKDF2 (200k). → `memory/gcm-oom-chunked-crypto.md`. |
| **`PeerHint.kt`** | Kodiert/dekodiert die lokale LAN-IP als kurzes Segment (`localSegment()`/`decode()`) — fürs lokale Direktverbinden. → `memory/peer-hint-in-beam-name.md`. |
| **`Trackers.kt`** | `DEFAULT_TRACKERS`, `TRACKERS_VERSION`, `parseTrackers()` (udp/http/https), `loadAndMigrateTrackers()`, `combinedTrackers()`. → `memory/trackers-http-for-mobile.md`. |
| **`BeamLink.kt`** | `.beam`-Format: `fileName()`, `parseName()`, `parseFile()`, `toMagnet()`, `minimalMagnet()`, `writeTo()`. |
| **`TorrentFactory.kt`** | `createTorrent()` → `.torrent` + InfoHash + Magnet (plattformneutral, via `TorrentBuilder`). |
| **`KeyValueStore.kt`** | Abstraktion für Persistenz (Android: SharedPreferences-Adapter; Desktop: aktuell n/a). |

---

## 3. `.beam`- und `.beamenc`-Format

**`.beam` (der Link):** Dateiname `<name>.<40-hex-InfoHash>[.pe<12-hex-PeerHint>].beam`.
Der **InfoHash steckt im Dateinamen** → der Empfänger liest nur den Namen, kein Stream nötig.
Inhalt = portabler Magnetlink (ohne Peer-Hint, nur als Fallback). Der optionale `pe…`-Teil
kodiert die LAN-IP des Senders fürs lokale Direktverbinden (NICHT im öffentlichen Magnet).
→ Warum Datei statt Link: WhatsApp macht nur `http(s)://` tippbar, `magnet:` nicht. `memory/link-sharing-approach.md`.

**`.beamenc` (verschlüsselte Nutzdaten):** `MAGIC2(8) | salt(16) | noncePrefix(8) | [ctLen(4)|ciphertext]…`.
Bei Einzeldatei opaker Container `Beam_<ts>.beamenc` (echter Name verborgen, steckt im Klartext-Header);
bei Bündel je Datei `<name>.beamenc`. Das `.beamenc` ist die **Nutzlast** (wird geseedet), NICHT der Link.

---

## 4. `:app` — Android

| Datei | Verantwortung |
|---|---|
| **`BeamApp.kt`** (Application) | Setzt früh `BeamLog.sink` → Logcat. Startet beim Prozessstart `MediaStoreSaver.cleanupOrphanPending()` (verwaiste `.pending`-Reste). |
| **`ui/MainActivity.kt`** | Intent-Einsprung + Sende-/Empfangs-Logik. Behandelt `ACTION_SEND`/`SEND_MULTIPLE` (Dateien vormerken → BEAM!-Knopf), `ACTION_VIEW` (`magnet:` und `.beam`). Bündelt mehrere Dateien, komprimiert Videos optional, verschlüsselt optional, baut Torrent, startet `SeedingService`, öffnet Share-Sheet. **SAF-Launcher**: Import (`OpenDocument`) und Ordner-Freigabe (`OpenDocumentTree`). |
| **`ui/MainScreen.kt`** | Compose-UI: blaue „Beam!"-TopAppBar, FABs **Import** + **Paste link**, Tabs **Transfers**/**Settings**. `TorrentCard` mit granularer Statuszeile. `downloadCardAction()` bestimmt das EINE Empfangs-Icon (⏳/▶/📂/Retry). `SettingsScreen` mit Trackers, Connectivity (µTP-Fallback-Sekunden), Share Beam!, **Maintenance → „Clean up temp files"**. |
| **`transfer/TorrentViewModel.kt`** | `StateFlow`-UI-State, Polling. `openFile()`, `openFolder()` (Medien → Geräte-Galerie; Dokumente → Verzeichnis via SAF/Downloads), SAF-Ordner-Helfer (`hasBeamFolderAccess`/`saveBeamFolderAccess`/`openBeamFolderTree`), `cleanupTempAndLogs()`, Tracker-Delegates, Streaming, `restartTransfer`. |
| **`service/SeedingService.kt`** | Foreground-Service (`dataSync`). `ACTION_ADD_TORRENT` (seed), `ACTION_ADD_MAGNET` (download). Erkennt Fertigstellung im Poll-Thread; beim Empfang: **Padding-Dateien überspringen** (`ti.files().padFileAt(i)`), `.beamenc` entschlüsseln, jede Datei via `MediaStoreSaver.publish()` veröffentlichen, `mediaOnly`/`galleryMime` setzen. |
| **`media/MediaStoreSaver.kt`** | `publish()` (Bilder→Pictures, Videos→Movies, Rest→Download/Beam; API 29+ via MediaStore, <29 via FileProvider), `guessMime()` (mit Fallback für heic/heif/mov…), `cleanupOrphanPending()`. **Publish ist gehärtet**: bei jedem Fehler nach `insert` wird `resolver.delete(uri)` aufgerufen → keine `.pending`-Leiche. |
| **`media/VideoCompressor.kt`** | Optionale Video-Verkleinerung vor dem Senden (MediaCodec/Transformer). |
| **`ui/VideoPlayerScreen.kt`** | Vollbild-Streaming-Player (Video während des Downloads abspielen). |
| **`transfer/SharedPrefsStore.kt`** | `KeyValueStore`-Adapter über SharedPreferences. |
| **`AndroidManifest.xml`** | Intent-Filter für Share, `magnet:`, `.beam` (per Pfad-Pattern) und **`application/x-beam` + `application/octet-stream`** (content-URIs ohne Endung). → siehe Gotcha #3. |

---

## 5. `:beam-desktop` — Windows/Desktop (Compose Desktop)

- **`src/main/kotlin/.../desktop/Main.kt`**: Compose-Desktop-UI im Android-Look. `main(args)` nimmt
  Datei-Pfade entgegen (`.beam` → empfangen, sonst → senden — für „Senden an → BEAM!"). `doSendFiles`
  (einzeln/Bündel, optional verschlüsselt), `doReceive`, `resolveDownload` (Bündel auflösen, `.beamenc`
  entschlüsseln). `stageBundle` (Hardlink/Kopie bzw. `.beamenc`). **`ensureNativeLib()`** extrahiert die
  libtorrent-DLL und setzt `libtorrent4j.jni.path` VOR dem ersten Engine-Zugriff (Gotcha #1).
  Weiterteilen: `.beam` in Zwischenablage (`copyFileToClipboard`) bzw. Explorer öffnen.
- **`build.gradle.kts`**: jpackage/Compose-Desktop-Packaging (MSI/Exe/Deb/Rpm/Dmg). **Auto-Versionierung**
  (`version.properties` → `packageVersion = "1.0.<n>"`), Windows-`fileAssociation` `.beam`=`application/x-beam`,
  `iconFile` = `icons/beam.ico`, `upgradeUuid` stabil (MajorUpgrade).
- Endnutzer brauchen **kein Java** — jpackage bündelt die Runtime.

---

## 6. Datenflüsse

**Senden (Android):**
`Datei(en)` → [Video optional komprimieren] → [optional verschlüsseln → `.beamenc`] →
`TorrentBuilder` → `.torrent` + Magnet → `SeedingService` seedet (aus `cacheDir`) →
`.beam` schreiben (mit Peer-Hint) → Share-Sheet.

**Empfangen (Android):**
`.beam`/`magnet:` → `MainActivity` parst (Hash aus Dateiname) → `SeedingService` lädt nach
`filesDir/incoming` → bei Abschluss: **Pad-Dateien überspringen** → `.beamenc` entschlüsseln →
`MediaStoreSaver.publish()` (Galerie/Download) → Karte zeigt ▶/📂.

**Desktop:** spiegelt beides mit In-Process-Session; Dateien landen in `~/Downloads/Beam`.

---

## 7. Netzwerk-Strategie

- **TCP-first**: eingehendes µTP immer an, **ausgehendes µTP** erst als Fallback nach konfigurierbarer
  Zeit ohne Peer (Settings „Connectivity", 3–60 s, Default 15). libtorrent würde sonst das langsame
  µTP bevorzugen. → `memory/tcp-only-throughput.md`.
- **Tracker**: UDP fürs WLAN-Rückgrat + **HTTP/HTTPS** für Mobilfunk (UDP wird dort oft geblockt);
  Auto-Fetch Top-Tracker von ngosang beim Start; Migration über `TRACKERS_VERSION`. → `memory/trackers-http-for-mobile.md`.
- **Netzwechsel** mitten im Transfer wird über `registerDefaultNetworkCallback` aufgefangen (re-announce).
- **Lokale Discovery (LSD)** braucht `MulticastLock`. → `memory/fetching-metadata-hang.md`.

---

## 8. Build & Deploy

**Android (Debug):** `java` ist NICHT im PATH — JBR nutzen:
```powershell
$env:JAVA_HOME = "C:\Installed\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug --console=plain
```
Auto-Version in `app/version.properties` (`versionCode`, +1 je assemble/install; `versionName` = code/100).
→ `memory/build-setup.md`.

**Desktop starten:** `.\gradlew.bat :beam-desktop:run`

**Windows-MSI bauen** (braucht jpackage → Temurin-JDK, und WiX 3 Binaries):
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:PATH = "C:\Users\KEIBEL-OFFICE\androidstudioprojects\beam\tools\wix3;$env:PATH"
.\gradlew.bat :beam-desktop:packageMsi
```
Ergebnis: `beam-desktop/build/compose/binaries/main/msi/Beam-1.0.<n>.msi`.
**Vorher** laufende `Beam.exe` beenden (`Get-Process Beam | Stop-Process -Force`), sonst ist das
Build-Verzeichnis gesperrt. Auto-Version in `beam-desktop/version.properties`.

**Auf Handys aufspielen** (dynamisch über `adb devices`, nicht hardcoden): → `memory/deploy-to-phones.md`.

---

## 9. Gotchas / Lessons Learned (teuer erkämpft)

1. **Desktop-Native-Lib**: libtorrent4j lädt auf dem Desktop via `System.loadLibrary` → scheitert,
   weil die DLL nur als Classpath-Ressource vorliegt. **Fix:** DLL in Temp extrahieren +
   `System.setProperty("libtorrent4j.jni.path", …)` VOR dem ersten Engine-Zugriff (`ensureNativeLib`).
2. **GCM-OOM**: Androids AES/GCM puffert den ganzen Stream → OOM bei großen Dateien. **Fix:** gechunktes
   `BEAMENC2` (1-MB-Chunks). → `memory/gcm-oom-chunked-crypto.md`.
3. **PC→Android `.beam` öffnet nicht**: WhatsApp-Desktop taggt die Datei als `application/x-beam`
   (unsere Windows-MSI hat das so registriert) → das Manifest muss diesen MIME beim content-VIEW-Filter
   kennen. Per `adb logcat` bewiesen (`typ=application/x-beam`). → `memory/beam-file-association.md`.
4. **BitTorrent-Padding-Dateien**: Multi-File-Torrents enthalten 0-Byte-Pad-Dateien (Block-Ausrichtung,
   numerische Namen). Beim Empfang **überspringen** (`files.padFileAt(i)`) — sonst „Phantom"-Dateien,
   falsche Datei-Zählung, `.pending`-Leichen, kaputtes `mediaOnly`.
5. **MediaStore `.pending`-Leichen**: Bricht ein Publish nach `insert` ab, bleibt `IS_PENDING=1` +
   eine `.pending-…`-Datei. **Fix doppelt**: Publish löscht bei jedem Fehler die URI; zusätzlich
   `cleanupOrphanPending()` beim Prozessstart (Filter: eigener `OWNER_PACKAGE_NAME`, `MATCH_INCLUDE`).
6. **Ordner öffnen braucht SAF**: Android verbietet das Öffnen eines fremden Unterordners ohne
   Berechtigung (SecurityException). **Fix:** einmalig `ACTION_OPEN_DOCUMENT_TREE` (vornavigiert auf
   Download/Beam) + `takePersistableUriPermission`; danach `ACTION_VIEW` auf die Tree-Document-URI.
7. **Galerie**: `CATEGORY_APP_GALLERY` öffnet die *Standard*-Galerie (oft Google Photos), die lokale
   Dateien ggf. nicht sofort zeigt. **Fix:** gezielt die **Geräte-Galerie** per
   `getLaunchIntentForPackage` (Samsung `com.sec.android.gallery3d`, miui, huawei, coloros) öffnen.
8. **MSI-Upgrade**: Bleibt `packageVersion` gleich, verlangt Windows manuelle Deinstallation.
   **Fix:** Version bei jedem Packaging hochzählen (`1.0.<n>`); stabile `upgradeUuid` → MajorUpgrade
   ersetzt die alte Version automatisch.
9. **Android-Datei-Verknüpfung ist nicht zur Laufzeit änderbar** (Intent-Filter sind install-fest).
   Deshalb gibt es den **Import-Button** (SAF `OpenDocument`) als OEM-unabhängigen, zuverlässigen Weg.
10. **ffmpeg seamless mitbündeln** (PC-Video-Komprimierung): `ffmpeg.exe` liegt in
    `beam-desktop/desktop-resources/**windows**/ffmpeg/`, eingebunden via
    `nativeDistributions { appResourcesRootDir.set(project.file("desktop-resources")) }`. **FALLE:**
    `appResourcesRootDir` braucht OS-Unterordner (`common`/`windows`/…) — Dateien direkt im Wurzelverz.
    werden IGNORIERT. Laufzeit-Pfad: `System.getProperty("compose.application.resources.dir")` →
    `<app>/resources/ffmpeg/ffmpeg.exe`. Dev-Fallback: `desktop-resources/windows/…`.
11. **Backup-Empfang: Top-Ordner per `renameFile` strippen, NICHT nach dem Download verschieben.**
    Ein Multi-File-Torrent hat immer einen Top-Ordner (der Bündel-Ordnername `bundle_<ts>`). Nach dem
    Download zu verschieben scheitert (libtorrent hält Dateien beim Seeden offen → Windows-Sperre; und
    `\` vs `/`-Trenner). **Fix:** sobald Metadaten da sind, je Datei `handle.renameFile(i, <rel ohne
    Top>)` → die Dateien landen DIREKT im Zielordner. `torrentFile()` zeigt danach noch alte Pfade
    (Snapshot) → beim Öffnen den gewählten Ordner nehmen, nicht `revealTarget.parentFile`; leeren
    `bundle_…`-Rest im Hintergrund entfernen (nur wenn keine echte Datei drin).

---

## 10. Chronology-Backup (Event/Trip → PC) — `memory/feature-timerange-backup.md`

Die „geniale, einfache" Geste: ein ganzes Event in einem Schritt vom Handy ins PC-Archiv.
- **Auswahl per Anker:** Nutzer markiert in der Galerie das ERSTE + LETZTE Foto und teilt sie an Beam.
  Häkchen **„Chrono"** (in der „Ready to send"-Vorschaukarte). Beam nimmt min/max-Datum (`DATE_TAKEN`,
  Fallback `DATE_MODIFIED`) und holt per MediaStore-Query ALLE Medien dazwischen (inkl. WhatsApp/Social).
- **Vorschaukarte** (`PendingSummaryCard`) zeigt vor dem Senden Typ + Quelle (📷/🎬, Camera/WhatsApp/
  Screenshots/Social) + „Cancel". Gilt für Standard- UND Chronologie-Modus.
- **Senden:** Originale 1:1 (Verschlüsseln/Qualität ausgegraut) als Bündel über die bestehende
  Pipeline, `.beam` mit **`.bk`**-Token (`shareBeamLink`, nur bei `chronologyMode`).
- **PC-Empfang:** `.bk` → fragt nach Zielordner (z. B. NAS), lädt direkt dorthin (Top-Ordner via
  `renameFile` gestrippt, siehe Gotcha 11). Braucht Android-Leserechte READ_MEDIA_IMAGES/VIDEO
  (≥API33) bzw. READ_EXTERNAL_STORAGE (≤32), angefragt beim Aktivieren von „Chrono".
- **Feldbestätigt 2026-06-05:** São-Paulo-Reise (3 GB) Handy→PC. ✅
- **Offen (v2/3b):** No-Copy-In-place-Seeden (`file_storage` Wurzel `/storage/emulated/0` +
  `MANAGE_EXTERNAL_STORAGE`) → kein 2×-Cache-Speicher bei Riesen-Backups. AES-CTR-on-the-fly-
  Verschlüsselung als spätere Option (sonst widerspricht Verschlüsseln dem No-Copy).

## 11. Offene Punkte / Nächste Schritte

Siehe `memory/project-status.md` + `memory/feature-timerange-backup.md` (laufende Tracker). Stand:
- **Chronology-Backup: fertig + feldbestätigt** (siehe oben); v2 = No-Copy-In-place-Seeden.
- „Senden an → BEAM!" für die **installierte** PC-App (SendTo zeigt auf `C:\Program Files\Beam\Beam.exe`).
- Eigener **HTTPS-Tracker auf IONOS** (verlässlicher Mobilfunk + Privacy via `private`-Flag).
- Desktop-Persistenz (KeyValueStore) + Auto-Tracker; PC-Backup-Zielordner persistent + NAS-Default.
- Hilfe-/`help.md` weiter pflegen (Chronology-Abschnitt ist drin).

**Feldtest-Status:** 1. Runde (Tester-Freunde) lief; 2. Runde mit Chronology-Feature läuft an
(erst Family, dann Freunde). PC: `Beam-1.0.13.msi` (ffmpeg + Chronology + sauberer Zielordner).

---

*Nordstern (User-Fokus): ein **nahtloses PC-BEAM!**, „um das man fast nicht mehr herumkommt" —
Original-Qualität ohne Cloud/Recompression, E2E-verschlüsselt, ein wiederverwendbarer Link für
beliebig viele Empfänger, Cross-Platform Handy↔PC, bedienbar für Laien.*
