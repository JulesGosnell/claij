(ns claij.integration.llm-test
  "Feature-based LLM capability testing.
   
   PURPOSE: Verify all supported LLMs work correctly with CLAIJ by making
   minimal API calls while testing maximum functionality.
   
   DESIGN:
   - Features define request transformations and response validations
   - Capability matrix maps LLMs to their supported features
   - One test function runs per LLM, testing all applicable features
   - Two API calls per LLM: text→tool and tool-result→text
   
   Adding a new LLM:
   1. Add entry to `llm-capabilities` with supported features
   2. Run tests: clojure -M:test --focus claij.integration.llm-test
   
   Adding a new feature:
   1. Add feature def to `features` with :call-1 and/or :call-2 fns
   2. Update capability matrix for LLMs that support it"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [cheshire.core :as json]
   [claij.llm :as llm]
   [claij.model :as model]))

;;==============================================================================
;; Environment
;;==============================================================================

(defn env-key-set?
  "Check if an environment variable is set (in env or .env file)."
  [key]
  (or (System/getenv key)
      (try
        (let [content (slurp ".env")]
          (some #(str/starts-with? % (str "export " key "="))
                (str/split-lines content)))
        (catch Exception _ false))))

(defn llm-available?
  "Check if an LLM is available (env key set or local service)."
  [[service _model env-key]]
  (or (nil? env-key) ;; Local service (ollama)
      (env-key-set? env-key)))

;;==============================================================================
;; Supported LLMs Registry
;;==============================================================================

(def supported-llms
  "LLMs that CLAIJ officially supports.
   Format: [service model env-key-required]"
  [["anthropic" (model/direct-model :anthropic) "ANTHROPIC_API_KEY"]
   ["google" (model/direct-model :google) "GOOGLE_API_KEY"]
   ["openrouter" (model/openrouter-model :openai) "OPENROUTER_API_KEY"]
   ["xai" (model/direct-model :xai) "XAI_API_KEY"]
   ["ollama:local" (model/ollama-model :light) nil]])

;;==============================================================================
;; Tool Definition (used across all tests)
;;==============================================================================

(def calculator-tool
  "Simple calculator tool for testing tool calling."
  {"type" "function"
   "function" {"name" "calculate"
               "description" "Perform arithmetic. Call this to compute math."
               "parameters" {"type" "object"
                             "properties" {"operation" {"type" "string"
                                                        "enum" ["add" "subtract" "multiply" "divide"]}
                                           "a" {"type" "number"}
                                           "b" {"type" "number"}}
                             "required" ["operation" "a" "b"]}}})

(defn stub-tool-execution
  "Execute tool call with stubbed response. No real MCP involved."
  [tool-call]
  (let [args (get tool-call "arguments")
        op (get args "operation")
        a (get args "a")
        b (get args "b")]
    (case op
      "add" (+ a b)
      "subtract" (- a b)
      "multiply" (* a b)
      "divide" (/ a b)
      (throw (ex-info "Unknown operation" {:op op})))))

;;==============================================================================
;; Synchronous LLM Call Wrapper
;;==============================================================================

(defn call-llm-sync
  "Synchronous wrapper for llm/call. Returns response map or error map."
  [service model messages & [{:keys [tools timeout-ms]
                              :or {timeout-ms 60000}}]]
  (let [result (promise)]
    (llm/call service model messages
              (fn [r] (deliver result {:ok r}))
              (cond-> {:error (fn [e] (deliver result {:error e}))}
                tools (assoc :tools tools)))
    (let [r (deref result timeout-ms {:error {"timeout" true}})]
      (if (:ok r)
        (:ok r)
        {:llm-error (:error r)}))))

;;==============================================================================
;; Feature Definitions
;;==============================================================================

