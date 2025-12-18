# MCP & FSM Stories Analysis
## Bath-Driven-Development Plan 🛁

*Prepared for Jules - December 18, 2025*

---

## Executive Summary

15 open stories analyzed across MCP integration, FSM lifecycle, and local LLM support. The critical path is **FSM stop bug → cancellation → interruption handling**, which forms the foundation for robust MCP operations.

---

## Story Inventory

### Tier 1: Critical Foundation (Fix First)

| # | Story | Difficulty | Depends On | Impact |
|---|-------|------------|------------|--------|
| **76** | FSM stop not working | 🟡 Medium | None | **BLOCKING** - Can't test anything properly |
| **75** | MCP Request Cancellation | 🟡 Medium | #76 | Required for interrupt handling |
| **57** | MCP Connection Interruption | 🟠 Medium-Hard | #75, #76 | Robustness |

### Tier 2: Protocol Completeness

| # | Story | Difficulty | Depends On | Impact |
|---|-------|------------|------------|--------|
| **74** | logging/setLevel | 🟢 Easy | None | Debugging aid |
| **19** | MCP Logging (receive) | 🟢 Easy | None | Debugging aid |
| **56** | MCP ping/health | 🟢 Easy | None | Connection validation |
| **20** | Progress notifications | 🟡 Medium | None | UX for long operations |
| **10** | Handle notifications | 🟡 Medium | None | Protocol completeness |

### Tier 3: Advanced Features

| # | Story | Difficulty | Depends On | Impact |
|---|-------|------------|------------|--------|
| **73** | Lifecycle/Parallelism/FSM-level | 🟠 Medium-Hard | #76 | Architecture |
| **71** | Tool filtering & overrides | 🟡 Medium | None | Token efficiency |
| **29** | State-scoped tool filtering | 🟡 Medium | #71 | Security |
| **77** | Ollama integration | 🟢 Easy | None | Local dev experience |

### Tier 4: Configuration & Architecture

| # | Story | Difficulty | Depends On | Impact |
|---|-------|------------|------------|--------|
| **70** | Multi-level bridge config | 🟠 Medium-Hard | #73 | DX |
| **69** | Project abstraction | 🔴 Hard | #70 | Architecture |
| **33** | Dynamic MCP bootstrap | 🔴 Hard | Many | Vision/Demo |

### Already Complete (for reference)

| # | Story | Status |
|---|-------|--------|
| 53 | Batched MCP execution | ✅ |
| 54 | Sub-FSM machinery | ✅ |
| 67 | Hat infrastructure | ✅ |
| 68 | MCP Hat | ✅ |
| 72 | Multi-server support | ✅ |
| 64 | MCP envelope schemas | ✅ (Phase 4 deferred) |
| 62 | Schema propagation | ✅ (Phase 3 deferred) |

---

## Detailed Analysis

### #76: FSM Stop Not Working (CRITICAL)

**Root Cause Analysis:**

Looking at the code, I found the issue:

```clojure
;; In start-fsm:
stop-fsm (fn []
           (hat/run-stop-hooks context)  ;; Uses captured context
           (doseq [c all-channels]
             (safe-channel-operation close! c)))
```

The `context` captured in the closure IS the updated one from `don-hats`, so that's correct. However:

1. **Go-loop termination**: Closing channels causes `alts!` to return `[nil channel]`, but the go-loop only exits when `msg` is nil. This works IF the loop is waiting at `alts!`.

2. **In-flight actions**: If an action (e.g., LLM call) is in progress, the go-loop is blocked in `invoke-action`, not at `alts!`. Closing channels won't stop it until the action completes.

3. **No interrupt signal**: There's no mechanism to interrupt a running action.

**Proposed Fix:**

```clojure
;; Add interrupt atom to context
(let [interrupted? (atom false)
      context (assoc context :fsm/interrupted? interrupted?)
      ;; ...
      stop-fsm (fn []
                 (reset! interrupted? true)  ;; Signal interrupt
                 (hat/run-stop-hooks context)
                 (doseq [c all-channels]
                   (close! c)))]
  ;; Actions check interrupted? periodically
```

**Effort**: 2-3 hours
**Risk**: Low - isolated change

