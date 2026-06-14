# DE  | EN

# Beam! Was ist das?

**Austausch von Originaldateien. Volle Qualität. Keine Cloud. Kein Konto.**  
Beam ist dafür gedacht, ganze Sammlungen von hochauflösenden Dateien bis in den Gigabytebereich ohne Zwischendienst kostenneutral zu teilen und ohne Verlust von Metadaten in den Dateien (Aufnahmeort, Kameraeinstallungen, Dateinahme und Datum...). Ideal für Journalisten oder zum übertragen von Urlaubs-Videos und Fotos. 

## Dateien senden

Teile Dateien aus Galerie, Dateimanager oder einer beliebigen App mit der üblichen Methode an **Beam!**.

Bei Videos kann die Qualität verringert werden auf: **4K · FHD · 720p · 360p · 180p**. Hochskaliert wird nicht, Bildrate bleibt original.

Beam erzeugt eine winzige **.beam**-Datei. Diese teilst / schickst du per WhatsApp, E-Mail, Chat oder sonstwie weiter an den Empfänger.

Am PC: **Senden an → Beam!** nutzen oder Beam öffnen und die Dateien auswählen.

**Wichtig:** Das sendende Gerät ist der direkte Sender ohne Zwischenpufferung der Dateien durch Dritte. _Das genau ist BEAM!_. Es muss wach und online bleiben, bis der Download fertig ist. Wenn das Netzwerk zickt, hilft der **📡 Relay**-Button.

## Chrono: ein ganzes Ereignis in einem Rutsch

600 Fotos von Reise, Party oder Konferenz? Bitte nicht einzeln antippen.

Wähle eine Datei vom **Anfang** und eine vom **Ende** des Ereignisses, teile sie an Beam und aktiviere **Chrono**. Beam nimmt die älteste und neueste gewählte Datei als Zeitraum und fügt alles dazwischen hinzu: Fotos, Videos, Screenshots, WhatsApp-Bilder.

Die Sendekarte zeigt live Anzahl, Gesamtgröße und genauen Zeitraum. So kannst du prüfen, ob es passt, bevor du **BEAM!** drückst.

Chrono sendet Originale, außer du wählst eine kleinere Videoqualität. 
Ladezustand bei langen Transfers im Auge behalten. 

## Dateien empfangen

Öffne die empfangene **.beam**-Datei mit Beam. Am PC sollte ein Klick auf die Beam-Nachricht in gängien Clients wie WhatsApp/ Signal/ Threema die App starten. Sobald Dateien eintreffen, öffnet PC-Beam den Ordner und markiert alle vollständig geladenen Dateien, so dass man sie eintrudeln sieht und von dort gleich weitersortieren kann.      

Fotos und Videos landen in der Galerie; alles andere unter **.../Download/Beam**.

## Verschlüsselung optional

Beim Senden kannst du Verschlüsselung aktivieren und eine Passphrase setzen. Der Empfänger braucht dieselbe Passphrase.

Für sensible Dateien unbedingt nutzen, um nur ""Zeichensuppe"" zu verschicken.

## Symbole auf der Aktivitätskarte

| Symbol | Bedeutung                                                                                                                                                                    |
| ------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 📋     | Link kopieren, z. B. für andere Torrent-Apps.                                                                                                                                |
| 🔄     | Neu verbinden / erneut versuchen, etwa nach Netzwerkwechsel.                                                                                     |
| ↗      | Laufenden Transfer erneut / weiter teilen und weitere Empfänger einladen.                                                                                                    |
| 📡     | Relay. Auf der Senderseite nutzen, wenn direkte Verbindung durch Mobilfunk, Carrier-NAT oder störrische Router blockiert wird. Umgeht bewußt keine Firmensicherheitmaßnahmen |
| ⏳      | Noch keine Datei bereit.                                                                                                                                                     |
| ▶/📂   | ▶: Streamen von unkomprimierten Videos, schon während des Downloads.     <br/>📂: Datei oder Ordner öffnen wen fertig.                                                       |
| 🗑     | ALLES Löschen: löscht empfangene Dateien **und** die Aktivitätskarte. Weg ist weg.                                                                                           |
| ✕      | Entfernt nur die Aktivitätskarte. Die Dateien bleiben erhalten.                                                                                                              |

## Tracker und DHT

Tracker helfen Sender und Empfänger, sich über den gemeinsamen Hash zu finden. Die Standardliste passt; Sender und Empfänger sollten dieselben Tracker nutzen. http://217.160.159.14/bs7Kf3R9xLmQ2v/announce ist ein Beam-eigener Tracker, den nur BEAM! Anwendern verwenden können. Sollte also immer funktionieren.

**DHT erlauben** nutzt zusätzlich die öffentliche Distributed Hash Table. Das kann beim Finden helfen, aber öffentliche DHT-Crawler können den Hash sehen und Verbindungsversuche starten. Für ruhigere Transfers DHT ausschalten. Beams eigene Station, Peer-Hinweise und Relay funktionieren weiter.

Für sensible Dateien gilt: Verschlüsselung schlägt Hoffnung.

## Schneller und zuverlässiger

Am besten: beide Geräte im selben **5-GHz-WLAN**, oder ein Handy macht Hotspot und das andere verbindet sich damit.

Mobile Daten sind unberechenbarer, weil viele Anbieter Carrier-NAT verwenden. Wenn direkte Verbindung nicht klappt: Relay nutzen.

Auf Android Beam frei und performant laufen lassen:

- Akku-Optimierung für Beam ausschalten;
- bei Xiaomi/MIUI o.ä. **Autostart** aktivieren;
- **Datensparen** ausschalten oder uneingeschränkte Daten erlauben;
- sendendes Gerät wach halten und ans Ladegerät hängen.
- Wenn kein Transfer anliegt, schaltet sich BEAM selbst ruhig und spart Energie.

## Firmen-Netzwerk blockiert?

Manche Firmennetze blockieren Peer-to-Peer-Verkehr komplett, besonders mit Sicherheitslösungen wie z.B. Zscaler. Beam kann und soll Firmenregeln nicht austricksen. Lassen wir so! Hintergrund: Firmeneigene Sicherheitsalerts könnten anspringen! Und wir möchten niemanden in Schwierigkeiten bringen. 

Nimm einen Handy-Hotspot oder ein normales Heimnetz. Auch mit Firmen-VPN geht normaler Webverkehr oft per Split-Tunneling direkt raus — und Beam! kann beamen..

## Keep compressed video und data

Wenn du Videos komprimierst, bleiben die komprimierten Kopien auch nach dem Transfer erhalten. Diese Option sichert auch Daten auf dem Handy, die nicht vom Handy selbst stammen, etwa einer angeschlossenen SD-Karte. Usecase: Aufnahmen auf einer angeschlossenen SD (Drohne, GoPro...) z.B. auf den PC beamen (Selektieren und Teilen mit Beam und weiterleiten) und gleichzeitig auf dem Handy sichern .

---

© 2026 Dr. Andreas Keibel
