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

## Backend Setup

**claij** uses a protocol-based architecture for AI backends, making it easy to swap between different implementations. Each backend type (STT, TTS, etc.) defines a protocol that all implementations must satisfy.

### Speech-to-Text Backends (STT)

#### Whisper (OpenAI)

**Overview:**
- State-of-the-art speech recognition
- Runs locally (no API costs)
- Supports 99 languages
- Requires CUDA GPU (recommended) or CPU

**Installation:**

```bash
# 1. Install Python dependencies
pip install openai-whisper torch numpy soundfile

# 2. Test installation (optional)
python3 -c "import whisper; print('Whisper installed successfully')"

# 3. Download Clojure dependencies
clojure -P -M:whisper

# 4. Start the service
./bin/stt.sh                    # defaults: port 8000, host 0.0.0.0, model small
./bin/stt.sh 9000               # custom port
./bin/stt.sh 8000 localhost     # custom host  
./bin/stt.sh 8000 0.0.0.0 tiny  # tiny model (faster, less accurate)
```

**Model Sizes:**
- `tiny` - Fastest, least accurate (~1GB VRAM)
- `small` - Good balance (default) (~2GB VRAM)
- `medium` - Better accuracy (~5GB VRAM)
- `large-v3` - Best accuracy (~10GB VRAM)

**API Endpoints:**
- `POST /transcribe` - multipart/form-data with `audio` field (WAV, 16kHz) → `{"text": "...", "language": "en"}`
- `GET /health` - Service health check

**Troubleshooting:**
- **No GPU detected**: Whisper will fall back to CPU (much slower but works)
- **CUDA out of memory**: Try a smaller model size
- **Import errors**: Ensure all Python packages are installed in the same environment

### Text-to-Speech Backends (TTS)

#### Piper (Rhasspy)

**Overview:**
- Fast, local text-to-speech
- High-quality neural voices
- **No GPU required** - runs on CPU
- Multiple languages and voice styles
- Small model files (~100MB per voice)

**Installation:**

```bash
# 1. Install Python dependencies
pip install piper-tts

# 2. Create voice models directory
mkdir -p ~/piper-voices
cd ~/piper-voices

# 3. Download a voice model (example: US English, medium quality)
# See https://github.com/rhasspy/piper/releases for all available voices
curl -L -o en_US-lessac-medium.onnx \
  'https://github.com/rhasspy/piper/releases/download/v1.2.0/en_US-lessac-medium.onnx'

# Optional: Download voice config file
curl -L -o en_US-lessac-medium.onnx.json \
  'https://github.com/rhasspy/piper/releases/download/v1.2.0/en_US-lessac-medium.onnx.json'

# 4. Test installation
python3 -c "import piper; print('Piper installed successfully')"

# 5. Download Clojure dependencies
clojure -P -M:piper

# 6. Start the service
export PIPER_VOICE_PATH=~/piper-voices/en_US-lessac-medium.onnx
./bin/tts.sh                    # defaults: port 8001, host 0.0.0.0

# Or specify voice path inline:
./bin/tts.sh 8001 0.0.0.0 ~/piper-voices/en_US-lessac-medium.onnx
```

**Available Voices:**

Popular English voices:
- `en_US-lessac-medium` - Clear US English (recommended)
- `en_GB-alan-medium` - British English
- `en_US-amy-medium` - US English (female)
- `en_US-ryan-high` - US English (high quality, larger file)

Find all voices at: https://github.com/rhasspy/piper/blob/master/VOICES.md

**API Endpoints:**
- `POST /synthesize` - Accepts text (JSON: `{"text": "..."}` or plain text) → Returns WAV audio
- `GET /health` - Service health check

**Testing the Service:**

```bash
# Test with curl (saves to output.wav)
echo "Hello from Piper" | curl -X POST http://localhost:8001/synthesize \
  -H "Content-Type: text/plain" \
  --data-binary @- \
  -o output.wav

# Play the audio
aplay output.wav  # or paplay output.wav on PulseAudio systems

# Test with JSON
curl -X POST http://localhost:8001/synthesize \
  -H "Content-Type: application/json" \
  -d '{"text": "Hello from Piper"}' \
  -o output.wav
```

