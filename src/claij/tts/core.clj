(ns claij.tts.core
  "Core protocol and utilities for Text-To-Speech backends.
   
   This namespace defines the TTSBackend protocol that all TTS implementations
   must satisfy. Different backends (Piper, Google TTS, AWS Polly, etc.)
   can be plugged in by implementing this protocol.
   
   Example backends:
   - claij.tts.piper.python - Piper TTS via Python
   - claij.tts.google - Google Cloud Text-to-Speech
   - claij.tts.aws - AWS Polly
   
   Usage:
   (def backend (piper/create-backend {:voice \"en_US-lessac-medium\"}))
   (initialize! backend)
   (synthesize backend \"Hello world\")")

(defprotocol TTSBackend
  "Protocol for text-to-speech backends.
   
   All TTS implementations must provide these operations."

  (initialize!
    [this]
    "Initialize the backend (load models, connect to services, etc.).
     Should be idempotent - calling multiple times should be safe.
     Returns the backend instance or throws on failure.")

  (synthesize
    [this text]
    [this text options]
    "Synthesize speech from text.
     
     text: String to convert to speech
     options: Optional map with backend-specific settings
              (e.g., {:speed 1.2, :pitch 0.8})
     
     Returns a map with:
     {:audio-bytes byte-array  ; WAV format audio data
      :sample-rate 22050       ; Audio sample rate in Hz
      :duration 2.5            ; Duration in seconds (optional)
      :metadata {...}}         ; Optional backend-specific metadata")

  (health-check
    [this]
    "Check if backend is healthy and ready to synthesize.
     
     Returns a map with:
     {:healthy? true
      :backend-type :piper
      :details {...}} ; backend-specific health info
     
     Should not throw - returns {:healthy? false :error ...} on failure.")

  (backend-info
    [this]
    "Get information about the backend configuration.
     
     Returns a map with:
     {:backend-type :piper
      :voice \"en_US-lessac-medium\"
      :sample-rate 22050
      :version \"1.0.0\"
      ...} ; other backend-specific info"))

(defn synthesize-text
  "Convenience function to synthesize speech using any backend.
   Ensures backend is initialized before synthesizing."
  ([backend text]
   (synthesize-text backend text {}))
  ([backend text options]
   (initialize! backend)
   (synthesize backend text options)))
