# Beam-BBR für µTP — Implementierung & Port-Leitfaden

Stand 2026-06-16. Ergebnis: Im deterministischen libtorrent-Simulator schlägt unser BBR das
serienmäßige LEDBAT unter WAN-Verlust deutlich, bei sauberem Link Gleichstand.

## Messergebnis (Simulator, link 400 kB/s, RTT 120 ms, 256-KB-Puffer, 2 MB)

| Szenario      | LEDBAT (Ø / Peak) | BBR (Ø / Peak) |
|---------------|-------------------|----------------|
| sauber (0 %)  | ~74 % / ~88 %     | ~58 % / ~66 % (gleichauf bei gleicher Größe) |
| **1 % Loss**  | **13 % / 16 %**   | **48 % / 69 %**  → **3,6–4,2× schneller** |

Kernaussage: **Da wo TCP/LEDBAT unter WAN-Verlust zusammenbricht (√loss-Gesetz), hält BBR die Rate.**
Genau Beams CGNAT/Mobilfunk-Fall.

## Die 4 entscheidenden Bausteine (alle in `src/utp_stream.cpp` + Member in `include/.../utp_stream.hpp`)

1. **Delivery-Rate-Sampling (tcp_rate.c-Äquivalent)** — Voraussetzung für alles.
   - `packet`-Struct (`packet_pool.hpp`) trägt Schnappschuss `delivered / delivered_time / first_sent_time`.
   - Beim Senden: Snapshot setzen; bei `in_flight==0` `m_first_sent_time/m_delivered_time = now`.
   - In `ack_packet`: `rate = (m_delivered − p->delivered) / max(send_elapsed, ack_elapsed)`; `BtlBw = max(rate)`.
   - **KRITISCHSTER BUG (40× Unterschätzung):** `m_first_sent_time` MUSS bei JEDER Auslieferung auf
     `p->send_time` nachgeschoben werden (wie `tcp_rate_skb_delivered`). Sonst friert es bei Dauertransfer
     auf t=0 ein → `send_elapsed` wächst unbegrenzt → BtlBw ≈ 0 → cwnd am mtu×4-Boden → 9 % statt 99 %.

2. **State Machine** (`do_bbr`): STARTUP → DRAIN → PROBE_BW → (alle 10 s) PROBE_RTT.
   - STARTUP: `cwnd += acked`, gedeckelt auf 2×aktuelle BDP, KEIN cwnd≥bdp-Exit; Plateau = 3 Runden
     <25 % BtlBw-Zuwachs → DRAIN. (Henne-Ei vermeiden!)
   - DRAIN: Ziel 1×BDP, bis `in_flight ≤ bdp` → PROBE_BW.
   - PROBE_BW: cruise bei **2×BDP** (ohne Pacing nötig als Loss-/HoL-Puffer; 1,25× getestet = schlechter).
   - PROBE_RTT: RTprop = Min über 10-s-Fenster; bei Ablauf cwnd auf 4×MTU drainen, 200 ms still messen,
     zurück zu PROBE_BW. Rein ACK-getrieben, kein Extra-Timer.

3. **BBR ignoriert Verlust — Fix A:** in `experienced_loss()` bei `congestion_control()==utp_bbr` SOFORT
   `return` (kein cwnd-Cut). Sonst halbiert der Fast-Retransmit-Pfad das Fenster bei jedem Verlust → Kollaps.

4. **BBR ignoriert Verlust — Fix B:** im RTO-/Timeout-Pfad (`!ignore_loss`) für BBR cwnd NICHT auf 1 MSS,
   sondern auf Modell-BDP (`BtlBw × RTprop`, Floor MTU). Sonst Re-Ramp-Sturm unter Dauer-RTOs.

Beide Loss-Fixes sind **nur für BBR** gegatet; LEDBAT-Verhalten unverändert (Regression geprüft: sauberer
Fall weiter Gleichstand).

## Baustein 5: Pacing (BBR Phase 3) — GEBAUT mit echtem Sende-Timer

- `deadline_timer m_pacing_timer` pro `utp_socket_impl` (Manager exponiert `get_io_context()`).
- Gate in `send_pkt` nach dem Resend-Loop: neue DATA bei `now < m_pacing_next_send` → Timer auf den Slot,
  `return false`. `on_pacing_timer()` ruft `while(send_pkt())` + `maybe_trigger_send_callback({})` (wie `issue_write`).
