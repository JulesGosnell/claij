# Session 2025-11-27: MCP FSM Integration Test Fixes

## Context

The `mcp-fsm-tool-call-test` integration test is failing. After debugging, we found:

1. The FSM infrastructure works - it gets through start → shedding → initing → servicing ↔ caching loop → llm
2. The LLM (`gpt-4o-mini`) returns garbage - it outputs MCP protocol responses instead of valid FSM transitions
3. The LLM is confused because the trail contains too much MCP protocol noise

## TODO - Three Fixes Required

### 1. Stop eager loading of resource bodies in cache-action
**Status:** DONE ✓

Already fixed - the `check-cache-and-continue` function no longer has "Hole 2" that eagerly fetched resource bodies. The `merge-resources` import has also been removed.

Resource bodies are now loaded lazily when the LLM requests them via the `["llm" "servicing"]` transition with a `resources/read` message.

### 2. Filter trail data sent to LLM
**Status:** TODO

The trail currently contains all MCP protocol messages (initialize, notifications, list responses, etc.). The LLM is mimicking these instead of following the FSM transition schema.

Need to ensure only relevant data from the trail is sent to the LLM - probably just the document and the output schema, not the full MCP protocol history.

### 3. Fix ./bin/integration-test.sh to run ALL integration tests
**Status:** TODO

Currently `./bin/integration-test.sh` only runs the memory-demo integration tests. It should also run the `^:integration` tagged tests in the test suite (like `mcp-fsm-tool-call-test`).

## Debugging Notes

### LLM Output Issue
The LLM returns:
```json
{"jsonrpc" "2.0", "id" 1, "result" {"resources" [...]}}
```

But should return:
```json
{"id": ["llm", "servicing"], "message": {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {...}}}
```

### Key Files
- `src/claij/fsm/mcp_fsm.clj` - FSM definition and actions
- `src/claij/fsm.clj` - FSM engine, `llm-action`, `make-prompts`
- `src/claij/mcp.clj` - MCP schema generation functions
- `test/claij/fsm/mcp_fsm_test.clj` - Integration tests

## Fix Applied: omit=true Now Works for Both Input AND Output Transitions

### Problem
Transitions marked with `"omit" true` were still appearing in the trail sent to the LLM. The LLM was getting confused by seeing MCP protocol messages (like `["servicing" "caching"]`) that should have been hidden.

### Root Cause
In `xform`, the code only checked if the **output** transition (`ox`) had `omit=true`. It didn't check the **input** transition (`ix`).

When transitioning through:
```
servicing --[servicing→caching]--> caching --[caching→llm]--> llm
```

The input transition `["servicing" "caching"]` has `omit=true`, but the output `["caching" "llm"]` doesn't. So the trail entry was added even though the input side should be hidden.

### Fix (in fsm.clj xform function)
1. Extract `ix-omit?` from the input transition in the function signature
2. Rename `omit?` to `ox-omit?` when extracting from the output transition  
3. Combine both: `omit? (or ix-omit? ox-omit?)`

Now if EITHER the input or output transition has `omit=true`, the pair is excluded from the trail.

### Lines Changed
- Line 244: Added `ix-omit? "omit"` to `ix` destructuring
- Line 260: Renamed `omit?` to `ox-omit?` in `ox` destructuring
- Line 275-276: Added combined `omit?` binding

## IMPORTANT: Do NOT Use Structured Output API

OpenAI's structured output API (`response_format: json_schema`) has restrictions that are incompatible with our schema structure:
- Requires root schema to be `{"type": "object", ...}`
- Our schema uses `{"oneOf": [...]}` at the root

**DO NOT CHANGE BACK TO STRUCTURED OUTPUT** - the restrictions are too limiting for our FSM schemas.

We use the schema for validation on our side, not for LLM enforcement.

## TODO: Investigate Cheaper/Free LLMs for MCP

MCP involves large schemas (tool definitions, resources, prompts) which consume many tokens. Need to find a cost-effective LLM for MCP testing/development.

### Candidates to Investigate:
- **Gemini Flash** - Already using, relatively cheap, fast
- **Gemini 1.5 Flash 8B** - Even cheaper variant
- **Mistral** - Open weights, various sizes
- **DeepSeek** - Very cheap, good at structured output
- **Qwen** - Free tier available
- **Groq** - Fast inference, free tier
- **Together.ai** - Free credits, many open models
- **Local models** (Ollama):
  - Llama 3.1 8B/70B
  - Mistral 7B
  - Qwen2 7B
  - Could be free if running locally

