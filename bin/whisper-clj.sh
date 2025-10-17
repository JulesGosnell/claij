#!/bin/sh

# Whisper speech-to-text service (Clojure implementation)
# Uses libpython-clj to call Python's Whisper library
# Requires: CUDA-capable GPU with CUDA and cuDNN installed
# 
# First-time setup:
#   pip install openai-whisper torch numpy soundfile
#
# Usage:
#   ./bin/whisper-clj.sh [port] [host] [model-size]
#
# Examples:
#   ./bin/whisper-clj.sh                    # defaults: 8000, 0.0.0.0, small
#   ./bin/whisper-clj.sh 9000               # custom port
#   ./bin/whisper-clj.sh 8000 localhost     # custom host
#   ./bin/whisper-clj.sh 8000 0.0.0.0 tiny  # tiny model (faster, less accurate)

cd "$(dirname "$0")/.."

echo "Starting Whisper speech-to-text service (Clojure + libpython-clj)..."
echo "Requires CUDA-capable GPU for best performance"
echo ""

clojure -M:whisper "$@"
