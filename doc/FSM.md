# Finite State Machine (FSM) System

## Overview

The CLAIJ FSM system enables **schema-guided multi-agent LLM workflows** where LLMs dynamically assume different roles and cooperate on clearly defined tasks. Each state transition is governed by JSON Schema, creating a self-describing, evolvable system where **FSMs can create, modify, and orchestrate other FSMs**.

> "The factory is the product." - Elon Musk
>
> "Anything you can do, I can do Meta" - Jules Gosnell
>
> "The mind is not a single thing but rather a swarm of tiny concepts..." - Marvin Minsky - "The Society of Mind"

## Core Concepts

### Architecture

An FSM is defined by:
- **States**: Nodes representing different roles/responsibilities (MC, reviewer, BA, end, etc.)
- **Transitions** (xitions): Edges between states, each backed by a `core.async` channel
- **Schemas**: JSON Schema definitions constraining valid transitions
- **Actions**: Functions executed when entering a state (LLM calls, MCP services, human endpoints)

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

### Schema Constraints (Token Optimization)

FSM schemas enforce constraints that optimize token usage:

**Xition Schemas** - Must use `$ref` format only:
```clojure
"schema" {"$ref" "#/$defs/request"}  ; ✓ Valid - references FSM's $defs
"schema" {"type" "object" ...}       ; ✗ Invalid - inline schemas not allowed
```

This enforces **token compression**: The FSM schema (with all `$defs`) is provided once as a system prompt, while xition schemas in the conversation history are just compact references.

**Root FSM Schema** - Must be valid JSON Schema:
```clojure
"schema" {"type" "object"           ; ✓ Valid - proper JSON Schema
          "properties" {...}}
"schema" {"type" "invalid-type"}    ; ✗ Invalid - violates meta-schema
```

These constraints are validated at FSM definition time via the `fsm-m2` meta-schema.

## Implementation Status

### Shared LLM Action (2025-11-26)

The `llm-action` function lives in `claij.fsm` and is shared by all FSMs that use LLM states:

```clojure
(defn llm-action
  "FSM action: call LLM with prompts built from FSM config and trail.
   Extracts provider/model from input data, defaults to openai/gpt-4o."
  [context fsm ix state trail handler]
  ...)
```

Key points:
- **Builds prompts** from FSM schema, state prompts, and conversation trail via `make-prompts`
- **Calls `open-router-async`** with provider/model from input data (or defaults)
- **Returns `(handler context output)`** - FSM handler validates output against schema
- **No structured output schema needed** - FSM handler already validates and retries on failure

FSM definitions reference this action by name in state definitions:
```clojure
{"id" "llm-state"
 "action" "llm"
 "prompts" ["State-specific instructions..."]}
```

### Recently Completed (2025-11-05)

**FSM Event Processing Fixes**:
- Fixed missing `recur` in state go-loops (now continuously process events)
- Fixed handler data extraction from LLM response structures
- All 4 transitions in code-review-fsm now complete successfully

**Schema Constraint Tightening**:
- Added `schema-ref` constraint enforcing `$ref` format for xitions
- Added `json-schema` constraint validating root FSM schemas
- Implemented comprehensive test coverage (5/5 tests pass)
- Prevents false positives with both positive and negative test cases

### Current State

The FSM system is **experimental prototype code** - "free-climbing with a few pitons":
- Core channel routing architecture: ✅ Working
- Schema-guided conversation triples: ✅ Working
- Multi-turn LLM coordination: ✅ Working
- Schema constraints and validation: ✅ Working
- Token-optimized schema references: ✅ Working
- Channel lifecycle management: ❌ Not implemented
- FSM interruptibility: ❌ Not implemented
- Error recovery: ⚠️ Limited

## Vision: Self-Evolving AI Organizations

### The Triage FSM: Dynamic Problem-Solving Orchestrator

The **general-problem-solving-fsm** acts as a universal entry point and orchestrator:

```
Human Input (voice/text)
        ↓
    [Triage State]
        ↓
┌───────┴────────┐
│ Load FSM Store │ → [id, version, description]
└───────┬────────┘
        ↓
┌───────────────────────────────────┐
│ Search/Select FSM                 │
│ - Search descriptions for fit     │
│ - Prefer load by version (caching)│
│ - Consider adaptation vs new      │
└───────┬───────────────────────────┘
        ↓
    ┌───┴────┐
    │ Found? │
    └───┬────┘
    Yes │   No
        │    └──────→ [BA State: Build Minimal FSM]
        │                   ↓
        │            Store FSM, Start FSM
        │                   ↓
        └──────→ [Start Selected/Built FSM]
                           ↓
                   [FSMs improve themselves
                    during execution]
```

#### Triage Flow

1. **Human Input**: Voice or text describing a task
2. **Load FSM Store**: Retrieve available FSMs with metadata
3. **Search/Select**: 
   - Match descriptions to find good starting point
   - Prefer versioned loading for caching efficiency
   - Decide: use existing, adapt, or build new
4. **No Match → BA Creation**:
   - BA (Business Analyst) state creates minimal FSM
   - FSM is immediately usable
   - LLMs improve it during execution
5. **Start FSM**: Execute selected/built FSM
6. **Continuous Improvement**: FSM evolves as it runs

### Sub-FSM Spawning: Contextual Interruption and Resumption

FSMs must support **interruptibility** - pausing one FSM, spawning another, then resuming with modified context:

#### Example: Recipe Improvement During Execution

```clojure
;; Original "baking-cake" FSM running
[Human: "Add cream to the mixture"]
    ↓
[Execution State: Processing instruction]
    ↓
[LLM thinks: "Perhaps recipe should allow yogurt instead"]
    ↓
[SPAWN: recipe-improvement-fsm]
    │
    ├─→ [Nutritionist State: Analyze substitutions]
    ├─→ [Chef State: Taste compatibility check]
    ├─→ [Consensus State: Approve oneOf[cream, yogurt]]
    ↓
[RETURN: Updated schema with oneOf constraint]
    ↓
[RESUME: baking-cake-fsm with enhanced schema]
    ↓
[Continue: Now accepts yogurt OR cream]
```

#### Shape Modification During Execution

When resuming from a sub-FSM:
- **Input schema can change**: Original expected `{"ingredient": "cream"}`, now accepts `{"ingredient": {"oneOf": ["cream", "yogurt"]}}`
- **Return values adapt**: Sub-FSM returns schema modifications
- **Trail context preserved**: Conversation history maintains continuity
- **Validation updates**: New schemas validated before resumption

This enables **dynamic schema evolution** - FSMs don't just execute tasks, they can reshape their own contracts mid-execution.

### FSM Interruptibility Requirements

To support sub-FSM spawning, FSMs need:

1. **Suspension State Capture**:
   ```clojure
   {:suspended-fsm "baking-cake"
    :suspended-state "mixing"
    :trail [...conversation-history...]
    :pending-transition ["mixing" "baking"]
    :context {...accumulated-state...}}
   ```

2. **Sub-FSM Invocation**:
   ```clojure
   {"action" "fsm://recipe-improvement"
    "input-schema" {...current-schema...}
    "context" {...relevant-context...}}
   ```

3. **Resumption with Modification**:
   ```clojure
   {:resume-fsm "baking-cake"
    :resume-state "mixing"
    :schema-updates {"ingredient" {"oneOf": ["cream", "yogurt"]}}
    :trail [...original-trail...]
    :new-context {...merged-context...}}
   ```

4. **Schema Compatibility Checking**:
   - Validate modified schemas before resumption
   - Ensure new constraints don't break pending transitions
   - Migration paths for incompatible changes

### Human-in-the-Loop States

FSMs need states that integrate with human endpoints:

```clojure
{"id" "human-approval"
 "action" "human://text"  ; or "human://voice"
 "description" "Wait for human input via text interface"
 "schema" {"$ref" "#/$defs/approval-request"}}

{"id" "voice-consultation"
 "action" "human://voice"
 "description" "Engage human via voice conversation"
 "schema" {"$ref" "#/$defs/consultation-schema"}}
```