---

### #75: MCP Request Cancellation

**What's needed:**

```clojure
;; In bridge.clj
(defn cancel-request [{:keys [input-chan pending]} request-id reason]
  "Send cancellation notification for in-flight request."
  (>!! input-chan {"jsonrpc" "2.0"
                   "method" "notifications/cancelled"
                   "params" {"requestId" request-id
                             "reason" (or reason "Client cancelled")}})
  ;; Remove from pending (response may never come)
  (when-let [{:keys [promise]} (get @pending request-id)]
    (deliver promise {:cancelled true :reason reason})
    (swap! pending dissoc request-id)))
```

**Challenge**: Currently `send-and-await` blocks synchronously. For cancellation to work, we need:
- Async variant with cancellation token
- OR timeout-based cancellation
- OR separate thread for cancellation signal

**Depends on**: #76 (need working stop first)

**Effort**: 3-4 hours
**Risk**: Medium - async complexity

---

### #57: MCP Connection Interruption Handling

**Scenarios to handle:**

| Scenario | Detection | Recovery |
|----------|-----------|----------|
| Server crash | Process exit / broken pipe | Restart & reconnect |
| Network timeout | No response within timeout | Retry with backoff |
| Partial batch | Some responses, then failure | Return partial + errors |
| Resource exhaustion | OOM / file descriptor limit | Graceful degradation |

**Design:**

```clojure
(defn with-resilient-mcp [bridge opts f]
  "Execute f with MCP resilience wrapper."
  (let [{:keys [max-retries backoff-ms on-failure]} opts]
    (loop [attempt 0]
      (try
        (f bridge)
        (catch Exception e
          (if (< attempt max-retries)
            (do
              (Thread/sleep (* backoff-ms (inc attempt)))
              (recur (inc attempt)))
            (on-failure e)))))))
```

**Depends on**: #75, #76
**Effort**: 4-6 hours
**Risk**: Medium - error handling is tricky

---

### #74: logging/setLevel (EASY WIN)

**Implementation:**

```clojure
(defn set-log-level [bridge level]
  "Set MCP server log level. Level is one of:
   debug, info, notice, warning, error, critical, alert, emergency"
  (send-and-await bridge
    [{"jsonrpc" "2.0"
      "id" (swap! (:next-id bridge) inc)
      "method" "logging/setLevel"
      "params" {"level" level}}]
    5000))
```

**Hat config option:**

```clojure
{"hats" [{"mcp" {:servers {...}
                 :log-level "debug"}}]}  ;; Set on init
```

**Effort**: 1 hour
**Risk**: Very low

---

### #73: Lifecycle, Parallelism, FSM-level Config

**Three sub-problems:**

#### A. Parallelism (Easy)

```clojure
;; Current (sequential)
(reduce-kv (fn [acc server requests]
             (assoc acc server (bridge/send-and-await ...)))
           {} calls)

;; Parallel
(let [futures (map-vals (fn [server requests]
                          (future (bridge/send-and-await ...)))
                        calls)]
  (map-vals deref futures))
```

**Effort**: 30 minutes
**Risk**: Low

#### B. Lifecycle (Medium)

Current: Bridges start on hat expansion, stop on FSM stop.

Options:
1. **FSM-scoped** (current): Bridge dies with FSM
2. **Session-scoped**: Bridge outlives FSM, shared across runs
3. **Global**: Bridge starts on first use, stops on explicit close

**Effort**: 2-3 hours for option 2
**Risk**: Medium - state management

#### C. FSM-level Hats (Needs Design)

```clojure
;; Proposed syntax
{"id" "my-fsm"
 "hats" {"shared-mcp" {"mcp" {:servers {...}}}}  ;; Declare at FSM level
 "states" [{"id" "mc"
            "hats" ["shared-mcp"]}  ;; Reference by name
           {"id" "reviewer"}]}     ;; No MCP access
```

**Effort**: 4-6 hours
**Risk**: Medium - design decisions needed

---

### #77: Ollama Integration (EASY WIN)

**Minimal implementation:**

