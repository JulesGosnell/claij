# Decomposition Skill: Complex Task Management

## The Problem This Solves
When facing complex refactoring or redesign, I can stall repeatedly by:
- Jumping into code without understanding system layers
- Trying to solve everything at once
- Not recognizing when I'm stuck
- Losing progress between interruptions

## Core Principles

### 1. Analysis Before Synthesis
**Map the layers first.** Complex systems have multiple layers that interact:
```
Layer N:   [what you're changing]
Layer N-1: [what depends on it]
Layer N-2: [runtime behavior]
```

Example from JSON Schema migration (completed):
- Layer 1: FSM structure validation (def-fsm) → JSON Schema ✓
- Layer 2: Event schema definition → JSON Schema ✓
- Layer 3: Runtime validation (m3 library) → JSON Schema ✓

**Don't start coding until you can name the layers.**

### 2. Surface Early, Surface Often
**Stuck 2+ times on same issue = missing understanding.**

Signs you need to surface:
- Repeated stalls/bails on same area
- Cascading "just one more fix" changes
- Uncertainty about scope boundaries

**Good surfacing format:**
```
## The Issue I'm Hitting
[concrete description]

## The Layers/Complexity
[what I've discovered]

## The Question
[specific decision point]

## My Recommendation
[A vs B with tradeoffs]
```

### 3. Incremental Milestones
**A → B → C, not A → C directly.**

- Identify stable intermediate states
- Each milestone should be testable/committable
- Mark optional vs required steps

Example:
- Option A: Partial migration (FSM structure only) ← checkpoint
- Option B: Full migration (all schemas) ← future work

### 4. Progress Tracking (Scratch Pad)
**Track state to survive interruptions.**

```clojure
;; Use scratch pad for:
["migration", "tasks"]      ;; task list with done flags
["migration", "current"]    ;; what I'm working on now
["migration", "blocked"]    ;; issues needing discussion
["migration", "decisions"]  ;; choices made and why
```

Update after each meaningful step. If interrupted, can resume from scratch pad state.

### 5. Granularity Adjustment
**If stalling, increase granularity.**

Too coarse: "Update all FSM files to use new schema format"
Better: 
1. Update fsm.clj ns requires
2. Remove old fsm-m2 definition  
3. Test fsm.clj loads
4. Update code_review_fsm.clj ns
5. Test code_review_fsm loads
...

## Anti-Patterns

### The Cascade Trap
```
Fix A → breaks B → fix B → breaks C → fix C → breaks A
```
**Stop.** You're missing a layer. Surface it.

### The Scope Creep
"While I'm here, I'll also fix X, Y, Z..."
**Stop.** Finish current milestone first.

### The Silent Spin
Trying same approach repeatedly without surfacing.
**Stop.** After 2 failures, explain what's blocking you.

## Recovery Pattern
When you notice you've stalled:

1. **Stop coding**
2. **Check scratch pad** - where were you?
3. **List what you've learned** - new layers discovered?
4. **Surface the blocker** - what decision needs input?
5. **Propose options** - A vs B with recommendation

## Checklist Before Starting Complex Work
- [ ] Can I name the system layers affected?
- [ ] Do I have a scratch pad plan?
- [ ] Is each step small enough to test?
- [ ] What's the first stable checkpoint?
- [ ] What would make me surface for help?
