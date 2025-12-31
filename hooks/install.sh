#!/bin/bash
# Install git hooks from hooks/ directory

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOKS_DIR="$(git rev-parse --git-dir)/hooks"

echo "Installing git hooks..."

for hook in "$SCRIPT_DIR"/*; do
  if [[ -f "$hook" && "$(basename "$hook")" != "install.sh" ]]; then
    hook_name=$(basename "$hook")
    cp "$hook" "$HOOKS_DIR/$hook_name"
    chmod +x "$HOOKS_DIR/$hook_name"
    echo "  Installed: $hook_name"
  fi
done

echo "Done!"
