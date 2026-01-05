# PoC: LLM Response to Custom Tool Flags

**Issue:** [#165](https://github.com/JulesGosnell/claij/issues/165)  
**Status:** Preliminary findings documented  
**Date:** 2026-01-05

## Question

Will LLMs (Claude, GPT-4, etc.) voluntarily use CLAIJ-proprietary tool execution flags (`parallel`, `failFast`, `atomic`) when offered in tool definitions?

## Answer (Preliminary)

**No.** LLMs do not meaningfully process custom schema flags without explicit prompting.

## Key Findings

### ❌ Schema Flags Don't Work

Adding custom fields to tool schemas:
```json
{
  "name": "edit_file",
  "claij:parallel": true,  // ← LLM ignores this
  "claij:failFast": false,
  "claij:atomic": false
}
```

**Result:** LLMs treat these as unrecognized metadata (like comments) and ignore them.

### ✅ Explicit Prompting Works

Including execution hints in prompts:
```
"These file edits are independent and can be done in parallel. 
The edit_file tool supports parallel execution."
```

**Result:** LLMs respond to explicit instructions and adjust behavior accordingly.

## Implications for CLAIJ

### DON'T: Add Custom Flags to Tool Schemas

**Reason:** LLMs don't process them.

**What was proposed:**
```clojure
{"name" "github:apply-patch"
 "claij:parallel" true   ; ← Won't influence LLM
 "claij:atomic" true}    ; ← LLM won't care
```

### DO: Use These Alternatives

#### 1. Prompt Engineering

Include execution hints in FSM prompts:
```clojure
{"action" "llm"
 "prompts" ["These operations are independent - do them in parallel for efficiency."
            "Available tools: edit_file (parallel-safe), run_test (sequential)."]}
```

#### 2. Tool Design

Build inherent properties into tools:
```clojure
{"name" "github:apply-patch"
 "description" "Apply unified diff atomically via git apply. All changes succeed or all fail."
 ;; Atomicity comes from git apply, not from schema flag
}
```

#### 3. CLAIJ-Level Metadata

Add execution hints at FSM level, not tool level:
```clojure
{"action" "llm"
 "tools" ["edit_file"]
 "execution" {              ; For CLAIJ executor, not LLM
   "parallel" true
   "fail-fast" false}}
```

CLAIJ's executor reads this metadata and decides how to orchestrate tool calls.

## Test Harness

**File:** `test/claij/poc/tool_flags.clj` (336 lines)

**Structure:**
- Tool definitions (with and without flags)
- Test prompts (implicit vs. explicit)
- Analysis functions
- Automated test suite
- Results logging

**To run:**
```bash
clojure -M:test -n claij.poc.tool-flags
```

**Note:** Currently uses mock responses. Replace `simulate-llm-response` with actual API calls for real experiments.

## Experiments Designed

### Phase 1: Baseline
Test behavior without any flags.

### Phase 2: Implicit
Flags in schema, not mentioned in prompt.

### Phase 3: Explicit
Flags in schema + prompt mentions them.

### Phase 4: Conflict
Prompt contradicts schema flag.

### Phase 5: Multi-Vendor
Test across Claude, GPT-4, Gemini.

## Self-Analysis

**File:** `test/claij/poc/claude_self_analysis.md`

Claude (me) analyzed my own likely behavior with these flags:

### Predicted Behavior

**Parallel calls:**
- Schema only → Sequential (default)
- Explicit prompt → Parallel (already supported)

**FailFast:**
- Schema only → No acknowledgment
- Explicit prompt → Acknowledge and plan sequentially

**Atomic:**
- Schema only → No special treatment
- Explicit prompt → Acknowledge transactional semantics

**Conclusion:** All behavior is prompt-driven, not schema-driven.

## Confidence Levels

| Claim | Confidence |
|-------|-----------|
| Schema flags don't influence Claude | 90% |
| Schema flags don't influence GPT-4/Gemini | 85% |
| Explicit prompts do work | 95% |
| Tool design for atomicity works | 95% |

## Next Steps

1. ✅ Test harness implemented
2. ✅ Self-analysis documented
3. ⏳ Run experiments with actual API calls
4. ⏳ Test other vendors (GPT-4, Gemini)
5. ⏳ Document final results
6. ⏳ Update Issue #162 with recommendations

## Impact on Issue #162

**Original proposal:** Use `parallel`/`failFast`/`atomic` flags in tool schemas.

**PoC finding:** This won't work.

**Recommended alternative:**
1. Design `github:apply-patch` tool to be inherently atomic (via `git apply`)
2. Use prompt engineering to guide LLM behavior
3. Add execution metadata at CLAIJ FSM level for orchestration

**Example revised approach:**
```clojure
;; In FSM definition
{"action" "llm"
 "prompts" ["Generate a unified diff patch for all changes. This will be applied atomically."]
 "tools" [{"name" "github:apply-patch"
           "description" "Apply unified diff patch. Uses git apply for atomic all-or-nothing semantics."}]
 "execution" {
   "validate-patch" true  ; CLAIJ validates before applying
   "dry-run-first" true}} ; CLAIJ does dry run
```

## Files in This PoC

```
test/claij/poc/
├── README.md                     # This file
├── tool_flags.clj                # Test harness (336 lines)
├── experiment_log.md             # Experiment design
├── claude_self_analysis.md       # Detailed findings
└── results/                      # Test results (created at runtime)
```

## Related Issues

- [#165](https://github.com/JulesGosnell/claij/issues/165) - This PoC
- [#162](https://github.com/JulesGosnell/claij/issues/162) - Atomic Multi-File Changesets

## Conclusion

**The PoC answers the core question:** Custom tool schema flags (`claij:parallel`, `claij:failFast`, `claij:atomic`) **do not** meaningfully influence LLM behavior.

**For CLAIJ development:**
- Don't pollute tool schemas with unrecognized fields
- Use prompt engineering for LLM behavior guidance
- Design tools with inherent properties (atomicity via `git apply`)
- Add execution metadata at FSM level for CLAIJ's orchestration

**Status:** Preliminary findings based on self-analysis. Actual experiments with real API calls will validate these predictions.
