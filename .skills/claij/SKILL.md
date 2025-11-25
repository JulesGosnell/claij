# CLAIJ Expertise Skill

## FSM Architecture
State machines as explicit data with schema-validated transitions

Actions signature: (fn [context fsm ix state trail handler] ...)
Context threads through all actions
Actions return updated context

## Destructuring ix for Multi-Entry States

When a state has multiple incoming transitions, destructure the transition id:
```clojure
(defn my-action [context fsm {id "id"} state [{[is {m "message"} os] "content"} :as trail] handler]
  (case id
    ["state-a" "my-state"] (handle-from-a ...)
    ["state-b" "my-state"] (handle-from-b ...)))
```

Or check message content when same transition serves multiple purposes.

## Context Threading (Issue 5 DONE)
Actions receive context, return updated context to handler
Context accumulates state (caches, counters, etc)

## MCP Schema Generation (Issue 6 - In Progress)

**Layered schema architecture:**
```
Level 3: CLAIJ Envelope    {claij-id, request}     <- TODO
Level 2: JSON-RPC Envelope {jsonrpc, id, method, params} <- TODO
Level 1: Combined Schema   mcp-cache->request-schema ✅
Level 0: Per-capability    tools-cache->request-schema, etc ✅
```

**Schema generation from cache (claij.mcp):**
```clojure
;; Per-capability
(tools-cache->request-schema tools)     ;; oneOf by tool name
(resources-cache->request-schema resources) ;; enum of URIs
(prompts-cache->request-schema prompts) ;; oneOf by prompt name

;; Combined - all operations
(mcp-cache->request-schema cache)  ;; oneOf: tools/call | resources/read | prompts/get | logging/setLevel
(mcp-cache->response-schema cache) ;; anyOf: all response types
```

**Key insight:** Schemas with descriptions ARE the interface spec. No need to pass raw cache to LLM - schemas are the single source of truth.

**FSM extension needed:**
- Dynamic schemas at transition time (cache changes)
- `$defs` for shared schema pieces (avoid prompt bloat)
- Support `schema-fn` or compose from context

## MCP Integration

**Working Flow:**
```
start → starting → shedding → initing → servicing → llm ←→ servicing → llm → end
```

**Tool Call Pattern:**
```clojure
(let [request-id (inc (:mcp/request-id context))
      tool-call {"jsonrpc" "2.0" "id" request-id
                 "method" "tools/call"
                 "params" {"name" "tool-name" "arguments" {...}}}]
  (handler (assoc context :mcp/request-id request-id)
           {"id" ["llm" "servicing"] "message" tool-call}))
```

Cache helpers in claij.mcp:
- initialize-mcp-cache
- invalidate-mcp-cache-item  
- refresh-mcp-cache-item
- merge-resources

**Future MCP capabilities (TODO in mcp.clj):**
- sampling - Server requests LLM call (high priority for multi-agent)
- completions - Argument autocompletion
- elicitation - Server requests user input

## FSM Composition (Issue 2)

**Option B (current direction):** Wrapper/Sub-FSM
- mcp-proxy state wraps mcp-fsm
- Stash trail → delegate → await → restore trail
- Clean separation, reusable

## FSM = Skill Insight
Enter state = Load skill
Perform actions = Use skill
Exit state = GC skill, keep summary
