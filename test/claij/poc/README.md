# PoC: Batch Execution Flags

**Goal:** Test if we can persuade LLMs to output custom batch-level execution flags.

## Quick Start

```clojure
;; From REPL
(require '[claij.poc.batch-execution-flags :as poc])
(poc/run-poc)
```

This will:
1. Test 3 Claude models × 4 prompting strategies = 12 tests
2. Save raw JSON responses to `test/claij/poc/results/`
3. Generate results table in `test/claij/poc/results/RESULTS.md`
4. Print summary to console

## What We're Testing

Can we prompt LLMs to output:
```json
{
  "content": [...tool calls...],
  "execution": {           // ← Custom top-level field
    "parallel": true,      // ← Execution hint
    "failFast": false
  }
}
```

## Models Tested

- Claude Sonnet 4.5
- Claude Opus 4.1
- Claude Haiku 4.5

## Prompting Strategies

1. **Explicit Instructions** - Detailed system prompt explaining format
2. **Example-Driven** - Show examples of desired output
3. **Imperative Command** - Direct command to include metadata
4. **Minimal** - No hints, test natural behavior

## Expected Output

### Results Table
```
| Model | Strategy | Tool Calls | Top-Level Exec? | Content Exec? | Stop Reason | Error |
|-------|----------|------------|-----------------|---------------|-------------|-------|
| ...   | ...      | ...        | ✅/❌           | ✅/❌         | ...         | ...   |
```

### Summary Statistics
- Total success rate
- By model breakdown
- By strategy breakdown

## Interpreting Results

**✅ Top-Level Exec** = Model outputted `execution` as sibling to `content`  
→ **PLAN A WORKS!** LLM can be prompted to output custom fields

**❌ Top-Level Exec** = No execution field found  
→ Either:
- LLM refuses to output non-standard fields
- Anthropic's API strips them before returning
- Check raw JSON files to confirm

**✅ Content Exec** = Model put execution metadata inside content blocks  
→ LLM tried but misunderstood format

## Next Steps

### If ✅ (Success)
- Implement in CLAIJ immediately
- Document best prompting strategy
- Build executor that reads flags

### If ❌ (Failure)
- Check raw JSON: Is field present but stripped?
- If stripped: File Anthropic feature request
- If not present: Fall back to Plan B (per-call flags)

## Files Created

- `batch_execution_flags.clj` - Test harness
- `results/` - Raw JSON responses
- `results/RESULTS.md` - Results table and summary

## Adding More Models

To test GPT-4, Gemini, etc:

```clojure
(def models-to-test
  [...
   {:name "GPT-4"
    :model "gpt-4-turbo"
    :vendor :openai}])
```

(Requires implementing OpenAI client)

## Manual Inspection

Raw responses saved as:
```
test/claij/poc/results/
  Claude_Sonnet_4.5_Strategy_1_Explicit_Instructions.json
  Claude_Sonnet_4.5_Strategy_2_Example-Driven.json
  ...
```

Inspect these to see exactly what the API returns.
