#!/bin/sh
# Copies Mumble.proto and MumbleUDP.proto from mumble-core and adds the Java options this app needs.
# The protos are otherwise byte-for-byte upstream; re-run this whenever the core's protocol changes.
#
#   tools/sync-protos.sh [path to mumble-video-core]
set -eu
core=${1:-$HOME/projects/mumble-video-core}
src="$core/src/mumble-core"
dst="$(dirname "$0")/../core-proto/src/main/proto"
rev=$(git -C "$core" rev-parse --short HEAD)

for name in Mumble MumbleUDP; do
    {
        cat "$src/$name.proto"
        printf '\n// Added by tools/sync-protos.sh from mumble-video-core %s.\n' "$rev"
        printf 'option java_package = "com.calegix.mumblevideo.proto";\n'
        printf 'option java_outer_classname = "%sProto";\n' "$name"
    } > "$dst/$name.proto"
done
mkdir -p "$(dirname "$0")/../core-native/src/test/resources/vectors"
cp "$src"/test-vectors/*.json "$(dirname "$0")/../core-native/src/test/resources/vectors/"
echo "synced protos and test vectors from mumble-video-core $rev"
