# claij

[![CI](https://github.com/JulesGosnell/claij/actions/workflows/ci.yml/badge.svg)](https://github.com/JulesGosnell/claij/actions/workflows/ci.yml)

**Clojure AI Integration Junction** - A playground and proof-of-concept for orchestrating AI technologies through Clojure.

## Vision

**claij** is an AI integration hub that uses Clojure as the coordination layer to connect multiple AI models, tools, and interaction modalities. The goal is to create a unified platform where different AI technologies can be seamlessly orchestrated through an elegant Clojure-based DSL.

Think of it as a **"Clojure AI conductor"** - leveraging Clojure's strengths in data transformation and functional composition to wire together the modern AI stack.

**Bootstrapping AI with AI**: claij aims to not only provide AI acceleration to other projects but to be AI-accelerated itself. We're bootstrapping our way out of the chicken-and-egg problem - using AI to build better AI tooling. The project sits alongside developers in their natural workflow, enabling communication via speech and text directly from Emacs or a Clojure REPL.

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

### MCP Bridges

**claij** is currently experimenting with several MCP (Model Context Protocol) bridges to deeply integrate AI into the development workflow:

- **clojure-mcp** - Provides AI access to bash commands, Clojure REPL evaluation, file operations, and project introspection
- **emacs-mcp** - Enables AI to work directly within Emacs alongside the developer
- **clojure-language-server** - Gives AI deep understanding of Clojure projects through LSP capabilities

While we're currently focused on building a complete Clojure development environment (since claij itself is written in Clojure), the architecture is language-agnostic. Any language with an MCP integration and any editor/IDE with MCP support could leverage the same approach.

### Current Components

- **Agent Bridges** (`src/claij/agent/bridge.clj`) - MCP protocol communication
- **LLM Integrations** - Individual adapters for each AI provider
- **Request Transformation** (`src/claij/agent/xform.clj`) - Data transformation pipelines
- **Configuration** (`src/claij/agent/config.clj`) - Centralized configuration management

## Development

### Running Tests

```bash
# Unit tests (no Python required) - runs everywhere including CI
clojure -M:test --skip integration

# All tests including integration tests (requires Python environment)
clojure -M:whisper:test
```

The project uses a two-tier testing strategy:
- **Unit tests** - Fast, no external dependencies, run on any machine
- **Integration tests** - Require Python ML stack, test Whisper service with libpython-clj

This allows the project to build and test in environments lacking Python/GPU (like CI runners) while still having comprehensive tests for the full system.

## Setup

### Clojure Components

Requires:
- Java 21+
- Clojure CLI tools

Install dependencies:
```bash
clojure -P  # Download all dependencies
```

### Speech-to-Text Service

**claij** includes a Whisper speech-to-text service implemented in pure Clojure using `libpython-clj` to call Python's Whisper library.

**Features:**
- **All in-memory processing** - No temporary files for better performance
- **Pure Clojure** - Ring/Jetty HTTP server with functional composition
- **Optional Python dependency** - Project builds and tests without Python environment
- **Clean architecture** - Decomposed into testable modules

**Architecture:**
- `claij.whisper.python` - Python interop layer (model loading, transcription)
- `claij.whisper.audio` - Audio processing utilities (WAV conversion, validation)
- `claij.whisper.handler` - Ring HTTP handlers (routing, request/response)
- `claij.whisper.server` - Server lifecycle management

**Requirements:**

*Development (no Python needed):*
- Java 21+ and Clojure CLI tools
- Unit tests run without Python environment

*Production (Python required):*
- Python 3.8+ with: `openai-whisper`, `torch`, `numpy`, `soundfile`
- NVIDIA GPU with CUDA (recommended) or CPU fallback

**Setup and Run:**

```bash
# Install Python dependencies (production only)
pip install openai-whisper torch numpy soundfile

# Download Clojure dependencies
clojure -P -M:whisper

# Run the service
./bin/whisper.sh                    # defaults: port 8000, host 0.0.0.0, model small
./bin/whisper.sh 9000               # custom port
./bin/whisper.sh 8000 localhost     # custom host
./bin/whisper.sh 8000 0.0.0.0 tiny  # tiny model (faster, less accurate)
```

**API Endpoints:**
- `POST /transcribe` - multipart/form-data with `audio` field (WAV, 16kHz) → `{"text": "..."}`
- `GET /health` - `{"status": "healthy", "service": "whisper-clojure"}`

**Key Benefits:**
- How Clojure can leverage Python's ML ecosystem via libpython-clj
- Functional composition and clean architecture principles
- In-memory processing for better performance
- Graceful degradation (builds/tests everywhere, runs where Python is available)

### Speech Client (Clojure)

Record and transcribe speech:
```bash
./bin/speech.sh                                    # Use default service URL
./bin/speech.sh http://custom-host:8000/transcribe # Custom service URL
WHISPER_URL=http://host:8000/transcribe ./bin/speech.sh # Via environment
```

The client will:
1. Listen for speech (detected by audio level)
2. Record until 1 second of silence
3. Send audio to Whisper service
4. Display transcribed text
5. Repeat

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
- **Python interop** - Via libpython-clj, can leverage Python's ML ecosystem while maintaining Clojure's functional elegance

**Showcasing Clojure**: Ultimately, claij should demonstrate Clojure's exceptional suitability for AI orchestration and coordination tasks. The Clojure Whisper service is a prime example - leveraging Python's ML libraries while providing better architecture, in-memory processing, and graceful degradation. While much of the current codebase is AI-generated (and thus not yet as elegant as hand-crafted Clojure), the project aims to evolve into a showcase of idiomatic Clojure's expressiveness and power in the AI domain.

## Contributing

**We're looking for talent!** claij is an ambitious project at the intersection of AI, developer tooling, and functional programming. We're particularly interested in contributors who are passionate about:

- **Clojure best practices** - Help us refine AI-generated code into idiomatic Clojure
- **AI/ML integration** - Experience with LLMs, speech recognition, or AI orchestration
- **Developer experience** - Building tools that developers love to use
- **MCP protocol** - Extending our bridges or creating new ones
- **Testing and reliability** - Making the system robust and production-ready

Whether you're a Clojure expert who wants to explore AI, or an AI practitioner interested in functional programming, we'd love to have you involved. Check out the issues, join discussions, or just reach out!

## License

Apache License 2.0 - See [LICENSE](LICENSE) file for details.

---

**Note**: This is an experimental project. APIs and architecture are subject to change as different approaches are explored.
