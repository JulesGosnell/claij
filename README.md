# claij

[![CI](https://github.com/JulesGosnell/claij/actions/workflows/ci.yml/badge.svg)](https://github.com/JulesGosnell/claij/actions/workflows/ci.yml)

**Clojure AI Integration Junction** - A playground and proof-of-concept for orchestrating AI technologies through Clojure.

## Vision

**claij** is an AI integration hub that uses Clojure as the coordination layer to connect multiple AI models, tools, and interaction modalities. The goal is to create a unified platform where different AI technologies can be seamlessly orchestrated through an elegant Clojure-based DSL.

Think of it as a **"Clojure AI conductor"** - leveraging Clojure's strengths in data transformation and functional composition to wire together the modern AI stack.

## Architecture

### Integration Points

**claij** connects:

1. **LLM Connections via OpenRouter** - Access to multiple AI models:
   - Claude (Anthropic)
   - GPT (OpenAI)
   - Grok (xAI)
   - Gemini (Google)
   - Any other LLM supported by OpenRouter

2. **MCP (Model Context Protocol) Backends** - Give AI models access to tools and data sources through the standardized MCP protocol

3. **Text Input from Emacs** - Direct text interaction through your favorite editor

4. **Text Input from Clojure REPL** - REPL-driven AI conversations for interactive development

5. **Voice-to-Text Microservice** - Speech input processing (local or remote)

6. **Coordination DSL** - A Clojure REPL-based DSL to orchestrate complex multi-step AI workflows

### Current Components

- **Agent Bridges** (`src/claij/agent/bridge.clj`) - MCP protocol communication
- **LLM Integrations** - Individual adapters for each AI provider
- **Request Transformation** (`src/claij/agent/xform.clj`) - Data transformation pipelines
- **Configuration** (`src/claij/agent/config.clj`) - Centralized configuration management

## Development

### Running Tests

```bash
./bin/test.sh
```

### Project Status

**claij** is currently a proof-of-concept playground. It's actively evolving as different integration patterns and orchestration strategies are explored.

## Goals

The aim is to create a platform where you can:
- **Switch between AI models seamlessly** - Use the best model for each task
- **Give AI tools via MCP** - Enable AI to interact with your development environment
- **Interact through multiple modalities** - Text (Emacs/REPL) or voice
- **Orchestrate complex workflows** - Chain together AI operations through Clojure code
- **Experiment freely** - A sandbox for exploring AI integration patterns

## Why Clojure?

Clojure is ideal for this kind of integration work:
- **Data-oriented** - AI interactions are fundamentally about transforming data
- **Functional composition** - Chain operations elegantly
- **REPL-driven** - Interactive development matches interactive AI workflows
- **JVM and ClojureScript** - Can run anywhere (backend, frontend, mobile)
- **Rich ecosystem** - Great libraries for HTTP, async, JSON, WebSockets, etc.

## License

[To be determined]

---

**Note**: This is an experimental project. APIs and architecture are subject to change as different approaches are explored.
