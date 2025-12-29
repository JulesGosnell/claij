(ns claij.poc.tool-calling-poc-test
  "LLM Compatibility Test: Schema-in-prompt → structured tool calls.
   
   PURPOSE: Validates that LLMs understand CLAIJ's core pattern:
   - Parse JSON Schema tool definitions from prompt text
   - Emit properly structured JSON tool_calls
   - Produce output that validates against JSON Schema
   
   This demonstrates LLMs have 'muscle memory' for tool calling -
   no native tool API needed, just schema in prompt. This is the
   foundational validation of CLAIJ's schema-guided FSM architecture.
   
   GOING FORWARD: Use this test to validate new LLMs before adding
   them to CLAIJ. When evaluating a new model or service:
   1. Add a test case with the new service/model
   2. Run: clojure -M:test --focus claij.poc.tool-calling-poc-test
   3. If it passes, the model understands schema-in-prompt
   
   This is especially useful for validating:
   - New Ollama models (local inference)
   - New cloud provider models
   - Fine-tuned models
   - Smaller/faster models for cost optimization
   
   PREREQUISITES:
   - Ollama must be running for ollama tests
   - Cloud provider tests require respective API keys
   
   Current validated services:
   - anthropic / claude-sonnet-4-5
   - google / gemini-3-flash-preview
   - openrouter / openai/gpt-5.2
   - xai / grok-code-fast-1
   - ollama:local / mistral:7b, qwen2.5-coder:14b"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [claij.model :as model]
   [claij.schema :as schema]
   [claij.llm :as llm]))

;;------------------------------------------------------------------------------
;; JSON Schema for Tool Calls
;;------------------------------------------------------------------------------

(def CalculatorArguments
  "Schema for calculator tool arguments"
  {"type" "object"
   "required" ["op" "a" "b"]
   "properties" {"op" {"enum" ["add" "multiply"]}
                 "a" {"type" "number"}
                 "b" {"type" "number"}}})

(def ToolCall
  "Schema for a single tool call"
  {"type" "object"
   "required" ["id" "name" "arguments"]
   "properties" {"id" {"type" "string"}
                 "name" {"type" "string"}
                 "arguments" CalculatorArguments}})

(def ToolCallResponse
  "Schema for LLM response containing tool calls"
  {"type" "object"
   "required" ["tool_calls"]
   "properties" {"tool_calls" {"type" "array"
                               "items" ToolCall}}})

;;------------------------------------------------------------------------------
;; Service Availability Checks
;;------------------------------------------------------------------------------

(defn env-key-set?
  "Check if an environment variable is set and non-empty"
  [key]
  (not (str/blank? (System/getenv key))))

(defn google-available? [] (env-key-set? "GOOGLE_API_KEY"))
(defn anthropic-available? [] (env-key-set? "ANTHROPIC_API_KEY"))
(defn openrouter-available? [] (env-key-set? "OPENROUTER_API_KEY"))
(defn xai-available? [] (env-key-set? "XAI_API_KEY"))

