# Skill: CLAIJ Development Process

## Purpose

Comprehensive guide for developing CLAIJ features. Covers story-driven workflow, POC patterns, testing discipline, and coding guidelines. Use this skill when working on any CLAIJ development task.

## Prerequisites

- GitHub repository with issue tracking
- Status labels: `status:todo`, `status:doing`, `status:done`
- REPL environment configured
- Coverage threshold: 90% line, 60% form

---

## Development Phases

### Phase 0: Story Setup

**Create GitHub issue with:**
- Clear goal statement
- Acceptance criteria as checkboxes
- Labels: `enhancement` or `refactor` + `status:todo`

**Outputs:** Story number for commit references, clear scope definition.

### Phase 1: Design

1. Discuss architecture options
2. Choose simplest solution that works
3. Document design decisions in story body

**Good patterns:**
- Strategy dispatch for polymorphism
- Pure functions returning data (not executing)
- Service registry for configuration

**Anti-patterns:**
- Provider/model translation layers
- Hidden coupling between components

### Phase 2: POC

**Location:** `test/claij/poc/<feature>_poc_test.clj`

1. Implement core abstractions in test file
2. Write comprehensive unit tests
3. Verify with live integration test

**Commit:** `#<issue> feat(poc): <description>`

**Outputs:** Working code with tests, confidence in design.

### Phase 3: Production Migration

1. Create `src/claij/<feature>.clj` from POC
2. Create `test/claij/<feature>_test.clj` from POC tests
3. Update dependent code (imports, callers)
4. Run focused tests

**Commit:** `#<issue> feat(<scope>): <description>`

### Phase 4: Cleanup

1. Remove deprecated code
2. Remove POC test file (superseded)
3. Update remaining references
4. Run full test suite

**Commit:** `#<issue> chore: remove deprecated <what>`

### Phase 5: Verification

1. Run coverage: `./bin/test-coverage.sh`
2. Fix any test failures
3. Manual REPL verification
4. Live integration test if applicable

**Thresholds:** 90% line coverage, 60% form coverage.

### Phase 6: Documentation

1. **Review affected docs** - Check for stale references (grep for old patterns)
2. **Update README** announcements table with major advances
3. **Update ARCHITECTURE.md** if system design changed
4. **Update skill docs** if workflows/conventions changed
5. **Rationalise** - Remove obsolete content, consolidate duplicates
6. Update story with final commits
7. Close story: remove `status:doing`, add `status:done`
8. Comment closing any unchecked items in story comments

**Commit:** `#<issue> docs: <description>`

**Doc Update Checklist:**
- [ ] Grepped codebase for stale terminology
- [ ] README announcements current
- [ ] ARCHITECTURE.md reflects changes
- [ ] Skill docs updated
- [ ] No conflicting information across docs

### Phase 7: Retrospective

At the end of every story, Claude performs a brief retro:

**Questions to answer:**
1. What went well?
2. What went badly or was harder than expected?
3. How could we have done it better?
4. What changes should be made to skills/processes?
5. Did I follow the process? If not:
   - Does the process need improving?
   - Or do I need to be more rigorous?

**Actions:**
- Add retro notes as final comment on the GitHub issue
- Propose skill updates if patterns emerged
- Note any technical debt created

**This is automatic** - Claude initiates this without being asked.

---

## Complex Integration Strategy: Meet in the Middle

When integrating new functionality into existing complex code, **avoid symptom-chasing**. If something doesn't work during integration, don't hack around it - understand WHY first.

### The Anti-Pattern (What Goes Wrong)

1. Build new feature
2. Try to integrate
3. Something breaks → add hack to fix it
4. Something else breaks → add another hack
5. Repeat until codebase is full of special cases

**Root cause:** Jumping to integration before proving the pieces work, then chasing symptoms instead of understanding the architecture.

### The Pattern: Meet in the Middle

**Step 1: Prove both ends in isolation**

- **Left side (new code):** Build in POC, rigorously test with concrete examples
- **Right side (existing code):** Identify the exact integration points, understand how they currently work

The goal is to reduce integration complexity toward trivial.

**Step 2: If integration still not trivial, move the middle**

- Write unit tests that define the exact interface between new and existing
- Use concrete, proven-to-work examples from both sides
- Pure functions that transform between formats
- Incrementally adjust BOTH sides toward a clean meeting point
- Keep testing at every step

**Step 3: When integration is trivial, integrate**