```clojure
(defmethod call-llm :ollama [{:keys [model messages base-url]}]
  (let [url (str (or base-url "http://localhost:11434") "/v1/chat/completions")]
    (-> (http/post url {:body (json/encode {:model model :messages messages})
                        :content-type :json})
        :body
        json/decode)))
```

**Effort**: 1-2 hours
**Risk**: Very low - isolated addition

---

## Recommended Implementation Order

### Sprint 1: Foundation (Critical Path)

```
Week 1:
├── #76 FSM Stop Bug (2-3h) ← MUST DO FIRST
├── #74 logging/setLevel (1h) ← Easy win
├── #77 Ollama Integration (2h) ← Easy win, unlocks local dev
└── #73a Parallelism (30min) ← Quick improvement

Week 2:
├── #75 Request Cancellation (3-4h)
├── #56 Ping/Health (1h)
└── #19 MCP Logging receive (1h)
```

### Sprint 2: Robustness

```
Week 3:
├── #57 Connection Interruption (4-6h)
├── #20 Progress Notifications (2h)
└── #10 Handle Notifications (2h)

Week 4:
├── #73b Lifecycle options (3h)
├── #71 Tool filtering (3h)
└── Documentation & testing
```

### Sprint 3: Architecture

```
Week 5-6:
├── #73c FSM-level hats (4-6h)
├── #70 Multi-level config (4h)
├── #29 State-scoped filtering (2h)
└── #55 Code review demo

Future:
├── #69 Project abstraction
└── #33 Dynamic bootstrap
```

---

## Quick Wins (Do Anytime)

These can be done in any spare hour:

| Story | Time | Value |
|-------|------|-------|
| #74 logging/setLevel | 1h | Debugging |
| #77 Ollama | 2h | Local dev |
| #56 Ping | 1h | Health checks |
| #73a Parallelism | 30m | Performance |

---

## Dependencies Graph

```
                    ┌─────────────────────────────────────┐
                    │         #76 FSM Stop Bug            │
                    │         (CRITICAL BLOCKER)          │
                    └───────────────┬─────────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    ▼                               ▼
            ┌───────────────┐               ┌───────────────┐
            │ #75 Request   │               │ #73 Lifecycle │
            │ Cancellation  │               │ & Parallelism │
            └───────┬───────┘               └───────┬───────┘
                    │                               │
                    ▼                               ▼
            ┌───────────────┐               ┌───────────────┐
            │ #57 Connection│               │ #70 Multi-    │
            │ Interruption  │               │ level Config  │
            └───────────────┘               └───────┬───────┘
                                                    │
                                                    ▼
                                            ┌───────────────┐
                                            │ #69 Project   │
                                            │ Abstraction   │
                                            └───────────────┘

Independent (can do anytime):
┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐
│ #74    │ │ #77    │ │ #56    │ │ #19    │ │ #71    │
│setLevel│ │Ollama  │ │ Ping   │ │Logging │ │Filter  │
└────────┘ └────────┘ └────────┘ └────────┘ └────────┘
```

---

## Discussion Points for Tomorrow

1. **Bath-driven-development**: What insights emerged? 🛁

2. **FSM Stop (#76)**: 
   - Do we want interrupt capability for in-flight actions?
   - Or just wait for current action to complete?

3. **Lifecycle (#73)**:
   - Should bridges outlive FSMs?
   - What's the use case for shared bridges?

4. **Parallelism (#73a)**:
   - `pmap` vs `future` vs core.async?
   - Error handling strategy for parallel calls?

5. **Ollama (#77)**:
   - Which models to recommend?
   - Structured output reliability?

6. **Priority**:
   - Is local dev (Ollama) more urgent than robustness (cancellation)?
   - Demo (#55) vs infrastructure?

---

## My Recommendations

1. **Start with #76** - Everything else is shaky without working stop

2. **Quick wins** - Do #74, #77, #56 early for morale and utility

3. **Defer #69, #33** - These are big architectural pieces that need the foundation solid first

4. **#73 is really 3 stories** - Split into:
   - 73a: Parallelism (easy)
   - 73b: Lifecycle (medium)  
   - 73c: FSM-level hats (needs design)

5. **Test with Ollama** - Cheaper iteration than cloud APIs during development

---

*Ready to discuss when you're back from the bath! 🧼*
