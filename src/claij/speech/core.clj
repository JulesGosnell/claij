(ns claij.speech.core
  (:require [clojure.java.io :as io]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [clojure.data.json :refer [read-str]])
  (:import [javax.sound.sampled AudioFormat AudioSystem DataLine$Info TargetDataLine AudioFileFormat$Type]
           [java.io ByteArrayOutputStream]
           [java.nio ByteBuffer ByteOrder]))

(def audio-format
  (AudioFormat. 16000 16 1 true false)) ; 16kHz, 16-bit, mono, signed PCM, little-endian

(def default-whisper-url "http://prognathodon:8000/transcribe")
(def silence-threshold 300) ; Amplitude threshold for silence detection
(def silence-duration-ms 1000) ; 1s silence to stop
(def sample-rate 16000)
(def bytes-per-ms (* sample-rate 2 0.001)) ; 2 bytes per sample

(defn detect-speech [buffer]
  (let [bb (ByteBuffer/wrap buffer)
        _ (.order bb ByteOrder/LITTLE_ENDIAN)
        shorts (.asShortBuffer bb)
        max-amplitude (loop [i 0 max-amp 0]
                        (if (< i (.remaining shorts))
                          (recur (inc i) (max max-amp (abs (.get shorts i))))
                          max-amp))
        speech? (> max-amplitude silence-threshold)]
    (when (zero? (rand-int 100)) ; Log occasionally to avoid spam
      (log/debug "Max amplitude:" max-amplitude "Speech detected:" speech?))
    speech?))

(defn record-audio []
  (let [line-info (DataLine$Info. TargetDataLine audio-format)
        ^TargetDataLine line (AudioSystem/getLine line-info)
        buffer (byte-array (* 1024 2)) ; 2 bytes per sample
        out (ByteArrayOutputStream.)]
    (.open line audio-format)
    (.start line)
    (log/info "Recording started...")
    (loop [is-speaking? false
           silence-start nil]
      (let [bytes-read (.read line buffer 0 (alength buffer))]
        (if (<= bytes-read 0)
          (do
            (.stop line)
            (.close line)
            (log/info "Recording stopped.")
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

(defn save-audio [audio-data]
  (let [file (io/file "temp_recording.wav")]
    (with-open [audio-in (java.io.ByteArrayInputStream. audio-data)]
      (javax.sound.sampled.AudioSystem/write
       (javax.sound.sampled.AudioInputStream. audio-in audio-format (/ (alength audio-data) 2))
       AudioFileFormat$Type/WAVE file))
    (.getAbsolutePath file)))

(defn post-to-whisper
  ([audio-file] (post-to-whisper audio-file default-whisper-url))
  ([audio-file whisper-url]
   (try
     (let [response (http/post whisper-url
                               {:multipart [{:name "audio" :content (io/file audio-file)}]
                                :throw-exceptions false})]
       (if (= (:status response) 200)
         (:text (read-str (:body response) :key-fn keyword))
         (throw (Exception. (str "HTTP error: " (:status response))))))
     (catch Exception e
       (log/error "Failed to post to Whisper:" (.getMessage e))
       nil))))

(defn main-loop
  ([] (main-loop default-whisper-url))
  ([whisper-url]
   (while true
     (log/info "Waiting for speech...")
     (let [audio-data (record-audio)
           audio-file (save-audio audio-data)]
       (log/info "Sending to speech-to-text service at" whisper-url)
       (if-let [text (post-to-whisper audio-file whisper-url)]
         (log/info "Transcription:" text)
         (log/error "Transcription failed"))
       (io/delete-file audio-file true)
       (Thread/sleep 1000)))))

(defn -main [& args]
  (let [whisper-url (or (first args)
                        (System/getenv "WHISPER_URL")
                        default-whisper-url)]
    (log/info "Starting speech-to-text client, service URL:" whisper-url)
    (main-loop whisper-url)))
