# Memory Index

- [📋 Projekt-Status / Wiedereinstieg](project-status.md) — START HIER: Stand v1.63, was läuft, was offen (eigener Tracker)

- [Build-Setup](build-setup.md) — JAVA_HOME/JBR-Pfad zum Bauen via gradlew (java ist nicht im PATH)

- [Deploy auf Handys](deploy-to-phones.md) — APK auf online-Geräte (S23+S26) via adb devices dynamisch aufspielen

- [Link-Sharing-Ansatz](link-sharing-approach.md) — Links als .beam-Datei teilen, weil WhatsApp magnet:/beam: nicht verlinkt

- [Studio-Sync nach Edits](studio-sync-after-edits.md) — nach Claude-Edits Studio von Disk neu laden, sonst altes APK

- [Transport-Strategie](tcp-only-throughput.md) — TCP-first, µTP nur als Fallback nach ~25s ohne Peer (libtorrent bevorzugt sonst das langsame µTP)

- [.beam-Format Zukunft](beam-file-future.md) — später: Magnetlink-Inhalt raus / Vorschau-Icon rein (erst nach Stabilitätsphase)

- [Fetching-Metadata-Hänger](fetching-metadata-hang.md) — lokale Discovery (LSD) braucht MulticastLock; Tracker/DHT geben nur CGNAT-IPs

- [Peer-Hint im .beam-Namen](peer-hint-in-beam-name.md) — Sender-LAN-IP im Dateinamen kodiert (nicht im Magnet) fürs lokale Direktverbinden

- [HTTP-Tracker für Mobilfunk](trackers-http-for-mobile.md) — UDP blockt im Mobilfunk; http(s)-Tracker nötig; parseTrackers-Filter-Bug gefixt; Liste via ngosang

- [GCM-OOM / gechunkte Krypto](gcm-oom-chunked-crypto.md) — große Dateien verschlüsseln crashte (Android-GCM puffert alles); Fix = 1-MB-Chunks (BEAMENC2)


