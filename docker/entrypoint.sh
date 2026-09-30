#!/bin/sh
# Starts the virtual display the recorder's OpenGL capture and Unreal Tournament need, then runs the command.
set -e

export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime}"
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"

# /tmp lives in the container's writable layer, so a `docker stop` followed by `docker start` keeps whatever the
# previous Xvfb left there. The container runs a single display, so its lock file and socket are always stale on a
# restart: remove them before Xvfb binds $DISPLAY again, or it finds the display already active and exits.
rm -f "/tmp/.X${DISPLAY#:}-lock"
rm -f "/tmp/.X11-unix/X${DISPLAY#:}"

Xvfb "$DISPLAY" -screen 0 "${SCREEN_SIZE}x24" -nolisten tcp -ac &

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
