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
   - JSON response parsing with markdown stripping
   - Automatic retry on parse errors with LLM feedback"
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
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
  "Call LLM API asynchronously with JSON parsing and retry logic.
   
   Uses claij.llm.service for HTTP layer with strategy-based dispatch.
   Automatically retries on JSON parse errors with feedback to the LLM.
  
   Args:
     service  - Service name (e.g., 'anthropic', 'ollama:local', 'openrouter')
     model    - Model name native to service (e.g., 'claude-sonnet-4-20250514', 'mistral:7b')
     prompts  - Vector of message maps with 'role' and 'content' keys
     handler  - Function to call with successful parsed JSON response
   
   Options:
     :registry    - Service registry (default: svc/default-registry)
     :schema      - JSON Schema for response validation (currently unused)
     :error       - Function to call on error
     :retry-count - (Internal) Current retry attempt number
     :max-retries - Maximum number of retries for malformed JSON (default: 3)"
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
                   ;; Parse response as JSON (use false to get string keys, not keywords)
                   (let [j (json/parse-string (str/trim d) false)]
                     ;; Check if we got a proper map vs something else
                     (if (map? j)
                       (do
                         (log/info "      [OK] LLM Response: Valid JSON map received")
                         (log/info "      [>>] Calling handler with:" (pr-str (get j "id")))
                         (try
                           (handler j)
                           (log/info "      [OK] Handler returned successfully")
                           (catch Throwable t
                             (log/error t "      [X] Handler threw exception"))))
                       ;; Not a map - treat as parse error and retry
                       (throw (ex-info "JSON parsed but is not a map"
                                       {:parsed j :raw d}))))
                   (catch Exception e
                     (let [retrier (make-retrier max-retries)]
                       (retrier
                        retry-count
                        ;; Retry operation: send error feedback and try again
                        (fn []
                          (let [error-msg (str "Your response is not valid JSON.\n\n"
                                               "ERROR: " (.getMessage e) "\n\n"
                                               "YOUR RESPONSE WAS:\n" d "\n\n"
                                               "REQUIRED: Output ONLY a JSON object starting with { and ending with }.\n"
                                               "Do NOT output prose, explanations, markdown, or text like 'I will...'.\n"
                                               "The object must have an \"id\" key with a valid transition.")
                                retry-prompts (conj (vec prompts) {"role" "user" "content" error-msg})]
                            (log/warn (str "      [X] JSON Parse Error: " (.getMessage e)))
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
         (let [data (ex-data exception)
               body (when data (:body data))
               m (when body
                   (try (json/parse-string body true)
                        (catch Exception _ nil)))]
           (log/error (str "      [X] LLM Request Failed: "
                           (or (:error m) (.getMessage exception))))
           (when error
             (error (or m {:error "request-failed"
                           :message (.getMessage exception)
                           :exception-type (type exception)})))))))))


