#!/bin/sh
# Removes the server container, rebuilds the image from the current working tree and starts the server detached.
# Everything in the home volume is deleted first, so the next start provisions Unreal Tournament again from the
# downloads the cache volume already has. A cache left in the home volume by an older image is moved into the cache
# volume before the home is emptied. Both volumes are then given to the image's unprivileged user, so volumes an older
# image filled as root stay writable.
set -e

cd "$(dirname "$0")"

docker compose down --remove-orphans
docker compose build server
docker compose run --rm --no-deps --user root --entrypoint sh server -c '
    if [ -d "$UTRECORDER_HOME/cache" ]; then
        cp -a -n "$UTRECORDER_HOME/cache/." "$UTRECORDER_CACHE/"
    fi
    find "$UTRECORDER_HOME" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
    chown -R utrecorder:utrecorder "$UTRECORDER_HOME" "$UTRECORDER_CACHE"'
docker compose up --detach server
