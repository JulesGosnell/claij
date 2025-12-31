(ns claij.poc.tuple3-mcp-poc-test
  "PoC test: MCP tool calling with CLAIJ's tuple-3 protocol.
   
   PURPOSE: Validates full tuple-3 conversation flow:
   - LLM receives [input-schema input-doc output-schema]
   - LLM emits tool_calls conforming to output-schema
   - Tools execute, results fed back
   - LLM emits final answer
   
   GOING FORWARD: Use this to validate new LLMs support
   multi-turn schema-guided conversations with tool execution.
   
   Current validated services:
   - anthropic / claude-sonnet-4-5
   - google / gemini-3-flash-preview
   - openrouter / openai/gpt-5.2
   - xai / grok-code-fast-1"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [claij.model :as model]
   [claij.schema :as schema]
   [claij.llm :as llm]))

;;------------------------------------------------------------------------------
;; Service Availability Checks
;;------------------------------------------------------------------------------

(defn env-key-set? [key]
  (not (str/blank? (System/getenv key))))

(defn google-available? [] (env-key-set? "GOOGLE_API_KEY"))
(defn anthropic-available? [] (env-key-set? "ANTHROPIC_API_KEY"))
(defn openrouter-available? [] (env-key-set? "OPENROUTER_API_KEY"))
(defn xai-available? [] (env-key-set? "XAI_API_KEY"))

