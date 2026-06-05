# Beam! — Projektübergabe an Claude Code

## Projektidentität

| | Aktuell | Ziel |
|---|---|---|
| **App-Name** | P2P-Share | Beam! |
| **Package** | `com.example.p2p_share` | `de.systragon.beam` |
| **URL-Schema** | `magnet://` | `beam://` |
| **Plattform** | Android, Kotlin, Jetpack Compose | → |
| **Projektpfad** | `C:\Users\KEIBEL-OFFICE\AndroidStudioProjects\P2PShare` | → |

---

## Philosophie

> Klein. Direkt. Sauber. Keine Umwege.

- Keine Cloud
- Keine Accounts
- Keine sichtbaren Fremdinteressen
- Systragon komplett unsichtbar (nur stiller Tracker im Hintergrund)
- Zielgruppe: alle — besonders Datenschutz-bewusste User
- Positionierung: "AirDrop für alle, ohne Apple, ohne Limit"

**Kernaussage:**
> "Die Datei geht direkt von Handy zu Handy. Kein Server sieht sie jemals."

---

## Bereits implementiert

### Abhängigkeiten (`build.gradle.kts`)

```kotlin
implementation("org.libtorrent4j:libtorrent4j:2.1.0-31")
implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-31")
implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-31")
implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-31")
implementation("androidx.compose.material:material-icons-extended")
compileSdk = 35  // nicht 36, wegen Kompatibilität
minSdk = 24
```

### Dateistruktur

```
com.example.p2p_share/
├── MainActivity.kt
├── MainScreen.kt
├── TorrentEntry.kt      ← State + Datenmodell
├── TorrentManager.kt    ← Singleton, libtorrent4j Session
├── TorrentViewModel.kt  ← Compose ViewModel
└── SeedingService.kt    ← Foreground Service
```

### TorrentEntry.kt

```kotlin
enum class TorrentState {
    IDLE, HASHING, SEEDING, PAUSED, STOPPED, ERROR
}

data class TorrentEntry(
    val infoHash: String,
    val fileName: String,
    val fileSize: Long,
    val cachedFile: File,
    val torrentFile: File,
    var state: TorrentState = TorrentState.IDLE,
    var handle: TorrentHandle? = null,
    var errorMessage: String? = null,
    var uploadedBytes: Long = 0L,
    var currentPeers: Int = 0,
    var uploadRate: Int = 0
)
```

### TorrentManager.kt (Singleton)

- Eine libtorrent4j Session für alle Torrents
- Session-Settings: DHT, LSD, UPnP, NAT-PMP aktiv
- Tracker werden per `AnnounceEntry` zum Handle hinzugefügt
- Torrent wird mit `seed_mode` Flag gestartet
- Methoden: `startSession()`, `addAndStart()`, `pause()`, `resume()`, `stop()`, `stopAll()`
- `stop()` entfernt Eintrag komplett aus der Map
- `stopAll()` wird bei Service-Ende aufgerufen — kein Ressourcen-Müll

### SeedingService.kt (Foreground Service)

- Actions: `ACTION_ADD_TORRENT`, `ACTION_PAUSE`, `ACTION_RESUME`, `ACTION_STOP`
- Notification mit Live-Status (Seeds, Peers, Upload-Rate)
- Update-Thread alle 5 Sekunden
- `onDestroy()` → `TorrentManager.stopAll()`

### TorrentViewModel.kt

- Polling alle 2 Sekunden, aktualisiert Peers/Upload direkt vom Handle
- `StateFlow<List<TorrentEntry>>` für Compose-UI
- Tracker-Liste in SharedPreferences gespeichert, editierbar
- `copyMagnetLink()`, `stopTorrent()` (inkl. Cache-Dateien löschen)

### MainScreen.kt (Jetpack Compose)

- Tab 1: Transfer-Liste mit Live-Status
- Tab 2: Tracker-Einstellungen (editierbar, persistent)
- TorrentCard: Dateiname, Größe, Peers, Upload, State-Pill
- Tippen auf Karte → Magnetlink in Clipboard kopieren
- Mülltonne → sofort stoppen + Cache löschen

