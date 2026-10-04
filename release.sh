#!/bin/sh
# Publishes a server image from the head of development: ./release.sh [<version>].
#
# Without an argument, releases a development build: the version in gradle.properties without its pre-release suffix,
# followed by -dev.<commit>, where <commit> is the short hash of the head of development. With an argument, releases
# that version.
#
# A release sets the version in gradle.properties and commits it, unless it is already set. A development build
# commits nothing: release.yml passes its version to the build. Either way, tags the head of development v<version>,
# pushes development and the tag, and starts .github/workflows/release.yml on the tag through the GitHub CLI, which
# builds and publishes ghcr.io/klepto/utrecorder-server:<version>, then follows the run with gh run watch until it is
# done, and exits non-zero if it failed. A version is never reused, so a failed release is fixed with a new one. When
# the tag is already pushed to the head of development, as after a dispatch that failed, only starts the workflow.
set -eu

cd "$(dirname "$0")"

fail() {
    echo "release.sh: $*" >&2
    exit 1
}

[ $# -le 1 ] || fail "usage: ./release.sh [<version>]"

command -v gh >/dev/null || fail "the GitHub CLI is not installed; install it and run gh auth login"

branch=$(git symbolic-ref --quiet --short HEAD) || fail "HEAD is detached; check out development"
[ "$branch" = development ] || fail "on branch $branch; releases are made from development"

[ -z "$(git status --porcelain)" ] || fail "the working tree has uncommitted changes"

current=$(sed -n 's/^version=//p' gradle.properties | tr -d '\r')
[ -n "$current" ] || fail "gradle.properties has no version="

if [ $# -eq 1 ]; then
    version=$1
    commit=$1
else
    version="${current%%-*}-dev.$(git rev-parse --short HEAD)"
    commit=$current
fi
tag="v$version"

printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$' ||
    fail "version $version is not <major>.<minor>.<patch>[-<pre-release>]"

local_tag=$(git rev-parse --quiet --verify "refs/tags/$tag" 2>/dev/null) || local_tag=
status=0
remote_tag=$(git ls-remote --exit-code --tags origin "refs/tags/$tag") || status=$?
case "$status" in
    0) remote_tag=${remote_tag%%[[:space:]]*} ;;
    2) remote_tag= ;;
    *) fail "could not list the tags on origin (git ls-remote exited with $status)" ;;
esac

if [ -n "$local_tag" ] || [ -n "$remote_tag" ]; then
    # A previous run pushed the tag but did not start the workflow: resume it, but only when both tags are the same
    # tag of the head of development.
    [ "$local_tag" = "$remote_tag" ] &&
        [ "$(git rev-parse "$tag^{commit}")" = "$(git rev-parse HEAD)" ] &&
        [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/development)" ] ||
        fail "tag $tag already exists and is not the pushed tag of the head of development"
    echo "Tag $tag is already pushed; starting the workflow only."
else
    if [ "$commit" != "$current" ]; then
        sed -i "s/^version=.*/version=$commit/" gradle.properties
        git commit --quiet --message "chore: set version to $commit" gradle.properties
    fi

    git tag --annotate --message "$tag" "$tag"
    git push --atomic origin development "refs/tags/$tag"
fi

# GitHub can reject a dispatch on a tag pushed moments ago, before it resolves the ref.
attempt=1
until dispatch=$(gh workflow run release.yml --ref "$tag" --field "version=$version"); do
    [ "$attempt" -lt 5 ] || fail "pushed $tag, but could not start the workflow; run ./release.sh again to retry"
    attempt=$((attempt + 1))
    sleep 5
done

# gh workflow run prints the URL of the run it created when GitHub returns one. Otherwise the run is found by its
# run-name, Release <version>, which names one run because a version is never reused.
run=$(printf '%s\n' "$dispatch" | sed -n 's|.*/actions/runs/\([0-9][0-9]*\).*|\1|p' | head -n 1)
attempt=1
while [ -z "$run" ]; do
    [ "$attempt" -le 10 ] || fail "started release.yml for $version, but could not find its run; follow it with gh run watch"
    sleep 3
    run=$(gh run list --workflow release.yml --event workflow_dispatch --json databaseId,displayTitle \
        --jq "map(select(.displayTitle == \"Release $version\"))[0].databaseId // empty")
    attempt=$((attempt + 1))
done

echo "Started release.yml for $version; it publishes ghcr.io/klepto/utrecorder-server:$version."
gh run watch "$run" --exit-status ||
    fail "release.yml failed for $version; see gh run view $run --log-failed"
echo "Published ghcr.io/klepto/utrecorder-server:$version."
