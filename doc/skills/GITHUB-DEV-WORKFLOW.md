# Skill: GitHub Development Workflow

## Purpose

This skill defines the standard workflow for developing CLAIJ features using GitHub as the coordination layer. It serves two purposes:

1. **Immediate**: Guide Claude's behavior for consistent, auditable development
2. **Future**: Input specification for meta-FSM to generate `github-dev-fsm`

## Prerequisites

- GitHub repository with issue tracking enabled
- Status labels: `status:todo`, `status:doing`, `status:done`
- CI/CD pipeline configured (GitHub Actions)
- DEV environment on Fly.io for sanity testing
- Commit message convention: `#<issue> [Phase N]: <description>`

---

## CRITICAL: Real-Time Progress Updates

**This rule is non-negotiable and takes priority over efficiency.**

When working on any story with incremental results (testing, migration, data collection, etc.):

### The Rule

**Update the GitHub issue IMMEDIATELY after EACH significant result.**

Do NOT:
- Batch updates for later
- Wait until "a few more" results are in
- Rely on memory or context to preserve progress
- Promise to update "when done"

### Why This Matters

1. **Session fragility**: Claude sessions can crash, timeout, or hit token limits at any moment
2. **Context loss**: Without immediate updates, ALL progress is lost on session death
3. **Human visibility**: Jules cannot see progress happening unless it's in the issue
4. **Audit trail**: The issue IS the source of truth, not Claude's memory

### Implementation Pattern

```
FOR EACH test/task/item:
  1. Execute the test/task
  2. IMMEDIATELY call update_issue with result
  3. THEN proceed to next item

NOT:
  1. Execute all tests
  2. Update issue with all results  // TOO LATE - session may die
```

### Example: Testing Multiple Models

```clojure
;; WRONG - batching results
(let [results (for [model models] (test-model model))]
  (update-issue-with-all-results results))  ;; Session dies here = all lost

;; RIGHT - immediate updates
(doseq [model models]
  (let [result (test-model model)]
    (update-issue-with-result model result)  ;; Preserved even if next test crashes
    ))
```

### Tracking Table Pattern

For multi-item testing/verification, ALWAYS:

1. Create a tracking table in the issue BEFORE starting
2. Update the table row IMMEDIATELY after each item completes
3. Include: status emoji, result, timing, notes

Example table format:
```markdown
| Item | Status | Result | Time | Notes |
|------|--------|--------|------|-------|
| item-1 | ✅ | PASS | 4.2s | Clean |
| item-2 | ❌ | FAIL | 10s | Schema validation |
| item-3 | ⏳ | - | - | Testing... |
```

### Git Conventions Reminder

When updating issues frequently:
- Use `claij-github:update_issue` tool
- Preserve existing content, only modify the results table
- If the update is a significant milestone, also commit local code

---

## Work Hierarchy

Stories are decomposed into a two-level hierarchy:

```
Story #53: Batched MCP Tool Execution
├── Phase 1: Reorganize (no behavior change)
│   ├── Task 1.1: Create mcp/protocol.clj
│   ├── Task 1.2: Create mcp/cache.clj
│   ├── Task 1.3: Create mcp/schema.clj (JSON Schema generation)
│   └── Task 1.4: Delete old files, update imports
│
├── Phase 2: Add ID correlation to bridge
│   ├── Task 2.1: Add pending atom
│   ├── Task 2.2: Implement send-batch
│   └── Task 2.3: Implement await-responses
│
└── Phase 3: Create high-level client API
    ├── Task 3.1: Create call-tools function
    └── Task 3.2: Add protocol helpers
```

### Hierarchy Definitions

| Level | Scope | Commit? | Test? |
|-------|-------|---------|-------|
| **Story** | Complete feature/fix | Final commit on completion | CI + DEV validation |
| **Phase** | Logical grouping of related tasks | YES - after phase tests pass | Unit tests at end of phase |
| **Task** | Single discrete change | NO - accumulate within phase | May test incrementally |

### Commit Cadence

1. Execute all tasks within a phase
2. Run unit tests for the phase
3. If tests pass → commit with `#<issue> [Phase N]: <description>`
4. If tests fail → fix issues, re-run tests, then commit
5. Move to next phase

This gives meaningful atomic commits (one per phase) while avoiding excessive commit noise (one per task).

---

## Workflow Phases

### Phase 1: Story Selection

