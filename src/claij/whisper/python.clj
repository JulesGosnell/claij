(ns claij.whisper.python
  "Python interop layer for OpenAI Whisper speech recognition.
   Handles loading the Whisper model and performing transcription.
   
   NOTE: Requires libpython-clj and Python environment with:
   - openai-whisper
   - torch
   - numpy  
   - soundfile
   
   To run: clojure -M:whisper"
  (:require [clojure.tools.logging :as log]
            [libpython-clj2.python :as py]
            [libpython-clj2.require :refer [require-python]]))

(defonce ^:private model-state (atom nil))
(defonce ^:private modules-loaded? (atom false))

(defn- load-python-modules!
  "Load required Python modules (lazy, only once)."
  []
  (when-not @modules-loaded?
    (require-python 'whisper)
    (require-python 'torch)
    (require-python 'numpy)
    (require-python 'soundfile)
    (reset! modules-loaded? true)))

(defn detect-device
  "Detect available compute device (cuda or cpu)."
  []
  (load-python-modules!)
  (let [torch (py/import-module "torch")
        cuda (py/get-attr torch "cuda")
        is-available (py/call-attr cuda "is_available")]
    (if is-available
      "cuda"
      "cpu")))

(defn load-model!
  "Load Whisper model. Model size options: tiny, small, medium, large-v3.
   Returns the loaded model."
  ([] (load-model! "small"))
  ([model-size]
   (load-python-modules!)
   (let [whisper (py/import-module "whisper")
         device (detect-device)
         model (py/call-attr whisper "load_model" model-size device)]
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
        result (py/call-attr model "transcribe" audio-array)
        result-map (py/->jvm result)]
    (log/info "Transcription result:" (get result-map "text"))
    {:text (get result-map "text")
     :language (get result-map "language")
     :segments (get result-map "segments")}))
