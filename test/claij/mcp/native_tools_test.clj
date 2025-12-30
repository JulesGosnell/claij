(ns claij.mcp.native-tools-test
  "Unit tests for native ↔ MCP tool format translation.
   
   These tests use CONCRETE EXAMPLES from both sides:
   - Left side: Hat's tool schemas (from tool-cache->request-schema)
   - Right side: OpenAI native tools format
   
   All keys are STRINGS - no keywords anywhere.
   
   Functions under test live in claij.mcp.schema (MCP is the sub-concern)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [claij.mcp.schema :as mcp-schema]))

;;==============================================================================
;; Concrete Examples: Hat's Tool Schemas
;; (Output of claij.mcp.schema/tool-cache->request-schema)
;;==============================================================================

(def hat-tool-schema-bash
  "Hat's schema for bash tool (from tool-cache->request-schema)"
  {"type" "object"
   "additionalProperties" false
   "required" ["name" "arguments"]
   "description" "Run shell command"
   "properties" {"name" {"const" "bash"}
                 "arguments" {"type" "object"
                              "properties" {"command" {"type" "string"}}
                              "required" ["command"]}}})

(def hat-tool-schema-clojure-eval
  "Hat's schema for clojure_eval tool"
  {"type" "object"
   "additionalProperties" false
   "required" ["name" "arguments"]
   "description" "Evaluate Clojure code"
   "properties" {"name" {"const" "clojure_eval"}
                 "arguments" {"type" "object"
                              "properties" {"code" {"type" "string"}}}}})

(def hat-tool-schema-list-issues
  "Hat's schema for list_issues tool"
  {"type" "object"
   "additionalProperties" false
   "required" ["name" "arguments"]
   "description" "List repository issues"
   "properties" {"name" {"const" "list_issues"}
                 "arguments" {"type" "object"
                              "properties" {"repo" {"type" "string"}}}}})

(def hat-tool-schema-no-description
  "Hat's schema for a tool without description"
  {"type" "object"
   "additionalProperties" false
   "required" ["name" "arguments"]
   "properties" {"name" {"const" "simple_tool"}
                 "arguments" {"type" "object"}}})

;;==============================================================================
;; Concrete Examples: OpenAI Native Tools Format
;; (What we send to LLM providers - ALL STRING KEYS)
;;==============================================================================

(def native-tool-bash
  "OpenAI native tool format for bash"
  {"type" "function"
   "function" {"name" "bash"
               "description" "Run shell command"
               "parameters" {"type" "object"
                             "properties" {"command" {"type" "string"}}
                             "required" ["command"]}}})

(def native-tool-clojure-eval
  "OpenAI native tool format for clojure_eval"
  {"type" "function"
   "function" {"name" "clojure_eval"
               "description" "Evaluate Clojure code"
               "parameters" {"type" "object"
                             "properties" {"code" {"type" "string"}}}}})

(def native-tool-list-issues
  "OpenAI native tool format for list_issues"
  {"type" "function"
   "function" {"name" "list_issues"
               "description" "List repository issues"
               "parameters" {"type" "object"
                             "properties" {"repo" {"type" "string"}}}}})

(def native-tool-no-description
  "OpenAI native tool format for tool without description"
  {"type" "function"
   "function" {"name" "simple_tool"
               "description" ""
               "parameters" {"type" "object"}}})