**Actor**: Human + Claude collaboration

**Steps**:
1. Review backlog (issues without `status:doing` or `status:done`)
2. Human selects story to work on
3. Verify story has clear acceptance criteria
4. Check dependencies - ensure blocking stories are complete

**Triggers**:
- Human says "let's work on #N"
- Human says "what should we work on next?"
- FSM receives `{:event :start-story :issue-number N}`

**Outputs**:
- Selected issue number
- Understanding of scope and acceptance criteria

---

### Phase 2: Story Activation

**Actor**: Claude (with human approval)

**Steps**:
1. Move story from TODO → DOING
   ```
   Update issue labels: add `status:doing`, remove `status:todo` if present
   ```

2. Read existing story content
3. Identify if story needs decomposition into tasks

**GitHub Operations**:
```clojure
;; Move to DOING
(update-issue {:issue-number N
               :labels ["status:doing" ...existing-labels...]})
```

**Outputs**:
- Story is visible as "in progress"
- Story content loaded into context

---

### Phase 3: Story Decomposition

**Actor**: Claude (with human review)

**When**: Story lacks detailed tasks OR tasks need refinement

**Steps**:
1. Analyze story requirements
2. Break into discrete, testable tasks
3. Order tasks by dependency (topological sort)
4. Each task should be:
   - Small enough to complete in one coding session
   - Testable in isolation (TDD-friendly)
   - Committable independently
5. Write task list with explanatory notes
6. Update issue body with task checklist

**Task Numbering Convention**:
```
Phase.Task - e.g., 1.1, 1.2, 2.1, 3.1
```

**Task Format in Issue**:
```markdown
### Phase N: <Phase Name>

<Phase description and context>

- [ ] **N.1** <Task title> - <Brief explanation of what and why>
- [ ] **N.2** <Task title> - <Brief explanation>
```

**Dependency Ordering Rules**:
1. Infrastructure before features
2. Schemas before implementations
3. Unit tests before integration tests
4. Delete/cleanup after all dependent code updated

**Outputs**:
- Issue body contains ordered task checklist
- Each task has clear scope and rationale

---

### Workflow Step 4: Phase Execution (Loop)

**Actor**: Claude

**For each phase in order**:

#### 4.1 Plan the Phase
1. Read phase description and all its tasks from issue
2. Identify files to create/modify/delete across all tasks
3. Identify tests needed for the phase

#### 4.2 Execute Tasks
For each task in the phase:
1. Implement the task
2. Optionally verify with REPL or quick tests
3. Check off task in issue: `- [ ]` → `- [x]`

Do NOT commit after each task - accumulate changes for phase commit.

#### 4.3 Run Phase Unit Tests
1. Run unit tests covering the phase's functionality
2. Use REPL for quick verification when full test suite is slow
3. If tests fail → fix issues → re-run tests

#### 4.4 Commit Phase
Only after tests pass:

**Commit Message Format**:
```
#<issue> [Phase N]: <imperative description>

<optional body with context>
```

**Examples**:
```
#53 [Phase 1]: Reorganize MCP code into focused modules

Split mcp.clj (293 lines) into:
- mcp/protocol.clj: JSON-RPC message construction
- mcp/cache.clj: Cache management
- mcp/schema.clj: JSON Schema generation

Deleted dead code, updated imports.
```

```
#53 [Phase 2]: Add ID correlation to bridge

Implement promise-based tracking for JSON-RPC request/response
correlation. Supports out-of-order responses per MCP spec.
```

#### 4.5 Update Progress
1. Add entry to Progress Log table in issue
2. If phase revealed new tasks, add them to appropriate phase

**Loop until**: All phases complete

---

### Phase 5: Documentation Update

**Actor**: Claude

**Before requesting push, update all affected documentation:**

1. **Grep for stale references** - Check for outdated terminology, patterns, or examples
2. **Update README.md** - Add announcement if feature is user-visible
3. **Update ARCHITECTURE.md** - If system design changed
4. **Update skill docs** - If workflows or conventions changed
5. **Rationalise** - Remove obsolete content, consolidate duplicates

**Commit:** `#<issue> docs: Update documentation for <feature>`

**Doc Update Checklist:**
- [ ] Grepped for stale terminology
- [ ] No conflicting information across docs
- [ ] Examples reflect current patterns
- [ ] Skill docs current

---

### Phase 6: Request Push

