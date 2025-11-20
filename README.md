# CLAIJ - "...an elastic collection of LLMs able to coordinate on the execution of a self-improving process..."

[![CI](https://github.com/JulesGosnell/claij/actions/workflows/ci.yml/badge.svg)](https://github.com/JulesGosnell/claij/actions/workflows/ci.yml)

## Disclaimers

A few disclaimers:

- This project is currently in experimental mode
- This project is trying to eat its own dogfood - it is largely AI generated and consequently a mess

So, please don't jump in thinking this is production-ready or that this is the way that I write code, because neither could be further from the truth.

However, the way that software development is done is changing forever, and CLAIJ allows me to both experience this and potentially influence it.

## The Core Idea

I started with a gut-feeling: *The whole is greater than the sum of its parts*. A society of LLMs ([The Society of Mind - Minsky](https://en.wikipedia.org/wiki/Society_of_Mind)) would do a better job of software development than a single LLM working in isolation.

I had a couple of other intuitions:

- An LLM is like a junior developer
  - The more structure I can give them, the better they will perform
  - The fewer concerns I ask them to worry about, the better job they will do on the stuff I really want focused on

So my initial goal was simply to get a bunch of LLMs to cooperate on a shared goal.

## Token Efficiency

I had one other premise: the most important thing in any new LLM-accelerated project going forward will be "bang-for-token".

If you assume that the conversation between LLMs and their development boxes will be some structured format and not just plain text, this opens the door to the question: *"I want to save tokens - What is the most concise shape that this protocol could take?"* 

The answer seems to be, by definition: **A Domain Specific Language (DSL)**—a language devised explicitly for the problem at hand. Furthermore, this DSL needs to evolve dynamically with the project. As the project moves forward, the DSL will need extending to accommodate new concepts and requirements, and we don't want to have to keep manually extending, rebuilding, and redeploying the server.

Fortunately, I have a background in LISP.

> "Lisp isn't a language, it's a building material." - Alan Kay

LISP's (Clojure henceforth—my favorite language) homoiconicity means that it is essentially a DSL for building DSLs.

## Initial Experiments

So the initial idea for the project was a bunch of LLMs all coordinated by talking to each other on a chat channel that was actually a Clojure REPL (like an interpreter/compiler rolled into one).

I played with that for a while and soon came to the conclusion that it quickly led to chaos, and that the conversation itself suffered from all the issues I had identified above. Whilst a cool idea, just throwing everyone at a REPL wouldn't fly.

I considered what had been achieved with LangChain and LangGraph, but neither went far enough for me, so I've rolled my own experimental code to do the following:

## Current Implementation

### Done:

- Define a Finite State Machine (FSM) as a data structure (JSON)
  - To cross a transition, you must be a JSON document conformant to that transition's JSON Schema
  - When you get to a state it may:
    - Be an LLM, in which case the input transition's schema and document, plus an output schema reflecting the transitions leaving this state, are sent to the LLM
    - **(WIP)** Be an MCP bridge, in which case the underlying MCP service is sent the input (request) and returns a response as output
    - **(In future)** Be a Clojure REPL, which will evaluate the input producing an output—keeping the DSL dream (above) alive
    - Handle other custom requirements as they arrive
- The state's output is then matched against the transitions leaving that state—rinse and repeat as you traverse the FSM

### Proof of Concept

As a proof of concept, I've defined a code-review-fsm with a Master of Ceremonies (MC) and bunch of reviewers. The MC is given some code, chooses and requests some other LLMs to review the code. He iterates around this loop until he is not seeing any new issues, summarizes the changes and the code, and exits at the end of the FSM.

This idea seems to work very well.

## Future Directions

### Rebootstrapping

To rebootstrap the whole project on itself—proof that the concept works:

**TODO:**

- Define an fsm-fsm—i.e., an FSM that can produce a new or improve an existing FSM
- Use that to define new FSMs for reviewing and refactoring a whole software project
- Run this on itself

This would also allow, for example:
- Definition of a Kanban workflow
- Definition of a TDD development process
- Definition of any workflow you could describe to the LLMs or they could mine for themselves from the web

### Self-Improvement

Another important aspect: at any point in the FSM, an LLM should be able to decide to enter the fsm-fsm and kick off a process to improve the current FSM—a mini-retrospective, if you like. Once the improvements (better prompts, data structures, or DSL extensions, etc.) have been made, the new version of the FSM should be loaded and the LLM should be able to continue from the current state into the new version of the FSM.

If I can get this going, then theoretically we have an elastic collection of LLMs able to coordinate on the execution of a self-improving process...

**Watch this space...**

BTW - I am looking for a job at the moment - so if you have a Clojure project, particularly one using Agents etc., on which you think I might be able to be useful, please [get in touch](https://www.linkedin.com/in/jules-gosnell-15952a1/)

### Visualisation

I've just added some code that allows me to view CLAIJ's FSMs as a graph diagram - So, to be clear, these are visual representations of runnable FSMs. As soon as I have MCP working and an FSM-FSM for building FSMs, I will build out and document an FSM library. Here are a couple of examples:

## A Multi-LLM Code Review FSM.
- ![Code Review FSM](doc/code-review-fsm.svg)

## WIP - An MCP integration that uses the FSM platform to manage the MCP protocol itself
- ![MCP FSM](doc/mcp-fsm.svg)
