#!/usr/bin/env bash
# setup-vps.sh -- richtet einen FRISCHEN VPS als Beam-Relay-Station ein.
# Auf dem Server als root ausfuehren (idempotent, mehrfach gefahrlos):
#   scp -i ~/.ssh/beam_relay vps/* root@<IP>:/root/vps/ && ssh ... 'bash /root/vps/setup-vps.sh'
# Getestet auf Ubuntu 24.04 LTS.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "[1/5] Pakete: OpenJDK 21 (headless) + ufw..."
apt-get update -y
apt-get install -y openjdk-21-jre-headless ufw

echo "[2/5] Verzeichnisse..."
mkdir -p /root/beam-relay/lib   # Relay-Distribution (Jars) -> per vps/deploy.ps1
mkdir -p /root/beam-dist        # Beam.msi (token-gated Download) -> per build-pc-beam.ps1

echo "[3/5] Firewall (ufw): SSH(22) + Tracker(80) + Byte-Pipe(443) + BitTorrent(6881)..."
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 6881/tcp
ufw allow 6881/udp
ufw --force enable

echo "[4/5] systemd-Service installieren + aktivieren..."
cp "$HERE/beam-relay.service" /etc/systemd/system/beam-relay.service
systemctl daemon-reload
systemctl enable beam-relay

echo "[5/5] FERTIG mit der Server-Seite."
echo
echo "Jetzt vom Dev-PC aus:"
echo "  1) vps\\deploy.ps1            -> Relay-Jars nach /root/beam-relay/lib/"
echo "  2) build-pc-beam.ps1          -> Beam.msi  nach /root/beam-dist/Beam.msi"
echo "  3) ssh ... 'systemctl start beam-relay && systemctl is-active beam-relay'"
echo
echo "Logs:  tail -f /root/relay.log"
