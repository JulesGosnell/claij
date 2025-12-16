(ns claij.mcp-test
  "Tests for MCP schema generation and cache management.
   
   Note: Schema generators now emit Malli format, not JSON Schema.
   Validation uses Malli with mcp-registry (which includes :json-schema type)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
   [clojure.data.json :refer [write-str read-str]]
   [clojure.core.async :refer [chan <!! >!!] :as async]
   [malli.core :as m]
   [claij.mcp.bridge :refer [start-mcp-bridge]]
   [claij.mcp.protocol :refer [initialise-request
                               initialised-notification
                               list-tools-request
                               list-prompts-request
                               list-resources-request
                               list-changed?]]
   [claij.mcp.cache :refer [initialize-mcp-cache
                            invalidate-mcp-cache-item
                            refresh-mcp-cache-item]]
   [claij.mcp.schema :as schema :refer [tool-response-schema
                                        tool-cache->request-schema
                                        tool-cache->response-schema
                                        tools-cache->request-schema
                                        tools-cache->response-schema
                                        resource-response-schema
                                        resources-cache->request-schema
                                        prompt-response-schema
                                        prompt-cache->request-schema
                                        prompts-cache->request-schema
                                        logging-level-strings
                                        logging-levels
                                        logging-set-level-request-schema
                                        logging-notification-schema
                                        mcp-cache->request-schema
                                        mcp-cache->response-schema
                                        mcp-request-schema-fn
                                        mcp-response-schema-fn
                                        mcp-request-xition-schema-fn
                                        mcp-response-xition-schema-fn
                                        mcp-registry
                                        ;; Story #64 Phase 1: envelope schemas
                                        json-rpc-request-envelope
                                        json-rpc-response-envelope
                                        json-rpc-notification-envelope
                                        make-request-envelope-schema
                                        make-response-envelope-schema
                                        ;; Story #64 Phase 2: runtime resolution
                                        find-tool-in-cache
                                        mcp-tool-request-schema-fn
                                        mcp-tool-response-schema-fn
                                        resolve-mcp-tool-input-schema
                                        resolve-mcp-tool-output-schema
                                        resolve-mcp-request-schema
                                        resolve-mcp-response-schema]]))

;;------------------------------------------------------------------------------
;; Helper for Malli validation with mcp-registry
;;------------------------------------------------------------------------------

(defn malli-valid?
  "Validate value against Malli schema using mcp-registry."
  [schema value]
  (m/validate schema value {:registry mcp-registry}))

;;------------------------------------------------------------------------------
;; Test data
;;------------------------------------------------------------------------------

(def tools-list-changed-notification
  {"jsonrpc" "2.0", "method" "notifications/tools/list_changed"})

(def resources-list-changed-notification
  {"jsonrpc" "2.0", "method" "notifications/resources/list_changed"})

(def prompts-list-changed-notification
  {"jsonrpc" "2.0", "method" "notifications/prompts/list_changed"})

(def initialise-response
  {"jsonrpc" "2.0",
   "id" 1,
   "result"
   {"protocolVersion" "2024-11-05",
    "capabilities"
    {"logging" {},
     "prompts" {"listChanged" true},
     "resources" {"subscribe" true, "listChanged" true},
     "tools" {"listChanged" true}},
    "serverInfo" {"name" "clojure-server", "version" "0.1.0"}}})

(def list-tools-response
  {"jsonrpc" "2.0",
   "id" 2,
   "result"
   {"tools"
    [{"name" "clojure_eval",
      "description" "Takes a Clojure Expression and evaluates it."
      "inputSchema"
      {"type" "object",
       "properties" {"code" {"type" "string", "description" "The Clojure code to evaluate."}},
       "required" ["code"]}}
     {"name" "bash",
      "description" "Execute bash shell commands."
      "inputSchema"
      {"type" "object",
       "properties" {"command" {"type" "string", "description" "The shell command to execute"}},
       "required" ["command"]}}]}})

(def list-prompts-response
  {"jsonrpc" "2.0",
   "id" 3,
   "result"
   {"prompts"
    [{"name" "system-prompt",
      "description" "Provides guidelines.",
      "arguments" []}
     {"name" "save-as",
      "description" "Save to file.",
      "arguments" [{"name" "file_path", "description" "File path", "required" true}]}]}})

