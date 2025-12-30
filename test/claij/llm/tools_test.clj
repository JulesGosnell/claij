(ns claij.llm.tools-test
  "Unit tests for native ↔ MCP tool format translation.
   
   These tests use CONCRETE EXAMPLES from both sides:
   - Left side: Hat's tool schemas (from tool-cache->request-schema)
   - Right side: OpenAI native tools format
   
   All keys are STRINGS - no keywords anywhere."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [claij.llm.tools :as tools]))

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
           (tools/tool-schema->native-tool hat-tool-schema-bash))))

  (testing "converts clojure_eval tool schema to native format"
    (is (= native-tool-clojure-eval
           (tools/tool-schema->native-tool hat-tool-schema-clojure-eval))))

  (testing "converts list_issues tool schema to native format"
    (is (= native-tool-list-issues
           (tools/tool-schema->native-tool hat-tool-schema-list-issues))))

  (testing "handles tool without description"
    (is (= native-tool-no-description
           (tools/tool-schema->native-tool hat-tool-schema-no-description)))))

;;==============================================================================
;; Tests for: mcp-tools-schema? (detection predicate)
;;==============================================================================

(deftest mcp-tools-schema?-test
  (testing "detects MCP tools schema by title"
    (is (true? (tools/mcp-tools-schema? {"title" "mcp-tools" "type" "object"}))))

  (testing "returns false for non-MCP schemas"
    (is (false? (tools/mcp-tools-schema? {"title" "something-else"})))
    (is (false? (tools/mcp-tools-schema? {"type" "object"})))
    (is (false? (tools/mcp-tools-schema? {})))))

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
    (let [tool-schemas (tools/tool-schemas-from-mcp-schema full-mcp-schema-single-server)]
      (is (= 2 (count tool-schemas)))
      (is (some #(= "bash" (get-in % ["properties" "name" "const"])) tool-schemas))
      (is (some #(= "clojure_eval" (get-in % ["properties" "name" "const"])) tool-schemas)))))

;;==============================================================================
;; Tests for: mcp-schema->native-tools
;; (Full pipeline: MCP schema → vector of native tools)
;;==============================================================================

(deftest mcp-schema->native-tools-test
  (testing "converts full MCP schema to native tools"
    (let [native-tools (tools/mcp-schema->native-tools full-mcp-schema-single-server)]
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
