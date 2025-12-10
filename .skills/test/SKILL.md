# Test Writing Skill

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
- Reliability/integration experiments across providers
- Long-running tests tagged ^:long-running

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
