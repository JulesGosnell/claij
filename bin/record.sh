#!/bin/sh

# Audio recording client for speech-to-text
# Records audio from microphone and sends to Whisper STT service
#
# Usage:
#   ./bin/record.sh                                 # Use default URL
#   ./bin/record.sh http://localhost:8000/transcribe  # Custom URL
#   WHISPER_URL=http://... ./bin/record.sh          # Via environment variable

cd "$(dirname "$0")/.."

clojure -M:record "$@"
