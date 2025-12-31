(ns claij.llm.service
  "LLM service abstraction with strategy-based dispatch.
   
   Design:
   - Service registry maps service names to {:strategy, :url, :auth}
   - make-request dispatches on strategy, returns clj-http request map
   - parse-response dispatches on strategy, extracts content and tool_calls
   
   Strategies:
   - openai-compat: Uses OpenAI's /v1/chat/completions protocol
                    (adopted by Ollama, OpenRouter, xAI, Together, etc.)
   - anthropic: Anthropic's native /v1/messages API
   - google: Google's native Gemini API
   
   Native Tool Calling:
   - Tools passed via :tools option to make-request
   - parse-response returns {:content ... :tool_calls ...}
   - Callers must handle either content OR tool_calls (XOR)
   
   Usage:
     (def registry {\"ollama:local\" {:strategy \"openai-compat\"
                                     :url \"http://localhost:11434/v1/chat/completions\"}})
     (call-llm registry \"ollama:local\" \"mistral:7b\" messages)"
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [claij.util :refer [strip-keys-recursive]]))

(def sanitize-schema-for-google
  "Remove JSON Schema fields that Google's native API rejects."
  (partial strip-keys-recursive #{"additionalProperties" "$schema"}))

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
     options  - Strategy-specific options including:
                :tools - Vector of tool definitions (OpenAI format)
                         [{:type \"function\" :function {:name ... :description ... :parameters ...}}]
                :num_ctx - Context window size (Ollama)
                :max_tokens - Max response tokens
   
   Returns:
     {:url     string
      :headers map
      :body    string (JSON encoded)}"
  (fn [strategy _url _auth _model _messages _options] strategy))

(defmethod make-request "openai-compat"
  [_strategy url auth model messages options]
  (let [{:keys [tools]} options
        ;; Remove :tools from options - it's handled separately in OpenAI format
        other-options (dissoc options :tools)]
    {:url url
     :headers (merge {"Content-Type" "application/json"}
                     (resolve-auth auth))
     :body (json/generate-string
            (cond-> (merge {:model model
                            :messages messages}
                           other-options)
              (seq tools) (assoc :tools tools)))}))

(defmethod make-request "anthropic"
  [_strategy url auth model messages options]
  (let [{:keys [tools]} options
        system-msgs (filter #(= "system" (get % "role")) messages)
        system-text (when (seq system-msgs)
                      (str/join "\n\n" (map #(get % "content") system-msgs)))
        non-system (vec (remove #(= "system" (get % "role")) messages))
        ;; Convert OpenAI tool format to Anthropic format
        ;; OpenAI: {"type" "function" "function" {"name" ... "description" ... "parameters" ...}}
        ;; Anthropic: {"name" ... "description" ... "input_schema" ...}
        anthropic-tools (when (seq tools)
                          (mapv (fn [tool]
                                  (let [function (get tool "function")]
                                    {"name" (get function "name")
                                     "description" (get function "description")
                                     "input_schema" (or (get function "parameters")
                                                        {"type" "object" "properties" {}})}))
                                tools))]
    {:url url
     :headers (merge {"Content-Type" "application/json"
                      "anthropic-version" "2023-06-01"}
                     (resolve-auth auth))
     :body (json/generate-string
            (cond-> {"model" model
                     "max_tokens" 4096
                     "messages" non-system}
              system-text (assoc "system" system-text)
              anthropic-tools (assoc "tools" anthropic-tools)))}))

(defmethod make-request "google"
  [_strategy url auth model messages options]
  (let [{:keys [tools]} options
        full-url (str url "/" model ":generateContent")
        system-msgs (filter #(= "system" (get % "role")) messages)
        system-text (when (seq system-msgs)
                      (str/join "\n\n" (map #(get % "content") system-msgs)))
        non-system (remove #(= "system" (get % "role")) messages)
        contents (mapv (fn [{:strs [role content]}]
                         {"role" (if (= role "assistant") "model" role)
                          "parts" [{"text" content}]})
                       non-system)
        ;; Convert OpenAI tool format to Google format
        ;; OpenAI: {"type" "function" "function" {"name" ... "description" ... "parameters" ...}}
        ;; Google: {"function_declarations" [{"name" ... "description" ... "parameters" ...}]}
        google-tools (when (seq tools)
                       [{"function_declarations"
                         (mapv (fn [tool]
                                 (let [function (get tool "function")
                                       params (or (get function "parameters")
                                                  {"type" "object" "properties" {}})]
                                   {"name" (get function "name")
                                    "description" (get function "description")
                                    "parameters" (sanitize-schema-for-google params)}))
                               tools)}])]
    {:url full-url
     :headers (merge {"Content-Type" "application/json"}
                     (resolve-auth auth))
     :body (json/generate-string
            (cond-> {"contents" contents}
              system-text (assoc "systemInstruction" {"parts" [{"text" system-text}]})
              google-tools (assoc "tools" google-tools)))}))

;;------------------------------------------------------------------------------
;; Response Parsing - Dispatch on Strategy
;;------------------------------------------------------------------------------

(defmulti parse-response
  "Extract content and tool_calls from provider response.
   
   Args:
     strategy - \"openai-compat\", \"anthropic\", or \"google\"
     response - Parsed JSON response body
   
   Returns:
     {:content    string or nil - text content from assistant
      :tool_calls vector or nil - tool calls in normalized format
                  [{:id ... :name ... :arguments ...}]}
   
   Note: Per XOR constraint, only one of content/tool_calls should be present."
  (fn [strategy _response] strategy))

(defmethod parse-response "openai-compat"
  [_strategy response]
  (let [message (get-in response ["choices" 0 "message"])
        content (get message "content")
        tool-calls (get message "tool_calls")
        ;; Normalize tool_calls to consistent format (string keys throughout)
        normalized-tools (when (seq tool-calls)
                           (mapv (fn [tc]
                                   (let [function (get tc "function")]
                                     {"id" (get tc "id")
                                      "name" (get function "name")
                                      "arguments" (let [args (get function "arguments")]
                                                    (if (string? args)
                                                      (json/parse-string args false)
                                                      args))}))
                                 tool-calls))]
    {"content" content
     "tool_calls" normalized-tools}))

(defmethod parse-response "anthropic"
  [_strategy response]
  (let [content-blocks (get response "content")
        ;; Extract text blocks
        text-blocks (filter #(= "text" (get % "type")) content-blocks)
        text-content (when (seq text-blocks)
                       (str/join " " (map #(get % "text") text-blocks)))
        ;; Extract tool_use blocks and normalize
        tool-uses (filter #(= "tool_use" (get % "type")) content-blocks)
        normalized-tools (when (seq tool-uses)
                           (mapv (fn [tu]
                                   {"id" (get tu "id")
                                    "name" (get tu "name")
                                    "arguments" (get tu "input")})
                                 tool-uses))]
    {"content" (when-not (str/blank? text-content) text-content)
     "tool_calls" normalized-tools}))

(defmethod parse-response "google"
  [_strategy response]
  (let [parts (get-in response ["candidates" 0 "content" "parts"])
        ;; Extract text parts
        text-parts (filter #(get % "text") parts)
        text-content (when (seq text-parts)
                       (str/join " " (map #(get % "text") text-parts)))
        ;; Extract functionCall parts and normalize
        function-calls (filter #(get % "functionCall") parts)
        normalized-tools (when (seq function-calls)
                           (mapv (fn [part]
                                   (let [fc (get part "functionCall")]
                                     {"id" (get fc "name") ;; Google doesn't use separate IDs
                                      "name" (get fc "name")
                                      "arguments" (get fc "args")}))
                                 function-calls))]
    {"content" (when-not (str/blank? text-content) text-content)
     "tool_calls" normalized-tools}))

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
     registry      - Service registry
     service       - Service name (e.g., \"ollama:local\", \"anthropic\")
     model         - Model name (native to service)
     messages      - Vector of message maps
     extra-options - Optional additional options (e.g., {:tools [...]})
                     Merged with registry options, extra-options take precedence.
   
   Returns:
     clj-http request map"
  ([registry service model messages]
   (build-request-from-registry registry service model messages nil))
  ([registry service model messages extra-options]
   (let [{:keys [strategy url auth options]} (lookup-service registry service)
         merged-options (merge options extra-options)]
     (make-request strategy url auth model messages merged-options))))

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
     options  - Optional extra options (e.g., {:tools [...]})
   
   Returns:
     {\"content\"    string or nil - text response
      \"tool_calls\" vector or nil - normalized tool calls}"
  ([registry service model messages]
   (call-llm-sync registry service model messages nil))
  ([registry service model messages options]
   (let [{:keys [strategy]} (lookup-service registry service)
         req (build-request-from-registry registry service model messages options)]
     (log/info (str "LLM Call: " service "/" model))
     (let [response (http/post (:url req)
                               {:headers (:headers req)
                                :body (:body req)})]
       (parse-response strategy (json/parse-string (:body response) false))))))

(defn call-llm-async
  "Make an asynchronous HTTP call to an LLM service.
   
   Args:
     registry - Service registry
     service  - Service name
     model    - Model name (native to service)
     messages - Vector of message maps
     handler  - Success callback (fn [{:keys [content tool_calls]}])
     opts     - Options map:
                :error - Error callback (fn [error-info])
                :tools - Vector of tool definitions
   
   Returns:
     nil (result delivered via callbacks)"
  [registry service model messages handler & [{:keys [error tools]}]]
  (let [{:keys [strategy]} (lookup-service registry service)
        extra-options (when tools {:tools tools})
        req (build-request-from-registry registry service model messages extra-options)]
    (log/info (str "LLM Call (async): " service "/" model))
    (http/post
     (:url req)
     {:async? true
      :headers (:headers req)
      :body (:body req)}
     (fn [response]
       (try
         (let [parsed (json/parse-string (:body response) false)
               result (parse-response strategy parsed)]
           (handler result))
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
