(ns claij.whisper.handler
  "Ring HTTP handlers for Whisper transcription service.
   
   Provides HTTP endpoints for the Whisper service:
   - POST /transcribe: Transcribe audio file to text
   - GET /health: Health check endpoint
   
   Handlers accept multipart form data with audio files and return JSON responses.
   
   Public API:
   - app: Main Ring application handler with routing"
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [claij.whisper.audio :as audio]
            [claij.whisper.multipart :as multipart]
            [claij.whisper.python :as whisper]))

(defn transcribe-handler
  "Ring handler for POST /transcribe endpoint.
   Expects multipart form data with an 'audio' field containing WAV bytes."
  [request]
  (try
    (let [audio-part (get-in request [:multipart-params "audio"])
          _ (log/info "Received audio file:" (:filename audio-part))
          audio-bytes (multipart/extract-bytes audio-part)
          _ (log/debug "Audio size:" (count audio-bytes) "bytes")
          _ (multipart/validate-audio audio-bytes)
          result (-> audio-bytes
                     audio/wav-bytes->audio-array
                     whisper/transcribe-audio)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:text (:text result)})})
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
