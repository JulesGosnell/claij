# Session Handoff

## Current State: JSON Schema Migration Complete

### Summary

CLAIJ uses **JSON Schema** for all validation via the **m3** library (draft-2020-12).

**Why JSON Schema (not Malli)?**
- **LLM compatibility** - LLMs have extensive training data on JSON Schema; smaller/local models struggle with Malli's EDN format
- **Integration native** - MCP tools and OpenAPI endpoints define schemas in JSON Schema
- **Composition layer** - CLAIJ composes external integrations; using their native schema format minimizes translation

**The journey:**
1. Started with JSON Schema
2. Tried Malli (PoC showed LLMs understood it, but smaller Ollama models struggled)
3. Returned to JSON Schema with improved architecture

### Key Files

- `src/claij/schema.clj` - Validation via m3, draft-2020-12
- `src/claij/fsm.clj` - FSM engine with schema registry
- `docs/design/schema-subsumption.md` - Schema compatibility philosophy
- `docs/design/113-openapi-first.md` - OpenAPI integration design

### Schema Architecture

**FSM schemas use `$defs` with local `$ref` resolution:**
```json
{
  "$defs": {
    "code": {"type": "object", "properties": {"language": {"type": "string"}}},
    "request": {"$ref": "#/$defs/code"}
  }
}
```

**Xition schemas inherit FSM's `$defs`** - they "believe" they're part of the FSM document during validation. This enables terse local refs without verbose URI-based resolution.

**draft-2020-12 required** for nested `$ref` resolution (draft-07 loses context).

---

## Open Work

### Stories

| Issue | Description | Priority |
|-------|-------------|----------|
| #113 | OpenAPI-first REST API refactoring | Future |
| #114 | Verify Ollama models work with JSON Schema | Low |
| #115 | Remove Malli dependency (reitit coercion) | Low |
| #116 | Implement `subsumes?` for schema compatibility | Medium |

### Philosophy: Schema Subsumption

See `docs/design/schema-subsumption.md`.

CLAIJ is a **composition layer**. External systems (MCP, OpenAPI) define their own schemas. Our job is to:
1. Connect them into pipelines (FSMs)
2. Guarantee type safety at config/load time via `subsumes?`
3. Minimize glue - don't duplicate schemas that integrations already define

---

## To Continue

```
JSON Schema migration is complete on feature/json-schema branch.
- 239 unit tests passing
- Nested $ref resolution working (draft-2020-12)
- code-review-fsm validated in REPL

Ready to merge to main.

Next priorities:
1. Merge feature/json-schema → main
2. #116 - subsumes? for config-time validation (when needed)
3. #113 - OpenAPI integration (future phase)
```

---

## Architecture Reference

### The Core 3-Tuple Pattern

LLM receives: `[input-schema, input-document, output-schema]`

- **Input schema** - describes the shape of incoming data
- **Input document** - the actual data/event  
- **Output schema** - `oneOf` of valid output transitions

LLM output validates against ONE alternative in output schema → determines next FSM state.

### Key Directories

```
src/claij/
├── schema.clj      # JSON Schema validation (m3)
├── fsm.clj         # FSM engine, trail, actions
├── mcp.clj         # MCP integration
└── llm.clj         # LLM service abstraction

docs/design/
├── 113-openapi-first.md      # OpenAPI integration
└── schema-subsumption.md     # Composition philosophy
```
