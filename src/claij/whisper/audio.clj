(ns claij.whisper.audio
  "Audio processing utilities for Whisper service.
   
   This namespace provides in-memory audio processing functions for the Whisper
   transcription service. Key features:
   
   - Converts WAV format bytes to numpy arrays for Whisper
   - All operations performed in-memory (no temporary files)
   - Handles Java/Python byte representation differences
   - Validates audio format and sample rate
   
   Public API:
   - wav-bytes->audio-array: Convert WAV bytes to numpy float32 array
   - validate-audio: Validate audio byte array
   
   Dependencies:
   - Requires libpython-clj and Python libraries (numpy, soundfile)
   - Use with :whisper alias: clojure -M:whisper"
  (:require [clojure.tools.logging :as log]
            [claij.whisper.python :as whisper-py]))

(set! *warn-on-reflection* true)

(defn- java-bytes->python-bytes
  "Convert Java byte array to Python bytes object.
   
   Java bytes are signed (-128 to 127), Python bytes are unsigned (0-255).
   This function handles the conversion by masking with 0xFF to get unsigned values.
   
   Example: Java byte -1 (0xFF) becomes Python byte 255
   
   Args:
     java-bytes - Java byte array
   
   Returns:
     Python bytes object suitable for BytesIO."
  [^bytes java-bytes]
  (let [builtins (whisper-py/get-python-module :builtins)
        unsigned-bytes (map #(bit-and (long %) 0xFF) java-bytes)]
    (whisper-py/py-call-attr builtins "bytes" unsigned-bytes)))

(defn wav-bytes->audio-array
  "Convert WAV format bytes to numpy float32 array suitable for Whisper.
   Processes audio entirely in memory using Python's BytesIO.
   Returns numpy array of audio samples at 16kHz."
  [^bytes wav-bytes]
  (whisper-py/ensure-modules-loaded!)
  (let [np (whisper-py/get-python-module :numpy)
        sf (whisper-py/get-python-module :soundfile)
        pyio (whisper-py/get-python-module :io)
        ;; Convert to Python bytes and create BytesIO
        py-bytes (java-bytes->python-bytes wav-bytes)
        bytes-io (whisper-py/py-call-attr pyio "BytesIO" py-bytes)
        ;; Read audio using soundfile (returns [data, samplerate])
        [audio-data sample-rate] (whisper-py/py-call-attr sf "read" bytes-io)]

    ;; Validate sample rate
    (when (not= sample-rate 16000)
      (throw (ex-info "Audio must be 16kHz"
                      {:sample-rate sample-rate
                       :expected 16000})))

    ;; Convert to float32 for Whisper
    (whisper-py/py-call-attr audio-data "astype" (whisper-py/py-get-attr np "float32"))))


