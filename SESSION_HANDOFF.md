# Session Handoff

## Current Work: Trail Redesign

**See:** [doc/SESSION-2025-11-27-mcp-fsm-fixes.md](doc/SESSION-2025-11-27-mcp-fsm-fixes.md)

### Summary

The trail structure is being redesigned to separate two concerns:
1. **Audit log** - what actually happened (for debugging/replay)
2. **LLM prompts** - what the LLM needs to see (for conversation context)

**Solution:** Trail becomes a pure audit log (vector of traversal entries). When calling LLM, use `trail->prompts` to derive conversation format.

### Trail Entry Structure

**Success:** `{:fsm-id :fsm-version :from :to :event}`
**Failure:** `{:fsm-id :fsm-version :from :event :failure {...}}`

### Next Implementation Steps

1. Change trail from linked-list to vector
2. Change trail entries to new audit format
3. Implement `trail->prompts` function
4. Update tests

**Read the full design in:** `doc/SESSION-2025-11-27-mcp-fsm-fixes.md` (section: "Trail Redesign: Audit Log + trail->prompts")

---

# Previous Session: Issue 6 Schema Generation Complete

## What Was Accomplished This Session

### Issue 6: MCP Schema Generation ✅

**Goal:** Generate JSON Schemas from MCP cache for LLM-guided structured output.

**Completed layers:**

#### Layer 0: Per-capability schemas
| Capability | Request Schema | Response Schema | Tests |
|------------|---------------|-----------------|-------|
| Tools | `tool-cache->request-schema`, `tools-cache->request-schema` (oneOf) | `tool-cache->response-schema`, `tools-cache->response-schema` (anyOf) | 22 |
| Resources | `resources-cache->request-schema` (enum of URIs) | `resource-response-schema` (constant) | 8 |
| Prompts | `prompt-cache->request-schema`, `prompts-cache->request-schema` (oneOf) | `prompt-response-schema` (constant) | 13 |
| Logging | `logging-set-level-request-schema`, `logging-notification-schema` (constants) | - | 10 |

#### Layer 1: Combined MCP schemas
- `mcp-cache->request-schema` - generates oneOf covering all available operations (tools/call, resources/read, prompts/get, logging/setLevel)
- `mcp-cache->response-schema` - generates anyOf covering all response types
- Tests: 11 assertions (full/empty/partial caches, method discrimination)

**Key design decisions:**
- Requests use `oneOf` (discriminated by method const + name/uri const)
- Tool responses use `anyOf` (allows duplicates when no outputSchema)
- Descriptions preserved (not stripped) - they guide LLM output
- Dynamic generation from cache state, not static schemas

**Removed:** `strip-descriptions` function - descriptions are valuable for LLM guidance

**Total: 80 assertions in mcp-test** (was 38 at session start)

### TODO Added for Future Capabilities
See end of `src/claij/mcp.clj` - documented but not implemented:
- **sampling** (high priority) - Server requests LLM call from client
- **completions** - Autocompletion for arguments
- **elicitation** - Server requests user input
- **progress** - Progress notifications
- **roots** - Filesystem roots
- **subscriptions** - Resource update subscriptions

## Current State

**All Tests Pass:** ✅ 401 assertions total

**Schema generation exports from `claij.mcp`:**
```clojure
;; Per-tool
tool-response-schema
tool-cache->request-schema
tool-cache->response-schema
tools-cache->request-schema
tools-cache->response-schema

;; Per-resource
resource-response-schema
resources-cache->request-schema

;; Per-prompt
prompt-response-schema
prompt-cache->request-schema
prompts-cache->request-schema

;; Logging
logging-levels
logging-set-level-request-schema
logging-notification-schema

;; Combined (Level 1)
mcp-cache->request-schema
mcp-cache->response-schema
```

## Next Steps (Not Started)

### Level 2: JSON-RPC Envelope
Wrap method+params with JSON-RPC structure:
```clojure
{"jsonrpc" {"const" "2.0"}
 "id" <request-id-schema>
 "method" ...
 "params" ...}
```

### Level 3: CLAIJ Envelope
Add routing to specific MCP server:
```clojure
{"claij-id" {"enum" ["emacs" "claij-clojure-tools" ...]}
 "request" <json-rpc-envelope>}
```

### FSM Integration (The Real Goal)
**Problem:** FSM transition schemas are static, but MCP schemas are dynamic (change when cache updates).

**Extension needed:**
1. Support `"schema-fn"` or similar for dynamic schema generation
2. Use `$defs` at FSM level for shared schema pieces (avoid prompt bloat)

**Proposed structure:**
```clojure
{"$defs" {"tool-response" tool-response-schema        ;; static, define once
          "mcp-request" (mcp-cache->request-schema cache)}  ;; dynamic
 "states" [...]
 "xitions" [...
   {"id" ["llm" "servicing"]
    "schema" {"$ref" "#/$defs/mcp-request"}}]}  ;; reference, not inline
```

### Key Insight: Cache Abstraction May Be Unnecessary
Now that schemas preserve descriptions, the schema IS the complete interface spec:
- Tool/prompt/resource names
- Descriptions (LLM guidance)
- Argument schemas with descriptions
- Constraints (enums, required, types)

**Simplified flow:**
```
list-tools response arrives
    ↓
generate request/response schemas immediately
    ↓
store schemas in context (not raw cache)
    ↓
LLM call uses schemas directly
```

On `list_changed`: re-fetch → regenerate schemas → replace in context

No need to pass cache to LLM - schemas are the single source of truth.

## Key Files Modified This Session

- `src/claij/mcp.clj` - All schema generation functions
- `test/claij/mcp_test.clj` - 80 assertions for schema generation
- `SESSION_HANDOFF.md` - This file

## Code Pattern Reference

**Generate combined request schema from cache:**
```clojure
(mcp-cache->request-schema 
  {"tools" [{:name "eval" :inputSchema {...}}]
   "resources" [{:uri "custom://readme"}]
   "prompts" [{:name "system-prompt" :arguments []}]})
;; => {"oneOf" [{:method "tools/call" ...}
;;              {:method "resources/read" ...}
;;              {:method "prompts/get" ...}
;;              {:method "logging/setLevel" ...}]}
```

**Per-tool request schema:**
```clojure
(tool-cache->request-schema
  {"name" "clojure_eval"
   "description" "Evaluate Clojure code"
   "inputSchema" {"type" "object"
                  "properties" {"code" {"type" "string"
                                        "description" "The code to evaluate"}}
                  "required" ["code"]}})
;; => {"type" "object"
;;     "properties" {"name" {"const" "clojure_eval"}
;;                   "arguments" {...inputSchema with descriptions...}}
;;     "required" ["name" "arguments"]}
```

## To Continue Next Session

```
Continue CLAIJ Issue 6: Schema generation complete through Level 1.

Completed:
- Per-capability schemas (tools, resources, prompts, logging) 
- Combined mcp-cache->request-schema and mcp-cache->response-schema
- 80 assertions, all passing

Next steps:
1. Level 2: JSON-RPC envelope wrapping
2. Level 3: CLAIJ envelope (claij-id routing)
3. FSM integration - dynamic schemas in transitions

Key insight from last session: Cache abstraction may be unnecessary. 
Schemas with descriptions ARE the complete interface spec. Consider 
storing pre-computed schemas in context instead of raw cache.

FSM extension needed: Support dynamic schema generation at transition 
time, plus $defs for shared schema pieces to avoid prompt bloat.

Read SESSION_HANDOFF.md and src/claij/mcp.clj for full context.
```

---

Ready for Levels 2-3 and FSM integration! 🚀
