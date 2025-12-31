(ns claij.integration.llm-test
  "Feature-based LLM capability testing.
   
   Design principles:
   - One test function run against each supported LLM
   - Features compose: request transformers chain, validators accumulate
   - 2 LLM calls per provider: text→tool, tool-result→text
   - Maximum verification from minimum API calls
   - Capability matrix defines which LLMs support which features
   
   To add a new LLM:
   1. Add to llm-capabilities with supported features
   2. Run: clojure -M:test --focus claij.integration.llm-test
   
   To add a new feature:
   1. Define in `features` with :call-1/:call-2 request-fn/validate-fn
   2. Add to relevant LLMs in llm-capabilities"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [cheshire.core :as json]
   [claij.llm :as llm]
   [claij.schema :as schema]))

;;------------------------------------------------------------------------------
;; Environment / Availability

(defn env-key-set?
  "Check if environment variable is set and non-empty"
  [key]
  (let [v (System/getenv key)]
    (and v (not (str/blank? v)))))

(defn llm-available?
  "Check if an LLM is available (API key set or local service)"
  [[service _model env-key]]
  (or (nil? env-key) ;; Local service like Ollama
      (env-key-set? env-key)))

;;------------------------------------------------------------------------------
;; LLM Capability Matrix
;;
;; Maps [service model env-key] -> #{features}
;; This is the source of truth for what CLAIJ supports