**Troubleshooting:**
- **"voice-path is required"**: Set `PIPER_VOICE_PATH` environment variable or pass as argument
- **Import error**: Ensure `piper-tts` is installed: `pip list | grep piper`
- **Model not found**: Check the .onnx file path is correct and file exists
- **No audio output**: Ensure your system has `aplay` (ALSA) or `paplay` (PulseAudio)

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

## Future Directions

- **Structured Responses with Interceptor Architecture**: A comprehensive redesign of LLM interactions around validated JSON schemas and composable interceptors. See [doc/STRUCTURED_RESPONSES.md](doc/STRUCTURED_RESPONSES.md) for detailed design.
  
  **Core concepts:**
  - Every LLM response must conform to a JSON schema (validated via M3)
  - Invalid responses trigger a validation-retry loop with detailed error feedback
  - Interceptors provide three hooks: modify schema, modify prompts, interpret responses
  - Base schema starts minimal (`{answer, state}`) and grows through interceptor composition
  - Schema extensions are validated and audited (who, when, what)
  - Backend adapters handle provider-specific transformations
  - Designed to support: memory strategies, MCP integration, REPL evaluation, FSM state transitions, and future extensions not yet conceived
  
  This architecture enables mid-conversation protocol evolution and provides a foundation for all advanced features like shared memory, tool calling, and multi-agent coordination.

- **Hats as Dynamic Roles**: Introduce 'hats' as assignable roles (e.g., toolsmith, tech lead) that define AI behaviors, allowing multiple instances to collaborate on a shared channel. Hats enable specialized duties, like a toolsmith monitoring for code repetition and refactoring into libraries, fostering team-like dynamics where AIs address each other or respond only when relevant, reducing chaos in multi-agent interactions.

- **Evolving DSL for Efficiency**: Empower the toolsmith hat to extract and build a domain-specific language (DSL) from repeated patterns, minimizing token usage across the team. As AIs collaborate, the DSL evolves into shorthand functions (e.g., dsl.write_file_with_retry), broadcast to others for reuse, transforming verbose exchanges into concise calls and optimizing long-term project efficiency.

- **Finite State Machine Governance**: Overlay a FSM to structure conversations, defining states like planning, coding, and testing, with transitions triggered by JSON flags rather than keywords. This prevents broadcast storms by muting irrelevant hats per state, supports branching for issues like blockers, and allows mid-project team spin-ups, making workflows agile and predictable. **See [doc/WORKFLOW.md](doc/WORKFLOW.md) for the complete workflow specification.**

  **Sub-FSM Patterns for Team Workflows**:
  
  - **Vote Sub-FSM**: When the team needs to make a decision about design choices, implementation approaches, or conflicting proposals:
    - Requires a master-of-ceremonies hat to manage the voting process
    - States: proposal → discussion → voting → tally → declaration
    - The MC hat collects votes, prevents vote brigading, and declares the winner
    - Can be nested within other FSMs (e.g., during code review or refactoring decisions)
  
  - **Review Sub-FSM**: After a dev hat produces code, other dev hats should review it:
    - States: submission → parallel-review → discussion → resolution
    - Re-entrant: if major issues found, can loop back through revision → review
    - Typically ends with a vote sub-FSM if reviewers disagree
    - Review comments are collected and addressed systematically
    - Only the reviewing hats are unmuted during review phase
  
  - **Make-Tool Sub-FSM**: When the toolsmith hat identifies repeated patterns worth extracting into the DSL:
    - States: pattern-detection → proposal → review → implementation → dsl-update → reindex
    - Ends with code review (enters review sub-FSM)
    - Upon approval, updates the DSL and broadcasts the new function signature
    - Triggers reindexing of the DSL documentation
    - New DSL index is included with subsequent LLM requests
    - Reduces token costs across the team by replacing verbose patterns with concise DSL calls
  
  - **Refactor Sub-FSM**: For improving existing code structure without changing behavior:
    - States: identify-target → propose-refactoring → impact-analysis → implementation → testing → vote
    - Multiple dev hats can propose different refactoring approaches
    - Impact analysis phase determines which code/tests will be affected
    - Ends with a vote if multiple approaches are viable
    - Includes rollback state if tests fail after refactoring
  
  - **Debug Sub-FSM**: For reproducing bugs and establishing test cases:
    - States: reproduce → provide-test → verify-test-fails
    - Goal: Create a failing test that captures the bug
    - Transitions into Fix-Test Sub-FSM once test is established
    - Ensures bugs are captured as tests before attempting fixes
  
  - **Fix-Test Sub-FSM**: For systematic bug fixing with test-driven approach:
    - Entered from Debug Sub-FSM with a failing test in hand
    - States: hypothesize → instrument → test-hypothesis → (fix | eliminate-hypothesis) → verify-test-passes
    - Can spawn multiple parallel hypothesis-testing branches
    - Each hypothesis tested by a different dev hat instance
    - Reconverges when a fix makes the test pass or all hypotheses eliminated
    - If all hypotheses fail, may loop back to Debug Sub-FSM to refine the reproduction test
  
  - **Feature-Development Sub-FSM**: For implementing new features end-to-end:
    - States: requirements → design → vote-on-design → implementation → test → review → integration
    - Can nest other sub-FSMs (vote, review, refactor, make-tool)
    - Coordinates multiple hats: architect for design, devs for implementation, QA for testing
    - Maintains feature branch state separate from main development state