- Advance am Sendepunkt: `m_pacing_next_send = max(now, m_pacing_next_send) + new_in_flight / pacing_rate`.
- `pacing_rate = gain × BtlBw` in `do_bbr`: STARTUP 2,89 / DRAIN 0,35 / PROBE_BW+RTT **1,0** (flach).
  Der pacing_gain-Zyklus [1.25,0.75,…] NICHT verwenden — auf Single-Flow nur Queue/Jitter.
- Timer-Lifecycle (kein shared_ptr!): Member + `m_pacing_timer.cancel()` im Destruktor + Handler prüft `ec`
  ZUERST (`if (ec) return;`), bevor `this` angefasst wird → kein UAF.
- **ZWEI Bugs, die Pacing nötig machte zu fixen:** (a) `if(!cwnd_saturated) return;` am do_bbr-Anfang ENTFERNEN —
  Pacing hält in_flight unter cwnd → sonst friert die State Machine ein (nur STARTUP-cwnd-Wachstum bleibt
  cwnd_saturated-gated). (b) STARTUP zusätzlich verlassen bei `rtt_ms > 1,5×rtprop` (Pipe voll) — sonst hängt
  STARTUP bei kleinem Puffer fest (RTT bläht → Runden träge → Plateau-Exit zu spät).

## Baustein 6: Recovery-Boost (gegen HoL-Blocking unter Loss)

- Im BBR-Zweig von `experienced_loss`: `m_pacing_next_send = now` → Pacing kurz entsperren, Pipe nach dem
  ~1-RTT-HoL-Stall sofort bis cwnd nachfüllen. Headroom nur bei Bedarf, kein Dauer-Queue.
- Bringt 1%-Loss-Durchsatz 43%→46% bei niedriger Steady-State-Latenz.

## Bekannte Grenze (vermessen)

- Goodput-Lücke unter Loss = **Head-of-Line-Blocking** (ein Verlust blockt ACK-Fortschritt ~1 RTT), NICHT
  Resend-Amplifikation (Fast-Resend ist selektiv). Optimierung über cwnd-Faktor k: f(k) FLACH für k∈[1,75;2,25]
  → Schranke ist STRUKTURELL (adv_wnd/Puffer + HoL-Recovery), nicht CC. Restlücke (46% vs ~98%) nur mit
  nicht-CC-Mitteln zu heben (FEC, RACK-artige schnellere Retransmits, größerer Puffer).
- Latenz-Gewinn durch Pacing (clean, gemessen): in_flight 232→128 KB (2×→1×BDP), Queuing-Delay ~280→~15 ms,
  Durchsatz unverändert (94% Peak @6MB). Für Beam: großer Transfer würgt die Nutzer-Leitung nicht mehr ab.

## Port in den echten Build (Weg zum WAN-Feldtest)

1. Diese 4 Bausteine in den Android-Stack (`libtorrent4j 2.1.0`) und den Desktop-libtorrent übertragen.
   Versions-Diff 2.0.13 ↔ 2.1.0 an `utp_stream.cpp`/`packet_pool.hpp` prüfen (Struktur weitgehend gleich).
2. Laufzeit-Schalter LEDBAT↔BBR an die vorhandene µTP-Slider/Checkbox-Infrastruktur hängen
   (`settings_pack::utp_congestion_control`).
3. WAN-Test: Handy auf Mobilfunk (echtes CGNAT) ↔ PC, BBR vs LEDBAT, mehrere Läufe mitteln.

## Sim-Harness

`simulation/test_utp.cpp`: `run_beam_throughput(cc, label, loss_permille, latency_ms, queue_bytes)`,
huge_torrent = 128 Pieces (2 MB). Loss-Knopf: `sim::set_global_loss_permille()` (libsimulator `queue.cpp`,
deterministischer Bresenham-Drop). Build/Run `C:\boost_dl\b2sim5.bat`, Ergebnis
`C:\boost_dl\beam_throughput.txt`. Lossy-LEDBAT-Läufe kriechen (~40 s sim) — Geduld beim Wall-Clock.
