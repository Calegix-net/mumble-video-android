#!/bin/sh
# Vendors mumble-core (the protocol, crypto and video transport the app binds over JNI) from
# mumble-video-core into core-native/src/main/cpp/mumble-core, plus the one 3rdparty file it uses.
# Taken from a committed ref, never the working tree; CORE_REVISION records which.
#
#   tools/sync-core.sh [ref] [path to mumble-video-core]
set -eu
ref=${1:-core/step4-media-demux}
core=${2:-$HOME/projects/mumble-video-core}
here="$(cd "$(dirname "$0")/.." && pwd)"
dst="$here/core-native/src/main/cpp/mumble-core"
rev=$(git -C "$core" rev-parse --short "$ref^{commit}")

rm -rf "$dst"
mkdir -p "$dst"
git -C "$core" archive --format=tar "$rev" src/mumble-core 3rdparty/arc4random | tar -xf - -C "$dst"
# Not needed in the app: the device test (it wants nlohmann_json) and the standalone build scripts.
rm -rf "$dst/src/mumble-core/tests" "$dst/src/mumble-core/android"
printf "%s %s\n" "$rev" "$ref" > "$dst/CORE_REVISION"
echo "vendored mumble-core $rev ($ref)"
