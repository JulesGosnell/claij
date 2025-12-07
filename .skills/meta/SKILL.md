# Meta Skill: Skill Management

## When to Suggest New Skills
- Repeated explanations of same patterns (3+ times)
- Domain-specific knowledge emerging
- New coding patterns crystallizing
- Tool-specific workflows developing
- Project areas growing in complexity

## How to Suggest
💡 SKILL SUGGESTION: [name]
Reason: [why helpful]
Content: [brief outline]

## Skill Structure
- Terse (500-1000 tokens ideal)
- Action-oriented
- Example-rich
- Scannable

## Loading Pattern (FSM-like)
1. Enter context
2. Load skill
3. Apply knowledge
4. Exit context (GC, keep summary)

## Current Skills
token, test, clojure, development, claij, meta, decomposition

## Emerging Patterns

### Action Schemas (Future FSM)
Actions should carry their own schemas as metadata, enabling:
- Open extensibility (add actions without changing FSM schema)
- Two-phase validation: structural (FSM) → semantic (action params)
- Self-describing DSL of schema-carrying functions

Implementation pattern:
```clojure
(defn my-action
  {:action/schema [:map ["param1" :string] ["param2" {:optional true} :int]]}
  [context fsm ix state event trail handler]
  ...)
```

def-fsm validates:
1. FSM structure against FSM schema
2. Each state's params against its action's :action/schema metadata

This pattern should ultimately become an FSM that manages the action DSL.

Remember: Skills are live docs - suggest updates when patterns evolve
