(ns claij.fsm-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [m3.uri :refer [parse-uri]]
   [m3.validate :refer [validate]]
   [claij.fsm :refer [state-schema xition-schema schema-base-uri uri->schema]]
   [claij.llm.open-router :refer [open-router-async]]))

;;------------------------------------------------------------------------------
;; how do we know when a trail is finished
;; an action on the terminal state...
;; can we define an fsm with e.g. start->meeting->stop
;; can we define an fsm schema ?
;; the fsm itself should be json and have a schema
;; think about terminaology for states and transitions - very important to get it right - tense ?

;; TODO:
;; reintroduce roles as hats
;; add [sub-]schemas to trail
;; if [m2 m1] is returned by action and m2s are unique then we could just index-by and look up m2 without needing the oneOf validation... - yippee !
;; no - an llm will return just the m1 and we will need to do the oneOf validation to know what they meant ? or do e just get them to return [m2 m1]
;; we could just give them a list of schemas to choose from ...
;; maybe stick with oneOf stuff for the moment - consider tomorrow
;; should this be wired together with async channels and all just kick off asynchronously - yes - pass a handler to walk to put trail onto channel
;; the above is useful for controlled testing but not production
;; replace original with new impl
;; integrate an llm
;; integrate some sort of human postbox - email with a link ?
;; integrate mcp
;; integrate repl

;;------------------------------------------------------------------------------