(defmacro when-service-available
  "Run test body only if service is available, otherwise log warning and skip"
  [available-fn service-name env-var & body]
  `(if (~available-fn)
     (do ~@body)
     (do
       (println (str "\n⚠️  SKIPPING: " ~service-name " - " ~env-var " not set"))
       (is true "Skipped - API key not configured"))))

;;------------------------------------------------------------------------------
;; Test Prompt
;;------------------------------------------------------------------------------

(def tool-prompt
  "You have access to an MCP tool service with the following tool:

{
  \"name\": \"calculator\",
  \"description\": \"Performs arithmetic operations\",
  \"inputSchema\": {
    \"type\": \"object\",
    \"properties\": {
      \"op\": {\"type\": \"string\", \"enum\": [\"add\", \"multiply\"]},
      \"a\": {\"type\": \"number\"},
      \"b\": {\"type\": \"number\"}
    },
    \"required\": [\"op\", \"a\", \"b\"]
  }
}

Using this MCP tool, compute:
1. 42 + 17
2. 6 * 7
3. 100 + 23

Respond ONLY with a JSON object containing your tool calls. No prose. Example format:
{\"tool_calls\": [{\"id\": \"call_1\", \"name\": \"calculator\", \"arguments\": {\"op\": \"add\", \"a\": 1, \"b\": 2}}]}")

;;------------------------------------------------------------------------------
;; Helpers
;;------------------------------------------------------------------------------

(defn call-llm-sync
  "Synchronous wrapper for llm/call. Returns result or throws on error."
  [service model]
  (let [result (promise)]
    (llm/call service model
              [{"role" "user" "content" tool-prompt}]
              (fn [r] (deliver result {:ok r}))
              {:error (fn [e] (deliver result {:error e}))})
    (let [r (deref result 60000 {:error {:timeout true}})]
      (if (:ok r)
        (:ok r)
        (throw (ex-info "LLM call failed" {:service service :model model :error (:error r)}))))))

(defn validate-tool-calls
  "Validate response structure and semantic correctness"
  [response]
  ;; Structure validation
  (let [result (schema/validate ToolCallResponse response)]
    (is (:valid? result)
        (str "Response should match ToolCallResponse schema: " (pr-str (:errors result)))))

  ;; Semantic validation - correct operations requested
  (let [calls (get response "tool_calls")
        ops (set (map #(get-in % ["arguments" "op"]) calls))]
    (is (= 3 (count calls)) "Should have exactly 3 tool calls")
    (is (contains? ops "add") "Should include add operation")
    (is (contains? ops "multiply") "Should include multiply operation")))

;;------------------------------------------------------------------------------
;; Cloud Provider Integration Tests (skip if API key missing)
;;------------------------------------------------------------------------------

(deftest ^:integration test-claude-tool-calling
  (testing "Claude emits valid tool calls from MCP schema"
    (when-service-available anthropic-available? "Anthropic/Claude" "ANTHROPIC_API_KEY"
                            (let [response (call-llm-sync "anthropic" (model/direct-model :anthropic))]
                              (validate-tool-calls response)))))

(deftest ^:integration test-gemini-tool-calling
  (testing "Gemini emits valid tool calls from MCP schema"
    (when-service-available google-available? "Google/Gemini" "GOOGLE_API_KEY"
                            (let [response (call-llm-sync "google" (model/direct-model :google))]
                              (validate-tool-calls response)))))

(deftest ^:integration test-openai-tool-calling
  (testing "OpenAI (via OpenRouter) emits valid tool calls from MCP schema"
    (when-service-available openrouter-available? "OpenRouter/OpenAI" "OPENROUTER_API_KEY"
                            (let [response (call-llm-sync "openrouter" (model/openrouter-model :openai))]
                              (validate-tool-calls response)))))

(deftest ^:integration test-grok-tool-calling
  (testing "Grok emits valid tool calls from MCP schema (no native tool API!)"
    (when-service-available xai-available? "xAI/Grok" "XAI_API_KEY"
                            (let [response (call-llm-sync "xai" (model/direct-model :xai))]
                              (validate-tool-calls response)))))

(deftest ^:integration test-all-providers-consistent
  (testing "All providers produce structurally identical responses"
    (when-service-available
     #(and (anthropic-available?) (google-available?)
           (openrouter-available?) (xai-available?))
     "All cloud providers" "ANTHROPIC_API_KEY, GOOGLE_API_KEY, OPENROUTER_API_KEY, XAI_API_KEY"
     (let [claude (call-llm-sync "anthropic" (model/direct-model :anthropic))
           gemini (call-llm-sync "google" (model/direct-model :google))
           openai (call-llm-sync "openrouter" (model/openrouter-model :openai))
           grok (call-llm-sync "xai" (model/direct-model :xai))]

       ;; All should have same structure
       (is (= (count (get claude "tool_calls"))
              (count (get gemini "tool_calls"))
              (count (get openai "tool_calls"))
              (count (get grok "tool_calls")))
           "All providers should return same number of tool calls")

       ;; All should use same tool
       (is (every? #(= "calculator" (get % "name"))
                   (concat (get claude "tool_calls")
                           (get gemini "tool_calls")
                           (get openai "tool_calls")
                           (get grok "tool_calls")))
           "All tool calls should reference 'calculator' tool")))))

;;------------------------------------------------------------------------------
;; Ollama Tests (Local Inference - requires Ollama running)
;;------------------------------------------------------------------------------

(deftest ^:integration test-ollama-mistral-tool-calling
  (testing "Ollama mistral:7b emits valid tool calls from MCP schema"
    (let [response (call-llm-sync "ollama:local" "mistral:7b")]
      (validate-tool-calls response))))

(deftest ^:integration test-ollama-qwen-tool-calling
  (testing "Ollama qwen2.5-coder:14b emits valid tool calls from MCP schema"
    (let [response (call-llm-sync "ollama:local" "qwen2.5-coder:14b")]
      (validate-tool-calls response))))
