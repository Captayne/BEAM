---
name: beam-file-future
description: Geplante spätere Änderungen am .beam-Dateiformat (erst nach Stabilitätsphase)
metadata: 
  node_type: memory
  type: project
  originSessionId: e9685cb5-084b-4843-bbbc-fb9be3e903c6
---

Aktueller `.beam`-Stand (bewusst so): Dateiname `<Name>.<40-hex-Hash>.beam` ist primär & vollständig;
der Dateiinhalt enthält zusätzlich den Magnetlink **nur als Fallback** (umbenennende Messenger,
keine 0-Byte-Datei). Siehe [[link-sharing-approach]].

**Später geplant (erst wenn die App bei vielen Nutzern stabil läuft — nicht vorher umsetzen, auf
explizites Go des Nutzers warten):**
- Magnetlink-Inhalt **entfernen** → rein der proprietäre Beam-Tag im Dateinamen (Inhalt leer/minimal).
- Alternativ/zusätzlich eine **kleine Vorschau (Icon/Thumbnail)** in die `.beam`-Datei einbetten.

**Why:** Eigenständigeres, „proprietäres" Format ohne Torrent-Geruch; Vorschau verbessert UX.
**How to apply:** Erst Felderfahrung sammeln; dann auf Ansage umstellen.