(def features
  "Feature definitions with request transformation and response validation.
   Each feature has :doc, and optionally :call-1 and :call-2 maps with
   :request-fn and :validate-fn."

  {:base-tool-call
   {:doc "Basic text→tool→text roundtrip"
    :call-1 {:request-fn
             (fn [_service _model _prev-messages]
               [{"role" "user"
                 "content" "What is 25 + 17? Use the calculate tool to compute this."}])
             :validate-fn
             (fn [resp]
               (let [tool-calls (get resp "tool_calls")]
                 (and (seq tool-calls)
                      (= "calculate" (get (first tool-calls) "name")))))}
    :call-2 {:request-fn
             (fn [service model prev-messages resp-1 tool-result]
               ;; Build conversation with tool result
               ;; Different services have different formats for tool results
               (let [tool-call (first (get resp-1 "tool_calls"))
                     tool-id (get tool-call "id")
                     tool-name (get tool-call "name")]
                 (cond
                   ;; Anthropic format
                   (= service "anthropic")
                   (conj (vec prev-messages)
                         {"role" "assistant"
                          "content" [{"type" "tool_use"
                                      "id" tool-id
                                      "name" tool-name
                                      "input" (get tool-call "arguments")}]}
                         {"role" "user"
                          "content" [{"type" "tool_result"
                                      "tool_use_id" tool-id
                                      "content" (str tool-result)}]})

                   ;; Google format
                   (= service "google")
                   (conj (vec prev-messages)
                         {"role" "assistant"
                          "parts" [{"functionCall"
                                    {"name" tool-name
                                     "args" (get tool-call "arguments")}}]}
                         {"role" "user"
                          "parts" [{"functionResponse"
                                    {"name" tool-name
                                     "response" {"result" (str tool-result)}}}]})

                   ;; OpenAI-compatible format (OpenRouter, xAI, Ollama)
                   :else
                   (conj (vec prev-messages)
                         {"role" "assistant"
                          "tool_calls" [{"id" tool-id
                                         "type" "function"
                                         "function" {"name" tool-name
                                                     "arguments" (json/generate-string
                                                                  (get tool-call "arguments"))}}]}
                         {"role" "tool"
                          "tool_call_id" tool-id
                          "name" tool-name
                          "content" (str tool-result)}))))
             :validate-fn
             (fn [resp]
               ;; Response should contain "42" (25 + 17)
               ;; Check common response field names
               (let [resp-str (pr-str resp)]
                 (str/includes? resp-str "42")))}}

   :content-xor-tools
   {:doc "Response has content OR tools, never both"
    :call-1 {:validate-fn
             (fn [resp]
               (let [has-tools (seq (get resp "tool_calls"))]
                 ;; When we ask for a tool call, should have tools
                 has-tools))}
    :call-2 {:validate-fn
             (fn [resp]
               (let [has-tools (seq (get resp "tool_calls"))]
                 ;; After tool result, should NOT have tools (either content or structured response)
                 (not has-tools)))}}})

;;==============================================================================
;; Capability Matrix
;;==============================================================================

(def llm-capabilities
  "Maps [service model] to set of supported features.
   Start minimal, add features as verified."
  {["anthropic" (model/direct-model :anthropic)] #{:base-tool-call :content-xor-tools}
   ["google" (model/direct-model :google)] #{:base-tool-call :content-xor-tools}
   ["openrouter" (model/openrouter-model :openai)] #{:base-tool-call :content-xor-tools}
   ["xai" (model/direct-model :xai)] #{:base-tool-call :content-xor-tools}
   ["ollama:local" (model/ollama-model :light)] #{:base-tool-call}})

;;==============================================================================
;; Test Runner
;;==============================================================================

(defn run-feature-tests
  "Run all applicable feature tests for an LLM.
   Makes exactly 2 API calls, validates all features from those responses."
  [[service model _env-key]]
  (let [enabled-features (get llm-capabilities [service model])
        feature-defs (keep (fn [[k v]] (when (enabled-features k) v)) features)]

    (testing (str "LLM: " service "/" model)
      ;; Phase 1: Build and send first request
      (let [messages-1 ((:request-fn (:call-1 (:base-tool-call features)))
                        service model nil)

            _ (log/info "Call 1:" service model)
            resp-1 (call-llm-sync service model messages-1 {:tools [calculator-tool]})]

        ;; Check for LLM errors
        (if (:llm-error resp-1)
          (is false (str "LLM error: " (pr-str (:llm-error resp-1))))

          (do
            (testing "call-1: text→tool"
              ;; Run all :call-1 validators
              (doseq [f feature-defs
                      :let [validate (get-in f [:call-1 :validate-fn])]
                      :when validate]
                (testing (:doc f)
                  (is (validate resp-1)
                      (str "Failed: " (:doc f) "\nResponse: " (pr-str resp-1))))))

            ;; Phase 2: Execute tool and send result
            (when-let [tool-call (first (get resp-1 "tool_calls"))]
              (let [tool-result (stub-tool-execution tool-call)

                    ;; Build call-2 messages
                    messages-2 ((:request-fn (:call-2 (:base-tool-call features)))
                                service model messages-1 resp-1 tool-result)

                    _ (log/info "Call 2:" service model "tool-result:" tool-result)
                    resp-2 (call-llm-sync service model messages-2 {:tools [calculator-tool]})]

                ;; Check for LLM errors
                (if (:llm-error resp-2)
                  (is false (str "LLM error (call 2): " (pr-str (:llm-error resp-2))))

                  (testing "call-2: tool-result→text"
                    ;; Run all :call-2 validators
                    (doseq [f feature-defs
                            :let [validate (get-in f [:call-2 :validate-fn])]
                            :when validate]
                      (testing (:doc f)
                        (is (validate resp-2)
                            (str "Failed: " (:doc f) "\nResponse: " (pr-str resp-2)))))))))))))))

;;==============================================================================
;; Integration Test
;;==============================================================================

(deftest ^:integration llm-capability-test
  (doseq [llm supported-llms]
    (if (llm-available? llm)
      (run-feature-tests llm)
      (is false (str (first llm) " unavailable - " (nth llm 2) " not set")))))

;;==============================================================================
;; REPL Helpers
;;==============================================================================

(comment
  ;; Run single LLM test
  (run-feature-tests ["anthropic" (model/direct-model :anthropic) "ANTHROPIC_API_KEY"])

  ;; Run ollama test (no API key needed)
  (run-feature-tests ["ollama:local" (model/ollama-model :light) nil])

  ;; Check which LLMs are available
  (filter llm-available? supported-llms)

  ;; Run full test
  (clojure.test/run-tests 'claij.integration.llm-test))
