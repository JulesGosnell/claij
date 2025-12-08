(ns claij.tts.playback
  "Audio playback utilities for TTS.
   
   Provides functions to play audio bytes using system audio players:
   - Linux: aplay, paplay
   - macOS: afplay
   - Windows: Not yet supported
   
   Usage:
   (def result (synthesize backend \"Hello world\"))
   (play-audio (:audio-bytes result))
   
   Or with a specific player:
   (play-audio (:audio-bytes result) {:player :paplay})"
  (:require [clojure.tools.logging :as log]
            [clojure.java.shell :refer [sh]]
            [claij.tts.core :refer [synthesize]])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- detect-audio-player
  "Detect available audio player on the system."
  []
  (let [players [{:cmd "aplay" :name :aplay}
                 {:cmd "paplay" :name :paplay}
                 {:cmd "afplay" :name :afplay}]]
    (some (fn [{:keys [cmd name]}]
            (let [result (sh "which" cmd)]
              (when (zero? (:exit result))
                name)))
          players)))

(defn- create-temp-wav-file
  "Create a temporary WAV file with the given audio bytes.
   Returns the File object."
  ^File [^bytes audio-bytes]
  (let [temp-file (File/createTempFile "tts-audio-" ".wav")]
    (.deleteOnExit temp-file)
    (with-open [out (java.io.FileOutputStream. temp-file)]
      (.write out audio-bytes))
    temp-file))

(defn- play-with-aplay
  "Play audio using aplay (ALSA player)."
  [^File audio-file]
  (sh "aplay" "-d" "1" "/dev/zero")
  (let [result (sh "aplay" "-q" (.getAbsolutePath audio-file))]
    (when-not (zero? (:exit result))
      (throw (ex-info "aplay failed"
                      {:exit-code (:exit result)
                       :stderr (:err result)})))))

(defn- play-with-paplay
  "Play audio using paplay (PulseAudio player)."
  [^File audio-file]
  (let [result (sh "paplay" (.getAbsolutePath audio-file))]
    (when-not (zero? (:exit result))
      (throw (ex-info "paplay failed"
                      {:exit-code (:exit result)
                       :stderr (:err result)})))))

(defn- play-with-afplay
  "Play audio using afplay (macOS audio player)."
  [^File audio-file]
  (let [result (sh "afplay" (.getAbsolutePath audio-file))]
    (when-not (zero? (:exit result))
      (throw (ex-info "afplay failed"
                      {:exit-code (:exit result)
                       :stderr (:err result)})))))

(defn play-audio
  "Play audio bytes using system audio player.
   
   Parameters:
   - audio-bytes: byte array of WAV audio data
   - options (optional): map with:
     - :player - Specific player to use (:aplay, :paplay, :afplay, :auto)
     - :async - If true, play in background and return immediately (default: false)
   
   Returns:
   - nil on success
   - throws ex-info on failure
   
   Examples:
   (play-audio audio-bytes)
   (play-audio audio-bytes {:player :paplay})
   (play-audio audio-bytes {:async true})"
  ([audio-bytes]
   (play-audio audio-bytes {}))
  ([audio-bytes {:keys [player async] :or {player :auto async false}}]
   (let [player (if (= player :auto)
                  (or (detect-audio-player)
                      (throw (ex-info "No audio player found"
                                      {:available-players [:aplay :paplay :afplay]})))
                  player)
         ^File temp-file (create-temp-wav-file audio-bytes)
         play-fn (case player
                   :aplay play-with-aplay
                   :paplay play-with-paplay
                   :afplay play-with-afplay
                   (throw (ex-info "Unknown player"
                                   {:player player
                                    :available [:aplay :paplay :afplay]})))]
     (try
       (if async
         (future
           (try
             (play-fn temp-file)
             (log/info "Audio playback completed")
             (catch Exception e
               (log/error e "Audio playback failed"))))
         (do
           (play-fn temp-file)
           (log/info "Audio playback completed")))
       nil
       (finally
         ;; Clean up temp file after a delay (if async, give it time to play)
         (if async
           (future
             (Thread/sleep 10000)
             (.delete temp-file))
           (.delete temp-file)))))))

(defn speak
  "Convenience function: synthesize text and play it immediately.
   
   Parameters:
   - backend: TTS backend instance
   - text: Text to synthesize and speak
   - options (optional): map with playback options (see play-audio)
   
   Returns:
   - The synthesis result map (with :audio-bytes and :sample-rate)
   
   Example:
   (speak backend \"Hello, world!\")
   (speak backend \"Hello, world!\" {:async true})"
  ([backend text]
   (speak backend text {}))
  ([backend text options]
   (let [result (synthesize backend text)]
     (play-audio (:audio-bytes result) options)
     result)))

(comment
  ;; Usage examples
  (require '[claij.tts.piper.python :as piper])

  ;; Create backend and synthesize
  (def backend (piper/create-backend {:voice-path "/path/to/voice.onnx"}))
  (piper/initialize! backend)

  ;; Synthesize and play
  (def result (piper/synthesize backend "Hello from Clojure!"))
  (play-audio (:audio-bytes result))

  ;; Or use the convenience function
  (speak backend "This is a test of text to speech")

  ;; Play in background
  (speak backend "Playing in background" {:async true})

  ;; Use specific player
  (speak backend "Using paplay" {:player :paplay}))
