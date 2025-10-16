#!/bin/bash

set -e

# Get the project root (parent of bin directory)
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_PATH=$(cd "$SCRIPT_DIR/.." && pwd)

# Add Go bin to PATH if needed
export PATH="$HOME/go/bin:$HOME/bin:$PATH"

exec mcp-language-server \
    --workspace "$PROJECT_PATH" \
    --lsp clojure-lsp
