# Structured Responses with Interceptor Architecture

## Overview

A comprehensive redesign of LLM interaction in CLAIJ based on validated JSON schemas, composable interceptors, and robust error handling. This architecture provides a foundation for advanced features like shared memory, MCP integration, FSM-driven conversations, and mid-conversation protocol evolution.

## Motivation

**Current Problems:**
- Unstructured text responses are hard to parse reliably
- No validation of response format
- Extending functionality (e.g., adding memory) requires invasive changes
- Different LLMs behave inconsistently
- Hard to debug what went wrong

**Solution:**
- Every response conforms to a JSON schema
- Validation with immediate retry on failure
- Composable interceptors for extending functionality
- Backend adapters for provider-specific handling
- Self-documenting via JSON Schema descriptions

## Core Concepts

### 1. Response Schema

Every LLM response must conform to a JSON schema. The base schema is minimal:

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["answer", "state"],
  "properties": {
    "answer": {
      "type": "string",
      "description": "Your response to the user"
    },
    "state": {
      "type": "string", 
      "description": "Next FSM state to transition to"
    }
  }
}
```

**Key Design Decisions:**
- Start minimal, grow through composition
- Schema itself is self-documenting (via `description` fields)
- Required fields enforce contract
- Extensions add to `properties` using JSON Schema composition

### 2. Interceptors

Interceptors are the primary extension mechanism. Each interceptor has three optional hooks:

```clojure
{:name "summary-interceptor"
 :pre-schema (fn [base-schema ctx] 
               ;; Add fields to schema
               ;; Return modified schema
               )
 :pre-prompt (fn [prompts ctx]
               ;; Modify system/user prompts
               ;; Return modified prompts
               )
 :post-response (fn [response ctx]
                  ;; Extract data, update state, make decisions
                  ;; Return modified context
                  )}
```

**Execution Flow:**
1. **Request Time:** 
   - All `pre-schema` functions compose the final schema
   - Schema is validated (M3)
   - All `pre-prompt` functions modify prompts
   - Request sent to LLM

2. **Response Time:**
   - Response validated against composed schema (M3)
   - If invalid, retry with validation error (up to 3 times)
   - If valid, all `post-response` functions interpret/act

**Example Interceptors:**
- `memory-interceptor` - Adds `summary` field to schema, stores summaries
- `tool-interceptor` - Adds `tools` array to schema, executes tool calls
- `mcp-interceptor` - Adds MCP service documentation to prompts
- `repl-interceptor` - Adds `eval` field for Clojure evaluation
- `fsm-interceptor` - Routes based on `state` field

### 3. Validation Strategy

**Schema Validation (M3):**
- Composed schema validated before each request (memoized)
- If schema invalid, extension that broke it is rolled back
- Audit trail maintained: who added what, when

**Response Validation (M3):**
- Every response validated immediately after unmarshaling
- Validation errors are precise (path to problem, expected vs actual)

**Validation-Retry Loop:**

```clojure
(defn send-with-retry [request llm-fn max-retries]
  (loop [attempt 0
         last-error nil]
    (let [response (llm-fn request)
          validation (validate-response response schema)]
      (if (valid? validation)
        response
        (if (< attempt max-retries)
          (recur (inc attempt)
                 (:error validation)
                 (retry-prompt request response validation))
          (throw (ex-info "Max validation retries exceeded"
                         {:attempts (inc attempt)
                          :last-error last-error
                          :response response})))))))
```

**Retry Prompt Structure:**

```
Your previous response failed schema validation.

ORIGINAL REQUEST: <full original request>
YOUR RESPONSE: <the invalid response>
VALIDATION ERROR: <M3 error with path>

Please provide a corrected response that conforms to the schema.
Focus specifically on fixing: <extract specific field/type issue>
```

**Configuration:**
```clojure
(def max-validation-retries 
  "Maximum number of times to retry on validation failure"
  3)
```

### 4. Backend Adapters

Different LLM providers have different APIs. Adapters translate between our neutral format and provider-specific formats.

```clojure
{:name "openrouter"
 :supports? (fn [capability] 
              ;; Returns true if backend supports capability
              )
 :transform-request (fn [neutral-request]
                      ;; Convert to provider format
                      )
 :transform-response (fn [provider-response]
                       ;; Convert to neutral format
                       )}
