# Plan: MCP Batch Support with ID Correlation

## Status: ✅ IMPLEMENTED (2025-12-16)

See GitHub Issue #53 for progress tracking.

## Summary

Upgrade CLAIJ's MCP integration to support batched tool calls with proper JSON-RPC ID correlation, enabling parallel execution both within tool batches and across parallel FSM states.

## Why Full ID Correlation?

1. **MCP Spec Requires It**: "Response objects MAY be returned in any order. Client SHOULD match based on id member."
2. **Parallel FSM States**: Even single-tool calls need correlation when multiple FSM states run concurrently
3. **Future-Proof**: Parallel code review (MC runs multiple reviewers simultaneously) requires this

## Design Decisions

### Batch-Only API

**Decision**: Single public API that accepts batches. Singleton calls become `[single-call]`.

**Rationale**: 
- Small overhead (wrapping one item) vs. maintaining two code paths
- Consistency - all code follows same pattern
- We're not processing 10,000s of requests where this matters

### Pass-Through JSON-RPC Format

**Decision**: Client preserves exact JSON-RPC format that LLMs produce/expect.

**Rationale**:
- No format transformation means no translation errors
- LLMs already know JSON-RPC format
- Client only handles correlation and timeout

### ID Generation

**Decision**: Caller provides IDs in JSON-RPC format. Bridge handles correlation.

**Rationale**:
- LLMs generate their own IDs
- Bridge stays focused on correlation
- FSM trail can reference call IDs

---

## Final File Structure

```
src/claij/
├── mcp/
│   ├── bridge.clj       # Low-level transport + ID correlation (127 lines added)
│   ├── client.clj       # High-level: call-batch, call-one, ping (87 lines)
│   ├── protocol.clj     # JSON-RPC message construction
│   ├── cache.clj        # Cache management & invalidation
│   └── schema.clj       # Malli schema generation (batch support added)
```

---

## Implementation Notes

### Phase 1: File Reorganization ✅

Split `mcp.clj` (293 lines) into focused modules. Net change: -456 lines.

- Created `mcp/protocol.clj` - message construction, notification detection
- Created `mcp/cache.clj` - cache initialization, invalidation, refresh
- Created `mcp/schema.clj` - dynamic schema generation from MCP cache
- Deleted `mcp/core.clj` (old RocketChat code)
- Deleted `mcp/config.clj` (empty placeholder)
- Deleted `mcp.clj` (root file)

### Phase 2: ID Correlation ✅

Added to `bridge.clj`:

```clojure
;; Core correlation layer
(create-correlated-bridge config)  ;; Returns bridge with :pending atom
(send-request bridge request)      ;; Returns promise
(send-batch bridge requests)       ;; Returns {id -> promise}
(await-response promise id ms)     ;; Returns response or timeout error
(await-responses promises ms)      ;; Returns {id -> response}
(send-and-await bridge reqs ms)    ;; Convenience: send + wait
(pending-count bridge)             ;; Housekeeping
(clear-stale-requests bridge ms)   ;; Cleanup old requests
```

Key implementation details:
- Promises for async response delivery
- Atom tracks pending requests: `{id -> {:promise p :request r :timestamp t}}`
- go-loop correlates responses by ID
- Unmatched messages (notifications) go to `:output-chan`
- Shared deadline for batch timeouts

### Phase 3: Client API ✅

Created `mcp/client.clj` - thin wrapper preserving JSON-RPC format:

```clojure
;; Execute batch of JSON-RPC requests
(call-batch bridge
  [{"jsonrpc" "2.0" "id" 1 "method" "tools/call" "params" {...}}
   {"jsonrpc" "2.0" "id" 2 "method" "tools/call" "params" {...}}]
  {:timeout-ms 5000})
;; => [{"jsonrpc" "2.0" "id" 1 "result" {...}}
;;     {"jsonrpc" "2.0" "id" 2 "result" {...}}]

;; Single request convenience
(call-one bridge request opts)

;; Health check
(ping bridge {:timeout-ms 5000})  ;; => true/false

;; Fire-and-forget notification
(send-notification bridge "notifications/initialized" {})
```

### Phase 4: FSM Integration ✅

Updated `mcp_fsm.clj`:

- `start-action`: Uses `create-correlated-bridge` instead of raw channels
- `shed-action`: Uses `send-and-wait` helper
- `service-action`: Routes LLM requests through `client/call-batch`
  - Single request (map) → single response (map)
  - Batch request (vector) → batch response (vector)
- Added helpers: `drain-notifications`, `send-and-wait`
- Removed `hack` function (correlation handles notification shedding)

Updated `schema.clj`:
- `mcp-request-xition-schema-fn`: Supports single OR batch requests
- `mcp-response-xition-schema-fn`: Supports single OR batch responses

### Test Coverage

155 tests, 1118 assertions, 0 failures.

New tests:
- `bridge_test.clj`: ID correlation, timeout, batch tracking, stale request cleanup
- `client_test.clj`: Format preservation, batch handling, ping, notifications
- `mcp_fsm_test.clj`: Updated action tests, batch request/response tests

---

## API Summary

### For FSM Integration

```clojure
;; service-action handles both formats:

;; Single request → single response
{"message" {"jsonrpc" "2.0" "id" 1 "method" "tools/call" ...}}
;; → {"message" {"jsonrpc" "2.0" "id" 1 "result" {...}}}

;; Batch request → batch response
{"message" [{"jsonrpc" "2.0" "id" 1 ...} {"jsonrpc" "2.0" "id" 2 ...}]}
;; → {"message" [{"jsonrpc" "2.0" "id" 1 "result" {...}} 
;;               {"jsonrpc" "2.0" "id" 2 "result" {...}}]}
```

### For Direct Use

```clojure
(require '[claij.mcp.bridge :as bridge]
         '[claij.mcp.client :as client])

;; Create bridge
(def b (bridge/create-correlated-bridge 
         {"command" "node" "args" ["mcp-server.js"]}))

;; Call tools
(client/call-batch b
  [{"jsonrpc" "2.0" "id" "a" "method" "tools/call" 
    "params" {"name" "bash" "arguments" {"command" "ls"}}}]
  {:timeout-ms 10000})

;; Health check
(client/ping b {:timeout-ms 5000})

;; Stop bridge
((:stop b))
```

---

## Commits

```
2412cdc #53 [Phase 3]: Create high-level client API
a95445b #53 [Phase 2]: Add ID correlation to bridge
60bbd1d #53 [fix]: Fix test imports and list-changed? signature
a4b6032 #53 [Phase 1]: Reorganize MCP code into focused modules
b4cded9 #53 [Phase 4]: Update FSM for batched tool execution
```

---

## Related Issues

- #52 Epic: MCP Integration
- #53 Story: Batched MCP tool execution ← This implementation
- #54 Story: Sub-FSM for tool execution (depends on this)
- #55 Demo: Code review FSM with file access (depends on this)
- #56 Story: MCP ping/health check (✅ included in Phase 3)
- #57 Story: MCP connection interruption handling
