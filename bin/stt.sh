#!/bin/sh

# STT (Speech-to-Text) service with Whisper backend (Clojure implementation)
# Uses libpython-clj to call Python's Whisper library
# Requires: CUDA-capable GPU with CUDA and cuDNN installed
# 
# First-time setup:
#   pip install openai-whisper torch numpy soundfile
#
# Usage:
#   ./bin/stt.sh [port] [host] [model-size]
#
# Examples:
#   ./bin/stt.sh                    # defaults: 8000, 0.0.0.0, small
#   ./bin/stt.sh 9000               # custom port
#   ./bin/stt.sh 8000 localhost     # custom host
#   ./bin/stt.sh 8000 0.0.0.0 tiny  # tiny model (faster, less accurate)

cd "$(dirname "$0")/.."

echo "Starting STT service with Whisper backend (Clojure + libpython-clj)..."
echo "Requires CUDA-capable GPU for best performance"
echo ""

clojure -M:whisper "$@"
