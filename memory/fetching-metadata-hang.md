---
name: fetching-metadata-hang
description: "Warum Transfers bei \"Fetching Metadata\" hängen — lokale Peer-Discovery (LSD) braucht MulticastLock"
metadata: 
  node_type: memory
  type: project
  originSessionId: 944356e9-b04c-4da2-bf38-6fd02b79c83d
---

"Fetching Metadata" hängt = der Empfänger hat den Magnetlink, findet/verbindet aber keinen Peer,
um die .torrent-Metadaten zu ziehen. Das ist ein **Discovery/Connect-Problem**, nicht Durchsatz.

**Why (Setup gleiches WLAN/Hotspot):** Tracker und DHT liefern nur die **öffentlichen** IPs der
Handys (im Mobilfunk CGNAT) — damit klappt phone-to-phone keine Verbindung. Der einzige Weg, wie
sich zwei Geräte im selben WLAN/Hotspot ihre **lokalen** Adressen (192.168.x) mitteilen, ist
**LSD (Local Service Discovery)** über Multicast (239.192.152.143:6771). LSD ist aktiv
(`enable_lsd=true` in TorrentManager). ABER: Android verwirft eingehende Multicast-Pakete, solange
die App keinen `WifiManager.MulticastLock` hält → ohne den finden sich die Handys nie.

**How to apply:** Fix ist eingebaut (2026-06-02, ab v1.47): `MulticastLock` in
`SeedingService.acquireLocks()/releaseLocks()` + Permission `CHANGE_WIFI_MULTICAST_STATE` (und
`ACCESS_WIFI_STATE`) im Manifest. **Beide** Geräte müssen die Version mit dem Lock haben, sonst
empfängt die Gegenseite die Announces nicht. Verbindungsart pro Peer steht jetzt im BeamNet-Log
(uTP/TCP, out/in, holepunched). Falls es trotz MulticastLock im Android-Hotspot weiter hängt:
nächster Schritt wäre direktes Peer-Add auf die Hotspot-Gateway-IP (Multicast-Forwarding im SoftAP
ist je nach Gerät unzuverlässig). Siehe [[deploy-to-phones]], [[tcp-only-throughput]].

**DURCHBRUCH Mobilfunk (2026-06-02):** Mobilfunk↔WLAN ist NICHT grundsätzlich chancenlos. Test
zeigte: S26 (Mobilfunk, CGNAT) lädt mit **2–3 MB/s** von/zu S23 (Office-WLAN). Pfad im Log:
`peer 61.8.145.177:6688 TCP/in` — S26 verbindet sich **ausgehend** zu S23 (Quell-Port ≠ 6881 =
CGNAT-Übersetzung). Grund: **CGNAT blockt nur eingehend, ausgehend geht** — und S23 ist von außen
erreichbar (Office-Router öffnet Port per UPnP/NAT-PMP). In DIESEM Test lief es über IPv4 (die
[2a..]-IPv6-Peers trugen 0 B/s). **Bedingung für den IPv4-Weg:** die Nicht-Mobilfunk-Seite muss
erreichbar sein (UPnP-Router). **Mobilfunk↔Mobilfunk: BESTÄTIGT FUNKTIONIEREND (2026-06-02)** — getestet über zwei verschiedene
Carrier (1&1 ↔ Vodafone), in ALLE Richtungen (Mobil↔Mobil, WLAN↔Mobil, Mobil↔WLAN). Sehr
wahrscheinlich über öffentliches Mobilfunk-IPv6 (App lauscht auf [::]:6881; dt. Carrier geben IPv6)
— direkte Verbindung ohne NAT/Relay. Damit ist der ursprünglich als "braucht Relay" abgehakte Fall
ohne Zusatz-Infrastruktur gelöst. (Path noch nicht per Log auf IPv6 verifiziert, aber plausibelste
Erklärung.) Reine IPv4-CGNAT-Beidseitig ohne IPv6 bliebe weiterhin der harte Rest. Auslöser, dass es geht: frisches Re-Announce auf
Mobilfunk (s. u.) — vorher hing die Session im alten Netz-Stand.

**Netzwechsel-Recovery (ab v1.53):** Beam reagierte früher NICHT auf WLAN↔Mobilfunk-Wechsel →
die Session hing im alten Netz-Stand (Tracker `working=false`, DHT eingefroren), bis Neustart.
Jetzt: `SeedingService` registriert `registerDefaultNetworkCallback` (entprellt ~1,5 s) →
`TorrentManager.onNetworkChanged()` (=`reopenNetworkSockets()` + `forceReannounce()` auf allen
Torrents). Zusätzlich manueller **„Start over"-Button** (🔄/Refresh-Icon) in jeder Torrent-Karte
rechts vom Reshare → `ACTION_RESTART` → `TorrentManager.restartTransfer(infoHash)` (gleiche Logik
für genau einen Transfer). Hilft gegen vergunkten Discovery-Stand/Backoff.

**Bestätigt im Feldtest (2026-06-02):** Der Auto-Handler feuert (Log `SeedingService: Netzwechsel
erkannt`) und ein Download **überlebt einen WLAN↔Mobilfunk-Wechsel mitten im Transfer** (nur kurzer
Durchsatz-Einbruch, dann `COMPLETED`). Der 🔄-Button ist damit nur noch Komfort-Backup.
