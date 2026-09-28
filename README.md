# BEAM!

**Dateien zwischen Android und PC und untereinander teilen – direkt, in Originalqualität und ohne Benutzerkonto.**

BEAM! überträgt Dateien über Peer-to-Peer-Verbindungen auf Basis von BitTorrent.
Eine kleine `.beam`-Datei verbindet Sender und Empfänger; sie kann zum Beispiel
per Messenger oder E-Mail weitergegeben werden. Ein selbst betreibbares Relay
kann helfen, wenn keine direkte Verbindung zustande kommt.
Bei der Vermittlung des Senders zum Empfänger kommt die Bibliothek LibTorrent zum Einsatz, um vom dezentralen Vermittlungsnetz zu profitieren. 
Die zugrundeliegende BitTorrent Library wurde zum Zwecke der Übertragungsbandbreite modifiziert. Das LEDBAT Protokoll wurde durch eine High-Speed Variante ersetzt. Nicht die Latenzen entscheiden über die Bandbreite, sondern die gemessene Bandbreite. Sobald konkurrierender Verkehr auf der Leitung existiert, drosselt BEAM! die Bandbreite im Gegensatz zu LEDBAT nicht zwingend, sondern fordert im Wettbewerb maximalen Durchsatz.  Dieses modifiziert libtorrent Bibilothek daher bitte nicht in anderen Bittorrent clients verwenden. Wir gehen davon aus, dass dies bei BEAM kein Problem ist, da es nur zwischen zwei Parteien und auch nur ganz kurz zum Einsatz kommt, und nicht auf dedizierten Servern riesige Mengen an Files hostet und in alle Welt verteilt. Der BEAM Algoritmus könnte hier zu Netzwerk Problemen führen.  Im lokalen Netzwerk von Smartphone zu PC über das lokale Wifi wurden vom Anwender über 120MB/s gemessen. 
Möchte man über Netzwerk-Grenzen hinweg Daten austauschen kommt die Stärke von Bittorrent zum Zuge, das mit µTP oft gute Chancen hat, durch Carrier-Gate Network Adress Translations hindurch zu tunneln. Firewalls und IT Sicherheit wie z.B. ZScaler wird aber nicht zu knacken versucht. Es könnte für den Anwender zu Problemen führen, weil solche Versuche (mit Bittorrent-Protokoll aus Firmennetzen Daten zu senden oder zu empfangen) protololliert werden. 


Dieses gemeinsame Repository enthält drei Anwendungen und ihren geteilten Kern:

| Anwendung | Aufgabe | Quellordner / Gradle-Modul |
| --- | --- | --- |
| **BEAM4Android** | Dateien vom Android-Gerät senden und empfangen | `app/` · `:app` |
| **BEAM4PC** | Desktop-Anwendung für Windows | `beam-desktop/` · `:beam-desktop` |
| **BEAM_RELAY for VPS** | Headless-Relay und Tracker für einen Linux-VPS | `beam-relay/` · `:beam-relay` |
| **BEAM Core** | Gemeinsame Transferlogik, Dateiformate und Verschlüsselung | `beam-core/` · `:beam-core` |

Die Produktnamen und die derzeitigen technischen Modulnamen unterscheiden sich.
Die oben aufgeführten Ordner und Gradle-Befehle entsprechen dem aktuellen Stand.

## So funktioniert es

1. Dateien auswählen und mit BEAM! teilen.
2. Die erzeugte `.beam`-Datei an den Empfänger schicken.
3. Der Empfänger öffnet sie mit BEAM! und lädt die Dateien herunter.

Der Sender muss während der Übertragung erreichbar bleiben. Das Relay stellt
keinen dauerhaften Cloud-Speicher bereit. Eine erfolgreiche Verbindung hängt
von beiden Netzwerken ab; BEAM! umgeht keine Unternehmens-Firewallregeln.

## Funktionen

- Android ↔ Windows sowie Transfers zwischen Geräten derselben Plattform.
- Originaldateien ohne erzwungene Neukompression übertragen.
- Mehrere Dateien zusammen und an mehrere Empfänger teilen.
- Optionale passphrasenbasierte Dateiverschlüsselung.
- Optionale Video-Kompression vor dem Versand.
- Chrono-Auswahl für Medien aus einem zusammenhängenden Zeitraum auf Android.
- Relay-Unterstützung für Netze, in denen der direkte Weg nicht funktioniert.

Verschlüsselung muss ausdrücklich aktiviert werden. Ein `.beam`-Link ist kein
Anonymitätsversprechen: Tracker, DHT und Kommunikationspartner können
Verbindungsmetadaten sehen. Sensible Dateien nur verschlüsselt teilen und die
Passphrase getrennt übermitteln.

## Voraussetzungen

- **Android:** Android 7.0 / API 24 oder neuer.
- **PC:** Der hier geprüfte Desktop-Build ist Windows x64. Linux- und
  macOS-Paketformate sind im Build vorgesehen, aber hier nicht validiert.
- **VPS:** Linux mit einer zur nativen libtorrent-Bibliothek passenden
  Architektur; für die vorhandene Konfiguration Linux x64 und Java 21 verwenden.
