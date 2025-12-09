#!/bin/sh

cd "$(dirname "$0")/.."

# Needs :test for test/ path (where clojure_mcp.clj lives)
# Needs :mcp for nrepl and clojure-mcp deps (local SNAPSHOT, not on Maven Central)
clojure -M:test:mcp
