# CLAIJ Expertise Skill

## FSM Architecture
State machines as explicit data with schema-validated transitions

Actions signature: (fn [context fsm ix state trail handler] ...)
Context threads through all actions
Actions return updated context

## Context Threading (Issue 5 DONE)
Actions receive context, return updated context to handler
Context accumulates state (caches, counters, etc)

## MCP Integration (Issue 4 IN PROGRESS)
Paused for thread coordination infrastructure

Cache helpers in claij.mcp:
- initialize-mcp-cache
- invalidate-mcp-cache-item  
- refresh-mcp-cache-item
- merge-resources

MCP FSM: starting -> shedding -> initing -> servicing -> caching <-> llm -> end

## Current Status
Issue 5: Context Threading DONE
Issue 4: Cache Construction PAUSED for thread coordination
Focus: Thread coordination infrastructure

## FSM = Skill Insight
Enter state = Load skill
Perform actions = Use skill
Exit state = GC skill, keep summary
