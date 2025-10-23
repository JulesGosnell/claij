#!/bin/sh

cd "$(dirname "$0")/.."

clojure -M:llms "$@"
