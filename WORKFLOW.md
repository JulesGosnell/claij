# CLAIJ Development Workflow

## Overview

CLAIJ's development workflow combines **Kanban-style task management** with **FSM (Finite State Machine) governance** orchestrated by a **Master of Ceremonies (MC)** hat. This hybrid approach enables structured, predictable collaboration between multiple LLM instances while maintaining the flexibility to adapt and self-improve.

The workflow is designed for **AI-driven development teams** where LLM instances take on different "hats" (roles) and coordinate through structured state transitions rather than free-form chat. This prevents broadcast storms, reduces token waste, and enables parallel work with conflict avoidance.

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
 {"src/claij/agent/core.clj" {:story-id 42
                               :locked-at #inst "2025-10-27T10:30:00"
                               :locked-by "dev-hat-1"}
  "src/claij/agent/xform.clj" {:story-id 42
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
