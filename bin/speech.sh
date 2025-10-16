#!/bin/sh

# Speech-to-text client
# Usage:
#   ./bin/speech.sh                           # Use default URL
#   ./bin/speech.sh http://localhost:8000/transcribe  # Custom URL
#   WHISPER_URL=http://... ./bin/speech.sh    # Via environment variable

cd "$(dirname "$0")/.."

clojure -M:speech "$@"