**Actor**: Claude requests, Jules executes

**Claude NEVER pushes directly.** Jules reviews commits and pushes.

**Steps**:
1. Summarize changes: commits made, tests passing
2. Request Jules to push: "Ready for push - N commits on branch X"
3. Jules pushes and monitors CI

---

### Phase 7: CI Monitoring

**Actor**: Jules (Claude assists with diagnosis)

**Steps**:
1. Jules monitors CI status
   ```clojure
   ;; Check workflow runs
   (list-workflow-runs {:owner "JulesGosnell" 
                        :repo "claij"
                        :branch "<branch>"})
   ```

2. If CI fails:
   - Claude fetches logs for failed jobs
   - Claude diagnoses issue
   - Claude fixes and recommits with: `#<issue> [fix]: <description>`
   - Return to Phase 6 (Request Push)

3. Wait for CI to pass

**Outputs**:
- All commits pushed
- CI green

---

### Phase 8: DEV Environment Validation

**Actor**: Claude (with human for interactive testing)

**Steps**:
1. Deploy to DEV (if not auto-deployed by CI)
   ```bash
   fly deploy --app claij-dev
   ```

2. Run sanity tests against DEV:
   - Health check endpoint
   - Smoke test of affected functionality
   - Regression check of related features

3. If issues found:
   - Create fix tasks
   - Return to Phase 4

**DEV Environment**:
- URL: `https://claij-dev.fly.dev` (or configured endpoint)
- Purpose: Pre-production validation
- Data: Test data only, can be reset

**Outputs**:
- DEV environment running new code
- Sanity tests pass

---

### Phase 9: Story Completion

**Actor**: Claude

**Steps**:
1. Verify all tasks checked off
2. Verify CI is green
3. Verify DEV validation passed
4. Update issue:
   - Change labels: remove `status:doing`, add `status:done`
   - Add completion note to Progress Log
5. If this was on a feature branch:
   - Create PR (or merge if authorized)
   
**Final Commit** (if any cleanup):
```
closes #<issue>: Complete <story title>
```

**GitHub Operations**:
```clojure
(update-issue {:issue-number N
               :labels ["status:done" ...other-labels...]})

;; Optional: close issue explicitly if not auto-closed
(update-issue {:issue-number N
               :state "closed"})
```

**Outputs**:
- Story marked complete
- Full audit trail in GitHub

---

## Commit Message Reference

### Format
```
#<issue> [Phase N]: <imperative description>

<optional body>

<optional footer>
```

### Components

| Component | Required | Format | Example |
|-----------|----------|--------|---------|
| Issue ref | Yes | `#N` | `#53` |
| Phase ref | Yes | `[Phase N]` | `[Phase 2]` |
| Description | Yes | Imperative mood | `Add ID correlation to bridge` |
| Body | No | Wrapped at 72 chars | Context, rationale |
| Footer | No | `closes #N` etc | Auto-close on merge |

### Special References

| Reference | When to Use |
|-----------|-------------|
| `[Phase N]` | Normal phase completion (after tests pass) |
| `[fix]` | Fixing CI or test failure |
| `[docs]` | Documentation-only updates |
| `#0` | Change too small to merit a story (typo fix, minor tweak) |

### Examples

**Trivial change** (no story needed):
```
#0: Fix typo in README
```

```
#0: Add SVG+Hats link to FSM catalogue
```

**Phase completion** (the normal case):
```
#53 [Phase 1]: Reorganize MCP code into focused modules

Split mcp.clj into protocol/cache/schema modules.
Deleted dead code. Updated imports. All tests pass.
```

**CI fix**:
```
#53 [fix]: Resolve namespace conflict in protocol.clj
```

**Documentation**:
```
#53 [docs]: Add GitHub development workflow skill
```

**Final commit** (closes the story):
```
closes #53: Complete batched MCP tool execution

All phases implemented:
- Phase 1: File reorganization
- Phase 2: ID correlation in bridge
- Phase 3: High-level client API
- Phase 4: FSM integration
```

---

## Recovery Procedures

### If Claude Crashes Mid-Story

1. Read the issue to see current state
2. Check Progress Log for last completed task
3. Check git log for last commit
4. Resume from next unchecked task

### If CI Fails After Push

1. Fetch failed job logs
2. Diagnose root cause
3. Fix locally
4. Commit with `[fix]` task reference
5. Push and re-verify

