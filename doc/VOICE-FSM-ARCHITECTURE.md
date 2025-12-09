# Voice FSM Architecture

## Overview

This document outlines the architecture for a voice-driven conversational FSM system in CLAIJ. The goal is to create an FSM-orchestrated voice loop that can:

- Record audio input with silence detection
- Transcribe speech to text (STT)
- Process text through LLM-driven sub-FSMs with access to MCP/REPL services
- Synthesize responses back to speech (TTS)
- Play audio output
- Loop continuously with interrupt capability

## STT Technology Landscape

### Current Implementation: Python + libpython-clj

CLAIJ currently uses Python-based Whisper via libpython-clj:

```
┌─────────────────────────────────────────────────────────────┐
│                    Current Stack                            │
├─────────────────────────────────────────────────────────────┤
│  Audio Recording    │ Java Sound API (claij.stt.record)     │
│  Audio Preprocessing│ Python: numpy, soundfile              │
│  STT Model         │ Python: openai-whisper, torch          │
│  Token Decoding    │ Python: whisper tokenizer              │
└─────────────────────────────────────────────────────────────┘
```

**Advantages:**
- Demonstrates Clojure + Python interop (good for CLAIJ's AI story)
- Battle-tested preprocessing and tokenization
- Full Whisper model support (tiny → large)
- Working now on GPU-equipped machines

**Disadvantages:**
- Requires Python environment with CUDA
- Not testable on GitHub CI (no GPU)
- Complex dependency management

### Pure Clojure Path: What Exists

| Component | Purpose | Clojure Library | Status |
|-----------|---------|-----------------|--------|
| ONNX Inference | Run encoder/decoder | diamond-onnxrt (Uncomplicate) | ✅ New, active |
| Audio I/O | Read/write WAV, mic | clojure-sound (Uncomplicate) | ✅ Available |
| FFT/STFT | Frequency transform | thi-ng/fourier, Neanderthal | ⚠️ Partial |
| Mel Spectrogram | Audio → model input | — | ❌ Not available |
| BPE Tokenizer | Decode output tokens | — | ❌ Not available |

**Gap Analysis:**

For pure-Clojure Whisper, two components would need to be written:

1. **Mel Spectrogram Generator** (~200-300 lines)
   - Mel filter bank construction
   - Short-Time Fourier Transform (STFT)
   - Windowing (Hanning)
   - Log-power conversion
   - Must exactly match Whisper's preprocessing

2. **GPT-style BPE Tokenizer** (~150 lines)
   - Load Whisper's vocabulary
   - Decode token IDs to text
   - Handle special tokens (timestamps, language codes)

### Alternative: sherpa-onnx (Subprocess)

sherpa-onnx provides a complete STT solution without Python:

```bash
sherpa-onnx-offline \
  --whisper-encoder=tiny.en-encoder.int8.onnx \
  --whisper-decoder=tiny.en-decoder.int8.onnx \
  --tokens=tiny.en-tokens.txt \
  audio.wav
```

**Advantages:**
- No Python required
- CPU-only (int8 models run on Raspberry Pi)
- CI testable with tiny model (~117MB)
- WAV in → text out API

**Disadvantages:**
- Subprocess overhead
- Another binary to manage
- Less interesting than pure-Clojure or Python interop story

### Recommendation

**Keep Python/libpython-clj as primary** because:
- Working now
- Demonstrates valuable Clojure + Python interop pattern
- GPU acceleration for production quality

**Future exploration:**
- Watch diamond-onnxrt maturation
- Mel spectrogram in Neanderthal could be a fun side project
- sherpa-onnx as fallback for CI testing if needed

## Voice Loop FSM Architecture

### Basic Loop

```
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│    ┌──────────┐    ┌─────────┐    ┌──────────┐    ┌──────────┐  │
│    │          │    │         │    │          │    │          │  │
│ ──►│ recording│───►│ stt-ing │───►│processing│───►│ tts-ing  │  │
│    │          │    │         │    │          │    │          │  │
│    └──────────┘    └─────────┘    └──────────┘    └──────────┘  │
│         ▲                                               │        │
│         │              ┌──────────┐                     │        │
│         │              │          │                     │        │
│         └──────────────│ playback │◄────────────────────┘        │
│                        │          │                              │
│                        └──────────┘                              │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

### State Definitions

```clojure
(def voice-loop-states
  {:start
   {:initial? true
    :transitions [{:to :recording}]}
   
   :recording
   {:description "Record audio until silence detected"
    :action :record-audio
    :action-params {:silence-threshold-db -40
                    :silence-duration-ms 3000
                    :max-duration-ms 30000}
    :transitions [{:event :audio-captured
                   :to :stt-ing
                   :data-key :audio-data}
                  {:event :interrupt
                   :to :interrupted}]}
   
   :stt-ing
   {:description "Transcribe audio to text"
    :action :transcribe
    :transitions [{:event :transcription-complete
                   :to :processing
                   :data-key :text}
                  {:event :transcription-failed
                   :to :recording}]}  ; retry
   
   :processing
   {:description "LLM processes text, may invoke sub-FSM"
    :sub-fsm :conversation-fsm  ; or :code-review-fsm, etc.
    :transitions [{:event :response-ready
                   :to :tts-ing
                   :data-key :response-text}
                  {:event :need-more-input
                   :to :recording}
                  {:event :exit-requested
                   :to :goodbye}]}
   
   :tts-ing
   {:description "Synthesize response to audio"
    :action :synthesize
    :transitions [{:event :synthesis-complete
                   :to :playback
                   :data-key :audio-data}]}
   
   :playback
   {:description "Play audio to speaker"
    :action :play-audio
    :transitions [{:event :playback-complete
                   :to :recording}  ; loop back
                  {:event :interrupt
                   :to :interrupted}]}
   
   :interrupted
   {:description "Handle interrupt (user said 'stop')"
    :action :acknowledge-interrupt
    :transitions [{:to :recording}   ; resume
                  {:to :goodbye}]}   ; or exit
   
   :goodbye
   {:description "Farewell and cleanup"
    :action :say-goodbye
    :final? true}})
```

### The Processing State: Where It Gets Interesting

The `:processing` state is where the LLM lives. This could be:

1. **Simple completion** - Just call an LLM and get a response
2. **Sub-FSM invocation** - Spawn a code-review FSM, triage FSM, etc.
3. **MCP tool access** - The LLM can call GitHub, file systems, databases
4. **REPL interaction** - Execute Clojure code, inspect state

```clojure
;; The processing state could dynamically select a sub-FSM
;; based on what the user says:

(defn select-sub-fsm [user-text context]
  (cond
    (re-find #"review.*code|PR" user-text)
    :code-review-fsm
    
    (re-find #"run.*test|execute" user-text)
    :code-execution-fsm
    
    (re-find #"search.*file|find" user-text)
    :file-search-fsm
    
    :else
    :general-conversation-fsm))
```

## Cross-FSM Events and Interrupts

### The Interrupt Problem

When user shouts "STOP!" during playback:
1. Playback FSM needs to halt immediately
2. Voice loop FSM needs to transition to `:interrupted`
3. Any sub-FSM in `:processing` may need to abort

This is **cross-FSM event propagation**.

### Approaches

#### 1. Event Bus / Pub-Sub

```clojure
(defprotocol EventBus
  (publish! [this event])
  (subscribe! [this event-type handler]))

;; Any FSM can publish
(publish! bus {:type :interrupt :source :voice-detection})

;; Interested FSMs subscribe
(subscribe! bus :interrupt
  (fn [event]
    (swap! fsm-state assoc :pending-event :interrupt)))
```

#### 2. Hierarchical FSM with Event Bubbling

```clojure
;; Parent FSM receives all events from children
;; Can intercept and handle globally

{:voice-loop  ; parent
 {:children [:recording-fsm :playback-fsm :processing-fsm]
  :global-handlers {:interrupt :handle-global-interrupt}}}
```

#### 3. Shared Atom with Watch

```clojure
(def interrupt-signal (atom false))

;; Voice detection watches for "stop"
(add-watch interrupt-signal :voice-loop
  (fn [_ _ _ new-val]
    (when new-val
      (inject-event! voice-loop-fsm :interrupt))))
```

### Interrupt Source: Voice Activity Detection

Interesting twist: the interrupt itself could come from voice!

```
┌─────────────────┐
│ Always-On VAD   │──── detects "stop!" ────┐
└─────────────────┘                         │
                                            ▼
┌─────────────────┐                   ┌──────────┐
│   Voice Loop    │◄── :interrupt ────│Event Bus │
│      FSM        │                   └──────────┘
└─────────────────┘
```

This creates a secondary listening channel that can interrupt the main loop at any point.

## Voice Logger: Audio Debugging

### Concept

Attach a voice logger to FSM state transitions and actions. The user *hears* the FSM progressing:

```
[entering :recording] → "Listening..."
[audio captured]      → "Got it, transcribing..."
[entering :processing]→ "Thinking..."
[sub-fsm started]     → "Let me check that code..."
[entering :tts-ing]   → (silence, response coming)
[entering :playback]  → (plays response)
```

### Implementation Options

#### 1. Interceptor-Based

```clojure
(def voice-logger-interceptor
  {:name :voice-logger
   :enter (fn [context]
            (let [state (get-in context [:fsm :current-state])
                  message (state->announcement state)]
              (when message
                (quick-tts! message)))  ; non-blocking
            context)})
```

#### 2. State Metadata

```clojure
{:recording
 {:announcement "Listening for your input"
  :announcement-priority :low  ; skip if previous still playing
  ...}}
```

#### 3. Transition Logger (Events Crossing FSMs)

```clojure
;; Log when events cross FSM boundaries
(defn log-cross-fsm-event [event source-fsm target-fsm]
  (quick-tts! 
    (format "Event %s from %s to %s" 
            (:type event) source-fsm target-fsm)))
```

### Use Cases

1. **Development/Debugging** - Hear what the FSM is doing while coding
2. **Accessibility** - Audio feedback for vision-impaired users  
3. **Ambient Awareness** - Background audio cues while multitasking
4. **Bath/Bed-Driven Development** - Hands-free coding sessions!

## Composability Patterns

### Sub-FSMs Attached to Transitions

```clojure
{:transitions
 [{:event :complex-request
   :to :processing
   :sub-fsm :analysis-fsm  ; spawn on this transition
   :on-sub-fsm-complete :response-ready}]}
```

### Parallel FSMs with Synchronization

```clojure
;; Voice loop + background music + notification handler
;; all running in parallel, coordinated via event bus

(defn start-voice-environment []
  (let [bus (create-event-bus)]
    {:voice-loop (start-fsm! voice-loop-fsm bus)
     :ambient (start-fsm! ambient-music-fsm bus)
     :notifications (start-fsm! notification-fsm bus)}))
```

### Chain Pattern (Sequential Sub-FSMs)

```clojure
{:processing
 {:chain [:understand-intent-fsm
          :gather-context-fsm  
          :generate-response-fsm]
  :chain-mode :sequential  ; each feeds into next
  :transitions [{:event :chain-complete :to :tts-ing}]}}
```

## Existing CLAIJ Components

Components that exist (or existed) in CLAIJ that could be integrated:

| Component | Location | Status |
|-----------|----------|--------|
| STT Server | `claij.stt.server` | ✅ Working |
| TTS Server | `claij.tts.server` | ✅ Working |
| Audio Recording | `claij.stt.record` | ✅ Working |
| Audio Playback | `claij.tts.playback` | ✅ Working |
| FSM Engine | `claij.fsm.*` | ✅ Working |
| MCP Integration | `claij.mcp.*` | ✅ Working |
| Code Review FSM | `claij.fsm.code-review` | ✅ Working |

The pieces exist - they just need FSM orchestration rather than direct pipeline coupling.

## Next Steps

1. **Define action protocol** for FSM actions (`:record-audio`, `:transcribe`, etc.)
2. **Implement event bus** for cross-FSM communication
3. **Create voice-loop FSM definition** using existing components
4. **Add voice-logger interceptor** for audio debugging
5. **Test interrupt handling** across FSM boundaries
6. **Explore sub-FSM composition** patterns

## References

- [FSM Composition Patterns](FSM-COMPOSITION.md)
- [CLAIJ Architecture](ARCHITECTURE.md)
- [Uncomplicate diamond-onnxrt](https://github.com/uncomplicate/diamond-onnxrt)
- [Uncomplicate clojure-sound](https://github.com/uncomplicate/clojure-sound)
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
