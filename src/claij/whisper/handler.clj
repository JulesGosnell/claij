(ns claij.whisper.handler
  "Ring HTTP handler for Whisper transcription service."
  (:require [claij.whisper.audio :as audio]
            [claij.whisper.python :as whisper]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]))

(defn- bytes-from-multipart
  "Extract bytes from a multipart file upload."
  [file-part]
  (cond
    (bytes? file-part) file-part
    (map? file-part) (or (:bytes file-part)
                         (:tempfile file-part)
                         (throw (ex-info "Invalid file part format" {:part file-part})))
    :else (throw (ex-info "Unexpected file part type" {:type (type file-part)}))))

(defn transcribe-handler
  "Ring handler for POST /transcribe endpoint.
   Expects multipart form data with an 'audio' field containing WAV bytes."
  [request]
  (try
    (let [audio-part (get-in request [:multipart-params "audio"])
          _ (log/info "Received audio file for transcription:" (:filename audio-part))

          ;; Extract bytes from multipart upload
          audio-bytes (bytes-from-multipart audio-part)
          _ (log/debug "Audio file size:" (alength audio-bytes) "bytes")

          ;; Validate audio
          _ (audio/validate-audio audio-bytes)

          ;; Convert WAV bytes to numpy array (in memory)
          audio-array (audio/wav-bytes->audio-array audio-bytes)
          _ (log/debug "Converted to audio array")

          ;; Transcribe
          result (whisper/transcribe-audio audio-array)

          ;; Return JSON response
          response-body (json/write-str {:text (:text result)})]

      {:status 200
       :headers {"Content-Type" "application/json"}
       :body response-body})

    (catch Exception e
      (log/error e "Transcription failed")
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error (.getMessage e)})})))

(defn health-handler
  "Simple health check endpoint."
  [_request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {:status "healthy"
                          :service "whisper-clojure"})})

(defn not-found-handler
  "404 handler."
  [_request]
  {:status 404
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {:error "Not found"})})

(defn app
  "Main Ring application handler."
  [request]
  (let [uri (:uri request)
        method (:request-method request)]
    (cond
      (and (= uri "/transcribe") (= method :post))
      (transcribe-handler request)

      (and (= uri "/health") (= method :get))
      (health-handler request)

      :else
      (not-found-handler request))))
