#!/bin/sh
# Starts the virtual display the recorder's OpenGL capture and Unreal Tournament need, then runs the command.
set -e

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