**Integration modes**:
- **Text**: Async input via chat interface (like current interaction)
- **Voice**: Real-time voice conversation with TTS/STT
- **Approval**: Yes/no decisions on proposed actions
- **Clarification**: Questions when FSM encounters ambiguity
- **Override**: Human intervention to change FSM direction

These states make FSMs **collaborative** rather than autonomous, with natural points for human oversight.

### FSMs Writing FSMs (Meta-Evolution)

The killer feature: FSMs that can **review** and **generate** other FSM definitions.

Planned FSM types:
- **FSM-Reviewer**: Reviews existing FSM definitions, suggests improvements
- **FSM-Generator**: Creates new FSM definitions from natural language descriptions
- **FSM-Optimizer**: Analyzes execution patterns, optimizes state graphs
- **Schema-Reviewer**: Reviews and evolves JSON Schema definitions
- **Schema-Generator**: Creates schemas for new document types
- **BA-FSM**: Business Analyst role - creates minimal viable FSMs quickly

This creates a **self-improving workflow system** where LLMs don't just execute tasks - they evolve the task definitions themselves.

#### The BA (Business Analyst) Role

When the triage FSM finds no suitable FSM for a task:

1. **BA State Activated**: Given natural language task description
2. **Minimal FSM Generation**: Creates simplest possible FSM
   ```clojure
   ;; Example minimal FSM
   {"id" "blog-post-writer"
    "states" [{"id" "writer"} {"id" "end"}]
    "xitions" [{"id" ["" "writer"] "schema" {...}}
               {"id" ["writer" "end"] "schema" {...}}]
    "schema" {...minimal-schema...}}
   ```
3. **Immediate Start**: FSM begins execution immediately
4. **Live Improvement**: LLMs refine FSM as they use it
5. **Version Evolution**: Each improvement creates new version

This "start minimal, improve continuously" approach prevents over-engineering and adapts to actual usage patterns.

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

{"id" "human-approval"
 "action" "human://text"}        ; Human endpoint
```

This makes FSMs a **universal coordination layer** for:
- LLM conversations
- External tool calls (via MCP)
- Database interactions
- API integrations
- Sub-workflow invocation
- Human oversight and input

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

**Interruptible Composition**: FSM pauses, spawns sub-FSM, resumes
```clojure
main-fsm
  └─ pause at state-X
  └─ spawn improvement-fsm
       └─ returns schema-updates
  └─ resume main-fsm with updates
