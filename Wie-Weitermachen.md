# Wie weitermachen — Stand 2026-06-08 (autonom, während du bei KUKA warst)

## Was heute früh passierte (Relay-Hänger geknackt)
Der Relay-Transfer S26→PC hing. Per **Relay-Log auf dem VPS** bewiesen, woran:
Die Byte-Pipe **wies ab** → `pipe: ABGEWIESEN eaa063ed (kein Beam-Infohash)`.

**Ursache:** `engageRelay` (exklusiv) entfernte mit `replaceTrackers(emptyList())` AUCH unseren
eigenen Beam-Station-Tracker. → Der Sender meldete den Hash nicht mehr an → das Relay-Gate
(`BeamTracker.knows`) kannte ihn nicht → Pipe abgewiesen. Verschärft dadurch, dass „Relay NOW"
auf **beiden** Seiten gedrückt war → beide exklusiv → keiner meldet mehr an.
(Und: „ging gestern" war in Wahrheit **IPv6-direkt** vom 1und1-Mobilfunk, nicht das Relay.)

## Was ich gefixt habe — Branch `relay-gate-fix` (Commit 2939291), NUR kompiliergeprüft
1. **Gate-Fix** (`beam-core/TorrentManager.engageRelay`): exklusiv behält jetzt NUR den
   Beam-Station-Tracker (öffentliche raus) + `forceReannounce` → Hash bleibt angemeldet → Pipe lässt durch.
2. **Empfänger ohne Button** (Android + Desktop): „Send via Relay" erscheint nur noch beim **Sender**.
   Der Empfänger lauscht ohnehin automatisch am Relay (dein Wunsch).
3. **Rendezvous-Re-Dial**: beide Seiten wählen sich alle ~12 s neu ans Relay, solange `numPeers==0`
   (allein ankommender libtorrent bricht den Handshake sonst nach ~15 s ab) → Fenster überlappen → Paarung.

**Gebaut:** `app-debug.apk` + **`Beam-1.0.26.msi`** (in `beam-desktop/build/compose/binaries/main/msi/`).

## Relay ist jetzt robust (VPS 217.160.159.14)
Läuft als **systemd-Dienst `beam-relay`** (Auto-Restart, übersteht Reboot) und loggt endlich
in `/root/relay.log` (vorher ins Leere!). Gate bleibt an. Details: Memory [[relay-ops]].

## DEIN NÄCHSTER SCHRITT (auf echten Geräten testen, dann mergen)
1. Neue Version aufspielen: **APK auf S23+S26**, **Beam-1.0.26.msi auf PC** installieren.
   (Branch ist `relay-gate-fix` — `main`/„der Schatz" ist unangetastet.)
2. **Relay-Log mitlesen** während des Tests:
   `ssh -i ~/.ssh/beam_relay root@217.160.159.14 "tail -f /root/relay.log"`
   → Erfolg = Zeile **`pipe: PAARE … splice startet`** statt `ABGEWIESEN`.
3. Test: S26 (mobil, Sender) → PC/S23 (Empfänger). Empfänger NICHTS drücken (lauscht selbst).
   Nur am **Sender** „Relay NOW". Sollte jetzt paaren + durchlaufen.
4. Für **reinen** Relay-Beweis (ohne IPv6-Schummel): auf einer Seite IPv6 abschalten/Flugmodus-Trick.
5. Läuft's sauber → `git checkout main && git merge relay-gate-fix`.

## Falls du mich (Claude) neu startest
`cd C:\Users\KEIBEL-OFFICE\androidstudioprojects\beam` → `claude -c` (continue) oder `claude --resume`.
