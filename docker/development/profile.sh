#!/bin/sh
# Records one demo URL in a CLI container the way record.sh does, and measures the run. The CLI image is rebuilt from the
# working tree this script runs in and the CLI home volume, except installs/, is wiped first, by record.sh itself. Every
# -e option is passed on to the container. While the recording runs, the processes of the container are sampled every 5
# seconds.
#
# Everything the run leaves goes to .utrecorder/docker/profile/<label>/ of the main checkout, found through git's common
# directory, so a run from a workflow worktree or from a temporary checkout of another commit writes beside the others.
# An existing directory of the same label is replaced. It holds:
#
#   cli.log, cli.out   the standard error and standard out of the recording
#   ps.txt             every process sample, each headed by a "# <epoch seconds>" line
#   wall.txt           the wall seconds of the recording
#   *.log, *.ini       the logs and inis of every run workspace in the CLI home volume
#   *.mp4              the recording, when it succeeded
#
# Standard out gets one summary row: the exit code, the wall seconds, the capture seconds between the "Capturing 0%"
# and "Capturing 100%" log lines, the CPU seconds of the video capture's game, the audio capture's game, ffmpeg, the JVM
# and Xvfb in the last sample of each process, the render device of the video capture, the video frame count of the mp4
# from ffprobe on the host, and every "uthooks: timing" line of cli.log. The render device is the package of
# GameRenderDevice under [Engine.Engine] in the video capture's game ini, such as "device=OpenGLDrv". A figure that
# cannot be found is printed as "-". The script exits with the exit code of the recording, after copying the logs and
# printing the row whether the recording succeeded or not.
#
# Usage: profile.sh <label> <demo-url> [-e KEY=VALUE ...]
set -e

# Git Bash would otherwise rewrite the container paths below into Windows paths.
export MSYS_NO_PATHCONV=1

usage() {
    echo "Usage: profile.sh <label> <demo-url> [-e KEY=VALUE ...]" >&2
    exit 2
}

[ $# -ge 2 ] || usage
label=$1
url=$2
shift 2
case "$label" in
    '' | . | .. | */* | *\\*) usage ;;
esac
expect=option
for argument in "$@"; do
    if [ "$expect" = option ]; then
        [ "$argument" = -e ] || usage
        expect=value
    else
        expect=option
    fi
done
[ "$expect" = option ] || usage

cd "$(dirname "$0")"

home=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")
out="$home/.utrecorder/docker/profile/$label"
container=utrecorder-cli-profile

sh ./record.sh >&2

rm -rf "$out"
mkdir -p "$out"
docker rm --force "$container" >/dev/null 2>&1 || true

sample=$(mktemp)
(
    while :; do
        if docker exec "$container" ps -eo pid,etimes,times,args >"$sample" 2>/dev/null; then
            { echo "# $(date +%s)"; cat "$sample"; } >>"$out/ps.txt"
        fi
        sleep 5
    done
) &
sampler=$!
trap 'kill "$sampler" 2>/dev/null || true; rm -f "$sample"' EXIT

start=$(date +%s)
docker compose run -T --name "$container" --no-deps "$@" cli "$url" 2>&1 >"$out/cli.out" | tee "$out/cli.log" >&2 || true
end=$(date +%s)
kill "$sampler" 2>/dev/null || true
wait "$sampler" 2>/dev/null || true
echo "$((end - start))" >"$out/wall.txt"

status=$(docker inspect --format '{{.State.ExitCode}}' "$container" 2>/dev/null) || status=1
docker rm "$container" >/dev/null 2>&1 || true

# The CLI prints only the path of the mp4 on its standard out, but Xvfb, started by the entrypoint, shares that stream,
# so the path is taken from the last line rather than from the whole file.
mp4=
if [ "$status" -eq 0 ]; then
    mp4=$(tail -n 1 "$out/cli.out" | tr -d '\r')
fi
docker compose run --rm -T --no-deps --entrypoint sh cli -c '
    mkdir -p /tmp/profile
    if [ -d "$UTRECORDER_HOME/workspace" ]; then
        find "$UTRECORDER_HOME/workspace" -type f \( -name "*.log" -o -name "*.ini" \) -exec cp {} /tmp/profile/ \;
    fi
    if [ -n "$1" ]; then
        cp "$1" /tmp/profile/
    fi
    tar -cf - -C /tmp/profile .' sh "$mp4" | tar -xf - -C "$out" ||
    echo "profile.sh: copying the workspace files out of the CLI home volume failed" >&2

log=$(sed 's/\x1b\[[0-9;]*m//g' "$out/cli.log" | tr -d '\r')

capture=$(printf '%s\n' "$log" | awk '
    function seconds(time, parts) {
        split(time, parts, ":")
        return parts[1] * 3600 + parts[2] * 60 + parts[3]
    }
    / Capturing 0%/ && first == "" { first = seconds($1) }
    / Capturing 100%/ { last = seconds($1) }
    END {
        if (first == "" || last == "") {
            print "-"
        } else {
            duration = last - first
            if (duration < 0) duration += 86400
            printf "%.1f\n", duration
        }
    }')

cpu=$(awk '
    $1 ~ /^[0-9]+$/ {
        if ($4 ~ /video-capture-.*-ut-bin$/) kind = "video"
        else if ($4 ~ /audio-capture-.*-ut-bin$/) kind = "audio"
        else if ($4 ~ /(^|\/)ffmpeg$/) kind = "ffmpeg"
        else if ($4 ~ /(^|\/)java$/) kind = "jvm"
        else if ($4 ~ /(^|\/)Xvfb$/) kind = "xvfb"
        else next
        kinds[$1] = kind
        seconds[$1] = $3
    }
    END {
        for (pid in seconds) total[kinds[pid]] += seconds[pid]
        split("video audio ffmpeg jvm xvfb", order, " ")
        for (i = 1; i <= 5; i++) {
            printf "%s%s_cpu=%s", (i > 1 ? " " : ""), order[i], (order[i] in total ? total[order[i]] : "-")
        }
        print ""
    }' "$out/ps.txt" 2>/dev/null) || cpu="video_cpu=- audio_cpu=- ffmpeg_cpu=- jvm_cpu=- xvfb_cpu=-"

device=-
for ini in "$out"/video-capture-*-UnrealTournament.ini; do
    if [ -f "$ini" ]; then
        device=$(tr -d '\r' <"$ini" | awk '
            /^\[/ { section = $0 }
            section == "[Engine.Engine]" && /^GameRenderDevice=/ {
                sub(/^GameRenderDevice=/, "")
                sub(/\..*$/, "")
                print
                exit
            }')
        [ -n "$device" ] || device=-
        break
    fi
done

frames=-
if [ -n "$mp4" ] && [ -f "$out/$(basename "$mp4")" ]; then
    frames=$(ffprobe -v error -select_streams v:0 -show_entries stream=nb_frames -of csv=p=0 \
        "$out/$(basename "$mp4")" 2>/dev/null | tr -d '\r') || frames=-
    [ -n "$frames" ] || frames=-
fi

hooks=$(printf '%s\n' "$log" | grep 'uthooks: timing' | sed 's/^.*uthooks: timing/uthooks: timing/' |
    awk '{ printf " | %s", $0 }') || true

echo "$label exit=$status wall=$((end - start)) capture=$capture $cpu device=$device frames=$frames$hooks"
exit "$status"
