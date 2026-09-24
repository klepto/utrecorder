#!/bin/sh
# Builds the CLI distribution from the synced checkout and runs it with the given arguments.
set -e

cd /utrecorder
./gradlew --quiet :utrecorder-cli:installDist
exec /utrecorder/utrecorder-cli/build/install/utrecorder-cli/bin/utrecorder-cli "$@"
