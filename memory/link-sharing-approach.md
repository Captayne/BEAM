---
name: link-sharing-approach
description: Warum Beam Links als .beam-Datei teilt und nicht als magnet:/beam:-Text
metadata: 
  node_type: memory
  type: project
  originSessionId: e9685cb5-084b-4843-bbbc-fb9be3e903c6
---

WhatsApp verlinkt nur `http(s)://`-URLs tippbar; Custom-Schemata wie `magnet:` — und auch ein
künftiges `beam://` — bleiben unklickbarer Text (Ziffern werden als Telefonnummern interpretiert).

**Why:** Der Nutzer teilt Beam-Links primär über Messenger (v. a. WhatsApp). Ein nicht tippbarer
Link macht den Empfang unbrauchbar.

**How to apply:** Beam verschickt den Link als **Datei-Anhang**
`<originalname>.<40-hex-hash>.beam` — der Hash steckt im Dateinamen, der Empfänger liest nur den
Anzeigenamen (kein Stream/Müll). Dateiinhalt = Magnetlink nur noch als Fallback. Empfänger-App ist
für `.beam` registriert.
Reihenfolge der Entscheidungen: 1) `.beam`-Datei (umgesetzt), 2) später ggf. App Links über
`https://…systragon.de`, falls ein echter tippbarer *Link* (statt Anhang) in WhatsApp gewünscht
ist — nur https wird dort verlinkt. Das in der Roadmap geplante `beam://`-Schema löst das
WhatsApp-Problem NICHT. Siehe [[build-setup]] zum Bauen.
