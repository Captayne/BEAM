# Fremdkomponenten und Lizenzhinweise

Stand: 28. September 2026. Diese Übersicht dokumentiert die bisher geprüften
Hauptkomponenten. Sie ist noch kein vollständiges Lizenzverzeichnis der APK,
des MSI oder der VPS-Distribution. Vor einer Binärveröffentlichung sind die
tatsächlich enthaltenen transitiven und nativen Komponenten zusätzlich zu
erfassen und ihre Lizenztexte sowie erforderlichen Hinweise mitzuliefern.

Die Lizenz für BEAMs eigenen Code ändert die Lizenzbedingungen der
Fremdkomponenten nicht. Bereits vorhandene Copyright- und Lizenzhinweise
bleiben erhalten.

## Hauptkomponenten

| Komponente | Verwendung | Lizenz / Quelle |
| --- | --- | --- |
| libtorrent4j 2.1.0-31 | Java/JNI-Schnittstelle zur Torrent-Engine, Standard-Builds | MIT; Lizenzangabe im lokal aufgelösten POM; [Upstream-Lizenz](https://github.com/aldenml/libtorrent4j/blob/master/LICENSE.md) |
| libtorrent | Native Torrent-Engine | BSD-3-Clause für den Hauptteil; weitere enthaltene Quellen besitzen eigene Hinweise; [Projekt](https://github.com/arvidn/libtorrent) |
| Kotlin | Sprache und Laufzeitbibliotheken | Apache-2.0; [Lizenz](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) |
| Compose Multiplatform | Desktop-Oberfläche | Apache-2.0 für den Hauptteil; [Lizenz und enthaltene Hinweise](https://github.com/JetBrains/compose-multiplatform/blob/master/LICENSE.txt) |
| AndroidX / Compose / Media3 | Android-Oberfläche, Medienwiedergabe und Transformation | Apache-2.0 für die Hauptbibliotheken; [Media3-Lizenz](https://github.com/androidx/media/blob/release/LICENSE); konkrete Artefakte zusätzlich prüfen |
| FFmpeg 8.1.1 Essentials von gyan.dev | Extern gestarteter Video-Konverter im Windows-Paket | GPL-3.0-or-later laut der mitgelieferten Binärdatei; Details unten |

Die Upstream-Links können sich weiterentwickeln. Für die fertigen Releases
müssen die Hinweise der tatsächlich verwendeten Versionen archiviert werden.
Bei den nativen libtorrent4j-Artefakten sind insbesondere die eingebauten
Bibliotheken zusätzlich zur Java-Lizenz zu berücksichtigen.

## FFmpeg im Windows-Paket

Geprüfte Datei:

```text
beam-desktop/desktop-resources/windows/ffmpeg/ffmpeg.exe
Version: 8.1.1-essentials_build-www.gyan.dev
Konfiguration unter anderem: --enable-gpl --enable-version3 --enable-static
Lizenzausgabe: GNU GPL version 3 or any later version
```

Die Prüfung erfolgte mit `ffmpeg.exe -version` und `ffmpeg.exe -L`.
BEAM startet diese Datei als separaten Prozess über `ProcessBuilder`.
Die Datei ist von Git ausgeschlossen, wird aber in das Windows-MSI eingebunden.

Für ihre Weitergabe müssen die zu diesem konkreten Build gehörenden
Lizenzhinweise und der vollständige korrespondierende Quellcode samt benötigten
Build-Informationen nach den einschlägigen GPL-Bedingungen verfügbar gemacht
werden. Ein allgemeiner Link zur FFmpeg-Startseite dokumentiert noch nicht,
dass dieser konkrete Quellcode bereitgestellt ist. Der Quellcode-Nachweis und
die Zusammenstellung für den vorhandenen Build sind noch offen.

Quellen: [FFmpeg-Lizenzhinweise](https://ffmpeg.org/legal.html),
[FFmpeg-Lizenzübersicht](https://ffmpeg.org/doxygen/trunk/md_LICENSE.html),
[Build-Anbieter](https://www.gyan.dev/ffmpeg/builds/).

## Laufzeit und Build-Tools

Das Windows-Paket enthält eine mit `jlink` erzeugte Java-Laufzeit. Auch deren
Lizenz- und Copyright-Dateien müssen erhalten bleiben. Die lokale JDK-Kopie
enthält unter `tools/jdk/legal/` die modulspezifischen Texte.

JDK, Android SDK, Gradle-Distribution, WiX und lokale Dependency-Caches
werden nicht als Teil des Git-Quellrepositories hochgeladen. Ihre jeweiligen
Bedingungen sind bei separater Weitergabe weiterhin zu berücksichtigen.

## Native BBR-Variante

Die optionalen BBR-Builds verwenden lokal erzeugte Artefakte mit der Version
`2.1.0-39-beam-bbr1`. Quellstand, Änderungen, Build-Anleitung und Hinweise dieser
Artefakte müssen vor ihrer Verteilung separat erfasst werden. Diese Übersicht
bestätigt noch keine vollständige Lizenzprüfung der BBR-Variante.

## Vor einer Binärveröffentlichung noch zu erledigen

- Tatsächliche Abhängigkeiten von APK, MSI und Relay-ZIP vollständig erfassen.
- Versionierte Lizenztexte, Copyright-Hinweise und erforderliche NOTICE-Dateien
  zusammenstellen und in die jeweiligen Pakete aufnehmen.
- Quellcode und Build-Unterlagen für das enthaltene FFmpeg bereitstellen.
- Enthaltene Java-Laufzeit und native Bibliotheken auf vollständige Hinweise prüfen.
- Separate BBR-Artefakte nur mit nachvollziehbarem Quell- und Build-Stand verteilen.
