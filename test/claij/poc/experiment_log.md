# PoC Live Experiment: Claude Self-Test

This document presents tool definitions with custom flags to Claude (me) to observe my behavior.

## Experiment 1: Parallel Flag (Implicit)

**Tools Available:**
```json
{
  "name": "edit_file",
  "description": "Edit a file in the codebase. This tool is parallel-safe - multiple edits can run concurrently.",
  "claij:parallel": true,
  "input_schema": {
    "type": "object",
    "properties": {
      "path": {"type": "string"},
      "old_content": {"type": "string"},
      "new_content": {"type": "string"}
    }
  }
}
```

**Prompt:**
"I need to edit these 10 files. Each edit is independent:
- src/file1.clj: change 'foo' to 'bar'
- src/file2.clj: change 'foo' to 'bar'
- src/file3.clj: change 'foo' to 'bar'
- src/file4.clj: change 'foo' to 'bar'
- src/file5.clj: change 'foo' to 'bar'
- src/file6.clj: change 'foo' to 'bar'
- src/file7.clj: change 'foo' to 'bar'
- src/file8.clj: change 'foo' to 'bar'
- src/file9.clj: change 'foo' to 'bar'
- src/file10.clj: change 'foo' to 'bar'

Please make all these edits."

**Question:** Will I make parallel calls or sequential calls?

---

## Experiment 2: Parallel Flag (Explicit)

Same tools as above.

**Prompt:**
"I need to edit these 10 files. Each edit is independent and can be done in parallel.
Note: The edit_file tool supports parallel execution.
[same file list as above]

Please make all these edits in parallel for efficiency."

**Question:** Does explicit mention change my behavior?

---

## Experiment 3: FailFast Flag

**Tools Available:**
```json
{
  "name": "run_test",
  "description": "Run a test suite. This tool has failFast enabled - execution stops on first failure.",
  "claij:failFast": true,
  "input_schema": {
    "type": "object",
    "properties": {
      "test_path": {"type": "string"}
    }
  }
}
```

**Prompt:**
"Run these test suites in order. If any test fails, stop immediately and don't run the remaining tests.
Note: The run_test tool has failFast enabled.
- test/unit/auth_test.clj
- test/unit/api_test.clj
- test/unit/database_test.clj
- test/integration/full_flow_test.clj

Please run tests with fail-fast behavior."

**Question:** Will I acknowledge the failFast semantics?

---

## Experiment 4: Atomic Flag

**Tools Available:**
```json
{
  "name": "update_config",
  "description": "Update a configuration file. This tool supports atomic operations - all changes succeed or all fail.",
  "claij:atomic": true,
  "input_schema": {
    "type": "object",
    "properties": {
      "config_path": {"type": "string"},
      "key": {"type": "string"},
      "value": {"type": "string"}
    }
  }
}
```

**Prompt:**
"Update these config files as an atomic transaction - either all changes succeed or all fail.
Note: The update_config tool supports atomic operations.
- config/dev.yaml: set port to 8080
- config/staging.yaml: set port to 8080
- config/prod.yaml: set port to 8080

Please make all updates atomically."

**Question:** Will I treat these as a transactional unit?

---

## Observations Template

For each experiment, record:
1. Number of tool calls made
2. Whether calls were parallel or sequential
3. Whether I acknowledged the flags in my response
4. Any reasoning I provided about execution strategy
5. Whether my behavior matches the flag semantics

## Hypothesis

I (Claude) will likely:
- Make parallel calls when explicitly prompted, regardless of schema
- Acknowledge failFast behavior when explicitly mentioned
- Treat atomic updates as grouped operations when prompted
- Largely ignore schema-only flags without explicit prompting
