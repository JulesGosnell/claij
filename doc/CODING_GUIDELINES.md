# CLAIJ Coding Guidelines

## Core Values

**These principles guide all code in CLAIJ:**

- **Symmetry in naming** - `open/close`, `start/stop` - similar length, clear pairs
- **Short, clear names** - Prefer Anglo-Saxon over Latin/Greek: `get` not `retrieve`
- **No boilerplate** - Every line should earn its place
- **Inline when used once** - Extract only when reused or truly complex
- **Always ask: "Can this be simpler?"**
- **Idiomatic, functional style** - Pure functions, immutable data, composition
- **Decompose complexity** - Small, simple, reusable pieces composed via higher-order functions
- **Principle of least surprise** - Code should do what it looks like it does
- **Minimize concerns** - Keep each piece focused on one thing
- **Data-driven design** - Represent concepts as data, not objects
- **Think first, code second** - 1 unit of time [re]thinking is worth 100 units of time coding
- **Comments: sparse but pragmatic** - Minimize but don't exclude on religious grounds
- **Run the tests** - Always run the test suite before declaring something works

## Philosophy

**Think first, code second.** 1 unit of time [re]thinking is worth 100 units of time coding.

**Simplicity is the ultimate sophistication.** Always ask: can this be simpler?

**Agile development - build for today, not tomorrow:**
- We do look-ahead planning to understand direction
- We only code what we need right now
- Tomorrow may never come, or be completely different from expectations
- Don't build abstractions for hypothetical future use cases
- Wait for the third use case before extracting patterns
- Accept that some code will be thrown away - that's cheaper than maintaining unused abstractions

