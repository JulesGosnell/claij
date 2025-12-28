(ns claij.stt.whisper.python
  "Whisper backend implementation for STT using Python's OpenAI Whisper.
   
   This backend uses libpython-clj2 to call Python's Whisper library.
   
   Requirements:
   - libpython-clj2 on classpath (use :whisper alias)
   - Python environment with: openai-whisper, torch, numpy, soundfile
   
   Usage:
   (def backend (create-backend {:model-size \"small\"}))
   (initialize! backend)
   (transcribe backend audio-bytes)"
  (:require [clojure.tools.logging :as log]
            [claij.stt.core :refer [STTBackend initialize!]]))

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
            "Python support not available. Run with: clojure -M:whisper"
            {:type :python-not-available
             :help "Add :whisper alias to include libpython-clj2 dependencies"}))))

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
  "Import and return all required Python modules."
  []
  (let [py-import (requiring-resolve 'libpython-clj2.python/import-module)]
    {:whisper (py-import "whisper")
     :torch (py-import "torch")
     :numpy (py-import "numpy")
     :soundfile (py-import "soundfile")
     :io (py-import "io")
     :builtins (py-import "builtins")
     :scipy-signal (py-import "scipy.signal")}))

(defn- load-modules!
  "Load Python modules if not already loaded.
   Returns the module cache."
  [modules-loaded?-atom module-cache-atom]
  (when-not @modules-loaded?-atom
    (log/info "Loading Python modules for Whisper...")
    (require-python-or-throw!)
    (initialize-python!)
    (reset! module-cache-atom (import-python-modules!))
    (reset! modules-loaded?-atom true)
    (log/info "Python modules loaded successfully"))
  @module-cache-atom)

;;; Device Detection

(defn- detect-device
  "Detect available compute device (cuda or cpu)."
  [module-cache]
  (let [torch (:torch module-cache)
        cuda (py-get-attr torch "cuda")
        is-available (py-call-attr cuda "is_available")]
    (if is-available "cuda" "cpu")))

;;; Model Loading

(defn- load-whisper-model!
  "Load Whisper model with specified size on detected device."
  [model-size module-cache device]
  (let [whisper (:whisper module-cache)
        model (py-call-attr whisper "load_model" model-size device)]
    (log/info "Loaded Whisper model" model-size "on device" device)
    model))

;;; Backend Implementation

(defrecord WhisperBackend [model-size
                           model-atom
                           device-atom
                           modules-loaded?-atom
                           module-cache-atom]
  STTBackend

  (initialize! [this]
    (when-not @model-atom
      (let [modules (load-modules! modules-loaded?-atom module-cache-atom)
            detected-device (detect-device modules)
            loaded-model (load-whisper-model! model-size modules detected-device)]
        (reset! device-atom detected-device)
        (reset! model-atom loaded-model)))
    this)

  (transcribe [this audio-array]
    (initialize! this)
    (let [result (py-call-attr @model-atom "transcribe" audio-array)
          result-map (py->jvm result)]
      (log/debug "Raw transcription result:" (pr-str (get result-map "text")))
      {:text (get result-map "text")
       :language (get result-map "language")
       :segments (get result-map "segments")}))

  (health-check [this]
    (try
      (if @model-atom
        {:healthy? true
         :backend-type :whisper
         :details {:model-size model-size
                   :device @device-atom
                   :initialized? true}}
        {:healthy? true
         :backend-type :whisper
         :details {:model-size model-size
                   :device "unknown"
                   :initialized? false}})
      (catch Exception e
        {:healthy? false
         :backend-type :whisper
         :error (.getMessage e)})))

  (backend-info [this]
    {:backend-type :whisper
     :model model-size
     :device (or @device-atom "not-initialized")
     :version "1.0.0"
     :python-available? (python-available?)
     :initialized? (some? @model-atom)}))

;;; Public API

(defn create-backend
  "Create a new Whisper backend instance.
   
   Options:
   - :model-size - Model size to use (default: \"small\")
                   Options: \"tiny\", \"small\", \"medium\", \"large-v3\"
   
   Example:
   (def backend (create-backend {:model-size \"small\"}))"
  ([] (create-backend {}))
  ([{:keys [model-size] :or {model-size "small"}}]
   (->WhisperBackend model-size
                     (atom nil) ; model-atom (loaded lazily)
                     (atom nil) ; device-atom (detected lazily)
                     (atom false) ; modules-loaded?-atom
                     (atom {})))) ; module-cache

(defn get-module-cache
  "Get the Python module cache from a Whisper backend.
   This is needed for audio processing which uses Python libraries.
   
   backend - WhisperBackend instance
   
   Returns a map of Python modules."
  [backend]
  @(:module-cache-atom backend))
