#!/bin/sh

# Whisper speech-to-text service
# Requires: CUDA-capable GPU with CUDA and cuDNN installed
# 
# First-time setup:
#   pip install -r src/py/requirements.txt
#
# Usage:
#   ./bin/whisper.sh

cd "$(dirname "$0")/.."

echo "Starting Whisper speech-to-text service on port 8000..."
echo "Requires CUDA-capable GPU for best performance"
echo ""

cd src/py
uvicorn whisper:app --host 0.0.0.0 --port 8000 --reload
