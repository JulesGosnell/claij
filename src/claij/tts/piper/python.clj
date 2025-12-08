(ns claij.tts.piper.python
  "Piper backend implementation for TTS using Python's piper-tts.
   
   This backend uses libpython-clj2 to call Python's Piper library.
   
   Requirements:
   - libpython-clj2 on classpath (use :piper alias)
   - Python environment with: piper-tts
   - Voice model files (download from Piper releases)
   
   Usage:
   (def backend (create-backend {:voice \"en_US-lessac-medium\"
                                  :model-path \"/path/to/model.onnx\"}))
   (initialize! backend)
   (synthesize backend \"Hello world\")"
  (:require [clojure.tools.logging :as log]
            [claij.tts.core :refer [TTSBackend synthesize initialize!]])
  (:import [java.io ByteArrayOutputStream]))

;;; Python Interop Helpers

(defn- python-available?
  "Check if libpython-clj2 is available on classpath."
  []
  (try
    (requiring-resolve 'libpython-clj2.python/import-module)
    true
    (catch Exception _
      false)))

(defn- require-python-or-throw!
  "Throw an exception with helpful message if Python is not available."
  []
  (when-not (python-available?)
    (throw (ex-info
            "Python support not available. Run with: clojure -M:piper"
            {:type :python-not-available
             :help "Add :piper alias to include libpython-clj2 dependencies"}))))

(defn- py-get-attr
  "Dynamically resolve and call py/get-attr."
  [obj attr-name]
  (let [get-attr-fn (requiring-resolve 'libpython-clj2.python/get-attr)]
    (get-attr-fn obj attr-name)))

(defn- py-call-attr
  "Dynamically resolve and call py/call-attr."
  [obj attr-name & args]
  (let [call-attr-fn (requiring-resolve 'libpython-clj2.python/call-attr)]
    (apply call-attr-fn obj attr-name args)))

(defn- py->jvm
  "Dynamically resolve and call py/->jvm."
  [py-obj]
  (let [->jvm-fn (requiring-resolve 'libpython-clj2.python/->jvm)]
    (->jvm-fn py-obj)))

;;; Module Loading

(defn- initialize-python!
  "Initialize libpython-clj2."
  []
  (let [py-initialize (requiring-resolve 'libpython-clj2.python/initialize!)]
    (py-initialize)))

(defn- import-python-modules!
  "Import and return required Python modules for Piper."
  []
  (let [py-import (requiring-resolve 'libpython-clj2.python/import-module)]
    {:piper (py-import "piper")
     :wave (py-import "wave")
     :io (py-import "io")}))

(defn- load-modules!
  "Load Python modules if not already loaded.
   Returns the module cache."
  [modules-loaded?-atom module-cache-atom]
  (when-not @modules-loaded?-atom
    (log/info "Loading Python modules for Piper...")
    (require-python-or-throw!)
    (initialize-python!)
    (reset! module-cache-atom (import-python-modules!))
    (reset! modules-loaded?-atom true)
    (log/info "Python modules loaded successfully"))
  @module-cache-atom)

;;; Voice Loading

(defn- load-piper-voice!
  "Load Piper voice model from file.
   Returns the loaded voice object."
  [module-cache voice-path]
  (let [piper (:piper module-cache)
        voice-class (py-get-attr piper "PiperVoice")
        voice (py-call-attr voice-class "load" voice-path)]
    (log/info "Loaded Piper voice from" voice-path)
    voice))

;;; Audio Synthesis

(defn- synthesize-to-bytes
  "Synthesize text to WAV bytes using Piper voice."
  [voice text module-cache]
  (let [wave-module (:wave module-cache)
        io-module (:io module-cache)
        ;; Create BytesIO for in-memory WAV
        bytes-io (py-call-attr io-module "BytesIO")
        ;; Open WAV writer
        wav-writer (py-call-attr wave-module "open" bytes-io "wb")
        _ (py-call-attr wav-writer "setnchannels" 1) ; Mono
        _ (py-call-attr wav-writer "setsampwidth" 2) ; 16-bit
        config (py-get-attr voice "config")
        sample-rate (py-get-attr config "sample_rate")
        _ (py-call-attr wav-writer "setframerate" sample-rate)
        ;; Synthesize audio - returns iterator of audio chunks
        audio-generator (py-call-attr voice "synthesize" text)
        ;; Write all frames - AudioChunk is a bytes-like object itself
        _ (doseq [audio-chunk audio-generator]
            ;; AudioChunk has audio_int16_bytes attribute with the raw PCM data
            (let [audio-bytes (py-get-attr audio-chunk "audio_int16_bytes")]
              (py-call-attr wav-writer "writeframes" audio-bytes)))
        _ (py-call-attr wav-writer "close")
        ;; Get bytes
        wav-bytes (py-call-attr bytes-io "getvalue")]
    {:audio-bytes (byte-array (py->jvm wav-bytes))
     :sample-rate sample-rate}))

;;; Backend Implementation

(defrecord PiperBackend [voice-path
                         voice-atom
                         sample-rate-atom
                         modules-loaded?-atom
                         module-cache-atom]
  TTSBackend

  (initialize! [this]
    (when-not @voice-atom
      (let [modules (load-modules! modules-loaded?-atom module-cache-atom)
            voice (load-piper-voice! modules voice-path)
            config (py-get-attr voice "config")
            sample-rate (py-get-attr config "sample_rate")]
        (reset! voice-atom voice)
        (reset! sample-rate-atom sample-rate)))
    this)

  (synthesize [this text]
    (synthesize this text {}))

  (synthesize [this text _options]
    (initialize! this)
    (let [modules @module-cache-atom
          result (synthesize-to-bytes @voice-atom text modules)]
      (log/info "Synthesized" (count text) "characters to audio")
      result))

  (health-check [this]
    (try
      (if @voice-atom
        {:healthy? true
         :backend-type :piper
         :details {:voice-path voice-path
                   :sample-rate @sample-rate-atom
                   :initialized? true}}
        {:healthy? true
         :backend-type :piper
         :details {:voice-path voice-path
                   :initialized? false}})
      (catch Exception e
        {:healthy? false
         :backend-type :piper
         :error (.getMessage e)})))

  (backend-info [this]
    {:backend-type :piper
     :voice voice-path
     :sample-rate (or @sample-rate-atom "not-initialized")
     :version "1.0.0"
     :python-available? (python-available?)
     :initialized? (some? @voice-atom)}))

;;; Public API

(defn create-backend
  "Create a new Piper backend instance.
   
   Options:
   - :voice-path - Path to Piper .onnx voice model file (required)
   
   Example:
   (def backend (create-backend {:voice-path \"/path/to/en_US-lessac-medium.onnx\"}))"
  [{:keys [voice-path]}]
  (when-not voice-path
    (throw (ex-info "voice-path is required"
                    {:type :missing-voice-path
                     :help "Provide path to Piper .onnx model file"})))
  (->PiperBackend voice-path
                  (atom nil) ; voice-atom (loaded lazily)
                  (atom nil) ; sample-rate-atom
                  (atom false) ; modules-loaded?-atom
                  (atom {}))) ; module-cache

(defn get-module-cache
  "Get the Python module cache from a Piper backend.
   This is needed if other utilities need Python modules.
   
   backend - PiperBackend instance
   
   Returns a map of Python modules."
  [backend]
  @(:module-cache-atom backend))
