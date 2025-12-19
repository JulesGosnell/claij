(ns claij.voice.ui
  "Voice UI for CLAIJ - Bath Driven Development.
   
   Records audio from microphone, sends to /voice endpoint,
   plays back the response audio."
  (:require [clojure.string :as str]))

;; Forward declaration for mutual recursion
(declare send-audio!)

;;-----------------------------------------------------------------------------
;; State
;;-----------------------------------------------------------------------------

(defonce state
  (atom {:status :idle ; :idle :recording :processing :playing
         :media-stream nil
         :audio-context nil
         :analyser nil
         :script-processor nil
         :audio-chunks []
         :error nil}))

;;-----------------------------------------------------------------------------
;; DOM Helpers
;;-----------------------------------------------------------------------------

(defn by-id [id]
  (.getElementById js/document id))

(defn set-text! [el text]
  (set! (.-textContent el) text))

(defn add-class! [el class]
  (.add (.-classList el) class))

(defn remove-class! [el class]
  (.remove (.-classList el) class))

(defn has-class? [el class]
  (.contains (.-classList el) class))

;;-----------------------------------------------------------------------------
;; UI Updates
;;-----------------------------------------------------------------------------

(defn update-status! [text & [type]]
  (let [el (by-id "status")]
    (set-text! el text)
    (set! (.-className el) (or type ""))))

(defn update-button-state! [status]
  (let [btn (by-id "recordBtn")]
    (remove-class! btn "recording")
    (remove-class! btn "processing")
    (set! (.-disabled btn) false)
    (case status
      :recording (add-class! btn "recording")
      :processing (do (add-class! btn "processing")
                      (set! (.-disabled btn) true))
      nil)))

(defn show-playback! [show?]
  (let [el (by-id "playbackIndicator")]
    (if show?
      (add-class! el "active")
      (remove-class! el "active"))))

(defn append-transcript! [text]
  (let [el (by-id "transcript")
        now (.toLocaleTimeString (js/Date.))]
    (set! (.-innerHTML el)
          (str (.-innerHTML el)
               "<p style='color: var(--text-secondary); margin-bottom: 0.5rem;'>"
               "[" now "] " text "</p>"))))

;;-----------------------------------------------------------------------------
;; Waveform Visualization
;;-----------------------------------------------------------------------------

(defn draw-idle-waveform! [canvas ctx]
  (let [w (.-width canvas)
        h (.-height canvas)]
    (set! (.-fillStyle ctx) "#12121a")
    (.fillRect ctx 0 0 w h)
    (set! (.-strokeStyle ctx) "rgba(0, 255, 136, 0.2)")
    (set! (.-lineWidth ctx) 1)
    (.beginPath ctx)
    (.moveTo ctx 0 (/ h 2))
    (.lineTo ctx w (/ h 2))
    (.stroke ctx)))

(defn draw-waveform! []
  (let [canvas (by-id "waveform")
        ctx (.getContext canvas "2d")
        {:keys [analyser status]} @state]
    (if-not analyser
      (draw-idle-waveform! canvas ctx)
      (let [buffer-length (.-frequencyBinCount analyser)
            data-array (js/Uint8Array. buffer-length)
            _ (.getByteTimeDomainData analyser data-array)
            w (.-width canvas)
            h (.-height canvas)
            slice-width (/ w buffer-length)]
        (set! (.-fillStyle ctx) "#12121a")
        (.fillRect ctx 0 0 w h)
        (set! (.-lineWidth ctx) 2)
        (set! (.-strokeStyle ctx) (if (= status :recording) "#ff4466" "#00ff88"))
        (.beginPath ctx)
        (loop [i 0 x 0]
          (when (< i buffer-length)
            (let [v (/ (aget data-array i) 128.0)
                  y (* (/ h 2) v)]
              (if (zero? i)
                (.moveTo ctx x y)
                (.lineTo ctx x y))
              (recur (inc i) (+ x slice-width)))))
        (.lineTo ctx w (/ h 2))
        (.stroke ctx)
        (when (= status :recording)
          (js/requestAnimationFrame draw-waveform!))))))

(defn resize-canvas! []
  (let [canvas (by-id "waveform")
        container (.-parentElement canvas)]
    (set! (.-width canvas) (.-clientWidth container))
    (set! (.-height canvas) (.-clientHeight container))
    (draw-waveform!)))

;;-----------------------------------------------------------------------------
;; Audio Encoding
;;-----------------------------------------------------------------------------

