---
name: tcp-only-throughput
description: Transport-Strategie in Beam — TCP-first, µTP nur als Fallback (Durchsatz vs. NAT)
metadata:
  node_type: memory
  type: project
  originSessionId: e9685cb5-084b-4843-bbbc-fb9be3e903c6
---

**Finale Strategie (ab v1.5x, 2026-06-02): TCP-first, µTP als Reserve.**
`TorrentManager.startSession()`: **ausgehend µTP AUS**, **eingehend µTP AN** (jeder lauscht auf µTP,
kostet nix). DHT (UDP) bleibt an. `SeedingService.maybeAdjustTransport()` (5s-Loop) schaltet via
`TorrentManager.setOutgoingUtpEnabled(true)` das **ausgehende** µTP erst zu, wenn ein aktiver
Download **~25 s ohne jeden Peer** hängt; sobald Peers da → zurück auf ausgehend-TCP.
Clou (User-Idee): Seeder muss NICHT eskalieren — er lauscht eh dauerhaft auf eingehendes µTP, also
kann ein eskalierter Downloader µTP IN den Seeder wählen. Lokal bleibt's TCP (Downloader wählt
ausgehend TCP) → 8,8 MB/s bleiben. `resetTransportBaseline()` setzt ausgehend-µTP bei jedem neuen
Transfer auf aus (schützt gegen veraltete Session nach APK-Update).

**Why:** Bei beiden Transporten **bevorzugt libtorrent µTP** — und µTP/LEDBAT bremst (regelt bei
steigender Latenz zurück; auf WLAN/Mobilfunk wird Latenz-Jitter als Stau fehlgedeutet → drosselt
freie Leitung). Feldtest-Beleg: lokal über µTP nur ~2,5 MB/s, über TCP ~6,7–9 MB/s. **Bestätigt 2026-06-02:
nach TCP-first-Umstellung WLAN↔WLAN gemessen 8,8 MB/s** (vorher µTP-gedrosselt ~2,5).
Es gibt KEINEN „prefer TCP nach Speed"-Schalter und kein echtes Transport-Rennen; einzige Stellgröße
sind die enable-Flags → daher die App-seitige TCP-first-Logik.

**How to apply:** µTP NICHT als Default zurückdrehen. Die harten Fälle (Mobil↔WLAN, Mobil↔Mobil
1&1↔Vodafone) liefen über **TCP + IPv6**, nicht über µTP-Hole-Punching (siehe
[[fetching-metadata-hang]]) — µTP-Fallback ist also v.a. Versicherung, zieht selten. „User-Keibel"-
Idee, libtorrent zu forken und LEDBAT zu patchen: bewusst verworfen — wäre ein schlechteres TCP
(Userspace-UDP < Kernel-TCP); das „Latenz egal, Vollgas"-Verhalten liefert TCP/BBR schon. LEDBAT-
Tuning (`utp_target_delay=300`, `utp_gain_factor=16000`, `utp_loss_multiplier=20`) bleibt im Code,
greift aber nur wenn µTP per Fallback an ist. Messen per `adb logcat -s BeamNet` (Transport pro Peer).
Siehe [[build-setup]].