(def list-resources-response
  {"jsonrpc" "2.0",
   "id" 4,
   "result"
   {"resources"
    [{"uri" "custom://readme", "name" "README.md", "mimeType" "text/markdown"}
     {"uri" "custom://project-info", "name" "Project Info", "mimeType" "text/markdown"}]}})

;;------------------------------------------------------------------------------
;; Cache management tests (unchanged - don't involve schemas)
;;------------------------------------------------------------------------------

(deftest mcp-test

  (testing "initialize-mcp-cache"
    (let [capabilities {"tools" {"listChanged" true}
                        "prompts" {"listChanged" true}
                        "resources" {"subscribe" true "listChanged" true}
                        "logging" {}}
          initial-cache {}]
      (is (= {"tools" nil "prompts" nil "resources" nil}
             (initialize-mcp-cache initial-cache capabilities)))))

  (testing "invalidate-mcp-cache-item"
    (let [cache {"tools" ["tool1" "tool2"] "prompts" ["prompt1"] "resources" ["res1"]}]
      (is (= {"tools" nil "prompts" ["prompt1"] "resources" ["res1"]}
             (invalidate-mcp-cache-item cache "tools")))
      (is (= {"tools" ["tool1" "tool2"] "prompts" nil "resources" ["res1"]}
             (invalidate-mcp-cache-item cache "prompts")))
      (is (= {"tools" ["tool1" "tool2"] "prompts" ["prompt1"] "resources" nil}
             (invalidate-mcp-cache-item cache "resources")))))

  (testing "refresh-mcp-cache-item"
    (let [cache {"tools" nil "prompts" ["old-prompt"]}
          new-data {"tools" ["new-tool1" "new-tool2"]}]
      (is (= {"tools" ["new-tool1" "new-tool2"] "prompts" ["old-prompt"]}
             (refresh-mcp-cache-item cache new-data)))))

  (testing "list-changed?"
    (is (= "tools" (list-changed? "notifications/tools/list_changed")))
    (is (= "prompts" (list-changed? "notifications/prompts/list_changed")))
    (is (= "resources" (list-changed? "notifications/resources/list_changed")))
    (is (nil? (list-changed? "initialize"))))

  (testing "cache update flow"
    (let [cache0 {}
          capabilities (get-in initialise-response ["result" "capabilities"])
          cache1 (initialize-mcp-cache cache0 capabilities)
          _ (is (= {"tools" nil "prompts" nil "resources" nil} cache1))

          tools-data (get list-tools-response "result")
          cache2 (refresh-mcp-cache-item cache1 tools-data)
          _ (is (= (get-in list-tools-response ["result" "tools"])
                   (get cache2 "tools")))

          prompts-data (get list-prompts-response "result")
          cache3 (refresh-mcp-cache-item cache2 prompts-data)
          _ (is (= (get-in list-prompts-response ["result" "prompts"])
                   (get cache3 "prompts")))

          resources-data (get list-resources-response "result")
          cache4 (refresh-mcp-cache-item cache3 resources-data)
          _ (is (= (get-in list-resources-response ["result" "resources"])
                   (get cache4 "resources")))

          cache5 (invalidate-mcp-cache-item cache4 "tools")
          _ (is (nil? (get cache5 "tools")))
          _ (is (= (get cache4 "prompts") (get cache5 "prompts")))
          _ (is (= (get cache4 "resources") (get cache5 "resources")))])
    (is true))

  ;;----------------------------------------------------------------------------
  ;; Schema generation tests - now test Malli format
  ;;----------------------------------------------------------------------------

  (testing "tool call schema generation"
    (let [tool-cache {"name" "clojure_eval"
                      "description" "Takes a Clojure Expression and evaluates it."
                      "inputSchema" {"type" "object"
                                     "properties" {"code" {"type" "string"}}
                                     "required" ["code"]}}

          tool-request {"name" "clojure_eval" "arguments" {"code" "(+ 1 1)"}}
          invalid-request-wrong-name {"name" "wrong" "arguments" {"code" "(+ 1 1)"}}
          invalid-request-missing-arg {"name" "clojure_eval" "arguments" {}}

          request-schema (tool-cache->request-schema tool-cache)]

      ;; Schema structure is Malli [:map ...]
      (is (= :map (first request-schema)) "Request schema should be [:map ...]")
      (is (= {:closed true} (second request-schema)) "Schema should be closed")

      ;; Valid request passes
      (is (malli-valid? request-schema tool-request)
          "Valid tool request should validate")

      ;; Invalid requests fail
      (is (not (malli-valid? request-schema invalid-request-wrong-name))
          "Wrong tool name should fail")
      (is (not (malli-valid? request-schema invalid-request-missing-arg))
          "Missing required argument should fail (validated by :json-schema type)")))

  (testing "combined tools schema generation (:or)"
    (let [tools-cache [{"name" "add"
                        "inputSchema" {"type" "object"
                                       "properties" {"a" {"type" "integer"} "b" {"type" "integer"}}
                                       "required" ["a" "b"]}}
                       {"name" "greet"
                        "inputSchema" {"type" "object"
                                       "properties" {"name" {"type" "string"}}
                                       "required" ["name"]}}]

          add-request {"name" "add" "arguments" {"a" 1 "b" 2}}
          greet-request {"name" "greet" "arguments" {"name" "Alice"}}
          invalid-request {"name" "add" "arguments" {"a" "not-a-number"}}

          request-schema (tools-cache->request-schema tools-cache)]

      ;; Structure is [:or ...] with correct count
      (is (= :or (first request-schema)) "Request schema should be [:or ...]")
      (is (= 2 (dec (count request-schema))) ":or should have 2 alternatives")

      ;; Valid requests pass
      (is (malli-valid? request-schema add-request) "Add request should validate")
      (is (malli-valid? request-schema greet-request) "Greet request should validate")

      ;; Invalid request fails (wrong type for 'a')
      (is (not (malli-valid? request-schema invalid-request))
          "Invalid request should fail validation")))

  (testing "resource read schema generation"
    (let [resources-cache [{"uri" "custom://readme" "name" "README.md"}
                           {"uri" "custom://project-info" "name" "Project Info"}]

          valid-request {"uri" "custom://readme"}
          valid-request-2 {"uri" "custom://project-info"}
          invalid-request {"uri" "custom://unknown"}

          request-schema (resources-cache->request-schema resources-cache)]

      ;; Structure is [:map ["uri" [:enum ...]]]
      (is (= :map (first request-schema)) "Request schema should be [:map ...]")

      ;; Valid requests pass
      (is (malli-valid? request-schema valid-request))
      (is (malli-valid? request-schema valid-request-2))

      ;; Invalid request fails (uri not in enum)
      (is (not (malli-valid? request-schema invalid-request)))))

  (testing "prompt get schema generation"
    (let [prompt-no-args {"name" "system-prompt"
                          "description" "Provides guidelines."
                          "arguments" []}

          prompt-required-arg {"name" "save-as"
                               "description" "Save to file."
                               "arguments" [{"name" "file_path"
                                             "description" "File path"
                                             "required" true}]}

          request-no-args {"name" "system-prompt"}
          request-with-args {"name" "save-as" "arguments" {"file_path" "my-file.edn"}}
          request-missing-required {"name" "save-as" "arguments" {}}

          schema-no-args (prompt-cache->request-schema prompt-no-args)
          schema-required (prompt-cache->request-schema prompt-required-arg)

          prompts-cache [prompt-no-args prompt-required-arg]
          combined-schema (prompts-cache->request-schema prompts-cache)]

      ;; Schema structure is Malli
      (is (= :map (first schema-no-args)) "Prompt schema should be [:map ...]")

      ;; Combined schema uses :or
      (is (= :or (first combined-schema)) "Combined schema should be [:or ...]")
      (is (= 2 (dec (count combined-schema))) ":or should have 2 alternatives")

      ;; Valid requests pass
      (is (malli-valid? schema-no-args request-no-args))
      (is (malli-valid? schema-required request-with-args))

      ;; Missing required argument fails
      (is (not (malli-valid? schema-required request-missing-required)))))

  (testing "logging schema"
    (let [set-debug {"level" "debug"}
          set-error {"level" "error"}
          invalid-level {"level" "verbose"}]

      ;; Logging level strings constant
      (is (= 8 (count logging-level-strings)))
      (is (contains? logging-level-strings "debug"))
      (is (contains? logging-level-strings "emergency"))

      ;; Set-level requests - these are still refs to Malli schemas
      (is (malli-valid? [:ref "logging-set-level-request"] set-debug))
      (is (malli-valid? [:ref "logging-set-level-request"] set-error))
      (is (not (malli-valid? [:ref "logging-set-level-request"] invalid-level)))))

  (testing "combined MCP cache schema generation"
    (let [cache {"tools" [{"name" "eval"
                           "inputSchema" {"type" "object"
                                          "properties" {"code" {"type" "string"}}
                                          "required" ["code"]}}]
                 "resources" [{"uri" "custom://readme" "name" "README"}]
                 "prompts" [{"name" "system-prompt" "arguments" []}]}

          empty-cache {"tools" nil "resources" nil "prompts" nil}

          tools-only-cache {"tools" [{"name" "bash"
                                      "inputSchema" {"type" "object"
                                                     "properties" {"cmd" {"type" "string"}}}}]
                            "resources" nil
                            "prompts" nil}

          ;; Valid requests with JSON-RPC envelope
          tool-request {"jsonrpc" "2.0" "id" 1 "method" "tools/call"
                        "params" {"name" "eval" "arguments" {"code" "(+ 1 1)"}}}
          resource-request {"jsonrpc" "2.0" "id" 2 "method" "resources/read"
                            "params" {"uri" "custom://readme"}}
          prompt-request {"jsonrpc" "2.0" "id" 3 "method" "prompts/get"
                          "params" {"name" "system-prompt"}}
          logging-request {"jsonrpc" "2.0" "id" 4 "method" "logging/setLevel"
                           "params" {"level" "debug"}}

          invalid-method {"jsonrpc" "2.0" "id" 5 "method" "tools/list" "params" {}}

          request-schema (mcp-cache->request-schema cache)
          empty-request-schema (mcp-cache->request-schema empty-cache)
          tools-only-request-schema (mcp-cache->request-schema tools-only-cache)]

      ;; Full cache produces [:or ...] with 4 alternatives
      (is (= :or (first request-schema)) "Request schema should be [:or ...]")
      (is (= 4 (dec (count request-schema))) "Should have 4 alternatives")

      ;; Empty cache still has logging
      (is (= :or (first empty-request-schema)))
      (is (= 1 (dec (count empty-request-schema))) "Empty cache should have only logging")

      ;; Partial cache has tools + logging
      (is (= 2 (dec (count tools-only-request-schema))))

      ;; Valid requests pass
      (is (malli-valid? request-schema tool-request))
      (is (malli-valid? request-schema resource-request))
      (is (malli-valid? request-schema prompt-request))
      (is (malli-valid? request-schema logging-request))

      ;; Invalid method fails
      (is (not (malli-valid? request-schema invalid-method)))))

  (testing "FSM schema functions"
    (let [cache {"tools" [{"name" "clojure_eval"
                           "inputSchema" {"type" "object"
                                          "properties" {"code" {"type" "string"}}
                                          "required" ["code"]}}]
                 "resources" [{"uri" "custom://readme"}]
                 "prompts" [{"name" "system-prompt" "arguments" []}]}
          context {"state" cache}
          xition {"id" ["llm" "servicing"]}]

      ;; mcp-request-schema-fn returns [:or ...] schema
      (let [schema (mcp-request-schema-fn context xition)]
        (is (= :or (first schema)) "Request schema function should return [:or ...]")
        (is (= 4 (dec (count schema))) "Should have 4 alternatives"))

      ;; mcp-response-schema-fn returns [:or ...] schema
      (let [schema (mcp-response-schema-fn context xition)]
        (is (= :or (first schema)) "Response schema function should return [:or ...]"))

      ;; Works with empty cache
      (let [empty-context {"state" {"tools" nil "resources" nil "prompts" nil}}
            schema (mcp-request-schema-fn empty-context xition)]
        (is (= 1 (dec (count schema))) "Empty cache should have only logging"))))

  (testing "FSM xition schema functions (complete envelope)"
    (let [cache {"tools" [{"name" "clojure_eval"
                           "inputSchema" {"type" "object"
                                          "properties" {"code" {"type" "string"}}
                                          "required" ["code"]}}]
                 "resources" [{"uri" "custom://readme"}]
                 "prompts" [{"name" "system-prompt" "arguments" []}]}
          context {"state" cache}
          llm->servicing {"id" ["llm" "servicing"]}
          servicing->llm {"id" ["servicing" "llm"]}]

      ;; mcp-request-xition-schema-fn builds complete envelope
      (let [schema (mcp-request-xition-schema-fn context llm->servicing)]
        (is (= :map (first schema)) "Request xition schema should be [:map ...]")
        ;; Check that schema has "id" and "message" entries
        (let [entries (filter vector? (rest schema))
              entry-names (map first entries)]
          (is (some #{"id"} entry-names) "Should have id field")
          (is (some #{"message"} entry-names) "Should have message field")))

      ;; mcp-response-xition-schema-fn builds complete envelope
      (let [schema (mcp-response-xition-schema-fn context servicing->llm)]
        (is (= :map (first schema)) "Response xition schema should be [:map ...]"))

      ;; Validate actual events against generated schemas
      (let [request-schema (mcp-request-xition-schema-fn context llm->servicing)
            response-schema (mcp-response-xition-schema-fn context servicing->llm)

            valid-request {"id" ["llm" "servicing"]
                           "message" {"jsonrpc" "2.0" "id" 1 "method" "tools/call"
                                      "params" {"name" "clojure_eval"
                                                "arguments" {"code" "(+ 1 1)"}}}}
            valid-response {"id" ["servicing" "llm"]
                            "message" {"jsonrpc" "2.0" "id" 1
                                       "result" {"content" [{"type" "text" "text" "2"}]}}}
            invalid-request {"id" ["llm" "servicing"]
                             "message" {"jsonrpc" "2.0" "id" 2 "method" "invalid/method"
                                        "params" {}}}]

        (is (malli-valid? request-schema valid-request) "Valid tool call request should pass")
        (is (malli-valid? response-schema valid-response) "Valid tool response should pass")
        (is (not (malli-valid? request-schema invalid-request)) "Invalid method should fail")))))

;;------------------------------------------------------------------------------
;; Story #64: Static envelope schema tests
;;------------------------------------------------------------------------------

(deftest json-rpc-envelope-schemas-test
  (testing "Static request envelope validates JSON-RPC 2.0 requests"
    ;; Valid requests with various ID types
    (is (malli-valid? json-rpc-request-envelope
                      {"jsonrpc" "2.0" "id" 1 "method" "tools/call" "params" {"name" "foo"}})
        "Integer ID should be valid")
    (is (malli-valid? json-rpc-request-envelope
                      {"jsonrpc" "2.0" "id" "abc-123" "method" "tools/call" "params" {}})
        "String ID should be valid")
    (is (malli-valid? json-rpc-request-envelope
                      {"jsonrpc" "2.0" "id" 1 "method" "tools/call"})
        "Params should be optional")

    ;; Invalid requests
    (is (not (malli-valid? json-rpc-request-envelope
                           {"jsonrpc" "1.0" "id" 1 "method" "test"}))
        "Wrong jsonrpc version should fail")
    (is (not (malli-valid? json-rpc-request-envelope
                           {"jsonrpc" "2.0" "method" "test"}))
        "Missing id should fail"))

  (testing "Static response envelope validates JSON-RPC 2.0 responses"
    ;; Valid success response
    (is (malli-valid? json-rpc-response-envelope
                      {"jsonrpc" "2.0" "id" 1 "result" {"data" "anything"}})
        "Success response should be valid")
    (is (malli-valid? json-rpc-response-envelope
                      {"jsonrpc" "2.0" "id" "req-42" "result" nil})
        "Nil result should be valid")

    ;; Valid error response
    (is (malli-valid? json-rpc-response-envelope
                      {"jsonrpc" "2.0" "id" 1 "error" {"code" -32600 "message" "Invalid Request"}})
        "Error response should be valid")
    (is (malli-valid? json-rpc-response-envelope
                      {"jsonrpc" "2.0" "id" 1 "error" {"code" -32000 "message" "Server error" "data" {"details" "..."}}})
        "Error with data should be valid"))

  (testing "Static notification envelope validates JSON-RPC 2.0 notifications"
    (is (malli-valid? json-rpc-notification-envelope
                      {"jsonrpc" "2.0" "method" "notifications/initialized"})
        "Notification without params should be valid")
    (is (malli-valid? json-rpc-notification-envelope
                      {"jsonrpc" "2.0" "method" "notifications/message" "params" {"level" "info"}})
        "Notification with params should be valid")

    ;; Notifications must NOT have id
    (is (not (malli-valid? json-rpc-notification-envelope
                           {"jsonrpc" "2.0" "id" 1 "method" "test"}))
        "Notification with id should fail (that's a request)")))

(deftest make-envelope-schema-test
  (testing "make-request-envelope-schema builds typed envelopes"
    (let [tools-call-schema (make-request-envelope-schema "tools/call" [:map ["name" :string]])]
      ;; Valid tool call
      (is (malli-valid? tools-call-schema
                        {"jsonrpc" "2.0" "id" 1 "method" "tools/call" "params" {"name" "bash"}})
          "Valid tool call should pass")
      ;; Wrong method fails
      (is (not (malli-valid? tools-call-schema
                             {"jsonrpc" "2.0" "id" 1 "method" "resources/read" "params" {"name" "bash"}}))
          "Wrong method should fail")
      ;; Wrong params type fails
      (is (not (malli-valid? tools-call-schema
                             {"jsonrpc" "2.0" "id" 1 "method" "tools/call" "params" {"name" 123}}))
          "Wrong params type should fail")))

  (testing "make-request-envelope-schema with nil params uses :any"
    (let [any-params-schema (make-request-envelope-schema "test/method" nil)]
      (is (malli-valid? any-params-schema
                        {"jsonrpc" "2.0" "id" 1 "method" "test/method" "params" "anything"})
          "Any params should be valid when schema is nil")
      (is (malli-valid? any-params-schema
                        {"jsonrpc" "2.0" "id" 1 "method" "test/method" "params" [1 2 3]})
          "Array params should be valid when schema is nil")))

  (testing "make-response-envelope-schema builds typed envelopes"
    (let [tool-result-schema (make-response-envelope-schema [:map ["content" [:vector :map]]])]
      ;; Valid response
      (is (malli-valid? tool-result-schema
                        {"jsonrpc" "2.0" "id" 1 "result" {"content" [{"type" "text" "text" "hello"}]}})
          "Valid tool result should pass")
      ;; Wrong result type fails
      (is (not (malli-valid? tool-result-schema
                             {"jsonrpc" "2.0" "id" 1 "result" {"content" "not-a-vector"}}))
          "Wrong result type should fail")))

  (testing "make-response-envelope-schema with nil result uses :any"
    (let [any-result-schema (make-response-envelope-schema nil)]
      (is (malli-valid? any-result-schema
                        {"jsonrpc" "2.0" "id" 1 "result" "anything"})
          "Any result should be valid when schema is nil"))))

;;------------------------------------------------------------------------------
;; Story #64 Phase 2: Runtime schema resolution tests
;;------------------------------------------------------------------------------

(def test-tool-cache
  "Sample cache with tools for testing"
  {"tools" [{"name" "clojure_eval"
             "description" "Evaluate Clojure code"
             "inputSchema" {"type" "object"
                            "properties" {"code" {"type" "string"}}
                            "required" ["code"]}}
            {"name" "bash"
             "description" "Run bash commands"
             "inputSchema" {"type" "object"
                            "properties" {"command" {"type" "string"}}
                            "required" ["command"]}}
            {"name" "simple_tool"
             "description" "Tool without inputSchema"}]})

(deftest find-tool-in-cache-test
  (testing "finds tool by name"
    (let [tool (find-tool-in-cache test-tool-cache "clojure_eval")]
      (is (some? tool))
      (is (= "clojure_eval" (get tool "name")))))

  (testing "returns nil for unknown tool"
    (is (nil? (find-tool-in-cache test-tool-cache "unknown_tool"))))

  (testing "returns nil when cache has no tools"
    (is (nil? (find-tool-in-cache {} "clojure_eval")))
    (is (nil? (find-tool-in-cache {"tools" nil} "clojure_eval")))))

(deftest resolve-mcp-tool-input-schema-test
  (testing "resolves tool with inputSchema"
    (let [schema (resolve-mcp-tool-input-schema test-tool-cache "clojure_eval")]
      (is (= :json-schema (first schema)) "Should be :json-schema type")
      (is (= {"type" "object"
              "properties" {"code" {"type" "string"}}
              "required" ["code"]}
             (:schema (second schema))))))

  (testing "returns :any for tool without inputSchema"
    (is (= :any (resolve-mcp-tool-input-schema test-tool-cache "simple_tool"))))

  (testing "returns :any for unknown tool"
    (is (= :any (resolve-mcp-tool-input-schema test-tool-cache "unknown"))))

  (testing "returns :any for empty cache"
    (is (= :any (resolve-mcp-tool-input-schema {} "any_tool")))))

(deftest resolve-mcp-request-schema-test
  (testing "resolves complete request schema with tool name"
    (let [context {"state" test-tool-cache}
          schema (resolve-mcp-request-schema context "clojure_eval")]
      ;; Should be a complete JSON-RPC envelope
      (is (= :map (first schema)))

      ;; Valid request passes
      (is (malli-valid? schema
                        {"jsonrpc" "2.0"
                         "id" 1
                         "method" "tools/call"
                         "params" {"name" "clojure_eval"
                                   "arguments" {"code" "(+ 1 1)"}}})
          "Valid clojure_eval request should pass")

      ;; Wrong tool name fails
      (is (not (malli-valid? schema
                             {"jsonrpc" "2.0"
                              "id" 1
                              "method" "tools/call"
                              "params" {"name" "wrong_tool"
                                        "arguments" {"code" "(+ 1 1)"}}}))
          "Wrong tool name should fail")

      ;; Missing required argument fails
      (is (not (malli-valid? schema
                             {"jsonrpc" "2.0"
                              "id" 1
                              "method" "tools/call"
                              "params" {"name" "clojure_eval"
                                        "arguments" {}}}))
          "Missing required 'code' argument should fail")))

  (testing "returns generic envelope when tool-name is nil"
    (let [context {"state" test-tool-cache}
          schema (resolve-mcp-request-schema context nil)]
      ;; Should accept any params
      (is (malli-valid? schema
                        {"jsonrpc" "2.0"
                         "id" 1
                         "method" "tools/call"
                         "params" {"anything" "goes"}}))
      (is (malli-valid? schema
                        {"jsonrpc" "2.0"
                         "id" 1
                         "method" "tools/call"
                         "params" "just a string"}))))

  (testing "returns generic envelope when cache is empty"
    (let [context {"state" {}}
          schema (resolve-mcp-request-schema context "clojure_eval")]
      ;; Tool not in cache, but should still produce valid envelope with :any args
      (is (malli-valid? schema
                        {"jsonrpc" "2.0"
                         "id" 1
                         "method" "tools/call"
                         "params" {"name" "clojure_eval"
                                   "arguments" {"anything" "allowed"}}})))))

(deftest resolve-mcp-response-schema-test
  (testing "resolves response schema"
    (let [context {"state" test-tool-cache}
          schema (resolve-mcp-response-schema context "clojure_eval")]
      ;; Should be a complete JSON-RPC response envelope
      (is (= :map (first schema)))

      ;; Valid tool response passes
      (is (malli-valid? schema
                        {"jsonrpc" "2.0"
                         "id" 1
                         "result" {"content" [{"type" "text" "text" "2"}]}})
          "Valid tool response should pass")))

  (testing "response schema works without cache"
    (let [context {"state" {}}
          schema (resolve-mcp-response-schema context "any_tool")]
      ;; Should still produce valid envelope
      (is (= :map (first schema))))))

;;------------------------------------------------------------------------------
;; Story #64 Phase 3: FSM integration tests
;;------------------------------------------------------------------------------
;; These tests verify that schema functions work when called via FSM's
;; resolve-schema mechanism (string key -> :id->schema lookup -> (fn context xition))

(deftest mcp-tool-schema-fn-integration-test
  (testing "mcp-tool-request-schema-fn works at config time (no cache)"
    ;; Config time: No cache, no tool-name - returns envelope + :any
    (let [context {} ; No "state" with cache
          xition {"id" ["state-a" "state-b"]} ; No tool-name
          schema (mcp-tool-request-schema-fn context xition)]
      (is (= :map (first schema)) "Should return a map schema")
      ;; Should accept any valid JSON-RPC request
      (is (malli-valid? schema
                        {"jsonrpc" "2.0" "id" 1 "method" "tools/call" "params" {"anything" "goes"}})
          "Config-time schema should accept any params")))

  (testing "mcp-tool-request-schema-fn works at runtime (with cache + tool-name)"
    ;; Runtime: Cache available, tool-name in xition - returns typed schema
    (let [context {"state" test-tool-cache}
          xition {"id" ["state-a" "state-b"] "tool-name" "clojure_eval"}
          schema (mcp-tool-request-schema-fn context xition)]
      (is (= :map (first schema)) "Should return a map schema")
      ;; Should validate tool-specific params
      (is (malli-valid? schema
                        {"jsonrpc" "2.0" "id" 1 "method" "tools/call"
                         "params" {"name" "clojure_eval" "arguments" {"code" "(+ 1 1)"}}})
          "Runtime schema should accept valid tool call")
      ;; Wrong tool should fail (if schema is specific enough)
      (is (not (malli-valid? schema
                             {"jsonrpc" "2.0" "id" 1 "method" "tools/call"
                              "params" {"name" "wrong_tool" "arguments" {"code" "x"}}}))
          "Runtime schema should reject wrong tool name")))

  (testing "mcp-tool-response-schema-fn always validates tool-response format"
    ;; Response schema always uses tool-response format per MCP spec
    ;; (not :any, because MCP defines the response structure)
    (let [context {}
          xition {"id" ["state-a" "state-b"]}
          schema (mcp-tool-response-schema-fn context xition)]
      (is (= :map (first schema)) "Should return a map schema")
      ;; Must match tool-response format
      (is (malli-valid? schema
                        {"jsonrpc" "2.0" "id" 1
                         "result" {"content" [{"type" "text" "text" "output"}]}})
          "Schema should accept valid tool-response format")))

  (testing "mcp-tool-response-schema-fn works at runtime (same behavior)"
    ;; Response schema is the same regardless of cache (MCP defines it)
    (let [context {"state" test-tool-cache}
          xition {"id" ["state-a" "state-b"] "tool-name" "clojure_eval"}
          schema (mcp-tool-response-schema-fn context xition)]
      (is (= :map (first schema)) "Should return a map schema")
      ;; Should validate tool-response format
      (is (malli-valid? schema
                        {"jsonrpc" "2.0" "id" 1
                         "result" {"content" [{"type" "text" "text" "result"}]}})
          "Runtime schema should accept valid tool response")))

  (testing "same code path at all three times"
    ;; The key insight: these functions work identically regardless of when called
    ;; The difference is only what data is available in context/xition
    (let [config-ctx {}
          start-ctx {"state" {}} ; Empty cache
          runtime-ctx {"state" test-tool-cache}

          xition-no-tool {"id" ["a" "b"]}
          xition-with-tool {"id" ["a" "b"] "tool-name" "bash"}

          ;; All should return valid schemas
          s1 (mcp-tool-request-schema-fn config-ctx xition-no-tool)
          s2 (mcp-tool-request-schema-fn start-ctx xition-no-tool)
          s3 (mcp-tool-request-schema-fn runtime-ctx xition-with-tool)]

      ;; All produce map schemas
      (is (every? #(= :map (first %)) [s1 s2 s3])
          "All contexts should produce map schemas")

      ;; The runtime schema should be more specific (closed with tool name)
      ;; s1 and s2 should be equivalent (both have :any params)
      ;; s3 should be specific to "bash" tool
      (is (malli-valid? s3
                        {"jsonrpc" "2.0" "id" 1 "method" "tools/call"
                         "params" {"name" "bash" "arguments" {"command" "ls"}}})
          "Runtime schema specific to bash tool"))))

;;------------------------------------------------------------------------------
;; Integration test
;;------------------------------------------------------------------------------

(deftest ^:integration mcp-walk-through-test
  (testing "MCP bridge can send requests and receive responses"
    (let [n 100
          config {"command" "bash" "args" ["-c" "cd /home/jules/src/claij && ./bin/mcp-clojure-tools.sh"] "transport" "stdio"}
          ic (chan n (map write-str))
          ;; Filter out notifications - they have "method" key, responses have "result" key
          notification? (fn [msg] (and (map? msg) (contains? msg "method")))
          oc (chan n (comp (map read-str) (remove notification?)))
          stop (start-mcp-bridge config ic oc)]

      (try
        ;; Send initialize request
        (>!! ic initialise-request)
        (let [init-response (<!! oc)]
          (is (some? init-response) "Should receive initialize response")
          (is (contains? init-response "result") "Response should have result")
          (is (string? (get-in init-response ["result" "protocolVersion"]))
              "Response should include protocol version"))

        ;; Send initialized notification (no response expected)
        (>!! ic initialised-notification)

        ;; Send list-tools request
        (>!! ic list-tools-request)
        (let [tools-response (<!! oc)]
          (is (some? tools-response) "Should receive tools response")
          (is (vector? (get-in tools-response ["result" "tools"]))
              "Tools response should contain tools vector"))

        ;; Send list-prompts request
        (>!! ic list-prompts-request)
        (let [prompts-response (<!! oc)]
          (is (some? prompts-response) "Should receive prompts response")
          (is (vector? (get-in prompts-response ["result" "prompts"]))
              "Prompts response should contain prompts vector"))

        ;; Send list-resources request
        (>!! ic list-resources-request)
        (let [resources-response (<!! oc)]
          (is (some? resources-response) "Should receive resources response")
          (is (vector? (get-in resources-response ["result" "resources"]))
              "Resources response should contain resources vector"))

        (finally
          (stop))))))
