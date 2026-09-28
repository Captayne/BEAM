# BeamTP – Projektbriefing für Claude Code

## Kontext: Beam! App
- Android + Windows P2P Dateitransfer-App
- Package: `de.systragon.beam`
- Projekt: `C:\Users\KEIBEL-OFFICE\AndroidStudioProjects\P2PShare`
- Gemeinsame Codebase für Android (libtorrent4j/JNI) und Windows (JVM)
- Features: AES-256-GCM Verschlüsselung, Video-Kompression via MediaCodec, Keep&Leave/Throw UX
- Relay als Fallback (TCP → µTP → Relay)

## Problem
libtorrent's µTP verwendet LEDBAT als Congestion Control.
LEDBAT ist by design konservativ ("low priority") und drosselt massiv:
- Gemessener Throughput im Gigabit-LAN: **5 MB/s mit µTP vs. 120 MB/s mit TCP**
- Faktor 24 Unterschied – kein Tuning-Parameter-Problem, sondern falsches CC-Protokoll

## Lösung: BeamTP
µTP-Framing bleibt komplett (Sequenznummern, ACKs, Retransmit, SACK, NAT-Punch).
Nur der Congestion-Controller wird getauscht: **LEDBAT → BBR-style**

### Ziel-Architektur
- libtorrent bleibt für: Peer Discovery, DHT, NAT-Punch/Hole-Punching, Tracker
- CC-Kern in `utp_stream.cpp`, Funktion `do_ledbat()` wird ersetzt
- Zwei neue Member in `utp_socket_impl`: `m_btl_bw`, `m_rt_prop`

### BBR-Kern (minimal)
```cpp
void utp_socket_impl::do_ledbat(int const acked_bytes, int const delay, int const in_flight)
{
    // BtlBw messen
    double bw = (double)acked_bytes / m_rtt;
    m_btl_bw = std::max(m_btl_bw, bw);

    // RTprop tracken (minimale RTT über Zeitfenster)
    m_rt_prop = std::min(m_rt_prop, (double)delay);

    // cwnd setzen: BtlBw × RTprop × gain
    m_cwnd = (std::int64_t)(m_btl_bw * m_rt_prop * m_gain) << 16;
}
```

Neue Member in `utp_socket_impl`:
```cpp
double m_btl_bw  = 0.0;
double m_rt_prop = 1e9;  // initialisiert auf "sehr groß"
double m_gain    = 1.25; // Probe-Faktor
```

## Build-Setup
- **Android**: NDK-Build → `libbeam.so` (ARM64)
- **Windows**: MSVC/Clang → `libbeam.dll` (x86_64)
- libtorrent4j linkt gegen die custom Library
- JNI-Bridge braucht keine neuen Funktionen (nur CC-Kern getauscht)
- CMakeLists.txt für beide Plattformen

## Warum KISS
- Kein neues Framing, kein neues Protokoll von Grund auf
- Nur `do_ledbat()` ersetzen → minimaler Diff, maximaler Effekt
- Gemeinsame Codebase bleibt erhalten (Kotlin/Java-Layer unverändert)

## Produktvision: BeamTP
- Proprietärer Name, aber µTP-kompatibel für NAT-Traversal
- Schnell wie TCP im LAN, robust wie µTP über NAT/CGNAT
- Positionierung: privater verschlüsselter P2P-Transfer Android ↔ Windows
- Keine Cloud, kein Account, kein Mittelsmann
- App Store ready: AES-256 fällt unter EAR Ausnahme ENC (nur jährliche BIS-Notification)

## Nächste Schritte
1. libtorrent Sourcen clonen (arvidn/libtorrent, aktueller master)
2. `src/utp_stream.cpp` – `do_ledbat()` identifizieren und patchen
3. BBR-Member in `utp_socket_impl` (Header) ergänzen
4. NDK-Build aufsetzen (Android) + MSVC-Build (Windows)
5. Performance-Test: BeamTP vs. TCP im Gigabit-LAN
6. Feedback-Loop: Probe-Zyklus (8 RTTs aggressiv → Drain → Cruise)
