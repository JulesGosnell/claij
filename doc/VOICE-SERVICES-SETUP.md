# Voice Services Setup Guide

This document details how to set up the STT (Speech-to-Text) and TTS (Text-to-Speech) microservices required for CLAIJ's Bath Driven Development (BDD) voice pipeline.

## Overview

CLAIJ uses two Python-based microservices:

| Service | Port | Technology | Purpose |
|---------|------|------------|---------|
| STT | 8000 | OpenAI Whisper | Speech → Text |
| TTS | 8001 | Piper | Text → Speech |

Both services use **libpython-clj** for Clojure/Python interop and expose HTTP/JSON APIs.

## Hardware Requirements

### STT (Whisper) - GPU Recommended

Whisper benefits significantly from GPU acceleration:

| Model | VRAM Required | Relative Speed (GPU) | CPU Feasible? |
|-------|---------------|---------------------|---------------|
| tiny | ~1 GB | ~32x realtime | ✅ Yes |
| base | ~1 GB | ~16x realtime | ✅ Yes |
| small | ~2 GB | ~6x realtime | ⚠️ Slow |
| medium | ~5 GB | ~2x realtime | ❌ Too slow |
| large | ~10 GB | ~1x realtime | ❌ Too slow |

**Recommendations:**
- **Development**: `tiny` or `base` model (fast, works on CPU)
- **Production**: `small` or `medium` model with NVIDIA GPU
- **Minimum GPU**: NVIDIA with 4GB+ VRAM and CUDA support

### TTS (Piper) - CPU Only

Piper runs efficiently on CPU:
- No GPU required
- ~10x realtime on modern CPU
- Memory: ~500MB per voice model

## System Prerequisites

### 1. Python Environment

Python 3.9+ recommended (tested with 3.11):

```bash
# Check Python version
python3 --version

# Create virtual environment (optional but recommended)
python3 -m venv ~/venvs/claij-voice
source ~/venvs/claij-voice/bin/activate
```

### 2. CUDA (for GPU-accelerated STT)

For NVIDIA GPU support:

```bash
# Check NVIDIA driver
nvidia-smi

# Check CUDA version
nvcc --version
```

**Required versions:**
- NVIDIA Driver: 470+ 
- CUDA: 11.7+ or 12.x
- cuDNN: 8.x (matching CUDA version)

**Installation (Ubuntu/Debian):**
```bash
# Add NVIDIA package repository
wget https://developer.download.nvidia.com/compute/cuda/repos/ubuntu2204/x86_64/cuda-keyring_1.1-1_all.deb
sudo dpkg -i cuda-keyring_1.1-1_all.deb
sudo apt-get update

# Install CUDA toolkit
sudo apt-get install cuda-toolkit-12-1

# Install cuDNN
sudo apt-get install libcudnn8
```

See: https://docs.nvidia.com/cuda/cuda-installation-guide-linux/

## Python Dependencies

### Core Dependencies

```bash
pip install numpy soundfile scipy
```

| Package | Purpose |
|---------|---------|
| numpy | Array operations for audio processing |
| soundfile | Read/write audio files (WAV, FLAC) |
| scipy | Audio resampling (22kHz TTS → 16kHz STT) |

### STT Dependencies (Whisper)

```bash
pip install openai-whisper torch
```

| Package | Purpose |
|---------|---------|
| openai-whisper | OpenAI's Whisper speech recognition |
| torch | PyTorch (for model inference) |

**PyTorch with CUDA** (for GPU acceleration):
```bash
# For CUDA 11.8
pip install torch --index-url https://download.pytorch.org/whl/cu118

# For CUDA 12.1
pip install torch --index-url https://download.pytorch.org/whl/cu121

# CPU only (slower, but works everywhere)
pip install torch --index-url https://download.pytorch.org/whl/cpu
```

See: https://pytorch.org/get-started/locally/

### TTS Dependencies (Piper)

```bash
pip install piper-tts
```

| Package | Purpose |
|---------|---------|
| piper-tts | Fast neural TTS with ONNX runtime |

### All Dependencies (One-liner)

```bash
pip install numpy soundfile scipy openai-whisper torch piper-tts
```

## Voice Model Setup

### Whisper Models (STT)

Whisper models download automatically on first use. Models are cached in `~/.cache/whisper/`.

To pre-download a model:
```bash
python3 -c "import whisper; whisper.load_model('small')"
```

### Piper Voices (TTS)

Download voice models from: https://github.com/rhasspy/piper/releases

**Recommended voice:**
```bash
# Create models directory
mkdir -p ~/.local/share/piper-voices

# Download US English voice (lessac-medium, good quality)
cd ~/.local/share/piper-voices
wget https://github.com/rhasspy/piper/releases/download/v1.2.0/voice-en_US-lessac-medium.tar.gz
tar xzf voice-en_US-lessac-medium.tar.gz
```

