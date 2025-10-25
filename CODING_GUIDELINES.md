# CLAIJ Coding Guidelines

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
            [taoensso.timbre :as log]))   ; Exception: logging

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
- `log` for logging (taoensso.timbre, clojure.tools.logging, etc.)
- `json` for JSON libraries (clojure.data.json, cheshire, etc.)
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
                             {:body (json/encode prompts)})
                   (catch Exception e
                     (log/error e)
                     (retry-request prompts)))
        parsed (json/decode (:body response))
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

## Testing

**Test behavior, not implementation:**
- Focus on what the code does, not how it does it
- Use property-based testing where appropriate
- Integration tests only when necessary (and mark them clearly)

**Keep tests simple:**
```clojure
;; Good - clear and simple
(deftest test-validate-schema
  (is (valid? (validate simple-schema)))
  (is (not (valid? (validate broken-schema)))))

;; Avoid - overly elaborate
(deftest test-validate-schema
  (let [validator (make-validator)
        context (make-context)
        result (with-context context
                 (run-validator validator simple-schema))]
    (is (= :valid (:status result)))))
```

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
- Use destructuring to make intent clear
- Leverage sequence abstractions
- Use `let` for intermediate bindings with clear names

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

## When In Doubt

1. **Ask: "Can this be simpler?"**
2. **Ask: "What would surprise someone reading this?"**
3. **Ask: "Am I solving a real problem or an imagined one?"**
4. **Ask: "Will I understand this in 6 months?"**

If the answer concerns you, refactor.

---

**Remember:** These are guidelines, not laws. Use judgment. The goal is maintainable, understandable, robust code that solves real problems.

**Last updated:** 2025-10-24