### If DEV Deployment Fails

1. Check Fly.io logs
2. Rollback if needed: `fly releases rollback`
3. Fix issue
4. Redeploy

---

## FSM Specification (Meta-FSM Input)

This section is structured for parsing by the meta-FSM to generate `github-dev-fsm`.

> **Note:** Schema examples below use EDN/Malli-like pseudo-syntax for readability.
> Actual implementation uses JSON Schema. See `docs/design/schema-subsumption.md`
> for the schema architecture.

### State Diagram

```
[idle] ──select_story──→ [selected]
                              │
                         activate
                              ↓
                        [activated]
                              │
                         decompose
                              ↓
                       [decomposed]
                              │
                    ┌────────┴────────┐
                    ↓                 │
               [executing]            │
                    │                 │
            ┌───────┼───────┐         │
            ↓       ↓       ↓         │
         [test]  [impl]  [commit]     │
            │       │       │         │
            └───────┴───────┘         │
                    │                 │
              task_complete           │
                    │                 │
              more_tasks? ────yes─────┘
                    │
                    no
                    ↓
               [pushing]
                    │
                 ci_check
                    ↓
              [validating]
                    │
               dev_check
                    ↓
              [completing]
                    │
                complete
                    ↓
                [done]
```

### States

```clojure
(def states
  [{:id "idle"
    :type :initial
    :description "Waiting for story selection"
    :human-checkpoint? true}
   
   {:id "selected"
    :type :intermediate
    :description "Story chosen, ready to activate"
    :actions [:read-issue :verify-dependencies]}
   
   {:id "activated" 
    :type :intermediate
    :description "Story moved to DOING status"
    :actions [:update-labels-to-doing]}
   
   {:id "decomposed"
    :type :intermediate
    :description "Story broken into ordered tasks"
    :actions [:analyze-requirements :create-task-list :update-issue-body]
    :human-checkpoint? true}  ;; Human reviews task breakdown
   
   {:id "executing"
    :type :compound  ;; Contains sub-states
    :description "Working through tasks"
    :sub-states ["planning" "testing" "implementing" "committing"]
    :loop? true}
   
   {:id "pushing"
    :type :intermediate
    :description "All tasks done, pushing to remote"
    :actions [:git-push]}
   
   {:id "ci-checking"
    :type :waiting
    :description "Waiting for CI result"
    :actions [:monitor-workflow-runs]
    :timeout-ms 600000}  ;; 10 minute timeout
   
   {:id "validating"
    :type :intermediate
    :description "Running DEV environment checks"
    :actions [:deploy-to-dev :run-sanity-tests]
    :human-checkpoint? true}  ;; Human may do interactive testing
   
   {:id "completing"
    :type :intermediate
    :description "Finalizing story"
    :actions [:update-labels-to-done :add-completion-note]}
   
   {:id "done"
    :type :final
    :description "Story complete with full audit trail"}
   
   {:id "blocked"
    :type :error
    :description "Story blocked by issue"
    :human-checkpoint? true}])
```

### Transitions

