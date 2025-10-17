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
  (:require [claij.whisper.python :as whisper-py]))

(set! *warn-on-reflection* true)

(defn- unsigned-byte
  "Convert a Java signed byte to unsigned (0-255).
   Java bytes are signed (-128 to 127), so we mask with 0xFF."
  [b]
  (bit-and (long b) 0xFF))

(defn- java-bytes->python-bytes
  "Convert Java byte array to Python bytes object.
   
   Java bytes are signed (-128 to 127), Python bytes are unsigned (0-255).
   This function converts by mapping each byte to unsigned representation.
   
   Example: Java byte -1 (0xFF) becomes Python byte 255
   
   Args:
     java-bytes - Java byte array
   
   Returns:
     Python bytes object suitable for BytesIO."
  [^bytes java-bytes]
  (let [builtins (whisper-py/get-python-module :builtins)
        unsigned-bytes (map unsigned-byte java-bytes)]
    (whisper-py/py-call-attr builtins "bytes" unsigned-bytes)))

(defn- create-bytes-io
  "Create Python BytesIO from Java byte array."
  [^bytes java-bytes]
  (let [pyio (whisper-py/get-python-module :io)
        py-bytes (java-bytes->python-bytes java-bytes)]
    (whisper-py/py-call-attr pyio "BytesIO" py-bytes)))

(defn- read-wav-from-bytesio
  "Read audio data and sample rate from BytesIO using soundfile.
   Returns [audio-data sample-rate]."
  [bytes-io]
  (let [sf (whisper-py/get-python-module :soundfile)]
    (whisper-py/py-call-attr sf "read" bytes-io)))

(defn- validate-sample-rate!
  "Validate that audio is at 16kHz. Throws exception if not."
  [sample-rate]
  (when (not= sample-rate 16000)
    (throw (ex-info "Audio must be 16kHz"
                    {:sample-rate sample-rate
                     :expected 16000}))))

(defn- to-float32
  "Convert numpy array to float32 type for Whisper."
  [audio-data]
  (let [np (whisper-py/get-python-module :numpy)]
    (whisper-py/py-call-attr audio-data "astype"
                             (whisper-py/py-get-attr np "float32"))))

(defn wav-bytes->audio-array
  "Convert WAV format bytes to numpy float32 array suitable for Whisper.
   Processes audio entirely in memory using Python's BytesIO.
   Returns numpy array of audio samples at 16kHz."
  [^bytes wav-bytes]
  (whisper-py/ensure-modules-loaded!)
  (let [bytes-io (create-bytes-io wav-bytes)
        [audio-data sample-rate] (read-wav-from-bytesio bytes-io)]
    (validate-sample-rate! sample-rate)
    (to-float32 audio-data)))


