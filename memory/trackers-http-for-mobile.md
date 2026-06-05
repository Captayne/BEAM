---
name: trackers-http-for-mobile
description: Mobilfunk blockt UDP-Tracker → HTTP/HTTPS-Tracker nötig; parseTrackers-Filter-Bug
metadata: 
  node_type: memory
  type: project
  originSessionId: 944356e9-b04c-4da2-bf38-6fd02b79c83d
---

Auf **Mobilfunk** scheitern **alle UDP-Tracker** (Log: `working=false fails=5`) — Carrier
filtern/blocken UDP. **HTTP/HTTPS-Tracker** laufen über TCP 80/443 und kommen durch. Ohne
funktionierende Tracker meldet sich der Sender nirgends an → Empfänger hängt in "Fetching Metadata".

**Bug, der das verschärfte (gefixt v1.49):** `TorrentViewModel.parseTrackers` filterte
`it.startsWith("udp://")` — selbst manuell eingetragene http(s)-Tracker wurden **stillschweigend
verworfen**. Jetzt: udp/http/https erlaubt. `DEFAULT_TRACKERS` von 5 UDP auf 15 (UDP+HTTP+HTTPS)
erweitert. Sender- UND Empfänger-Pfad nutzen denselben `parseTrackers` (auch SeedingService).

**Tracker-Listen-Quelle (die Frage des Users):** `github.com/ngosang/trackerslist` — täglich
aktualisiert: `trackers_best.txt`, `trackers_all_http.txt`, `trackers_all_https.txt`. Außerdem
`newtrackon.com` (nach Uptime sortiert). Statische Listen veralten → später evtl. Button "Liste
von ngosang aktualisieren" einbauen.

**Auto-Tracker + Status-Anzeige (ab ~v1.6x):** Beim App-Start lädt `refreshAutoTrackers()` die Top 10
aus `ngosang/trackerslist/trackers_best.txt` (HTTPS) und legt sie in Prefs `auto_trackers` ab (wird
jedes Mal ersetzt → veraltet nicht). Effektive Liste = `combinedTrackers()` = manuelle (migriert) ∪
auto. Nutzen ViewModel UND SeedingService. In der Titelzeile zeigt `TrackerStatusIcon` (Sendemast)
die Health: hell = mind. 1 Tracker working, grau + rotes X = aktive Übertragung aber kein Tracker
hilft, ausgeblendet bei Leerlauf. Quelle: `TorrentManager.anyTrackerWorking` (vom Service alle 5s
via `refreshTrackerStatus()` gesetzt). Hinweis: best-Liste ist überwiegend UDP — für Mobilfunk
bleibt ein zuverlässiger eigener HTTPS-Tracker der echte Fix (geplant, User hat IONOS).

**Wichtige Falle:** Eine in den Prefs **gespeicherte** Trackerliste (`"trackers"`-Key) überschattet
`DEFAULT_TRACKERS` komplett — neue Default-Tracker erreichen solche Geräte sonst NIE (genau das
passierte auf S26). Fix ab v1.52: `TorrentViewModel.loadAndMigrateTrackers(prefs)` merged bei
`trackers_version`-Sprung die neuen Defaults in die gespeicherte Liste (Custom-Einträge bleiben).
**Wenn du `DEFAULT_TRACKERS` änderst, MUSS `TRACKERS_VERSION` hochgezählt werden**, sonst greift's
nicht bei Bestandsnutzern. ViewModel und SeedingService nutzen denselben Helper.

**Erwartung dämpfen:** Tracker fixen *Discovery*, nicht die NAT-Physik. Der wahrscheinliche Grund,
warum Mobilfunk *früher* ging: deutsche Carrier vergeben oft **öffentliches IPv6** → mit
funktionierender Discovery verbinden sich beide direkt über IPv6 (kein NAT; App lauscht schon auf
`[::]:6881`). Bei reinem IPv4-CGNAT auf beiden Seiten bleibt die Wand (siehe
[[fetching-metadata-hang]]). Mehr öffentliche Tracker = mehr Privacy-Exposure (Fremd-Peers/
dht-spy), bewusster Trade-off für Zuverlässigkeit.
