#!/bin/bash
# Integration test script for CLAIJ
# Runs all tests marked with ^:integration metadata

set -e  # Exit on error

cd "$(dirname "$0")/.."

echo "======================================"
echo "CLAIJ Integration Tests"
echo "======================================"
echo

echo "Running tests with ^:integration metadata..."
echo

clojure -M:test --focus :integration

echo
echo "======================================"
echo "Integration tests complete!"
echo "======================================"
