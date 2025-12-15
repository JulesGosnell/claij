(ns claij.poc.tuple3-mcp-poc-test
  "PoC test: MCP tool calling with CLAIJ's tuple-3 protocol."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [malli.core :as m]
   [claij.llm :as llm]))

;;------------------------------------------------------------------------------
;; Tuple-3 System Prompt (extracted from fsm.clj for reuse)
;;------------------------------------------------------------------------------

(def tuple3-system-prompt
  "We are living in a Clojure world.
All communications will be in EDN (Extensible Data Notation) format.

YOUR REQUEST:
- will contain [INPUT-SCHEMA INPUT-DOCUMENT OUTPUT-SCHEMA] triples.
- INPUT-SCHEMA: Malli schema describing the INPUT-DOCUMENT
- INPUT-DOCUMENT: The actual data to process
- OUTPUT-SCHEMA: Malli schema your response MUST conform to

YOUR RESPONSE - the OUTPUT-DOCUMENT:
- Must be ONLY valid EDN (no markdown, no backticks, no explanation)
- Must use string keys like \"id\" not keyword keys like :id
- The OUTPUT-SCHEMA will offer you a set (possibly only one) of choices/sub-schemas
- Your OUTPUT-DOCUMENT must conform strictly to one of these - it is a document NOT a schema itself
- Each sub-schema will contain a discriminator called \"id\". You must include this
- You must include all non-optional fields with a valid value

CRITICAL: Your entire response must be ONLY the EDN data structure. No prose, no explanation, no markdown fences.")

;;------------------------------------------------------------------------------
;; MCP Tool Definition (JSON Schema format, as MCP provides)
;;------------------------------------------------------------------------------

(def calculator-mcp-tool
  {"name" "calculator"
   "description" "Performs arithmetic operations. Returns numeric result."
   "inputSchema" {"type" "object"
                  "properties" {"op" {"type" "string" 
                                      "enum" ["add" "multiply"]}
                                "a" {"type" "number"}
                                "b" {"type" "number"}}
                  "required" ["op" "a" "b"]}})

;;------------------------------------------------------------------------------
;; Malli Schemas for Tuple-3 Protocol
;;------------------------------------------------------------------------------

(def CalculatorArgs
  [:map
   ["op" [:enum "add" "multiply"]]
   ["a" [:or :int :double]]
   ["b" [:or :int :double]]])

(def ToolCall
  [:map
   ["id" :string]
   ["name" [:= "calculator"]]
   ["arguments" CalculatorArgs]])

(def ToolCallsResponse
  [:map
   ["id" [:= "tool_calls"]]
   ["calls" [:vector {:min 1} ToolCall]]])

(def ToolResult
  [:map
   ["call_id" :string]
   ["result" [:or :int :double]]])

(def FinalAnswer
  [:map
   ["id" [:= "answer"]]
   ["value" [:or :int :double]]
   ["explanation" {:optional true} :string]])

(def OutputSchema
  [:or ToolCallsResponse FinalAnswer])

;;------------------------------------------------------------------------------
;; Input Schemas
;;------------------------------------------------------------------------------

(def TaskInput
  [:map
   ["task" :string]
   ["available_tools" [:vector :map]]])

(def ToolResultsInput
  [:map
   ["task" :string]
   ["tool_results" [:vector ToolResult]]])

;;------------------------------------------------------------------------------
;; Helper Functions
;;------------------------------------------------------------------------------

(defn make-tuple3-messages
  [context-prompts trail input-schema input-doc output-schema]
  (let [system-content (str/join "\n" 
                                 (concat [tuple3-system-prompt
                                          ""
                                          "CONTEXT:"]
                                         context-prompts
                                         [""
                                          "AVAILABLE MCP TOOLS:"
                                          (pr-str [calculator-mcp-tool])]))
        tuple3 [input-schema input-doc output-schema]]
    (concat
     [{"role" "system" "content" system-content}]
     trail
     [{"role" "user" "content" (pr-str tuple3)}])))

(defn call-llm-sync
  [provider model messages]
  (let [result (promise)]
    (llm/call provider model messages
              (fn [r] (deliver result {:ok r}))
              {:error (fn [e] (deliver result {:error e}))})
    (let [r (deref result 60000 {:error {:timeout true}})]
      (if (:ok r)
        (:ok r)
        (throw (ex-info "LLM call failed" {:provider provider :model model :error (:error r)}))))))

(defn execute-tool
  [{:strs [id arguments]}]
  (let [{:strs [op a b]} arguments
        result (case op
                 "add" (+ a b)
                 "multiply" (* a b))]
    {"call_id" id "result" result}))

;;------------------------------------------------------------------------------
;; Integration Tests
;;------------------------------------------------------------------------------

(deftest ^:integration test-tuple3-tool-calls-claude
  (testing "Claude emits tool_calls via tuple-3 protocol"
    (let [input-doc {"task" "Calculate: 42+17, 6*7, 100+23. Then sum all results."
                     "available_tools" [calculator-mcp-tool]}
          messages (make-tuple3-messages
                    ["You are a calculator assistant."
                     "Use the available tools to compute values."
                     "When you need to compute, emit tool_calls."
                     "When you have all results, emit final answer with the sum."]
                    []
                    TaskInput
                    input-doc
                    OutputSchema)
          response (call-llm-sync "anthropic" "claude-opus-4.5" messages)]
      (is (m/validate OutputSchema response)
          (str "Response should match OutputSchema: " (pr-str response)))
      (is (= "tool_calls" (get response "id"))
          "First response should be tool_calls")
      (is (>= (count (get response "calls")) 3)
          "Should request at least 3 tool calls"))))

(deftest ^:integration test-tuple3-tool-calls-gemini
  (testing "Gemini emits tool_calls via tuple-3 protocol"
    (let [input-doc {"task" "Calculate: 42+17, 6*7, 100+23. Then sum all results."
                     "available_tools" [calculator-mcp-tool]}
          messages (make-tuple3-messages
                    ["You are a calculator assistant."
                     "Use the available tools to compute values."
                     "When you need to compute, emit tool_calls."
                     "When you have all results, emit final answer with the sum."]
                    []
                    TaskInput
                    input-doc
                    OutputSchema)
          response (call-llm-sync "google" "gemini-3-pro-preview" messages)]
      (is (m/validate OutputSchema response)
          (str "Response should match OutputSchema: " (pr-str response)))
      (is (= "tool_calls" (get response "id"))
          "First response should be tool_calls")
      (is (>= (count (get response "calls")) 3)
          "Should request at least 3 tool calls"))))

(deftest ^:integration test-tuple3-tool-calls-openai
  (testing "OpenAI emits tool_calls via tuple-3 protocol"
    (let [input-doc {"task" "Calculate: 42+17, 6*7, 100+23. Then sum all results."
                     "available_tools" [calculator-mcp-tool]}
          messages (make-tuple3-messages
                    ["You are a calculator assistant."
                     "Use the available tools to compute values."
                     "When you need to compute, emit tool_calls."
                     "When you have all results, emit final answer with the sum."]
                    []
                    TaskInput
                    input-doc
                    OutputSchema)
          response (call-llm-sync "openai" "gpt-5.2-chat" messages)]
      (is (m/validate OutputSchema response)
          (str "Response should match OutputSchema: " (pr-str response)))
      (is (= "tool_calls" (get response "id"))
          "First response should be tool_calls")
      (is (>= (count (get response "calls")) 3)
          "Should request at least 3 tool calls"))))

(deftest ^:integration test-tuple3-tool-calls-grok
  (testing "Grok emits tool_calls via tuple-3 protocol"
    (let [input-doc {"task" "Calculate: 42+17, 6*7, 100+23. Then sum all results."
                     "available_tools" [calculator-mcp-tool]}
          messages (make-tuple3-messages
                    ["You are a calculator assistant."
                     "Use the available tools to compute values."
                     "When you need to compute, emit tool_calls."
                     "When you have all results, emit final answer with the sum."]
                    []
                    TaskInput
                    input-doc
                    OutputSchema)
          response (call-llm-sync "x-ai" "grok-code-fast-1" messages)]
      (is (m/validate OutputSchema response)
          (str "Response should match OutputSchema: " (pr-str response)))
      (is (= "tool_calls" (get response "id"))
          "First response should be tool_calls")
      (is (>= (count (get response "calls")) 3)
          "Should request at least 3 tool calls"))))

;;------------------------------------------------------------------------------
;; Multi-turn Piggyback Test
;;------------------------------------------------------------------------------

(deftest ^:integration test-tuple3-piggyback-conversation
  (testing "Full conversation: tool_calls -> execute -> final answer (Claude)"
    (let [input-doc-1 {"task" "Calculate: 42+17, 6*7, 100+23. Then sum all results."
                       "available_tools" [calculator-mcp-tool]}
          messages-1 (make-tuple3-messages
                      ["You are a calculator assistant."
                       "Use the available tools to compute values."
                       "When you need to compute, emit tool_calls."
                       "When you have all results, emit final answer with the sum."]
                      []
                      TaskInput
                      input-doc-1
                      OutputSchema)
          response-1 (call-llm-sync "anthropic" "claude-opus-4.5" messages-1)
          _ (is (= "tool_calls" (get response-1 "id")) "Turn 1 should be tool_calls")
          tool-results (mapv execute-tool (get response-1 "calls"))
          trail [{"role" "user" "content" (pr-str [TaskInput input-doc-1 OutputSchema])}
                 {"role" "assistant" "content" (pr-str response-1)}]
          input-doc-2 {"task" "Here are your tool results. Now provide the final sum."
                       "tool_results" tool-results}
          messages-2 (make-tuple3-messages
                      ["You are a calculator assistant."
                       "The tool results are provided."
                       "Sum up all the results and provide the final answer."]
                      trail
                      ToolResultsInput
                      input-doc-2
                      OutputSchema)
          response-2 (call-llm-sync "anthropic" "claude-opus-4.5" messages-2)]
      (is (m/validate OutputSchema response-2)
          (str "Response 2 should match OutputSchema: " (pr-str response-2)))
      (is (= "answer" (get response-2 "id"))
          "Turn 2 should be final answer")
      (is (= 224 (get response-2 "value"))
          "Final answer should be 224 (59 + 42 + 123)"))))