(defn encode-wav
  "Encode Float32Array samples to WAV blob."
  [samples sample-rate]
  (let [buffer (js/ArrayBuffer. (+ 44 (* (.-length samples) 2)))
        view (js/DataView. buffer)
        write-string (fn [offset s]
                       (doseq [i (range (count s))]
                         (.setUint8 view (+ offset i) (.charCodeAt s i))))]
    ;; WAV header
    (write-string 0 "RIFF")
    (.setUint32 view 4 (+ 36 (* (.-length samples) 2)) true)
    (write-string 8 "WAVE")
    (write-string 12 "fmt ")
    (.setUint32 view 16 16 true) ; Subchunk1Size
    (.setUint16 view 20 1 true) ; AudioFormat (PCM)
    (.setUint16 view 22 1 true) ; NumChannels (mono)
    (.setUint32 view 24 sample-rate true)
    (.setUint32 view 28 (* sample-rate 2) true) ; ByteRate
    (.setUint16 view 32 2 true) ; BlockAlign
    (.setUint16 view 34 16 true) ; BitsPerSample
    (write-string 36 "data")
    (.setUint32 view 40 (* (.-length samples) 2) true)

    ;; Convert samples to 16-bit PCM
    (doseq [i (range (.-length samples))]
      (let [s (max -1 (min 1 (aget samples i)))
            pcm (if (neg? s)
                  (* s 0x8000)
                  (* s 0x7FFF))]
        (.setInt16 view (+ 44 (* i 2)) pcm true)))

    (js/Blob. #js [buffer] #js {:type "audio/wav"})))

;;-----------------------------------------------------------------------------
;; Recording
;;-----------------------------------------------------------------------------

(defn start-recording! []
  (-> (js/navigator.mediaDevices.getUserMedia
       #js {:audio #js {:sampleRate 22050
                        :channelCount 1
                        :echoCancellation true
                        :noiseSuppression true}})
      (.then
       (fn [stream]
         ;; Create AudioContext - use webkit prefix for older iOS
         ;; Access via window object to avoid ClojureScript compile errors
         (let [AudioContext (or (.-AudioContext js/window)
                                (.-webkitAudioContext js/window))
               audio-ctx (AudioContext. #js {:sampleRate 22050})]

           ;; iOS requires explicit resume after user gesture
           (-> (.resume audio-ctx)
               (.then
                (fn []
                  (let [source (.createMediaStreamSource audio-ctx stream)
                        analyser (.createAnalyser audio-ctx)
                        _ (set! (.-fftSize analyser) 2048)
                        _ (.connect source analyser)

                        ;; Use ScriptProcessor to capture raw audio
                        ;; (deprecated but widely supported including iOS)
                        buffer-size 4096
                        processor (.createScriptProcessor audio-ctx buffer-size 1 1)]

                    (set! (.-onaudioprocess processor)
                          (fn [e]
                            (when (= (:status @state) :recording)
                              (let [channel-data (.getChannelData (.-inputBuffer e) 0)]
                                (swap! state update :audio-chunks conj (js/Float32Array. channel-data))))))

                    (.connect source processor)
                    (.connect processor (.-destination audio-ctx))

                    (swap! state assoc
                           :status :recording
                           :media-stream stream
                           :audio-context audio-ctx
                           :analyser analyser
                           :script-processor processor
                           :audio-chunks []
                           :error nil)

                    (update-button-state! :recording)
                    (update-status! "Recording... Click to stop")
                    (draw-waveform!))))
               (.catch
                (fn [err]
                  (js/console.error "AudioContext resume failed:" err)
                  ;; Clean up stream
                  (doseq [track (.getTracks stream)]
                    (.stop track))
                  (swap! state assoc :error "Audio initialization failed")
                  (update-status! "Error: Audio initialization failed (try reloading)" "error")))))))
      (.catch
       (fn [err]
         (js/console.error "Error starting recording:" err)
         (let [msg (cond
                     (= (.-name err) "NotAllowedError")
                     "Microphone access denied. Please allow microphone access."

                     (= (.-name err) "NotFoundError")
                     "No microphone found. Please connect a microphone."

                     (not js/navigator.mediaDevices)
                     "Microphone access requires HTTPS."

                     :else
                     (.-message err))]
           (swap! state assoc :error msg)
           (update-status! (str "Error: " msg) "error"))))))

(defn stop-recording! []
  (let [{:keys [media-stream audio-context script-processor audio-chunks]} @state]
    ;; Stop media stream
    (when media-stream
      (doseq [track (.getTracks media-stream)]
        (.stop track)))

    ;; Merge audio chunks
    (let [total-length (reduce + 0 (map #(.-length %) audio-chunks))
          merged (js/Float32Array. total-length)]
      (loop [chunks audio-chunks offset 0]
        (when (seq chunks)
          (.set merged (first chunks) offset)
          (recur (rest chunks) (+ offset (.-length (first chunks))))))

      ;; Encode to WAV
      (let [sample-rate (.-sampleRate audio-context)
            wav-blob (encode-wav merged sample-rate)]
        (js/console.log "Recorded WAV:" (.-size wav-blob) "bytes")

        ;; Clean up audio context
        (when script-processor
          (.disconnect script-processor))
        (when audio-context
          (.close audio-context))

        (swap! state assoc
               :status :processing
               :audio-context nil
               :analyser nil
               :script-processor nil)

        (update-button-state! :processing)
        (draw-waveform!)

        ;; Send to server
        (send-audio! wav-blob)))))

;;-----------------------------------------------------------------------------
;; Server Communication
;;-----------------------------------------------------------------------------

(defn send-audio! [wav-blob]
  (update-status! "Sending to CLAIJ...")

  (let [form-data (js/FormData.)]
    (.append form-data "audio" wav-blob "recording.wav")

    (-> (js/fetch "/voice"
                  #js {:method "POST"
                       :body form-data})
        (.then
         (fn [response]
           (if-not (.-ok response)
             (-> (.text response)
                 (.then (fn [text]
                          (throw (js/Error. (str "Server error: " (.-status response) " - " text))))))
             (.blob response))))
        (.then
         (fn [audio-blob]
           ;; Create visible audio player - iOS allows user-initiated playback
           (let [audio-url (.createObjectURL js/URL audio-blob)
                 container (by-id "audioPlayer")
                 reset-ui (fn []
                            (show-playback! false)
                            (swap! state assoc :status :idle)
                            (update-button-state! :idle)
                            (update-status! "Click to start recording")
                            (set! (.-innerHTML container) ""))]
             ;; Insert audio element with controls
             (set! (.-innerHTML container)
                   (str "<audio controls autoplay playsinline style='width:100%'>"
                        "<source src='" audio-url "' type='audio/wav'>"
                        "</audio>"))
             (let [audio (.querySelector container "audio")]
               ;; Set up event handlers BEFORE trying to play
               (.addEventListener audio "ended" reset-ui)
               (.addEventListener audio "error" (fn [e]
                                                  (js/console.error "Audio error:" e)
                                                  (reset-ui)))
               ;; Try autoplay, but it's OK if it fails - user can tap play
               (-> (.play audio)
                   (.catch (fn [_] nil)))) ; Ignore autoplay failure
             (show-playback! true)
             (swap! state assoc :status :playing)
             (update-status! "Tap ▶ to play response" "success")
             (append-transcript! "Response received"))))
        (.catch
         (fn [err]
           (js/console.error "Error sending audio:" err)
           (swap! state assoc :status :idle :error (.-message err))
           (update-button-state! :idle)
           (update-status! (str "Error: " (.-message err)) "error"))))))

;;-----------------------------------------------------------------------------
;; Event Handlers
;;-----------------------------------------------------------------------------

(defn toggle-recording! []
  (when-not (#{:processing :playing} (:status @state))
    (case (:status @state)
      :recording (stop-recording!)
      :idle (start-recording!)
      nil)))

(defn handle-keydown! [e]
  (when (and (= (.-code e) "Space")
             (not (.-repeat e))
             (= (.-activeElement js/document) (.-body js/document)))
    (.preventDefault e)
    (toggle-recording!)))

;;-----------------------------------------------------------------------------
;; Initialization
;;-----------------------------------------------------------------------------

(defn init! []
  (js/console.log "CLAIJ Voice UI initializing...")

  ;; Set up canvas
  (resize-canvas!)
  (.addEventListener js/window "resize" resize-canvas!)

  ;; Set up record button
  (let [btn (by-id "recordBtn")]
    (.addEventListener btn "click" toggle-recording!))

  ;; Set up keyboard shortcut
  (.addEventListener js/document "keydown" handle-keydown!)

  (js/console.log "CLAIJ Voice UI ready"))
