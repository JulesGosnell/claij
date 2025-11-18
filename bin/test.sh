#!/bin/sh

# Run unit tests only (no Python required)
# For integration tests: clojure -M:whisper:test --focus integration

clojure -M:test --skip integration --reporter kaocha.report/documentation
