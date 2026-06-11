# Kapitel 2 — Wie das Netz *wirklich* verbindet

> *In Kapitel 1 haben wir gesagt: „Die Datei geht direkt von dir zu deinem Freund." Klingt einfach.
> Aber wie findet dein Handy das Handy deines Freundes überhaupt? Diese eine Frage ist
> überraschend tief — und sie erklärt, warum 90 % der Arbeit an Beam Netzwerk-Arbeit war.*

---

## Adressen: die Postanschriften des Internets

Damit zwei Geräte reden können, braucht jedes eine **Adresse** — eine **IP-Adresse** (z. B.
`217.160.159.14`). Das ist wie eine Postanschrift: Wer deine kennt, kann dir etwas schicken.

Server haben **feste, öffentliche** IP-Adressen. Deshalb funktioniert „zentral" so bequem: Der Server
ist wie ein Geschäft mit einer Adresse im Branchenbuch — jeder findet ihn, jederzeit.

Dein **Handy** dagegen? Hat **keine** öffentliche Adresse. Und genau da fängt das Problem an.

## NAT: ein Haus, eine Adresse, viele Bewohner

Zuhause hängen Handy, Laptop, Fernseher, Konsole alle am selben Router. Nach außen hat dieses ganze
Netz nur **eine** öffentliche IP — die deines Routers. Innen vergibt der Router **private** Adressen
(`192.168.x.x`), die es millionenfach gibt und die von außen niemand ansprechen kann.

Dieses Teilen-einer-Adresse heißt **NAT** (Network Address Translation). Bild dir ein **Mietshaus** vor:

```
   INTERNET                    DEIN ROUTER (NAT)              DEINE GERAETE
                                  "ein Briefkasten"
   217.160.x.x  ◄──────────►   84.x.x.x (oeffentlich)   ┌─ 192.168.0.11  Handy
                                  Hausmeister            ├─ 192.168.0.12  Laptop
                                                         └─ 192.168.0.13  TV
```

- **Brief RAUS** (dein Handy ruft einen Server): Der Hausmeister notiert „Wohnung 11 erwartet Antwort",
  schickt den Brief mit der Hausadresse los. Kommt die Antwort, weiß er, zu wem. ✅ **Funktioniert.**
- **Brief REIN** (jemand will *unaufgefordert* dein Handy erreichen): Adressiert an „das Haus" — aber
  **welche Wohnung?** Der Hausmeister hat keine Notiz, weiß es nicht, **wirft den Brief weg.** ❌

Das ist der Kern: **Rausrufen geht, Reingerufen-werden nicht.** Dein Handy kann jeden Server erreichen,
aber kein Server (und kein anderes Handy) kann dein Handy *von sich aus* erreichen.

> Für zwei Handys heißt das: **Beide können raus, keins kann rein.** Wie sollen sie sich da je
> direkt verbinden? Halte die Frage fest — sie ist der rote Faden der nächsten Kapitel.

## CGNAT: zwei Hausmeister übereinander

Im Mobilfunk (und in vielen modernen Anschlüssen) wird's noch enger. Da gibt es nicht nur den Router
zuhause, sondern der **Mobilfunkanbieter** steckt *dich und tausende andere Kunden* hinter **eine
weitere** NAT-Schicht. Das nennt man **CGNAT** (Carrier-Grade NAT):

```
   INTERNET ──► Anbieter-NAT (eine IP fuer 1000e Kunden) ──► dein Router-NAT ──► dein Handy
                     "Hausmeister 2"                          "Hausmeister 1"
```

Jetzt teilst du dir eine öffentliche Adresse mit **Tausenden Fremden**, und du hast nicht mal Zugriff
auf den oberen Hausmeister. Der klassische Notnagel „im Router einen Port aufmachen" (Port-Forwarding)
**hilft nicht mehr** — über dem Router sitzt ja noch der Anbieter. **Niemand** kann dich von außen
erreichen, Punkt.

CGNAT ist der Grund, warum so viele „eigentlich einfachen" P2P-Ideen in der Praxis scheitern. Beam
musste genau das lösen.

## Firewalls & Deep Packet Inspection: wenn der Hausmeister auch noch liest

Bisher ging es ums **Adressieren**. Es gibt eine zweite Schranke: **was** durchgelassen wird.

