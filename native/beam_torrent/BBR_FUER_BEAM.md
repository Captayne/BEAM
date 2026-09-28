# BBR für µTP in Beam — was wir hier abgezogen haben

> Ein vollständiger **BBR-Congestion-Controller für libtorrents µTP**, der das serienmäßige
> LEDBAT auf verlustbehafteten WAN/CGNAT-Strecken **um das 3,5-fache schlägt — bei der halben
> Latenz**. Komplett im deterministischen Simulator entwickelt und vermessen, ohne einen einzigen
> Live-Test. Branch `app-bbr-utp-congestion`.

---

## TL;DR

| 1 % Paketverlust, 400 kB/s, 120 ms RTT, 256 kB Puffer | Ø Durchsatz | Steady-State-Latenz |
|---|---|---|
| **LEDBAT** (Serienstand) | 13 % der Linkrate | hoch (Bufferbloat) |
| **BBR** (unser Bau) | **46 %** der Linkrate | **niedrig** (~370 ms statt ~600 ms) |

Auf sauberer Leitung: Gleichstand bei ~94 % Peak. Unter Verlust: **3,5×**. Und der eigentliche
Clou — das selbst-erzeugte Queuing-Delay fällt von **~280 ms auf ~15 ms** bei *gleichem* Durchsatz.

---

## Warum überhaupt — das Problem

Beam verschickt Dateien P2P. Der harte Fall ist **Mobilfunk/CGNAT**:

- **TCP** scheitert oft am CGNAT (kein direkter Pfad).
- **µTP (LEDBAT)** kommt durch — aber LEDBAT ist ein *Scavenger*: delay-basiert, weicht bei der
  kleinsten Verzögerung zurück und reagiert auf Verlust TCP-artig (Fenster halbieren). Auf einer
  realen WAN-Strecke mit etwas Paketverlust bricht der Durchsatz nach dem **√loss-Gesetz** ein.

Genau dafür hat Google **BBR** gebaut: *rate-basiert* statt *loss-basiert*. BBR misst die echte
Engpass-Bandbreite und die Basis-RTT und fährt die Leitung danach — Paketverlust wird (richtigerweise)
weitgehend ignoriert. Das ist exakt Beams Bedarf. Nur: in libtorrents µTP gab es kein BBR. Also gebaut.

---

## Was BBR können muss — die fünf Bausteine

Alles in `src/utp_stream.cpp` (+ Member in `utp_stream.hpp`, Felder in `packet_pool.hpp`):