```

**Neutral Format:**
- Use OpenRouter format as baseline
- Schema included in system prompt
- Extensions modify system prompt

**Provider Optimizations:**
- Some providers support structured output natively
- Adapters can use provider-specific features when available
- Fallback to prompt-based schema communication

### 5. Schema Extensions

Interceptors can extend the schema dynamically. Extensions are:
- Validated before activation
- Audited (who, when, what)
- Global (all LLMs see same schema)

```clojure
{:extension-id "summary-v1"
 :added-by :memory-interceptor
 :added-at #inst "2025-10-24T..."
 :schema-delta {:properties {:summary {:type "string"
                                       :description "Brief summary for memory"}}}}
```

**Future Extension Mechanism:**
Framework supports LLMs proposing extensions mid-conversation:
1. LLM proposes extension in response
2. Extension goes through vote sub-FSM
3. If approved, extension validated and activated
4. All subsequent requests use extended schema

*(Exact protocol for LLM-proposed extensions to be designed later)*

## Architecture

### Namespace Structure

```clojure
claij.llm.schema          ;; Schema definition, composition, validation
claij.llm.interceptors    ;; Core interceptor pattern + built-in interceptors
claij.llm.backends        ;; Backend adapters (openrouter, anthropic, etc.)
claij.llm.validation      ;; M3 integration for validation
```

### Data Flow

```
[User Input]
    ↓
[Context] → [Interceptors: pre-schema] → [Composed Schema]
    ↓                                            ↓
[Validate Schema (M3, memoized)] ←──────────────┘
    ↓
[Interceptors: pre-prompt] → [Final Prompts]
    ↓
[Backend Adapter: transform-request]
    ↓
[LLM API Call]
    ↓
[Backend Adapter: transform-response]
    ↓
[Unmarshal JSON]
    ↓
[Validate Response (M3)] ──┐
    │ (invalid)             │ (valid)
    ↓                       ↓
[Retry Prompt] ←──→ [Interceptors: post-response]
(max 3 attempts)            ↓
    ↓                  [Updated Context]
[Error]                     ↓
                        [Result]
```

## Implementation Plan

### Phase 1: Foundation (Develop in Isolation)
- [ ] Define base schema
- [ ] Implement schema validation (M3 integration)
- [ ] Create interceptor protocol
- [ ] Build validation-retry loop
- [ ] Unit tests for each component

### Phase 2: Basic Interceptors
- [ ] Memory interceptor (summary field)
- [ ] FSM interceptor (state transitions)
- [ ] Logging interceptor
- [ ] Unit tests for interceptors

### Phase 3: Backend Adapters
- [ ] OpenRouter adapter (baseline)
- [ ] Anthropic adapter (if needed)
- [ ] OpenAI adapter (if needed)
- [ ] Adapter tests

### Phase 4: Integration & Validation (Critical Gateway)
- [ ] **Memory interceptor integration tests across ALL target LLMs**
  - [ ] Test with GPT-4 (currently failing - must fix)
  - [ ] Test with Claude Sonnet
  - [ ] Test with Grok-2
  - [ ] Test with Gemini Pro
  - **Gate:** Must pass on all LLMs before proceeding to Phase 5
- [ ] Test with multiple interceptors active simultaneously
- [ ] Test validation-retry loop with actual LLM failures
- [ ] Document any LLM-specific quirks discovered
- [ ] Update compatibility matrix

**Why this phase is critical:** 
The memory interceptor is our proof that the architecture works. If we can't get consistent memory behavior across LLMs (especially GPT), the whole structured response system hasn't solved the problem. Don't proceed until this works reliably.

### Phase 5: Migration
- [ ] Migrate existing code to new architecture
- [ ] Remove old transducer-based code
- [ ] Update documentation

**Development Strategy:**
- Build in isolation with extensive unit tests
- Validate each component thoroughly
- Only migrate old code when new architecture is fully working
- No backwards compatibility concerns (no production code)

## Use Case Examples

### Example 1: Memory Strategy

```clojure
(def memory-interceptor
  {:name "memory"
   :pre-schema (fn [schema ctx]
                 (assoc-in schema [:properties :summary]
                          {:type "string"
                           :description "Brief summary of key facts from this interaction"}))
   :pre-prompt (fn [prompts ctx]
                 (update prompts :system str 
                         "\n\nPrevious context: " (:memory ctx)))
   :post-response (fn [response ctx]
                    (assoc ctx :memory (:summary response)))})
