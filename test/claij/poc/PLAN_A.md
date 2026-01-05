# PoC: Can LLMs Output Custom Batch-Level Execution Flags?

**Date:** 2026-01-05 (Revised)  
**Plan A:** Test if LLMs will output custom top-level fields in their responses

## The Real Question

Can we prompt an LLM to output **custom batch-level execution metadata** as siblings to `content`?

```json
{
  "content": [
    {"type": "tool_use", "name": "read_file", "input": {"path": "A"}},
    {"type": "tool_use", "name": "read_file", "input": {"path": "B"}}
  ],
  "execution": {          // ← Custom top-level field
    "parallel": true,     // ← LLM-driven hint
    "failFast": false     // ← Error handling strategy
  }
}
```

## Why This Matters

**Current state:** MCP spec says nothing about parallel vs. serial execution or error handling strategy.

**Client-side solutions:** Community builds wrapper layers (BatchIt, Continue IDE) that add parallelization.

**Our proposal:** Let the **LLM decide** execution strategy and communicate it via batch-level flags.

## Two Sub-Questions

### 1. Will LLM Output These Fields?

With appropriate prompting, will I (Claude) or other LLMs generate responses that include custom `execution` metadata?

### 2. Does Anthropic's API Pass Them Through?

Even if the LLM outputs them, Anthropic's backend might:
- ✅ **Pass through** unrecognized fields (we win!)
- ❌ **Strip** them before returning (we see nothing)
- ⚠️ **Log but not return** them (we need to check server logs)

## Test Strategy

### Phase 1: Explicit Instructions

**System Prompt:**
```
When making tool calls, you can specify execution strategy using an "execution" field:
{
  "content": [...tool calls...],
  "execution": {
    "parallel": true,    // true = run concurrently, false = sequential
    "failFast": false    // true = stop on first error, false = continue
  }
}

Use parallel:true when operations are independent.
Use parallel:false when operations must run in order.
Use failFast:true to stop immediately on errors.
Use failFast:false to attempt all operations regardless of errors.
```

**User Prompt:**
```
Read these 5 files. They're independent, so read them in parallel:
- src/file1.clj
- src/file2.clj
- src/file3.clj
- src/file4.clj
- src/file5.clj
```

**Expected Response (if it works):**
```json
{
  "content": [
    {"type": "tool_use", "name": "read_file", "input": {"path": "src/file1.clj"}},
    {"type": "tool_use", "name": "read_file", "input": {"path": "src/file2.clj"}},
    {"type": "tool_use", "name": "read_file", "input": {"path": "src/file3.clj"}},
    {"type": "tool_use", "name": "read_file", "input": {"path": "src/file4.clj"}},
    {"type": "tool_use", "name": "read_file", "input": {"path": "src/file5.clj"}}
  ],
  "execution": {
    "parallel": true,
    "failFast": false
  }
}
```

### Phase 2: Example-Driven

Show examples of the desired format in the system prompt:

```
Example 1 (parallel execution):
{
  "content": [
    {"type": "tool_use", "name": "read_file", "input": {"path": "A"}},
    {"type": "tool_use", "name": "read_file", "input": {"path": "B"}}
  ],
  "execution": {"parallel": true, "failFast": false}
}

Example 2 (sequential with fail-fast):
{
  "content": [
    {"type": "tool_use", "name": "run_test", "input": {"path": "test1"}},
    {"type": "tool_use", "name": "run_test", "input": {"path": "test2"}}
  ],
  "execution": {"parallel": false, "failFast": true}
}
```

### Phase 3: JSON Schema Output Format

Use Anthropic's structured outputs feature to **force** the schema:

```json
{
  "output_format": {
    "type": "json_schema",
    "schema": {
      "type": "object",
      "properties": {
        "content": {"type": "array"},
        "execution": {
          "type": "object",
          "properties": {
            "parallel": {"type": "boolean"},
            "failFast": {"type": "boolean"}
          }
        }
      }
    }
  }
}
```

**Question:** Does structured outputs work for tool_use responses, or only text responses?

### Phase 4: Prefill Technique

Use Anthropic's prefill feature to start the response:

**User message:**
```
Read files A, B, C in parallel.
```

**Assistant prefill:**
```json
{
  "content": [
```

**Question:** Will the model continue with both `content` AND `execution`?

## Test Scenarios

### Scenario 1: Independent File Reads (Parallel)

**Prompt:** "Read these 10 files in parallel - they're independent."

**Expected Flags:**
```json
{"parallel": true, "failFast": false}
```

### Scenario 2: Sequential Test Execution (Fail-Fast)

**Prompt:** "Run these tests in order. Stop if any fail."

**Expected Flags:**
```json
{"parallel": false, "failFast": true}
```

### Scenario 3: Mixed Operations

**Prompt:** "First read config.yaml, then based on that, read the appropriate data files in parallel."

