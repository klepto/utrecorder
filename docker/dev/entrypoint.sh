#!/bin/sh
# Syncs the bind-mounted checkout into the container's own copy, starts a virtual display and runs the command.
set -e

# Gradle writes build/, .gradle/ and .kotlin/ next to the sources, so it builds a copy on the container's filesystem
# instead of the checkout, where the Linux outputs would overwrite the Windows ones. The excludes are anchored to the
# root and to the projects one level under it, because sources hold a package named build.
rsync -a --delete \
    --exclude '/.git/' \
    --exclude '/.idea/' \
    --exclude '/.utrecorder/' \
    --exclude '/build/' --exclude '/*/build/' \
    --exclude '/.gradle/' --exclude '/*/.gradle/' \
    --exclude '/.kotlin/' --exclude '/*/.kotlin/' \
    /source/ /utrecorder/

export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime}"
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"

Xvfb "$DISPLAY" -screen 0 "${SCREEN_SIZE}x24" -nolisten tcp -ac >/dev/null 2>&1 &

for _ in $(seq 1 100); do
    if xdpyinfo -display "$DISPLAY" >/dev/null 2>&1; then
        break
    fi
    sleep 0.1
done
xdpyinfo -display "$DISPLAY" >/dev/null 2>&1 || {
    echo "Xvfb failed to start on $DISPLAY" >&2
    exit 1
}

exec "$@"
