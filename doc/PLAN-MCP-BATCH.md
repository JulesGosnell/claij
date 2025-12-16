# Plan: MCP Batch Support with ID Correlation

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

### Parallel vs Sequential Execution

**Decision**: Sequential by default, parallel opt-in via flag.

**Rationale**:
- Sequential is simpler to debug
- Some tools may have ordering dependencies (read before write)
- Parallel can be added later per-batch or per-FSM-state

```clojure
;; Default: sequential
(call-tools bridge calls)

;; Explicit parallel
(call-tools bridge calls {:parallel? true})
```

### ID Generation

**Decision**: UUIDs generated at call site, not in bridge.

**Rationale**:
- Caller can correlate their own data with call IDs
- Bridge stays stateless
- FSM trail can reference call IDs

---

## File Reorganization

### Current Structure
```
src/claij/
├── mcp.clj              # 293 lines - mixed concerns
├── mcp/
│   ├── bridge.clj       # Low-level stdio (keep)
│   ├── config.clj       # Empty placeholder (delete)
│   └── core.clj         # Old RocketChat code (delete)
```

### New Structure
```
src/claij/
├── mcp/
│   ├── bridge.clj       # Low-level transport (stdio/http)
│   ├── client.clj       # High-level API: call-tools, ping
│   ├── protocol.clj     # JSON-RPC message construction
│   ├── cache.clj        # Cache management & invalidation
│   └── schema.clj       # Malli schema generation from cache
```

---

## Implementation Phases

### Phase 1: Reorganize (No Behavior Change)

**Goal**: Split mcp.clj into focused modules, delete dead code.

1. **Create `mcp/protocol.clj`**
   - Extract: `initialise-request`, `initialised-notification`
   - Extract: `list-tools-request`, `list-prompts-request`, `list-resources-request`
   - Extract: `notification?`, `list-changed?`

2. **Create `mcp/cache.clj`**
   - Extract: `initialize-mcp-cache`, `invalidate-mcp-cache-item`, `refresh-mcp-cache-item`

3. **Create `mcp/schema.clj`**
   - Extract: `tool-cache->request-schema`, `tools-cache->request-schema`
   - Extract: `mcp-cache->request-schema`, `mcp-cache->response-schema`
   - Extract: `mcp-request-schema-fn`, `mcp-response-schema-fn`
   - Extract: `mcp-request-xition-schema-fn`, `mcp-response-xition-schema-fn`
   - Keep: `mcp-schemas`, `mcp-registry`

4. **Delete dead code**
   - `mcp/core.clj` (old RocketChat integration)
   - `mcp/config.clj` (empty)
   - `mcp.clj` (after extraction complete)

5. **Update imports** in `mcp_fsm.clj`

6. **Run tests** - ensure no regression

### Phase 2: Add ID Correlation to Bridge

**Goal**: Bridge tracks in-flight requests and correlates responses.

1. **Modify `bridge.clj`**

```clojure
(defn start-mcp-bridge 
  "Start MCP bridge. Returns map with:
   - :send-fn     (fn [requests] ...) - send batch, returns promise of responses
   - :close-fn    (fn [] ...) - shut down bridge
   - :pending     (atom {}) - in-flight requests by ID (for debugging)"
  [config]
  ...)
```

2. **Correlation mechanism**

```clojure
;; In bridge
(def pending (atom {}))  ;; {id -> {:request r :promise p :timestamp t}}

(defn send-batch [requests]
  (let [ids (map #(get % "id") requests)
        promises (into {} (map (fn [id] [id (promise)]) ids))]
    ;; Register all pending
    (swap! pending merge 
           (zipmap ids (map (fn [id r] {:request r :promise (promises id) :timestamp (now)}) 
                           ids requests)))
    ;; Send batch
    (>!! output-chan (if (= 1 (count requests))
                       (first requests)  ;; Single request, don't wrap
                       requests))         ;; Batch as array
    ;; Return map of promises
    promises))

;; Response handler (in read loop)
(defn handle-response [response]
  (let [id (get response "id")]
    (when-let [{:keys [promise]} (get @pending id)]
      (deliver promise response)
      (swap! pending dissoc id))))
```

3. **Timeout handling**

```clojure
(defn await-responses [promises timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (into {}
          (map (fn [[id p]]
                 [id (deref p (max 0 (- deadline (System/currentTimeMillis)))
                           {:error "timeout" :id id})])
               promises))))
```

### Phase 3: Create High-Level Client API

**Goal**: Clean API for tool execution.

1. **Create `mcp/client.clj`**

```clojure
(ns claij.mcp.client
  "High-level MCP client API."
  (:require [claij.mcp.bridge :as bridge]
            [claij.mcp.protocol :as proto]))

(defn call-tools
  "Execute batch of tool calls. Returns vector of results in request order.
   
   Options:
   - :timeout-ms  - per-batch timeout (default 30000)
   - :parallel?   - execute tools in parallel (default false, reserved for future)
   
   Input:
   [{\"id\" \"uuid-1\" \"name\" \"calc\" \"arguments\" {:op \"add\"}}
    {\"id\" \"uuid-2\" \"name\" \"read_file\" \"arguments\" {:path \"foo.clj\"}}]
   
   Output:
   [{\"call_id\" \"uuid-1\" \"success\" true \"result\" 42}
    {\"call_id\" \"uuid-2\" \"success\" false \"error\" \"File not found\"}]"
  [bridge tool-calls & [{:keys [timeout-ms] :or {timeout-ms 30000}}]]
  (let [;; Convert to JSON-RPC requests
        requests (mapv proto/tool-call->request tool-calls)
        ;; Send and await
        promises (bridge/send-batch bridge requests)
        responses (bridge/await-responses promises timeout-ms)]
    ;; Convert back to tool results, preserving order
    (mapv (fn [{id "id" :as call}]
            (let [resp (get responses id)]
              (proto/response->tool-result id resp)))
          tool-calls)))

(defn ping
  "Health check. Returns true if server responds within timeout."
  [bridge & [{:keys [timeout-ms] :or {timeout-ms 5000}}]]
  (let [id (str (random-uuid))
        request (proto/ping-request id)
        promises (bridge/send-batch bridge [request])
        responses (bridge/await-responses promises timeout-ms)]
    (not (contains? (get responses id) :error))))
```

