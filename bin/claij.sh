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
#   GET  /health          - Health check
#   GET  /swagger-ui      - API documentation
#   POST /voice           - Voice assistant (audio in → audio out)
#   GET  /fsms/list       - List available FSMs
#   GET  /fsm/:id/document - Get FSM definition
#   GET  /fsm/:id/graph.svg - Visualize FSM
#   GET  /claij.crt       - Download SSL certificate (for iOS)
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
echo "📚 Swagger UI: http://localhost:$PORT/swagger-ui"
echo "🎤 Voice UI:   http://localhost:$PORT/"
echo ""
if [ "$USE_SSL" = true ]; then
    echo "📱 iOS setup: http://localhost:$PORT/claij.crt"
    echo ""
fi

exec $CMD
