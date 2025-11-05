# Finite State Machine (FSM) System

## Overview

The CLAIJ FSM system enables **schema-guided multi-agent LLM workflows** where LLMs dynamically assume different roles and cooperate on clearly defined tasks. Each state transition is governed by JSON Schema, creating a self-describing, evolvable system.

> "The factory is the product." - Elon Musk
>
> "Anything you can do, I can do Meta" - Jules Gosnell

## Core Concepts

### Architecture

An FSM is defined by:
- **States**: Nodes representing different roles/responsibilities (MC, reviewer, end, etc.)
- **Transitions** (xitions): Edges between states, each backed by a `core.async` channel
- **Schemas**: JSON Schema definitions constraining valid transitions
- **Actions**: Functions executed when entering a state (typically LLM calls)

Each state's `go-loop` monitors all incoming transition channels using `alts!`, routing events to the appropriate handler based on the transition schema.

### Schema-Guided Conversations

The trail (conversation history) maintains each message as a triple:
```clojure
{"role" "user"/"assistant"
 "content" [input-schema, input-document, output-schema]}
```

This structure provides:
1. **input-schema** - describes the incoming transition
2. **input-document** - the actual data being processed
3. **output-schema** - constrains the LLM's response format

This makes schemas **part of the conversation**, helping LLMs understand both what they're receiving and what they must produce.

### FSM-Scoped Memory

The trail serves as **bounded memory** for each FSM execution:
- Each LLM has perfect memory for its path through the current FSM
- Memory naturally bounded by FSM lifetime
- No context explosion across FSM boundaries
- Future: Summarization at FSM completion, potential merging of parallel FSM summaries

### String Keys

All FSM definitions and schemas use string keys (not keywords) because:
- FSM definitions will be dynamically generated and loaded documents
- Keys may be copied to values and vice versa
- Removes confusion between schema property names and Clojure keywords
- Maintains consistency across the entire system

## Implementation Status

### Recently Fixed (2025-11-05)

**Problem**: FSM was processing only 3 of 4 transitions, then silently failing on the final `["mc" "end"]` transition.

**Root Causes**:
1. **Missing recur**: Each state's `go-loop` processed one event then exited, leaving no listeners for subsequent events
2. **Handler data extraction**: LLM responses wrapped data in vectors with schema references; handler needed to extract the actual data map

**Solutions**:
1. Added `(recur)` to state go-loops for continuous event processing
2. Modified handler to extract data from `[{"$ref" "..."} {actual-data} {"$ref" "..."}]` structures

**Result**: Full code-review-fsm now successfully completes all transitions:
- ✅ `["" "mc"]` - Entry into MC orchestrator
- ✅ `["mc" "reviewer"]` - Request code review  
- ✅ `["reviewer" "mc"]` - Respond with feedback
- ✅ `["mc" "end"]` - Summarize and exit

### Current State

The FSM system is **experimental prototype code** - "free-climbing with a few pitons":
- Core channel routing architecture: ✅ Working
- Schema-guided conversation triples: ✅ Working
- Multi-turn LLM coordination: ✅ Working
- Validation: ⚠️ Temporarily disabled (`if true ;;v?`)
- Channel lifecycle management: ❌ Not implemented
- Error recovery: ⚠️ Limited

## Vision: Self-Evolving AI Organizations

### FSMs Writing FSMs (Meta-Evolution)

The killer feature: FSMs that can **review** and **generate** other FSM definitions.

Planned FSM types:
- **FSM-Reviewer**: Reviews existing FSM definitions, suggests improvements
- **FSM-Generator**: Creates new FSM definitions from natural language descriptions
- **Schema-Reviewer**: Reviews and evolves JSON Schema definitions
- **Schema-Generator**: Creates schemas for new document types

This creates a **self-improving workflow system** where LLMs don't just execute tasks - they evolve the task definitions themselves.

### MCP Services as FSM States

Extend states beyond LLM actions to include MCP (Model Context Protocol) services:

```clojure
{"id" "query-database"
 "action" "mcp://database/query"
 "schema" {...}}

{"id" "create-pr"
 "action" "mcp://github/create-pr"
 "schema" {...}}

{"id" "code-review"
 "action" "fsm://code-review"}  ; Sub-FSM invocation
```

This makes FSMs a **universal coordination layer** for:
- LLM conversations
- External tool calls (via MCP)
- Database interactions
- API integrations
- Sub-workflow invocation

### FSM Composition

Enable building complex workflows from simpler FSMs:

**Sequential Composition**: Chain FSMs together
```clojure
design-fsm → implementation-fsm → testing-fsm → deployment-fsm
```

**Parallel Composition**: Run FSMs concurrently, merge results
```clojure
(parallel
  security-review-fsm
  performance-review-fsm
  code-quality-review-fsm) → merge-results-fsm
```

**Conditional Composition**: Route based on state/data
```clojure
(if-then-else
  complexity-assessment-fsm
  simple-review-fsm
  comprehensive-review-fsm)
```

