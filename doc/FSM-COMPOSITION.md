# FSM Composition Patterns

This document captures design decisions and future directions for composing FSMs in CLAIJ.

## Overview

FSMs can be composed in several ways, each with different implications for:
- **Prompt history**: How child FSM activity appears in parent LLM context
- **Control flow**: Sequential, parallel, or event-triggered
- **State persistence**: Memory vs durable for long-running workflows

The key principle: **LLMs don't need to know about FSMs**. We synthesize prompt history that presents composed FSMs as simple "I asked, I received" interactions. This keeps LLM context focused on the domain problem, not our orchestration machinery.

## Composition Patterns

### 1. Sub-FSM (Implemented)

**Pattern**: Parent FSM delegates to child FSM, waits for completion, continues.

```
Parent: start → prepare → [delegate] → collect → end
                              ↓
Child:                   start → process → end
```

**Implementation**: `fsm-action` in `claij.actions`

**Prompt Synthesis**: Child's internal trail is filtered. Parent sees:
```clojure
{:from "delegate" :to "collect"
 :event {"id" ["delegate" "collect"]
         "result" {"answer" "42"}
         "child-trail" {:child-fsm "calculator"
                        :steps 3
                        :first-event {...}
                        :last-event {...}}}}
```

When `trail->prompts` encounters this, it synthesizes something like:
> "A specialized subroutine was invoked and returned: {result}"

The parent's LLM never sees the child's internal deliberations - just the outcome.

**Trail Modes**:
- `:omit` - No child trail info, just result
- `:summary` - FSM id, step count, first/last events
- `:full` - Complete child trail (use sparingly - bloats context)

**Config**:
```clojure
{"id" "delegate"
 "action" "fsm"
 "config" {"fsm-id" "child-calculator"
           "success-to" "collect"
           "failure-to" "error"
           "timeout-ms" 30000
           "trail-mode" :summary}}
```

### 2. Chain (Design Only)

**Pattern**: FSM-A completes, FSM-B starts with A's final context/trail.

```
FSM-A: start → ... → end
                      ↓ (trail continues)
FSM-B:            start → ... → end
```

**Prompt Synthesis**: Full trail concatenation. FSM-B's LLM sees entire conversation history from FSM-A. This is one continuous dialogue that happens to cross FSM boundaries.

**Use Cases**:
- Pipeline processing: Draft → Review → Publish (each a separate FSM)
- Escalation: Tier-1-Support → Tier-2-Support
- Workflow stages with different action sets

**Implementation Sketch**:
```clojure
(defn chain-fsms
  "Run FSMs in sequence, threading trail through."
  [context fsms initial-event]
  (reduce
    (fn [[ctx trail] fsm]
      (let [{:keys [submit await]} (start-fsm ctx fsm)]
        ;; Submit with previous trail as context
        (submit (assoc initial-event :chain/prior-trail trail))
        (await)))
    [context []]
    fsms))
```

**Open Question**: Should FSM-B's entry transition schema require/accept the prior trail? Or should we inject it into context only?

### 3. Fork-Join (Design Only)

**Pattern**: Parent spawns N children in parallel, waits for all, aggregates results.

```
Parent: start → prepare → [fork] ────────────────→ [join] → synthesize → end
                            ↓                         ↑
                     ┌──────┼──────┐                  │
                     ↓      ↓      ↓                  │
Child-1:          start → end ─────────────────────────
Child-2:          start → end ─────────────────────────
Child-3:          start → end ─────────────────────────
```

**Prompt Synthesis**: Aggregated results presented as a batch:
> "Three parallel analyses were conducted:
> - Analyst-1: [summary of result-1]
> - Analyst-2: [summary of result-2]  
> - Analyst-3: [summary of result-3]"

**Implementation Considerations**:
- `fork-action` spawns children with shared completion latch
- `join-action` waits for latch, collects results
- Results arrive as vector: `[{:child-id "1" :result {...}} ...]`
- Next state's schema must expect batch input

