# CLAIJ Development Workflow

## Overview

CLAIJ's development workflow combines **Kanban-style task management** with **FSM (Finite State Machine) governance** orchestrated by a **Master of Ceremonies (MC)** hat. This hybrid approach enables structured, predictable collaboration between multiple LLM instances while maintaining the flexibility to adapt and self-improve.

The workflow is designed for **AI-driven development teams** where LLM instances take on different "hats" (roles) and coordinate through structured state transitions rather than free-form chat. This prevents broadcast storms, reduces token waste, and enables parallel work with conflict avoidance.

## Workflow Visualization

Visual representations of the workflow FSM are available:

### High-Level Workflow
- **[Workflow Diagram (SVG)](workflow.svg)** - Rendered visualization of the complete system
- **[Workflow Source (DOT)](workflow.dot)** - GraphViz source for the diagram

Shows the Meta-Loop (Retrospective → Planning) and Story Loop (Pick → Zone Check → API Design → Implement → Test → Merge → Close) with their key sub-FSMs.

### Story Processing FSM
- **[Story FSM Diagram (SVG)](story.svg)** - Detailed story implementation flow
- **[Story FSM Source (DOT)](story.dot)** - GraphViz source

Shows the detailed flow of taking a story from Ready-for-Dev through to Done: Pick → Move to Dev → Break into Tasks → Assign & Implement → Dev-Complete → Integration Test → Done.

