# Session: Trail Refactor & MCP Integration Testing
**Date:** 2025-11-28
**Focus:** Trail vector refactoring completion, MCP FSM integration testing, multi-LLM validation

## Completed Work

### 1. Trail Refactoring (Steps 2a & 2b)

**COMPLETED** - Changed trail from linked-list to vector format with audit-style entries.

#### Step 2a: Removed Event Duplication
Changed trail entry format from `{:from :to :input-event :output-event}` to `{:from :to :event}`.

**Key insight:** Entry records the OUTPUT transition (where the event goes), not the input transition. When handler validates output-event against ox-schema for transition [from, to], we record:
- `:from` and `:to` from ox-id (the output transition ID)
- `:event` is the output-event that validated against that transition's schema

**Critical bug fixed:** Was checking `ix-omit?` (input transition) when deciding whether to record. Changed to check `ox-omit?` (output transition). This is correct because omit on transition X means "don't record X", regardless of how we arrived at the state producing X.

#### Step 2b: Fixed Role Assignment
Fixed `trail->prompts` to assign role based on what PRODUCED the event (from-state's action), not where it's going:
- If from-state has `action="llm"` → `role="assistant"` (LLM produced this)
- Otherwise → `role="user"` (request to LLM from human/service/etc)

### 2. MCP FSM Integration Testing

**Test:** `mcp-fsm-tool-call-test` - Full round-trip with real LLM calling tools.

#### Prompt Improvements
Enhanced `make-prompts` system message with explicit guidance:
```
CRITICAL - The "id" field is a UNIQUE DISCRIMINATOR that determines routing:
- The "id" MUST be an array of exactly two strings, e.g. ["llm", "servicing"] or ["llm", "end"]
- The "id" value MUST match EXACTLY one of the const values specified in the OUTPUT-SCHEMA options

IMPORTANT: The OUTPUT-SCHEMA is a oneOf with multiple options. Choose based on what you need to do:
- To call a tool/service: use "id": ["llm", "servicing"] and include the JSON-RPC tool call in "message"
- To return a final answer: use "id": ["llm", "end"] and put your result in "result"
You MUST call tools via the servicing route - do NOT embed tool calls in the end result.
```

#### Schema Constraint Tightening
Fixed `["llm", "end"]` transition schema - was too loose (`"result" {"type" "object"}`).
Tightened to MCP content format:
```clojure
"result" {"type" "object"
          "properties"
          {"content" {"type" "array"
                      "items" {"type" "object"
                               "properties"
                               {"type" {"type" "string" "enum" ["text" "image" "resource"]}
                                "text" {"type" "string"}}
                               "required" ["type" "text"]}}
           "isError" {"type" "boolean"}}
          "required" ["content"]}
```

### 3. Multi-LLM Validation Results

| Model | Result | Retries | Speed | Cost | Notes |
|-------|--------|---------|-------|------|-------|
| **Gemini 2.5 Flash** | ✅ Pass | 0-1 | Fast | Free tier | **BEST CHOICE** - reliable tool use |
| Claude Opus 4.5 | ✅ Pass | 1 | Medium | $$$ | Works great |
| GPT-5.1 | ✅ Pass | 1 | Medium | $$ | Works great |
| Llama 4 Maverick | ✅ Pass | 3 | Slow | $ | Messy JSON but honest |
| Grok 4.1 Fast | ❌ FAIL | - | - | - | Refuses to use tools |
| Kimi K2 (free) | ❌ FAIL | 0 | Fast | Free | Fabricates answers |
| Qwen 2.5 7B | ❌ FAIL | 3 | - | Free | Context overflow + wrong format |

**Key findings:**
- **Best free option:** Gemini 2.5 Flash - fast, clean, works first try
- **Cheaters:** Kimi K2, Grok - will fabricate answers rather than use tools
- **Too small:** Qwen 7B - context/capability limits (schema ~54K tokens)
- **Hostname test** proved which models actually call tools vs fabricate

**Default LLM set to:** `google/gemini-2.5-flash`

### 4. Files Modified

- `/home/jules/src/claij/src/claij/fsm.clj`
  - `xform`: Records output transition with single `:event`
  - `last-event`: Simplified to `(:event (peek trail))`
  - `trail->prompts`: Role based on from-state's action
  - `make-prompts`: Enhanced system prompt with discriminator guidance
  - `llm-action`: Default changed to `google/gemini-2.5-flash`, reduced logging

- `/home/jules/src/claij/src/claij/llm/open_router.clj`
  - Reduced verbose debug logging (removed println statements)

- `/home/jules/src/claij/src/claij/fsm/mcp_fsm.clj`
  - `["llm", "end"]` schema: Tightened result to require MCP content format

- `/home/jules/src/claij/test/claij/fsm_test.clj`
  - `trail->prompts-test`: Updated for audit format and role logic

- `/home/jules/src/claij/test/claij/fsm/omit_test.clj`
  - `trail-contains-event-id?`: Updated for single `:event` field

- `/home/jules/src/claij/test/claij/fsm/mcp_fsm_test.clj`
  - Test now uses hostname command (unfakeable by LLMs)

## Outstanding Work / TODOs

### Integration Testing Strategy (HIGH PRIORITY)
1. **Get ALL integration tests running from `./bin/integration-test.sh`**
   - Currently tests are scattered
   - Need a clear, organized integration testing strategy
   - Current approach is "a bit blunderbuss"

2. **Test categories to organize:**
   - Unit tests (fast, no external deps)
   - Integration tests (MCP, LLM calls) - marked with `^:integration`
   - End-to-end FSM tests

3. **Current integration test locations:**
   - `test/claij/fsm/mcp_fsm_test.clj` - MCP FSM integration (now passing)
   - Memory demo tests (run by current script)
   - Need to consolidate and document

### From Previous Session (2025-11-27)
Reviewed SESSION-2025-11-27-mcp-fsm-fixes.md - most items DONE:
- ✅ Stop eager loading of resource bodies in cache-action
- ✅ Filter trail data sent to LLM (fixed via trail refactor)
- ✅ LLM confusion fixed (prompts now guide schema-conformant output)
- ✅ Omit flag fixes (now checks output transition correctly)
- ✅ LLM benchmarking (Gemini Flash winner)
- ❌ TODO: Fix `./bin/integration-test.sh` to run ALL `^:integration` tests

### Refactoring Strategy Skill (CAPTURE FOR FUTURE)
**Key learning from this session:** Breaking large refactors into bite-sized pieces works extremely well.

The trail refactor seemed overwhelming initially but succeeded by:
1. Identifying logical sub-steps (2a: remove duplication, 2b: fix roles)
2. Updating components one at a time
3. Running tests after each change
4. Using the test suite as safety net

**Future vision:** Translate this skill into FSMs so multiple LLMs (Grok for review, Gemini/Claude/GPT for execution) can work overnight to:
- Divide refactoring into tiny pieces
- Execute changes incrementally  
- Rebuild with cleaner code
- Achieve 100% test coverage

**Where to capture this skill:** Consider creating `/home/jules/src/claij/doc/SKILL-incremental-refactoring.md` that can later be translated into an FSM for automated refactoring workflows.

## Test Status
- All 93 unit tests passing with 468 assertions
- MCP FSM integration test passing with hostname validation
- Default LLM (Gemini Flash) confirmed working

## Notes for Next Session
1. Review this handoff and previous session file for backlog items
2. Consolidate integration test strategy
3. Consider creating SKILL.md for "incremental refactoring" approach
4. SVG diagram is up-to-date (llm↔servicing loop visible)

## Future Work: MCP Protocol Expansion

### Missing Protocol Pieces
- **Interrupts/cancellation** - see design below
- **Sampling** - MCP servers requesting completions from the client
- **Roots** - filesystem root declarations
- **Logging** - server-to-client log messages
- **Progress notifications** - for long-running operations

### Other MCP Services to Test
- filesystem server (read/write files)
- git server
- postgres/sqlite servers
- puppeteer/browser automation
- fetch (HTTP requests)
- memory (persistent key-value)

### State-Level Prompts
The `mcp-fsm` has a top-level prompt but no state-level prompts. The `llm` state in particular could benefit from prompts explaining what the LLM should do (use tools via servicing, return final answer via end, etc.).

## Interrupt Design (READY TO IMPLEMENT)

### Problem
Interruption needs to reach across execution boundaries - can't pass a new context to code already running with the old one. Need mutable state, but keep it minimal and scoped.

### Design: Single Atom with Pair

```clojure
;; Context gets this at creation
{:interrupt (atom {:interrupted? false :hook identity})}

;; Actions register cleanup by composing onto the hook
(swap! (:interrupt context) update :hook comp my-cleanup-fn)

;; Interrupt function - atomic swap returns new value with hook
(defn interrupt [context]
  (let [{:keys [hook]} (swap! (:interrupt context) assoc :interrupted? true)]
    (hook)))

;; Transitions check early
(when (:interrupted? @(:interrupt context))
  (handler {:id [from "interrupted"]}))
```

### Why Single Atom
Using one atom holding `{:interrupted? :hook}` instead of two atoms ensures atomic updates - no race condition window where one is updated and the other isn't.

### Behavior
- Each action that starts something interruptible (HTTP request, MCP call, subprocess) adds its cleanup to `:hook` via `comp`
- `interrupt` atomically sets the flag and calls the composed hook
- Transitions check `:interrupted?` and route to `interrupted` terminal state
- FSM always completes a proper transition (clean trail record)
- The trail shows: `{:from "llm" :to "interrupted" :event {...}}`

### Next Steps
1. Investigate what the interrupt hooks look like for:
   - HTTP requests (open-router async calls)
   - MCP subprocess communication
2. Implement the interrupt atom in context creation
3. Add interrupt checks to transition machinery
4. Test with MCP FSM
