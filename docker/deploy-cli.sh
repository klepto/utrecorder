#!/bin/sh
# Rebuilds the CLI image from the current working tree and, given a demo URL, records it in a CLI container beside the
# server. Everything in the CLI home volume is deleted first, so every run provisions Unreal Tournament again from the
# downloads the cache volume, shared with the server, already has. The output of the run goes to docker/out/cli.log,
# and the mp4 is copied to docker/out/.
#
# Usage: deploy-cli.sh [demo-url]
set -e

cd "$(dirname "$0")"

docker compose build cli
docker compose run --rm --no-deps --entrypoint sh cli -c \
    'find "$UTRECORDER_HOME" -mindepth 1 -maxdepth 1 -exec rm -rf {} +'

if [ $# -eq 0 ]; then
    exit 0
fi

container=utrecorder-cli-run
mkdir -p out
docker rm --force "$container" >/dev/null 2>&1 || true
docker compose run -T --name "$container" --no-deps cli "$@" | tee out/cli.log || true
status=$(docker inspect --format '{{.State.ExitCode}}' "$container")
if [ "$status" -eq 0 ]; then
    # The CLI prints the path of the mp4 as the last line of its output.
    mp4=$(tail -n 1 out/cli.log)
    docker cp "$container:$mp4" out/
    echo "out/$(basename "$mp4")"
fi
docker rm "$container" >/dev/null
exit "$status"
