#!/bin/sh

# Run unit tests only (fast, no external dependencies)
#
# Usage:
#   ./bin/test-unit.sh               # Run unit tests
#   ./bin/test-unit.sh --debug       # Run with full output (not captured)
#   ./bin/test-unit.sh --watch       # Run in watch mode

cd "$(dirname "$0")/.."

# Check for --debug flag
DEBUG_OPTS=""
ARGS=""
for arg in "$@"; do
    if [ "$arg" = "--debug" ]; then
        DEBUG_OPTS="--no-capture-output"
    else
        ARGS="$ARGS $arg"
    fi
done

clojure -M:test unit $DEBUG_OPTS $ARGS
