# The CLAIJ Vision

*Why FSMs as Data Will Transform Agentic AI*

---

## The Problem with Traditional Agentic Pipelines

The current state of agentic AI development looks remarkably like software development circa 2005: complex, fragile, and ripe for disruption.

### What We're Doing Now

- **Complex fragile code in Python** — Pipelines are imperative code, hard to reason about, harder to modify
- **Humans still in the loop** — Slow development iterations, manual intervention required
- **Production downtime on releases** — Traditional deployment means taking systems offline
- **Testing and deployment friction** — Every change requires the full CI/CD ceremony
- **The killer problem** — Any further investment in this approach looks like it will be swept aside by advancements in AI in the near future

We're building agentic systems the same way we built web apps in 2005. And just as web development was transformed by declarative frameworks, agentic AI is ready for its own paradigm shift.

---

## The CLAIJ Solution

**Each pipeline becomes an FSM (Finite State Machine) expressed as data.**

Not code that defines an FSM. Not a DSL that compiles to an FSM. Pure data: States, Transitions, Prompts, Schemas, and Hats.

```clojure
{"id" "code-review"
 "states" [{"id" "mc" "action" "llm" "hats" ["mcp"]}
           {"id" "reviewer" "action" "llm"}]
 "xitions" [{"id" ["start" "mc"]}
            {"id" ["mc" "reviewer"]}
            {"id" ["reviewer" "mc"]}
            {"id" ["mc" "end"]}]}
```

This isn't configuration. This *is* the business logic. The entire workflow is visible, versionable, and modifiable without touching code.

---

## Core Concepts

### Actions: The Verbs

Actions are what states *do*. CLAIJ provides several built-in actions:

| Action | Purpose |
|--------|---------|
| **LLM** | Call any language model (OpenRouter, Ollama, proprietary APIs) |
| **MCP** | Access external tools via Model Context Protocol |
| **DSL** | Domain-specific operations, more token-efficient than MCP |
| **REPL** | Execute Clojure code directly |
| **FSM** | Invoke sub-workflows |

The DSL action deserves special attention: as your system matures, you can evolve domain-specific operations that are far more token-efficient than generic tool calling. And because everything is data, these DSLs can be evolved *on the fly*.

### Hats: Cross-Cutting Concerns

Hats are CLAIJ's answer to aspects, middleware, and decorators. A hat:

- Handles cross-cutting concerns without polluting business logic
- Offers integration points for proprietary systems
- Injects prompts, states, and transitions at config time

```clojure
{"id" "mc"
 "action" "llm"
 "hats" ["mcp"         ;; Tool access
         "retry"       ;; Auto-retry on failure
         "triage"]}    ;; Route errors intelligently
```

**Powerful hat patterns:**

- **Prompt Engineering Hat** — "What if I gave you this prompt - can you improve it?"
- **Society of LLMs Hat** — Multiple models cross-check each other, catching hallucinations
- **Retrospective Hat** — "I don't like the options I've been given, let's talk"
- **Auto-Retry Hat** — Handle transient failures gracefully
- **Triage Hat** — Route problems to appropriate handlers

### Schema-Guided Transitions

Every LLM call is structured:

```
[input-schema] + [input-document] → LLM → [output-schema]
```

The output shape determines which transition fires. Schema mismatch? The system can auto-retry with error feedback, escalate to a triage hat, or fail gracefully.

This isn't just validation—it's *navigation*. The LLM's output literally chooses the next step in the workflow.

### Full Trail: Replay Anything

Every transition is recorded. When something goes wrong in production, you can replay the exact path in development:

```clojure
;; From production logs
{:trail [{:from "start" :to "mc" :event {...}}
         {:from "mc" :to "reviewer" :event {...}}
         {:from "reviewer" :to "mc" :event {...}}  ;; ← Problem here
         ...]}

;; Replay in dev
(replay-trail context fsm production-trail)
```

No more "works on my machine." The trail *is* the test case.

---

## The Architecture

### Clean Separation

| Layer | Nature | Investment Strategy |
|-------|--------|---------------------|
| **Platform** | Small code kernel | Cost centre — minimize |
| **Business Logic** | FSM data | Competitive advantage — maximize |

Your competitive advantage isn't in how you call OpenAI. It's in *what* you tell it to do, *when*, and *how* you orchestrate the conversation. That's the FSM. That's data. That's where you should be investing.

### Self-Descriptive Systems

CLAIJ is built on a philosophical foundation: **for a system to improve itself, it must first understand itself.**

All data is:
- **Immutable** — Every version is preserved
- **Versioned** — Changes are tracked
- **Persistent** — Nothing is lost
- **Self-descriptive** — The system can read its own definition

This enables something profound: the system can improve *itself*.

```clojure
;; The system reads its own FSM
(def current-fsm (load-fsm "code-review"))

;; An LLM suggests improvements
(def improved-fsm (improve-fsm current-fsm feedback))

;; The improvement is validated and reloaded
(reload-fsm! improved-fsm)
;; No restart. No deployment. Immediate effect.
```

### Hot Reload: Change Without Downtime

FSMs are versioned data. They can be improved and reloaded into a live system:

- **No coding** — Changes are data, not code
- **No release process** — Unless you want one
- **No system restart** — Hot reload preserves state
- **Instant turnaround** — See changes immediately

You can map a data release process to your current code release process. Or you can bypass it entirely for development. The choice is yours.

---

## Why Clojure?

CLAIJ is written in Clojure for deep technical reasons:

1. **Homoiconicity** — Code is data. FSMs are data. Schemas are data. It's data all the way down.

2. **REPL-Driven Development** — Immediate feedback, live system modification, the fastest iteration possible.

3. **Immutable by Default** — Trail replay, version control, and self-improvement all depend on immutability.

4. **EDN** — The same format for FSMs, schemas, prompts, and LLM communication. No JSON marshalling, no special modes.

> *"Lisp isn't a language, it's a building material."* — Alan Kay

Clojure lets us build the building material for agentic AI.

---

## The Future

CLAIJ is infrastructure for a world where:

- Business logic is data, not code
- Systems improve themselves
- Changes happen without deployment
- Every production issue is reproducible
- AI agents are coordinated, not chaotic

The factory is the product. The system builds itself. And it's all just data.

---

## Get Involved

CLAIJ is experimental but the architecture is sound. If you're building agentic systems and want to escape the Python/imperative trap, let's talk.

📧 Contact: [Jules Gosnell](https://github.com/JulesGosnell)

📖 [Technical Architecture →](ARCHITECTURE.md)

🧠 [Philosophical Foundation →](SELF-DESCRIPTIVE-SYSTEMS.md)
