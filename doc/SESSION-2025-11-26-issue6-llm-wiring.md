# Session Summary: Issue 6 - Dynamic Schema Generation & LLM Wiring

**Date:** 2025-11-26
**Status:** In progress - refactoring to reuse code-review-fsm's llm-action

## What Was Accomplished (Earlier)

### 1. Level 2: JSON-RPC Envelope Wrapping
- `mcp-cache->request-schema` now wraps each method in JSON-RPC envelope:
  ```json
  {"jsonrpc": {"const": "2.0"},
   "id": {"type": "integer"},
   "method": {"const": "tools/call"},
   "params": {...}}
  ```
- `mcp-cache->response-schema` wraps responses and notifications appropriately

### 2. Created `claij.env` Namespace
- Pure Clojure `.env` file loader (no dependencies)
- Supports `export VAR=value` bash format
- Merges with System/getenv (system takes priority)
- Keywordizes names: `OPENROUTER_API_KEY` → `:openrouter-api-key`
- Updated `claij.util/assert-env-var` to use it
- **Integration tests now pass** (was failing due to missing API key)

### 3. Updated Prompts
- FSM-level prompts explain MCP integration context
- State-level prompts for `llm` state provide detailed instructions:
  - How to make requests (CLAIJ envelope → JSON-RPC → MCP params)
  - Available operations (tools/call, resources/read, etc.)
  - How to complete task (transition to "end")

### 4. Test Results
- 149 assertions passing
- Integration test validates full flow with hardcoded llm-action

## Full Event Structure (3 layers)

```
CLAIJ envelope
  └── {"id": ["llm", "servicing"], "message": ...}
      └── JSON-RPC envelope  
          └── {"jsonrpc": "2.0", "id": 123, "method": "tools/call", "params": ...}
              └── MCP letter
                  └── {"name": "clojure_eval", "arguments": {"code": "(+ 1 1)"}}
```

## Session Continued: Reusing llm-action

### Key Discovery: Don't Reimplement llm-action

The original plan was to implement a new `llm-action` in `mcp-fsm.clj`. But:

1. **`code-review-fsm.clj` already has a working `llm-action`** that:
   - Calls `open-router-async` correctly
   - Passes `(handler context output)` with 2 args (correct!)
   - Uses `make-prompts` to build prompts from FSM/state/trail

2. **The FSM handler signature is `(handler context event)`** - 2 args minimum:
   ```clojure
   (fn handler
     ([new-context event] (handler new-context event initial-trail))
     ([new-context {ox-id "id" :as event} current-trail] ...))
   ```

3. **Older code had a bug** (now fixed): The multi-arity version called `(handler output)` with 1 arg, which would throw `ArityException`.

### Added Regression Test

Added `llm-action-handler-arity-test` to `code_review_fsm_test.clj` to ensure handler is called with 2 args. Test passes.

### Output Schema Clarification

**No need to pass schema to `open-router-async`!**

- The FSM handler already validates LLM responses against the output schema
- If validation fails, the FSM retries with error feedback
- Passing schema to `open-router-async` would be an optimization (structured output), not a requirement

### LLM Provider/Model Selection

- Currently defaults to `openai/gpt-4o` if no `"llm"` in input data
- In code-review-fsm, the MC gets defaults, then chooses LLM for reviewer
- Future: put provider/model in state definition for better control

## Next Steps: Move Shared Code to claij.fsm

Move these from `code-review-fsm.clj` to `claij.fsm`:
1. `make-prompts` - builds prompt messages from FSM config and trail
2. `llm-action` - FSM action that calls LLM
3. `llm-configs` - per-LLM configuration (prompts, etc.)

Then:
4. Update `code-review-fsm` to refer to them from `claij.fsm`
5. Update `mcp-fsm` to use the shared `llm-action`
6. Run tests

## Files Modified This Session

1. **src/claij/mcp.clj**
   - `mcp-cache->request-schema` - added JSON-RPC wrapping
   - `mcp-cache->response-schema` - added JSON-RPC wrapping

2. **src/claij/env.clj** (NEW)
   - `env` - merged map of .env + System/getenv
   - `getenv` - lookup by string or keyword

3. **src/claij/util.clj**
   - `assert-env-var` - now uses `claij.env/getenv`

4. **src/claij/fsm/mcp_fsm.clj**
   - Updated prompts at FSM and state level

5. **test/claij/mcp_test.clj**
   - Updated test data to include JSON-RPC envelope fields

6. **test/claij/fsm/code_review_fsm_test.clj**
   - Added `llm-action-handler-arity-test` regression test

## TODOs (Not Started)

1. **Use $ref to shared MCP schema** - Currently inline schemas duplicate definitions from `resources/mcp/schema.json`. Should use `$ref` for DRYness.

2. **Improve request ID handling** - Currently using `:mcp/request-id` counter in context. LLM needs to output unique ids.

3. **LLM selection in state** - Put provider/model in state definition rather than relying on input data or defaults.

## Key Files

- `src/claij/fsm.clj` - FSM engine (handler defined here)
- `src/claij/fsm/mcp_fsm.clj` - MCP FSM definition and actions
- `src/claij/fsm/code_review_fsm.clj` - Code review FSM with working llm-action
- `src/claij/llm/open_router.clj` - `open-router-async` function
- `test/claij/fsm/mcp_fsm_test.clj` - MCP FSM integration tests
- `test/claij/fsm/code_review_fsm_test.clj` - Code review FSM tests
