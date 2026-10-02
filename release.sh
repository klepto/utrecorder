#!/bin/sh
# Tags the current commit of development with the version in gradle.properties and pushes development and the tag.
# The pushed tag starts .github/workflows/release.yml, which builds and publishes the server image of that version.
# Bump version= in gradle.properties and commit it first: a -SNAPSHOT version is never released, and a tag that
# already exists is never moved or reused, so a failed release is fixed with a new patch version.
set -eu

cd "$(dirname "$0")"

fail() {
    echo "release.sh: $*" >&2
    exit 1
}

branch=$(git symbolic-ref --quiet --short HEAD) || fail "HEAD is detached; check out development"
[ "$branch" = development ] || fail "on branch $branch; releases are tagged on development"

[ -z "$(git status --porcelain)" ] || fail "the working tree has uncommitted changes"

version=$(sed -n 's/^version=//p' gradle.properties | tr -d '\r')
[ -n "$version" ] || fail "gradle.properties has no version="
case "$version" in
    *-SNAPSHOT) fail "version $version is a snapshot; set a release version in gradle.properties and commit it" ;;
esac

tag="v$version"

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

git tag --annotate --message "$tag" "$tag"
git push --atomic origin development "refs/tags/$tag"

echo "Pushed $tag; .github/workflows/release.yml publishes ghcr.io/klepto/utrecorder-server:$version."
