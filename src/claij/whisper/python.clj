(ns claij.whisper.python
  "Python interop layer for OpenAI Whisper speech recognition.
   Handles loading the Whisper model and performing transcription.
   
   NOTE: Requires libpython-clj and Python environment with:
   - openai-whisper
   - torch
   - numpy  
   - soundfile
   
   To run: clojure -M:whisper"
  (:require [clojure.tools.logging :as log]))

(set! *warn-on-reflection* true)

(defonce ^:private model-state (atom nil))
(defonce ^:private modules-loaded? (atom false))
(defonce ^:private module-cache (atom {}))

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

(defn py-get-attr
  "Dynamically resolve and call py/get-attr."
  [obj attr-name]
  (let [get-attr-fn (requiring-resolve 'libpython-clj2.python/get-attr)]
    (get-attr-fn obj attr-name)))

(defn py-call-attr
  "Dynamically resolve and call py/call-attr."
  [obj attr-name & args]
  (let [call-attr-fn (requiring-resolve 'libpython-clj2.python/call-attr)]
    (apply call-attr-fn obj attr-name args)))

(defn py->jvm
  "Dynamically resolve and call py/->jvm."
  [py-obj]
  (let [->jvm-fn (requiring-resolve 'libpython-clj2.python/->jvm)]
    (->jvm-fn py-obj)))

(defn ensure-modules-loaded!
  "Ensure all required Python modules are loaded (lazy, only once).
   Loads and caches: whisper, torch, numpy, soundfile, io, builtins.
   All modules are stored in module-cache for reuse."
  []
  (when-not @modules-loaded?
    (log/info "Loading Python modules for Whisper...")
    (require-python-or-throw!)
    (let [py-import (requiring-resolve 'libpython-clj2.python/import-module)]
      (reset! module-cache
              {:whisper (py-import "whisper")
               :torch (py-import "torch")
               :numpy (py-import "numpy")
               :soundfile (py-import "soundfile")
               :io (py-import "io")
               :builtins (py-import "builtins")}))
    (reset! modules-loaded? true)
    (log/info "Python modules loaded successfully")))

(defn get-python-module
  "Get a cached Python module by keyword.
   Ensures modules are loaded first.
   
   Available modules: :whisper, :torch, :numpy, :soundfile, :io, :builtins"
  [module-key]
  (ensure-modules-loaded!)
  (get @module-cache module-key))

(defn detect-device
  "Detect available compute device (cuda or cpu).
   Uses cached torch module."
  []
  (let [torch (get-python-module :torch)
        cuda (py-get-attr torch "cuda")
        is-available (py-call-attr cuda "is_available")]
    (if is-available
      "cuda"
      "cpu")))

(defn load-model!
  "Load Whisper model using cached Python modules.
   Model size options: tiny, small, medium, large-v3.
   Returns the loaded model."
  ([] (load-model! "small"))
  ([model-size]
   (ensure-modules-loaded!)
   (let [whisper (get-python-module :whisper)
         device (detect-device)
         model (py-call-attr whisper "load_model" model-size device)]
     (log/info "Loaded Whisper model" model-size "on device" device)
     (reset! model-state model)
     model)))

(defn get-model
  "Get the current model, loading it if necessary."
  []
  (or @model-state (load-model!)))

(defn transcribe-audio
  "Transcribe audio data using Whisper.
   audio-array: numpy array of float32 audio samples at 16kHz
   Returns a map with :text and other metadata."
  [audio-array]
  (let [model (get-model)
        result (py-call-attr model "transcribe" audio-array)
        result-map (py->jvm result)]
    (log/info "Transcription result:" (get result-map "text"))
    {:text (get result-map "text")
     :language (get result-map "language")
     :segments (get result-map "segments")}))
