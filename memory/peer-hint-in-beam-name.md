---
name: peer-hint-in-beam-name
description: .beam-Dateiname kodiert optional die Sender-LAN-IP (peer hint) fürs lokale Direktverbinden
metadata: 
  node_type: memory
  type: project
  originSessionId: 944356e9-b04c-4da2-bf38-6fd02b79c83d
---

Seit v1.48: Der `.beam`-Dateiname kann einen **Peer-Hinweis** tragen, damit der Empfänger lokal
**sofort direkt** verbindet (ohne auf Tracker/DHT/LSD zu warten — das war die ~2-Min-Wartezeit in
"Fetching Metadata", siehe [[fetching-metadata-hang]]).

**Format:** `<name>.<40hex-hash>[.pe<12hex>].beam`. Das `pe`-Segment = 4 Byte IPv4 + 2 Byte Port
(Port 6881) hex-kodiert, z. B. `pec0a8b2291ae1` = 192.168.178.41:6881. Logik in
`core/PeerHint.kt` (Kodierung + Wahl der besten WLAN-/Hotspot-IPv4, meidet Mobilfunk-rmnet).

**Bewusste Entscheidung des Users:** Peer-Hint NUR im Dateinamen, **nicht** im Magnetlink. Der
`.beam`-Inhalt bleibt der saubere/portable Magnet (kopierbar, in anderen Torrent-Apps nutzbar).
Empfängerseitig wird der Hint dekodiert und intern als `&x.pe=ip:port` an den Magnet gehängt
(`MainActivity.magnetFromBeamFileName`) — verlässt nie das Gerät.

**Grenzen:** Hilft im Hotspot-Fall und in nicht-isolierten WLANs. Bei **AP-/Client-Isolation**
(z. B. Office-WLAN) bleibt Gerät-zu-Gerät im LAN blockiert — das kann keine App umgehen. Offen
außerdem: Privacy (Transfers laufen weiter über öffentliche Tracker/DHT, Dateiname leakt).
Beide Geräte müssen die Version mit dem Feature haben. Deploy siehe [[deploy-to-phones]].