```clojure
(def transitions
  [;; Story selection (human-initiated)
   {:from "idle"
    :to "selected"
    :event "select_story"
    :schema [:map
             ["issue_number" :int]
             ["reason" {:optional true} :string]]}
   
   ;; Activation
   {:from "selected"
    :to "activated"
    :event "activate"
    :guard "dependencies-met?"
    :actions [:update-issue {:labels ["status:doing"]}]}
   
   {:from "selected"
    :to "blocked"
    :event "blocked"
    :guard "has-blocking-issues?"
    :schema [:map ["blocking_issues" [:vector :int]]]}
   
   ;; Decomposition
   {:from "activated"
    :to "decomposed"
    :event "decompose"
    :llm-action {:task :decompose-story
                 :output-schema [:map
                                 ["tasks" [:vector
                                           [:map
                                            ["id" :string]
                                            ["title" :string]
                                            ["description" :string]
                                            ["test_approach" :string]
                                            ["dependencies" [:vector :string]]]]]]}}
   
   ;; Task execution loop
   {:from "decomposed"
    :to "executing"
    :event "start_tasks"
    :actions [:set-current-task-to-first]}
   
   {:from "executing"
    :to "executing"
    :event "task_complete"
    :guard "more-tasks?"
    :actions [:check-off-task :commit-work :advance-to-next-task]
    :schema [:map
             ["task_id" :string]
             ["commit_sha" :string]
             ["test_results" [:map ["passed" :int] ["failed" :int]]]]}
   
   {:from "executing"
    :to "pushing"
    :event "all_tasks_complete"
    :guard "no-more-tasks?"}
   
   ;; CI checking
   {:from "pushing"
    :to "ci-checking"
    :event "pushed"
    :actions [:git-push :get-workflow-run-id]
    :schema [:map ["run_id" :int]]}
   
   {:from "ci-checking"
    :to "validating"
    :event "ci_passed"
    :schema [:map ["run_id" :int] ["conclusion" [:= "success"]]]}
   
   {:from "ci-checking"
    :to "executing"
    :event "ci_failed"
    :actions [:fetch-failure-logs :create-fix-task]
    :schema [:map 
             ["run_id" :int]
             ["failed_jobs" [:vector :string]]
             ["error_summary" :string]]}
   
   ;; DEV validation
   {:from "validating"
    :to "completing"
    :event "dev_passed"
    :schema [:map ["tests_run" :int] ["tests_passed" :int]]}
   
   {:from "validating"
    :to "executing"
    :event "dev_failed"
    :actions [:create-fix-task]
    :schema [:map ["failure_reason" :string]]}
   
   ;; Completion
   {:from "completing"
    :to "done"
    :event "complete"
    :actions [:update-issue {:labels ["status:done"]}
              :add-progress-log-entry]}
   
   ;; Recovery transitions
   {:from "blocked"
    :to "selected"
    :event "unblocked"
    :human-initiated? true}])
```

### Context Schema

```clojure
(def context-schema
  [:map
   ;; Story info
   ["issue_number" :int]
   ["issue_title" :string]
   ["issue_body" :string]
   
   ;; Task tracking
   ["tasks" [:vector
             [:map
              ["id" :string]
              ["title" :string]
              ["description" :string]
              ["done" :boolean]
              ["commit_sha" {:optional true} :string]]]]
   ["current_task_index" :int]
   
   ;; Git state
   ["branch" :string]
   ["commits" [:vector
               [:map
                ["sha" :string]
                ["message" :string]
                ["task_id" :string]]]]
   
   ;; CI state
   ["workflow_run_id" {:optional true} :int]
   ["ci_status" {:optional true} [:enum "pending" "success" "failure"]]
   
   ;; DEV state
   ["dev_deployment_id" {:optional true} :string]
   ["dev_status" {:optional true} [:enum "pending" "success" "failure"]]
   
   ;; Audit trail
   ["started_at" :string]
   ["completed_at" {:optional true} :string]
   ["progress_log" [:vector
                    [:map
                     ["timestamp" :string]
                     ["event" :string]
                     ["details" :string]]]]])
```

### Actions (MCP Tool Mappings)

```clojure
(def actions
  {;; GitHub Issue Operations
   :read-issue
   {:tool "claij-github:get_issue"
    :params-fn (fn [ctx] {:owner "JulesGosnell"
                          :repo "claij"
                          :issue_number (get ctx "issue_number")})}
   
   :update-issue
   {:tool "claij-github:update_issue"
    :params-fn (fn [ctx params]
                 (merge {:owner "JulesGosnell"
                         :repo "claij"
                         :issue_number (get ctx "issue_number")}
                        params))}
   
   :update-labels-to-doing
   {:tool "claij-github:update_issue"
    :params-fn (fn [ctx]
                 {:owner "JulesGosnell"
                  :repo "claij"
                  :issue_number (get ctx "issue_number")
                  :labels ["status:doing"]})}
   
   :update-labels-to-done
   {:tool "claij-github:update_issue"
    :params-fn (fn [ctx]
                 {:owner "JulesGosnell"
                  :repo "claij"
                  :issue_number (get ctx "issue_number")
                  :labels ["status:done"]})}
   
   ;; Git Operations
   :git-push
   {:tool "claij-clojure-tools:bash"
    :params-fn (fn [ctx]
                 {:command (format "git push origin %s" (get ctx "branch"))})}
   
   :commit-work
   {:tool "claij-clojure-tools:bash"
    :params-fn (fn [ctx task-id message]
                 {:command (format "git commit -m '#%d [%s]: %s'"
                                   (get ctx "issue_number")
                                   task-id
                                   message)})}
   
   ;; CI Operations
   :monitor-workflow-runs
   {:tool "claij-github:list_workflow_runs"
    :params-fn (fn [ctx]
                 {:owner "JulesGosnell"
                  :repo "claij"
                  :branch (get ctx "branch")})}
   
   :fetch-failure-logs
   {:tool "claij-github:get_job_logs"
    :params-fn (fn [ctx]
                 {:owner "JulesGosnell"
                  :repo "claij"
                  :run_id (get ctx "workflow_run_id")
                  :failed_only true
                  :return_content true})}
   
   ;; DEV Environment
   :deploy-to-dev
   {:tool "claij-clojure-tools:bash"
    :params-fn (fn [_ctx]
                 {:command "fly deploy --app claij-dev"
                  :timeout_ms 300000})}
   
   :run-sanity-tests
   {:tool "claij-clojure-tools:bash"
    :params-fn (fn [_ctx]
                 {:command "clojure -X:test:dev-sanity"})}})
```

