(ns claij.stt.record
  "Audio recording and speech-to-text client.
   
   This namespace provides functionality for recording audio from the microphone
   and sending it to a Whisper STT service for transcription.
   
   Features:
   - Voice activity detection (VAD) to start/stop recording automatically
   - In-memory WAV conversion (no temporary files)
   - Transducer-based processing pipeline
   - Lazy sequence of transcriptions
   
   Usage:
   ;; Start recording loop (blocks until interrupted)
   (start-recording-loop \"http://localhost:8000/transcribe\")
   
   ;; Or get a lazy sequence of transcriptions
   (take 5 (transcription-seq \"http://localhost:8000/transcribe\"))
   
   Related namespaces:
   - claij.stt.server - Whisper STT service
   - claij.tts.playback - Audio playback (opposite of recording)"
  (:require [clj-http.client :refer [post]]
            [clojure.tools.logging :as log]
            [clojure.data.json :refer [read-str]]
            [clojure.string :refer [blank? trim]]
            [claij.tts.playback :refer [play-audio]]
            [claij.tts.core :refer [initialize! synthesize]]
            [claij.tts.piper.python :refer [create-backend]])
  (:import [javax.sound.sampled AudioFormat AudioSystem DataLine$Info TargetDataLine AudioFileFormat$Type AudioInputStream]
           [java.io ByteArrayOutputStream ByteArrayInputStream]
           [java.nio ByteBuffer ByteOrder]))

;; Audio configuration
(def audio-format
  (AudioFormat. 16000 16 1 true false)) ; 16kHz, 16-bit, mono, signed PCM, little-endian

(def default-whisper-url "http://prognathodon:8000/transcribe")
(def default-llms-url "http://megalodon:8000/chat")
(def silence-threshold 2000)
(def silence-duration-ms 1000)
(def min-audio-bytes 32000) ; ~1 second at 16kHz, 16-bit

;; Core recording function (not easily testable - hardware dependent)
(defn detect-speech
  "Detect speech in audio buffer based on amplitude threshold."
  [buffer]
  (let [^ByteBuffer bb (ByteBuffer/wrap buffer)
        _ (.order bb ByteOrder/LITTLE_ENDIAN)
        ^java.nio.ShortBuffer shorts (.asShortBuffer bb)
        max-amplitude (loop [i 0 max-amp (long 0)]
                        (if (< i (.remaining shorts))
                          (recur (inc i) (max max-amp (Math/abs (long (.get shorts (int i))))))
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
  [^bytes audio-data]
  (boolean
   (and audio-data
        (>= (alength audio-data) min-audio-bytes))))

(defn audio-data->wav-bytes
  "Convert raw PCM audio data to WAV format bytes (in memory)."
  [^bytes audio-data]
  (with-open [baos (ByteArrayOutputStream.)
              audio-in (ByteArrayInputStream. audio-data)]
    (let [audio-stream (AudioInputStream. audio-in audio-format (/ (alength audio-data) 2))]
      (AudioSystem/write audio-stream AudioFileFormat$Type/WAVE baos))
    (.toByteArray baos)))

(defn post-to-whisper
  "Post audio data to Whisper service. Returns transcription text or nil."
  [wav-bytes whisper-url]
  (try
    (let [response (post whisper-url
                         {:multipart [{:name "audio"
                                       :content wav-bytes
                                       :filename "audio.wav"}]
                          :throw-exceptions false})]
      (if (= (:status response) 200)
        (:text (read-str (:body response) :key-fn keyword))
        (do
          (log/error "HTTP error from Whisper service:" (:status response))
          nil)))
    (catch Exception e
      (log/error "Failed to post to Whisper:" (.getMessage e))
      nil)))

(defn post-to-llms
  "Post text to LLMs service. Returns answer text or nil."
  [question llms-url]
  (try
    (let [response (post llms-url
                         {:body question
                          :throw-exceptions false})]
      (if (= (:status response) 200)
        (:body response)
        (do
          (log/error "HTTP error from LLMs service:" (:status response))
          nil)))
    (catch Exception e
      (log/error "Failed to post to LLMs:" (.getMessage e))
      nil)))

;; Transducers for the audio processing pipeline

(defn prepare-audio [ctx]
  (assoc ctx :wav-bytes (audio-data->wav-bytes (:audio-data ctx))))

(defn transcribe [whisper-url ctx]
  (assoc ctx :text (trim (post-to-whisper (:wav-bytes ctx) whisper-url))))

(defn ask-llm [llms-url {text :text llm :llm :as ctx}]
  (assoc ctx :answer (post-to-llms text (str llms-url "/" llm))))

(def backends
  "Lazy map of LLM names to TTS backend factories.
   Backends are created on first use via delay."
  {"grok" (delay (doto (create-backend {:voice-path "/home/jules/piper-voices/cori-med.onnx"})
                   (initialize!)))
   "claude" (delay (doto (create-backend {:voice-path "/home/jules/piper-voices/en_US-lessac-medium.onnx"})
                     (initialize!)))
   "gpt" (delay (doto (create-backend {:voice-path "/home/jules/piper-voices/kristin.onnx"})
                  (initialize!)))
   "gemini" (delay (doto (create-backend {:voice-path "/home/jules/piper-voices/norman.onnx"})
                     (initialize!)))})

(defn tts [{llm :llm :as ctx}]
  (assoc ctx :output (:audio-bytes (synthesize @(backends llm) (:answer ctx)))))

(defn playback [ctx]
  (play-audio (:output ctx) :paplay)
  ctx)

(defn trace [m f ctx]
  (if-let [v (f ctx)]
    (log/info m ":" (pr-str v))
    (log/warn m " failed"))
  ctx)

;;------------------------------------------------------------------------------
;; routing

(defn lazy-split [^String s re]
  (let [^java.util.regex.Matcher m (re-matcher re s)]
    (letfn [(next-split [start]
              (lazy-seq
               (if (.find m start)
                 (cons (.substring s start (.start m))
                       (next-split (.end m)))
                 (when (< start (.length s))
                   (list (.substring s start))))))]
      (next-split 0))))

;; needs more work and integration...
(let [greetings (sort-by count > [["hey"] ["hi"] ["hello"] ["good" "morning"] ["morning"] ["good" "afternoon"] ["afternoon"] ["good" "evening"] ["evening"] ["so"] [] ["well"]])
      llm-ids {"grok" "grok" "claude" "claude" "gpt" "gpt" "gemini" "gemini" "grock" "grok" "grog" "grok" "crook" "grok" "grokk" "grok" "grook" "grok" "gruck" "grok"}
      current-llm (atom "claude")
      max-greeting-len (apply max (map count greetings))]
  (defn select-llm [text]
    (let [words (map #(clojure.string/replace % #"[^\w]" "")
                     (take (inc max-greeting-len)
                           (lazy-split (clojure.string/lower-case text) #"\s+")))
          greeting-len (some (fn [g] (when (= (take (count g) words) g) (count g))) greetings)]
      (if greeting-len
        (let [candidate (nth words greeting-len "")]
          (if-let [selected (get llm-ids candidate)]
            (reset! current-llm selected)
            @current-llm))
        @current-llm))))

;;------------------------------------------------------------------------------
;; Pipeline composition

(defn process-audio-xf
  "Complete audio processing pipeline (all in-memory, no file I/O)."
  [whisper-url llms-url]
  (comp
   (filter (comp has-audio? :audio-data))
   (map prepare-audio)
   (map (partial trace "recording" :audio-data))
   (map (partial transcribe whisper-url))
   (map (partial trace "transcription" :text))
   (filter (comp not blank? :text))
   (map (fn [{t :text :as ctx}] (assoc ctx :llm (select-llm t))))
   (map (partial ask-llm llms-url))
   (map (partial trace "llm" :answer))
   (map tts)
   (map (partial trace "tts" :output))
   (map playback)))

;; Main loop

(defn start-recording-loop
  "Start the speech-to-text recording loop synchronously."
  ([] (start-recording-loop default-whisper-url default-llms-url))
  ([whisper-url llms-url]
   (log/info "Starting speech-to-text client, service URL:" whisper-url)
   (let [process (process-audio-xf whisper-url llms-url)]
     (loop []
       (log/info "Waiting for speech...")
       (let [ctx {:audio-data (record-audio)}]
         (when (has-audio? (:audio-data ctx))
           (dorun (sequence process [ctx]))))
       (recur)))))

(defn -main [& args]
  (let [whisper-url (or (first args)
                        (System/getenv "WHISPER_URL")
                        default-whisper-url)
        llms-url (or (second args)
                     (System/getenv "LLMS_URL")
                     default-llms-url)]
    (start-recording-loop whisper-url llms-url)))

;; lets leave the llm ms remote at the moment so that restarting does not throw away our state,
;; we need to extend the ms so that we can control which llm we want to send the message to and this should also select the voice to be used
;; we need to refactor and simplify the piper stuff - it is a mess