- Eine **Firewall** entscheidet, welche Verbindungen erlaubt sind (z. B. „nur Port 443 raus").
- **Deep Packet Inspection (DPI)** geht weiter: Sie **schaut in den Datenstrom hinein** und lässt nur
  durch, was *aussieht* wie erlaubter Verkehr (z. B. echtes HTTPS).

Hier eine **wahre Geschichte aus dem Beam-Feldtest** — sie lehrt eine Lektion, die viele Profis
verblüfft:

> Im **Firmennetz von KUKA** war der Port-Test auf :80 und :443 **grün** (TCP-Verbindung klappt). Trotzdem
> übertrug Beam **nichts**, weder rein noch raus. Im Relay-Log sahen wir: Der HTTP-Verkehr auf **:80** kam
> durch (die Quell-Adressen gehörten zu **Zscaler** — einem Cloud-Sicherheits-Proxy, durch den die Firma
> *allen* Verkehr leitet). Aber die eigentliche Datenröhre auf **:443** tauchte **nie** auf: Sobald dort
> rohes BitTorrent (kein TLS) floss, hat die DPI die Verbindung **abgewürgt**.

Die **Lektion** (schreib sie dir hinter die Ohren):

> **Ein grüner Port-Test heißt NICHT, dass Daten fließen.** Der Port-Test prüft nur, ob der
> *TCP-Handshake* klappt (Schicht 4, „Transport"). Ob der *Inhalt* durchgelassen wird (Schicht 7,
> „Anwendung"), ist eine ganz andere Frage. Profis verwechseln das ständig.

Und: Gegen einen Proxy, der **TLS aufbricht** (mit einem Firmen-Zertifikat auf dem PC), hilft auch
Verschlüsseln-auf-:443 nicht — er entschlüsselt mit und sieht das BitTorrent darin. Manche Netze sind
**bewusst dicht** gebaut. Das ist kein Bug, den man „fixt" — das ist Politik in Technik gegossen.

## Wie verbinden sich zwei un-adressierbare Handys dann überhaupt?

Wenn beide nur **raus** können, gibt es zwei Auswege — und Beam nutzt beide:

1. **Ein Treffpunkt mit fester Adresse.** Wenn beide nur rauswählen können, dann wählen eben **beide raus
   zum selben öffentlichen Punkt** — einem Server, den jeder erreicht. Dort tauschen sie Informationen
   aus (oder lassen sich sogar durchreichen). In Beam sind das der **Tracker** (Kapitel 3) und das
   **Relay** (Kapitel 4).
2. **Hole Punching** („ein Loch stanzen"): Ein cleverer Trick, bei dem beide Seiten *gleichzeitig*
   rauswählen und die NAT-Hausmeister so überlisten, dass doch eine Direktverbindung entsteht.
   (BitTorrent/libtorrent macht das automatisch im Hintergrund.)

Erstaunlich oft reicht das: Im Feldtest lief Beam sogar über **Mobilfunk-CGNAT direkt** durch. Nur die
ganz dichten Netze (Firmen-DPI) bleiben außen vor — dafür gibt es das Relay als Netz.

## Wo das im echten Code lebt
- **`beam-core/RelayConfig.kt`** — die feste Adresse des Treffpunkts (`217.160.159.14`), den beide Seiten
  rauswählen.
- **Transport-Strategie** (im Poll-Loop von App & Desktop): erst Direkt-TCP, dann µTP als NAT-Traversal,
  dann das Relay. Siehe [`ARCHITECTURE.md`](../ARCHITECTURE.md) Kapitel 5 + 7b.
- Die ganze Firmen-DPI-Geschichte steht als **Gotcha 17** in der ARCHITECTURE.md — mit der Diagnose
  Schritt für Schritt.

## Zum Mitdenken
1. Warum kann dein Handy einen Server erreichen, aber ein Server nicht *von sich aus* dein Handy?
2. Du bist im Mobilfunk (CGNAT). Ein Freund will dir direkt etwas schicken. Erkläre in einem Satz,
   warum „mach halt einen Port auf" nicht funktioniert.
3. Der Port-Test sagt „:443 offen", aber nichts kommt durch. Was ist der Unterschied zwischen
   „der Port ist offen" und „mein Protokoll wird durchgelassen"?

## Im nächsten Kapitel
Wir haben den Treffpunkt erwähnt — aber wie organisiert man so etwas, ohne alles selbst neu zu erfinden?
Beam stellt sich auf die Schultern eines Riesen: **BitTorrent**. Wir schauen, was ein *Infohash*, ein
*Tracker* und ein *Magnet-Link* sind — und wie aus „ein Foto teilen" ein Mini-Schwarm wird.

→ **Kapitel 3: Auf Riesen stehen — BitTorrent**
