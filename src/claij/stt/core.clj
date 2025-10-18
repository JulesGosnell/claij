(ns claij.stt.core
  "Core protocol and utilities for Speech-To-Text backends.
   
   This namespace defines the STTBackend protocol that all STT implementations
   must satisfy. Different backends (Whisper, Google Speech, AWS Transcribe, etc.)
   can be plugged in by implementing this protocol.
   
   Example backends:
   - claij.stt.whisper.python - OpenAI Whisper via Python
   - claij.stt.google - Google Cloud Speech-to-Text
   - claij.stt.aws - AWS Transcribe
   
   Usage:
   (def backend (whisper/create-backend {:model-size \"small\"}))
   (initialize! backend)
   (transcribe backend audio-bytes)")

(defprotocol STTBackend
  "Protocol for speech-to-text backends.
   
   All STT implementations must provide these operations."

  (initialize!
    [this]
    "Initialize the backend (load models, connect to services, etc.).
     Should be idempotent - calling multiple times should be safe.
     Returns the backend instance or throws on failure.")

  (transcribe
    [this audio-data]
    "Transcribe audio data to text.
     
     audio-data: byte array or numpy array of audio samples
     
     Returns a map with:
     {:text \"transcribed text\"
      :language \"en\"  ; detected/specified language code
      :segments [...] ; optional: detailed timing segments
      :confidence 0.95 ; optional: confidence score
      :metadata {...}} ; optional: backend-specific metadata")

  (health-check
    [this]
    "Check if backend is healthy and ready to transcribe.
     
     Returns a map with:
     {:healthy? true
      :backend-type :whisper
      :details {...}} ; backend-specific health info
     
     Should not throw - returns {:healthy? false :error ...} on failure.")

  (backend-info
    [this]
    "Get information about the backend configuration.
     
     Returns a map with:
     {:backend-type :whisper
      :model \"small\"
      :device \"cuda\"
      :version \"1.0.0\"
      ...} ; other backend-specific info"))

(defn transcribe-audio
  "Convenience function to transcribe audio using any backend.
   Ensures backend is initialized before transcribing."
  [backend audio-data]
  (initialize! backend)
  (transcribe backend audio-data))
