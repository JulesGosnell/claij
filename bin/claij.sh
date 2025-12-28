#!/bin/bash
#
# Start the CLAIJ server
#
# Usage: ./bin/claij.sh [options]
#
# Options:
#   -p, --port PORT       HTTP port (default: 8080, use 0 to disable)
#   -s, --ssl-port PORT   HTTPS port (default: 8443)
#   --no-ssl              Disable HTTPS
#
# Environment variables:
#   OPENROUTER_API_KEY  - Required for LLM calls
#   CLAIJ_API_KEY       - Optional API key for authenticated endpoints
#
# Endpoints:
#   GET  /                 - FSM catalogue (redirects to /fsms)
#   GET  /health           - Health check
#   GET  /fsms             - FSM catalogue UI
#   GET  /fsm-swagger      - OpenAPI UI for FSM endpoints
#   GET  /bdd              - BDD Voice Assistant UI
#   POST /voice            - Voice endpoint (audio in → audio out)
#   GET  /fsms/list        - List available FSMs (JSON)
#   GET  /fsm/:id          - FSM detail page
#   GET  /fsm/:id/document - Get FSM definition (JSON)
#   GET  /fsm/:id/graph.svg - Visualize FSM
#   POST /fsm/:id/run      - Execute FSM with input
#   GET  /install-cert     - SSL certificate install instructions
#

set -e

cd "$(dirname "$0")/.."

# Check required environment variables
if [ -z "$OPENROUTER_API_KEY" ]; then
    echo "⚠️  Warning: OPENROUTER_API_KEY not set - LLM calls will fail"
fi

# Defaults
PORT=8080
SSL_PORT=8443
USE_SSL=true
KEYSTORE="claij-dev.jks"
KEY_PASSWORD="changeit"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --port|-p)
            PORT="$2"
            shift 2
            ;;
        --ssl-port|-s)
            SSL_PORT="$2"
            shift 2
            ;;
        --no-ssl)
            USE_SSL=false
            shift
            ;;
        *)
            shift
            ;;
    esac
done

# Generate SSL certificate if needed
if [ "$USE_SSL" = true ] && [ ! -f "$KEYSTORE" ]; then
    echo "🔐 Generating SSL certificate..."
    ./bin/gen-ssl-cert.sh
    echo ""
fi

# Build command
CMD="clojure -M -m claij.server --port $PORT"

if [ "$USE_SSL" = true ]; then
    CMD="$CMD --ssl-port $SSL_PORT --keystore $KEYSTORE --key-password $KEY_PASSWORD"
fi

# Print startup info
echo "🚀 Starting CLAIJ server"
if [ "$PORT" != "0" ]; then
    echo "   HTTP:  http://localhost:$PORT"
fi
if [ "$USE_SSL" = true ]; then
    echo "   HTTPS: https://localhost:$SSL_PORT"
fi
echo ""
echo "📋 FSM Catalogue:  http://localhost:$PORT/fsms"
echo "📚 OpenAPI UI:     http://localhost:$PORT/fsm-swagger"
echo "🎤 BDD Voice UI:   http://localhost:$PORT/bdd"
echo ""
if [ "$USE_SSL" = true ]; then
    echo "📱 iOS setup: http://localhost:$PORT/install-cert"
    echo ""
fi

exec $CMD
