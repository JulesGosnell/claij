(ns claij.llm.service
  "LLM service abstraction with strategy-based dispatch.
   
   Design:
   - Service registry maps service names to {:strategy, :url, :auth}
   - make-request dispatches on strategy, returns clj-http request map
   - parse-response dispatches on strategy, extracts content string
   
   Strategies:
   - openai-compat: Uses OpenAI's /v1/chat/completions protocol
                    (adopted by Ollama, OpenRouter, xAI, Together, etc.)
   - anthropic: Anthropic's native /v1/messages API
   - google: Google's native Gemini API
   
   Usage:
     (def registry {\"ollama:local\" {:strategy \"openai-compat\"
                                     :url \"http://localhost:11434/v1/chat/completions\"}})
     (call-llm registry \"ollama:local\" \"mistral:7b\" messages)"
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [cheshire.core :as json]
   [clj-http.client :as http]))

;;------------------------------------------------------------------------------
;; Auth Resolution
;;------------------------------------------------------------------------------

(defn get-env
  "Get environment variable. Wrapper for testability."
  [var-name]
  (System/getenv var-name))

(defn resolve-auth
  "Resolve auth configuration to HTTP headers.
   
   Auth types:
   - nil                              -> no auth headers
   - {:type :bearer :env \"VAR\"}     -> Authorization: Bearer <token>
   - {:type :x-api-key :env \"VAR\"}  -> x-api-key: <token>
   - {:type :x-goog-api-key :env \"VAR\"} -> x-goog-api-key: <token>
   
   Returns map of headers to merge, or nil."
  [auth]
  (when auth
    (let [token (get-env (:env auth))]
      (case (:type auth)
        :bearer {"Authorization" (str "Bearer " token)}
        :x-api-key {"x-api-key" token}
        :x-goog-api-key {"x-goog-api-key" token}
        {}))))

;;------------------------------------------------------------------------------
;; Request Building - Dispatch on Strategy
;;------------------------------------------------------------------------------

(defmulti make-request
  "Build a clj-http request map for the given strategy.
   
   Args:
     strategy - \"openai-compat\", \"anthropic\", or \"google\"
     url      - API endpoint URL
     auth     - Auth config map or nil
     model    - Model name (native to service)
     messages - Vector of message maps [{\"role\" \"user\" \"content\" \"...\"}]
     options  - Strategy-specific options (e.g., {:num_ctx 32768} for Ollama)
   
   Returns:
     {:url     string
      :headers map
      :body    string (JSON encoded)}"
  (fn [strategy _url _auth _model _messages _options] strategy))

(defmethod make-request "openai-compat"
  [_strategy url auth model messages options]
  {:url url
   :headers (merge {"Content-Type" "application/json"}
                   (resolve-auth auth))
   :body (json/generate-string (merge {:model model
                                       :messages messages}
                                      options))})

(defmethod make-request "anthropic"
  [_strategy url auth model messages _options]
  (let [system-msgs (filter #(= "system" (get % "role")) messages)
        system-text (when (seq system-msgs)
                      (str/join "\n\n" (map #(get % "content") system-msgs)))
        non-system (vec (remove #(= "system" (get % "role")) messages))]
    {:url url
     :headers (merge {"Content-Type" "application/json"
                      "anthropic-version" "2023-06-01"}
                     (resolve-auth auth))
     :body (json/generate-string
            (cond-> {:model model
                     :max_tokens 4096
                     :messages non-system}
              system-text (assoc :system system-text)))}))

(defmethod make-request "google"
  [_strategy url auth model messages _options]
  (let [full-url (str url "/" model ":generateContent")
        system-msgs (filter #(= "system" (get % "role")) messages)
        system-text (when (seq system-msgs)
                      (str/join "\n\n" (map #(get % "content") system-msgs)))
        non-system (remove #(= "system" (get % "role")) messages)
        contents (mapv (fn [{:strs [role content]}]
                         {:role (if (= role "assistant") "model" role)
                          :parts [{:text content}]})
                       non-system)]
    {:url full-url
     :headers (merge {"Content-Type" "application/json"}
                     (resolve-auth auth))
     :body (json/generate-string
            (cond-> {:contents contents}
              system-text (assoc :systemInstruction {:parts [{:text system-text}]})))}))

;;------------------------------------------------------------------------------
;; Response Parsing - Dispatch on Strategy
;;------------------------------------------------------------------------------

(defmulti parse-response
  "Extract content string from provider response.
   
   Args:
     strategy - \"openai-compat\", \"anthropic\", or \"google\"
     response - Parsed JSON response body
   
   Returns:
     Content string"
  (fn [strategy _response] strategy))

(defmethod parse-response "openai-compat"
  [_strategy response]
  (get-in response [:choices 0 :message :content]))

(defmethod parse-response "anthropic"
  [_strategy response]
  (get-in response [:content 0 :text]))

(defmethod parse-response "google"
  [_strategy response]
  (get-in response [:candidates 0 :content :parts 0 :text]))

;;------------------------------------------------------------------------------
;; Service Registry
;;------------------------------------------------------------------------------

(defn lookup-service
  "Look up service configuration from registry.
   
   Args:
     registry - Map of service-name -> {:strategy :url :auth}
     service  - Service name string
   
   Returns:
     Service config map
   
   Throws:
     ExceptionInfo if service not found"
  [registry service]
  (or (get registry service)
      (throw (ex-info (str "Unknown service: " service)
                      {:service service
                       :available (keys registry)}))))

(defn build-request-from-registry
  "Build request from service registry lookup.
   
   Args:
     registry - Service registry
     service  - Service name (e.g., \"ollama:local\", \"anthropic\")
     model    - Model name (native to service)
     messages - Vector of message maps
   
   Returns:
     clj-http request map"
  [registry service model messages]
  (let [{:keys [strategy url auth options]} (lookup-service registry service)]
    (make-request strategy url auth model messages options)))

;;------------------------------------------------------------------------------
;; HTTP Execution
;;------------------------------------------------------------------------------

(defn call-llm-sync
  "Make a synchronous HTTP call to an LLM service.
   
   Args:
     registry - Service registry
     service  - Service name
     model    - Model name (native to service)
     messages - Vector of message maps
   
   Returns:
     Content string from response"
  [registry service model messages]
  (let [{:keys [strategy]} (lookup-service registry service)
        req (build-request-from-registry registry service model messages)]
    (log/info (str "LLM Call: " service "/" model))
    (let [response (http/post (:url req)
                              {:headers (:headers req)
                               :body (:body req)
                               :as :json})]
      (parse-response strategy (:body response)))))

(defn call-llm-async
  "Make an asynchronous HTTP call to an LLM service.
   
   Args:
     registry - Service registry
     service  - Service name
     model    - Model name (native to service)
     messages - Vector of message maps
     handler  - Success callback (fn [content-string])
     error-fn - Error callback (fn [error-info]) - optional
   
   Returns:
     nil (result delivered via callbacks)"
  [registry service model messages handler & [{:keys [error]}]]
  (let [{:keys [strategy]} (lookup-service registry service)
        req (build-request-from-registry registry service model messages)]
    (log/info (str "LLM Call (async): " service "/" model))
    (http/post
     (:url req)
     {:async? true
      :headers (:headers req)
      :body (:body req)}
     (fn [response]
       (try
         (let [parsed (json/parse-string (:body response) true)
               content (parse-response strategy parsed)]
           (handler content))
         (catch Exception e
           (log/error e "Error parsing LLM response")
           (when error (error {:error "parse-error" :exception e})))))
     (fn [exception]
       (log/error exception "LLM request failed")
       (when error
         (error {:error "request-failed" :exception exception}))))))

;;------------------------------------------------------------------------------
;; Default Registry
;;------------------------------------------------------------------------------

(def default-registry
  "Default service registry. Can be overridden in context.
   
   Environment variables:
   - OLLAMA_HOST: Ollama server host (default: localhost)
   - OLLAMA_PORT: Ollama server port (default: 11434)"
  (let [ollama-host (or (get-env "OLLAMA_HOST") "localhost")
        ollama-port (or (get-env "OLLAMA_PORT") "11434")]
    {"ollama:local" {:strategy "openai-compat"
                     :url (str "http://" ollama-host ":" ollama-port "/v1/chat/completions")
                     :auth nil
                     :options {:num_ctx 32768}} ;; Ollama context window (default 4096 too small)

     "openrouter" {:strategy "openai-compat"
                   :url "https://openrouter.ai/api/v1/chat/completions"
                   :auth {:type :bearer :env "OPENROUTER_API_KEY"}
                   :options {:max_tokens 4096}}

     "anthropic" {:strategy "anthropic"
                  :url "https://api.anthropic.com/v1/messages"
                  :auth {:type :x-api-key :env "ANTHROPIC_API_KEY"}}

     "google" {:strategy "google"
               :url "https://generativelanguage.googleapis.com/v1beta/models"
               :auth {:type :x-goog-api-key :env "GOOGLE_API_KEY"}}

     "xai" {:strategy "openai-compat"
            :url "https://api.x.ai/v1/chat/completions"
            :auth {:type :bearer :env "XAI_API_KEY"}}

     "openai" {:strategy "openai-compat"
               :url "https://api.openai.com/v1/chat/completions"
               :auth {:type :bearer :env "OPENAI_API_KEY"}}}))
