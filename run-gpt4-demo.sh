#!/usr/bin/env bash
# Quick test of GPT-4 integration

cd /home/jules/src/claij

echo "=== Testing GPT-4 Memory Demo ==="
echo "Loading environment and calling GPT-4..."
echo ""

clojure -M:dev << 'CLOJURE_SCRIPT'
(require 'user)
(require '[claij.examples.memory-demo :as demo])

;; Run the demo
(demo/demo-with-gpt4)
(System/exit 0)
CLOJURE_SCRIPT
