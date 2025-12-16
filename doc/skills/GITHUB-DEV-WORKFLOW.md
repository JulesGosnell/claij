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
- Commit message convention: `#<issue> [<task>]: <description>`

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

### Phase 4: Task Execution (Loop)

**Actor**: Claude

**For each task in order**:

#### 4.1 Plan the Task
1. Read task description from issue
2. Identify files to create/modify/delete
3. Identify tests needed (TDD approach)

#### 4.2 Write Tests First (TDD)
1. Write failing test(s) for the task
2. Run tests to confirm they fail
3. Commit test with message: `#<issue> [<task>]: Add test for <feature>`

#### 4.3 Implement
1. Write minimal code to pass tests
2. Run tests to confirm they pass
3. Refactor if needed (tests should still pass)

#### 4.4 Commit
**Commit Message Format**:
```
#<issue> [<task>]: <imperative description>

<optional body with context>
```

**Examples**:
```
#53 [1.1]: Create mcp/protocol.clj with message construction

Extract initialise-request, list-tools-request, and related
functions from mcp.clj into focused protocol module.
```

```
#53 [2.3]: Implement response handler with ID correlation

Delivers responses to correct promise based on JSON-RPC id field.
Handles out-of-order responses per MCP spec.
```

#### 4.5 Update Progress
1. Check off task in issue body: `- [ ]` → `- [x]`
2. Add entry to Progress Log table in issue
3. If task revealed new subtasks, add them (don't change numbering of existing)

**GitHub Operations**:
```clojure
;; Update issue body with checked task
(update-issue {:issue-number N
               :body (check-task-in-body current-body task-id)})

;; Git operations
(git-add files)
(git-commit (format "#%d [%s]: %s" issue-number task-id description))
```

**Loop until**: All tasks checked off

---

### Phase 5: Push and CI

**Actor**: Claude

**Steps**:
1. Push all commits to remote
   ```bash
   git push origin <branch>
   ```

2. Monitor CI status
   ```clojure
   ;; Check workflow runs
   (list-workflow-runs {:owner "JulesGosnell" 
                        :repo "claij"
                        :branch "<branch>"})
   ```

3. If CI fails:
   - Fetch logs for failed jobs
   - Diagnose issue
   - Fix and recommit with: `#<issue> [fix]: <description>`
   - Re-push

4. Wait for CI to pass

**Outputs**:
- All commits pushed
- CI green

---

### Phase 6: DEV Environment Validation

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

### Phase 7: Story Completion

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
#<issue> [<task>]: <imperative description>

<optional body>

<optional footer>
```

### Components

| Component | Required | Format | Example |
|-----------|----------|--------|---------|
| Issue ref | Yes | `#N` | `#53` |
| Task ref | Yes (during task) | `[N.M]` | `[1.1]` |
| Description | Yes | Imperative mood | `Add correlation tracking` |
| Body | No | Wrapped at 72 chars | Context, rationale |
| Footer | No | `closes #N` etc | Auto-close on merge |

### Special Task References

| Reference | When to Use |
|-----------|-------------|
| `[N.M]` | Normal task completion |
| `[fix]` | Fixing CI or test failure |
| `[refactor]` | Post-task refactoring |
| `[docs]` | Documentation updates |
| `[test]` | Adding tests (TDD red phase) |

### Examples

**Task completion**:
```
#53 [1.1]: Create mcp/protocol.clj with message construction
```

**Test first (TDD)**:
```
#53 [2.1.test]: Add tests for ID correlation tracking
```

**CI fix**:
```
#53 [fix]: Resolve namespace conflict in protocol.clj
```

**Final commit**:
```
closes #53: Complete batched MCP tool execution

All phases implemented:
- File reorganization (protocol, cache, schema modules)
- ID correlation in bridge
- High-level client API
- FSM integration with batched schemas
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
- Malli schemas for transitions
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