```

### Example 2: MCP Integration

```clojure
(def mcp-interceptor
  {:name "mcp"
   :pre-schema (fn [schema ctx]
                 (assoc-in schema [:properties :mcp-calls]
                          {:type "array"
                           :items {:type "object"
                                   :required ["service" "method" "args"]
                                   :properties {...}}}))
   :pre-prompt (fn [prompts ctx]
                 (update prompts :system str
                         "\n\nAvailable MCP services:\n" 
                         (format-mcp-docs (:mcp-services ctx))))
   :post-response (fn [response ctx]
                    (let [results (execute-mcp-calls (:mcp-calls response))]
                      (assoc ctx :mcp-results results)))})
```

**Design Note: Multi-Project MCP Service Filtering**

When implementing the MCP interceptor, we need a strategy for handling multiple projects with different MCP services:

**Option 1: Project-Aware Service Filtering**
- Context tracks current project: `(:project ctx)`
- Filter MCP services to only those relevant to current project
- Prevents LLM confusion from seeing irrelevant services
- Requires clear project boundaries

**Option 2: Hat-Based Project Prompting**
- Each project gets a "hat" (specialized prompt/persona)
- Hat includes instructions: "You are working on Project X, only use these MCP services: [...]"
- LLM learns to self-filter based on project context
- More flexible but relies on LLM following instructions

**Option 3: Hybrid Approach**
- Hard filter to project-relevant services (Option 1)
- Add hat prompt for additional context (Option 2)
- Best of both worlds: technical enforcement + semantic guidance

**Option 4: Dynamic Service Discovery**
- LLM queries available services based on task context
- Services self-describe their project scope
- More complex but potentially more flexible

**Decision pending:** Will be determined during MCP interceptor implementation based on actual multi-project usage patterns.

### Example 3: REPL Evaluation

```clojure
(def repl-interceptor
  {:name "repl"
   :pre-schema (fn [schema ctx]
                 (assoc-in schema [:properties :eval]
                          {:type "string"
                           :description "Clojure code to evaluate in REPL"}))
   :post-response (fn [response ctx]
                    (if-let [code (:eval response)]
                      (let [result (eval-in-repl code)]
                        (assoc ctx :repl-result result))
                      ctx))})
```

### Example 4: Tool Calling (DSL)

```clojure
(def tool-interceptor
  {:name "tools"
   :pre-schema (fn [schema ctx]
                 (assoc-in schema [:properties :tools]
                          {:type "array"
                           :items {:type "object"
                                   :required ["name" "args"]
                                   :properties {...}}}))
   :pre-prompt (fn [prompts ctx]
                 (update prompts :system str
                         "\n\nAvailable tools:\n"
                         (format-dsl-docs (:dsl ctx))))
   :post-response (fn [response ctx]
                    (let [results (execute-tools (:tools response) (:dsl ctx))]
                      (assoc ctx :tool-results results)))})
```

## Thought Experiments

### Swappable Memory Strategies

The interceptor architecture naturally supports different memory approaches:

**Strategy 1: Summary-based (Current)**
- `summary` field in each response
- Concatenate summaries in system prompt
- Simple, low token cost

**Strategy 2: Vector Database**
- `summary` + `embedding` fields
- Store in vector DB
- Retrieve relevant memories via similarity search
- Add to prompts

**Strategy 3: Hierarchical Summaries**
- `summary` for immediate context
- `long-term-insights` for patterns across many exchanges
- Multi-level retrieval

**Switching Strategy:**
```clojure
;; Just swap the interceptor!
(def active-interceptors
  [fsm-interceptor
   summary-memory-interceptor  ; or vector-memory-interceptor
   mcp-interceptor])
```

### Multi-LLM Coordination

Different LLMs can have different interceptor stacks:

```clojure
(def llm-configs
  {:gpt-4 {:backend :openrouter
           :interceptors [memory-interceptor fsm-interceptor]}
   :claude {:backend :anthropic
            :interceptors [memory-interceptor mcp-interceptor fsm-interceptor]}
   :specialist {:backend :openrouter
                :interceptors [repl-interceptor tool-interceptor]}})
