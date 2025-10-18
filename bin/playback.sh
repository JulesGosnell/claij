#!/bin/sh

# TTS playback - synthesize and play text to speech
# 
# Requirements:
#   - aplay, paplay, or afplay (audio player)
#   - Piper voice model downloaded
#
# Usage:
#   ./bin/playback.sh "Text to speak"
#
# Examples:
#   ./bin/playback.sh "Hello from Clojure"
#   ./bin/playback.sh "This is a test of text to speech"

cd "$(dirname "$0")/.."

if [ -z "$1" ]; then
  echo "Usage: $0 \"text to speak\""
  exit 1
fi

clojure -M:piper-repl -m claij.tts.test-playback "$@"
