# Privates Repository Captayne/BEAM

BEAM! wird auf Wunsch des Projektinhabers privat verwaltet. Eine spätere
öffentliche Veröffentlichung und die Lizenzwahl sind offen. Derzeit wird
keine Open-Source-Lizenz für den eigenen Projektcode erteilt.

## Inhalt

- BEAM4Android (`app`), BEAM4PC (`beam-desktop`) und BEAM_RELAY for VPS
  (`beam-relay`) mit dem gemeinsamen Modul `beam-core`.
- Bestehende Git-Historie, Projektdokumentation und lokale Build-Starter.
- Quellcode-Snapshot der eigenen libtorrent-Anpassungen und ihrer
  Quellabhängigkeiten unter `native/beam_torrent`.
- Die drei Builds wurden am 28. September 2026 lokal auf Windows offline geprüft.

## Was lokal bleibt

Toolchains, Gradle-Caches, lokale SDK-Konfiguration, gebündelte FFmpeg- und
BBR-Binärdateien, Build-Ergebnisse sowie SSH-Schlüssel gehören nicht zum
Quellrepository. Ein frischer Clone benötigt deshalb noch die in
[BUILD-LOCAL.md](../BUILD-LOCAL.md) beschriebene Einrichtung. Das private
GitHub-Repository ersetzt keine vollständige Sicherung der lokalen Build-Umgebung.

Der unverbindliche MIT-Entwurf bleibt lokal und wird nicht hochgeladen.
Es wird keine `LICENSE` mit einer ungewählten Open-Source-Lizenz hinzugefügt.

## Falls später öffentlich veröffentlicht werden soll

Diese Schritte sind derzeit nicht beauftragt:

- Projektlizenz auswählen.
- Relay-Konfiguration, interne Angaben und komplette Git-Historie auf eine
  Veröffentlichung vorbereiten. Bereits mit Apps ausgelieferte gemeinsame
  Tokens sind keine verlässlichen Geheimnisse.
- Einrichtung aus einem frischen Clone vervollständigen und prüfen.
- Für Binärdownloads die offenen Punkte in
  [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md) erledigen.
- Dauerhafte Android-Release-Signierung einrichten.

## Repository

Zieladresse: https://github.com/Captayne/BEAM

Beim ersten Upload wird die private Sichtbarkeit vor der Übertragung geprüft.
Die Sichtbarkeit darf nicht ohne ausdrücklichen Auftrag geändert werden.
