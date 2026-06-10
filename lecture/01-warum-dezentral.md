# Kapitel 1 — Warum dezentral?

> *Bevor wir eine einzige Zeile Code anschauen: Warum sollte man sich die Mühe machen, etwas
> dezentral zu bauen, wenn es doch WhatsApp, Google Drive und iCloud gibt?*

---

## Ein Foto, das nicht ankommt

Stell dir vor, du warst tauchen. Du hast ein Video gefilmt — ein Hai in 30 Metern Tiefe, das Licht
bricht sich im Wasser, jede Schuppe scharf. Oder, echtes Beispiel aus dem Beam-Feldtest: ein kleines
**KUKA-Robotermodell zwischen Korallen** in den Malediven, leuchtend orange auf türkisem Grund.

Du schickst es einem Freund über den üblichen Messenger. Was kommt bei ihm an?

- Die Auflösung ist **runtergerechnet** — aus 4K wird ein matschiges Etwas.
- Das **Aufnahmedatum** und der **GPS-Ort** sind weg (rausgestrippt).
- Der **echte Dateiname** ist weg, ersetzt durch `IMG-20260608-WA0049.jpg`.
- Es gibt ein **Größenlimit**, also schickst du das *kleine* Video, nicht das gute.

Das Original — das, was du gesehen hast — kommt **nie** an. Es wurde unterwegs verändert. Von wem?

## Die große Partei in der Mitte

Zwischen dir und deinem Freund sitzt fast immer ein **Dritter**: der Messenger, die Cloud, die
Plattform. Technisch nennt man diese Form **zentralisiert** oder **Client-Server**:

```
        DU  ───►  [  GROSSER SERVER  ]  ───►  FREUND
       (Client)      (die "Mitte")          (Client)
                         │
                  liest, speichert,
                  komprimiert, wertet aus
```

Deine Datei geht **nicht** direkt zu deinem Freund. Sie geht erst auf einen fremden Server hoch, wird
dort verarbeitet, und von dort lädt dein Freund eine **Kopie**. Dieser Server gehört einer Firma. Und
diese Firma:

- darf laut **AGB** (die du beim Installieren akzeptiert hast) deine Inhalte **scannen** und
  „zur Verbesserung der Dienste" verwenden,
- **komprimiert** deine Medien, um Speicher und Bandbreite zu sparen (deshalb der Qualitätsverlust),
- braucht ein **Konto** von dir (= eine Identität, die sie kennt),
- setzt **Limits**, weil Speicher Geld kostet.

Nichts davon ist „böse" — es ist einfach, **was Zentralisierung bedeutet**: Macht, Daten und Kontrolle
sammeln sich in der Mitte. Bequem für alle — aber die Mitte bestimmt die Regeln.

## Die unbequeme Frage

> Muss das so sein? Was, wenn die Datei **direkt** von dir zu deinem Freund ginge — unverändert,
> ohne dass jemand in der Mitte sie anfasst?

Genau das ist die Idee von **dezentral** oder **Peer-to-Peer** (P2P):

```
        DU  ◄──────────────────────►  FREUND
     (gleichberechtigter        (gleichberechtigter
        "Peer")                     "Peer")
                  kein Server dazwischen,
                  keine Kopie auf fremder Platte
```

