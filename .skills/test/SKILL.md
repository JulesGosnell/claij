# Test Writing Skill

Testing is difficult to do well. These principles help manage complexity.

## The Combinatorial Explosion Problem

Tests grow exponentially with branches:
- 1 branch → 2 tests
- 2 composed branches → 2² = 4 tests  
- 3 composed branches → 2³ = 8 tests
- N composed branches → 2^N tests

**If you test small units separately:**
- 3 single-branch functions tested individually = 6 tests
- 3 single-branch functions tested together = 8 tests

Managing this is a matter of taste and experience. Strategies:
- Use small, pure, composable functions
- Use IoC (Inversion of Control) - inject dependencies
- By injecting a 0-branch function into a 1-branch function, you write 2 tests, not 4

## Test Categories

### Unit Tests
**Purpose:** Test business logic in isolation, fast feedback loop.

**Characteristics:**
- Run after every code change
- Must be fast and cheap
- NO remote access - only local in-process
- No file I/O, no database, no network
- Forking small local processes is acceptable (e.g., Python MCP test server)

**What belongs here:**
- Pure function logic
- Data transformations
- Schema validation
- Protocol formatting/parsing (with recorded data)

**What does NOT belong:**
- Real HTTP calls to APIs
- Tests requiring running services
- Subprocess spawning of heavy processes (JVM)

### Integration Tests
**Purpose:** Test I/O boundaries work correctly.

**Characteristics:**
- Run less frequently (before commits, in CI)
- Can take more time
- Test ONLY the I/O code itself
- Associated business logic should be disconnected and unit tested separately

**Tag with:** `^:integration`

**What belongs here:**
- HTTP client → real API endpoint
- MCP bridge → real MCP server process
- File system operations
- Database connections

**Minimal coverage:** One successful roundtrip per integration point proves it works.

### Long-Running Tests
**Purpose:** Performance and stability testing.

**Characteristics:**
- Very slow and expensive
- May do I/O
- Run very infrequently - only after big changes
- Unit and integration tests should protect these from bit-rot

**Tag with:** `^:long-running`

**What belongs here:**
- Load testing
- Soak testing
- Multi-hour stability runs
- Performance benchmarks

## Rational & Minimal Coverage

Continuously ask:
- Is this test adding unique value?
- Could this be tested at a lower level (unit vs integration)?
- Am I testing the same thing multiple ways?
- Is the test fast enough for its category?

**Anti-patterns:**
- Testing the same API call 5 different ways across files
- POC tests that hit real APIs when they could use recorded responses
- "Integration" tests that are really unit tests with accidental I/O

## Integration Points (CLAIJ-specific)

CLAIJ has these actual integration points:

1. **LLM Services** - HTTP APIs (Anthropic, Google, OpenRouter, xAI, Ollama)
2. **MCP Servers** - Subprocess stdio (JSON-RPC protocol)

**Minimal integration test set:**
- One test per LLM provider: connect, authenticate, get response, parse
- One MCP roundtrip: spawn, initialize, list tools, call tool, shutdown

**Everything else should be unit tests** with mocked/recorded responses.

## Coverage Requirement
- Run `./bin/test-coverage.sh` before committing
- Maintain 90%+ line coverage (script enforces this)
- Check `target/coverage/index.html` for gaps
- Add minimal tests to cover new code paths
- Use simplest possible inputs to exercise code

## Structure
- One x_test.clj per x.clj
- require uses :refer not :as
- One deftest x-test (matches module name)
- Same order as production code
- One testing per function
- Sub-testings for specific cases

## POC Tests
POC (Proof of Concept) tests live in `test/claij/poc/` directory:
- Experimental code that may migrate to production
- Tests that don't correspond to a production file (yet)
- Often start as integration tests, should migrate to unit tests with recorded data

POC files are exempt from the "one x_test.clj per x.clj" rule.
When POC code matures, move it to production and create matching test file.

## Test Consolidation
When merging test files:
- Track test counts before/after to avoid losing tests
- Use `grep -c "^(deftest"` to count tests per file
- Verify total assertions match after merge
- Use `git mv` to preserve history

## Keep Simple
- Test behavior not implementation
- If test too complex, split production code
- Don't state obvious

## NEVER Thread/sleep
Always use proper coordination:
- Promises for one-time events
- CountDownLatches for sync points
- core.async channels

Rule: Thread/sleep = bug waiting to happen

## Directory Boundaries

**Test code MUST stay in `./test/`** - never leak into `./src/`:
- Test fixtures, test configs, test utilities → `test/`
- Test-only MCP servers, mock data → `test/` or `bin/` (for scripts)
- If production code needs test-specific config, use dependency injection or test fixtures

**Why this matters:**
- Production builds should not include test code
- Clear separation of concerns
- Avoids bloating production artifacts
- Makes it obvious what's test vs production

**Examples:**
```clojure
;; BAD - test config in production namespace
(def test-mcp-config  ;; in src/claij/mcp/bridge.clj
  {"command" "python3" ...})

;; GOOD - test config in test namespace
(def test-mcp-config  ;; in test/claij/mcp/bridge_test.clj
  {"command" "python3" ...})
```
