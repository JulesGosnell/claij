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

## FSM Composition (Issue 2)

**Option B (current direction):** Wrapper/Sub-FSM
- mcp-proxy state wraps mcp-fsm
- Stash trail → delegate → await → restore trail
- Clean separation, reusable

## FSM = Skill Insight
Enter state = Load skill
Perform actions = Use skill
Exit state = GC skill, keep summary
