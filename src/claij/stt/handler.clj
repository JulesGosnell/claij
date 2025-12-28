(ns claij.stt.handler
  "Generic Ring HTTP handlers for STT services.
   
   Provides HTTP endpoints for any STT backend:
   - POST /transcribe: Transcribe audio file to text
   - GET /health: Health check endpoint
   
   Handlers accept multipart form data with audio files and return JSON responses.
   
   This namespace is backend-agnostic - it works with any implementation of
   the STTBackend protocol.
   
   Usage:
   (def backend (whisper/create-backend {:model-size \"small\"}))
   (def app-handler (create-app backend))
   
   Public API:
   - create-app: Create Ring handler for a specific backend"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [claij.stt.core :refer [transcribe health-check backend-info]]
            [claij.stt.whisper.audio :refer [wav-bytes->audio-array]]
            [claij.stt.whisper.multipart :refer [extract-bytes validate-audio]]))

;;; OpenAPI Specification

(def openapi-spec
  "OpenAPI 3.0 specification for the STT service."
  {"openapi" "3.0.0"
   "info" {"title" "CLAIJ STT Service"
           "description" "Speech-to-Text service using Whisper"
           "version" "1.0.0"}
   "paths"
   {"/transcribe"
    {"post"
     {"operationId" "transcribe"
      "summary" "Transcribe audio to text"
      "description" "Accepts WAV audio and returns transcribed text"
      "requestBody"
      {"required" true
       "content"
       {"multipart/form-data"
        {"schema"
         {"type" "object"
          "required" ["audio"]
          "properties"
          {"audio" {"type" "string"
                    "format" "binary"
                    "description" "WAV audio file to transcribe"}}}}}}
      "responses"
      {"200" {"description" "Successful transcription"
              "content"
              {"application/json"
               {"schema"
                {"type" "object"
                 "properties"
                 {"text" {"type" "string"
                          "description" "Transcribed text"}
                  "language" {"type" "string"
                              "description" "Detected language code"}}}}}}
       "500" {"description" "Transcription failed"
              "content"
              {"application/json"
               {"schema"
                {"$ref" "#/components/schemas/Error"}}}}}}}

    "/health"
    {"get"
     {"operationId" "healthCheck"
      "summary" "Health check"
      "description" "Returns service health status and backend info"
      "responses"
      {"200" {"description" "Service healthy"
              "content"
              {"application/json"
               {"schema"
                {"type" "object"
                 "properties"
                 {"healthy?" {"type" "boolean"}
                  "name" {"type" "string"}
                  "version" {"type" "string"}}}}}}
       "503" {"description" "Service unhealthy"}}}}

    "/openapi.json"
    {"get"
     {"operationId" "getOpenApiSpec"
      "summary" "Get OpenAPI specification"
      "description" "Returns the OpenAPI 3.0 specification for this service"
      "responses"
      {"200" {"description" "OpenAPI specification"
              "content"
              {"application/json"
               {"schema" {"type" "object"}}}}}}}}

   "components"
   {"schemas"
    {"Error"
     {"type" "object"
      "properties"
      {"error" {"type" "string"
                "description" "Error message"}}}}}})

;;; Request Processing Helpers

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

;;; Response Builders

(defn- build-success-response
  "Build successful transcription response."
  [result]
  (let [trimmed-text (some-> (:text result) str/trim)]
    (log/info "Transcription result:" (pr-str trimmed-text))
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str {:text trimmed-text
                            :language (:language result)})}))

(defn- build-error-response
  "Build error response from exception."
  [^Exception exception]
  (log/error exception "Transcription failed")
  {:status 500
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {:error (.getMessage exception)})})

(defn- build-health-response
  "Build health check response from backend health-check result."
  [health-result backend-info]
  {:status (if (:healthy? health-result) 200 503)
   :headers {"Content-Type" "application/json"}
   :body (json/write-str (merge health-result backend-info))})

;;; Request Handlers

(defn- transcribe-handler
  "Ring handler for POST /transcribe endpoint.
   Expects multipart form data with an 'audio' field containing WAV bytes.
   
   backend - STTBackend instance
   module-cache - Backend's Python module cache (for Whisper audio processing)"
  [backend module-cache request]
  (try
    (let [audio-bytes (-> request
                          (get-in [:multipart-params "audio"])
                          log-audio-info
                          extract-bytes
                          log-audio-size
                          (doto validate-audio))
          ;; Convert WAV bytes to audio array using backend's modules
          audio-array (wav-bytes->audio-array module-cache audio-bytes)
          ;; Transcribe using the backend
          result (transcribe backend audio-array)]
      (build-success-response result))
    (catch Exception e
      (build-error-response e))))

(defn- health-handler
  "Health check endpoint.
   Returns backend health status and info."
  [backend _request]
  (let [health-result (health-check backend)
        backend-info (backend-info backend)]
    (build-health-response health-result backend-info)))

(defn- openapi-handler
  "Serve the OpenAPI specification."
  [_request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-str openapi-spec)})

(defn- not-found-handler
  "404 handler."
  [_request]
  {:status 404
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {:error "Not found"})})

;;; Routing

(defn- match-route
  "Match request to handler based on URI and method."
  [uri method]
  (cond
    (and (= uri "/transcribe") (= method :post)) :transcribe
    (and (= uri "/health") (= method :get)) :health
    (and (= uri "/openapi.json") (= method :get)) :openapi
    :else :not-found))

(defn- dispatch-handler
  "Dispatch to the appropriate handler based on route.
   
   backend - STTBackend instance
   module-cache - Backend's Python module cache
   route - Matched route keyword
   request - Ring request map"
  [backend module-cache route request]
  (case route
    :transcribe (transcribe-handler backend module-cache request)
    :health (health-handler backend request)
    :openapi (openapi-handler request)
    :not-found (not-found-handler request)))

;;; Public API

(defn create-app
  "Create a Ring handler for the given STT backend.
   
   backend - Implementation of STTBackend protocol
   module-cache - Backend's Python module cache (for audio processing)
   
   Returns a Ring handler function that routes requests to appropriate handlers.
   
   Example:
   (def backend (whisper/create-backend {:model-size \"small\"}))
   (initialize! backend)
   (def app (create-app backend @(.module-cache-atom backend)))
   
   Routes:
   - POST /transcribe - Transcribe audio to text
   - GET /health - Health check"
  [backend module-cache]
  (fn app [request]
    (let [uri (:uri request)
          method (:request-method request)
          route (match-route uri method)]
      (dispatch-handler backend module-cache route request))))