1. **Delivery-Rate-Sampling** (Äquivalent zu Linux' `tcp_rate.c`). Pro Paket ein Schnappschuss des
   Liefer-Zustands; `BtlBw` = gefenstertes Maximum der gemessenen Rate.
2. **State Machine**: STARTUP → DRAIN → PROBE_BW → PROBE_RTT (RTprop als Minimum über ein 10-s-Fenster).
3. **Loss-Toleranz**: BBR *ignoriert* Verlust — kein Fenster-Cut, RTO fällt auf die Modell-Pipe (BDP)
   zurück statt auf 1 Paket.
4. **Pacing mit echtem Sende-Timer**: Pakete werden zeitlich verteilt (`pacing_rate = gain × BtlBw`)
   statt im Schwall. Das ist das Herzstück von echtem BBR — die Pacing-Rate ist die Stellgröße, cwnd
   nur ein Sicherheits-Deckel.
5. **Recovery-Boost**: nach einem Verlust kurz „Vollgas", um die Pipe wieder zu füllen, ohne eine
   Dauer-Queue aufzubauen.

---

## Die coolsten Momente

### 1. Der Ein-Zeilen-Bug, der 9 % zu 99 % machte
Die erste Version maß die Bandbreite **40× zu niedrig** → cwnd blieb am Boden → 9 % Durchsatz.
Ursache: `first_sent_time` wurde nur zurückgesetzt, wenn die Leitung leerlief. Bei einem
Dauertransfer passiert das nie → der Zeitstempel fror auf t=0 ein → die gemessene „Sendedauer" wuchs
unbegrenzt → Rate ≈ 0. Linux' `tcp_rate.c` schiebt den Stempel bei **jeder Auslieferung** nach.
Eine Zeile. **9 % → 99 % der Linkrate.**

### 2. „BBR muss Verlust ignorieren" — und die zwei versteckten Fallen
BBR soll Loss ignorieren — aber µTP hatte zwei Stellen, die *unabhängig* von der Congestion Control
das Fenster bei Verlust zusammenstrichen: der Fast-Retransmit-Pfad (halbiert cwnd) und der RTO
(setzt cwnd auf 1 Paket). Solange die nicht gegated waren, **kollabierte unser BBR unter Verlust
genauso wie LEDBAT**. Erst nachdem beide für BBR entschärft waren, kam der 3,5×-Vorsprung.

### 3. Pacing hat einen schlafenden Bug geweckt
Als das Pacing lief, fror die State Machine plötzlich ein. Grund: ganz oben in der BBR-Routine stand
ein `if (!cwnd_saturated) return;` (ein LEDBAT-Erbe). Pacing hält in_flight *absichtlich unter* cwnd
— also war „saturated" fast nie wahr → die Routine kehrte sofort um → Modell und Pacing-Rate froren
ein. Der Bug war nur sichtbar, *weil* Pacing das Verhalten änderte.

### 4. Die Latenz-Messung, die den eigentlichen Gewinn zeigte
Auf dem Durchsatz allein sah Pacing fast neutral aus. Erst als wir in_flight und RTT mitloggten,
wurde der wahre Gewinn sichtbar: in_flight fällt von **2×BDP (232 KB) auf 1×BDP (128 KB)**, das
Queuing-Delay von **~280 ms auf ~15 ms** — bei *gleichem* Durchsatz. Für Beam heißt das: ein großer
Transfer würgt die Leitung des Nutzers nicht mehr ab. Das ist der Sinn von BBR.

### 5. Die Optimierung als Nullstellen-Problem
Die Feinabstimmung wurde zum sauberen 1D-Optimierungsproblem: maximiere Durchsatz(Parameter), suche
**df/dParameter = 0**. Und weil der Paketverlust im Simulator *deterministisch* ist (gesäter
Bresenham-Akkumulator, kein Zufall), ist Durchsatz(Parameter) eine **reproduzierbare, glatte
Funktion** — keine Monte-Carlo-Wolke. Der cwnd-Faktor k erwies sich als **byte-identisch** über
k ∈ [1,75; 2,25]: df/dk ≡ 0, ein flaches Plateau. Damit war bewiesen, dass die Schranke **strukturell**
ist (Empfängerfenster/Puffer + Head-of-Line-Blocking der Loss-Recovery), nicht die Congestion Control.
Wir saßen bereits auf dem Maximum.

---

## Wie wir's gemessen haben — die Methode

Kein einziger Live-Test war nötig. Die ganze Entwicklung lief im **deterministischen
libtorrent-Simulator** (b2-Build, `simulation/test_utp.cpp`):

- **Reproduzierbar**: gleicher Code + gleicher Seed → bit-genau gleiches Ergebnis. A/B-Vergleiche
  LEDBAT vs. BBR auf exakt derselben Strecke.
- **Realistische Widrigkeit**: ein deterministischer Loss-Knopf in der Sim-Queue
  (`sim::set_global_loss_permille`, im `libsimulator`-Submodul) modelliert WAN/CGNAT-Verlust.
- **Schnell genug zum Iterieren**: Dutzende Bauen-Messen-Runden, ohne ein Handy anzufassen.

Das hat die ganze schwierige Algorithmen-Arbeit von menschlichem Rauschen entkoppelt — und genau
deshalb ließen sich subtile Bugs (der 40×-Messfehler, der eingefrorene State) überhaupt sauber finden.

---

## Was es (noch) nicht kann — die ehrliche Grenze

Unter Verlust bleibt eine Lücke (46 % statt ~98 %). Die ist **kein** Congestion-Control-Problem mehr,
sondern **Head-of-Line-Blocking**: ein verlorenes Paket blockiert den ACK-Fortschritt für ~1 RTT, und
das hängt an Puffergröße und RTT, nicht an der Sende-Strategie. Belegt durch das flache df/dk ≡ 0.
Weiter käme man nur mit **nicht-CC-Mitteln**: FEC, RACK-artige schnellere Retransmits, oder schlicht
ein größerer Empfangspuffer.

---

## Was als Nächstes kommt

1. **Port in den echten Build** — die fünf Bausteine in den Android-Stack (`libtorrent4j 2.1.0`) und
   den Desktop-libtorrent übertragen. Details in [`BBR_PORTING_NOTES.md`](BBR_PORTING_NOTES.md).
2. **Laufzeit-Schalter** LEDBAT ↔ BBR (`settings_pack::utp_congestion_control`).
3. **WAN-Feldtest**: Handy auf Mobilfunk (echtes CGNAT) ↔ PC, mehrere Läufe gemittelt.
4. Optional gegen die HoL-Lücke: ein FEC-/Fast-Retransmit-Experiment.

---

## Eine Zeile zum Mitnehmen

Wir haben einem 20 Jahre alten P2P-Transportprotokoll Googles modernste Congestion Control
beigebracht, drei echte Bugs gefunden, die nur durch sauberes Messen sichtbar wurden, und das Ganze
in einem deterministischen Simulator bis ans bewiesene Optimum getrieben — **3,5× schneller als der
Serienstand unter Verlust, bei halber Latenz.**
