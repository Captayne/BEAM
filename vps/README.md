# Beam Relay — VPS (Server-Infrastruktur)

Alles, was den **Relay-Server** betrifft. Damit ist das VPS ein **reproduzierbarer Bestandteil**
von Beam (Infrastructure-as-Code) und wird mit dem Repo aufs NAS gesichert.

> Der Relay-Server ist Beams Sicherheitsnetz: Wenn zwei Seiten keinen Direktweg finden
> (CGNAT, Firmen-Firewall), waehlen beide RAUS zum Relay, das die Verbindung brueckt.

---

## Eckdaten
| | |
|---|---|
| **Provider** | IONOS VPS |
| **IP** | `217.160.159.14` |
| **OS** | Ubuntu 24.04 LTS |
| **Java** | OpenJDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`) |
| **SSH** | `ssh -i ~/.ssh/beam_relay root@217.160.159.14` (Key NICHT im Repo; im NAS-Backup unter `ssh/`) |
| **Dienst** | systemd `beam-relay` (Restart=always) |
| **Logs** | `/root/relay.log` |

## Ports (ufw)
| Port | Wofuer |
|---|---|
| 22/tcp | SSH |
| **80/tcp** | Mini-Tracker (HTTP, token-gated Routen) |
| **443/tcp** | Byte-Pipe / Relay-Roehre (sieht aus wie HTTPS -> kommt durch Firmen-Firewalls) |
| 6881 tcp/udp | BitTorrent (Reserve) |

## Verzeichnis-Layout auf dem Server
```
/root/beam-relay/lib/        # Runtime-Jars (aus Gradle installDist) -> via vps/deploy.ps1
/root/beam-relay/bin/        # Start-Skripte (aus installDist; Service nutzt aber java direkt)
/root/beam-dist/Beam.msi     # token-gated PC-Download (fuer "Share PC-Beam!") -> via build-pc-beam.ps1
/etc/systemd/system/beam-relay.service
/root/relay.log
```

## HTTP-Routen (Port 80, Token `bs7Kf3R9xLmQ2v`, steht in beam-core/RelayConfig.kt)
- `/<token>/announce`  — privater BitTorrent-Tracker
- `/<token>/seed`      — Sender meldet sich als Seeder (Rollen-Signal der Pipe)
- `/<token>/waiting`   — Empfaenger-Heartbeat "ich haenge" (speist die Stuck-Zahl)
- `/<token>/status`    — Anzahl gerade haengender Empfaenger (Badge am Relay-Knopf)
- `/<token>/Beam.msi`  — token-gated Download der PC-Version

---

## A) Frischen Server von Null aufsetzen
1. IONOS-VPS mit Ubuntu 24.04, public IP, SSH-Key hinterlegen.
2. Dateien hochladen + provisionieren:
   ```powershell
   $key = "$env:USERPROFILE\.ssh\beam_relay"
   ssh -i $key root@<IP> "mkdir -p /root/vps"
   scp -i $key vps\beam-relay.service vps\setup-vps.sh root@<IP>:/root/vps/
   ssh -i $key root@<IP> "bash /root/vps/setup-vps.sh"
   ```
3. Relay-Jars + MSI deployen (vom Dev-PC):
   ```powershell
   .\vps\deploy.ps1        # Relay-Distribution -> /root/beam-relay/lib/
   .\build-pc-beam.ps1     # Beam.msi          -> /root/beam-dist/Beam.msi
   ```
4. Starten + pruefen:
   ```powershell
   ssh -i $key root@<IP> "systemctl start beam-relay; systemctl is-active beam-relay; tail -5 /root/relay.log"
   ```
> Wechselt die IP: in `beam-core/.../RelayConfig.kt` (`DEFAULT_HOST`) anpassen + Apps neu bauen,
> oder per `relay.conf` ueberschreiben (siehe RelayConfig-Doc).

## B) Update deployen (Code-Aenderung am Relay)
```powershell
.\vps\deploy.ps1     # baut installDist neu, scp lib\*, systemctl restart
```

## C) Betrieb / Debug
```bash
systemctl status beam-relay      # laeuft es?
systemctl restart beam-relay     # neu starten
tail -f /root/relay.log          # live mitlesen (SEEDER/WARTET/brücke/relayed)
journalctl -u beam-relay -n 50   # systemd-Sicht
```

---

## Was hier NICHT liegt (bewusst)
- **SSH-Private-Key** (`~/.ssh/beam_relay`) — nie ins Repo; liegt im NAS-Backup unter `ssh/`.
- **Die Runtime-Jars / Beam.msi** — werden gebaut (`deploy.ps1` / `build-pc-beam.ps1`), nicht eingecheckt.
- **`relay.log`** — Laufzeit, uninteressant fuers Repo.
