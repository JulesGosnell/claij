(ns claij.stt.whisper.audio
  "Audio processing utilities for Whisper backend.
   
   This namespace provides in-memory audio processing functions for the Whisper
   transcription backend. Key features:
   
   - Converts WAV format bytes to numpy arrays for Whisper
   - All operations performed in-memory (no temporary files)
   - Handles Java/Python byte representation differences
   - Automatically resamples audio to 16kHz for Whisper compatibility
   
   Public API:
   - wav-bytes->audio-array: Convert WAV bytes to numpy float32 array
   
   Dependencies:
   - Requires libpython-clj and Python libraries (numpy, soundfile, scipy)
   - Use with :whisper alias: clojure -M:whisper")

;;; Python Interop Helpers

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

;;; Byte Conversion

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
     module-cache - Map of Python modules
     java-bytes - Java byte array
   
   Returns:
     Python bytes object suitable for BytesIO."
  [module-cache ^bytes java-bytes]
  (let [builtins (:builtins module-cache)
        unsigned-bytes (map unsigned-byte java-bytes)]
    (py-call-attr builtins "bytes" unsigned-bytes)))

;;; Audio Processing

(defn- create-bytes-io
  "Create Python BytesIO from Java byte array.
   
   Args:
     module-cache - Map of Python modules
     java-bytes - Java byte array
   
   Returns:
     Python BytesIO object."
  [module-cache ^bytes java-bytes]
  (let [pyio (:io module-cache)
        py-bytes (java-bytes->python-bytes module-cache java-bytes)]
    (py-call-attr pyio "BytesIO" py-bytes)))

(defn- read-wav-from-bytesio
  "Read audio data and sample rate from BytesIO using soundfile.
   
   Args:
     module-cache - Map of Python modules
     bytes-io - Python BytesIO object
   
   Returns:
     [audio-data sample-rate] tuple."
  [module-cache bytes-io]
  (let [sf (:soundfile module-cache)]
    (py-call-attr sf "read" bytes-io)))

(defn- resample-audio
  "Resample audio data to target sample rate using scipy.
   
   Args:
     module-cache - Map of Python modules
     audio-data - Numpy array of audio samples
     orig-sr - Original sample rate
     target-sr - Target sample rate (default 16000)
   
   Returns:
     Resampled numpy array."
  [module-cache audio-data orig-sr target-sr]
  (if (= orig-sr target-sr)
    audio-data
    (let [signal (:scipy-signal module-cache)
          ;; Calculate number of samples in output
          num-samples (py-get-attr audio-data "shape")
          ;; Use scipy.signal.resample for high-quality resampling
          new-num-samples (int (* (first num-samples) (/ target-sr orig-sr)))]
      (py-call-attr signal "resample" audio-data new-num-samples))))

(defn- to-float32
  "Convert numpy array to float32 type for Whisper.
   
   Args:
     module-cache - Map of Python modules
     audio-data - Numpy array
   
   Returns:
     Float32 numpy array."
  [module-cache audio-data]
  (let [np (:numpy module-cache)]
    (py-call-attr audio-data "astype" (py-get-attr np "float32"))))

;;; Public API

(defn wav-bytes->audio-array
  "Convert WAV format bytes to numpy float32 array suitable for Whisper.
   Processes audio entirely in memory using Python's BytesIO.
   Automatically resamples to 16kHz if needed.
   
   Args:
     module-cache - Map of Python modules (:numpy, :soundfile, :io, :builtins, :scipy-signal)
     wav-bytes - WAV format audio as byte array
   
   Returns:
     Numpy array of audio samples at 16kHz as float32."
  [module-cache ^bytes wav-bytes]
  (let [bytes-io (create-bytes-io module-cache wav-bytes)
        [audio-data sample-rate] (read-wav-from-bytesio module-cache bytes-io)
        ;; Resample to 16kHz if needed
        resampled (resample-audio module-cache audio-data sample-rate 16000)]
    (to-float32 module-cache resampled)))