**YAGNI (You Aren't Gonna Need It):**
```clojure
;; Avoid - building for imagined future
(defn process-data [data options]
  (let [preprocessor (get options :preprocessor identity)
        postprocessor (get options :postprocessor identity)
        validator (get options :validator (constantly true))
        transformer (get options :transformer identity)]
    ;; ... complex generic processing
    ))

;; Good - solve today's problem
(defn process-data [data]
  (-> data
      validate
      transform))

;; When you actually need options (3rd use case), refactor then
```

**Minimize dependencies and code:**
- **One library when one will do** - If a single library solves the problem, don't add a second
- **Use stable, proven libraries** - Before writing code yourself, look for mature open-source solutions
- **Less code is better** - Every line of code is a liability that must be maintained, tested, and understood
- **Less code we maintain is even better** - Code in dependencies is maintained by others
- **The best solution uses the least code** - Simplicity and brevity are virtues

When evaluating solutions, prefer in this order:
1. Standard library solution (clojure.core, clojure.string, etc.)
2. Well-maintained, stable open-source library
3. Writing minimal custom code
4. Complex custom abstractions (avoid unless truly necessary)

**Examples:**
```clojure
;; Avoid - multiple libraries for the same thing
(ns my.app
  (:require [cheshire.core :as cheshire]
            [jsonista.core :as jsonista]
            [clojure.data.json :as json]))

;; Good - pick one and stick with it
(ns my.app
  (:require [clojure.data.json :as json]))

;; Avoid - reinventing the wheel
(defn my-custom-json-parser [s]
  ;; 200 lines of JSON parsing code
  ...)

;; Good - use proven library
(json/read-str s :key-fn keyword)

;; Avoid - writing code when there's a library
(defn fetch-url [url]
  ;; Custom HTTP client implementation
  ...)

;; Good - use clj-http
(http/get url)
```

**Ask yourself:**
- "Is there a standard library function for this?"
- "Is there a single, well-maintained library that solves this?"
- "Am I reinventing something that already exists?"
- "Can I delete this code and use a library instead?"

## Naming

**Symmetry and consistency:**
- Paired operations should have symmetric names: `open/close`, `start/stop`, `begin/end`
- Names should be similar length when paired
- Use verbs for actions, nouns for data

**Prefer short, clear names:**
- Anglo-Saxon over Latin/Greek: `get` not `retrieve`, `make` not `construct`
- Be specific but concise: `user-ctx` not `uc`, but also not `user-context-information-holder`
- Single-word when possible: `run`, `send`, `parse`, `validate`

**Examples:**
```clojure
;; Good
(defn start-server [config] ...)
(defn stop-server [server] ...)

;; Avoid
(defn initialize-server-instance [configuration-map] ...)
(defn terminate-server-instance [server-instance] ...)
```

## Namespace Requirements

**Prefer individual symbol referrals over namespace aliases:**
- Use `:refer [symbol1 symbol2 ...]` to explicitly import symbols
- Makes it clear which symbols are used from external namespaces
- Avoids namespace prefix clutter throughout the code
- Exception: logging namespaces can use `:as log` for brevity

**Rationale:**
- Individual referrals make dependencies explicit and visible at the top of the file
- Easier to track which external symbols are being used
- Prevents accidental reliance on undocumented namespace APIs
- Cleaner code without repeated namespace prefixes

**Handle symbol collisions with `:rename`:**
- When symbols from different namespaces collide, use `:rename` to disambiguate
- Choose descriptive rename that indicates source or purpose

**Examples:**
```clojure
;; Good - individual referrals
(ns my.app.core
  (:require [clojure.string :refer [join split trim]]
            [clojure.set :refer [difference union]]
            [clojure.data.json :as json]  ; json is commonly used with prefix
            [clojure.tools.logging :as log]))   ; Exception: logging

;; Avoid - namespace aliases (except logging and json)
(ns my.app.core
  (:require [clojure.string :as str]
            [clojure.set :as set]))

;; Good - handling collisions with :rename
(ns my.app.core
  (:require [clojure.set :refer [difference union]]
            [clojure.data :refer [diff]]
            [my.utils :refer [difference] :rename {difference my-difference}]))

;; Then in code:
(defn compare-sets [s1 s2]
  (difference s1 s2))           ; clojure.set/difference

(defn compare-data [d1 d2]
  (my-difference d1 d2))        ; my.utils/difference
```

**Common acceptable aliases:**
- `log` for clojure.tools.logging specifically
- `json` for clojure.data.json specifically
- `async` for clojure.core.async (idiomatic, many symbols)
- Otherwise prefer `:refer [...]`

## Performance: Avoid Reflection

**Always enable reflection warnings:**
```clojure
(set! *warn-on-reflection* true)
```

**Why avoid reflection:**
- Reflection is **slow** - up to 100x slower than direct method calls
- Adds up quickly in hot code paths
- Easy to fix with type hints
- Should be caught during development, not production

**Use type hints to eliminate reflection:**
```clojure
;; Bad - uses reflection (slow)
(defn get-length [s]
  (.length s))  ; Compiler doesn't know s is a String

;; Good - no reflection (fast)
(defn get-length [^String s]
  (.length s))

;; Common type hints:
^String          ; java.lang.String
^Long            ; java.lang.Long
^Boolean         ; java.lang.Boolean
^java.util.List  ; Fully qualified Java class
^objects         ; Array of Objects
^longs           ; Array of primitive longs
```

**Examples:**
```clojure
;; Bad - reflection warnings
(defn parse-number [s]
  (Integer/parseInt s))

;; Good - with type hint
(defn parse-number [^String s]
  (Integer/parseInt s))

;; Bad - reflection on Java interop
(defn get-matcher [s pattern]
  (let [m (re-matcher pattern s)]
    (.find m)))

;; Good - type hints on both
(defn get-matcher [^String s ^java.util.regex.Pattern pattern]
  (let [^java.util.regex.Matcher m (re-matcher pattern s)]
    (.find m)))

;; Multiple arguments
(defn substring [^String s ^long start ^long end]
  (.substring s start end))
```

**Where to add type hints:**
- Function parameters that call Java methods
- Let bindings that call Java methods
- Function return types when used by other functions needing hints

**Strategy:**
1. Enable `*warn-on-reflection*` in your REPL/build
2. Compile your code and watch for warnings
3. Add type hints where needed
4. Zero reflection warnings = zero reflection overhead

## Code Structure

**Avoid boilerplate:**
- No unnecessary abstractions
- No defensive programming unless there's a clear reason
- No "just in case" code

**Inline when appropriate:**
- If something is used exactly once, inline it unless it's getting complex
- Extract when you need to reuse or when complexity demands separation
- Trust your judgment on "too complex"

**Decompose complexity:**
- Break complex functions into smaller, reusable pieces
- Use higher-order functions for composition
- Each function should do one thing well

**Small and composable works better with LLM-assisted coding:**
- LLMs struggle with large, complex functions
- Small, focused functions are easier for LLMs to understand and modify
- Composition allows building complexity from simple, well-understood pieces
- When asking an LLM to help, show it one small function at a time
- Keep functions under ~20 lines when possible
- If you can't explain what a function does in one sentence, it's probably too complex

```clojure
;; Avoid - too much for LLM to handle well
(defn process-llm-request [request]
  (let [validated (if (valid? request)
                    request
                    (throw (ex-info "Invalid" {:request request})))
        schema (reduce merge base-schema 
                      (map :schema-extension interceptors))
        prompts (reduce (fn [p i] ((:pre-prompt i) p ctx)) 
                       base-prompts interceptors)
        response (try
                   (http/post llm-url 
                             {:body (json/write-str prompts)})
                   (catch Exception e
                     (log/error e)
                     (retry-request prompts)))
        parsed (json/read-str (:body response) :key-fn keyword)
        validated-response (m3/validate parsed schema)]
    (if (valid? validated-response)
      validated-response
      (retry-with-error validated-response))))

;; Good - small, composable pieces
(defn process-llm-request [request]
  (-> request
      validate-request
      compose-schema
      apply-interceptors
      send-to-llm
      parse-response
      validate-response))

;; Each function is small enough for LLM to help with individually
(defn validate-request [req] ...)
(defn compose-schema [req] ...)
(defn apply-interceptors [req] ...)
```

**Example:**
```clojure
;; Avoid deeply nested complexity
(defn process [data]
  (let [step1 (do-something data)
        step2 (if (valid? step1)
                (transform step1)
                (default-value))
        step3 (if (ready? step2)
                (finalize step2)
                step2)]
    step3))

;; Prefer composition
(defn process [data]
  (-> data
      do-something
      ensure-valid
      ensure-ready
      finalize))
```

## Functional Style

**Immutability by default:**
- Use immutable data structures
- Avoid `def` inside functions
- Avoid atoms/refs unless you truly need mutable state

**Pure functions when possible:**
- Separate pure logic from side effects
- Push side effects to the edges
- Makes testing and reasoning easier

**Data-driven design:**
- Represent domain concepts as data (maps, vectors)
- Use data literals over objects
- Let data flow through transformations

**Example:**
```clojure
;; Good - data-driven
(def interceptor
  {:name "memory"
   :pre-schema (fn [schema ctx] ...)
   :pre-prompt (fn [prompts ctx] ...)
   :post-response (fn [response ctx] ...)})

;; Avoid - object-oriented thinking
(defrecord Interceptor [name]
  InterceptorProtocol
  (pre-schema [this schema ctx] ...)
  (pre-prompt [this prompts ctx] ...)
  (post-response [this response ctx] ...))
```

## Principle of Least Surprise

**Code should do what it looks like it does:**
- Function name should clearly indicate behavior
- No hidden side effects in innocent-looking functions
- If it returns data, don't also mutate state

**Be explicit about side effects:**
```clojure
;; Good - name indicates side effect
(defn save-to-db! [record] ...)
(defn send-to-llm [request] ...)

;; Avoid - looks pure but has side effects
(defn process-user [user]
  (save-to-db user)  ; Surprise!
  (transform user))
```

## Separation of Concerns

**Keep each piece focused:**
- One function, one responsibility
- One namespace, one domain concept
- Composition happens at a higher level

**Example:**
```clojure
;; Avoid mixing concerns
(defn handle-request [request]
  (let [validated (validate request)
        result (call-llm validated)
        saved (save-to-db result)
        logged (log-event saved)]
    (send-response logged)))

;; Prefer separation
(defn handle-request [request]
  (-> request
      validate-request
      call-llm
      save-result
      log-event
      send-response))

;; Each function focused on one thing
(defn validate-request [req] ...)
(defn call-llm [req] ...)
(defn save-result [result] ...)
```

## Comments

**Comment sparingly but pragmatically:**
- Code should be self-documenting through clear names and structure
- Comment the "why", not the "what"
- Comment surprising decisions or non-obvious implications
- Don't exclude comments on religious grounds - if it helps, write it

**When to comment:**
```clojure
;; Good - explains non-obvious decision
(defn retry-with-backoff [f]
  ;; Exponential backoff prevents thundering herd when LLM is overloaded
  (loop [attempt 0 delay 100]
    ...))

;; Unnecessary - code is clear
(defn add [x y]
  ;; Add x and y together
  (+ x y))

;; Good - warns about gotcha
(defn parse-schema [json]
  ;; Note: M3 validation is memoized, so schema changes won't be 
  ;; reflected until the memoization cache is cleared
  (m3/validate json base-schema))
```

## Testing - CRITICAL

**Testing is not an afterthought. Tests define what "working" means.**

### The TDD Discipline

**Write the test FIRST, then write the code to make it pass:**

1. Write a failing test that describes the behavior you want
2. Run it - it should fail (if it passes, your test is wrong)
3. Write the minimum code to make it pass
4. Refactor if needed
5. Repeat

**Why TDD matters:**
- Tests become the specification
- Forces you to think about API before implementation
- Ensures code is testable by design
- Prevents the "tests that pass but don't test anything" trap

```clojure
;; WRONG - write code first, then write test to match
(defn add [x y] (+ x y))

(deftest add-test
  (is (= 3 (add 1 2))))  ; This test was written to pass, not to specify

;; RIGHT - write test first
(deftest add-test
  ;; Specification: add should sum two numbers
  (is (= 3 (add 1 2)))
  (is (= 0 (add -1 1)))
  (is (= -5 (add -2 -3))))

;; THEN write the code
(defn add [x y] (+ x y))
```

### Unit Tests Test Units

**A unit test tests ONE function with controlled inputs:**

- Test the actual production code, not a mock of it
- If you need to mock dependencies, inject them
- Each function should be testable in isolation

```clojure
;; BAD - "unit test" that mocks away the thing being tested
(deftest process-with-llm-test
  (let [mock-llm (fn [_] {:result "canned"})]
    (with-redefs [call-llm mock-llm]
      (is (= {:result "canned"} (process-with-llm "input"))))))
;; This tests NOTHING - you're testing your mock, not your code

;; GOOD - unit test of a pure function
(deftest parse-response-test
  (testing "extracts id from valid response"
    (is (= ["mc" "reviewer"] 
           (parse-response {"id" ["mc" "reviewer"] "data" "..."}))))
  (testing "returns nil for missing id"
    (is (nil? (parse-response {"data" "..."}))))
  (testing "handles malformed input"
    (is (thrown? Exception (parse-response "not a map")))))
```

### Integration Tests Test Integration

**If your system involves external services (LLMs, databases, APIs), you MUST have integration tests with real external services:**

- Mark integration tests clearly with `^:integration`
- Integration tests can be slow - that's okay
- Run them before declaring something "works"
- A mock test is NOT a substitute for an integration test

```clojure
;; This is a UNIT test - tests FSM machinery with mock actions
(deftest fsm-machinery-test
  (let [mock-action (fn [ctx _ _ _ event _ handler]
                      (handler ctx (process event)))]
    ...))

;; This is an INTEGRATION test - tests actual LLM behavior
(deftest ^:integration llm-response-format-test
  (testing "LLM produces valid JSON matching schema"
    (let [response (call-real-llm "test prompt" output-schema)]
      (is (valid? response output-schema))
      (is (contains? response "id")))))
```

### The Mocking Trap

**Mocks are useful for isolating units. Mocks are DANGEROUS when they replace integration testing.**

Ask yourself:
- "If I mock this, what am I actually testing?"
- "Could this code be broken in production while tests pass?"
- "Am I testing my mock or my code?"

```clojure
;; DANGEROUS - full system test with everything mocked
(deftest code-review-fsm-test
  (let [mock-llm-action (fn [_ _ _ _ event _ handler]
                          (handler ctx (canned-responses event)))]
    ;; This tests that canned responses flow through FSM
    ;; It does NOT test that LLMs produce valid responses
    ;; It does NOT test that prompts work
    ;; It does NOT test schema comprehension
    ;; THE FSM COULD BE COMPLETELY BROKEN IN PRODUCTION
    ...))

;; CORRECT - separate unit and integration tests
(deftest fsm-machinery-unit-test
  ;; Tests FSM state transitions with controlled inputs
  ...)

(deftest ^:integration llm-schema-comprehension-test
  ;; Tests that real LLMs understand and follow schemas
  ...)

(deftest ^:integration code-review-e2e-test
  ;; Tests the full flow with real LLMs
  ...)
```

### Production Code Exists for Production

**Code is written to work in production with diverse real-world inputs, not to make tests pass.**

- Tests verify production behavior, they don't define the happy path
- Consider edge cases, error conditions, malformed inputs
- If tests only cover the happy path, production will surprise you

```clojure
;; INSUFFICIENT - only tests the happy path
(deftest validate-schema-test
  (is (valid? (validate good-input))))

;; BETTER - tests real-world scenarios
(deftest validate-schema-test
  (testing "valid input passes"
    (is (valid? (validate good-input))))
  (testing "missing required field fails"
    (is (not (valid? (validate (dissoc good-input "required-field"))))))
  (testing "wrong type fails"  
    (is (not (valid? (validate (assoc good-input "count" "not-a-number"))))))
  (testing "extra fields in closed schema fails"
    (is (not (valid? (validate (assoc good-input "extra" "field"))))))
  (testing "nil input fails gracefully"
    (is (not (valid? (validate nil))))))
```

### Test Organization

**One `x_test.clj` file per `x.clj`:**
- Tests live next to the code they test
- Same namespace structure with `-test` suffix
- One `deftest` per function (or logical group)

```clojure
;; src/claij/fsm.clj contains:
;; - validate-event
;; - state-schema  
;; - start-fsm

;; test/claij/fsm_test.clj contains:
(deftest validate-event-test ...)
(deftest state-schema-test ...)
(deftest start-fsm-test ...)
```

### Test Tiers

| Tier | Speed | External Services | When to Run |
|------|-------|-------------------|-------------|
| Unit | Fast (<1s) | None | Every save, CI |
| Integration | Slow (seconds) | Yes (LLMs, DBs) | Before commit, CI |
| E2E | Very slow (minutes) | Full system | Before release |

```bash
# Run unit tests frequently
clj -X:test

# Run integration tests before committing
clj -X:test :includes [:integration]

# Run everything before release
clj -X:test :includes [:integration :e2e]
```

### The Checklist Before Declaring "It Works"

1. ✅ Unit tests pass
2. ✅ Integration tests pass (with REAL external services)
3. ✅ Manual smoke test of actual use case
4. ✅ Tests cover error cases, not just happy path
5. ✅ No mocks hiding broken integration points

**If you skip integration testing, you haven't tested your code.**

### Keep Tests Simple

```clojure
;; Good - clear and simple
(deftest validate-schema-test
  (is (valid? (validate simple-schema)))
  (is (not (valid? (validate broken-schema)))))

;; Avoid - overly elaborate
(deftest validate-schema-test
  (let [validator (make-validator)
        context (make-context)
        result (with-context context
                 (run-validator validator simple-schema))]
    (is (= :valid (:status result)))))
```

**CRITICAL: Never use Thread/sleep for async coordination:**

`Thread/sleep` is **never** a sufficient solution for async testing:
- **Non-deterministic** - May pass on fast machines, fail on slow ones
- **Race conditions** - Timing-dependent behavior is fragile
- **Slow tests** - Must wait for worst-case timing
- **False confidence** - Tests might pass by luck, fail in CI/production

**Always use proper thread coordination primitives:**
- Promises for one-time events
- CountDownLatches for synchronization points  
- Core.async channels for message passing
- Atoms with waiting conditions

**Examples:**

```clojure
;; ❌ BAD - Non-deterministic, slow, fragile
(deftest async-test
  (let [result (atom nil)]
    (future (reset! result (compute-something)))
    (Thread/sleep 1000)  ; Hope it's done by now!
    (is (= :expected @result))))

;; ✅ GOOD - Deterministic, fast, reliable
(deftest async-test
  (let [result-promise (promise)]
    (future (deliver result-promise (compute-something)))
    (is (= :expected (deref result-promise 5000 :timeout)))))

;; ❌ BAD - FSM test with sleep
(deftest fsm-test
  (start-fsm fsm)
  (Thread/sleep 5000)  ; Guessing how long it takes
  (is (reached-end-state?)))

;; ✅ GOOD - FSM test with latch
(deftest fsm-test
  (let [done (promise)]
    (start-fsm fsm {:on-complete #(deliver done true)})
    (is (deref done 10000 false))))

;; ✅ GOOD - Using core.async
(deftest channel-test
  (let [result-chan (chan)]
    (go
      (let [result (<! (async-computation))]
        (>! result-chan result)))
    (is (= :expected (<!! result-chan)))))

;; ✅ GOOD - Using CountDownLatch for multiple events
(deftest multi-event-test
  (let [latch (java.util.concurrent.CountDownLatch. 3)]
    (doseq [_ (range 3)]
      (future 
        (process-event)
        (.countDown latch)))
    (.await latch 5 java.util.concurrent.TimeUnit/SECONDS)
    (is (all-events-processed?))))
```

**Design async code to be testable:**
- Make async boundaries explicit (promises, channels, callbacks)
- Don't hide async work inside functions
- Provide hooks for test coordination
- Document async behavior clearly

**FSM testing specifically:**
- FSMs should provide completion signals (promises/latches)
- Shared start/end actions can manage coordination
- Tests await FSM completion, don't guess timing
- See `doc/MCP.md` for planned latch-based FSM testing infrastructure

**Rule of thumb:**
If your test has `Thread/sleep`, you have a bug waiting to happen in CI or production. Always use proper synchronization primitives.

## Error Handling

**Fail fast and explicitly:**
- Validate at boundaries (function entry, API calls)
- Use `ex-info` for rich error context
- Don't catch exceptions unless you can handle them

**Example:**
```clojure
;; Good
(defn process-request [request]
  (when-not (valid-request? request)
    (throw (ex-info "Invalid request" 
                   {:request request 
                    :reason :invalid-schema})))
  (do-processing request))

;; Avoid swallowing errors
(defn process-request [request]
  (try
    (do-processing request)
    (catch Exception e
      nil)))  ; What went wrong? No idea!
```

## Performance

**Optimize for clarity first, performance second:**
- Write clear code first
- Profile before optimizing
- Document why when you optimize for performance
- Don't sacrifice readability for micro-optimizations

**When optimization matters:**
```clojure
;; Hot path - justified optimization with comment
(defn validate-responses [responses]
  ;; Using transducer to avoid intermediate collections
  ;; This is called 1000s of times per second
  (into [] (comp (map parse) (filter valid?)) responses))
```

## Clojure-Specific

**Embrace the language:**
- Use threading macros (`->`, `->>`, `some->`) for pipelines
- Use destructuring to make intent clear - prefer explicit named bindings over `:keys` and `:strs`
- Favor destructuring over `get`/`get-in` when possible - achieves multiple bindings in one line
- Leverage sequence abstractions
- Use `let` for intermediate bindings with clear names

**Destructuring best practices:**
```clojure
;; Prefer explicit destructuring over get/get-in
;; Good - clear, concise, all bindings visible
(let [{ctx :context trail :trail} msg
      {ic :input oc :output} ctx]
  ...)

;; Avoid - verbose, scattered bindings
(let [ctx (get msg :context)
      trail (get msg :trail)
      ic (get-in msg [:context :input])
      oc (get-in msg [:context :output])]
  ...)

;; Good - explicit named bindings (preferred over :keys/:strs)
(defn process [{provider :provider model :model}]
  (call-llm provider model))

;; Acceptable but less preferred
(defn process [{:keys [provider model]}]
  (call-llm provider model))

;; Good - nested destructuring
(defn handle-message [{{ic :input oc :output} :context 
                       [{role :role content :content}] :trail}]
  ...)

;; Avoid - multiple get-in calls
(defn handle-message [msg]
  (let [ic (get-in msg [:context :input])
        oc (get-in msg [:context :output])
        role (get-in msg [:trail 0 :role])
        content (get-in msg [:trail 0 :content])]
    ...))
```

**Leverage Clojure's concurrency primitives:**
- Persistent collections eliminate whole classes of concurrency bugs
- No defensive copying, no accidental sharing of mutable state
- Atoms for coordinated, synchronous state updates
- core.async for clear async patterns
- These primitives make concurrent code easier to reason about - for humans and LLMs

**Concurrency done simply:**
```clojure
;; Avoid - raw threads and locks (hard for humans and LLMs)
(def state (java.util.concurrent.ConcurrentHashMap.))
(def lock (Object.))

(defn update-state [key value]
  (locking lock
    (let [old (.get state key)
          new (transform old value)]
      (.put state key new))))

;; Good - Clojure primitives (clear and simple)
(def state (atom {}))

(defn update-state [key value]
  (swap! state update key transform value))

;; Or with core.async for async work
(def work-channel (chan 100))

(defn process-work []
  (go-loop []
    (when-let [item (<! work-channel)]
      (process item)
      (recur))))

;; LLMs can easily understand and modify these patterns
```

**Why this matters for LLM-assisted coding:**
- LLMs trained on Clojure code understand these idioms
- Atoms and persistent collections are well-documented patterns
- Much clearer than reasoning about locks, mutexes, volatile
- When you ask an LLM "make this concurrent", it can suggest `atom` or `core.async`
- When debugging concurrency issues, LLMs can spot misuse of these primitives

**Multi-LLM coordination example:**
```clojure
;; Shared state across LLM conversations
(def conversation-state 
  (atom {:memory {}
         :active-interceptors []
         :schema base-schema}))

;; Each LLM interaction updates atomically
(defn add-to-memory [llm-id summary]
  (swap! conversation-state 
         update-in [:memory llm-id] 
         conj summary))

;; No locks, no races, clear intent
```

**Idiomatic patterns:**
```clojure
;; Good - idiomatic Clojure
(defn process-users [users]
  (->> users
       (filter active?)
       (map enrich-profile)
       (group-by :region)))

;; Avoid - imperative style
(defn process-users [users]
  (let [active (filter active? users)
        enriched (map enrich-profile active)
        grouped (group-by :region enriched)]
    grouped))
```

## Documentation

**Let the code speak, but help it:**
- Docstrings for public functions, especially at namespace boundaries
- Examples in docstrings for complex functions
- README/design docs for architecture and patterns
- Keep docs close to code (not in separate wiki)

**Good docstring:**
```clojure
(defn validate-with-retry
  "Validates response against schema with retry loop.
  
  Retries up to max-retries times if validation fails, sending
  the validation error back to the LLM each time.
  
  Returns validated response or throws after max retries.
  
  Example:
    (validate-with-retry response schema {:max-retries 3})"
  [response schema opts]
  ...)
```

## Making Design Decisions

**When choosing between multiple solution approaches:**

Always consider future directions and evolution of the code:
- Think beyond the immediate requirement to likely next steps
- Choose solutions that don't paint you into a corner
- Prefer approaches that enable future flexibility

**Example decision process:**

When choosing between lazy initialization approaches:
1. List the options (delay, atom with explicit lifecycle, lazy in action)
2. Consider immediate needs (testing, clean startup)
3. **Consider future needs** (restart capability, health checks, monitoring)
4. Choose the option that best supports both present and future
5. Document why in comments or commit message

**Document your reasoning:**
```clojure
;; We chose Option 1 (explicit lifecycle) over delays because:
;; 1. Immediate: Allows testing with minimal services
;; 2. Future: Enables service restart and health monitoring
;; 3. Trade-off: Slightly more code, but clearer semantics

(defonce mcp-services (atom {}))

(defn start-service! [id config]
  ;; Can be called multiple times to restart
  ...)
```

**Questions to ask:**
- "Which approach better supports adding X feature later?"
- "Which approach is easier to extend without breaking?"
- "Which approach gives us more options down the road?"
- "What would we have to throw away if requirements change?"

**Balance:**
- Don't over-engineer for hypothetical futures (YAGNI still applies)
- DO consider one or two likely next steps
- DO choose extensible patterns over limiting ones
- DON'T build features you don't need yet
- DO build in a way that makes those features easier when needed

## What to Avoid

**Anti-patterns:**
- ❌ Premature abstraction (don't create abstractions until you have 3+ use cases)
- ❌ Deep nesting (use threading macros or extract functions)
- ❌ Mutable state without clear justification
- ❌ Global state (except for truly global config)
- ❌ Clever code (if you're proud of how clever it is, it's probably too clever)
- ❌ Copy-paste (extract and reuse instead)
- ❌ Defensive programming (let it fail explicitly rather than paper over issues)

## CLAIJ-Specific

**Interceptor pattern:**
- Interceptors should be pure data
- Functions in interceptors should be small and focused
- Compose interceptors, don't build mega-interceptors

**Schema handling:**
- Base schema minimal, extend through composition
- Validate early and often (with M3)
- Schema is self-documenting through descriptions

**LLM interactions:**
- Always validate responses
- Retry with clear error messages
- Log failures for debugging
- Tag integration tests clearly

## Persistent Planning for Complex Tasks

**For any significant work, use persistent ratcheted execution:**

When working on complex features that span multiple steps or sessions, maintain state in a scratchpad or similar persistent structure. This prevents:
- Losing progress when conversations stall or get interrupted
- Having to rebuild context from scratch each session
- Repeating work that was already completed
- Forgetting important requirements or edge cases

**Structure for complex tasks:**

```clojure
{:goal "Clear description of what we're building"
 :requirements [{:id 1 :task "First requirement" :done false}
                {:id 2 :task "Second requirement" :done true}
                ...]
 :current-status "Where we are right now"
 :learnings ["Things we discovered along the way"]
 :blockers ["Issues preventing progress"]}
```

**Ratcheted execution means:**
1. Break work into discrete, checkpointable steps
2. Mark each step complete when verified (not just attempted)
3. Record learnings and discoveries as you go
4. Never lose ground - if interrupted, resume from last checkpoint
5. Update status frequently so context is always current

**When to use persistent planning:**
- Multi-step implementations
- Integration with external systems (LLMs, APIs)
- Debugging complex issues
- Any work that might span multiple sessions
- Exploratory work where discoveries change the approach

**Example workflow:**
```
1. Create task list with all requirements
2. For each task:
   a. Attempt implementation
   b. Verify it works (tests, REPL validation)
   c. Mark as done ONLY when verified
   d. Record any learnings
3. If interrupted, resume from last completed task
4. Keep notes on what's still pending
```

**This discipline is especially important for LLM-assisted development** where:
- Context windows have limits
- Conversations may be compacted or interrupted
- The same issue might be worked on across multiple sessions
- Multiple approaches may need to be tried

## Git Best Practices

**Use `git mv` when moving or renaming files:**
- Preserves file history and enables Git to track renames
- Makes `git log --follow` work correctly
- Keeps blame information intact
- Enables proper diff display showing moves vs. delete+create

```bash
# Good - Git tracks the move
git mv old/path/file.clj new/path/file.clj

# Bad - Git sees a delete and a new file, history is broken
mv old/path/file.clj new/path/file.clj
git add .
```

**This matters because:**
- Code archaeology (`git blame`, `git log`) requires history continuity
- PR reviews are cleaner when Git knows it's a move
- Merges work better when Git understands file relationships

## When In Doubt

1. **Ask: "Can this be simpler?"**
2. **Ask: "What would surprise someone reading this?"**
3. **Ask: "Am I solving a real problem or an imagined one?"**
4. **Ask: "Will I understand this in 6 months?"**

If the answer concerns you, refactor.

---

**Remember:** These are guidelines, not laws. Use judgment. The goal is maintainable, understandable, robust code that solves real problems.

**Last updated:** 2025-12-06
