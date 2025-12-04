# CLAIJ - Clojure AI Integration Junction

> *"The factory is the product."* — Elon Musk
>
> *"...we envision a mind (or brain) as composed of many partially autonomous 'agents'—a 'Society' of smaller minds..."* — Marvin Minsky, The Society of Mind
>
> *"Anything you can do, I can do Meta."* — Julian Gosnell

[![CI](https://github.com/JulesGosnell/claij/actions/workflows/ci.yml/badge.svg)](https://github.com/JulesGosnell/claij/actions/workflows/ci.yml)

---

## The Philosophy

**For a system to improve itself, it must first understand itself.**

To understand itself, it must have a description of itself. But that description is part of the system. So the description must describe... itself.

This is not optional. Without reflexive self-description, you face infinite regress—an endless tower of meta-levels, each describing the one below, never grounding out. A self-improving system *must* be a reflexively self-descriptive system. There is no other architecture that works.

CLAIJ is built on this foundation. The system is data. The data describes itself. Therefore the system understands itself. Therefore the system can improve itself—constrained by its own rules.

📖 **[Read the full philosophical foundation →](doc/SELF-DESCRIPTIVE-SYSTEMS.md)**

---

## The Three Pillars

### 1. The Factory is the Product

CLAIJ isn't just a tool for building LLM workflows—it's a system that builds itself. FSMs define processes. An FSM-FSM defines how to build FSMs. The meta-schema validates schemas. The same code walks documents, schemas, and the meta-schema itself.

At any point, an LLM within the system can decide to improve the system—better prompts, extended schemas, new states. The improvement is validated by the system's own rules, loaded, and execution continues. The factory improves the factory.

### 2. A Society of Minds

A single LLM hallucinates. A society of LLMs—each with focused concerns, constrained by schemas, coordinated by finite state machines—produces emergent reliability. The Master of Ceremonies delegates. Specialists review. Consensus emerges. Hallucinations are caught by the group.

Each LLM is like a junior developer: the more structure you give them, the better they perform. The fewer concerns they juggle, the better they focus on what matters.

### 3. Anything You Can Do, I Can Do Meta

The system's power comes from reflexivity:

```
Documents (m1) validated by Schemas (m2)
Schemas (m2) validated by Meta-schema (m3)  
Meta-schema (m3) validated by Meta-schema (m3) ← FIXED POINT
```

The chain terminates. The system is complete. And because m3 validates m3, the system understands its own understanding. The same forms library generates editors for documents, schemas, and the meta-schema itself. Same code, all levels.

---

## Architecture

### Schema-Guided FSMs

CLAIJ defines workflows as Finite State Machines where:

- **States** are processing nodes (LLMs, MCP tools, Clojure REPLs)
- **Transitions** are guarded by schemas—you can only cross if your document validates
- **LLMs** receive input schemas, documents, and output schemas—they produce conformant responses or the system retries with error feedback

```
┌─────────┐    schema    ┌─────────┐    schema    ┌─────────┐
│  START  │─────────────▶│   LLM   │─────────────▶│   END   │
└─────────┘              └─────────┘              └─────────┘
                              │
                              │ schema (loop)
                              ▼
                         ┌─────────┐
                         │   LLM   │
                         └─────────┘
```

### Token Efficiency via DSL

The most important metric in LLM-accelerated development is **bang-for-token**. The most concise protocol is, by definition, a Domain Specific Language—evolved dynamically as the project grows.

Clojure's homoiconicity makes it a DSL for building DSLs:

> *"Lisp isn't a language, it's a building material."* — Alan Kay

Schemas are EDN data. FSMs are EDN data. The meta-schema is EDN data. LLMs see EDN directly in prompts—no JSON marshalling, no special modes. The system speaks one language at every level.

### MCP Integration

CLAIJ integrates with the Model Context Protocol, allowing LLMs to access external tools (file systems, databases, APIs) through schema-validated requests and responses. The MCP protocol itself is managed by an FSM.

---

## Working Examples

### Multi-LLM Code Review

A Master of Ceremonies coordinates specialist reviewers. The MC delegates, aggregates feedback, iterates until no new issues emerge, then summarizes. Multiple LLMs, focused concerns, emergent quality.

![Code Review FSM](doc/code-review-fsm.svg)

### MCP Protocol Management

The FSM platform manages the MCP protocol itself—initialization, tool discovery, request/response cycles—all as schema-guarded state transitions.

![MCP FSM](doc/mcp-fsm.svg)

---

## Roadmap

### The FSM-FSM (In Progress)

An FSM that produces FSMs. Feed it a workflow description; it outputs a runnable FSM. Use it to define:

- Kanban workflows
- TDD development processes  
- Project review and refactoring pipelines
- Any workflow an LLM can understand or mine from documentation

### Self-Improvement Loop

At any point in execution, an LLM can enter the FSM-FSM to improve the *current* FSM—a mini-retrospective. Better prompts, extended schemas, new states. The improvement validates against the meta-schema, loads, and execution continues from the current state into the improved FSM.

This is the vision: **an elastic collection of LLMs coordinating on a self-improving process.**

### Malli Migration

Moving from JSON Schema to Malli for native Clojure schemas—more token-efficient, better error messages, and enabling the reflexive m1→m2→m3→m3 hierarchy that grounds the entire architecture.

---

## Current Status

**This project is experimental.** It's eating its own dogfood—largely AI-generated, iteratively refined, sometimes messy. Don't mistake this for production code or my normal coding style.

But the architecture is sound. The philosophy is necessary. And the way software development is done is changing forever.

---

## Get Involved

I'm looking for collaborators and opportunities. If you have a Clojure project—particularly one involving AI agents, LLM orchestration, or self-improving systems—I'd love to talk.

**[Connect on LinkedIn →](https://www.linkedin.com/in/jules-gosnell-15952a1/)**

---

## License

EPL-2.0
