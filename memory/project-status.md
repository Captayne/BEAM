---
name: project-status
description: "Beam Statusbericht / Wiedereinstieg — Stand, was läuft, was offen ist (Stand 2026-06-02, v1.63)"
metadata: 
  node_type: memory
  type: project
  originSessionId: 944356e9-b04c-4da2-bf38-6fd02b79c83d
---

**Beam** = Android-P2P-Filesharing (libtorrent4j 2.1.0-31), Handy↔Handy, kein Server dazwischen.
Geteilt wird ein `.beam`-Link (Magnet im Dateinamen) über z. B. WhatsApp. Stand **v1.63**, beide
Testgeräte (S23 + S26) aktuell bespielt. Bauen: [[build-setup]], Deploy: [[deploy-to-phones]].

## Was funktioniert (Feldtest-bestätigt 2026-06-02)
- **WLAN↔WLAN: 8,8 MB/s** (TCP-first, µTP-Bremse weg) — [[tcp-only-throughput]].
- **Mobil↔WLAN** und **Mobil↔Mobil (1&1↔Vodafone)** liefen — ABER abhängig von Trackern/IPv6,
  nicht garantiert (siehe Offen). [[fetching-metadata-hang]].
- **Netzwechsel mitten im Transfer** wird automatisch aufgefangen (re-announce).
- **Verschlüsselung großer Dateien** (606 MB getestet) — gechunktes GCM, kein OOM mehr —
  [[gcm-oom-chunked-crypto]].

## Diese Session umgesetzt
- Transport: **TCP-first, eingehend µTP immer an, ausgehendes µTP-Fallback** nach einstellbarer
  Zeit ohne Peer (Settings „Connectivity", 3–60 s, Default 15) + manueller 🔄-Button je Karte.
- Netzwechsel-Handler (`registerDefaultNetworkCallback`) + Transport-Baseline-Reset je Transfer
  (gegen veraltete Session nach APK-Update).
- **Peer-Hint** (Sender-LAN-IP) im `.beam`-Namen → lokales Direktverbinden — [[peer-hint-in-beam-name]].
- **HTTP/HTTPS-Tracker** + parseTrackers-Fix + Migration; **Auto-Fetch Top-10 von ngosang** beim
  Start (`auto_trackers`) — [[trackers-http-for-mobile]].
- **Granulare Statuszeile** statt „Fetching metadata": „Connecting to trackers… / Searching for
  sender… / Connecting to sender…" (+ Sender: „Waiting for receiver…"). Zeigt sofort WO es hängt.
  (Titel-Sendemast-Icon wurde wieder verworfen zugunsten dieses Texts.)
- Akku-Optimierung-Prompt beim Start; Reshare-Icon; Auto-Versionierung (jeder Build +0.01);
  Connection-/Transport-Logging (`adb logcat -s BeamNet BeamTrk SeedingService`); neueste
  Transfer-Karte zuoberst (`TorrentEntry.createdAt`).

## Offen / nächste Schritte
1. **Eigener HTTPS-Tracker auf IONOS** (User hat dort eine Website) — DER Schritt für *verlässlichen*
   Mobilfunk: öffentliche HTTP-Tracker sind flaky (im Test alle down → Mobil-Transfer scheiterte an
   reiner Discovery). Löst gleichzeitig **Privacy** (eigener Tracker + `private`-Flag → kein
   öffentliches DHT/Tracker-Announce, keine Fremd-Peers, kein Dateinamen-Leak). Geplant: eigenen
   Tracker als Default + optional private-Flag.
2. Optional: Auto-Fetch zusätzlich um **Top-HTTPS-Tracker** ergänzen (ngosang `trackers_all_https`),
   da `best` überwiegend UDP ist. Entschieden: **NICHT** auf HTTP-only umstellen — UDP bleibt fürs
   WLAN das Rückgrat; beide nebeneinander kostet nichts.
3. Privacy generell (falls ohne eigenen Tracker): Inhalt ist mit Verschlüsselung schon dicht;
   Metadaten/IP nur über eigenen Tracker/private-Flag zu verbergen. XOR-Hash-Verschleierung im
   Dateinamen bringt NICHTS (Hash leakt über den Announce, nicht über den Link).
4. [[beam-file-future]]: später Magnetlink-Inhalt raus / Vorschau-Icon rein.

## Bekannte Grenzen
- **Beide Seiten IPv4-CGNAT ohne IPv6** ohne eigenen Tracker/Relay = weiterhin chancenlos.
- Öffentliche HTTP(S)-Tracker unzuverlässig → Mobilfunk ohne eigenen Tracker „Glückssache".
