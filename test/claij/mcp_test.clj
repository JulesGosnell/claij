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
   [claij.mcp :refer [initialise-request
                      initialised-notification
                      list-tools-request
                      list-prompts-request
                      list-resources-request
                      list-changed?
                      initialize-mcp-cache
                      invalidate-mcp-cache-item
                      refresh-mcp-cache-item
                      tool-response-schema
                      tool-cache->request-schema
                      tool-cache->response-schema
                      tools-cache->request-schema
                      tools-cache->response-schema
                      resource-response-schema
                      resources-cache->request-schema
                      prompt-response-schema
                      prompt-cache->request-schema
                      prompts-cache->request-schema
                      logging-levels
                      logging-set-level-request-schema
                      logging-notification-schema
                      mcp-cache->request-schema
                      mcp-cache->response-schema
                      mcp-request-schema-fn
                      mcp-response-schema-fn
                      mcp-request-xition-schema-fn
                      mcp-response-xition-schema-fn
                      mcp-registry]]))

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
      (is (= 8 (count claij.mcp/logging-level-strings)))
      (is (contains? claij.mcp/logging-level-strings "debug"))
      (is (contains? claij.mcp/logging-level-strings "emergency"))

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
;; Integration test
;;------------------------------------------------------------------------------

(deftest ^:integration mcp-walk-through-test
  (let [n 100
        config {"command" "bash", "args" ["-c", "cd /home/jules/src/claij && ./bin/mcp-clojure-tools.sh"], "transport" "stdio"}
        ic (chan n (map write-str))
        oc (chan n (comp (remove list-changed?) (map read-str)))
        stop (start-mcp-bridge config ic oc)]

    (try
      (>!! ic initialise-request)
      (>!! ic initialised-notification)
      (>!! ic list-tools-request)
      (>!! ic list-prompts-request)
      (>!! ic list-resources-request)

      (catch Throwable t (log/error "should not have happened:" t))

      (finally
        (log/info "stopping bridge...")
        (Thread/sleep 1000)
        (stop)
        (log/info "bridge stopped")
        (is true)))))