### Requirements for MCP LLM:
1. Good JSON output (structured responses)
2. Handles large context (schemas can be 10k+ tokens)
3. Follows schema constraints reasonably well
4. Cheap or free for development/testing

### Action Items:
- [ ] Benchmark different models on MCP tool-call task
- [ ] Compare cost per 1M tokens across providers
- [ ] Test local Ollama models for development
- [ ] Consider model-specific prompting adjustments

---

## Trail Redesign: Audit Log + trail->prompts

### Design Tension Identified

The current trail structure is trying to serve two purposes and doing both badly:

1. **LLM conversation context** - giving the LLM history so it can make good decisions
2. **FSM execution audit/history** - recording what happened for debugging, replay, analysis

### Chosen Solution: Option (1)

Keep trail as a **pure audit log** with minimal structure. When we need to call an LLM, use a `trail->prompts` function to derive the LLM conversation format.

**Benefits:**
- `trail->prompts` is easily tested and maintained in a single place
- Less data duplication in the pipeline
- Simpler logging and REPL inspection
- Performance is not a concern for this app
- Can always migrate to separate data structures later if needed

### Trail Entry Structure

**Successful traversal:**
```clojure
{:fsm-id "..."
 :fsm-version 1
 :from "A"
 :to "B"
 :event {...}}
;; future: :timestamp
```

**Failure entry:**
```clojure
{:fsm-id "..."
 :fsm-version 1
 :from "A"
 ;; :to absent or nil - indicates failure
 :event {...}
 :failure {:type :validation  ;; or :json-parse, etc.
           :errors [...]
           :attempt 2}}
;; future: :timestamp
```

**Identifying success vs failure:** Entry has `:to` = success, no `:to` (or nil) = failure.

### Trail Structure

- **Vector** (not linked list) - `conj`-ing new entries
- **First→last order** - more intuitive to read
- Old structure was linked list (cons) for performant head access, but event now travels as separate parameter

### What's NOT Stored in Trail

- **Output schema (s-schema / oneOf):** Computed by `trail->prompts` by looking at destination state's outgoing xitions
- **omit flag:** Looked up from FSM xition definition, not duplicated in trail
- **Role (user/assistant):** Determined by `trail->prompts` looking at state's action type

**Rationale:** Keep trail minimal for debugging. Everything else can be derived from FSM definition.

### `trail->prompts` Function

**Signature:** `(trail->prompts fsm trail output-schema)`

**Responsibilities:**
- Filter out entries where xition has `omit=true` (looked up in FSM)
- Determine role ("user" or "assistant") based on whether from-state's action is "llm"
- Format as `[input-schema, input-doc, output-schema]` triples
- Handle failure entries appropriately (for retry feedback to LLM)

**Where schemas come from:**
- **Input schema:** Look up xition `[:from :to]` in FSM, get its `"schema"`
- **Output schema:** Passed in as parameter (the oneOf computed by caller for current state)

### Action Signature

Stays the same: `(context fsm ix state event trail handler)`

Trail structure changes internally, actions use it as they see fit.

### Retry/Failure Handling

Retries are managed by FSM code (handler + retrier in `xform`), not actions. Flow:
1. `xform` calls action with handler
2. Action does work, calls `handler(context, output-event)`
3. Handler validates output
4. On failure: conj failure entry to trail, call action again
5. On success: conj success entry to trail, put on output channel

### Versioning Consideration

Trail entries include `:fsm-id` and `:fsm-version` to handle:
- Historical audit trails remaining comprehensible after FSM changes
- Future dream of rewriting FSM during traversal

FSM ID doesn't need to be in every entry if trail is stored alongside FSM metadata.

### Implementation Notes

- TODO: Add `:timestamp` to entries in future (complicates unit testing)
- Currently two entries added per transition (user + assistant) - changing to ONE entry per xition
- Only add entry when `omit=true` is NOT set on the xition

### Questions Resolved

1. **omit flag:** Look up in FSM, don't store in trail
2. **Retry handling:** FSM manages retries in handler, failure entries go in trail
3. **Action signature:** Unchanged
4. **trail->prompts:** Needs FSM + trail + output-schema
