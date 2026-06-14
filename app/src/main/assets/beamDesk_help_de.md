# DE | EN

# Beam! Was ist das?

**Originaldateien. Volle Qualität. Keine Cloud. Kein Konto.**  
Beam teilt ganze Sammlungen hochauflösender Dateien — bis in den Gigabytebereich — direkt zwischen PC und Handy, ohne kostenpflichtige Zwischendienste und ohne Verlust der Metadaten in den Dateien (Aufnahmeort, Kameraeinstellungen, Dateiname, Datum). Ideal für Journalisten oder einfach zum Übertragen von Urlaubsvideos und -fotos, wie sie wirklich sind.

## Dateien senden

Drücke **Choose file(s)** und wähle, was du teilen willst — oder nutze im Windows-Explorer **Senden an → Beam!**, oder **zieh die Dateien einfach auf das Beam-Fenster**.

Bei Videos kannst du vorab eine niedrigere **Quality** wählen: **4K · FHD · 720p · 360p · 180p**. Beam verkleinert nur — eine Stufe greift nur, wenn sie kleiner als das Quellvideo ist; sonst wird das Original unangetastet gesendet (nie hochskaliert). Die Bildrate bleibt original.

Beam erzeugt eine winzige **.beam**-Datei. Teile sie über **Share (copy .beam)** (dann in WhatsApp / E-Mail einfügen) oder **Show in Explorer**, um die Datei selbst zu greifen.

**Wichtig:** Dein sendender PC ist der Server. Er muss wach und online bleiben, bis die Empfänger fertig sind — also Energiesparmodus aus. Klappt keine direkte Verbindung, springt das **📡 Relay** ein (siehe unten).

## Chrono: ein ganzes Ereignis in einem Rutsch

Hunderte Fotos von Reise, Party oder Konferenz? Bitte nicht einzeln antippen.

Wähle eine Datei vom **Anfang** und eine vom **Ende** des Ereignisses und aktiviere **Send Chronology**. Beam nimmt die älteste und neueste gewählte Datei als Zeitraum und fügt alles dazwischen hinzu (gleicher Ordner).

Die Sende-Vorschau zeigt live Anzahl, Gesamtgröße und genauen Zeitraum — so kannst du prüfen, bevor du **BEAM!** drückst. Chrono sendet Originale, außer du wählst eine kleinere Videoqualität.

## Dateien empfangen

Drücke **Open .beam** und wähle die empfangene Datei — oder **doppelklicke** einfach eine gespeicherte **.beam**. Der Transfer startet automatisch; der Empfänger muss nichts weiter tun, nicht einmal fürs Relay.

Sobald Dateien eintreffen, öffnet Beam den Zielordner und markiert jede vollständig geladene Datei, so dass du sie eintrudeln siehst — in der Großsymbol-Ansicht sogar als Live-Vorschau — und gleich weitersortieren kannst. Dateien landen in **Downloads\Beam**; **Show in Explorer** öffnet den Ordner.

## Verschlüsselung optional

Beim Senden kannst du Verschlüsselung aktivieren und eine Passphrase setzen — Ende-zu-Ende. Der Empfänger braucht dieselbe Passphrase — selbst das Relay sieht immer nur Zeichensuppe.

## Knöpfe auf einer Transferkarte

| Knopf | Bedeutung |
| --- | --- |
| **Share (copy .beam)** | Kopiert die .beam in die Zwischenablage; in WhatsApp / E-Mail als Anhang einfügen. |
| **Show in Explorer** | Zeigt die .beam-Datei (Sender) bzw. die empfangenen Dateien (Empfänger). |
| **Reconnect** | Erneut versuchen, z. B. nach einem Netzwerkwechsel. |
| **📡 Relay NOW!** | Nur Sender. Ist keine direkte Übertragung möglich (z. B. beide hinter Carrier-NAT), fließen die Daten anonym in Häppchen durch das Beam-Relay. Der Empfänger erledigt das Relay automatisch. |
| **Remove** | Entfernt die Karte; die empfangenen Dateien bleiben erhalten. |

## Tracker und DHT

Tracker helfen Sender und Empfänger, sich über den gemeinsamen Hash zu finden. Die Standardliste passt; beide Seiten sollten dieselben Tracker nutzen. `http://217.160.159.14/bs7Kf3R9xLmQ2v/announce` ist ein Beam-eigener Tracker für BEAM!-Anwender, sollte also immer funktionieren.

**DHT erlauben** nutzt zusätzlich die öffentliche Distributed Hash Table. Das kann beim Finden helfen, aber öffentliche DHT-Crawler können den Hash sehen und Verbindungsversuche starten. Für ruhigere Transfers DHT ausschalten (in den Settings der Handy-App). Für sensible Dateien gilt: Verschlüsselung schlägt Hoffnung.

## Schneller und zuverlässiger

- Am schnellsten: direkte Verbindung im selben Netz (LAN / WLAN). Das Relay ist nur Fallback.
- Über Carrier-NAT (häufig im Mobilfunk und in manchen Firmennetzen) klappt eine direkte Verbindung nicht immer; dann springt das Relay ein.
- Sendenden PC wach halten (Energiesparmodus aus), bis der Empfänger fertig ist.

## Firmen-Netzwerk blockiert?

Manche Firmennetze blockieren Peer-to-Peer-Verkehr komplett, besonders mit Sicherheitslösungen wie Zscaler. Beam kann und soll Firmenregeln nicht umgehen — und wir wollen niemanden in Schwierigkeiten bringen.

Tipp: Nimm einen Handy-Hotspot oder ein Heimnetz. Auch mit Firmen-VPN geht normaler Webverkehr oft per Split-Tunneling direkt raus — und Beam! kann beamen.

## WhatsApp öffnet eine .beam nicht?

Nach Installation/Update von Beam sagt WhatsApp Desktop evtl. „wird geöffnet", aber nichts passiert — Windows verwirft bei jeder (Neu-)Installation den Sicherheits-Hash der Dateiverknüpfung. Lösung: **🔧 Repair** (Titelleiste) → in den Settings **.beam → Beam** setzen, dann **einmal ab- und wieder anmelden**. In der Zwischenzeit klappt ein Doppelklick auf eine gespeicherte .beam immer.

Das Fenster ist eine kompakte, frei skalierbare Box; es merkt sich nichts, was du nicht willst — keine Cloud, kein Konto.

---

© 2026 Dr. Andreas Keibel
