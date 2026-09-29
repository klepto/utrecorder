#!/bin/sh
# Removes the server container, rebuilds the image from the current working tree and starts the server detached.
# Everything in the home volume except cache/ is deleted first, so the next start provisions Unreal Tournament again
# from the downloads it already has.
set -e

cd "$(dirname "$0")"

docker compose down --remove-orphans
docker compose build
docker compose run --rm --no-deps --entrypoint sh server -c \
    'find "$UTRECORDER_HOME" -mindepth 1 -maxdepth 1 ! -name cache -exec rm -rf {} +'
docker compose up --detach
