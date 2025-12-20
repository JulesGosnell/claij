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

1. Update README announcements table
2. Update story with final commits
3. Close story: remove `status:doing`, add `status:done`
4. Comment closing any unchecked items in story comments

**Commit:** `#<issue> docs: <description>`

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
2. **Use `git mv`** not `mv` for file moves (preserves history)
3. **Atomic commits** - one logical change per commit
4. **Reference issue number** in every commit

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
- Malli schemas for validation
- `[:or :int :double]` not `:number` (Malli has no `:number`)
- `:closed` maps to prevent extra fields

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
git add -A
git commit -m '#<issue> [Phase N]: <description>'
git log --oneline -10
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
| 2025-12-20 | Initial version - consolidated from existing docs and practical experience |