**Set voice path:**
```bash
export PIPER_VOICE_PATH=~/.local/share/piper-voices/en_US-lessac-medium.onnx
```

Other voices available:
- `en_US-amy-low` - Fast, lower quality
- `en_US-lessac-high` - Slower, higher quality
- `en_GB-*` - British English voices
- Many other languages available

## Running the Services

### Start STT Service

```bash
cd /path/to/claij

# Default: port 8000, all interfaces, 'small' model
./bin/stt.sh

# Custom configuration
./bin/stt.sh 8000 0.0.0.0 tiny    # faster, less accurate
./bin/stt.sh 8000 localhost small  # local only, better quality
```

### Start TTS Service

```bash
cd /path/to/claij

# Requires PIPER_VOICE_PATH to be set
export PIPER_VOICE_PATH=~/.local/share/piper-voices/en_US-lessac-medium.onnx

# Default: port 8001, all interfaces
./bin/tts.sh

# Custom configuration
./bin/tts.sh 8001 0.0.0.0
./bin/tts.sh 9001 localhost
```

### Verify Services

```bash
# Check STT health
curl http://localhost:8000/health

# Check TTS health  
curl http://localhost:8001/health

# Check OpenAPI specs
curl http://localhost:8000/openapi.json
curl http://localhost:8001/openapi.json
```

## Testing the Pipeline

### Test STT

```bash
# Record a test WAV file (or use any WAV file)
# Then POST to STT service
curl -X POST http://localhost:8000/transcribe \
  -F "audio=@test.wav" \
  -H "Accept: application/json"

# Response: {"text": "hello world", "language": "en"}
```

### Test TTS

```bash
# Request speech synthesis
curl -X POST http://localhost:8001/synthesize \
  -H "Content-Type: application/json" \
  -d '{"text": "Hello, this is a test."}' \
  --output response.wav

# Play the result
aplay response.wav  # Linux
afplay response.wav # macOS
```

### Test Full Pipeline (via CLAIJ)

```clojure
(require '[claij.fsm.bdd-fsm :as bdd])
(require '[clojure.java.io :as io])

;; Load test audio
(def audio-bytes (-> "test.wav" io/file io/input-stream .readAllBytes))

;; Run BDD FSM
(def result (bdd/run-bdd audio-bytes))
```

## Sample Rate Note

- **Whisper STT** expects 16kHz audio
- **Piper TTS** produces 22kHz audio

The services handle resampling automatically using scipy. If you see sample rate errors, ensure scipy is installed:

```bash
pip install scipy
```

## Systemd Service Files (Production)

For running services at boot on Linux:

### /etc/systemd/system/claij-stt.service

```ini
[Unit]
Description=CLAIJ STT Service (Whisper)
After=network.target

[Service]
Type=simple
User=jules
WorkingDirectory=/home/jules/src/claij
Environment=PATH=/home/jules/venvs/claij-voice/bin:/usr/bin
ExecStart=/home/jules/src/claij/bin/stt.sh 8000 0.0.0.0 small
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

### /etc/systemd/system/claij-tts.service

```ini
[Unit]
Description=CLAIJ TTS Service (Piper)
After=network.target

[Service]
Type=simple
User=jules
WorkingDirectory=/home/jules/src/claij
Environment=PATH=/home/jules/venvs/claij-voice/bin:/usr/bin
Environment=PIPER_VOICE_PATH=/home/jules/.local/share/piper-voices/en_US-lessac-medium.onnx
ExecStart=/home/jules/src/claij/bin/tts.sh 8001 0.0.0.0
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

**Enable and start:**
```bash
sudo systemctl daemon-reload
sudo systemctl enable claij-stt claij-tts
sudo systemctl start claij-stt claij-tts
sudo systemctl status claij-stt claij-tts
```

## Troubleshooting

### "No module named 'whisper'"
```bash
pip install openai-whisper
```

### "CUDA out of memory"
Use a smaller model:
```bash
./bin/stt.sh 8000 0.0.0.0 tiny
```

### "Sample rate mismatch" / Resampling errors
```bash
pip install scipy
```

### "No Piper voice found"
Ensure `PIPER_VOICE_PATH` points to a valid `.onnx` file:
```bash
ls -la $PIPER_VOICE_PATH
```

### Services not accessible remotely
Check firewall rules:
```bash
sudo firewall-cmd --add-port=8000/tcp --permanent
sudo firewall-cmd --add-port=8001/tcp --permanent
sudo firewall-cmd --reload
```

## References

- **OpenAI Whisper**: https://github.com/openai/whisper
- **Piper TTS**: https://github.com/rhasspy/piper
- **PyTorch Installation**: https://pytorch.org/get-started/locally/
- **CUDA Installation**: https://docs.nvidia.com/cuda/
- **libpython-clj**: https://github.com/clj-python/libpython-clj

