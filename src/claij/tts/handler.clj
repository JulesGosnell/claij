(ns claij.tts.handler
  "Generic Ring HTTP handlers for TTS services.
   
   Provides HTTP endpoints for any TTS backend:
   - POST /synthesize: Convert text to speech
   - GET /health: Health check endpoint
   
   This namespace is backend-agnostic - it works with any implementation of
   the TTSBackend protocol.
   
   Usage:
   (def backend (piper/create-backend {:voice-path \"/path/to/model.onnx\"}))
   (def app-handler (create-app backend))
   
   Public API:
   - create-app: Create Ring handler for a specific backend"
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [claij.tts.core :refer [synthesize health-check backend-info]]))

;;; OpenAPI Specification

(def openapi-spec
  "OpenAPI 3.0 specification for the TTS service."
  {"openapi" "3.0.0"
   "info" {"title" "CLAIJ TTS Service"
           "description" "Text-to-Speech service using Piper"
           "version" "1.0.0"}
   "paths"
   {"/synthesize"
    {"post"
     {"operationId" "synthesize"
      "summary" "Convert text to speech"
      "description" "Accepts text and returns WAV audio"
      "requestBody"
      {"required" true
       "content"
       {"application/json"
        {"schema"
         {"type" "object"
          "required" ["text"]
          "properties"
          {"text" {"type" "string"
                   "description" "Text to synthesize"}}}}
        "text/plain"
        {"schema"
         {"type" "string"
          "description" "Plain text to synthesize"}}}}
      "responses"
      {"200" {"description" "Successful synthesis"
              "content"
              {"audio/wav"
               {"schema"
                {"type" "string"
                 "format" "binary"
                 "description" "WAV audio bytes"}}}}
       "500" {"description" "Synthesis failed"
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

;;; Response Builders

(defn- build-success-response
  "Build successful synthesis response with WAV audio."
  [result]
  {:status 200
   :headers {"Content-Type" "audio/wav"}
   :body (:audio-bytes result)})

(defn- build-error-response
  "Build error response from exception."
  [^Exception exception]
  (log/error exception "Synthesis failed")
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

(defn- extract-text
  "Extract text from request body.
   Accepts JSON with {\"text\": \"...\"} or plain text."
  [request]
  (let [body (:body request)
        ^String content-type (get-in request [:headers "content-type"] "")]
    (cond
      ;; JSON body
      (.contains content-type "application/json")
      (let [json-data (json/read-str (slurp body))]
        (get json-data "text"))

      ;; Plain text body
      :else
      (slurp body))))

(defn- synthesize-handler
  "Ring handler for POST /synthesize endpoint.
   Expects text in request body (JSON or plain text)."
  [backend request]
  (try
    (let [text (extract-text request)]
      (when (or (nil? text) (empty? text))
        (throw (ex-info "Text is required" {:type :missing-text})))
      (log/info "Synthesizing" (count text) "characters")
      (let [result (synthesize backend text)]
        (build-success-response result)))
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
    (and (= uri "/synthesize") (= method :post)) :synthesize
    (and (= uri "/health") (= method :get)) :health
    (and (= uri "/openapi.json") (= method :get)) :openapi
    :else :not-found))

(defn- dispatch-handler
  "Dispatch to the appropriate handler based on route.
   
   backend - TTSBackend instance
   route - Matched route keyword
   request - Ring request map"
  [backend route request]
  (case route
    :synthesize (synthesize-handler backend request)
    :health (health-handler backend request)
    :openapi (openapi-handler request)
    :not-found (not-found-handler request)))

;;; Public API

(defn create-app
  "Create a Ring handler for the given TTS backend.
   
   backend - Implementation of TTSBackend protocol
   
   Returns a Ring handler function that routes requests to appropriate handlers.
   
   Example:
   (def backend (piper/create-backend {:voice-path \"/path/to/model.onnx\"}))
   (initialize! backend)
   (def app (create-app backend))
   
   Routes:
   - POST /synthesize - Convert text to speech (returns WAV audio)
   - GET /health - Health check"
  [backend]
  (fn app [request]
    (let [uri (:uri request)
          method (:request-method request)
          route (match-route uri method)]
      (dispatch-handler backend route request))))