(def llm-capabilities
  "LLMs that CLAIJ officially supports and their capabilities"
  {["anthropic" "claude-sonnet-4-5-20250514" "ANTHROPIC_API_KEY"]
   #{:base-tool-call}

   ["google" "gemini-2.0-flash" "GOOGLE_API_KEY"]
   #{:base-tool-call}

   ["openrouter" "openai/gpt-4o" "OPENROUTER_API_KEY"]
   #{:base-tool-call}

   ["xai" "grok-3-fast" "XAI_API_KEY"]
   #{:base-tool-call}

   ["ollama:local" "qwen3:8b" nil]
   #{:base-tool-call}})

;;------------------------------------------------------------------------------
;; Calculator Tool Definition (used by all features)

(def calculator-tool
  "MCP-style tool definition for testing"
  {"name" "calculator"
   "description" "Performs arithmetic operations"
   "inputSchema" {"type" "object"
                  "properties" {"op" {"type" "string" "enum" ["add" "multiply"]}
                                "a" {"type" "number"}
                                "b" {"type" "number"}}
                  "required" ["op" "a" "b"]}})

;;------------------------------------------------------------------------------
;; Schemas for validation

(def ToolCallSchema
  "Schema for a single tool call"
  {:type "object"
   :properties {"name" {:type "string"}
                "arguments" {:type "object"}}
   :required ["name" "arguments"]})

(def ToolCallsResponseSchema
  "Schema for response containing tool_calls array"
  {:type "object"
   :properties {"tool_calls" {:type "array"
                              :items ToolCallSchema
                              :minItems 1}}
   :required ["tool_calls"]})

;;------------------------------------------------------------------------------
;; Feature Definitions
;;
;; Each feature has:
;; - :doc - description for test output
;; - :call-1 - first LLM call (text → tool)
;;   - :request-fn (fn [base-messages] -> messages) - transform request
;;   - :validate-fn (fn [response] -> bool) - validate response
;; - :call-2 - second LLM call (tool-result → text)
;;   - :request-fn (fn [messages tool-result] -> messages) - add tool result
;;   - :validate-fn (fn [response] -> bool) - validate final response

(def call-1-prompt
  "You have access to an MCP tool service with the following tool:

%s

Using this tool, compute: 2 + 2

Respond ONLY with a JSON object containing your tool calls. No prose. Example format:
{\"tool_calls\": [{\"name\": \"calculator\", \"arguments\": {\"op\": \"add\", \"a\": 1, \"b\": 2}}]}")

(def features
  {:base-tool-call
   {:doc "Basic text→tool→text roundtrip"
    :call-1
    {:request-fn
     (fn [_base-messages]
       [{"role" "user"
         "content" (format call-1-prompt (json/generate-string calculator-tool))}])
     :validate-fn
     (fn [response]
       ;; claij.llm/call returns parsed JSON directly, e.g. {"tool_calls": [...]}
       (and (get response "tool_calls")
            (seq (get response "tool_calls"))
            (= "calculator" (get-in response ["tool_calls" 0 "name"]))
            (= "add" (get-in response ["tool_calls" 0 "arguments" "op"]))))}
    :call-2
    {:request-fn
     (fn [prev-messages prev-response tool-result]
       ;; prev-response is parsed JSON (e.g. {"tool_calls": [...]})
       ;; Serialize back to JSON for assistant message content
       (conj prev-messages
             {"role" "assistant" "content" (json/generate-string prev-response)}
             {"role" "user" "content" (str "Tool result: " (json/generate-string tool-result)
                                           "\n\nRespond with a JSON object: {\"answer\": \"<your final answer>\"}")}))
     :validate-fn
     (fn [response]
       ;; Response is parsed JSON with "answer" key (may be string or number)
       (let [answer (get response "answer")
             answer-str (str answer)]
         (and answer
              (str/includes? answer-str "4"))))}}})

;;------------------------------------------------------------------------------
;; Stub Tool Execution

(defn extract-tool-call
  "Extract first tool call from LLM response.
   Response is already parsed JSON from claij.llm/call."
  [response]
  (first (get response "tool_calls")))

(defn stub-tool-execution
  "Execute tool call with stub implementation (no MCP)"
  [tool-call]
  (when tool-call
    (let [args (get tool-call "arguments")
          op (get args "op")
          a (get args "a")
          b (get args "b")]
      (case op
        "add" {"result" (+ a b)}
        "multiply" {"result" (* a b)}
        {"error" "unknown operation"}))))

;;------------------------------------------------------------------------------
;; LLM Call Helper

(defn call-llm-sync!
  "Synchronous LLM call. Returns response map or nil on error (with test failure logged)."
  [service model messages]
  (let [result (promise)]
    (llm/call service model
              messages
              (fn [r] (deliver result {:ok r}))
              {:error (fn [e] (deliver result {:error e}))})
    (let [r (deref result 60000 {:error {:timeout true}})]
      (cond
        (:ok r) (:ok r)
        (:error r) (do
                     (is false (str "LLM call failed: " (pr-str (:error r))))
                     nil)
        :else (do
                (is false "LLM call timed out")
                nil)))))

;;------------------------------------------------------------------------------
;; Test Runner

(defn run-feature-test
  "Run capability test for a single LLM with its enabled features"
  [[service model _env-key :as llm-key] enabled-feature-keys]
  (testing (str "LLM: " service "/" model)
    (let [feature-defs (map features enabled-feature-keys)]
      ;; Build call-1 messages by composing all feature request-fns
      (let [call-1-messages (reduce
                             (fn [msgs f]
                               (if-let [xform (get-in f [:call-1 :request-fn])]
                                 (xform msgs)
                                 msgs))
                             []
                             feature-defs)]
        (testing "call-1: text→tool"
          (when-let [resp-1 (call-llm-sync! service model call-1-messages)]
            ;; Run all call-1 validators
            (doseq [f feature-defs
                    :let [validate (get-in f [:call-1 :validate-fn])]
                    :when validate]
              (testing (:doc f)
                (is (validate resp-1)
                    (str "Response: " (pr-str resp-1)))))

            ;; Extract and execute tool
            (let [tool-call (extract-tool-call resp-1)
                  tool-result (stub-tool-execution tool-call)]
              (testing "call-2: tool-result→text"
                ;; Build call-2 messages
                (let [call-2-messages (reduce
                                       (fn [msgs f]
                                         (if-let [xform (get-in f [:call-2 :request-fn])]
                                           (xform msgs resp-1 tool-result)
                                           msgs))
                                       call-1-messages
                                       feature-defs)]
                  (when-let [resp-2 (call-llm-sync! service model call-2-messages)]
                    ;; Run all call-2 validators
                    (doseq [f feature-defs
                            :let [validate (get-in f [:call-2 :validate-fn])]
                            :when validate]
                      (testing (:doc f)
                        (is (validate resp-2)
                            (str "Response: " (pr-str resp-2)))))))))))))))

;;------------------------------------------------------------------------------
;; Main Test

(deftest ^:integration llm-capability-test
  (doseq [[llm-key feature-keys] llm-capabilities]
    (if (llm-available? llm-key)
      (run-feature-test llm-key feature-keys)
      (let [[service model env-key] llm-key]
        (testing (str "LLM: " service "/" model)
          (is false (str service " unavailable - " env-key " not set")))))))
