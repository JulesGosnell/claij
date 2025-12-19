#!/bin/bash
#
# Start the CLAIJ server
#
# Usage: ./bin/claij.sh [--port PORT]
#
# Environment variables:
#   OPENROUTER_API_KEY  - Required for LLM calls
#   CLAIJ_API_KEY       - Optional API key for authenticated endpoints
#
# Endpoints:
#   GET  /health          - Health check
#   GET  /swagger-ui      - API documentation
#   POST /voice           - Voice assistant (audio in → audio out)
#   GET  /fsms/list       - List available FSMs
#   GET  /fsm/:id/document - Get FSM definition
#   GET  /fsm/:id/graph.svg - Visualize FSM
#

set -e

cd "$(dirname "$0")/.."

# Check required environment variables
if [ -z "$OPENROUTER_API_KEY" ]; then
    echo "⚠️  Warning: OPENROUTER_API_KEY not set - LLM calls will fail"
fi

# Default port
PORT="${1:-8080}"

# Parse --port argument
while [[ $# -gt 0 ]]; do
    case $1 in
        --port|-p)
            PORT="$2"
            shift 2
            ;;
        *)
            shift
            ;;
    esac
done

echo "🚀 Starting CLAIJ server on http://localhost:$PORT"
echo "📚 Swagger UI: http://localhost:$PORT/swagger-ui"
echo "🎤 Voice endpoint: POST http://localhost:$PORT/voice"
echo ""

exec clojure -M:llms --port "$PORT"
