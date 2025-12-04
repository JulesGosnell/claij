# Foldable Tree Trail Architecture

## Problem Statement

Linear trails accumulate unbounded context:
- MCP protocol details pollute LLM prompts (Issue 1: Trail Pollution)
- Token costs grow linearly with conversation length
- No way to "complete" a subgoal and move on with just a summary
- Claude Desktop approach: all MCP operations added serially until context exhausted

## Solution: Hierarchical Scopes with Summarization

Trail becomes a **foldable tree** instead of linear sequence. Scopes can be:
- **Folded**: LLM sees only summary (token-efficient)
- **Unfolded**: Full detail available for debugging/audit

### Visual Example

```
[Main Goal: Build web app]
  ├─ "I need to set up GitHub"
  │
  ├─ PUSH MCP(GitHub) scope ────┐
  │    ├─ Initialize service    │ Full MCP
  │    ├─ Create repo           │ protocol 
  │    ├─ Add files             │ detail here
  │    ├─ Set webhooks          │ (thousands of tokens)
  │    └─ EXIT: summarize ──────┘
  │       ↓
  │    "Setup complete: repo 'myapp' created, 3 files added, webhook configured"
  │
  ├─ [Back to main] Continue with summary only (20 tokens vs 5000)
  │
  ├─ PUSH MCP(Slack) scope ─────┐
  │    ├─ Auth/connect          │ More protocol
  │    ├─ Send notification     │ detail
  │    └─ EXIT: summarize ──────┘
  │       ↓
  │    "Team notified in #dev channel"
  │
  └─ Complete main goal
```

## Trail Structure

### Folded View (What LLM Sees)

```clojure
[{:uuid "550e8400-..."
  :type "message" 
  :content "Build me a web app"}
 
 {:uuid "7c9e6679-..."
  :type "response"
  :content "I'll set up the repo first"}
 
 {:uuid "a1b2c3d4-..."
  :type "scope"
  :name "github-mcp"
  :folded true
  :summary "Created repo 'myapp', added 3 files, configured webhook"
  :children-uuids ["uuid1" "uuid2" "uuid3" ...]}  ; References to DB/storage
 
 {:uuid "9f8e7d6c-..."
  :type "response"
  :content "Repo is ready, now I'll notify the team"}
 
 {:uuid "e5f6g7h8-..."
  :type "scope"
  :name "slack-mcp" 
  :folded true
  :summary "Posted update to #dev channel"
  :children-uuids ["uuid4" "uuid5" ...]}]
```

### Unfolded Storage (Full Detail)

```clojure
;; Content-addressable storage (DB, or in-memory for now)
{"uuid1" {:type "mcp-init" 
          :service "github"
          :capabilities {...}
          :timestamp ...}
 
 "uuid2" {:type "tool-call" 
          :tool "create-repo"
          :arguments {:name "myapp"}
          :timestamp ...}
 
 "uuid3" {:type "tool-result"
          :result {:repo-id "12345" :url "..."}}
 
 ;; ... full protocol detail preserved
 }
```

## Key Properties

1. **Token Efficient**: LLM only sees summaries in context
2. **Full Audit Trail**: All details preserved, accessible by UUID
3. **Clean Separation**: Scopes contain their complexity
4. **Hierarchical**: Scopes can nest (sub-sub-goals)
5. **Lazy Loading**: Unfold only when needed (error recovery, debugging)

## Operations

### Scope Entry
```clojure
(push-scope! trail {:name "github-mcp"
                    :uuid (uuid)
                    :parent-uuid current-scope})
;; All subsequent events go into this scope
```

### Scope Exit
```clojure
(pop-scope! trail {:summarize-fn github-summarizer})
;; Calls LLM or custom function:
;;   "Summarize these GitHub operations: [full detail]"
;; Stores summary in parent, preserves children in storage
```

### Unfold (For Debugging)
```clojure
(unfold-scope trail scope-uuid)
;; Fetches all children-uuids from storage
;; Reconstructs full detail for inspection
```

## Scalability Examples

