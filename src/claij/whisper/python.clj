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

(defonce ^:private model-state (atom nil))
(defonce ^:private modules-loaded? (atom false))
(defonce ^:private python-available? (atom nil))

(defn- check-python-available []
  (when (nil? @python-available?)
    (reset! python-available?
            (try
              (require '[libpython-clj2.python :as py])
              (require '[libpython-clj2.require :refer [require-python]])
              true
              (catch Exception e
                (log/error "libpython-clj not available. Install with: clojure -P -M:whisper")
                false))))
  @python-available?)

(defn- load-python-modules!
  "Load required Python modules (lazy, only once)."
  []
  (when-not @modules-loaded?
    (when (check-python-available?)
      (let [py (requiring-resolve 'libpython-clj2.python)
            require-python (requiring-resolve 'libpython-clj2.require/require-python)]
        (require-python '[whisper :as whisper]
                        '[torch :as torch]
                        '[numpy :as np]
                        '[soundfile :as sf])
        (reset! modules-loaded? true)))))

(defn detect-device
  "Detect available compute device (cuda or cpu)."
  []
  (if-not (check-python-available?)
    (throw (ex-info "Python environment not available"
                    {:reason "libpython-clj not loaded"}))
    (do
      (load-python-modules!)
      (let [py (requiring-resolve 'libpython-clj2.python)
            torch (resolve 'torch)]
        (if (@py :call-attr @torch :cuda.is_available)
          "cuda"
          "cpu")))))

(defn load-model!
  "Load Whisper model. Model size options: tiny, small, medium, large-v3.
   Returns the loaded model."
  ([] (load-model! "small"))
  ([model-size]
   (if-not (check-python-available?)
     (throw (ex-info "Python environment not available"
                     {:reason "libpython-clj not loaded"
                      :help "Run with: clojure -M:whisper"}))
     (do
       (load-python-modules!)
       (let [py (requiring-resolve 'libpython-clj2.python)
             whisper (resolve 'whisper)
             device (detect-device)
             model (@py :call-attr @whisper :load_model model-size device)]
         (log/info "Loaded Whisper model" model-size "on device" device)
         (reset! model-state model)
         model)))))

(defn get-model
  "Get the current model, loading it if necessary."
  []
  (or @model-state (load-model!)))

(defn transcribe-audio
  "Transcribe audio data using Whisper.
   audio-array: numpy array of float32 audio samples at 16kHz
   Returns a map with :text and other metadata."
  [audio-array]
  (if-not (check-python-available?)
    (throw (ex-info "Python environment not available"
                    {:reason "libpython-clj not loaded"}))
    (let [py (requiring-resolve 'libpython-clj2.python)
          model (get-model)
          result (@py :call-attr model :transcribe audio-array)
          result-map (@py :->jvm result)]
      (log/info "Transcription result:" (get result-map "text"))
      {:text (get result-map "text")
       :language (get result-map "language")
       :segments (get result-map "segments")})))
