#!/bin/sh

# Run code coverage analysis on UNIT TESTS ONLY
# Uses kaocha with cloverage plugin
#
# Usage:
#   ./bin/test-coverage.sh               # Run coverage with HTML report
#   ./bin/test-coverage.sh --debug       # Run with full output (not captured)
#   ./bin/test-coverage.sh --codecov     # Generate codecov.json for CI upload
#   ./bin/test-coverage.sh --text        # Include text summary
#
# Thresholds:
#   - Forms coverage: 60% (cloverage built-in)
#   - Lines coverage: 80% (custom check)

cd "$(dirname "$0")/.."

# Unset API keys to ensure unit tests never call external services
# This prevents:
#   a) Accidentally writing tests that cost tokens
#   b) Tests that work locally but fail in CI
unset ANTHROPIC_API_KEY
unset OPENAI_API_KEY
unset OPENROUTER_API_KEY
unset GEMINI_API_KEY
unset GROK_API_KEY

LINE_COVERAGE_THRESHOLD=80

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

# Run cloverage and capture output
OUTPUT=$(clojure -M:test unit --plugin cloverage --cov-fail-threshold 60 $DEBUG_OPTS $ARGS 2>&1)
EXIT_CODE=$?

# Display output
echo "$OUTPUT"

if [ $EXIT_CODE -ne 0 ]; then
    echo ""
    echo "❌ Coverage analysis failed with exit code $EXIT_CODE"
    exit $EXIT_CODE
fi

# Extract line coverage percentage from "ALL FILES" row
LINE_COVERAGE=$(echo "$OUTPUT" | grep "ALL FILES" | awk -F'|' '{print $4}' | tr -d ' %')

if [ -n "$LINE_COVERAGE" ]; then
    # Compare as integers (truncate decimal)
    LINE_INT=$(echo "$LINE_COVERAGE" | cut -d. -f1)
    
    if [ "$LINE_INT" -lt "$LINE_COVERAGE_THRESHOLD" ]; then
        echo ""
        echo "❌ Line coverage ${LINE_COVERAGE}% is below threshold of ${LINE_COVERAGE_THRESHOLD}%"
        exit 1
    else
        echo ""
        echo "✅ Line coverage ${LINE_COVERAGE}% meets threshold of ${LINE_COVERAGE_THRESHOLD}%"
    fi
fi

echo ""
echo "✅ Coverage analysis complete!"
echo ""
echo "HTML report: target/coverage/index.html"

exit 0