**Variants**:
- **Parallel Sub-FSMs**: Same FSM definition, different inputs (map operation)
- **Parallel Chains**: Different FSM definitions, same or different inputs
- **Race**: First to complete wins, others cancelled

**Implementation Sketch**:
```clojure
(defn fork-fsms
  "Run FSMs in parallel, collect all results."
  [context fsm-configs]
  (let [results (atom [])
        latch (java.util.concurrent.CountDownLatch. (count fsm-configs))
        on-complete (fn [child-id result]
                      (swap! results conj {:child-id child-id :result result})
                      (.countDown latch))]
    ;; Start all children
    (doseq [{:keys [id fsm input]} fsm-configs]
      (let [child-ctx (assoc context :fsm/on-complete #(on-complete id %))]
        (future
          (let [{:keys [submit]} (start-fsm child-ctx fsm)]
            (submit input)))))
    ;; Wait and return
    (.await latch)
    @results))
```

### 4. Event-Driven / Pub-Sub (Future)

**Pattern**: FSMs communicate via events on shared bus, loosely coupled.

```
FSM-A: ... → [publish "doc-ready"] → ...

Event Bus: "doc-ready" ──→ triggers

FSM-B: [subscribe "doc-ready"] → start → ... → end
```

**Use Cases**:
- Decoupled microservice-style workflows
- Fan-out notifications
- Reactive pipelines

**Not Yet Needed**: Current use cases are satisfied by sub-fsm and chain patterns. Event-driven adds complexity (event bus infrastructure, message ordering, exactly-once delivery). Defer until clear need emerges.

## Prompt History Synthesis

### The Core Principle

LLMs work best with clean, domain-focused context. They don't need to know:
- That we're using FSMs
- That a "subroutine" was actually another FSM
- The internal deliberation of child workflows

They DO need to know:
- What question was asked
- What answer was received
- Relevant context for their current decision

### Synthesis Functions (To Implement)

```clojure
(defn synthesize-sub-fsm-result
  "Convert child trail summary to LLM-friendly description."
  [{:keys [child-fsm steps first-event last-event]} result]
  ;; Returns string for inclusion in prompt
  (str "A specialized process (" child-fsm ") was invoked. "
       "After " steps " steps, it returned: " (pr-str result)))

(defn synthesize-fork-join-results
  "Convert parallel results to LLM-friendly batch description."
  [results]
  (str "Multiple parallel analyses were conducted:\n"
       (join "\n" (map-indexed 
                    (fn [i {:keys [child-id result]}]
                      (str "- Analysis " (inc i) " (" child-id "): " 
                           (pr-str result)))
                    results))))
```

### Integration with trail->prompts

The `trail->prompts` function needs enhancement to detect and synthesize composed FSM results:

```clojure
(defn trail->prompts [context fsm trail]
  (mapcat
    (fn [{:keys [event] :as entry}]
      (cond
        ;; Sub-FSM result - synthesize
        (contains? event "child-trail")
        [{:role "user" 
          :content (synthesize-sub-fsm-result 
                     (get event "child-trail")
                     (get event "result"))}]
        
        ;; Fork-join result - synthesize batch
        (contains? event "parallel-results")
        [{:role "user"
          :content (synthesize-fork-join-results
                     (get event "parallel-results"))}]
        
        ;; Normal event
        :else
        (standard-event->prompts context fsm entry)))
    trail))
```

## Persistence & Long-Running Workflows

### The Problem

Human-in-the-loop workflows may span hours, days, or weeks:
1. FSM reaches "awaiting-review" state
2. Human reviewer is on holiday
3. Days pass...
4. Reviewer submits approval via web UI
5. FSM must resume from "awaiting-review" state

Current implementation: FSM state lives in memory (channels, atoms). Server restart = lost state.

### Event Sourcing: We're Already There

