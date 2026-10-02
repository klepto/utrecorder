#!/usr/bin/env bash
# Copies the server's SQLite database out of the server-home volume into a dated file under /var/backups/utrecorder,
# and keeps the newest 14 copies. Run as root by utrecorder-backup.timer every night and by utrecorder-deploy before
# every deploy. Takes no arguments.
#
# sqlite3 .backup copies a consistent snapshot while the server keeps writing. A host where the volume or the
# database does not exist yet, such as one before its first deploy, has nothing to back up and succeeds.
set -euo pipefail

readonly VOLUME=utrecorder_server-home
readonly TARGET=/var/backups/utrecorder
readonly KEEP=14
# The image's unprivileged user, which owns everything in the volume.
readonly OWNER=10001:10001

if ! home=$(docker volume inspect --format '{{.Mountpoint}}' "$VOLUME" 2>/dev/null); then
    echo "No volume $VOLUME, nothing to back up."
    exit 0
fi
database=$home/database/server.db
if [[ ! -f $database ]]; then
    echo "No database at $database, nothing to back up."
    exit 0
fi

install -d -m 700 "$TARGET"
backup=$TARGET/server-$(date -u +%Y%m%dT%H%M%SZ).db
sqlite3 "$database" ".backup '$backup'"
chmod 600 "$backup"
# sqlite3 runs as root and may have created the write-ahead log or its index beside the database. The server must
# still be able to open them.
for file in "$database-wal" "$database-shm"; do
    if [[ -e $file ]]; then
        chown "$OWNER" "$file"
    fi
done
echo "Backed up $database to $backup."

find "$TARGET" -maxdepth 1 -type f -name 'server-*.db' -printf '%f\n' \
    | sort -r \
    | tail -n "+$((KEEP + 1))" \
    | while read -r old; do
        rm -f -- "${TARGET:?}/$old"
        echo "Removed $TARGET/$old."
    done
