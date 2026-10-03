#!/bin/sh
# Rebuilds the CLI image from the current working tree and, given a demo URL, records it in a CLI container beside the
# server. Everything in the CLI home volume except installs/ is deleted first, so every run starts from an empty
# workspace but reuses the Unreal Tournament installation an earlier run provisioned, and the wall time of a run does
# not include provisioning. Both volumes are then given to the image's unprivileged user, so volumes an older image
# filled as root stay writable. The log of the run goes to .utrecorder/docker/cli.log, its standard out to
# .utrecorder/docker/cli.out, and the mp4 is copied to .utrecorder/docker/.
#
# Usage: record.sh [demo-url]
set -e

cd "$(dirname "$0")"

docker compose build cli
docker compose run --rm --no-deps --user root --entrypoint sh cli -c '
    find "$UTRECORDER_HOME" -mindepth 1 -maxdepth 1 ! -name installs -exec rm -rf {} +
    chown -R utrecorder:utrecorder "$UTRECORDER_HOME" "$UTRECORDER_CACHE"'

if [ $# -eq 0 ]; then
    exit 0
fi

container=utrecorder-cli-run
out=../../.utrecorder/docker
mkdir -p "$out"
docker rm --force "$container" >/dev/null 2>&1 || true
docker compose run -T --name "$container" --no-deps cli "$@" 2>&1 >"$out/cli.out" | tee "$out/cli.log" || true
status=$(docker inspect --format '{{.State.ExitCode}}' "$container")
if [ "$status" -eq 0 ]; then
    # The CLI prints only the path of the mp4 on its standard out, but Xvfb, started by the entrypoint, shares that
    # stream, so the path is taken from the last line rather than from the whole file.
    mp4=$(tail -n 1 "$out/cli.out")
    docker cp "$container:$mp4" "$out/"
    echo "$out/$(basename "$mp4")"
fi
docker rm "$container" >/dev/null
exit "$status"
