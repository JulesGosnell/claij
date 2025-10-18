#!/bin/sh

# TTS (Text-to-Speech) service with Piper backend (Clojure implementation)
# Uses libpython-clj to call Python's Piper library
# 
# First-time setup:
#   pip install piper-tts
#   # Download voice model from https://github.com/rhasspy/piper/releases
#
# Usage:
#   ./bin/tts.sh [port] [host] [voice-path]
#
# Or set voice path via environment variable:
#   export PIPER_VOICE_PATH=/path/to/en_US-lessac-medium.onnx
#   ./bin/tts.sh [port] [host]
#
# Examples:
#   ./bin/tts.sh 8001 0.0.0.0 /path/to/model.onnx
#   ./bin/tts.sh 9001                          # custom port, uses PIPER_VOICE_PATH
#   ./bin/tts.sh 8001 localhost                # custom host

cd "$(dirname "$0")/.."

echo "Starting TTS service with Piper backend (Clojure + libpython-clj)..."
echo ""

clojure -M:piper "$@"
