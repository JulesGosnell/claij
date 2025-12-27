# Schema Subsumption in CLAIJ

## Philosophy

CLAIJ is a **composition layer** for AI integrations. External systems (MCP tools, OpenAPI endpoints, LLM services) define their own I/O schemas in JSON Schema. CLAIJ's job is to:

1. **Compose** these integrations into pipelines (FSMs)
2. **Orchestrate** data flow between components
3. **Guarantee** type safety with minimal glue

We don't reinvent schemas - we connect them.

## The Problem

Data flows through a pipeline:

```
xition → action → MCP tool
              ↘ OpenAPI endpoint
```

Each component may have input/output schemas:
- **Xitions**: optional `"schema"` field
- **Actions**: `:action/input-schema`, `:action/output-schema` metadata
- **MCP tools**: `inputSchema` from tool definition
- **OpenAPI endpoints**: request/response schemas from spec

At runtime, a type mismatch causes failure. We want to detect mismatches **before** runtime.

## Schema Subsumption

**Subsumption** (or subtyping) is the relationship:

```
A <: B  ≡  "every value valid under A is also valid under B"
        ≡  "A can safely flow into something expecting B"
```

For a connection `output-A → input-B` to be type-safe:

```
output_schema(A) <: input_schema(B)
```

In code:
```clojure
(subsumes? input-schema output-schema)
;; => {:subsumed? true} or {:subsumed? false :reason "..."}
```

### Examples

```clojure
;; Integer subsumes integer (trivial)
(subsumes? {"type" "integer"} {"type" "integer"})
;; => {:subsumed? true}

;; Number subsumes integer (widening)
(subsumes? {"type" "number"} {"type" "integer"})  
;; => {:subsumed? true} - integers are numbers

;; Integer does NOT subsume number (narrowing)
(subsumes? {"type" "integer"} {"type" "number"})
;; => {:subsumed? false} - 3.14 is a number but not an integer

;; Object with optional field subsumes object with required field
(subsumes? 
  {"type" "object" "properties" {"x" {"type" "integer"}}}           ;; x optional
  {"type" "object" "properties" {"x" {"type" "integer"}} "required" ["x"]})  ;; x required
;; => {:subsumed? true} - required always provides what optional accepts
```

## Design Principles

### 1. Schemas Flow from Integrations

MCP tools and OpenAPI endpoints define their schemas. Don't duplicate or override them:

```clojure
;; BAD: Redundant schema on xition when action has one
{"xitions" [{"id" ["state-a" "mcp-tool-state"]
             "schema" {...}}]}  ;; Don't do this

;; GOOD: Let action's schema (from MCP tool) be authoritative
{"xitions" [{"id" ["state-a" "mcp-tool-state"]}]}  ;; No schema - inherited
```

### 2. Xition Schemas for Glue Only

Use xition schemas only when connecting incompatible components:

```clojure
;; When LLM output needs transformation before MCP tool input
{"xitions" [{"id" ["llm-state" "transform-state"]
             "schema" {...}}  ;; Shape LLM output
            {"id" ["transform-state" "mcp-state"]}]}  ;; MCP schema takes over
```

### 3. Validate at Config Time When Possible

For statically-known schemas (actions, xitions), validate subsumption at FSM definition:

```clojure
(def-fsm my-fsm
  {...})  ;; Macro validates schema compatibility
```

### 4. Validate at Runtime for Dynamic Schemas

MCP tools and OpenAPI schemas discovered at runtime. Validate when "donning hats":

```clojure
(start-fsm context fsm)
;; During hat expansion:
;; - Fetch MCP tool schemas
;; - Fetch OpenAPI schemas  
;; - Validate all connections
;; - Fail fast if incompatible
```

## Implementation Status

### Done
- Action schema metadata (`:action/input-schema`, `:action/output-schema`)
- Dynamic schema resolution (`resolve-schema`)
- Schema registry (`build-fsm-registry`)
- Nested $ref resolution (draft-2020-12)

### Future
- `subsumes?` function for JSON Schema
- Config-time validation in `def-fsm`
- Runtime validation in `don-hats`

## References

- **IBM jsonsubschema** (ISSTA 2021): Proves subsumption is decidable for JSON Schema with 93.5% recall, 100% precision
- **JSON Schema spec**: https://json-schema.org/
- **Type theory**: Subtyping relation A <: B

## Related Issues

- #113 - OpenAPI-first refactoring
- #114 - Ollama schema compatibility
- #115 - Remove Malli dependency