```

All see the same base schema, but different pre-prompt modifications.

### Future: LLM-Driven Schema Evolution

When LLMs can propose extensions:

```clojure
;; LLM response includes meta-request
{:answer "I think it would help if I could indicate my confidence"
 :state "propose-extension"
 :proposed-extension {:properties {:confidence {:type "number" 
                                                 :minimum 0 
                                                 :maximum 1}}}}

;; Extension enters vote sub-FSM
;; If approved, all LLMs get extended schema in next request
```

This enables organic protocol evolution driven by the LLMs themselves.

## Advanced: Meta-Debugging Sub-FSM

**Future Enhancement:** When validation repeatedly fails for one LLM, use another LLM to diagnose:

```clojure
(defn meta-debug [failed-llm working-llm request failed-response validation-error]
  (let [diagnostic-prompt (format
                           "LLM '%s' failed to produce valid output.
                           
                           REQUEST: %s
                           FAILED RESPONSE: %s
                           VALIDATION ERROR: %s
                           
                           Analyze why this LLM is failing and suggest how to fix the prompt or schema."
                           failed-llm request failed-response validation-error)]
    (call-llm working-llm diagnostic-prompt)))
```

Could be a sub-FSM:
- States: `detect-repeated-failure` → `select-diagnostic-llm` → `analyze` → `suggest-fix` → `apply-fix` → `verify`
- Requires at least one LLM that's working with current schema
- Could suggest prompt improvements, schema simplifications, or LLM-specific handling

## Testing Strategy

### Unit Tests
- Schema composition with multiple interceptors
- M3 validation (valid and invalid cases)
- Interceptor execution order
- Backend adapter transformations
- Retry loop with mocked LLM responses

### Interceptor Integration Tests (Critical)

**Key Insight:** Interceptors contain prompts that can only be validated by sending them to real LLMs. Each interceptor must have integration tests that verify it works correctly across our representative LLM collection.

**Test Structure per Interceptor:**

```clojure
(deftest test-memory-interceptor-integration
  (testing "memory interceptor with GPT-4"
    (let [ctx (-> (make-context)
                  (add-interceptor memory-interceptor))
          response-1 (send-llm ctx "Remember: my favorite color is blue")
          response-2 (send-llm ctx "What's my favorite color?")]
      
      ;; Verify schema compliance
      (is (valid-schema? response-1))
      (is (valid-schema? response-2))
      
      ;; Verify memory functionality
      (is (contains? response-1 :summary))
      (is (string/includes? (:answer response-2) "blue"))))
  
  (testing "memory interceptor with Claude"
    ;; Same test with Claude
    ...)
  
  (testing "memory interceptor with Grok"
    ;; Same test with Grok
    ...))
```

**First Priority: Summary-Memory Interceptor**
- This is the one currently failing on GPT
- Must work across all target LLMs before proceeding
- Tests verify: 
  - LLM provides valid `summary` field
  - Summaries actually capture key information
  - Memory persists across multiple exchanges
  - LLM uses provided memory in responses

**Representative LLM Collection:**
```clojure
(def target-llms
  [:gpt-4          ;; OpenAI flagship
   :claude-sonnet  ;; Anthropic
   :grok-2         ;; xAI  
   :gemini-pro     ;; Google
   ;; Add more as needed
   ])
```

**LLM Qualification Process:**

When introducing a new LLM to CLAIJ:
1. Run full interceptor integration test suite against it
2. Document any failures or quirks
3. Only add to `target-llms` if it passes all tests
4. If it fails, either:
   - Fix the interceptor prompts to work with that LLM
   - Add LLM-specific handling in the interceptor
   - Document as "unsupported" with reasons

**Compatibility Matrix:**

Maintain a matrix showing which interceptors work with which LLMs:

```
                GPT-4  Claude  Grok  Gemini