(deftest fsm-test
  (let [fsm {"id" "test-fsm" "version" 0}
        state {"id" "test-state-A"}
        xition-1 {"id" ["test-state-A" "test-state-B"] "schema" {"type" "string"}}
        xition-2 {"id" ["test-state-A" "test-state-C"] "schema" {"type" "number"}}]
    (testing "xition-schema"
      (let [actual (xition-schema fsm xition-1)
            expected
            {"$schema" "https://json-schema.org/draft/2020-12/schema"
             "$$id" "https://claij.org/schemas/test-fsm/0/test-state-A.test-state-B"
             "properties"
             {"$schema" {"type" "string"}
              "$id" {"type" "string"}
              "id" {"const" ["test-state-A" "test-state-B"]}
              "document" {"type" "string"}}}]
        (is (= expected actual))
        (is (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-B"] "document" "test"})))
        (is (not (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-B"] "document" 0}))))))
    ;; (testing "state-schema: "
    ;;   (testing "with values"
    ;;     (let [actual
    ;;           (state-schema fsm state [xition-1 xition-2])
    ;;           expected
    ;;           {"$schema" "https://json-schema.org/draft/2020-12/schema"
    ;;            "$$id" "https://claij.org/schemas/test-fsm/0/test-state-A"
    ;;            "oneOf"
    ;;            [{"properties"
    ;;              {"$schema" {"type" "string"}
    ;;               "$id" {"type" "string"}
    ;;               "id" {"const" ["test-state-A" "test-state-B"]}
    ;;               "document" {"type" "string"}}}
    ;;             {"properties"
    ;;              {"$schema" {"type" "string"}
    ;;               "$id" {"type" "string"}
    ;;               "id" {"const" ["test-state-A" "test-state-C"]}
    ;;               "document" {"type" "number"}}}]}]
    ;;       (is (= expected actual))
    ;;       (is      (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-B"] "document" "test"})))
    ;;       (is (not (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-B"] "document" 0}))))
    ;;       (is      (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-C"] "document" 0})))
    ;;       (is (not (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-C"] "document" "test"}))))))
    ;;   (testing "with refs"
    ;;     (let [actual
    ;;           (state-schema fsm state [xition-1 xition-2])
    ;;           expected
    ;;           {"$schema" "https://json-schema.org/draft/2020-12/schema"
    ;;            "$$id" "https://claij.org/schemas/test-fsm/0/test-state-A"
    ;;            "oneOf"
    ;;            [{"properties"
    ;;              {"$schema" {"type" "string"}
    ;;               "$id" {"type" "string"}
    ;;               "id" {"const" ["test-state-A" "test-state-B"]}
    ;;               "document" {"type" "string"}}}
    ;;             {"properties"
    ;;              {"$schema" {"type" "string"}
    ;;               "$id" {"type" "string"}
    ;;               "id" {"const" ["test-state-A" "test-state-C"]}
    ;;               "document" {"type" "number"}}}]}]
    ;;       (is (= expected actual))
    ;;       (is      (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-B"] "document" "test"})))
    ;;       (is (not (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-B"] "document" 0}))))
    ;;       (is      (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-C"] "document" 0})))
    ;;       (is (not (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-C"] "document" "test"})))))))
    (testing "$ref to remote schema"
      (let [c2
            {:draft :draft2020-12
             :uri->schema
             (partial
              uri->schema
              {(parse-uri (str schema-base-uri "/test-schema"))
               {"$defs" {"a-string" {"type" "string"}
                         "a-number" {"type" "number"}}}})}]
        (is (:valid? (validate c2 {"$ref" (str schema-base-uri "/test-schema#/$defs/a-string")} {} "test")))
        (is (not (:valid? (validate c2 {"$ref" (str schema-base-uri "/test-schema#/$defs/a-string")} {} 0))))
        (is (:valid? (validate c2 {"$ref" (str schema-base-uri "/test-schema#/$defs/a-number")} {} 0)))
        (is (not (:valid? (validate c2 {"$ref" (str schema-base-uri "/test-schema#/$defs/a-number")} {} "test"))))))))

;;------------------------------------------------------------------------------
;; Weather Schema Integration Test

(deftest ^:integration weather-schema-test
  (let [schema
        {"$id" "https://claij.org/schemas/structured-data-integration-test"
         "type" "object",
         "properties"
         {"location"
          {"type" "string", "description" "City or location name"},
          "temperature"
          {"type" "number", "description" "Temperature in Celsius"},
          "conditions"
          {"type" "string", "description" "Weather conditions description"}},
         "required" ["location" "temperature" "conditions"],
         "additionalProperties" false}
        prompts
        [{"role" "user", "content" "What's the weather like in London?"}]]
    (doseq [[provider model] [["openai" "gpt-4o"]]]
                              ;; ["x-ai" "grok-code-fast-1"]
                              ;; ["x-ai" "grok-4"]
                              ;; ["google" "gemini-2.5-flash"]
                              ;; failing
                              ;;["openai" "gpt-5-pro"]
                              ;;["anthropic" "claude-sonnet-4.5"] ;; https://forum.bubble.io/t/anthropic-json-mode-tools-via-the-api-connector-tutorial/331283

      (testing "weather"
        (testing (str provider "/" model)
          (is (:valid? (validate {:draft :draft7} schema {} (let [p (promise)] (open-router-async provider model prompts (partial deliver p) {:schema schema}) @p)))))))))

;;------------------------------------------------------------------------------
;; Context Threading Test

(deftest context-threading-test
  (testing "Context flows through FSM transitions"
    (let [state-a-action (fn [context _fsm _ix _state _trail handler]
                          ;; Add cache to context and transition
                           (handler (assoc context :cache {:tools []})
                                    {"id" ["state-a" "state-b"]
                                     "data" "test"}))
          state-b-action (fn [context _fsm _ix _state _trail handler]
                          ;; Assert cache is present from previous state
                           (is (= {:tools []} (:cache context)))
                          ;; Add more to cache and transition to end
                           (handler (assoc context :cache {:tools ["bash" "read_file"]})
                                    {"id" ["state-b" "end"]}))
          end-action (fn [context _fsm _ix _state [{[_is event _os] "content"}] _handler]
                      ;; Deliver completion event to promise
                       (when-let [p (:fsm/completion-promise context)]
                         (deliver p event))
                      ;; Verify final context has accumulated cache
                       (is (= {:tools ["bash" "read_file"]} (:cache context))))
          test-fsm {"id" "context-test"
                    "schema" {"$schema" "https://json-schema.org/draft/2020-12/schema"
                              "$$id" "https://claij.org/schemas/context-test"
                              "$version" 0}
                    "states" [{"id" "state-a" "action" "action-a"}
                              {"id" "state-b" "action" "action-b"}
                              {"id" "end" "action" "end"}]
                    "xitions" [{"id" ["start" "state-a"]
                                "schema" {"type" "object"
                                          "properties" {"id" {"const" ["start" "state-a"]}
                                                        "input" {"type" "string"}}
                                          "required" ["id" "input"]}}
                               {"id" ["state-a" "state-b"]
                                "schema" {"type" "object"
                                          "properties" {"id" {"const" ["state-a" "state-b"]}
                                                        "data" {"type" "string"}}
                                          "required" ["id" "data"]}}
                               {"id" ["state-b" "end"]
                                "schema" {"type" "object"
                                          "properties" {"id" {"const" ["state-b" "end"]}}
                                          "required" ["id"]}}]}
          initial-context {:id->action {"action-a" state-a-action
                                        "action-b" state-b-action
                                        "end" end-action}}
          [submit await stop] (claij.fsm/start-fsm initial-context test-fsm)]

      ;; Submit and wait for completion
      (submit {"id" ["start" "state-a"] "input" "test-input"})
      
      (let [result (await 5000)]
        (is (not= result :timeout) "FSM should complete within timeout")
        (is (= {"id" ["state-b" "end"]} result) "FSM should return final event"))

      ;; Clean up
      (stop))))
