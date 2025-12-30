(ns claij.llm.tools-test
  "Unit tests for native ↔ MCP tool format translation.
   
   These tests use CONCRETE EXAMPLES from both sides:
   - Left side: Native tool_calls format from claij.llm.service
   - Right side: MCP event format from claij.hat.mcp
   
   The transformation functions must pass these exact examples."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [claij.llm.tools :as tools]))

;;==============================================================================
;; Concrete Examples from Production Code
;;==============================================================================

;; From claij.llm.service/parse-response "openai-compat":
;; The arguments come back with KEYWORD keys because json/parse-string uses true
(def native-tool-calls-single
  "Single tool call as returned by LLM service (keyword keys in arguments)"
  [{:id "call_abc123"
    :name "bash"
    :arguments {:command "ls -la"}}])

(def native-tool-calls-multi
  "Multiple tool calls in single response"
  [{:id "call_1"
    :name "clojure_eval"
    :arguments {:code "(+ 1 2)"}}
   {:id "call_2"
    :name "bash"
    :arguments {:command "pwd"}}])

;; For multi-server MCP setup, tool names are prefixed: "server__tool"
(def native-tool-calls-prefixed
  "Tool calls with server-prefixed names (multi-server MCP)"
  [{:id "call_gh1"
    :name "github__list_issues"
    :arguments {:repo "claij" :state "open"}}
   {:id "call_tools1"
    :name "tools__bash"
    :arguments {:command "git status"}}])

;; From claij.hat.mcp-test - what mcp-service-action expects:
;; ALL KEYS ARE STRINGS, including inside arguments
(def mcp-event-single
  "MCP event format for single server, single call"
  {"id" ["llm" "llm-mcp"]
   "calls" {"default" [{"jsonrpc" "2.0"
                        "id" "call_abc123"
                        "method" "tools/call"
                        "params" {"name" "bash"
                                  "arguments" {"command" "ls -la"}}}]}})

(def mcp-event-multi-calls
  "MCP event format for single server, multiple calls"
  {"id" ["llm" "llm-mcp"]
   "calls" {"default" [{"jsonrpc" "2.0"
                        "id" "call_1"
                        "method" "tools/call"
                        "params" {"name" "clojure_eval"
                                  "arguments" {"code" "(+ 1 2)"}}}
                       {"jsonrpc" "2.0"
                        "id" "call_2"
                        "method" "tools/call"
                        "params" {"name" "bash"
                                  "arguments" {"command" "pwd"}}}]}})

(def mcp-event-multi-server
  "MCP event format for multiple servers"
  {"id" ["llm" "llm-mcp"]
   "calls" {"github" [{"jsonrpc" "2.0"
                       "id" "call_gh1"
                       "method" "tools/call"
                       "params" {"name" "list_issues"
                                 "arguments" {"repo" "claij" "state" "open"}}}]
            "tools" [{"jsonrpc" "2.0"
                      "id" "call_tools1"
                      "method" "tools/call"
                      "params" {"name" "bash"
                                "arguments" {"command" "git status"}}}]}})

;;==============================================================================
;; MCP Results → Native Tool Results (for continuing conversation)
;;==============================================================================

(def mcp-results-single
  "MCP results from single server"
  {"results" {"default" [{"result" {"content" [{"type" "text" "text" "file1.txt\nfile2.txt"}]}}]}})

(def mcp-results-multi-server
  "MCP results from multiple servers"
  {"results" {"github" [{"result" {"content" [{"type" "text" "text" "Issue #1: Bug"}]}}]
              "tools" [{"result" {"content" [{"type" "text" "text" "/home/jules/src/claij"}]}}]}})

;;==============================================================================
;; Tests for: native-tool-calls->mcp-event
;;==============================================================================

(deftest native-tool-calls->mcp-event-test
  (testing "converts single tool call to MCP event format"
    (let [result (tools/native-tool-calls->mcp-event
                  "llm" "llm-mcp" native-tool-calls-single)]
      (is (= mcp-event-single result))))

  (testing "converts multiple tool calls to MCP event format"
    (let [result (tools/native-tool-calls->mcp-event
                  "llm" "llm-mcp" native-tool-calls-multi)]
      (is (= mcp-event-multi-calls result))))

  (testing "routes prefixed tool names to correct servers"
    (let [result (tools/native-tool-calls->mcp-event
                  "llm" "llm-mcp" native-tool-calls-prefixed)]
      (is (= mcp-event-multi-server result))))

  (testing "converts keyword keys in arguments to string keys"
    ;; This is the critical bug fix - arguments must have string keys
    (let [result (tools/native-tool-calls->mcp-event
                  "llm" "llm-mcp" native-tool-calls-single)
          args (get-in result ["calls" "default" 0 "params" "arguments"])]
      (is (every? string? (keys args)) "all argument keys must be strings"))))

;;==============================================================================
;; Tests for: mcp-results->tool-messages (for conversation continuation)
;;==============================================================================

(deftest mcp-results->tool-messages-test
  (testing "converts MCP results to tool result messages for LLM"
    ;; When LLM calls tools, we need to send results back in tool_result format
    ;; TODO: Define expected format and implement
    (is (= 1 1) "placeholder")))

;;==============================================================================
;; Tests for: mcp-tools->native-tools (for LLM request)
;;==============================================================================

;; MCP tool format (from tools/list)
(def mcp-tools-single-server
  {"default" [{"name" "bash"
               "description" "Run shell command"
               "inputSchema" {"type" "object"
                              "properties" {"command" {"type" "string"}}
                              "required" ["command"]}}
              {"name" "clojure_eval"
               "description" "Evaluate Clojure code"
               "inputSchema" {"type" "object"
                              "properties" {"code" {"type" "string"}}}}]})

(def mcp-tools-multi-server
  {"github" [{"name" "list_issues"
              "description" "List repository issues"
              "inputSchema" {"type" "object"
                             "properties" {"repo" {"type" "string"}}}}]
   "tools" [{"name" "bash"
             "description" "Run shell command"
             "inputSchema" {"type" "object"
                            "properties" {"command" {"type" "string"}}}}]})

;; Expected OpenAI native tool format
(def native-tools-single-server
  "Single server tools - no prefixing"
  [{:type "function"
    :function {:name "bash"
               :description "Run shell command"
               :parameters {"type" "object"
                            "properties" {"command" {"type" "string"}}
                            "required" ["command"]}}}
   {:type "function"
    :function {:name "clojure_eval"
               :description "Evaluate Clojure code"
               :parameters {"type" "object"
                            "properties" {"code" {"type" "string"}}}}}])

(def native-tools-multi-server
  "Multi-server tools get prefixed names for routing back"
  [{:type "function"
    :function {:name "github__list_issues"
               :description "[github] List repository issues"
               :parameters {"type" "object"
                            "properties" {"repo" {"type" "string"}}}}}
   {:type "function"
    :function {:name "tools__bash"
               :description "[tools] Run shell command"
               :parameters {"type" "object"
                            "properties" {"command" {"type" "string"}}}}}])

(deftest mcp-tools->native-tools-test
  (testing "converts single-server MCP tools to native format (no prefixing)"
    (let [result (tools/mcp-tools->native-tools mcp-tools-single-server)]
      (is (= native-tools-single-server result))))

  (testing "converts multi-server MCP tools with prefixed names"
    (let [result (tools/mcp-tools->native-tools mcp-tools-multi-server)]
      ;; Order may vary due to map iteration, so check contents
      (is (= (count native-tools-multi-server) (count result)))
      (is (some #(= "github__list_issues" (get-in % [:function :name])) result))
      (is (some #(= "tools__bash" (get-in % [:function :name])) result))
      (is (some #(str/starts-with? (get-in % [:function :description]) "[github]") result))
      (is (some #(str/starts-with? (get-in % [:function :description]) "[tools]") result)))))

;;==============================================================================
;; Helper function tests
;;==============================================================================

(deftest stringify-keys-test
  (testing "converts keyword keys to string keys recursively"
    (is (= {"a" 1 "b" {"c" 2}} (tools/stringify-keys {:a 1 :b {:c 2}})))
    (is (= {"a" [1 2 3]} (tools/stringify-keys {:a [1 2 3]})))
    (is (= "string" (tools/stringify-keys "string"))))

  (testing "handles mixed key types"
    (is (= {"a" 1 "b" 2} (tools/stringify-keys {:a 1 "b" 2}))))

  (testing "handles vectors with nested maps"
    (is (= [{"a" 1} {"b" 2}] (tools/stringify-keys [{:a 1} {:b 2}]))))

  (testing "handles nil and primitives"
    (is (nil? (tools/stringify-keys nil)))
    (is (= 42 (tools/stringify-keys 42)))
    (is (= true (tools/stringify-keys true)))))

(deftest parse-prefixed-tool-name-test
  (testing "parses server__tool format"
    (is (= ["github" "list_issues"] (tools/parse-prefixed-tool-name "github__list_issues")))
    (is (= ["tools" "bash"] (tools/parse-prefixed-tool-name "tools__bash"))))

  (testing "returns default server for non-prefixed names"
    (is (= ["default" "bash"] (tools/parse-prefixed-tool-name "bash")))
    (is (= ["default" "clojure_eval"] (tools/parse-prefixed-tool-name "clojure_eval"))))

  (testing "handles edge cases"
    ;; Single underscore is NOT a separator
    (is (= ["default" "my_function"] (tools/parse-prefixed-tool-name "my_function")))
    ;; Multiple underscores after prefix
    (is (= ["server" "my_long_function"] (tools/parse-prefixed-tool-name "server__my_long_function")))))