Our trail IS an event log:
```clojure
[{:from "start" :to "draft" :event {...} :timestamp "2024-12-09T10:00:00Z"}
 {:from "draft" :to "review" :event {...} :timestamp "2024-12-09T10:05:00Z"}
 ;; ... workflow paused, waiting for human ...
 ]
```

The entire FSM state can be reconstructed from:
- FSM definition (id + version, loaded from store)
- Trail (event history)
- Current state (derivable from trail: last entry's `:to`)
- Context (minus transient bits like channels)

### Checkpoint Structure

```clojure
{:checkpoint/id "uuid"
 :checkpoint/created-at "2024-12-09T10:05:00Z"
 
 ;; FSM identity
 :fsm/id "document-review"
 :fsm/version 3
 
 ;; Current position
 :state/current "awaiting-review"
 :state/trail [{...} {...}]
 
 ;; Resumable context (serializable parts only)
 :context/store-id "postgres-prod"
 :context/correlation-id "doc-123"
 :context/user-id "reviewer-456"
 
 ;; Resume trigger
 :resume/webhook-path "/webhooks/fsm/uuid/resume"
 :resume/expected-schema [:map ["approved" :boolean] ["comments" :string]]}
```

### Resume Flow

```
1. Webhook receives POST /webhooks/fsm/{checkpoint-id}/resume
   Body: {"approved": true, "comments": "LGTM"}

2. Load checkpoint from DB

3. Reconstruct FSM:
   - Load FSM definition (fsm-load-version store fsm-id version)
   - Rebuild context (reconnect to store, etc.)
   - DON'T replay trail - just set current state

4. Submit resume event:
   {"id" ["awaiting-review" "approved"]  ; or "rejected"
    "approved" true
    "comments" "LGTM"}

5. FSM continues normally from "approved" state
```

### Why Clojure Wins Here

Contrast with Corda/Temporal's approach:
- They checkpoint JVM stack frames (complex serialization)
- Class versioning nightmares
- Kotlin's type system fights runtime flexibility

Our approach:
- State is EDN data: `(pr-str checkpoint)` → DB
- Resume: `(edn/read-string stored)` → checkpoint
- FSM definitions versioned in store
- No class loader games, no serialization frameworks

### Implementation Priority

**Not Yet**: Current use cases are automated LLM workflows completing in seconds/minutes.

**When Needed**: 
- Human approval workflows
- Multi-day document review processes
- Workflows spanning external service SLAs

**Prerequisites**:
- Correlation ID threading (partially designed)
- Checkpoint storage schema
- Resume webhook infrastructure
- Timeout/expiry handling for abandoned workflows

## Lessons from Corda

Having worked on Corda, key takeaways:

**What Worked**:
- Subflows (analogous to our sub-fsm)
- Event sourcing for audit
- Versioned flow definitions

**What Didn't**:
- JVM stack checkpointing (complex, fragile)
- Static typing friction (Kotlin) for dynamic workflows
- Over-engineering for distributed consensus when simpler solutions sufficed

**Apply to CLAIJ**:
- Keep it simple: EDN in, EDN out
- Defer distribution complexity until proven necessary
- Lean on Clojure's dynamic nature for workflow flexibility

## Summary

| Pattern | Status | Prompt Synthesis | Persistence |
|---------|--------|------------------|-------------|
| Sub-FSM | ✅ Implemented | Synthesize child result | Not needed |
| Chain | 📋 Designed | Full trail continuation | Optional |
| Fork-Join | 📋 Designed | Batch result summary | Optional |
| Event-Driven | 🔮 Future | TBD | Required |
| Long-Running | 🔮 Future | N/A | Required |

Next steps:
1. Implement prompt synthesis for sub-fsm results in `trail->prompts`
2. Test sub-fsm with real LLM workflows
3. Implement chain when pipeline use case emerges
4. Design checkpoint schema when human-in-loop needed
