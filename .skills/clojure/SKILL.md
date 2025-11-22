# Clojure Style Skill

## Naming
- Symmetric pairs: open/close, start/stop
- Short clear: get not retrieve
- Verbs for actions, nouns for data

## Namespaces
Prefer :refer over :as (except log, json, async)

## Destructuring
Explicit named bindings over :keys/:strs
Favor destructuring over get/get-in

## Performance
Enable reflection warnings, use type hints

## Style
- Immutable by default
- Pure functions when possible
- Data-driven (maps/vectors over objects)
- Threading macros
- Small composable functions (<20 lines)

## Concurrency
Atoms, core.async, persistent collections
Clear for humans AND LLMs
