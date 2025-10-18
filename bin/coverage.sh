#!/bin/sh

# Run code coverage analysis
# Generates HTML report in target/coverage/
# 
# Usage:
#   ./bin/coverage.sh                    # Run coverage on all unit tests
#   ./bin/coverage.sh --text             # Show text summary only
#   ./bin/coverage.sh --html             # Generate HTML report (default)

cd "$(dirname "$0")/.."

echo "Running code coverage analysis..."
echo ""
echo "Note: This only runs unit tests (not integration tests)"
echo "Integration tests require Python/GPU environment"
echo ""

# Run cloverage (excludes integration tests by default since test-integration/ not in test paths)
clojure -M:coverage "$@"

EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
    echo ""
    echo "✅ Coverage analysis complete!"
    echo ""
    echo "HTML report: target/coverage/index.html"
    echo "To view: open target/coverage/index.html"
else
    echo ""
    echo "❌ Coverage analysis failed with exit code $EXIT_CODE"
fi

exit $EXIT_CODE
