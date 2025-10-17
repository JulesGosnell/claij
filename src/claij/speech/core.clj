(ns claij.speech.core
  (:require [clojure.java.io :as io]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [clojure.data.json :refer [read-str]])
  (:import [javax.sound.sampled AudioFormat AudioSystem DataLine$Info TargetDataLine AudioFileFormat$Type]
           [java.io ByteArrayOutputStream File]
           [java.nio ByteBuffer ByteOrder]))

;; Audio configuration
(def audio-format
  (AudioFormat. 16000 16 1 true false)) ; 16kHz, 16-bit, mono, signed PCM, little-endian

(def default-whisper-url "http://prognathodon:8000/transcribe")
(def silence-threshold 300)
(def silence-duration-ms 1000)
(def min-audio-bytes 32000) ; ~1 second at 16kHz, 16-bit

;; Core recording function (not easily testable - hardware dependent)
(defn detect-speech [buffer]
  (let [bb (ByteBuffer/wrap buffer)
        _ (.order bb ByteOrder/LITTLE_ENDIAN)
        shorts (.asShortBuffer bb)
        max-amplitude (loop [i 0 max-amp 0]
                        (if (< i (.remaining shorts))
                          (recur (inc i) (max max-amp (abs (.get shorts i))))
                          max-amp))]
    (> max-amplitude silence-threshold)))

(defn record-audio []
  (let [line-info (DataLine$Info. TargetDataLine audio-format)
        ^TargetDataLine line (AudioSystem/getLine line-info)
        buffer (byte-array (* 1024 2))
        out (ByteArrayOutputStream.)]
    (.open line audio-format)
    (.start line)
    (log/info "🎤 Recording started...")
    (loop [is-speaking? false
           silence-start nil]
      (let [bytes-read (.read line buffer 0 (alength buffer))]
        (if (<= bytes-read 0)
          (do
            (.stop line)
            (.close line)
            (.toByteArray out))
          (let [speech-detected? (detect-speech buffer)]
            (.write out buffer 0 bytes-read)
            (if is-speaking?
              (if-not speech-detected?
                (let [silence-start (or silence-start (System/currentTimeMillis))]
                  (if (> (- (System/currentTimeMillis) silence-start) silence-duration-ms)
                    (do
                      (.stop line)
                      (.close line)
                      (log/info "Recording stopped.")
                      (.toByteArray out))
                    (recur is-speaking? silence-start)))
                (recur is-speaking? nil))
              (if speech-detected?
                (recur true nil)
                (recur false silence-start)))))))))

;; Testable pure functions

(defn has-audio?
  "Check if audio data contains meaningful content."
  [audio-data]
  (boolean
   (and audio-data
        (>= (alength audio-data) min-audio-bytes))))

(defn save-audio
  "Save audio data to a temporary WAV file. Returns filename."
  [audio-data]
  (let [file (File/createTempFile "claij-speech-" ".wav")]
    (with-open [audio-in (java.io.ByteArrayInputStream. audio-data)]
      (javax.sound.sampled.AudioSystem/write
       (javax.sound.sampled.AudioInputStream. audio-in audio-format (/ (alength audio-data) 2))
       AudioFileFormat$Type/WAVE file))
    (.getAbsolutePath file)))

(defn post-to-whisper
  "Post audio file to Whisper service. Returns transcription text or nil."
  [filename whisper-url]
  (try
    (let [response (http/post whisper-url
                              {:multipart [{:name "audio" :content (io/file filename)}]
                               :throw-exceptions false})]
      (if (= (:status response) 200)
        (:text (read-str (:body response) :key-fn keyword))
        (do
          (log/error "HTTP error from Whisper service:" (:status response))
          nil)))
    (catch Exception e
      (log/error "Failed to post to Whisper:" (.getMessage e))
      nil)))

(defn cleanup-file
  "Delete a file, returns nil. Safe to call with nil."
  [filename]
  (when filename
    (io/delete-file filename true))
  nil)

;; Transducers for the audio processing pipeline

(defn save-audio-xf
  "Transducer that saves audio data to file and adds :filename to context."
  [rf]
  (fn
    ([] (rf))
    ([result] (rf result))
    ([result ctx]
     (let [filename (save-audio (:audio-data ctx))]
       (rf result (assoc ctx :filename filename))))))

(defn transcribe-xf
  "Transducer that transcribes audio file and adds :text to context."
  [whisper-url]
  (fn [rf]
    (fn
      ([] (rf))
      ([result] (rf result))
      ([result ctx]
       (let [text (post-to-whisper (:filename ctx) whisper-url)]
         (rf result (assoc ctx :text text)))))))

(defn log-result-xf
  "Transducer that logs transcription results."
  [rf]
  (fn
    ([] (rf))
    ([result] (rf result))
    ([result ctx]
     (if (:text ctx)
       (log/info "Transcription:" (:text ctx))
       (log/error "Transcription failed"))
     (rf result ctx))))

(defn cleanup-xf
  "Transducer that cleans up temporary files."
  [rf]
  (fn
    ([] (rf))
    ([result] (rf result))
    ([result ctx]
     (cleanup-file (:filename ctx))
     (rf result ctx))))

;; Pipeline composition

(defn process-audio-xf
  "Complete audio processing pipeline."
  [whisper-url]
  (comp
   (filter (comp has-audio? :audio-data))
   save-audio-xf
   (transcribe-xf whisper-url)
   log-result-xf
   cleanup-xf))

;; Lazy sequence of recordings

(defn recording-seq
  "Lazy sequence of audio recordings. Each element is {:audio-data bytes}."
  []
  (repeatedly #(do
                 (log/info "Waiting for speech...")
                 {:audio-data (record-audio)})))

(defn transcription-seq
  "Lazy sequence of transcriptions. Returns a sequence of contexts with :text.
   Each element represents a processed sound-bite with transcription.
   
   Example:
     (take 5 (transcription-seq))
     => ({:text \"hello\"} {:text \"world\"} ...)"
  ([] (transcription-seq default-whisper-url))
  ([whisper-url]
   (let [recordings (recording-seq)
         process-xf (process-audio-xf whisper-url)]
     (sequence process-xf recordings))))

;; Main loop

(defn start-recording-loop
  "Start the speech-to-text recording loop with the given whisper service URL.
   Eagerly consumes the transcription-seq, logging results as they arrive."
  ([] (start-recording-loop default-whisper-url))
  ([whisper-url]
   (log/info "Starting speech-to-text client, service URL:" whisper-url)
   (run! identity (transcription-seq whisper-url))))

(defn -main [& args]
  (let [whisper-url (or (first args)
                        (System/getenv "WHISPER_URL")
                        default-whisper-url)]
    (start-recording-loop whisper-url)))
