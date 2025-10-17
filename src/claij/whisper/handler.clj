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

(set! *warn-on-reflection* true)

(defn- log-audio-info
  "Log audio file information and return audio-part unchanged."
  [audio-part]
  (log/info "Received audio file:" (:filename audio-part))
  audio-part)

(defn- log-audio-size
  "Log audio byte size and return bytes unchanged."
  [audio-bytes]
  (log/debug "Audio size:" (count audio-bytes) "bytes")
  audio-bytes)

(defn- build-success-response
  "Build successful transcription response."
  [result]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {:text (:text result)})})

(defn- build-error-response
  "Build error response from exception."
  [exception]
  (log/error exception "Transcription failed")
  {:status 500
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {:error (.getMessage exception)})})

(defn transcribe-handler
  "Ring handler for POST /transcribe endpoint.
   Expects multipart form data with an 'audio' field containing WAV bytes."
  [request]
  (try
    (-> request
        (get-in [:multipart-params "audio"])
        log-audio-info
        multipart/extract-bytes
        log-audio-size
        (doto multipart/validate-audio)
        audio/wav-bytes->audio-array
        whisper/transcribe-audio
        build-success-response)
    (catch Exception e
      (build-error-response e))))

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

(defn- match-route
  "Match request to handler based on URI and method."
  [uri method]
  (cond
    (and (= uri "/transcribe") (= method :post)) :transcribe
    (and (= uri "/health") (= method :get)) :health
    :else :not-found))

(defn- dispatch-handler
  "Dispatch to the appropriate handler based on route."
  [route request]
  (case route
    :transcribe (transcribe-handler request)
    :health (health-handler request)
    :not-found (not-found-handler request)))

(defn app
  "Main Ring application handler.
   Routes requests to appropriate handlers based on URI and method."
  [request]
  (let [uri (:uri request)
        method (:request-method request)
        route (match-route uri method)]
    (dispatch-handler route request)))