;;==============================================================================
;; Tests for: tool-schema->native-tool
;; (Hat's tool schema → OpenAI native tool)
;;==============================================================================

(deftest tool-schema->native-tool-test
  (testing "converts bash tool schema to native format"
    (is (= native-tool-bash
           (mcp-schema/mcp-tool-schema->native-tool-def hat-tool-schema-bash))))

  (testing "converts clojure_eval tool schema to native format"
    (is (= native-tool-clojure-eval
           (mcp-schema/mcp-tool-schema->native-tool-def hat-tool-schema-clojure-eval))))

  (testing "converts list_issues tool schema to native format"
    (is (= native-tool-list-issues
           (mcp-schema/mcp-tool-schema->native-tool-def hat-tool-schema-list-issues))))

  (testing "handles tool without description"
    (is (= native-tool-no-description
           (mcp-schema/mcp-tool-schema->native-tool-def hat-tool-schema-no-description)))))

;;==============================================================================
;; Tests for: mcp-tools-schema? (detection predicate)
;;==============================================================================

(deftest mcp-tools-schema?-test
  (testing "detects MCP tools schema by title"
    (is (true? (mcp-schema/native-tools-schema? {"title" "mcp-tools" "type" "object"}))))

  (testing "returns false for non-MCP schemas"
    (is (false? (mcp-schema/native-tools-schema? {"title" "something-else"})))
    (is (false? (mcp-schema/native-tools-schema? {"type" "object"})))
    (is (false? (mcp-schema/native-tools-schema? {})))))

;;==============================================================================
;; Tests for: tool-schemas-from-mcp-schema
;; (Extract the oneOf tool schemas from full MCP schema)
;;==============================================================================

;; Full MCP schema as produced by hat-mcp-request-schema-fn
(def full-mcp-schema-single-server
  "Complete MCP request schema with tools"
  {"title" "mcp-tools"
   "description" "MCP tool calling interface. Select tools and provide arguments."
   "type" "object"
   "additionalProperties" false
   "required" ["id" "calls"]
   "properties" {"id" {"const" ["llm" "llm-mcp"]}
                 "calls" {"type" "object"
                          "propertyNames" {"enum" ["default"]}
                          "additionalProperties"
                          {"type" "array"
                           "items" {"oneOf" [{"type" "object"
                                              "required" ["jsonrpc" "method" "params"]
                                              "properties"
                                              {"jsonrpc" {"const" "2.0"}
                                               "method" {"const" "tools/call"}
                                               "params" {"oneOf" [hat-tool-schema-bash
                                                                  hat-tool-schema-clojure-eval]}}}]}}}}})

(deftest tool-schemas-from-mcp-schema-test
  (testing "extracts tool schemas from full MCP schema"
    (let [tool-schemas (mcp-schema/mcp-tool-schemas-from-request-schema full-mcp-schema-single-server)]
      (is (= 2 (count tool-schemas)))
      (is (some #(= "bash" (get-in % ["properties" "name" "const"])) tool-schemas))
      (is (some #(= "clojure_eval" (get-in % ["properties" "name" "const"])) tool-schemas)))))

;;==============================================================================
;; Tests for: mcp-schema->native-tools
;; (Full pipeline: MCP schema → vector of native tools)
;;==============================================================================

(deftest mcp-schema->native-tools-test
  (testing "converts full MCP schema to native tools"
    (let [native-tools (mcp-schema/mcp-request-schema->native-tool-defs full-mcp-schema-single-server)]
      (is (= 2 (count native-tools)))
      ;; Check structure
      (is (every? #(= "function" (get % "type")) native-tools))
      ;; Check tool names extracted
      (is (some #(= "bash" (get-in % ["function" "name"])) native-tools))
      (is (some #(= "clojure_eval" (get-in % ["function" "name"])) native-tools))
      ;; Check descriptions preserved
      (is (some #(= "Run shell command" (get-in % ["function" "description"])) native-tools))
      (is (some #(= "Evaluate Clojure code" (get-in % ["function" "description"])) native-tools)))))

;;==============================================================================
;; Tests for helper functions
;;==============================================================================

(deftest parse-prefixed-tool-name-test
  (testing "parses server__tool format"
    (is (= ["github" "list_issues"] (mcp-schema/parse-prefixed-tool-name "github__list_issues")))
    (is (= ["tools" "bash"] (mcp-schema/parse-prefixed-tool-name "tools__bash"))))

  (testing "returns default server for non-prefixed names"
    (is (= ["default" "bash"] (mcp-schema/parse-prefixed-tool-name "bash")))
    (is (= ["default" "clojure_eval"] (mcp-schema/parse-prefixed-tool-name "clojure_eval"))))

  (testing "handles edge cases"
    ;; Single underscore is NOT a separator
    (is (= ["default" "my_function"] (mcp-schema/parse-prefixed-tool-name "my_function")))
    ;; Multiple underscores after prefix
    (is (= ["server" "my_long_function"] (mcp-schema/parse-prefixed-tool-name "server__my_long_function")))))

;;==============================================================================
;; Tests for M1 Instance Conversions
;; Runtime tool calls (LLM ↔ MCP)
;;==============================================================================

(def native-tool-call-bash
  "Native tool call from LLM (all string keys)"
  {"id" "call_abc123"
   "name" "bash"
   "arguments" {"command" "ls -la"}})

(def native-tool-call-prefixed
  "Native tool call with server prefix"
  {"id" "call_gh1"
   "name" "github__list_issues"
   "arguments" {"repo" "claij"}})

(def mcp-tool-call-bash
  "Expected MCP request format"
  {"jsonrpc" "2.0"
   "id" "call_abc123"
   "method" "tools/call"
   "params" {"name" "bash"
             "arguments" {"command" "ls -la"}}})

(def mcp-tool-call-list-issues
  "Expected MCP request format (prefix stripped)"
  {"jsonrpc" "2.0"
   "id" "call_gh1"
   "method" "tools/call"
   "params" {"name" "list_issues"
             "arguments" {"repo" "claij"}}})

(deftest native-tool-call->mcp-tool-call-test
  (testing "converts native tool call to MCP request"
    (is (= mcp-tool-call-bash
           (mcp-schema/native-tool-call->mcp-tool-call native-tool-call-bash))))

  (testing "strips server prefix from tool name"
    (is (= mcp-tool-call-list-issues
           (mcp-schema/native-tool-call->mcp-tool-call native-tool-call-prefixed))))

  (testing "handles missing arguments"
    (let [result (mcp-schema/native-tool-call->mcp-tool-call {"id" "x" "name" "foo"})]
      (is (= {} (get-in result ["params" "arguments"]))))))

(def mcp-tool-result
  "MCP tool result format"
  {"result" {"content" [{"type" "text" "text" "file1.txt\nfile2.txt"}]}})

(def mcp-tool-result-multi
  "MCP result with multiple content items"
  {"result" {"content" [{"type" "text" "text" "first"}
                        {"type" "text" "text" "second"}]}})

(deftest mcp-tool-result->native-tool-result-test
  (testing "converts MCP result to native tool result message"
    (let [result (mcp-schema/mcp-tool-result->native-tool-result mcp-tool-result "call_123")]
      (is (= "tool" (get result "role")))
      (is (= "call_123" (get result "tool_call_id")))
      (is (= "file1.txt\nfile2.txt" (get result "content")))))

  (testing "concatenates multiple text content items"
    (let [result (mcp-schema/mcp-tool-result->native-tool-result mcp-tool-result-multi "call_456")]
      (is (= "first\nsecond" (get result "content")))))

  (testing "handles empty content"
    (let [result (mcp-schema/mcp-tool-result->native-tool-result {"result" {}} "call_789")]
      (is (= "" (get result "content"))))))

;;==============================================================================
;; Tests for XOR constraint helpers
;;==============================================================================

(deftest xor-tool-prompt-test
  (testing "XOR prompt is defined and non-empty"
    (is (string? mcp-schema/xor-tool-prompt))
    (is (> (count mcp-schema/xor-tool-prompt) 50))))

(deftest xor-violation?-test
  (testing "detects XOR violation (both tool_calls and content)"
    (is (true? (mcp-schema/xor-violation? {"tool_calls" [{"id" "1"}] "content" "hello"}))))

  (testing "no violation with only tool_calls"
    (is (false? (mcp-schema/xor-violation? {"tool_calls" [{"id" "1"}]})))
    (is (false? (mcp-schema/xor-violation? {"tool_calls" [{"id" "1"}] "content" nil})))
    (is (false? (mcp-schema/xor-violation? {"tool_calls" [{"id" "1"}] "content" ""})))
    (is (false? (mcp-schema/xor-violation? {"tool_calls" [{"id" "1"}] "content" "   "}))))

  (testing "no violation with only content"
    (is (false? (mcp-schema/xor-violation? {"content" "hello"})))
    (is (false? (mcp-schema/xor-violation? {"content" "hello" "tool_calls" nil})))
    (is (false? (mcp-schema/xor-violation? {"content" "hello" "tool_calls" []})))))

(deftest find-mcp-xition-test
  (testing "finds xition with MCP tools schema"
    (let [mcp-xition {"id" ["llm" "mcp"]}
          other-xition {"id" ["llm" "end"]}
          schema-resolver (fn [x]
                            (if (= x mcp-xition)
                              {"title" "mcp-tools"}
                              {"title" "other"}))]
      (is (= mcp-xition (mcp-schema/find-mcp-xition [other-xition mcp-xition] schema-resolver)))))

  (testing "returns nil when no MCP xition"
    (let [xitions [{"id" ["a" "b"]} {"id" ["b" "c"]}]
          schema-resolver (constantly {"title" "not-mcp"})]
      (is (nil? (mcp-schema/find-mcp-xition xitions schema-resolver))))))

(deftest extract-native-tools-from-xitions-test
  (testing "extracts tools when MCP xition exists"
    (let [mcp-xition {"id" ["llm" "mcp"]}
          schema-resolver (fn [x]
                            (if (= x mcp-xition)
                              full-mcp-schema-single-server
                              {"title" "other"}))
          result (mcp-schema/extract-native-tools-from-xitions [mcp-xition] schema-resolver)]
      (is (some? result))
      (is (= 2 (count (:tools result))))
      (is (= mcp-xition (:mcp-xition result)))))

  (testing "returns nil when no MCP xition"
    (let [xitions [{"id" ["a" "b"]}]
          schema-resolver (constantly {"title" "other"})]
      (is (nil? (mcp-schema/extract-native-tools-from-xitions xitions schema-resolver))))))

(deftest native-tool-calls->mcp-event-test
  (testing "converts native tool_calls to MCP event format"
    (let [mcp-xition-id ["llm" "llm-mcp"]
          tool-calls [{"id" "call_1" "name" "bash" "arguments" {"cmd" "ls"}}
                      {"id" "call_2" "name" "clojure_eval" "arguments" {"code" "(+ 1 2)"}}]
          result (mcp-schema/native-tool-calls->mcp-event mcp-xition-id tool-calls)]
      (is (= mcp-xition-id (get result "id")))
      (is (= 2 (count (get-in result ["calls" "default"]))))))

  (testing "routes prefixed tools to different servers"
    (let [mcp-xition-id ["llm" "llm-mcp"]
          tool-calls [{"id" "call_1" "name" "bash" "arguments" {"cmd" "ls"}}
                      {"id" "call_2" "name" "github__list_issues" "arguments" {"repo" "x"}}]
          result (mcp-schema/native-tool-calls->mcp-event mcp-xition-id tool-calls)]
      (is (= mcp-xition-id (get result "id")))
      (is (= 1 (count (get-in result ["calls" "default"]))))
      (is (= 1 (count (get-in result ["calls" "github"])))))))