**Note**: These visualizations are the first steps toward a **library of executable FSMs**. The vision is to encode all workflow state machines in a Clojure DSL that links states to specific hats (roles) and scripts (implementations), enabling fully automated workflow orchestration. See [FSM Library Vision](#fsm-library-vision) below.

## Core Principles

### 1. Small, Granular Stories
- Stories should be implementable in a single focused session
- Prefer atomic changes that can be merged to main immediately
- Break large features into independent, deployable increments

### 2. Conflict Prevention via Zoning
- Lock code areas before starting work to prevent concurrent conflicts
- Queue stories that overlap with locked zones
- Promote API-first design to enable parallel work on decoupled components

### 3. Self-Improvement via Toolsmith
- Monitor for repetitive patterns across the team
- Extract patterns into reusable DSL functions
- Continuously evolve the team's shared vocabulary

### 4. Trunk-Based Development
- All work happens on main branch
- No long-lived feature branches or PRs
- Atomic commits only; MC enforces clean merges
- Tests must pass before merge

### 5. Junior-Friendly Structure
- Explicit state machines beat free-form chat for AI agents
- Clear role definitions via hats
- Structured decision-making (votes, reviews)
- Reduces token waste from wandering conversations

## The Kanban Board

The board progresses through these columns (v1 focuses on **Ready-for-Dev** onward):

```
Backlog → Ready-for-Analysis → Analysis → Ready-for-Dev → Dev → Done
```

### Column Definitions

- **Backlog**: Unrefined ideas, feature requests, bugs
- **Ready-for-Analysis**: Sized and prioritized; ready for BA hats
- **Analysis**: Being analyzed by BA hats to create detailed stories
- **Ready-for-Dev**: Analyzed stories ready for implementation
- **Dev**: Currently being worked on by dev hats
- **Done**: Implemented, tested, merged to main

## The Master of Ceremonies (MC) Hat

The MC is a special hat that orchestrates workflow transitions and maintains team coordination:

### MC Responsibilities

1. **Story Selection**: Pick highest-priority story from Ready-for-Dev
2. **Zone Management**: Lock/unlock code areas; queue conflicting work
3. **FSM Orchestration**: Trigger state transitions and sub-FSM entry/exit
4. **Vote Management**: Conduct votes when decisions need team consensus
5. **Merge Enforcement**: Validate tests pass; enforce atomic commits
6. **Retrospective Facilitation**: Run retros when Ready-for-Dev empties
7. **Planning Coordination**: Manage story pulling, sizing, and prioritization

The MC does not write code or make technical decisions—it facilitates the process.

## The Meta-Loop

Triggered when **Ready-for-Dev becomes empty**:

```
┌─────────────────────────────────────────────────┐
│              META-LOOP (MC-driven)              │
├─────────────────────────────────────────────────┤
│  1. Retrospective                               │
│     - Review what caused jams/blockers          │
│     - Identify patterns (good and bad)          │
│     - Vote on process improvements              │
│     - Update workflow rules if needed           │
│                                                  │
│  2. Planning Refill                             │
│     - Pull stories from Backlog                 │
│     - Size stories (small/medium/large)         │
│     - Prioritize by value/impact                │
│     - Load Ready-for-Dev with next batch        │
│                                                  │
│  └─→ Return to Story Loop                       │
└─────────────────────────────────────────────────┘
```

### Retrospective State Machine

```
States: gather-feedback → identify-jams → brainstorm-improvements → vote-on-changes → update-rules → complete

Transitions:
- gather-feedback → identify-jams: All hats submit feedback
- identify-jams → brainstorm-improvements: Jams categorized
- brainstorm-improvements → vote-on-changes: Ideas collected
- vote-on-changes → update-rules: Vote passes (>50%)
- vote-on-changes → complete: Vote fails (≤50%)
- update-rules → complete: Rules documented
```

### Planning State Machine

```
States: pull-backlog → size-stories → assess-value → prioritize → load-ready-for-dev → complete

Transitions:
- pull-backlog → size-stories: Stories selected from Backlog
- size-stories → assess-value: All stories sized (S/M/L)
- assess-value → prioritize: Value/impact scores assigned
- prioritize → load-ready-for-dev: Stories sorted by priority
- load-ready-for-dev → complete: Ready-for-Dev filled to capacity
```

## The Story Loop

For each story picked from Ready-for-Dev:

```
┌────────────────────────────────────────────────────────────────┐
│                    STORY LOOP (MC-driven)                      │
├────────────────────────────────────────────────────────────────┤
│  1. Pick Story (MC)                                            │
│     - Select highest priority from Ready-for-Dev               │
│     - Move to Dev column                                       │
│                                                                 │
│  2. Zone Check (MC)                                            │
│     - Identify code areas this story will touch                │
│     - Check if zones are locked by other stories               │
│     - If conflict: queue story, pick next                      │
│     - If clear: lock zones, proceed                            │
│                                                                 │
│  3. API Design Sub-FSM ──────────────────────────┐             │
│     - Gather API proposals from dev hats         │             │
│     - MC clumps similar ideas                    │             │
│     - Vote on final design                       │             │
│     - Lock API contract                          │             │
│     └─→ Promotes decoupling for parallelism      │             │
│                                                   │             │
│  4. Implement Sub-FSM ───────────────────────────┤             │
│     - Assign roles:                              │             │
│       • Coder(s): Write implementation           │             │
│       • Tester: Write tests                      │             │
│       • Documenter: Update docs                  │             │
│       • Toolsmith: Scan for patterns to extract  │             │
│     - Coordinate parallel work                   │             │
│     - Review code (may trigger Review Sub-FSM)   │             │
│                                                   │             │
│  5. Test (MC verifies)                           │             │
│     - Run test suite                             │             │
│     - If fail: loop back to Implement            │             │
│     - If pass: proceed to merge                  │             │
│                                                   │             │
│  6. Merge (MC enforces)                          │             │
│     - Atomic commit to main                      │             │
│     - Clean merge (no conflicts)                 │             │
│     - Unlock zones                               │             │
│                                                   │             │
│  7. Close (MC)                                   │             │
│     - Move story to Done                         │             │
│     - Pick next story from Ready-for-Dev         │             │
│                                                   │             │
│  └─→ Loop or Enter Meta-Loop if Ready-for-Dev empty            │
└────────────────────────────────────────────────────────────────┘
```

## Sub-FSM Library

These are reusable state machines that can be nested within the Story Loop or each other:

### 1. Vote Sub-FSM

**Purpose**: Team needs to make a decision about design choices, implementation approaches, or conflicting proposals.

```
States: proposal → discussion → voting → tally → declaration

Roles:
- MC: Manages voting process, prevents vote brigading, declares winner
- All relevant hats: Cast votes, participate in discussion

Triggers:
- API design conflicts
- Multiple refactoring approaches
- Review disagreements
- Process improvement proposals (in retros)

Exit:
- Returns winning decision to parent FSM
```

### 2. Review Sub-FSM

**Purpose**: After dev hat produces code, other dev hats review it.

```
States: submission → parallel-review → discussion → resolution

Sub-states of resolution:
- approved: No changes needed
- request-changes: Back to revision → review
- disagree: Enter Vote Sub-FSM

Roles:
- Submitter: Author of code under review
- Reviewers: Other dev hats (MC decides who's relevant)
- MC: Coordinates review process, mutes non-reviewing hats

Re-entrant:
- If major issues found, loops back through revision → review
- Tracks revision iterations to prevent infinite loops (max 3)

Exit:
- Code approved: Returns to parent FSM
- Code needs more work: Returns to implement state
```

### 3. Make-Tool Sub-FSM

**Purpose**: Toolsmith hat identifies repeated patterns worth extracting into DSL.

```
States: pattern-detection → proposal → review → implementation → dsl-update → reindex → broadcast

Roles:
- Toolsmith: Identifies patterns, proposes new DSL functions
- Dev hats: Review proposal (enters Review Sub-FSM)
- MC: Coordinates reindexing and broadcast

Key Actions:
- pattern-detection: Toolsmith monitors conversations for repetition
- proposal: Draft new DSL function signature and docstring
- review: Team reviews via Review Sub-FSM
- implementation: Write function, add to DSL namespace
- dsl-update: Update DSL codebase
- reindex: Rebuild DSL documentation index
- broadcast: New DSL available to all hats in subsequent requests

Exit:
- New DSL function available team-wide
- Token costs reduced for future similar operations
```

### 4. Refactor Sub-FSM

**Purpose**: Improve existing code structure without changing behavior.

```
States: identify-target → propose-refactoring → impact-analysis → implementation → testing → vote?

Roles:
- Proposer: Dev hat identifying code smell
- Impact Analyzer: Dev hat assessing affected code/tests
- Implementer(s): May be multiple parallel approaches
- MC: Coordinates and decides if vote needed

Branches:
- Single proposal: Direct to implementation
- Multiple proposals: Each implemented in parallel, then Vote Sub-FSM

Rollback State:
- If tests fail after refactoring, automatic rollback
- Return to propose-refactoring with failure insights

Exit:
- Refactoring merged (tests pass)
- Or rollback (tests fail after retries)
```

### 5. Debug Sub-FSM

**Purpose**: Reproduce bugs and establish test cases before fixing.

```
States: reproduce → provide-test → verify-test-fails → complete

Roles:
- Bug Hunter: Dev hat attempting to reproduce issue
- Test Writer: Creates test that captures the bug
- Test Runner: Verifies test actually fails as expected
- MC: Verifies test is proper reproduction

Goal:
- Create a failing test that captures the bug
- Ensure the test fails for the right reason
- Document expected vs actual behavior

Transitions:
- reproduce → provide-test: Bug successfully reproduced
- provide-test → verify-test-fails: Test written
- verify-test-fails → complete: Test confirmed failing
- verify-test-fails → reproduce: Test doesn't fail (bad reproduction)

Exit:
- Transitions into Fix-Test Sub-FSM with failing test in hand
```

### 6. Fix-Test Sub-FSM

**Purpose**: Systematic bug fixing with test-driven approach.

```
States: hypothesize → instrument → test-hypothesis → evaluate → complete

Sub-states of evaluate:
- fix-found: Test now passes → verify-all-tests → complete
- hypothesis-eliminated: Try next hypothesis or give up

Roles:
- Hypothesis Generator: Dev hats propose explanations
- Instrumenter: Add logging/debugging code
- Fixer: Implement potential fix
- Test Runner: Verify fix makes test pass
- MC: Coordinates parallel hypothesis testing

Entry:
- Entered from Debug Sub-FSM with failing test

Parallel Branches:
- Can spawn multiple hypothesis-testing branches
- Each hypothesis tested by different dev hat instance
- Reconverges when a fix passes or all hypotheses exhausted

Rollback:
- If all hypotheses fail, may loop back to Debug Sub-FSM
- Suggests the reproduction test may be incorrect

Exit:
- Fix found and all tests pass: Merge to main
- All hypotheses failed: Back to Debug or escalate to team
```

### 7. API-Design Sub-FSM

**Purpose**: Design API contracts before implementation to enable parallel work.

```
States: gather-proposals → clump-similar → discuss → vote → lock-contract

Roles:
- Proposers: Dev hats suggest API shapes
- MC: Clumps similar proposals, runs vote
- All dev hats: Vote on final design

Key Benefits:
- Decouples implementation from interface
- Enables parallel development on both sides of API
- Prevents integration conflicts later

Exit:
- Locked API contract (function signatures, data shapes)
- Implementation can proceed in parallel
- Contract changes require new API-Design Sub-FSM
```

### 8. Implement Sub-FSM

**Purpose**: Coordinate parallel implementation work with assigned roles.

```
States: assign-roles → parallel-work → integrate → review

Roles assigned by MC:
- Coder(s): Write implementation code
- Tester: Write test cases
- Documenter: Update documentation
- Toolsmith: Monitor for extractable patterns

Parallel Tracks:
- Implementation and tests written concurrently
- Documentation updated alongside code
- Toolsmith watches for DSL opportunities

Integration:
- Code, tests, and docs merged together
- All must be complete before moving to review

Exit:
- Ready for Review Sub-FSM or Test phase
```

### 9. Feature-Development Sub-FSM

**Purpose**: Implement new features end-to-end (high-level coordination).

```
States: requirements → design → vote-on-design → implement → test → review → integration

Nested Sub-FSMs:
- design may enter API-Design Sub-FSM
- vote-on-design enters Vote Sub-FSM
- implement enters Implement Sub-FSM (which may trigger Make-Tool Sub-FSM)
- test may trigger Debug Sub-FSM if issues found
- review enters Review Sub-FSM

Roles:
- Architect: High-level design
- Dev hats: Implementation (via Implement Sub-FSM)
- QA: Testing strategy
- MC: Orchestrates entire feature workflow

Exit:
- Feature merged to main, fully tested and documented
```

## State Muting and Hat Orchestration

The FSM prevents chaos by **muting irrelevant hats** in each state:

| State | Active Hats | Muted Hats |
|-------|------------|------------|
| **API-Design** | Architect, Dev hats, MC | Tester, Documenter, Toolsmith |
| **Implement (coding)** | Assigned Coder(s), MC | Other devs, unless reviewing |
| **Implement (testing)** | Tester, Coder(s), MC | Documenter, Toolsmith |
| **Review** | Reviewers, Submitter, MC | Non-reviewing devs |
| **Vote** | All stakeholder hats, MC | Irrelevant hats (context-dependent) |
| **Debug** | Bug Hunter, Test Writer, MC | Other devs unless consulting |
| **Fix-Test** | Hypothesis testers, MC | Other devs unless consulting |
| **Retrospective** | All hats | None (everyone participates) |
| **Planning** | MC, Product Owner, Architect | Dev hats (unless consulting) |

## Zone Management

**Zones** are code areas (files, namespaces, functions) that get locked during active work:

### Zone Locking Rules

1. Story claims zones at start of implementation
2. MC maintains zone lock registry (in state)
3. Subsequent stories that overlap locked zones are queued
4. Zones unlocked after merge to main
5. Queued stories automatically picked by MC after unlock

### Example Zone Lock State

```clojure
{:zone-locks
 {"src/claij/mcp/core.clj" {:story-id 42
                               :locked-at #inst "2025-10-27T10:30:00"
                               :locked-by "dev-hat-1"}
  "src/claij/util/xform.clj" {:story-id 42
                                :locked-at #inst "2025-10-27T10:30:00"
                                :locked-by "dev-hat-1"}}
 :zone-queue
 [{:story-id 43
   :zones ["src/claij/agent/core.clj"]
   :blocked-since #inst "2025-10-27T10:35:00"}]}
```

## FSM State Transitions

State transitions are triggered by **JSON flags in LLM responses**, not keywords. This integrates with the Structured Responses architecture (see `STRUCTURED_RESPONSES.md`).

### Example Response with State Transition

```json
{
  "answer": "API design approved by vote. Proceeding to implementation.",
  "state": {
    "current_state": "implement",
    "previous_state": "api-design",
    "transition": "vote-passed",
    "locked_zones": ["src/claij/server.clj", "src/claij/agent/core.clj"],
    "active_hats": ["coder-1", "coder-2", "tester", "toolsmith"],
    "muted_hats": ["architect", "documenter"]
  }
}
```

The MC reads the `state` field and:
1. Updates FSM current state
2. Locks specified zones
3. Mutes/unmutes appropriate hats
4. Routes next messages only to active hats

## Workflow Examples

### Example 1: Simple Bug Fix

```
1. Bug reported → Added to Backlog
2. Meta-Loop Planning → Sized (S), moved to Ready-for-Dev
3. MC picks story, checks zones (no conflicts)
4. Enter Debug Sub-FSM:
   - Reproduce bug
   - Write failing test
   - Verify test fails
5. Enter Fix-Test Sub-FSM:
   - Dev hat proposes hypothesis
   - Implements fix
   - Test passes
6. MC runs full test suite (passes)
7. MC merges to main
8. Story moved to Done
```

### Example 2: Feature with API Design

```
1. Feature idea → Added to Backlog
2. Meta-Loop Planning → Sized (L), split into 3 stories, prioritized
3. MC picks first story (API design)
4. Enter API-Design Sub-FSM:
   - 3 dev hats propose API shapes
   - MC clumps similar proposals (2 camps)
   - Enter Vote Sub-FSM → Design A wins
   - API contract locked
5. Enter Implement Sub-FSM:
   - MC assigns roles (2 coders, 1 tester, 1 documenter)
   - Work proceeds in parallel (API already defined)
   - Toolsmith notices repetitive error handling pattern
6. Toolsmith triggers Make-Tool Sub-FSM:
   - Proposes new DSL function: `dsl.wrap-api-errors`
   - Enter Review Sub-FSM → Approved
   - DSL updated, reindexed, broadcast to team
7. Back to Implement Sub-FSM (now using new DSL function)
8. Enter Review Sub-FSM:
   - Code reviewed by other dev hats
   - Minor changes requested
   - Revision → Re-review → Approved
9. MC runs tests (pass)
10. MC merges to main
11. Story moved to Done
12. MC picks second story (continues with locked API contract)
```

### Example 3: Retrospective Triggers Process Change

```
1. Ready-for-Dev becomes empty
2. MC triggers Meta-Loop
3. Enter Retrospective:
   - Dev hats report: "Too many merge conflicts lately"
   - MC identifies pattern: Stories not granular enough
   - Brainstorm: "Enforce smaller story sizes"
   - Enter Vote Sub-FSM → Rule change approved
   - MC updates workflow rules: Max story size = M
4. Enter Planning:
   - Pull from Backlog
   - Aggressively break down large stories
   - Size all stories (S or M only)
   - Prioritize, load Ready-for-Dev
5. Return to Story Loop with new rules
```

## Integration with CLAIJ Architecture

### Structured Responses

The FSM workflow integrates with CLAIJ's structured response architecture:

- Every LLM response includes a `state` field
- State transitions are validated against FSM rules
- Invalid transitions trigger retry with error feedback
- See `STRUCTURED_RESPONSES.md` for schema details

### Hat System

Hats are implemented as:
- Role-specific system prompts
- Context awareness (muted/unmuted per state)
- Persistent identity across conversations
- Memory of past decisions/actions

### MCP Integration

The MC can use MCP tools to:
- Lock/unlock files (zone management)
- Run tests (merge validation)
- Update documentation (DSL reindexing)
- Manage the Kanban board state

### DSL Evolution

As the Toolsmith extracts patterns:
- New functions added to `claij.dsl` namespace
- Documentation auto-generated
- Team uses DSL in subsequent work
- Token costs decrease over time

## FSM Library Vision

The workflow visualizations (workflow.dot, story.dot) represent the first steps toward a **comprehensive library of executable FSMs** encoded in a Clojure DSL. This library will enable fully automated, reproducible workflow orchestration.

### Design Goals

1. **Declarative FSM Definitions**: Each FSM encoded as data (not imperative code)
2. **Hat Bindings**: States explicitly bind to specific hats (roles) that handle them
3. **Script Integration**: Transitions can trigger executable scripts (Clojure functions, shell commands, tool calls)
4. **Composability**: FSMs nest within other FSMs (sub-FSM pattern)
5. **State Persistence**: FSM state serializable and recoverable across sessions
6. **Introspection**: FSMs self-document their states, transitions, and requirements
7. **Validation**: FSM definitions validated at load time (no invalid transitions)

### FSM DSL Sketch

Here's a conceptual sketch of what the FSM DSL might look like in Clojure:

```clojure
(ns claij.fsm.library.story
  (:require [claij.fsm.core :as fsm]))

(fsm/deffsm story-processing
  "Processes a story from Ready-for-Dev to Done"
  
  {:initial-state :start
   :terminal-states #{:done}
   
   :states
   {:start
    {:label "Start - Ready-for-Dev Not Empty"
     :active-hats #{:mc}
     :muted-hats #{:all-dev-hats}}
    
    :pick-story
    {:label "Take Highest Priority Story"
     :active-hats #{:mc}
     :on-entry (fsm/script [:mc/pick-highest-priority-story])
     :on-exit (fsm/script [:kanban/update-board :story-id :to :dev])}
    
    :break-into-tasks
    {:label "Collaboratively Break into Granular Tasks"
     :active-hats #{:mc :architect :lead-dev}
     :on-entry (fsm/script [:mc/facilitate-task-breakdown])
     :sub-fsm :task-breakdown}
    
    :assign-and-implement
    {:label "Assign & Implement Tasks"
     :active-hats #{:mc :assigned-dev-hats :tester :toolsmith}
     :sub-fsm :implement-tasks
     :parallel true  ;; Multiple tasks can run concurrently
     :zone-lock true}  ;; Lock code zones during implementation
    
    :integration-test
    {:label "Integration Test"
     :active-hats #{:mc :tester}
     :on-entry (fsm/script [:test/run-full-suite])
     :success-condition (fsm/predicate [:test/all-passed?])
     :retry-on-failure {:max-retries 3
                        :backoff-state :fix-test}}
    
    :done
    {:label "Move to Done"
     :active-hats #{:mc}
     :on-entry (fsm/script [:kanban/move-to-done :story-id]
                           [:zone/unlock-all])}}
   
   :transitions
   [{:from :start        :to :pick-story         :trigger :ready-for-dev-not-empty}
    {:from :pick-story   :to :break-into-tasks   :trigger :story-picked}
    {:from :break-into-tasks :to :assign-and-implement :trigger :tasks-defined}
    {:from :assign-and-implement :to :integration-test :trigger :all-tasks-complete}
    {:from :integration-test :to :done :trigger :tests-passed}
    {:from :integration-test :to :assign-and-implement :trigger :tests-failed}
    {:from :done :to :start :trigger :more-stories-in-ready-for-dev}
    {:from :done :to :meta-loop :trigger :ready-for-dev-empty}]})

;; Sub-FSM for task breakdown
(fsm/deffsm task-breakdown
  "Breaks a story into atomic tasks"
  
  {:initial-state :gather-proposals
   :terminal-states #{:tasks-locked}
   
   :states
   {:gather-proposals
    {:label "Gather Task Proposals from Team"
     :active-hats #{:architect :dev-hats}
     :timeout-ms 60000}
    
    :review-and-merge
    {:label "Review and Merge Similar Proposals"
     :active-hats #{:mc :architect}
     :sub-fsm :vote-if-needed}
    
    :size-tasks
    {:label "Size Each Task (S/M/L)"
     :active-hats #{:mc :dev-hats}
     :on-entry (fsm/script [:task/estimate-complexity])}
    
    :tasks-locked
    {:label "Tasks Finalized"
     :on-entry (fsm/script [:task/persist-to-board])}}
   
   :transitions
   [{:from :gather-proposals :to :review-and-merge :trigger :all-proposals-submitted}
    {:from :review-and-merge :to :size-tasks :trigger :consensus-reached}
    {:from :size-tasks :to :tasks-locked :trigger :all-sized}]})
```

### Script Implementation

Scripts referenced in FSM definitions would be implemented as multimethods or protocols:

```clojure
(ns claij.fsm.scripts.mc)

(defmulti execute-script
  "Execute FSM scripts based on script type"
  (fn [script-vector state-data] (first script-vector)))

(defmethod execute-script :mc/pick-highest-priority-story
  [_ {:keys [ready-for-dev-stories]}]
  (let [story (first (sort-by :priority ready-for-dev-stories))]
    {:story-id (:id story)
     :next-trigger :story-picked}))

(defmethod execute-script :kanban/update-board
  [[_ story-id-key to-column] state-data]
  (let [story-id (get state-data story-id-key)]
    (kanban/move-card! story-id to-column)
    {:next-trigger :story-moved}))

(defmethod execute-script :test/run-full-suite
  [_ state-data]
  (let [results (test/run-all-tests!)]
    (if (test/all-passed? results)
      {:next-trigger :tests-passed
       :test-results results}
      {:next-trigger :tests-failed
       :test-results results
       :failure-details (test/extract-failures results)})))
```

### Hat Configuration

Hats would be configured with FSM awareness:

```clojure
(ns claij.hats.core)

(defrecord Hat [id role system-prompt active? muted? fsm-context])

(defn activate-hats-for-state
  "Activate only the hats relevant to current FSM state"
  [fsm current-state all-hats]
  (let [state-def (get-in fsm [:states current-state])
        active-hat-roles (:active-hats state-def)
        muted-hat-roles (:muted-hats state-def)]
    (for [hat all-hats]
      (assoc hat
             :active? (contains? active-hat-roles (:role hat))
             :muted? (contains? muted-hat-roles (:role hat))
             :fsm-context {:current-state current-state
                          :available-triggers (fsm/available-triggers fsm current-state)}))))
```

### Benefits of FSM Library

1. **Reproducibility**: Same FSM definition produces same behavior every time
2. **Debuggability**: FSM execution traced and logged; easy to see where things went wrong
3. **Testability**: FSM definitions tested in isolation; scripts mocked
4. **Composability**: Complex workflows built from simple, reusable FSM components
5. **Self-Documentation**: FSMs generate their own documentation, diagrams, and help text
6. **Evolution**: FSMs versioned; team can experiment with workflow changes safely
7. **Tool Integration**: Scripts can call any CLAIJ tool (MCP, REPL, file system, etc.)

### FSM Library Structure

```
claij.fsm/
├── core.clj              # FSM engine, interpreter, state management
├── dsl.clj               # DSL macros (deffsm, script, predicate, etc.)
├── validation.clj        # FSM definition validation
├── visualization.clj     # Generate .dot files from FSM definitions
├── scripts/
│   ├── mc.clj           # MC-specific scripts
│   ├── kanban.clj       # Kanban board manipulation
│   ├── test.clj         # Test running scripts
│   ├── zone.clj         # Code zone locking/unlocking
│   └── vote.clj         # Voting coordination
└── library/
    ├── story.clj        # Story processing FSM
    ├── meta_loop.clj    # Retrospective + Planning FSMs
    ├── vote.clj         # Vote sub-FSM
    ├── review.clj       # Code review sub-FSM
    ├── debug.clj        # Debug sub-FSM
    ├── fix_test.clj     # Fix-test sub-FSM
    ├── make_tool.clj    # Make-tool sub-FSM
    ├── refactor.clj     # Refactor sub-FSM
    ├── api_design.clj   # API design sub-FSM
    └── implement.clj    # Implement sub-FSM
```

### Execution Model

The FSM engine would:

1. **Load FSM**: Read FSM definition from library
2. **Initialize State**: Set initial state and load state data
3. **Enter State**: Activate appropriate hats, run on-entry scripts
4. **Wait for Trigger**: Monitor for state transition triggers (from LLM responses, script results, or timeouts)
5. **Validate Transition**: Check if trigger is valid for current state
6. **Execute Transition**: Run on-exit scripts, move to new state
7. **Handle Sub-FSMs**: Push/pop FSM stack for nested sub-FSMs
8. **Persist State**: Save FSM state after each transition
9. **Repeat**: Continue until terminal state reached

### Integration with Structured Responses

FSM state and available triggers would be included in the JSON schema sent to LLMs:

```json
{
  "answer": "I've broken the story into 3 tasks: implement API, write tests, update docs",
  "state": {
    "current_fsm": "story-processing",
    "current_state": "break-into-tasks",
    "available_triggers": ["all-proposals-submitted", "request-more-time"],
    "active_hats": ["mc", "architect", "lead-dev"],
    "sub_fsm_stack": []
  },
  "trigger": "all-proposals-submitted"
}
```

The MC would read the `trigger` field and advance the FSM accordingly.

### Roadmap

**Phase 1 (Current)**: Document FSMs as .dot visualizations
- ✓ workflow.dot (high-level)
- ✓ story.dot (story processing)
- ⧗ Complete library of .dot files for all sub-FSMs

**Phase 2**: Implement FSM DSL and engine
- Core FSM interpreter
- DSL macros for FSM definition
- State persistence
- Script execution framework

**Phase 3**: Implement script library
- MC scripts (story picking, facilitation, etc.)
- Kanban manipulation scripts
- Testing scripts
- Zone management scripts

**Phase 4**: Implement all Sub-FSMs in DSL
- Port Vote, Review, Make-Tool, Refactor, etc. from documentation to executable code
- Add FSM validation and testing

**Phase 5**: Full integration
- Wire FSMs to LLM conversations via Structured Responses
- Hat system fully FSM-aware
- End-to-end workflow automation

**Phase 6**: Self-improvement
- Toolsmith can propose new FSM patterns
- Team can vote on workflow FSM changes
- FSMs evolve based on retrospective insights

## Future Enhancements

### Permutation City Forking

Extend Fix-Test Sub-FSM to fork parallel hypothesis-testing instances:
- Each fork is a separate LLM conversation
- Forks compete to find working fix first
- MC merges successful fork, discards others
- Enables speculative execution with rollbacks

### Upstream BA (Business Analyst) Workflow

Extend to handle story creation:
- BA hats analyze Backlog items
- Create detailed stories with acceptance criteria
- Move refined stories to Ready-for-Dev
- Separate Analysis FSM for this work

### Dynamic Team Sizing

MC adjusts team size based on workload:
- Spin up additional dev hats when Ready-for-Dev is large
- Spin down hats during planning phases
- Optimize for parallel work vs communication overhead

### Automated Story Sizing

ML-based story size prediction:
- Learn from historical velocity
- Suggest sizes during Planning
- Alert when stories seem too large

### Conflict Prediction

Analyze code dependencies to predict conflicts:
- Suggest story reordering to minimize queue time
- Recommend API designs that maximize parallelism
- Identify high-conflict zones for refactoring

## Conclusion

This MC-driven FSM workflow provides structure and predictability to AI team collaboration while maintaining flexibility for:
- Self-improvement (retrospectives, DSL evolution)
- Parallel work (API design, zone locking)
- Quality assurance (reviews, votes, tests)
- Conflict avoidance (zone management, small stories)

The workflow beats free-form chat by preventing broadcast storms, reducing token waste, and enabling AI instances to coordinate like a real development team—even with junior-like capabilities.