memory-int       ✓      ✓      ✓      ?
mcp-int          ✓      ✓      ?      ?
repl-int         ?      ?      ?      ?
tool-int         ?      ?      ?      ?
```

Update as we develop and test each interceptor.

**Test Execution:**

**⚠️ IMPORTANT: Integration tests are NEVER run automatically ⚠️**

Integration tests make real API calls that cost money. They must be:
- Run manually and deliberately
- Excluded from default test runs
- Excluded from CI/CD pipelines
- Clearly separated from unit tests

```bash
# Default test run - NO integration tests
lein test

# Integration tests - MANUAL ONLY, issues warning
lein test :integration        # WARNING: Will consume API tokens!

# Run integration tests for specific interceptor
lein test :integration :memory

# Run integration tests for specific LLM  
lein test :integration :gpt-4

# Run full compatibility matrix (EXPENSIVE!)
lein test :integration :all   # WARNING: Multiple LLM calls!
```

**Test Implementation:**

Each integration test should:
1. **Issue a warning before running**:
   ```clojure
   (deftest ^:integration test-memory-interceptor-gpt4
     (println "\n⚠️  WARNING: Running integration test against GPT-4")
     (println "This will consume API tokens and cost money.")
     (println "Press ENTER to continue or Ctrl-C to abort...")
     (read-line)
     
     ;; Test implementation...
     )
   ```

2. **Be tagged with `:integration` metadata** so they can be excluded:
   ```clojure
   (deftest ^:integration test-name ...)
   ```

3. **Log token usage** after completion:
   ```clojure
   (println (format "✓ Test completed. Tokens used: ~%d" estimated-tokens))
   ```

**Test Configuration:**

```clojure
;; In test config
(def integration-test-config
  {:require-confirmation? true  ; Always ask before running
   :log-costs? true             ; Log estimated costs
   :max-retries 1               ; Limit retries in tests
   :timeout-ms 30000})          ; Fail fast if LLM hangs
```

**Cost Considerations:**
- Integration tests make real API calls = costs money
- Run selectively during development
- Full matrix run before releases
- CI can run against cheaper/faster models as smoke tests
- Full suite run on-demand or nightly

### Property-Based Tests
- Generate random schemas → validate
- Generate responses from schema → validate (should always pass)
- Compose random interceptor combinations → ensure no conflicts

### Regression Tests
- Capture real failure cases as test cases
- When validation-retry loop is triggered in production, log for test suite
- Build corpus of "tricky" prompts that caused issues

## Open Questions

1. **Schema Complexity:** How do we prevent the schema from becoming too complex as extensions accumulate? 
   - Could have schema "compaction" that merges similar fields
   - Could require extensions to justify token cost

2. **Interceptor Ordering:** Does order matter? 
   - For `pre-schema`: probably composable (merging)
   - For `pre-prompt`: might matter (later interceptors see earlier modifications)
   - For `post-response`: might matter (order of side effects)
   - Start with explicit ordering, optimize if needed

3. **Error Propagation:** How do we handle interceptor failures?
   - Wrap in try-catch?
   - Skip failed interceptor and log?
   - Fail entire request?

4. **Performance:** Schema validation on every response - too expensive?
   - M3 should be fast for small schemas
   - Memoize schema compilation
   - Profile and optimize if needed

## Success Criteria

The architecture is successful if:
- ✓ Adding new functionality (memory, tools, MCP) is just adding an interceptor
- ✓ Switching memory strategies is just swapping interceptors
- ✓ **Memory interceptor works consistently across all target LLMs (especially GPT-4)**
- ✓ LLMs produce valid responses >95% of the time on first try
- ✓ Validation failures are debuggable (clear error messages)
- ✓ Schema extensions don't break existing interceptors
- ✓ Multiple LLMs can interoperate with same schema
- ✓ Code is testable (pure functions, dependency injection)
- ✓ Each interceptor passes integration tests on representative LLM collection

**Critical Success Indicator:** If GPT-4 can reliably maintain memory across exchanges using the memory interceptor, we've solved the problem that motivated this redesign.

## References

- [M3 Validation](https://github.com/your-repo/m3) - Schema validation via M3
- [JSON Schema](https://json-schema.org/) - Schema definition standard
- [Ring Middleware](https://github.com/ring-clojure/ring/wiki/Concepts#middleware) - Inspiration for interceptor pattern

---

**Status:** Design Document  
**Next Steps:** Begin Phase 1 implementation  
**Last Updated:** 2025-10-24