### LLM Prompts

```clojure
(def llm-prompts
  {:decompose-story
   {:system "You are a senior developer breaking down a story into implementable tasks.
             
             Guidelines:
             - Each task should be completable in one coding session
             - Order by dependencies (topological sort)
             - Each task should be independently testable (TDD)
             - Each task should be independently committable
             - Use format: Phase.Task (e.g., 1.1, 1.2, 2.1)
             - Infrastructure before features
             - Schemas before implementations  
             - Unit tests before integration tests
             - Deletions after dependent code updated"
    
    :user-template "Break down this story into tasks:
                    
                    ## Story #{{issue_number}}: {{issue_title}}
                    
                    {{issue_body}}
                    
                    Return tasks as structured data."}
   
   :implement-task
   {:system "You are implementing a specific task from a larger story.
             Follow TDD: write tests first, then implementation.
             Make minimal changes to pass tests.
             Commit message format: #{{issue_number}} [{{task_id}}]: <description>"
    
    :user-template "Implement task {{task_id}}: {{task_title}}
                    
                    Description: {{task_description}}
                    
                    Test approach: {{test_approach}}
                    
                    Context from story:
                    {{story_context}}"}
   
   :diagnose-ci-failure
   {:system "You are diagnosing a CI failure. Analyze the logs and determine:
             1. Root cause
             2. Which task/code is responsible  
             3. Fix approach"
    
    :user-template "CI failed for story #{{issue_number}}
                    
                    Failed jobs: {{failed_jobs}}
                    
                    Logs:
                    {{logs}}
                    
                    Recent commits:
                    {{recent_commits}}"}})
```

### Human Checkpoints

```clojure
(def human-checkpoints
  [{:state "idle"
    :reason "Story selection requires human judgment on priorities"
    :prompt "Which story should we work on? (Provide issue number)"}
   
   {:state "decomposed"
    :reason "Task breakdown review before execution"
    :prompt "I've broken down the story into {{task_count}} tasks. Please review the plan in the issue and confirm to proceed."}
   
   {:state "validating"
    :reason "DEV environment may need interactive testing"
    :prompt "Code deployed to DEV. Please run any manual tests. Confirm when ready to mark complete."}
   
   {:state "blocked"
    :reason "Human decision needed to resolve blocker"
    :prompt "Story is blocked by: {{blocking_reason}}. How should we proceed?"}])
```

### Recovery Procedures

```clojure
(def recovery
  {:on-crash
   {:description "Resume from last known state"
    :procedure ["Read issue to get current task list"
                "Check progress log for last completed task"
                "Check git log for last commit"
                "Resume from next unchecked task"]}
   
   :on-ci-failure
   {:description "Fix CI and retry"
    :procedure ["Fetch failed job logs"
                "Diagnose root cause (use LLM)"
                "Create fix task with [fix] reference"
                "Implement fix"
                "Re-push and verify"]}
   
   :on-dev-failure
   {:description "Fix DEV deployment issue"
    :procedure ["Check Fly.io logs"
                "Rollback if needed: fly releases rollback"
                "Fix issue"
                "Redeploy"]}
   
   :on-conflict
   {:description "Handle merge conflicts"
    :procedure ["Pull latest from main"
                "Resolve conflicts"
                "Commit with [fix]: Resolve merge conflicts"
                "Re-push"]}})

---

## Autonomous Development Pipeline

This skill is one component of a larger autonomous development system:

```
┌─────────────────────────────────────────────────────────────────────┐
│                    AUTONOMOUS DEVELOPMENT PIPELINE                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐        │
│  │   PLANNING   │     │    META      │     │   GITHUB     │        │
│  │  MEETING FSM │────▶│     FSM      │────▶│   DEV FSM    │        │
│  └──────────────┘     └──────────────┘     └──────────────┘        │
│         │                    │                    │                 │
│         ▼                    ▼                    ▼                 │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐        │
│  │   Stories    │     │ FSM Specs    │     │  Working     │        │
│  │   + Tasks    │     │ from Skills  │     │  Code        │        │
│  └──────────────┘     └──────────────┘     └──────────────┘        │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Component FSMs

