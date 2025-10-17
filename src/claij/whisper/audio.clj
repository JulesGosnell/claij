(ns claij.whisper.audio
  "Audio processing utilities for Whisper service.
   All operations are performed in-memory without temporary files.
   
   NOTE: Requires libpython-clj and Python with numpy, soundfile
   To run: clojure -M:whisper"
  (:require [clojure.tools.logging :as log])
  (:import [java.io ByteArrayInputStream]))

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
                (log/error "libpython-clj not available")
                false))))
  @python-available?)

(defn- load-python-modules!
  "Load required Python modules (lazy, only once)."
  []
  (when-not @modules-loaded?
    (when (check-python-available?)
      (let [require-python (requiring-resolve 'libpython-clj2.require/require-python)]
        (require-python '[numpy :as np]
                        '[soundfile :as sf]
                        '[io :as pyio])
        (reset! modules-loaded? true)))))

(defn wav-bytes->audio-array
  "Convert WAV format bytes to numpy float32 array suitable for Whisper.
   Processes audio entirely in memory using Python's BytesIO.
   Returns numpy array of audio samples at 16kHz."
  [wav-bytes]
  (if-not (check-python-available?)
    (throw (ex-info "Python environment not available"
                    {:reason "libpython-clj not loaded"
                     :help "Run with: clojure -M:whisper"}))
    (do
      (load-python-modules!)
      (let [py (requiring-resolve 'libpython-clj2.python)
            pyio (resolve 'pyio)
            sf (resolve 'sf)
            np (resolve 'np)
            ;; Create Python BytesIO from Java bytes
            bytes-io (@py :call-attr @pyio :BytesIO (@py :->py-bytes wav-bytes))
            ;; Read audio using soundfile (returns [data, samplerate])
            [audio-data sample-rate] (@py :call-attr @sf :read bytes-io)]

        ;; Validate sample rate
        (when (not= sample-rate 16000)
          (throw (ex-info "Audio must be 16kHz"
                          {:sample-rate sample-rate
                           :expected 16000})))

        ;; Convert to float32 for Whisper
        (@py :call-attr audio-data :astype (@py :get-attr @np :float32))))))

(defn validate-audio
  "Validate audio byte array. Returns true if valid, throws exception otherwise."
  [wav-bytes]
  (when (nil? wav-bytes)
    (throw (ex-info "Audio data is nil" {})))

  (when (zero? (alength wav-bytes))
    (throw (ex-info "Audio data is empty" {})))

  true)