Only when:
- Both sides are proven working
- The interface is clean and tested
- Integration is just "plug these two tested pieces together"

### Practical Example: Native Tool Calling

**Wrong approach (what failed):**
1. Built translation layer
2. Tried to integrate into llm-action
3. FSM hung → hacked terminal state detection
4. Action lookup failed → hacked action registration
5. More breakage, more hacks

**Right approach:**
1. **Prove left:** POC shows native tools work with LLMs (XOR compliance)
2. **Prove right:** Understand how existing MCP events flow through FSM
3. **Define middle:** Unit tests for pack-request / unpack-response with concrete examples from both sides
4. **Meet:** Pure transformation functions, tested in isolation
5. **Integrate:** Trivial - just wire the proven functions into llm-action

### Key Questions Before Integration

1. "How does this work in the existing code?" - trace the happy path
2. "What concrete examples do I have from both sides?"
3. "Can I write a unit test for the transformation without touching integration?"
4. "Is the integration now trivial, or do I need more preparation?"

If you find yourself adding special-case code during integration, **STOP** - you haven't proven enough in isolation yet.

---

## Git Rules

### Commit Format

| Type | Format |
|------|--------|
| Phase | `#<issue> [Phase N]: <imperative description>` |
| Fix | `#<issue> [fix]: <description>` |
| Docs | `#<issue> docs: <description>` |
| Final | `closes #<issue>: Complete <story title>` |

### Critical Rules

1. **NEVER git push** - Claude commits locally, Jules reviews and pushes
2. **NEVER git merge** - Claude works on branches, Jules merges
3. **NEVER git add -A or git add .** - Always explicitly list files to avoid committing sensitive/unwanted files
4. **Use `git mv`** not `mv` for file moves (preserves history)
5. **Pipe git to cat** - Use `git log | cat` or `--no-pager` to avoid pager hang
6. **Atomic commits** - one logical change per commit
7. **Reference issue number** in every commit

### Incremental Change Strategy

**Always prefer small, tested increments over big-bang changes.**

1. **Default approach**: Make small change → test → commit → repeat
   - Each step should leave tests passing
   - If tests break, the change is too big

2. **If incremental is impossible**: Drop into a "rabbit hole"
   - Improve production code structure first (small incremental steps)
   - This is an opportunity to improve testing and coverage
   - Once structure allows, return to original task

3. **If still impossible**: Feature branch approach
   - Create feature branch: `git checkout -b feature/<n>`
   - Do the work on branch with commits
   - Ask Jules to merge and push at end of story
   - **Only Jules merges branches**

### Pre-Push Checklist

- [ ] All tests pass
- [ ] Coverage meets thresholds
- [ ] Manual verification done

---

## Testing Discipline

### TDD Approach

Write test first, then code to pass it. Tests define what "working" means.

### Test Tiers

| Tier | Speed | External | When | Marker |
|------|-------|----------|------|--------|
| Unit | <1s | None | Every save, CI | (default) |
| Integration | Seconds | LLMs, DBs | Before commit | `^:integration` |

### Mocking Rules

- Mocks isolate units, NOT replace integration tests
- Ask: "If I mock this, what am I actually testing?"
- A mock test is NOT a substitute for integration test

### Async Testing

**NEVER use `Thread/sleep` for coordination.** Instead:
- Promises for one-time events
- `CountDownLatch` for sync points
- `deref` with timeout

```clojure
;; Good
(let [result (promise)]
  (async-op #(deliver result %))
  (is (= expected (deref result 5000 :timeout))))

;; Bad - non-deterministic, fragile
(async-op)
(Thread/sleep 1000)
(is (= expected @result))
```

### Checklist Before Done

- [ ] Unit tests pass
- [ ] Integration tests pass with REAL services
- [ ] Manual smoke test
- [ ] Error cases covered, not just happy path
- [ ] No mocks hiding broken integration

---

## Coding Principles

### Core Values

1. **Simplicity** - always ask "can this be simpler?"
2. **Symmetry in naming** - `open/close`, `start/stop`
3. **No boilerplate** - every line earns its place
4. **Inline when used once** - extract when reused
5. **Pure functions, immutable data, composition**
6. **Data-driven design** over objects
7. **Small composable functions** - <20 lines ideal

### Namespace Rules

- Prefer `:refer [symbols]` over namespace aliases
- Exception: `log` for logging, `json` for JSON
- Use `:rename` for symbol collisions