```

### Self-Describing, Versioned Evolution

Build a system where every artifact is:
- **Versioned**: FSMs, schemas, meta-schemas all have version numbers
- **Schema-described**: Every document has a schema (including schemas themselves)
- **Evolvable**: LLMs can propose modifications
- **Reviewable**: Other LLMs validate changes
- **Traceable**: System documents its own evolution
- **Cacheable**: Version-based loading enables efficient caching

This is "Git for AI workflow systems" where commits are made by LLMs collaborating through FSMs.

## Roadmap

### Phase 1: Solidify Foundation (Immediate)

**Critical fixes** (hammer in the pitons):
1. ✅ Re-enable validation (completed 2025-11-05)
2. ✅ Schema constraints tightening (completed 2025-11-05)
3. Channel lifecycle management (cleanup on FSM completion)
4. FSM interruptibility (suspend/resume)
5. Better error handling (malformed JSON, validation failures)
6. Comprehensive test coverage for edge cases

**Documentation**:
1. Document FSM definition format
2. Document schema triple convention
3. Create example FSMs with annotations
4. Document sub-FSM spawning pattern

### Phase 2: Dynamic Orchestration (Next)

**Triage FSM**:
1. FSM store implementation (load by ID/version)
2. FSM search by description
3. Version-based caching
4. BA state for minimal FSM generation

**Sub-FSM Support**:
1. Suspension state capture
2. Sub-FSM invocation protocol
3. Resumption with schema updates
4. Schema compatibility validation

**Human Integration**:
1. Human endpoint states (text/voice)
2. Approval workflow patterns
3. Clarification/consultation protocols

### Phase 3: Meta-Evolution (Medium-term)

**FSM Self-Improvement**:
1. **BA-FSM**: Generates minimal viable FSMs from descriptions
2. **FSM-Validator**: Validates FSM definitions against meta-schema
3. **FSM-Optimizer**: Analyzes execution, suggests improvements
4. **Schema-Generator**: Generates schemas for new document types
5. **FSM-Reviewer**: Reviews and suggests FSM improvements

**Versioning System**:
1. FSM version management
2. Schema version compatibility checking
3. Migration paths between versions
4. Execution history and analytics per version

### Phase 4: MCP Integration (Medium-term)

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

### Phase 5: Composition (Long-term)

**Composition Operators**:
1. Sequential composition (`then`)
2. Parallel composition (`parallel`, with result merging)
3. Conditional composition (`if-then-else`, `switch`)
4. Recursive composition (`spawn-child-fsm`)
5. Interruptible composition (`suspend`, `resume`)

**Schema Composition**:
1. Output schema of FSM-A → Input schema of FSM-B validation
2. Parallel FSM result merging schemas
3. Conditional branching schema constraints
4. Schema evolution during execution

### Phase 6: Advanced Features (Research)

**Challenges**:
1. **Cost Management**: Every transition is an LLM call - optimization strategies?
2. **Observability**: Debugging self-modifying systems
3. **Conflict Resolution**: What if LLMs propose incompatible changes?
4. **Complexity Bounds**: Preventing FSM-generation explosions
5. **Safety**: Human oversight for critical system changes
6. **Caching Strategy**: Version-based caching vs execution memoization

**Potential Solutions**:
1. Caching/memoization for repeated patterns
2. FSM execution visualization tools (web dashboard)
3. Consensus mechanisms for FSM evolution
4. Complexity budgets and limits
5. Human-in-the-loop approval for meta-changes
6. Version-based loading with intelligent cache invalidation

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

All schemas live in `$defs` of the FSM's root schema, referenced via `{"$ref": "#/$defs/..."}`.

### Execution Flow
1. User submits code via `["" "mc"]` transition
2. MC state evaluates, decides to request review
3. MC emits `["mc" "reviewer"]` with code and notes
4. Reviewer state analyzes, provides feedback
5. Reviewer emits `["reviewer" "mc"]` with comments
6. MC state considers feedback, **autonomously decides** review is sufficient
7. MC emits `["mc" "end"]` with summary
8. End state captures result, workflow completes

**Key Insight**: The MC made an autonomous decision to run TWO review cycles before completing. This demonstrates **non-deterministic workflow execution** - the FSM structure is fixed, but LLMs decide the actual path through it based on context and reasoning.

## Why This Matters

Most AI agent frameworks use **hardcoded workflows** where humans define every step. This system enables:

- **Workflows as Data**: FSM definitions are documents, not code
- **Evolvable Workflows**: LLMs can modify FSM definitions
- **Composable Workflows**: FSMs invoke other FSMs
- **Interruptible Workflows**: FSMs pause, spawn sub-FSMs, resume with updates
- **Self-Aware System**: Schemas describe everything, including themselves
- **Dynamic Orchestration**: Triage system selects/adapts/creates FSMs on demand
- **Human-Collaborative**: Natural integration points for human oversight

This is **infrastructure for AI organizations** - not just individual agents, but systems of cooperating agents that can:
- Reorganize themselves to solve evolving problems
- Create new workflows when none exist
- Improve workflows as they execute them
- Collaborate with humans at natural decision points

The system maintains safety through:
- Schema validation at every transition
- Versioned evolution with audit trails
- Bounded memory per FSM execution
- Human oversight states for critical changes
- Consensus mechanisms for FSM evolution (future)

## References

- Implementation: `src/claij/fsm.clj`
- Meta-schema: `fsm-m2` with `schema-ref` and `json-schema` constraints
- Tests: `test/claij/fsm_test.clj`
- Example FSM: `code-review-fsm` in test file
- JSON Schema: Draft 2020-12 via m3 validation library
- Validation: m3 library (`src/m3/`)
