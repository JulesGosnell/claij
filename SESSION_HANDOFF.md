# Session Handoff

## Current Work: Malli POC - Migrating from JSON Schema to Malli

### Motivation

Investigating replacing JSON Schema + JSON with Malli + EDN as CLAIJ's core schema/data format.

**Why Malli?**
- **Token efficiency** - Malli is dramatically terser than JSON Schema
- **DSL dream** - Malli can describe function signatures (`[:=> [:cat :string :int] :boolean]`), enabling LLMs to output Clojure forms for direct REPL evaluation
- **Homoiconicity** - Schema IS Clojure data, no JSON↔EDN translation layer
- **Simpler stack** - Drop m3 dependency, use battle-tested Malli

**The risk:** LLMs trained on mountains of JSON Schema, Malli is niche. Will they grok it?

### POC Structure

**File:** `test/claij/malli_poc_test.clj`

**PoC 1: Basic Malli Schema Understanding** ✅ PASSING
- Feed LLM a simple example in CLAIJ's 3-tuple format: `[input-schema, input-doc, output-schema]`
- LLM must return valid EDN conforming to output schema
- Tests the core pattern: LLM chooses FSM transition by outputting schema-conformant data

**PoC 2: FSM Self-Modification** 🚧 IN PROGRESS
- Input: instruction text + current FSM definition (in Malli)
- LLM must modify the FSM according to instructions
- This is the heart of the self-improving FSM vision

### PoC 1 Results (All Passing)

Tested 2024-12-03, all 5 providers successfully:
- ✅ anthropic/claude-sonnet-4
- ✅ google/gemini-2.5-flash  
- ✅ openai/gpt-4o
- ✅ x-ai/grok-3-beta
- ✅ meta-llama/llama-4-scout

All providers:
- Parsed and understood Malli schema syntax
- Returned valid EDN (not JSON)
- Got the exact constant `:id ["reviewer" "mc"]` correct (mirrors FSM transition ID pattern)
- Followed schema structure with proper enums
- Provided optional fields appropriately

### PoC 2 Status

Current implementation does single-shot FSM modification. Previous session hit confusion - the test was working but only doing a fraction of what's needed.

**Next step:** Clarify what PoC 2 should actually validate before continuing implementation.

### Key Design Patterns

**The 3-tuple prompt format:**
```clojure
[input-schema input-document output-schema]
```

- Input schema: "here's the shape of what you received"
- Input document: "here's the actual data"
- Output schema: `[:or ...]` or similar - "here are your valid moves, pick one"

The LLM's output must conform to ONE of the output alternatives. Whichever validates determines the next FSM transition.

**Malli FSM schema (meta-schema for FSM definitions):**
```clojure
[:map {:closed true}
 [:id :string]
 [:description {:optional true} :string]
 [:states [:vector
           [:map {:closed true}
            [:id :string]
            [:action :string]
            [:prompts {:optional true} [:vector :string]]]]]
 [:xitions [:vector
            [:map {:closed true}
             [:id [:tuple :string :string]]
             [:label {:optional true} :string]
             [:schema {:optional true} [:or [:map] :string :boolean]]]]]]
```

### To Continue

```
Continue Malli POC investigation.

PoC 1 PASSING - all 5 LLM providers understand Malli schemas and return valid EDN.

PoC 2 IN PROGRESS - FSM self-modification. Need to clarify requirements:
- Current: single-shot "instruction + FSM → modified FSM"
- Possibly needed: multi-step FSM-driven modification workflow?

Key validation achieved: LLMs can work with Malli schemas instead of JSON Schema.
This unblocks the potential migration from JSON Schema to Malli.

File: test/claij/malli_poc_test.clj
```

---

# Previous Work: Trail Redesign

**See:** [doc/SESSION-2025-11-27-mcp-fsm-fixes.md](doc/SESSION-2025-11-27-mcp-fsm-fixes.md)

### Summary

The trail structure was redesigned to separate two concerns:
1. **Audit log** - what actually happened (for debugging/replay)
2. **LLM prompts** - what the LLM needs to see (for conversation context)

**Solution:** Trail becomes a pure audit log (vector of traversal entries). When calling LLM, use `trail->prompts` to derive conversation format.

### Trail Entry Structure

**Success:** `{:fsm-id :fsm-version :from :to :event}`
**Failure:** `{:fsm-id :fsm-version :from :event :failure {...}}`

**Implementation:** `trail->prompts` function exists in `src/claij/fsm.clj`

---

# Previous Work: Issue 6 Schema Generation Complete

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
- `mcp-cache->request-schema` - generates oneOf covering all available operations
- `mcp-cache->response-schema` - generates anyOf covering all response types
- Tests: 11 assertions

**Total: 80 assertions in mcp-test**

### Pending MCP Work (Not Started)

- Level 2: JSON-RPC envelope wrapping
- Level 3: CLAIJ envelope (claij-id routing)
- FSM integration with dynamic schemas

**Note:** If Malli migration proceeds, MCP schema generation will need to be reimplemented in Malli instead of JSON Schema.

---

# Architecture Reference

## The Core 3-Tuple Pattern

LLM receives: `[input-schema, input-document, output-schema]`

- **Input schema** - describes the shape of incoming data
- **Input document** - the actual data/event
- **Output schema** - `oneOf`/`[:or ...]` of valid output transitions

LLM output validates against ONE alternative in output schema → determines next FSM state.

## Key Files

- `src/claij/fsm.clj` - FSM engine, `trail->prompts`, `llm-action`
- `src/claij/mcp.clj` - MCP schema generation (JSON Schema, may migrate to Malli)
- `test/claij/malli_poc_test.clj` - Malli POC tests
- `doc/SESSION-2025-11-27-mcp-fsm-fixes.md` - Trail redesign details
