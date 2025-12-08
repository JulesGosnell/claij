#!/bin/sh

# Run integration tests (fast, testing integration points)
#
# Usage:
#   ./bin/test-integration.sh            # Run all integration tests
#   ./bin/test-integration.sh --debug    # Run with full output (not captured)
#   ./bin/test-integration.sh --watch    # Run in watch mode

cd "$(dirname "$0")/.."

echo "Running integration tests..."
echo "(Tests marked with ^:integration metadata)"
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

clojure -M:test integration $DEBUG_OPTS $ARGS
