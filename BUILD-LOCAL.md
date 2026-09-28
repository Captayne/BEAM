# BEAM! lokal bauen

Dieser Ordner enthaelt Android (`app`), Windows-PC (`beam-desktop`) und
VPS-Relay (`beam-relay`) mit dem gemeinsamen Modul `beam-core`.

In einer Eingabeaufforderung oder PowerShell in diesem Ordner:

```powershell
.\build.cmd all
.\build.cmd android
.\build.cmd pc
.\build.cmd vps
```

Die Starter bauen offline mit den mitgelieferten Abhaengigkeiten. Sie laden
nichts auf den VPS hoch und installieren keine App. Fuer neue Abhaengigkeiten
Gradle direkt ohne `--offline` aufrufen. `gradlew.bat` aktiviert automatisch
die lokalen Tools, auch bei einem Aufruf aus einem anderen Verzeichnis.

Ergebnisse:

| Ziel | Datei / Ordner |
| --- | --- |
| Android Debug-APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Windows MSI | `beam-desktop/build/compose/binaries/main/msi/Beam-*.msi` |
| VPS ZIP mit Startskripten und allen Runtime-JARs | `beam-relay/build/distributions/beam-relay.zip` |
| VPS entpackte Distribution | `beam-relay/build/install/beam-relay/` |

Die vorhandene automatische Versionierung erhoeht bei diesen Builds die
Versionsnummern. Eine einzelne Relay-JAR enthaelt nicht alle Abhaengigkeiten;
fuer den Server die komplette Distribution verwenden (Java >= 11 auf Linux).

## Tools

- `tools/jdk`: Temurin JDK 21, einschliesslich jpackage/jlink
- `tools/android-sdk`: Android SDK samt installierten Build-Tools, NDK und CMake
- `tools/wix3`: Windows-MSI-Erzeugung
- `tools/gradle-home`: Gradle 9.4.1 und lokale Dependency-Caches
- `tools/maven-repository`: lokales libtorrent4j-BBR-Artefakt
- `tools/env.ps1`: optional mit `. .\tools\env.ps1` in PowerShell aktivieren

Der Gradle-Wrapper bleibt unter `gradle/wrapper`; seine Distribution und Caches
liegen unter `tools`. ffmpeg und die vorhandenen BBR-Binaerdateien bleiben als
App-Ressourcen in den Modulen. Das zusaetzliche `beam_torrent`-Repository ist
mitkopiert; der Neubau seiner nativen C++-Bibliotheken ist kein Bestandteil
der drei Gradle-Builds. Diese verwenden die vorhandenen nativen Bibliotheken.

## Android Studio

Diesen Ordner oeffnen, Gradle-JDK auf `tools/jdk` und Gradle User Home auf
`tools/gradle-home` stellen. `local.properties` verweist auf das lokale SDK.
Die IDE selbst ist nicht fuer die Kommandozeilen-Builds erforderlich.

## Veroeffentlichen

`build-pc-beam.ps1` und `vps/deploy.ps1` sind weiterhin explizite Upload-Skripte.
Sie verwenden jetzt ebenfalls die lokalen Build-Tools. SSH-Schluessel bleiben
im Benutzerprofil. Die Migration fuehrt diese Skripte nicht aus.

Der urspruengliche Ordner auf C: bleibt erhalten. Der separate alte Ordner
`PCbeam` ist nicht die Quelle dieses gemeinsamen Drei-Modul-Projekts.
Die aeltere `BUILD.md` enthaelt historische Installations- und Backup-Pfade;
fuer lokale Builds gilt diese Anleitung.
