(ns claij.poc.tool-calling-poc-test
  "LLM Compatibility Test: Schema-in-prompt → structured tool calls.
   
   PURPOSE: Validates that LLMs understand CLAIJ's core pattern:
   - Parse JSON Schema tool definitions from prompt text
   - Emit properly structured EDN tool_calls
   - Produce output that validates against Malli schema
   
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
   
   Current validated services:
   - anthropic / claude-sonnet-4-20250514
   - google / gemini-2.0-flash
   - openrouter / openai/gpt-4o
   - xai / grok-3-beta
   - ollama:local / mistral:7b (if running)"
  (:require
   [clojure.test :refer [deftest testing is]]
   [malli.core :as m]
   [claij.llm :as llm]))

;;------------------------------------------------------------------------------
;; Malli Schema for Tool Calls
;;------------------------------------------------------------------------------

(def CalculatorArguments
  "Schema for calculator tool arguments"
  [:map
   [:op [:enum "add" "multiply"]]
   [:a [:or :int :double]]
   [:b [:or :int :double]]])

(def ToolCall
  "Schema for a single tool call"
  [:map
   [:id :string]
   [:name :string]
   [:arguments CalculatorArguments]])

(def ToolCallResponse
  "Schema for LLM response containing tool calls"
  [:map
   [:tool_calls [:vector ToolCall]]])

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

Respond ONLY with an EDN data structure containing your tool calls. No prose. Example format:
{:tool_calls [{:id \"call_1\" :name \"calculator\" :arguments {:op \"add\" :a 1 :b 2}}]}")

;;------------------------------------------------------------------------------
;; Helper
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
  (is (m/validate ToolCallResponse response)
      (str "Response should match ToolCallResponse schema: "
           (m/explain ToolCallResponse response)))

  ;; Semantic validation - correct operations requested
  (let [calls (:tool_calls response)
        ops (set (map #(get-in % [:arguments :op]) calls))]
    (is (= 3 (count calls)) "Should have exactly 3 tool calls")
    (is (contains? ops "add") "Should include add operation")
    (is (contains? ops "multiply") "Should include multiply operation")))

;;------------------------------------------------------------------------------
;; Integration Tests
;;------------------------------------------------------------------------------

(deftest ^:integration test-claude-tool-calling
  (testing "Claude emits valid tool calls from MCP schema"
    (let [response (call-llm-sync "anthropic" "claude-sonnet-4-20250514")]
      (validate-tool-calls response))))

(deftest ^:integration test-gemini-tool-calling
  (testing "Gemini emits valid tool calls from MCP schema"
    (let [response (call-llm-sync "google" "gemini-2.0-flash")]
      (validate-tool-calls response))))

(deftest ^:integration test-openai-tool-calling
  (testing "OpenAI (via OpenRouter) emits valid tool calls from MCP schema"
    (let [response (call-llm-sync "openrouter" "openai/gpt-4o")]
      (validate-tool-calls response))))

(deftest ^:integration test-grok-tool-calling
  (testing "Grok emits valid tool calls from MCP schema (no native tool API!)"
    (let [response (call-llm-sync "xai" "grok-3-beta")]
      (validate-tool-calls response))))

(deftest ^:integration test-all-providers-consistent
  (testing "All providers produce structurally identical responses"
    (let [claude (call-llm-sync "anthropic" "claude-sonnet-4-20250514")
          gemini (call-llm-sync "google" "gemini-2.0-flash")
          openai (call-llm-sync "openrouter" "openai/gpt-4o")
          grok (call-llm-sync "xai" "grok-3-beta")]

      ;; All should have same structure
      (is (= (count (:tool_calls claude))
             (count (:tool_calls gemini))
             (count (:tool_calls openai))
             (count (:tool_calls grok)))
          "All providers should return same number of tool calls")

      ;; All should use same tool
      (is (every? #(= "calculator" (:name %))
                  (concat (:tool_calls claude)
                          (:tool_calls gemini)
                          (:tool_calls openai)
                          (:tool_calls grok)))
          "All tool calls should reference 'calculator' tool"))))

;;------------------------------------------------------------------------------
;; Ollama Tests (Local Inference)
;;------------------------------------------------------------------------------

(deftest ^:integration test-ollama-mistral-tool-calling
  (testing "Ollama mistral:7b emits valid tool calls from MCP schema"
    (let [response (call-llm-sync "ollama:local" "mistral:7b")]
      (validate-tool-calls response))))

(deftest ^:integration test-ollama-qwen-tool-calling
  (testing "Ollama qwen2.5-coder:7b emits valid tool calls from MCP schema"
    (let [response (call-llm-sync "ollama:local" "qwen2.5-coder:7b")]
      (validate-tool-calls response))))