## CLAIJ Server Setup

The CLAIJ server orchestrates the voice pipeline and provides the web UI.

### Prerequisites

- Java 17+ (Temurin/Adoptium recommended)
- Clojure CLI (1.11+)
- Node.js 18+ (for ClojureScript compilation)

```bash
# Check versions
java -version
clojure --version
node --version
```

### Install Dependencies

```bash
cd /path/to/claij

# Install Node dependencies for ClojureScript
npm install

# Compile ClojureScript voice UI
npx shadow-cljs release voice
```

### Start CLAIJ Server

```bash
cd /path/to/claij

# HTTP only (default: port 8080)
clojure -M -m claij.server

# Custom HTTP port
clojure -M -m claij.server -p 9090

# HTTPS for mobile/iOS support
# First generate a self-signed certificate:
chmod +x bin/gen-ssl-cert.sh
./bin/gen-ssl-cert.sh

# Then start with HTTPS:
clojure -M -m claij.server --ssl-port 8443 --keystore claij-dev.jks

# Both HTTP and HTTPS:
clojure -M -m claij.server -p 8080 --ssl-port 8443 --keystore claij-dev.jks

# HTTPS only (disable HTTP):
clojure -M -m claij.server -p 0 --ssl-port 8443 --keystore claij-dev.jks
```

**Mobile/iOS Notes:**
- iOS Safari requires HTTPS for microphone access
- Access via `https://<your-ip>:8443` from mobile device
- Accept the self-signed certificate warning
- On iOS: Settings → General → About → Certificate Trust Settings
  to fully trust the certificate

### Verify CLAIJ

```bash
# Health check
curl http://localhost:8080/health

# List FSMs
curl http://localhost:8080/fsms/list

# OpenAPI docs
open http://localhost:8080/swagger
```

### Voice Web UI

Open http://localhost:8080 in a browser. The voice UI provides:

- Click-to-record (or press spacebar)
- Live waveform visualization
- Automatic WAV encoding (22kHz mono PCM)
- Audio playback of responses

### Configuration

The BDD FSM connects to STT/TTS services. By default it expects:

| Service | Default URL |
|---------|-------------|
| STT | http://prognathodon:8000 |
| TTS | http://prognathodon:8001 |

To customize, edit `src/claij/fsm/bdd_fsm.clj`:

```clojure
(def default-stt-url "http://localhost:8000")
(def default-tts-url "http://localhost:8001")
```

Or pass options when creating the context:

```clojure
(bdd/make-bdd-context {:stt-url "http://localhost:8000"
                       :tts-url "http://localhost:8001"})
```

### Systemd Service (Production)

```ini
# /etc/systemd/system/claij.service
[Unit]
Description=CLAIJ Server
After=network.target claij-stt.service claij-tts.service

[Service]
Type=simple
User=jules
WorkingDirectory=/home/jules/src/claij
Environment=OPENROUTER_API_KEY=sk-...
ExecStart=/usr/bin/clojure -M -m claij.server 8080
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

### ClojureScript Development

For live reloading during UI development:

```bash
# Start shadow-cljs in watch mode
npx shadow-cljs watch voice

# Opens dev server at http://localhost:8090
# Changes to src-cljs/ will hot-reload
```

### Test Full Voice Pipeline

```bash
# Generate test audio
curl -X POST http://localhost:8001/synthesize \
  -H "Content-Type: application/json" \
  -d '{"text": "Hello, what can you help me with?"}' \
  --output test-input.wav

# Send through CLAIJ voice endpoint
curl -X POST http://localhost:8080/voice \
  -F "audio=@test-input.wav;type=audio/wav" \
  --output response.wav

# Play response
aplay response.wav

# Verify response content (send back to STT)
curl -X POST http://localhost:8000/transcribe \
  -F "audio=@response.wav" | jq .text
```

## Quick Reference

```bash
# Install all Python deps
pip install numpy soundfile scipy openai-whisper torch piper-tts

# Download Piper voice
mkdir -p ~/.local/share/piper-voices
cd ~/.local/share/piper-voices
wget https://github.com/rhasspy/piper/releases/download/v1.2.0/voice-en_US-lessac-medium.tar.gz
tar xzf voice-en_US-lessac-medium.tar.gz
export PIPER_VOICE_PATH=~/.local/share/piper-voices/en_US-lessac-medium.onnx

# Start services (from claij directory)
./bin/stt.sh &
./bin/tts.sh &

# Verify STT/TTS
curl http://localhost:8000/health
curl http://localhost:8001/health

# Install CLAIJ JS dependencies and compile
npm install
npx shadow-cljs release voice

# Start CLAIJ server
clojure -M -m claij.server

# Open voice UI
open http://localhost:8080
```
