# Beam — eine Vorlesung über dezentrale Software
### Modernes Software-Engineering, gelernt an einem echten, laufenden System

---

Die meisten Lehrbücher erklären Programmieren an Spielzeug: „Hello World", eine To-do-Liste, ein
Taschenrechner. Man lernt Syntax — aber nicht, wie echte Software *wirklich* entsteht, wo sie weh tut,
und warum man sie so und nicht anders baut.

Diese Vorlesung geht anders vor. Sie erklärt alles an **Beam** — einer echten App, die Menschen täglich
benutzen, um Fotos und Videos in **Originalqualität** zu teilen: **ohne Cloud, ohne Konto, ohne
Mittelsmann**, direkt von Gerät zu Gerät. Beam wurde in **unter zwei Wochen** gebaut — *gegen* den Strom
der Zentralisierung, die heute fast alles bestimmt.

Genau das macht Beam zum idealen Lehrstück:
- Du siehst, wie **viele Disziplinen zusammenspielen** — Netzwerke, Kryptografie, plattformübergreifende
  Architektur, Server-Betrieb, Benutzerführung.
- Du siehst, **wie es in der Realität schiefgeht** — die teuersten und lehrreichsten Momente, die kein
  Lehrbuch hat (in Beam heißen sie „Gotchas").
- Und du siehst die **Haltung** dahinter: dass man Dinge selbst bauen kann, dezentral, ohne sich einem
  großen Anbieter auszuliefern.

## Für wen ist das?
Für junge Leute, die verstehen wollen, wie moderne Software hinter der Oberfläche funktioniert — und die
keine Angst vor einem **echten** System haben. Du brauchst kein Vorwissen über Netzwerke oder Krypto; das
bauen wir Kapitel für Kapitel auf. Programmier-Grundkenntnisse helfen, sind aber für die Konzepte nicht
Pflicht.

## Die Kapitel (jedes baut auf dem vorigen auf)
| # | Titel | Worum es geht |
|---|---|---|
| **1** | [Warum dezentral?](01-warum-dezentral.md) | Die Motivation, die Ethik, der Haken, der dich packt. |
| 2 | Wie das Netz *wirklich* verbindet | IP, NAT, CGNAT — warum sich zwei Handys nicht finden. |
| 3 | Auf Riesen stehen: BitTorrent | Ein herrenloses Protokoll wiederverwenden statt neu erfinden. |
| 4 | Durch die Wand: das Relay | Systemdesign + Kreativität (der „synthetische Handshake"). |
| 5 | Vertrauen & Geheimnis | Kryptografie — und ein *ehrliches* Bedrohungsmodell. |
| 6 | Ein Hirn, drei Körper | Software-Architektur: ein Kern, drei Plattformen. |
| 7 | Ausliefern | Build, Pakete, Selbstverteilung, Infrastruktur-as-Code. |
| 8 | Die menschliche Schicht | Software für Nicht-Experten + der Realitäts-Check: Feldtest. |

## Wie man die Kapitel liest
Jedes Kapitel verwebt drei Stränge:
1. **Konzept** — die Idee, allgemein verständlich, mit Bildern.
2. **Echter Code** — wo und wie Beam es umsetzt (mit Datei-/Klassennamen, die du im Repo findest).
3. **Eine wahre Geschichte** — wie es schiefging und was wir daraus gelernt haben.

Daneben gehören zwei Begleitdokumente:
- [`ARCHITECTURE.md`](../ARCHITECTURE.md) — die technische **Landkarte** des ganzen Systems.
- Die generierte **API-Doku** (`gradlew dokkaHtmlMultiModule` → `build/dokka/htmlMultiModule/`).

## Der Geist dahinter
Software muss nicht bedeuten, dass ein paar große Firmen deine Daten halten, lesen und verwerten. Man kann
Dinge **selbst** bauen, **direkt** zwischen Menschen, **ohne** jemanden in der Mitte. Das ist kein
nostalgischer Traum — Beam beweist, dass es heute geht, sogar hinter restriktiven Netzen.

Diese Vorlesung gibt das weiter: nicht nur *wie* man baut, sondern *warum* — damit die nächste Generation
weiß, dass sie die Wahl hat.

> *„That a Beam transfer reached you at all is a mark of trust."* — die Begrüßung der App.
> Wissen weiterzugeben ist auch eine.
