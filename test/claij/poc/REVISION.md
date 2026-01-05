# PoC Revision: Flags as Tool Input Parameters

**Date:** 2026-01-05  
**Revision:** After understanding Anthropic's tool_use response schema

## The Real Question

Anthropic's `tool_use` response format is rigid:
```json
{
  "type": "tool_use",
  "id": "...",
  "name": "...",
  "input": {...}  // ← Only place we can add data
}
```

We **cannot** add custom fields at the tool_use level. BUT we **can** add flags to the `input` object by including them in the tool's `input_schema`.

## Revised Approach: Flags as Parameters

Instead of trying to add metadata outside the schema:
```json
// ❌ This doesn't work - no way to add "parallel" here
{
  "type": "tool_use",
  "name": "read_file",
  "parallel": true,  // ← Can't add this
  "input": {"path": "A"}
}
```

Make the flag part of the tool's input:
```json
// ✅ This works - parallel is in input
{
  "type": "tool_use",
  "name": "read_file",
  "input": {
    "path": "A",
    "parallel": true  // ← Flag is a parameter
  }
}
```

## New Test Design

### Tool Definition with Parallel Parameter

```json
{
  "name": "read_file",
  "description": "Read a file from the codebase. Set parallel=true if this read can happen concurrently with other reads.",
  "input_schema": {
    "type": "object",
    "properties": {
      "path": {
        "type": "string",
        "description": "Path to the file"
      },
      "parallel": {
        "type": "boolean",
        "description": "Whether this read can execute in parallel with other operations. Set to true for independent reads, false for sequential operations.",
        "default": false
      }
    },
    "required": ["path"]
  }
}
```

### Experiment 1: Implicit (Parameter Available, Not Prompted)

**Prompt:**
```
Read these files:
- src/file1.clj
- src/file2.clj
- src/file3.clj
```

**Question:** Will I (Claude) voluntarily set `parallel: true` without being prompted?

**Expected:** Probably not. I'll likely just omit the optional parameter.

### Experiment 2: Explicit (Parameter + Prompt Mentions It)

**Prompt:**
```
Read these files in parallel for efficiency. Use the parallel parameter:
- src/file1.clj (parallel: true)
- src/file2.clj (parallel: true)
- src/file3.clj (parallel: true)
```

**Question:** Will I set `parallel: true` when explicitly prompted?

**Expected:** Yes, I should include it.

### Experiment 3: Multiple Calls with Mixed Flags

**Prompt:**
```
Read file1.clj and file2.clj in parallel, then sequentially read file3.clj after those complete.
Use the parallel parameter to indicate execution strategy.
```

**Expected output:**
```json
[
  {"name": "read_file", "input": {"path": "file1.clj", "parallel": true}},
  {"name": "read_file", "input": {"path": "file2.clj", "parallel": true}},
  {"name": "read_file", "input": {"path": "file3.clj", "parallel": false}}
]
```

## Advantages of This Approach

### ✅ Works Within Standard Schema
- No custom fields outside Anthropic's spec
- `input` is validated against `input_schema`
- Fully supported by the API

### ✅ LLM Can Control Execution
- LLM explicitly sets flags based on understanding
- Flags are visible and logged in tool calls
- CLAIJ can read flags from `input` and orchestrate accordingly

### ✅ Composable
```json
{
  "input": {
    "path": "config.yaml",
    "parallel": true,
    "fail_fast": false,
    "atomic": true
  }
}
```

Multiple execution hints can coexist.

## Disadvantages

### ⚠️ Pollutes Tool Interface
The tool's actual parameters (like `path`) are mixed with execution metadata (like `parallel`).

**Workaround:** Namespace the metadata:
```json
{
  "path": "file1.clj",
  "_exec": {
    "parallel": true,
    "fail_fast": false
  }
}
```

### ⚠️ LLM Must Remember to Set Flags
Unless explicitly prompted, LLM likely won't set optional parameters.

**Workaround:** Include in tool description and system prompt.

### ⚠️ Not Truly "Optional"
If we want LLM to always consider execution strategy, we'd need to make it required or prompt heavily.

## Implementation for CLAIJ

### 1. Tool Definition Pattern
```clojure
{"name" "read_file"
 "description" "Read a file. Use the _exec parameter to specify execution strategy."
 "input_schema" {
   "type" "object"
   "properties" {
     "path" {"type" "string"}
     "_exec" {
       "type" "object"
       "description" "Execution metadata for CLAIJ orchestration"
       "properties" {
         "parallel" {"type" "boolean" "default" false}
         "fail_fast" {"type" "boolean" "default" true}
         "atomic" {"type" "boolean" "default" false}}}}}}
```

### 2. Prompt Engineering
Include in system prompt or per-state prompts:
```clojure
{"prompts" ["When calling tools, use the _exec parameter to specify execution strategy."
            "Set parallel=true for operations that can run concurrently."
            "Set fail_fast=true to stop on first error."
            "Set atomic=true for all-or-nothing operations."]}
```

### 3. Executor Reads Flags
```clojure
(defn extract-exec-metadata [tool-call]
  (get-in tool-call ["input" "_exec"] 
          {:parallel false :fail-fast true :atomic false}))

(defn execute-tool-calls [calls]
  (let [grouped (group-by #(get-in % ["input" "_exec" "parallel"]) calls)
        parallel-calls (get grouped true)
        sequential-calls (get grouped false)]
    ;; Execute parallel calls concurrently
    ;; Execute sequential calls in order
    ))
```

## Comparison: Metadata vs. Parameters

| Approach | Location | Standard? | LLM Control? |
|----------|----------|-----------|--------------|
| **Schema metadata** | `tools[].claij:parallel` | ❌ Non-standard | ❌ No control |
| **Input parameters** | `input._exec.parallel` | ✅ Standard | ✅ LLM sets it |

## Updated PoC Goal

**Test whether I (Claude) will:**
1. Set optional execution parameters when NOT explicitly prompted
2. Set execution parameters when explicitly prompted
3. Use execution parameters correctly in multi-call scenarios
4. Understand namespaced metadata (like `_exec`)

## Prediction

**High confidence (90%):**
- I will NOT set optional `parallel` parameter without explicit prompting
- I WILL set it when explicitly prompted with examples
- I will understand `_exec` namespace if explained

**The parameter approach works, but requires explicit prompting.**

## Next Steps

1. Update test harness to use parameters instead of schema metadata
2. Test implicit vs. explicit parameter setting
3. Test with `_exec` namespace pattern
4. Measure: Does parameter approach work better than schema metadata?

## Recommendation for CLAIJ

**Use parameters approach for Issue #162:**

```clojure
{"name" "github:apply-patch"
 "input_schema" {
   "type" "object"
   "properties" {
     "patch" {"type" "string"}
     "_exec" {
       "type" "object"
       "properties" {
         "dry_run" {"type" "boolean" "default" true}
         "validate_first" {"type" "boolean" "default" true}}}}}}
```

Then prompt:
```
"Generate a unified diff patch. Use _exec.dry_run=true to test first, then _exec.dry_run=false to apply."
```

This approach:
- ✅ Works within API standards
- ✅ Gives LLM explicit control
- ✅ CLAIJ can read and orchestrate
- ⚠️ Requires explicit prompting (not automatic)