- **Permutation City Forking and Merging**: Inspired by Greg Egan's Permutation City, implement forking of AI instances for parallel problem-solving, using channels and futures to create lazy lists of responses ordered by completion time. When forks complete, a merge process (via a dedicated hat) resolves conflicts and integrates insights back into the main state, enabling speculative execution with rollbacks for failed branches, accelerating development through concurrent exploration.

- **Parallel Versioning of State with Intermediate Summaries**:
 - Versioning Per LLM: Maintain state versions on a per-LLM basis, using a tree structure (vector of vectors) for nested subversions. Paths like [0 1 2] serialize to 0.1.2, enabling parallel forks from any parent without hashing overhead.
 - Summary Layering: Generate summaries only at the current level, treating base summaries as shared user-role messages. Clones build sub-summaries from the base, minimizing redundancy.
 - Merge Detection: Incorporate special merge nodes in the tree structure, flagged with {:type :merge, :base-summary "...", :sub-summaries [...], :merged-summary "..."}. This allows easy identification of merges during traversal or replay.

## Documentation

### Architecture and Design

- **[doc/WORKFLOW.md](doc/WORKFLOW.md)** - Complete FSM-driven development workflow specification
  - Kanban board structure and Meta-Loop (Retrospective → Planning)
  - Story Loop with zone management and conflict prevention
  - All 9 Sub-FSMs (Vote, Review, Make-Tool, Refactor, Debug, Fix-Test, API-Design, Implement, Feature-Development)
  - State muting rules and hat orchestration
  - Integration with MCP and DSL evolution
  - **FSM Library Vision** - Roadmap for encoding workflows in executable Clojure DSL
  - Visualizations:
    - [High-level workflow (SVG)](doc/workflow.svg) | [Source (DOT)](doc/workflow.dot)
    - [Story processing FSM (SVG)](doc/story.svg) | [Source (DOT)](doc/story.dot)

- **[doc/STRUCTURED_RESPONSES.md](doc/STRUCTURED_RESPONSES.md)** - Comprehensive JSON schema-based response architecture
  - Validated responses with M3
  - Interceptor patterns for extensibility
  - Validation-retry loops
  - Schema composition and auditing

### Development Guidelines

- **[doc/CODING_GUIDELINES.md](doc/CODING_GUIDELINES.md)** - Comprehensive coding style guide and best practices
  - Core values and philosophy
  - Naming conventions and code structure
  - Functional programming patterns
  - Performance optimization (avoiding reflection)
  - Testing and error handling guidelines

### Technical Documentation

- **[doc/NAMESPACE_REFACTORING.md](doc/NAMESPACE_REFACTORING.md)** - Namespace organization and refactoring notes
- **[doc/REFLECTION_FIX.md](doc/REFLECTION_FIX.md)** - Details on reflection handling and fixes

## License

Apache License 2.0 - See [LICENSE](LICENSE) file for details.

---

**Note**: This is an experimental project. APIs and architecture are subject to change as different approaches are explored.