- **Entwicklung:** JDK 21 mit `jpackage`, Android SDK (Compile SDK 35),
  Gradle 9.4.1 über den Wrapper und WiX 3 für das Windows-MSI.

## Cloud-Builds auf GitHub

Unter **Actions > BEAM - Cloud Build > Run workflow** lassen sich Android,
Windows-PC und VPS-Relay einzeln oder gemeinsam bauen. Dein lokaler Rechner
wird dabei nicht verwendet. Die fertigen Pakete stehen sieben Tage als private
Downloads am jeweiligen Lauf bereit.

Start und Hinweise zur Android-Testsignierung:
[Cloud-Build-Anleitung](docs/CLOUD-BUILDS.md).

## Bauen unter Windows

Die lokale Entwicklungsumgebung verwendet Toolchains und Caches unter `tools/`.
Mit eingerichteter Umgebung im Repository-Ordner ausführen:

```powershell
.\build.cmd all
```

Oder die Anwendungen einzeln bauen:

```powershell
.\build.cmd android
.\build.cmd pc
.\build.cmd vps
```

| Build | Ergebnis |
| --- | --- |
| Android | `app/build/outputs/apk/debug/app-debug.apk` |
| Windows | `beam-desktop/build/compose/binaries/main/msi/Beam-<Version>.msi` |
| VPS | `beam-relay/build/distributions/beam-relay.zip` |

Der VPS-Build enthält die Startskripte und alle Runtime-JARs. Die einzelne
Relay-JAR allein genügt nicht. `build.cmd` baut lokal und lädt nichts auf einen
Server hoch. Die vorhandenen separaten Deployment-Skripte führen dagegen
Uploads aus und müssen vor Verwendung für die eigene Infrastruktur geprüft werden.

**Geprüfter Stand:** Alle drei Builds wurden am 28. September 2026 sowohl lokal
offline unter Windows als auch aus frischen GitHub-Checkouts in der Cloud
erfolgreich erstellt. Der [erste Cloud-Lauf](https://github.com/Captayne/BEAM/actions/runs/36442573334)
enthält die Android-APK, das Windows-MSI und die VPS-Distribution als private Downloads.

Die Cloud richtet die Standard-Builds automatisch ein. Für einen lokalen Clone
müssen JDK, Android SDK und Zusatzdateien weiterhin eingerichtet werden:
[BUILD-LOCAL.md](BUILD-LOCAL.md). Die lokalen Starter verwenden `--offline`;
der erste Download neuer Abhängigkeiten muss ohne diese Option erfolgen.
Die optionalen BBR-Binärdateien bleiben lokal und werden vom Cloud-Workflow
nicht verwendet. Ein erfolgreicher Build ersetzt keinen Geräte- oder Netzwerktest.


## Projektstruktur

```text
app/             BEAM4Android
beam-desktop/    BEAM4PC
beam-relay/      BEAM_RELAY for VPS
beam-core/       gemeinsam verwendeter Code
vps/             Server-Einrichtung und Deployment
gradle/          Gradle-Wrapper und Versionskatalog
tools/           Build-Hilfen und lokale Toolchains
docs/            Projektverwaltung und Lizenzhinweise
native/          Quellcode-Snapshot der angepassten Torrent-Engine
```

Unter `native/beam_torrent/` liegt ein Quellcode-Snapshot der angepassten nativen
Torrent-Engine samt ihren drei Quellabhängigkeiten. Herkunft und Commit-IDs stehen
in `native/beam_torrent/SOURCE-ORIGIN.md`. Das separate Entwicklungsrepository
`beam_torrent/` bleibt lokal erhalten. Die drei Standard-Builds verwenden
weiterhin die in Gradle angegebenen libtorrent4j-Abhängigkeiten.

## Entwicklungsstand und Veröffentlichung

BEAM! wird als **privates Repository Captayne/BEAM** verwaltet. Eine öffentliche
Veröffentlichung ist nicht beschlossen. Die bestehende Projekthistorie und
interne Dokumentation bleiben erhalten. Zugriff erhalten nur ausdrücklich
berechtigte Personen.

Android-Builds sind derzeit Debug-APKs. Für reguläre Android-Releases ist eine
dauerhaft verwaltete Release-Signierung vorzusehen. Ein erfolgreicher Build ist
kein vollständiger Netzwerk- oder Gerätetest.

Der Stand der Vorbereitung ist in
[docs/PUBLICATION.md](docs/PUBLICATION.md) festgehalten.

## Lizenz

Für den eigenen BEAM-Code wurde **keine Open-Source-Lizenz erteilt**.
Die Lizenzentscheidung bleibt offen; es gelten die Rechte der jeweiligen
Rechteinhaber. Ein lokal vorhandener Lizenzvorschlag ist kein Bestandteil
des Git-Repositories und keine erteilte Lizenz.

Verwendete Fremdkomponenten behalten ihre jeweiligen Lizenzbedingungen.
Die bisher geprüften Komponenten und offene Punkte für die Paketverteilung
stehen in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

Projekt von Dr. Andreas Keibel · GitHub: [Captayne](https://github.com/Captayne).
