#!/bin/sh

# Run code coverage analysis on UNIT TESTS ONLY
# Uses kaocha with cloverage plugin
#
# Usage:
#   ./bin/coverage.sh               # Run coverage with HTML report
#   ./bin/coverage.sh --debug       # Run with full output (not captured)
#   ./bin/coverage.sh --text        # Include text summary

cd "$(dirname "$0")/.."

echo "Running code coverage analysis (unit tests only)..."
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

clojure -M:test unit --plugin cloverage $DEBUG_OPTS $ARGS

EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
    echo ""
    echo "✅ Coverage analysis complete!"
    echo ""
    echo "HTML report: target/coverage/index.html"
else
    echo ""
    echo "❌ Coverage analysis failed with exit code $EXIT_CODE"
fi

exit $EXIT_CODE
