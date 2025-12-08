#!/bin/sh

# Run long-running tests (slow, testing performance/reliability)
# These consume LLM tokens and may take minutes to complete
#
# Usage:
#   ./bin/test-long-running.sh           # Run all long-running tests
#   ./bin/test-long-running.sh --debug   # Run with full output (not captured)
#   ./bin/test-long-running.sh --watch   # Run in watch mode
#
# Note: These tests make real LLM API calls and consume tokens.

cd "$(dirname "$0")/.."

echo "Running long-running tests..."
echo "(Tests marked with ^:long-running metadata)"
echo ""
echo "⚠️  Warning: These tests consume LLM tokens!"
echo ""

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

clojure -M:test long-running $DEBUG_OPTS $ARGS
