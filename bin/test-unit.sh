#!/bin/sh

# Run unit tests only (fast, no external dependencies)
#
# Usage:
#   ./bin/test-unit.sh               # Run unit tests
#   ./bin/test-unit.sh --debug       # Run with full output (not captured)
#   ./bin/test-unit.sh --watch       # Run in watch mode

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

# Check for --debug flag
DEBUG_OPTS=""
ARGS=""
for arg in "$@"; do
    if [ "$arg" = "--debug" ]; then
        DEBUG_OPTS="--no-capture-output"
        export DEBUG=1
    else
        ARGS="$ARGS $arg"
    fi
done

clojure -M:test unit $DEBUG_OPTS $ARGS
