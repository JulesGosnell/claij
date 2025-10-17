(ns claij.whisper.audio
  "Audio processing utilities for Whisper service.
   All operations are performed in-memory without temporary files.
   
   NOTE: Requires libpython-clj and Python with numpy, soundfile
   To run: clojure -M:whisper"
  (:require [clojure.tools.logging :as log]
            [libpython-clj2.python :as py]
            [libpython-clj2.require :refer [require-python]])
  (:import [java.io ByteArrayInputStream]))

(defonce ^:private modules-loaded? (atom false))

(defn- load-python-modules!
  "Load required Python modules (lazy, only once)."
  []
  (when-not @modules-loaded?
    (require-python 'numpy)
    (require-python 'soundfile)
    (require-python 'io)
    (reset! modules-loaded? true)))

(defn wav-bytes->audio-array
  "Convert WAV format bytes to numpy float32 array suitable for Whisper.
   Processes audio entirely in memory using Python's BytesIO.
   Returns numpy array of audio samples at 16kHz."
  [wav-bytes]
  (load-python-modules!)
  (let [;; Get Python modules
        np (py/import-module "numpy")
        sf (py/import-module "soundfile")
        pyio (py/import-module "io")
        builtins (py/import-module "builtins")
        ;; Convert Java signed bytes to unsigned (0-255 range)
        ;; Java bytes are -128 to 127, but Python bytes needs 0-255
        unsigned-bytes (mapv #(bit-and (int %) 0xFF) wav-bytes)
        ;; Convert to Python bytes using bytes() constructor
        py-bytes (py/call-attr builtins "bytes" unsigned-bytes)
        ;; Create Python BytesIO from bytes
        bytes-io (py/call-attr pyio "BytesIO" py-bytes)
        ;; Read audio using soundfile (returns [data, samplerate])
        result (py/call-attr sf "read" bytes-io)
        audio-data (py/get-item result 0)
        sample-rate (py/get-item result 1)]

    ;; Validate sample rate
    (when (not= sample-rate 16000)
      (throw (ex-info "Audio must be 16kHz"
                      {:sample-rate sample-rate
                       :expected 16000})))

    ;; Convert to float32 for Whisper
    (py/call-attr audio-data "astype" (py/get-attr np "float32"))))

(defn validate-audio
  "Validate audio byte array. Returns true if valid, throws exception otherwise."
  [wav-bytes]
  (when (nil? wav-bytes)
    (throw (ex-info "Audio data is nil" {})))

  (when (zero? (alength wav-bytes))
    (throw (ex-info "Audio data is empty" {})))

  true)
