# BEAM in der GitHub-Cloud bauen

## Geprüfter Probelauf

Am 28. September 2026 wurden alle drei Ziele aus einem frischen GitHub-Checkout
erfolgreich gebaut und als private Artefakte hochgeladen:
[erster erfolgreicher Lauf](https://github.com/Captayne/BEAM/actions/runs/36442573334).
Gebauter Commit: `69d5a5b766c8f5fcf7107840543a7f36ce723201`.
Der Test bestätigt das Erzeugen der Pakete; eine Installation auf Geräten oder
ein Funktionstest der Übertragung ist damit nicht durchgeführt.

## Starten und herunterladen

1. [BEAM - Cloud Build](https://github.com/Captayne/BEAM/actions/workflows/cloud-build.yml)
   öffnen und bei GitHub anmelden.
2. **Run workflow** anklicken; als Branch `main` auswählen.
3. Bei `target` zwischen `all`, `android`, `pc` und `vps` wählen.
4. Mit **Run workflow** starten und anschließend den Lauf öffnen.
5. Nach erfolgreichem Abschluss unten unter **Artifacts** die Pakete herunterladen.

| Auswahl | Build-Rechner | Inhalt des Downloads |
| --- | --- | --- |
| `android` | Ubuntu 24.04 x64 | Android Debug-APK |
| `pc` | Windows Server 2022 x64 | Windows-MSI einschließlich FFmpeg |
| `vps` | Ubuntu 24.04 x64 | ZIP mit Relay-JARs und Startskripten |
| `all` | drei getrennte Jobs | alle drei Downloads |

Die äußeren Download-ZIPs enthalten zusätzlich Prüfsummen, Lizenzhinweise und
`build-info.json` mit dem gebauten Commit. Beim Relay ist die eigentliche
installierbare Distribution eine ZIP innerhalb des heruntergeladenen Artefakts.

## Ablauf und Grenzen

- Nur manuell gestartet: Ein normaler Push löst keinen Cloud-Build aus.
- Der Rechner zu Hause wird nicht benötigt und kann ausgeschaltet bleiben.
- Java 21 wird bereitgestellt; Android SDK 35 und Build-Tools 36.0.0 werden
  über den SDK-Manager sichergestellt. Gradle kommt aus dem Projekt-Wrapper.
- Der PC-Build lädt FFmpeg 8.1.1 von einem festgelegten Release und prüft dessen
  SHA256. Die Compose-Build-Werkzeuge stellen WiX bereit.
- Diese Builds verwenden die Standard-libtorrent4j-Bibliotheken. Die lokale
  BBR-Variante wird nicht gebaut, weil ihre vorgebauten JNI-Bibliotheken nicht
  Bestandteil des Git-Repositories sind.
- Pakete werden nur als private Actions-Artefakte gespeichert. Es gibt keine
  automatische Installation, Veröffentlichung als Release oder VPS-Bereitstellung.
- Jeder Job endet spätestens nach 30 Minuten. Abhängigkeiten werden gecacht.
  Artefakte werden nach sieben Tagen gelöscht; wichtige Ergebnisse rechtzeitig
  herunterladen. Verbrauch und Kontingente unter GitHub Settings → Billing prüfen.

## Android-Signierung

Die APK ist ein Test-Build mit dem Debug-Schlüssel des temporären Build-Rechners.
Dieser kann von deiner lokalen Installation und von früheren Cloud-Builds
abweichen. Android kann deshalb ein Update über eine bestehende Installation
ablehnen. Eine Deinstallation würde App-Daten entfernen; bestehende Daten vorher
sichern oder zunächst ein separates Testgerät verwenden.

Eine dauerhafte Signierung wird hier noch nicht eingerichtet. Es werden keine
lokalen Signierschlüssel oder VPS-Zugangsdaten an den Workflow übertragen.

## Versionsnummern

Die im Repository gespeicherte Android-/PC-Buildnummer plus GitHub-Laufnummer
bestimmt die Cloud-Version. Ein erneuter Versuch desselben Laufs behält dieselbe
Version. Diese Anpassung erfolgt ausschließlich im temporären Checkout; der
Workflow schreibt keine Versionsänderungen zurück ins Repository.
Lokale Builds verwenden weiterhin ihre bisherige automatische Versionierung.
Lokale und Cloud-Versionen bilden noch keinen gemeinsamen Release-Zähler.

## Lokal weiterarbeiten

Die vorhandenen Befehle aus [BUILD-LOCAL.md](../BUILD-LOCAL.md) bleiben erhalten.
Die Windows-Umgebungsskripte bevorzugen die lokalen Tools, wenn vorhanden, und
verwenden andernfalls die eingerichteten Umgebungsvariablen des Build-Rechners.

Definition: [cloud-build.yml](../.github/workflows/cloud-build.yml).