2. **Protocol helpers in `mcp/protocol.clj`**

```clojure
(defn tool-call->request
  "Convert high-level tool call to JSON-RPC request."
  [{id "id" name "name" args "arguments"}]
  {"jsonrpc" "2.0"
   "id" id
   "method" "tools/call"
   "params" {"name" name "arguments" args}})

(defn response->tool-result
  "Convert JSON-RPC response to tool result."
  [call-id {:strs [result error] :as response}]
  (if error
    {"call_id" call-id "success" false "error" (get error "message")}
    {"call_id" call-id "success" true "result" result}))

(defn ping-request [id]
  {"jsonrpc" "2.0" "id" id "method" "ping"})
```

### Phase 4: Update FSM Integration

**Goal**: MCP FSM uses batched API.

1. **Modify `service-action`** in `mcp_fsm.clj`

Current (single tool):
```clojure
(>!! ic m)
(let [response (take! oc 2000 {})]
  ...)
```

New (batch):
```clojure
(let [tool-calls (get event "tool_calls")
      results (client/call-tools bridge tool-calls)]
  (handler context {"id" ["servicing" "llm"] 
                    "tool_results" results}))
```

2. **Update transition schemas**

```clojure
;; LLM → servicing (tool calls)
{"id" ["llm" "servicing"]
 "schema"
 [:map
  ["id" [:= ["llm" "servicing"]]]
  ["tool_calls" [:vector
                 [:map
                  ["id" :string]
                  ["name" :string]
                  ["arguments" :map]]]]]}

;; servicing → LLM (tool results)
{"id" ["servicing" "llm"]
 "schema"
 [:map
  ["id" [:= ["servicing" "llm"]]]
  ["tool_results" [:vector
                   [:map
                    ["call_id" :string]
                    ["success" :boolean]
                    ["result" {:optional true} :any]
                    ["error" {:optional true} :string]]]]]}
```

### Phase 5: Update Tests

1. **Unit tests** for each new module
2. **Integration tests** for batched tool calls
3. **Partial failure tests** (some tools succeed, some fail)
4. **Timeout tests**

---

## Schemas

### Tool Call (LLM output)
```clojure
(def ToolCall
  [:map
   ["id" :string]        ;; UUID, generated by caller
   ["name" :string]      ;; Tool name from MCP
   ["arguments" :map]])  ;; Tool-specific args
```

### Tool Result (returned to LLM)
```clojure
(def ToolResult
  [:map
   ["call_id" :string]
   ["success" :boolean]
   ["result" {:optional true} :any]
   ["error" {:optional true} :string]])
```

### Batch schemas
```clojure
(def ToolCallBatch
  [:map
   ["id" [:= "tool_calls"]]  ;; FSM transition ID
   ["calls" [:vector ToolCall]]])

(def ToolResultBatch
  [:map
   ["id" [:= "tool_results"]]
   ["results" [:vector ToolResult]]])
```

---

## Open Questions

1. **JSON-RPC Batch Format**: Should we send array (native batch) or individual messages? 
   - Batch: `[{req1}, {req2}]` - more efficient
   - Individual: `{req1}\n{req2}\n` - simpler, current approach
   - **Tentative**: Start with individual (newline-delimited), add batch later

2. **Stdio vs HTTP**: Current bridge is stdio only. HTTP transport would simplify batching.
   - **Decision**: Defer HTTP transport, stick with stdio for now

3. **Request ID Scope**: Per-session unique or globally unique?
   - **Decision**: UUIDs (globally unique) - simplest, no tracking needed

---

## Task List

- [ ] Phase 1: Create `mcp/protocol.clj` with message construction
- [ ] Phase 1: Create `mcp/cache.clj` with cache management  
- [ ] Phase 1: Create `mcp/schema.clj` with schema generation
- [ ] Phase 1: Delete `mcp/core.clj`, `mcp/config.clj`
- [ ] Phase 1: Delete `mcp.clj` (after all extractions)
- [ ] Phase 1: Update `mcp_fsm.clj` imports
- [ ] Phase 1: Run tests, verify no regression
- [ ] Phase 2: Add ID correlation to `bridge.clj`
- [ ] Phase 2: Add timeout handling
- [ ] Phase 2: Unit tests for correlation
- [ ] Phase 3: Create `mcp/client.clj` with `call-tools`, `ping`
- [ ] Phase 3: Add protocol helpers for message conversion
- [ ] Phase 3: Unit tests for client
- [ ] Phase 4: Update `service-action` for batched API
- [ ] Phase 4: Update FSM transition schemas
- [ ] Phase 4: Integration tests with batches
- [ ] Phase 5: Partial failure tests
- [ ] Phase 5: Timeout tests
- [ ] Update story #53 with implementation notes

---

## Related Issues

- #52 Epic: MCP Integration
- #53 Story: Batched MCP tool execution
- #54 Story: Sub-FSM for tool execution
- #56 Story: MCP ping/health check
- #57 Story: MCP connection interruption handling
