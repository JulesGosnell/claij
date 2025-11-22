# Test Writing Skill

## Structure
- One x_test.clj per x.clj
- require uses :refer not :as
- One deftest x-test (matches module name)
- Same order as production code
- One testing per function
- Sub-testings for specific cases

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