(defmacro when-service-available
  "Run test body only if service is available, otherwise fail with clear message"
  [available-fn service-name env-var & body]
  `(if (~available-fn)
     (do ~@body)
     (is false (str ~service-name " unavailable - " ~env-var " not set"))))

;;------------------------------------------------------------------------------
;; Tuple-3 System Prompt (JSON Schema version)
;;------------------------------------------------------------------------------

(def tuple3-system-prompt
  "We are living in a Clojure world.
All communications will be in JSON format.

YOUR REQUEST:
- will contain [INPUT-SCHEMA INPUT-DOCUMENT OUTPUT-SCHEMA] triples.
- INPUT-SCHEMA: JSON Schema describing the INPUT-DOCUMENT
- INPUT-DOCUMENT: The actual data to process
- OUTPUT-SCHEMA: JSON Schema your response MUST conform to

YOUR RESPONSE - the OUTPUT-DOCUMENT:
- Must be ONLY valid JSON (no markdown, no backticks, no explanation)
- Must use string keys like \"id\" not keyword keys like :id
- The OUTPUT-SCHEMA will offer you a set (possibly only one) of choices via \"oneOf\"
- Your OUTPUT-DOCUMENT must conform strictly to one of these - it is a document NOT a schema itself
- Each sub-schema will contain a discriminator called \"id\". You must include this
- You must include all required fields with a valid value

SELF-CHECK BEFORE RESPONDING:
- All map keys are strings (every key begins with a quote character)
- Zero keyword keys (no key starts with a colon)
- Output matches OUTPUT-SCHEMA and includes \"id\"

CRITICAL: Your entire response must be ONLY the JSON data structure.")

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
;; JSON Schemas for Tuple-3 Protocol
;;------------------------------------------------------------------------------

(def CalculatorArgs
  {"type" "object"
   "required" ["op" "a" "b"]
   "properties" {"op" {"enum" ["add" "multiply"]}
                 "a" {"type" "number"}
                 "b" {"type" "number"}}})

(def ToolCall
  {"type" "object"
   "required" ["id" "name" "arguments"]
   "properties" {"id" {"type" "string"}
                 "name" {"const" "calculator"}
                 "arguments" CalculatorArgs}})

(def ToolCallsResponse
  {"type" "object"
   "required" ["id" "calls"]
   "properties" {"id" {"const" "tool_calls"}
                 "calls" {"type" "array"
                          "minItems" 1
                          "items" ToolCall}}})

(def ToolResult
  {"type" "object"
   "required" ["call_id" "result"]
   "properties" {"call_id" {"type" "string"}
                 "result" {"type" "number"}}})

(def FinalAnswer
  {"type" "object"
   "required" ["id" "value"]
   "properties" {"id" {"const" "answer"}
                 "value" {"type" "number"}
                 "explanation" {"type" "string"}}})

(def OutputSchema
  {"oneOf" [ToolCallsResponse FinalAnswer]})

;;------------------------------------------------------------------------------
;; Input Schemas
;;------------------------------------------------------------------------------

(def TaskInput
  {"type" "object"
   "required" ["task" "available_tools"]
   "properties" {"task" {"type" "string"}
                 "available_tools" {"type" "array"
                                    "items" {"type" "object"}}}})

(def ToolResultsInput
  {"type" "object"
   "required" ["task" "tool_results"]
   "properties" {"task" {"type" "string"}
                 "tool_results" {"type" "array"
                                 "items" ToolResult}}})

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
  [service model messages]
  (let [result (promise)]
    (llm/call service model messages
              (fn [r] (deliver result {:ok r}))
              {:error (fn [e] (deliver result {:error e}))})
    (let [r (deref result 60000 {:error {:timeout true}})]
      (if (:ok r)
        (:ok r)
        (throw (ex-info "LLM call failed" {:service service :model model :error (:error r)}))))))

(defn execute-tool
  [{:strs [id arguments]}]
  (let [{:strs [op a b]} arguments
        result (case op
                 "add" (+ a b)
                 "multiply" (* a b))]
    {"call_id" id "result" result}))

(defn valid?
  "Validate response against schema, return true if valid"
  [schema response]
  (:valid? (schema/validate schema response)))

;;------------------------------------------------------------------------------
;; Integration Tests
;;------------------------------------------------------------------------------

(deftest ^:integration test-tuple3-tool-calls-claude
  (testing "Claude emits tool_calls via tuple-3 protocol"
    (when-service-available anthropic-available? "Anthropic/Claude" "ANTHROPIC_API_KEY"
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
                                  response (call-llm-sync "anthropic" (model/direct-model :anthropic) messages)]
                              (is (valid? OutputSchema response)
                                  (str "Response should match OutputSchema: " (pr-str response)))
                              (is (= "tool_calls" (get response "id"))
                                  "First response should be tool_calls")
                              (is (>= (count (get response "calls")) 3)
                                  "Should request at least 3 tool calls")))))

(deftest ^:integration test-tuple3-tool-calls-gemini
  (testing "Gemini emits tool_calls via tuple-3 protocol"
    (when-service-available google-available? "Google/Gemini" "GOOGLE_API_KEY"
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
                                  response (call-llm-sync "google" (model/direct-model :google) messages)]
                              (is (valid? OutputSchema response)
                                  (str "Response should match OutputSchema: " (pr-str response)))
                              (is (= "tool_calls" (get response "id"))
                                  "First response should be tool_calls")
                              (is (>= (count (get response "calls")) 3)
                                  "Should request at least 3 tool calls")))))

(deftest ^:integration test-tuple3-tool-calls-openai
  (testing "OpenAI emits tool_calls via tuple-3 protocol"
    (when-service-available openrouter-available? "OpenRouter/OpenAI" "OPENROUTER_API_KEY"
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
                                  response (call-llm-sync "openrouter" (model/openrouter-model :openai) messages)]
                              (is (valid? OutputSchema response)
                                  (str "Response should match OutputSchema: " (pr-str response)))
                              (is (= "tool_calls" (get response "id"))
                                  "First response should be tool_calls")
                              (is (>= (count (get response "calls")) 3)
                                  "Should request at least 3 tool calls")))))

(deftest ^:integration test-tuple3-tool-calls-grok
  (testing "Grok emits tool_calls via tuple-3 protocol"
    (when-service-available xai-available? "xAI/Grok" "XAI_API_KEY"
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
                                  response (call-llm-sync "xai" (model/direct-model :xai) messages)]
                              (is (valid? OutputSchema response)
                                  (str "Response should match OutputSchema: " (pr-str response)))
                              (is (= "tool_calls" (get response "id"))
                                  "First response should be tool_calls")
                              (is (>= (count (get response "calls")) 3)
                                  "Should request at least 3 tool calls")))))

;;------------------------------------------------------------------------------
;; Multi-turn Piggyback Test
;;------------------------------------------------------------------------------

(deftest ^:integration test-tuple3-piggyback-conversation
  (testing "Full conversation: tool_calls -> execute -> final answer (Claude)"
    (when-service-available anthropic-available? "Anthropic/Claude" "ANTHROPIC_API_KEY"
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
                                  response-1 (call-llm-sync "anthropic" (model/direct-model :anthropic) messages-1)
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
                                  response-2 (call-llm-sync "anthropic" (model/direct-model :anthropic) messages-2)]
                              (is (valid? OutputSchema response-2)
                                  (str "Response 2 should match OutputSchema: " (pr-str response-2)))
                              (is (= "answer" (get response-2 "id"))
                                  "Turn 2 should be final answer")
                              (is (= 224 (get response-2 "value"))
                                  "Final answer should be 224 (59 + 42 + 123)")))))
