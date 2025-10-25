#!/bin/bash
# Integration test script for CLAIJ memory interceptor
# Tests memory persistence across all registered LLM models

set -e  # Exit on error

cd "$(dirname "$0")/.."

echo "======================================"
echo "CLAIJ Memory Interceptor Integration Tests"
echo "======================================"
echo

# Check if running with --mock flag for no API calls
if [ "$1" = "--mock" ]; then
    echo "Running with MOCK LLM (no API calls)"
    echo
    clojure -M -e "
    (require '[claij.examples.memory-demo :as demo])
    (System/exit (if (demo/demo-with-mock) 0 1))"
else
    # Check for API key only when running real tests
    if [ -z "$OPENROUTER_API_KEY" ]; then
        echo "ERROR: OPENROUTER_API_KEY not set"
        echo
        echo "Please set your OpenRouter API key:"
        echo "  export OPENROUTER_API_KEY='your-key-here'"
        echo
        echo "Or source the .env file:"
        echo "  source .env"
        exit 1
    fi

    echo "API Key: ${OPENROUTER_API_KEY:0:15}..."
    echo
    
    echo "Running FULL integration test suite (makes real API calls)"
    echo "This will test: Grok, GPT-5, Claude, Gemini"
    echo
    echo "Cost estimate: ~$0.10-0.50 depending on models"
    echo
    read -p "Press ENTER to continue or Ctrl-C to abort..."
    echo
    
    clojure -M -e "
    (require '[claij.examples.memory-demo :as demo])
    (System/exit (if (demo/demo-all-models {:temperature 0.3 :max-tokens 300}) 0 1))"
fi

echo
echo "======================================"
echo "Integration tests complete!"
echo "======================================"