### MainActivity.kt

- Share-Intent (`ACTION_SEND`) → Datei verarbeiten
- Magnetlink-Handler (`ACTION_VIEW`, `magnet://`) → Phase 6 (noch offen)
- Background-Thread für Dateiverarbeitung
- Torrent erzeugen → Service starten → Share Sheet öffnen

### AndroidManifest.xml

- Permissions: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`
- Share-Intent für: `image/*`, `video/*`, `application/octet-stream`
- Magnetlink-Handler: `magnet://`
- FileProvider registriert

### Standard-Tracker-Liste

```
udp://tracker.opentrackr.org:1337/announce
udp://open.stealth.si:80/announce
udp://tracker.torrent.eu.org:451/announce
udp://exodus.desync.com:6969/announce
udp://tracker.systragon.de:1337/announce  ← stiller eigener Tracker (noch aufzusetzen)
```

---

## Funktioniert bereits ✅

- Datei aus Galerie teilen → Torrent erzeugen → Magnetlink generieren
- Seeding über BitTorrent (DHT + öffentliche Tracker)
- Anderes Android-Gerät (LibreTorrent) lädt erfolgreich herunter
- Live-Status in der App (Peers, Upload-Rate)
- Tracker editierbar in Einstellungen
- Magnetlink per WhatsApp/Threema/Signal teilen
- Foreground Service mit Notification

---

## Bekannte Probleme / TODO

| Problem | Priorität | Lösung |
|---|---|---|
| Cache-Problem: Datei wird vollständig kopiert (doppelter Speicher) | Hoch | Virtuelles Storage Backend (v2.0) |
| qBittorrent Kompatibilität suboptimal | Mittel | LibreTorrent funktioniert einwandfrei |
| Phase 6 (Empfang) nicht implementiert | Hoch | Nächste Session |
| Debug-Logging aktiv | Niedrig | Vor Release entfernen |
| 16KB Page Size Warning (libtorrent4j.so) | Niedrig | libtorrent4j-seitig, abwarten |

---

## Nächste Session — Sofort

### 1. Refactoring & Umbenennung

```
com.example.p2p_share → de.systragon.beam
App-Name: Beam!
```

Neue Paketstruktur:
```
de.systragon.beam/
├── core/        ← TorrentManager, Session, State
├── transfer/    ← Senden, Empfangen, Streaming
├── media/       ← Komprimierung, Player, Galerie
├── ui/          ← Compose Screens
└── service/     ← Foreground Service
```

### 2. Eigenes Link-Schema `beam://`

**Aktuell:** `magnet:?xt=urn:btih:HASH&dn=name&tr=...`

**Neu:** `beam://Base64url(BinaryPayload)`

Payload-Format (binär, dann Base64url-kodiert):
```
Byte 0:      Version (1 Byte)
Byte 1:      Flags (1 Byte)
             Bit0 = oneTimeView
             Bit1 = hasExpiry
             Bit2-7 = reserved
Byte 2-21:   InfoHash (20 Bytes, SHA1)
Byte 22-X:   Dateiname (UTF-8, null-terminated)
Byte X+1-4:  Expiry-Timestamp optional (4 Bytes, Unix)
```

Beispiel-Link:
```
beam://AQF1NFj6qeFUzMQu5IZX+AM2C3TOxnZpZGVvLm1wNAA=
```

**Vorteile:**
- Kein Torrent-Geruch im Link
- Kompakt, unlesbar für Außenstehende
- Tracker werden von der App automatisch ergänzt (nicht im Link)
- Erweiterbar durch Flags-Byte

### 3. Phase 6 — Empfang implementieren

Empfangs-Verhalten nach Dateityp:

**Videos:**
```
beam://-Link öffnen
→ Download starten (sequential mode)
→ Lokaler HTTP-Server auf 127.0.0.1:PORT
→ Sofort streamen ab ersten Blocks
→ [Keep & Leave] oder [Throw]
  Keep & Leave = in Galerie speichern, Download läuft weiter
  Throw = Abbruch + sofort löschen
```

**Bilder:**
```
→ Download abwarten (klein, schnell)
→ Eingebetteter Viewer
→ [Keep & Leave] oder [Throw]
```

**Andere Dateien (PDF, DOC, PPT, ZIP, ...):**
```
→ Silent download nach /Downloads/Beam/
→ Notification: "datei.pdf wurde gespeichert"
→ Tippen auf Notification: [Öffnen] [Schließen]
→ Öffnen = Android Standard-Intent (öffnet passende App)
```

---

## Mittelfristig (v1.5)

### Senden — Verbesserungen

**Fortschrittsanzeige beim Cache-Kopieren:**
```
⏳ Kopiere... [████████░░] 78%  (2.6 GB / 3.4 GB)
```

**Video-Komprimierungsstufen (vor dem Senden wählen):**
```
[100%  Original      ~3.4 GB]
[Q50   Reduziert     ~800 MB]  ← empfohlen
[Q25   Klein         ~200 MB]
[Q10   Sehr klein    ~80 MB ]
[Q5    Vorschau      ~5 MB  ]  ← 320x200
```
Implementierung: Android `MediaCodec` API (ab API 29)

**OneTimeView:**
- Checkbox beim Senden: `☑ Einmal ansehen`
- Flag im beam://-Link (Bit0 in Flags-Byte)
- Beim Empfänger: kein "Keep & Leave", nach Abspielen automatisch löschen
- v1.0: Client-seitiges Vertrauen (technisch umgehbar, für normalen Use-Case ausreichend)

### Multi-File Support

- `ACTION_SEND_MULTIPLE` registrieren
- Mehrere Dateien → temporärer Beam-Ordner → ein Link
- Empfänger wählt Zielordner via `ACTION_OPEN_DOCUMENT_TREE`

```
beam://HASH/Urlaub_Mallorca_2026/
  ├── IMG_001.jpg
  ├── VID_001.mp4
  └── VID_002.mp4
```

---

## Langfristig (v2.0)

### Virtuelles Storage Backend
- Kein Cache-Kopieren beim Senden
- libtorrent4j liest direkt über Android `ContentResolver`
- Löst das doppelte Speicherproblem grundsätzlich

### Briefmarke zuerst — Original auf Anfrage
- Sender schickt automatisch Q5-Vorschau
- Empfänger kann Original anfordern
- Kommunikation über BitTorrent Extension Protocol (BEP 10)

### Ephemerer P2P-Chat
- Über bestehende BitTorrent-Verbindung (BEP 10)
- Nur solange beide online — kein Speicher, spurlos
- Funktionaler Zweck: Qualitätsstufe anfordern, Abbrechen, Bestätigen

---

## Technische Notizen

| | |
|---|---|
| libtorrent4j Version | 2.1.0-31 |
| compileSdk | 35 (nicht 36, Kompatibilität) |
| minSdk | 24 |
| Java | VERSION_11 |
| 16KB Warning | bekannt, libtorrent4j-seitig, kein Handlungsbedarf |
| qBittorrent | suboptimal, LibreTorrent funktioniert einwandfrei |

### Systragon-Strategie
- `systragon.de` gehört dem Entwickler
- Komplett unsichtbar in der App — kein Branding
- `tracker.systragon.de` — eigener opentracker auf VPS, stiller Fallback-Tracker
- Datenschutzerklärung für Play Store auf `systragon.de` hosten

### Play Store (später)
- Google Developer Konto: 25$ einmalig
- Package-ID `de.systragon.beam` — kann nach Veröffentlichung nicht geändert werden
- Datenschutzerklärung erforderlich (Netzwerkzugriff)
- Kern-Aussage: "Die App überträgt keine Daten an uns. Alle Übertragungen sind direkt zwischen den Geräten."