```clojure
;; Good
(ns claij.feature
  (:require
   [clojure.string :refer [join split trim]]
   [clojure.tools.logging :as log]
   [cheshire.core :as json]))
```

### Clojure Idioms

- Threading macros (`->`, `->>`) for pipelines
- Destructuring over `get`/`get-in`
- Atoms for coordinated state
- `core.async` for async patterns

### Error Handling

- Fail fast with `ex-info`
- Don't catch unless you can handle
- Validate at boundaries

### Performance

- Enable `*warn-on-reflection*`
- Add type hints to eliminate reflection
- Profile before optimizing

---

## CLAIJ-Specific Conventions

### Key Abstractions

| Concept | Description |
|---------|-------------|
| **FSM** | Finite State Machine - states, transitions (xitions), schemas |
| **Action** | What states DO: `llm`, `mcp`, `fsm`, `repl` |
| **Hat** | Cross-cutting concerns: `mcp`, `retry`, `triage` |
| **Trail** | Audit log of state transitions |
| **Context** | Runtime state: `:llm/service`, `:llm/model`, `:store` |

### Context Keys

| Key | Description |
|-----|-------------|
| `:llm/service` | Service name (e.g., `"openrouter"`, `"ollama:local"`) |
| `:llm/model` | Model name native to service |
| `:store` | Database connection for FSM persistence |

### Schema Conventions

- String keys in FSM definitions (not keywords)
- JSON Schema for validation via m3 library (draft-2020-12)
- Use `$defs` with `$ref` for schema reuse
- Nested `$ref` resolution requires draft-2020-12

### File Layout

| Path | Purpose |
|------|---------|
| `src/claij/<domain>.clj` | Production code |
| `src/claij/<domain>/<sub>.clj` | Nested modules |
| `test/claij/<domain>_test.clj` | Unit tests |
| `test/claij/poc/<feature>_poc_test.clj` | POC explorations |

---

## Story Hygiene

### Before Closing

1. Scan issue **BODY** for unchecked boxes
2. Scan issue **COMMENTS** for unchecked boxes
3. Either: check them, delete them, or move to new story
4. Update labels: remove `status:doing`, add `status:done`
5. Add final progress comment if significant

### During Development

- Update checkboxes as tasks complete
- Add comments with commit SHAs
- Note any blockers or discoveries
- Keep acceptance criteria current

### Common Gotchas

- Phase 2 tasks in comments may be stale
- Tests may reference old API (e.g., `:service` vs `:llm/service`)
- Coverage may drop after removing code

---

## Recovery Procedures

### On Crash Mid-Story

1. Read issue to see current state
2. Check Progress Log for last completed task
3. Check `git log` for last commit
4. Resume from next unchecked task

### On Test Failure

1. Run focused test: `clojure -M:test --focus <ns>`
2. Fix the specific issue
3. Re-run full suite
4. Commit fix with `[fix]` reference

### On Coverage Drop

1. Check which namespace dropped
2. Add unit tests with mocks for new code
3. Target the uncovered lines specifically

### On Stale Context

1. Check `/mnt/transcripts/journal.txt` for recent sessions
2. Read relevant transcript for context
3. Check GitHub issue for current state

---

## Common Commands

### Testing

```bash
clojure -M:test unit              # All unit tests
clojure -M:test --focus <ns>      # Focused namespace
clojure -M:test integration       # Integration tests
./bin/test-coverage.sh            # Coverage report
```

### REPL

```clojure
;; Start: clojure -M:dev
(require '[<ns>] :reload)
(clojure.test/run-tests '<ns>)
```

### Git

```bash
git status
git add <specific-files>                    # NEVER use -A or .
git commit -m '#<issue> [Phase N]: <description>'
git log --oneline -10 | cat                 # Always pipe to cat
git diff
```

---

## Related Documents

- [GITHUB-DEV-WORKFLOW.md](GITHUB-DEV-WORKFLOW.md) - Full FSM specification
- [CODING_GUIDELINES.md](../CODING_GUIDELINES.md) - Detailed coding standards
- [ARCHITECTURE.md](../ARCHITECTURE.md) - System design
- [VISION.md](../VISION.md) - Strategic direction

---

## Version History

| Date | Change |
|------|--------|
| 2025-12-30 | Added "Complex Integration Strategy: Meet in the Middle" section |
| 2025-12-20 | Initial version - consolidated from existing docs and practical experience |