### Massive Project
```
Root FSM: "Build e-commerce platform" (50 tokens)
├─ [Folded] Infrastructure setup (summary: 20 tokens)
│            [Storage: 50,000 tokens of AWS/K8s details]
├─ [Folded] Backend API (summary: 30 tokens)
│            [Storage: 100,000 tokens of coding sessions]
├─ [Folded] Frontend (summary: 25 tokens)
│            [Storage: 75,000 tokens of React work]
└─ [Folded] Deployment (summary: 30 tokens)
             [Storage: 40,000 tokens of CI/CD work]

LLM Context: ~155 tokens
Total Work Preserved: 265,000 tokens
```

### Surgical Error Recovery
```
LLM: "Deployment failed at step 3.2.5"
System: *identifies scope UUID* → unfolds just that scope
LLM: *sees exact failure context without other noise*
Fix applied, scope re-folded with new summary
```

### Cross-Conversation Reuse
```clojure
;; Conversation A creates valuable scope
{:uuid "abc-123"
 :summary "Database schema designed for e-commerce"
 :children-uuids [...]}

;; Conversation B references it
User: "Continue from scope abc-123"
System: *loads from storage*
LLM: *has full context of previous work*
```

## What's Missing (Implementation Gaps)

### 1. Summarization Protocol
Need to define:
- **When**: At scope exit (always? conditional?)
- **How**: LLM call? Custom function? Both?
- **Quality**: How to ensure summaries preserve essential information?
- **Schema**: Structure for summaries (key facts, outcomes, side effects)

```clojure
;; Example summarization contract
(defn summarize-scope
  [scope-events]
  ;; Returns: {:summary "Human readable..."
  ;;           :facts {:files-created ["a.txt" "b.txt"]
  ;;                   :services-called ["github"]
  ;;                   :outcomes [:success :repo-created]}}
  )
```

### 2. Identity Management
- **UUID generation**: When? Who assigns?
- **Scope lifecycle**: Who manages scope stack?
- **Parent/child relationships**: Maintained where?
- **Storage interface**: Abstraction for in-memory vs DB

```clojure
;; Scope identity structure
{:uuid "a1b2c3..."
 :parent-uuid "parent123..." ; nil for root
 :depth 2                     ; nesting level
 :created-at timestamp
 :closed-at timestamp
 :status :open | :closed | :summarized}
```

### 3. FSM Integration
- How does FSM machinery know about scopes?
- Where does scope management live? (FSM core? MCP layer? Root FSM?)
- How to trigger summarization at FSM boundaries?

## Benefits for CLAIJ Vision

### Management FSM Orchestration
```
You (bath/bed/train): "Build me feature X"
  ↓
Management FSM: [Folded view of entire project]
  ├─ Scope: Research similar features (summary)
  ├─ Scope: Design API (summary)
  ├─ Scope: Implement backend (summary)
  │   ├─ Scope: Database migrations (summary)
  │   └─ Scope: Write tests (summary)
  └─ Scope: Deploy to staging (summary)
  ↓
Management FSM: "Feature X deployed, all tests passing"

You see: High-level summary only
Full audit: 10,000s of tokens preserved
Token cost: Minimal (only summaries in context)
```

### Multi-LLM Coordination
```
Root: Assigns subgoals to specialist LLMs
├─ LLM-Backend: Works in own scope with MCP services
├─ LLM-Frontend: Works in own scope with different MCP services
└─ LLM-Testing: Works in own scope with yet more services

Each LLM:
- Full context within its scope
- Can use multiple MCP services
- Produces summary on completion

Root LLM:
- Sees only summaries from specialists
- Coordinates based on high-level state
- Manages dependencies between scopes
```

## Next Steps

1. **Document MCP Integration**: How does MCP FSM fit with scope model?
2. **Implement Scope Stack**: Basic push/pop operations
3. **Design Summarization**: LLM-based vs rule-based vs hybrid
4. **Add UUIDs to Trail**: Extend current trail structure
5. **Storage Abstraction**: In-memory first, DB later

## Related Issues

- **Issue 1 (Trail Pollution)**: Solved by folding MCP protocol details
- **Issue 4 (Cache Construction)**: MCP cache lives in scope, summarized on exit
- **Token Economics**: Summaries dramatically reduce cost of long-running projects

---

## Notes

This architecture enables:
- **Enterprise-scale AI orchestration** (months of work)
- **Full auditability** (compliance, debugging)
- **Token efficiency** (pay only for what's in focus)
- **Compositional reuse** (scopes as first-class entities)
- **Persistent memory** (survives restarts)

The foldable tree trail is the foundation for building a "distributed AI memory system with lazy loading."