**Recursive Composition**: FSMs spawning child FSMs
```clojure
project-fsm spawns [feature-fsm feature-fsm ...]
feature-fsm spawns [task-fsm task-fsm ...]
```

### Self-Describing, Versioned Evolution

Build a system where every artifact is:
- **Versioned**: FSMs, schemas, meta-schemas all have version numbers
- **Schema-described**: Every document has a schema (including schemas themselves)
- **Evolvable**: LLMs can propose modifications
- **Reviewable**: Other LLMs validate changes
- **Traceable**: System documents its own evolution

This is "Git for AI workflow systems" where commits are made by LLMs collaborating through FSMs.

## Roadmap

### Phase 1: Solidify Foundation (Immediate)

**Critical fixes** (hammer in the pitons):
1. Re-enable validation (`if v?` → actual validation)
2. Channel lifecycle management (cleanup on FSM completion)
3. Better error handling (malformed JSON, validation failures)
4. Comprehensive test coverage for edge cases

**Documentation**:
1. Document FSM definition format
2. Document schema triple convention
3. Create example FSMs with annotations

### Phase 2: Meta-Evolution (Next)

**FSM Self-Improvement**:
1. **FSM-Validator**: FSM that validates other FSM definitions against meta-schema
2. **FSM-Generator**: Natural language → FSM definition
3. **Schema-Generator**: Generate schemas for new document types
4. **FSM-Optimizer**: Analyze FSMs, suggest efficiency improvements

**Versioning System**:
1. FSM version management
2. Schema version compatibility checking
3. Migration paths between versions

### Phase 3: MCP Integration (Medium-term)

**MCP State Support**:
1. MCP tool discovery and schema extraction
2. MCP state action handler
3. Input/output schema mapping
4. Error handling for external services

**Example Integrations**:
1. GitHub PR creation/review states
2. Database query states
3. File system operations states
4. API call states

### Phase 4: Composition (Long-term)

**Composition Operators**:
1. Sequential composition (`then`)
2. Parallel composition (`parallel`, with result merging)
3. Conditional composition (`if-then-else`, `switch`)
4. Recursive composition (`spawn-child-fsm`)

**Schema Composition**:
1. Output schema of FSM-A → Input schema of FSM-B validation
2. Parallel FSM result merging schemas
3. Conditional branching schema constraints

### Phase 5: Advanced Features (Research)

**Challenges**:
1. **Cost Management**: Every transition is an LLM call - optimization strategies?
2. **Observability**: Debugging self-modifying systems
3. **Conflict Resolution**: What if LLMs propose incompatible changes?
4. **Complexity Bounds**: Preventing FSM-generation explosions
5. **Safety**: Human oversight for critical system changes

**Potential Solutions**:
1. Caching/memoization for repeated patterns
2. FSM execution visualization tools
3. Consensus mechanisms for FSM evolution
4. Complexity budgets and limits
5. Human-in-the-loop approval for meta-changes

## Example: Code Review FSM

The working code-review-fsm demonstrates the core concepts:

### States
- **mc**: Orchestrates the review process, decides when to request reviews or summarize
- **reviewer**: Provides code feedback based on quality criteria
- **end**: Terminal state capturing final summary

### Transitions
- `["" "mc"]`: Entry with code document
- `["mc" "reviewer"]`: Request for review
- `["reviewer" "mc"]`: Review response with comments
- `["mc" "end"]`: Final summary and exit

### Schema Definitions
Each transition has a schema defining its structure:
- `entry`: Initial code submission format
- `request`: MC's review request format
- `response`: Reviewer's feedback format
- `summary`: Final summary format

All schemas live in `$defs` of the FSM's root schema, referenced via `$ref`.

### Execution Flow
1. User submits code via `["" "mc"]` transition
2. MC state evaluates, decides to request review
3. MC emits `["mc" "reviewer"]` with code and notes
4. Reviewer state analyzes, provides feedback
5. Reviewer emits `["reviewer" "mc"]` with comments
6. MC state considers feedback, decides review is sufficient
7. MC emits `["mc" "end"]` with summary
8. End state captures result, workflow completes

## Why This Matters

Most AI agent frameworks use **hardcoded workflows** where humans define every step. This system enables:

- **Workflows as Data**: FSM definitions are documents, not code
- **Evolvable Workflows**: LLMs can modify FSM definitions
- **Composable Workflows**: FSMs invoke other FSMs
- **Self-Aware System**: Schemas describe everything, including themselves

This is **infrastructure for AI organizations** - not just individual agents, but systems of cooperating agents that can reorganize themselves to solve evolving problems.

The system maintains safety through:
- Schema validation at every transition
- Versioned evolution with audit trails
- Bounded memory per FSM execution
- Human oversight for critical changes (future)

## References

- Implementation: `src/claij/fsm.clj`
- Tests: `test/claij/fsm_test.clj`
- Example FSM: `code-review-fsm` in test file
- JSON Schema: Draft 2020-12 via m3 validation library
