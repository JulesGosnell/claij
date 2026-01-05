# PoC: Batch Execution Flags - INCONCLUSIVE

**Goal:** Test if LLMs output custom batch-level execution flags as siblings to `content`.

**Status:** ⚠️ Could not complete due to API credit limit

## What We Tried

Test if this works:
```json
{
  "content": [...tool calls...],
  "execution": {           // ← Custom top-level field
    "parallel": true,
    "failFast": false
  }
}
```

## Result

All 12 tests failed with:
```
"Your credit balance is too low to access the Anthropic API"
```

## Assessment (Without Testing)

**Predicted: Plan A Unlikely to Work (90% confidence)**

**Why:**
- Anthropic's response schema is server-controlled
- Standard fields: `id`, `type`, `role`, `content`, `stop_reason`, `usage`
- Custom top-level fields would likely be **stripped** by API
- No documented support for custom response fields

## Recommendation: Plan B

Use **per-call flags in tool input**:

```json
{
  "name": "read_file",
  "input": {
    "path": "file.clj",
    "_exec": {              // ← Namespaced execution metadata
      "parallel": true,
      "failFast": false
    }
  }
}
```

**Advantages:**
- ✅ Works within standard schema
- ✅ LLM has control via prompting
- ✅ Can implement immediately

**Disadvantages:**
- ⚠️ More verbose
- ⚠️ Mixes execution metadata with parameters

## Files

- `batch_execution_flags.clj` - PoC test harness (ready but untested)
- `PLAN_A.md` - Complete specification
- `results/` - Failed test results (credit error)

## Next Steps

Proceed with Plan B for Issue #162 (Atomic Changesets) and tool execution orchestration.
