# Beam — Build & Deploy (Selbsthilfe-Handbuch)

Damit du **ohne fremde Hilfe** am Quellcode arbeiten, bauen und deployen kannst.
Repo ist **lokal-only** (kein Git-Remote) → der Ordner + das NAS-Backup sind die einzige Kopie.

---

## 1. Projektstruktur (Gradle-Multi-Modul)
| Modul | Was | Sprache/Ziel |
|---|---|---|
| `:app` | **Android-App** (Beam fürs Handy) | Kotlin/Android → APK |
| `:beam-core` | **Geteilter Kern** (TorrentManager, RelayConfig, Trackers …) | Kotlin-JVM, plattformneutral |
| `:beam-desktop` | **PC-App** (Compose Desktop) | Kotlin → MSI (jpackage) |
| `:beam-relay` | **Headless Relay-Station** (Tracker :80 + Byte-Pipe :443) | Kotlin → JAR (laeuft auf dem VPS) |

`beam-core` wird von `:app`, `:beam-desktop` und `:beam-relay` geteilt → eine Logik, drei Ziele.

---

## 2. Voraussetzungen (auf diesem PC schon installiert)
- **Android Studio** (bringt eigenes JBR-JDK mit) — fuer App-Entwicklung/-Build.
- **Adoptium JDK 21** unter `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`
  — fuer PC-MSI (jpackage) und Relay-JAR.
- **Android SDK** — Pfad steht in `local.properties` (gitignored, legt Android Studio selbst an).

`java` ist NICHT im PATH → vor gradle-Aufrufen `JAVA_HOME` setzen (siehe unten).

---

## 3. In Android Studio arbeiten (der einfache Weg)
1. Android Studio → **Open** → diesen Ordner waehlen.
2. Gradle-Sync abwarten. Editieren in `app/src/main/java/...` bzw. `beam-*/src/...`.
3. App bauen/installieren: gruener **Run**-Knopf (Geraet per USB, USB-Debugging an),
   oder Build → Build APK(s).

Das reicht fuer 99% der Quellcode-Aenderungen an der **Android-App**.

---

## 4. Kommandozeile (PowerShell) — alle Artefakte

### Android-APK (Debug)
```powershell
$env:JAVA_HOME = "C:\Installed\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
# Ergebnis: app\build\outputs\apk\debug\app-debug.apk
```
APK auf ein angestecktes Geraet:
```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices
& $adb -s <DEVICE-ID> install -r app\build\outputs\apk\debug\app-debug.apk
```

### PC-App (Windows-MSI)
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\gradlew.bat :beam-desktop:packageMsi
# Ergebnis: beam-desktop\build\compose\binaries\main\msi\Beam-<version>.msi
```

### Relay-JAR (laeuft auf dem VPS)
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\gradlew.bat :beam-relay:jar
# Ergebnis: beam-relay\build\libs\beam-relay.jar
```

---

## 5. Relay-Station (VPS) betreiben/deployen
> **Vollstaendige Server-Doku + Skripte liegen in [`vps/`](vps/README.md):** `README.md`
> (Eckdaten, Routen, Server-von-Null-aufsetzen), `setup-vps.sh` (Provisioning), `deploy.ps1`
> (Build + Push), `beam-relay.service` (systemd). Update deployen: **`.\vps\deploy.ps1`**.

- **Server:** `root@217.160.159.14`, SSH-Key `~\.ssh\beam_relay` (im NAS-Backup unter `ssh\`).
- **Dienst:** systemd `beam-relay` (Logs: `/root/relay.log`). Klassenpfad `/root/beam-relay/lib/*`.
- **Token / Routen (HTTP :80):** Token `bs7Kf3R9xLmQ2v` (steht in `beam-core/.../RelayConfig.kt`).
  - `/<token>/announce` (Tracker), `/<token>/seed`, `/<token>/waiting`, `/<token>/status`,
    `/<token>/Beam.msi` (token-gated MSI fuer "Share PC-Beam!").
- **Byte-Pipe :443** = die Relay-Roehre (rollen-bewusst: paart Sender<->Empfaenger).

Neues Relay deployen:
```powershell
$key = "$env:USERPROFILE\.ssh\beam_relay"
scp -i $key beam-relay\build\libs\beam-relay.jar root@217.160.159.14:/root/beam-relay/lib/beam-relay.jar
ssh -i $key root@217.160.159.14 "systemctl restart beam-relay; systemctl is-active beam-relay; tail -5 /root/relay.log"
```

---

## 6. Self-Distribution / PC-Version verteilen
`build-pc-beam.ps1` (Repo-Wurzel) baut die MSI **und** laedt sie token-gated aufs VPS
(`/root/beam-dist/Beam.msi`), damit "Share PC-Beam!" auf Android immer die neueste verteilt:
```powershell
.\build-pc-beam.ps1
```

---

## 7. Was NICHT in Git ist (aber im Ordner + NAS-Backup liegt)
Bewusst gitignored (zu gross fuer Git), aber zum Bauen noetig — im Projektordner vorhanden,
und das **NAS-Backup spiegelt sie mit**, also nach Restore sofort baufaehig:
- `tools/wix3/` — WiX (fuer MSI-Erzeugung).
- `beam-desktop/desktop-resources/.../ffmpeg/ffmpeg.exe` — Video-Kompression der PC-App.
- `OpenJDK21U-*.msi` — JDK-Installer (Bequemlichkeit).

> Falls je verloren: ffmpeg = static Windows-Build von ffmpeg.org; WiX 3 von wixtoolset.org.
> Niemals `git clean -fdx` ausfuehren — das loescht genau diese Tools!

---

## 8. Notfall-Wiederherstellung
NAS-Backup liegt unter `Y:\Develop\AndroidStudioProjects\beam\` (RAID-NAS).
- `BEAM-Backup.BAT` — sichert C: -> NAS (inkrementell).
- `BEAM-Restore.BAT` — stellt NAS -> C: wieder her (inkl. SSH-Key + Memory).
Nach dem Restore: Android Studio oeffnen (Gradle-Sync), fertig.
