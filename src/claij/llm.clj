(ns claij.llm
  "Unified LLM client with service-based dispatch and async HTTP.
   
   Uses strategy-based dispatch via claij.llm.service:
   - openai-compat: Ollama, OpenRouter, xAI, OpenAI
   - anthropic: Anthropic direct API
   - google: Google/Gemini direct API
   
   Service registry maps service names to {:strategy :url :auth}.
   Registry can be passed explicitly or defaults to claij.llm.service/default-registry.
   
   Features:
   - Async HTTP with callbacks
   - EDN response parsing with markdown stripping
   - Automatic retry on parse errors with LLM feedback"
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [clojure.edn :as edn]
   [clj-http.client :as http]
   [cheshire.core :as json]
   [claij.util :refer [make-retrier]]
   [claij.llm.service :as svc]))

;;------------------------------------------------------------------------------
;; Response Processing
;;------------------------------------------------------------------------------

(defn strip-md-json
  "Strip markdown code fences from LLM response.
   LLMs often wrap JSON/EDN in ```json ... ``` blocks."
  [s]
  (-> s
      (str/replace #"^```(?:json|edn|clojure)?\s*" "")
      (str/replace #"\s*```$" "")))

;;------------------------------------------------------------------------------
;; Async LLM Call with EDN Parsing and Retry
;;------------------------------------------------------------------------------

(defn call
  "Call LLM API asynchronously with EDN parsing and retry logic.
   
   Uses claij.llm.service for HTTP layer with strategy-based dispatch.
   Automatically retries on EDN parse errors with feedback to the LLM.
  
   Args:
     service  - Service name (e.g., 'anthropic', 'ollama:local', 'openrouter')
     model    - Model name native to service (e.g., 'claude-sonnet-4-20250514', 'mistral:7b')
     prompts  - Vector of message maps with 'role' and 'content' keys
     handler  - Function to call with successful parsed EDN response
   
   Options:
     :registry    - Service registry (default: svc/default-registry)
     :schema      - Malli schema for response validation (currently unused)
     :error       - Function to call on error
     :retry-count - (Internal) Current retry attempt number
     :max-retries - Maximum number of retries for malformed EDN (default: 3)"
  [service model prompts handler & [{:keys [registry schema error retry-count max-retries]
                                     :or {retry-count 0 max-retries 3}}]]
  (let [registry (or registry svc/default-registry)]
    (log/info (str "      LLM Call: " service "/" model
                   (when (> retry-count 0) (str " (retry " retry-count "/" max-retries ")"))))

    ;; Build request via service abstraction
    (let [{:keys [strategy]} (svc/lookup-service registry service)
          req (svc/build-request-from-registry registry service model prompts)]
      (http/post
       (:url req)
       {:async? true
        :headers (:headers req)
        :body (:body req)}
       (fn [response]
         (try
           ;; Parse JSON response and extract content via strategy
           (let [parsed-response (json/parse-string (:body response) true)
                 raw-content (svc/parse-response strategy parsed-response)]
             (if (nil? raw-content)
               ;; Handle nil content - likely an error response from the provider
               (do
                 (log/error (str "      [X] LLM returned nil content. Full response: "
                                 (pr-str parsed-response)))
                 (when error
                   (error {:error "nil-content"
                           :message "LLM service returned nil content"
                           :raw-response parsed-response})))
               ;; Normal flow - process the content
               (let [d (strip-md-json raw-content)]
                 (try
                   (let [j (edn/read-string (str/trim d))]
                     (log/info "      [OK] LLM Response: Valid EDN received")
                     (handler j))
                   (catch Exception e
                     (let [retrier (make-retrier max-retries)]
                       (retrier
                        retry-count
                        ;; Retry operation: send error feedback and try again
                        (fn []
                          (let [error-msg (str "We could not unmarshal your EDN - it must be badly formed.\n\n"
                                               "Here is the exception:\n"
                                               (.getMessage e) "\n\n"
                                               "Here is your malformed response:\n" d "\n\n"
                                               "Please try again. Your response should only contain the relevant EDN document.")
                                retry-prompts (conj (vec prompts) {"role" "user" "content" error-msg})]
                            (log/warn (str "      [X] EDN Parse Error: " (.getMessage e)))
                            (log/info (str "      [>>] Sending error feedback to LLM"))
                            (call service model retry-prompts handler
                                  {:registry registry
                                   :schema schema
                                   :error error
                                   :retry-count (inc retry-count)
                                   :max-retries max-retries})))
                        ;; Max retries handler
                        (fn []
                          (log/debug (str "Final malformed response: " d))
                          (when error (error {:error "max-retries-exceeded"
                                              :raw-response d
                                              :exception (.getMessage e)}))))))))))
           (catch Throwable t
             (log/error t "Error processing LLM response"))))
       (fn [exception]
         (try
           (let [data (.getData exception)
                 body (when data (:body data))
                 m (when body (json/parse-string body true))]
             (log/error (str "      [X] LLM Request Failed: " (or (get m :error) exception)))
             (when error (error (or m {:error "request-failed" :exception exception}))))
           (catch Throwable t
             (log/error t "Error handling LLM failure"))))))))


