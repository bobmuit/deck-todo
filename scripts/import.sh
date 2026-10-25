#!/usr/bin/env bash
# Runs the To Do importer: scripts/import.sh <command> [options]; without arguments it prints help.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew --quiet --console=plain :importer:installDist
exec importer/build/install/deck-todo-import/bin/deck-todo-import "$@"
