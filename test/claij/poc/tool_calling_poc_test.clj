(ns claij.poc.tool-calling-poc-test
  "PoC test: LLMs emit structured tool calls from MCP-style schema.
   
   Validates that all supported LLMs can:
   1. Parse JSON Schema tool definitions (MCP format)
   2. Emit properly structured EDN tool_calls
   3. Produce output that validates against Malli schema
   
   This demonstrates LLMs have 'muscle memory' for tool calling -
   no native tool API needed, just schema in prompt."
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
   [:a :number]
   [:b :number]])

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
  [provider model]
  (let [result (promise)]
    (llm/call provider model
              [{"role" "user" "content" tool-prompt}]
              (fn [r] (deliver result {:ok r}))
              {:error (fn [e] (deliver result {:error e}))})
    (let [r (deref result 30000 {:error {:timeout true}})]
      (if (:ok r)
        (:ok r)
        (throw (ex-info "LLM call failed" {:provider provider :model model :error (:error r)}))))))

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
    (let [response (call-llm-sync "anthropic" "claude-opus-4.5")]
      (validate-tool-calls response))))

(deftest ^:integration test-gemini-tool-calling
  (testing "Gemini emits valid tool calls from MCP schema"
    (let [response (call-llm-sync "google" "gemini-3-pro-preview")]
      (validate-tool-calls response))))

(deftest ^:integration test-openai-tool-calling
  (testing "OpenAI (via OpenRouter) emits valid tool calls from MCP schema"
    (let [response (call-llm-sync "openai" "gpt-5.2-chat")]
      (validate-tool-calls response))))

(deftest ^:integration test-all-providers-consistent
  (testing "All providers produce structurally identical responses"
    (let [claude (call-llm-sync "anthropic" "claude-opus-4.5")
          gemini (call-llm-sync "google" "gemini-3-pro-preview")
          openai (call-llm-sync "openai" "gpt-5.2-chat")]

      ;; All should have same structure
      (is (= (count (:tool_calls claude))
             (count (:tool_calls gemini))
             (count (:tool_calls openai)))
          "All providers should return same number of tool calls")

      ;; All should use same tool
      (is (every? #(= "calculator" (:name %))
                  (concat (:tool_calls claude)
                          (:tool_calls gemini)
                          (:tool_calls openai)))
          "All tool calls should reference 'calculator' tool"))))
