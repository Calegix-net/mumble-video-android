#!/bin/sh
# Everything that must pass before a commit: formatting, lint, unit tests, a debug build.
set -eu
cd "$(dirname "$0")/.."
./gradlew ktlintCheck lintDebug testDebugUnitTest assembleDebug --continue -q
echo "all checks passed"