#### 1. Planning Meeting FSM (`planning-fsm`)
**Purpose**: Human + AI collaboration to create and decompose stories

**States**: `gathering` → `analyzing` → `decomposing` → `prioritizing` → `ready`

**Inputs**:
- High-level goals from human
- Current backlog state
- Technical constraints

**Outputs**:
- Well-formed GitHub issues with task breakdowns
- Dependency graph between stories
- Priority ordering

**Human Involvement**: High - this is collaborative planning

#### 2. Meta FSM (`meta-fsm`)  
**Purpose**: Generate domain-specific FSMs from skill documents

**States**: `parsing_skill` → `extracting_states` → `building_transitions` → `generating_schemas` → `validating` → `outputting`

**Inputs**:
- Skill documents (like this one)
- MCP tool definitions
- LLM configuration

**Outputs**:
- Runnable FSM definition (Clojure data structure)
- JSON Schemas for transitions
- Prompt templates for LLM states

**Human Involvement**: Low - mostly automated, human reviews output

#### 3. GitHub Dev FSM (`github-dev-fsm`) - THIS SKILL
**Purpose**: Execute stories autonomously with audit trail

**States**: See FSM Specification section above

**Inputs**:
- Issue number to work on
- MCP tools for file operations, git, GitHub API

**Outputs**:
- Committed, tested code
- Updated issue with progress
- CI-validated changes

**Human Involvement**: Checkpoints for story selection, task review, DEV validation

### Trust Levels

As the system matures, human checkpoints can be reduced:

| Level | Name | Checkpoints | Use Case |
|-------|------|-------------|----------|
| 1 | Supervised | All | Initial deployment, learning |
| 2 | Assisted | Story selection, DEV validation | Routine development |
| 3 | Autonomous | Story selection only | Trusted, well-tested FSMs |
| 4 | Full Auto | None (batch mode) | Bug fixes, refactoring |

### Bootstrap Sequence

To achieve autonomous development:

```
1. [DONE] Basic MCP integration
2. [DONE] Schema-in-prompt FSM validation
3. [NOW]  This skill document (github-dev-workflow)
4. [NEXT] Test github-dev-fsm with simple story (manual orchestration)
5. [THEN] Build meta-fsm to generate FSMs from skills
6. [THEN] meta-fsm generates github-dev-fsm from this skill
7. [THEN] github-dev-fsm implements planning-fsm
8. [THEN] Full pipeline: planning → meta → execution
```

### Self-Improvement Loop

Once operational, the pipeline can improve itself:

```
┌────────────────────────────────────────────────────────────────┐
│                    SELF-IMPROVEMENT LOOP                       │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│   1. Planning FSM identifies improvement story                 │
│                        ↓                                       │
│   2. Meta FSM generates updated github-dev-fsm                 │
│                        ↓                                       │
│   3. Github Dev FSM implements the improvement                 │
│                        ↓                                       │
│   4. CI validates changes                                      │
│                        ↓                                       │
│   5. New capability available for next iteration               │
│                        ↓                                       │
│   6. Loop back to step 1                                       │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

**Key constraint**: Human oversight remains for:
- Security-sensitive changes
- Architectural decisions  
- External integrations
- Trust level changes

---

## Related Issues

- #28 Build kanban-fsm for autonomous GitHub-based development
- #30 GitHub-native distributed development platform
- #31 Idea-to-Production pipeline
- #32 Bootstrap claij self-hosting
- #52 Epic: MCP Integration (prerequisite)
- #53 Story: Batched MCP tool execution (current)

## Version History

| Date | Change |
|------|--------|
| 2025-12-16 | Initial version - full FSM specification for meta-FSM parsing |