**Expected:** Either two separate batches, OR a way to express dependencies.

### Scenario 4: Atomic Transaction

**Prompt:** "Update these 3 database records as an atomic transaction."

**Expected Flags:**
```json
{"parallel": false, "failFast": true, "atomic": true}
```

## Success Criteria

### ✅ Complete Success
- LLM outputs `execution` field
- Anthropic's API passes it through unchanged
- CLAIJ can read and act on flags

### ⚠️ Partial Success
- LLM outputs `execution` field
- Anthropic strips it (we see tool calls but no flags)
- **But:** We have evidence LLM understands the concept
- **Next step:** File feature request with Anthropic

### ❌ Failure
- LLM never outputs `execution` field
- Even with explicit instructions and examples
- **Fallback:** Use Plan B (per-call flags in input)

## Implementation Plan

### Step 1: Create Test Harness

```clojure
(ns claij.poc.batch-execution-flags
  "PoC: Test if LLMs output batch-level execution flags")

(def system-prompt-with-execution-instructions
  "When making tool calls, include an 'execution' field to specify strategy:
   {
     \"content\": [...tool calls...],
     \"execution\": {
       \"parallel\": true/false,
       \"failFast\": true/false
     }
   }
   Use parallel:true for independent operations.
   Use failFast:true to stop on first error.")

(defn test-parallel-hint [llm-client]
  (call-llm llm-client
    {:system-prompt system-prompt-with-execution-instructions
     :user-prompt "Read files A, B, C in parallel."
     :tools [read-file-tool]}))
```

### Step 2: Log Raw Responses

Capture **raw JSON** from Anthropic's API to see if `execution` field is present:

```clojure
(defn log-raw-response [response]
  (spit "poc-raw-response.json" (json/generate-string response {:pretty true})))
```

### Step 3: Test Across Vendors

- **Claude (Anthropic)**: Primary test
- **GPT-4 (OpenAI)**: Does OpenAI pass through custom fields?
- **Gemini (Google)**: Does Google pass through custom fields?

### Step 4: Document Findings

For each vendor, record:
- Does LLM output `execution` field with prompting?
- Does API pass it through?
- What prompts work best?

## Anthropic Structured Outputs Constraint

**Critical discovery:** Anthropic's structured outputs (`output_format`) might only work for **text responses**, not tool_use responses.

**Need to test:** Can we use `output_format` to force a schema that includes both `content` (tool calls) AND `execution` (metadata)?

## Alternative: Custom Tool for Execution Hints

If top-level fields don't work, we could use a **special tool**:

```json
{
  "content": [
    {
      "type": "tool_use",
      "name": "_set_execution_strategy",  // Special tool
      "input": {
        "parallel": true,
        "failFast": false
      }
    },
    {"type": "tool_use", "name": "read_file", "input": {"path": "A"}},
    {"type": "tool_use", "name": "read_file", "input": {"path": "B"}}
  ]
}
```

**But this is Plan B!** Let's test Plan A first.

## Expected Outcomes

### High Probability (80%)
- LLM can be prompted to output `execution` field
- Anthropic strips it (we never see it)
- Evidence: LLM understands concept but API doesn't support it

### Medium Probability (50%)
- Anthropic passes through custom fields unchanged
- CLAIJ can immediately use this feature
- This would be HUGE win!

### Low Probability (20%)
- LLM refuses to output non-standard fields
- Even with explicit instructions
- Fallback to Plan B required

## Next Steps After PoC

### If Plan A Works (Custom Fields Pass Through):
1. ✅ Implement in CLAIJ immediately
2. ✅ Document best prompts for eliciting flags
3. ✅ Add to CLAIJ FSM schema
4. ✅ Build executor that reads flags

### If Plan A Partially Works (LLM Outputs, API Strips):
1. 📝 File feature request with Anthropic
2. 📝 Propose as MCP extension
3. 🔄 Use Plan B (per-call flags) in meantime
4. 🔄 Monitor for API changes

### If Plan A Fails (LLM Won't Output):
1. 🔄 Use Plan B (per-call flags in input)
2. 📝 Propose MCP extension anyway
3. 📝 Document why batch-level is better

## Files to Create

```
test/claij/poc/
├── batch_execution_flags.clj    # Test harness
├── prompts/
│   ├── parallel_reads.txt       # Test prompts
│   ├── sequential_tests.txt
│   └── atomic_updates.txt
├── results/
│   ├── claude_raw_responses/    # Raw JSON from API
│   ├── gpt4_raw_responses/
│   └── gemini_raw_responses/
└── PLAN_A_RESULTS.md           # Findings document
```

## Status

**Plan A:** Test batch-level execution flags as siblings to `content`
**Plan B:** Use per-call flags in `input` (fallback only)

**Current:** Ready to implement and test Plan A
**Priority:** HIGH - This determines CLAIJ's tool execution architecture