Keine Mitte. Zwei gleichberechtigte Geräte („Peers"), die **direkt** miteinander reden. Das Original
fließt von A nach B. Niemand komprimiert, niemand liest mit, niemand braucht ein Konto, niemand setzt
ein Limit. **Du** behältst die Kontrolle.

Das ist die ganze Daseinsberechtigung von Beam — und sie steht sogar in der Begrüßung der App:

> *„No big player gets your data, because there simply is none in the middle. Just Beam to Beam."*

## Der Haken (und das Versprechen dieser Vorlesung)

Wenn dezentral so toll ist — warum macht es dann nicht jeder?

Weil zentralisiert eine echte Stärke hat: **der Server ist immer da.** Er hat eine feste Adresse, ist
rund um die Uhr online, und jeder kann ihn erreichen. Zwei *private* Geräte dagegen sind ein Problem:

- Sie sind mal an, mal aus.
- Sie haben **keine feste, erreichbare Adresse** (dazu Kapitel 2 — das ist überraschend tief!).
- Sie verstecken sich hinter Routern und Firmen-Firewalls.

**Dezentral zu bauen heißt: diese Probleme selbst lösen**, die dir ein Server sonst abnimmt. Genau das
ist der Stoff dieser Vorlesung. Schritt für Schritt:
- Wie finden sich zwei Geräte ohne feste Adresse? → **Kapitel 2 & 3**
- Was, wenn eine Firewall den Direktweg blockt? → **Kapitel 4** (ein wunderschöner Trick)
- Wie bleibt es trotzdem privat und sicher? → **Kapitel 5**

Beam beweist: Es geht. Im Feldtest lief es sogar über Mobilfunk und durch Firmennetze.

## Warum das größer ist als eine Foto-App

Die Form, die Software hat — zentral oder dezentral — ist keine reine Technikfrage. Sie entscheidet,
**wer Macht hat**:

- Das frühe Internet war **dezentral**: E-Mail, das Web, Chat-Protokolle — jeder konnte mitmachen,
  niemandem gehörte das Ganze.
- Heute läuft das meiste über ein paar **zentrale** Plattformen („Walled Gardens"). Bequem — aber sie
  sehen alles, bestimmen die Regeln, und du kannst nicht einfach weg.

Dezentral zu bauen ist deshalb auch eine **Haltung**: Daten und Kontrolle wieder zu den Menschen
verteilen, statt sie in der Mitte zu stapeln. Du musst nicht die Welt retten — aber du solltest
**wissen, dass du die Wahl hast.** Die meisten wissen es nicht mehr.

## Beams Strategie (der pragmatische Mittelweg)

Beam ist nicht naiv-dogmatisch. Es macht drei kluge Dinge:

1. **Ein bestehendes dezentrales Protokoll wiederverwenden** statt eins neu zu erfinden — **BitTorrent**
   (Kapitel 3). Herrenlos, erprobt, überall.
2. **Nur das Minimum an Infrastruktur** dazubauen, und nur **dort, wo das Netz den Direktweg verbietet**
   — eine bewusst *dumme*, ersetzbare Relay-Station (Kapitel 4). Sie sieht nichts, speichert nichts.
3. **Den Menschen die Kontrolle lassen** — kein Konto, kein Limit, optionale Ende-zu-Ende-Verschlüsselung,
   und du entscheidest, wer was wann bekommt.

So bekommt man die Vorteile von dezentral (Originalqualität, Privatheit, keine Mitte) **ohne** komplett
auf Bequemlichkeit zu verzichten.

## Wo das im echten Code lebt
- Es gibt **keinen** „Upload zu Beam"-Server-Code — weil es keinen gibt. Die Engine
  (`beam-core/TorrentManager.kt`) verbindet Geräte direkt.
- Es gibt **keinen** Login, **keine** Konto-Klasse. Such mal danach — du wirst nichts finden. Das ist
  Absicht.
- Die Philosophie steht wörtlich in der Willkommens-Karte (`app/.../MainScreen.kt`, `WELCOME_BODY`).

## Zum Mitdenken
1. Nenne drei Dinge, die ein zentraler Server dir *abnimmt* — und überlege, wer sie im dezentralen Fall
   übernehmen muss.
2. Warum braucht ein zentraler Dienst ein Konto von dir, ein P2P-Transfer aber nicht?
3. Wenn niemand in der Mitte sitzt — wie verhinderst du dann, dass ein *Lauscher* unterwegs mitliest?
   (Halte den Gedanken fest; wir lösen ihn in Kapitel 5.)

## Im nächsten Kapitel
Wir stellen die scheinbar simple Frage, an der alles hängt: **Wie finden sich zwei Handys überhaupt im
Internet?** Die Antwort führt uns zu IP-Adressen, NAT und dem berüchtigten **CGNAT** — und du wirst
verstehen, warum „direkt verbinden" viel schwerer ist, als es klingt.

→ **Kapitel 2: Wie das Netz *wirklich* verbindet**
