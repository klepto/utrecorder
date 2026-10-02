#!/usr/bin/env bash
# Sets up a Debian host to run the utrecorder server. Run once as root over SSH, from a copy of docker/production:
#
#   scp -r docker/production root@recorder.example.com:/tmp/utrecorder
#   ssh root@recorder.example.com /tmp/utrecorder/bootstrap.sh
#
# Installs Docker Engine from Docker's apt repository, unattended-upgrades, ufw and sqlite3, opens ports 22, 80 and
# 443 only, sets Docker's log rotation, adds a 2 GB swap file, fills /opt/utrecorder and enables the nightly backup.
# A second run changes nothing it already did, keeps an existing /opt/utrecorder/.env and updates the other files of
# /opt/utrecorder from the copy it runs from. Takes no arguments.
set -euo pipefail

SOURCE=$(cd "$(dirname "$0")" && pwd)
readonly SOURCE
readonly DIRECTORY=/opt/utrecorder
readonly SWAP=/swapfile

[[ $EUID -eq 0 ]] || { echo "bootstrap.sh: run as root." >&2; exit 1; }

export DEBIAN_FRONTEND=noninteractive

# Docker Engine from Docker's own repository, as https://docs.docker.com/engine/install/debian/ describes.
apt-get update
apt-get install --yes ca-certificates curl
install -d -m 755 /etc/apt/keyrings
curl --fail --silent --show-error --location https://download.docker.com/linux/debian/gpg --output /etc/apt/keyrings/docker.asc
chmod 644 /etc/apt/keyrings/docker.asc
# shellcheck source=/dev/null
codename=$(. /etc/os-release && echo "$VERSION_CODENAME")
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $codename stable" \
    >/etc/apt/sources.list.d/docker.list
apt-get update
apt-get install --yes \
    docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin \
    unattended-upgrades ufw sqlite3

# Security updates install themselves every day.
cat >/etc/apt/apt.conf.d/20auto-upgrades <<'CONFIG'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
CONFIG

# Docker publishes the container ports through its own iptables rules, past ufw. The server's port 8080 is bound to
# 127.0.0.1, so 80 and 443, which Caddy publishes, are the only ones reachable from outside either way.
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443
ufw --force enable

# Container logs rotate at 10 MB, three files each. Docker restarts only when the setting changed.
daemon=$(mktemp)
cat >"$daemon" <<'CONFIG'
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
CONFIG
if ! cmp --silent "$daemon" /etc/docker/daemon.json; then
    install -m 644 "$daemon" /etc/docker/daemon.json
    systemctl restart docker
fi
rm -f "$daemon"
systemctl enable --now docker

# 2 GB of swap beside the 8 GB of memory, so a peak during a recording slows down instead of being killed.
if [[ ! -f $SWAP ]]; then
    fallocate --length 2G "$SWAP"
    chmod 600 "$SWAP"
    mkswap "$SWAP"
fi
if ! swapon --show=NAME --noheadings | grep -qx "$SWAP"; then
    swapon "$SWAP"
fi
if ! grep -q "^$SWAP " /etc/fstab; then
    echo "$SWAP none swap sw 0 0" >>/etc/fstab
fi

install -d -m 755 "$DIRECTORY"
install -m 644 "$SOURCE/compose.yml" "$SOURCE/Caddyfile" "$DIRECTORY/"
install -m 755 "$SOURCE/utrecorder-deploy" "$SOURCE/backup.sh" "$DIRECTORY/"
ln -sfn "$DIRECTORY/utrecorder-deploy" /usr/local/bin/utrecorder-deploy

# Holds secrets such as the Internet Archive keys, so only root reads it. utrecorder-deploy adds UTRECORDER_VERSION.
if [[ ! -e $DIRECTORY/.env ]]; then
    (
        umask 077
        cat >"$DIRECTORY/.env" <<'CONFIG'
# Only Caddy reaches the server, and Caddy sets X-Forwarded-Host and X-Forwarded-Proto.
UTRECORDER_SERVER_TRUST_FORWARDED_HEADERS=true
CONFIG
    )
fi
chown root:root "$DIRECTORY/.env"
chmod 600 "$DIRECTORY/.env"

install -m 644 "$SOURCE/utrecorder-backup.service" "$SOURCE/utrecorder-backup.timer" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now utrecorder-backup.timer

echo "Host ready. Add any UTRECORDER_* keys to $DIRECTORY/.env, then run: utrecorder-deploy <version>"
