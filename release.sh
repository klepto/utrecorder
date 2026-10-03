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
# builds and publishes ghcr.io/klepto/utrecorder-server:<version>. A version is never reused, so a failed release is
# fixed with a new one.
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

if git rev-parse --quiet --verify "refs/tags/$tag" >/dev/null; then
    fail "tag $tag already exists locally"
fi
status=0
git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null || status=$?
case "$status" in
    0) fail "tag $tag already exists on origin" ;;
    2) ;;
    *) fail "could not list the tags on origin (git ls-remote exited with $status)" ;;
esac

if [ "$commit" != "$current" ]; then
    sed -i "s/^version=.*/version=$commit/" gradle.properties
    git commit --quiet --message "chore: set version to $commit" gradle.properties
fi

git tag --annotate --message "$tag" "$tag"
git push --atomic origin development "refs/tags/$tag"

gh workflow run release.yml --ref "$tag" --field "version=$version" ||
    fail "pushed $tag, but could not start the workflow; run: gh workflow run release.yml --ref $tag --field version=$version"

echo "Started release.yml for $version; it publishes ghcr.io/klepto/utrecorder-server:$version. Follow it with: gh run watch"
