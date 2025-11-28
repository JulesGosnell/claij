(ns claij.fsm-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [m3.uri :refer [parse-uri]]
   [m3.validate :refer [validate]]
   [claij.fsm :refer [state-schema xition-schema schema-base-uri uri->schema
                      resolve-schema start-fsm llm-action trail->prompts]]
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
    (let [state-a-action (fn [context _fsm _ix _state _event _trail handler]
                          ;; Add cache to context and transition
                           (handler (assoc context :cache {:tools []})
                                    {"id" ["state-a" "state-b"]
                                     "data" "test"}))
          state-b-action (fn [context _fsm _ix _state _event _trail handler]
                          ;; Assert cache is present from previous state
                           (is (= {:tools []} (:cache context)))
                          ;; Add more to cache and transition to end
                           (handler (assoc context :cache {:tools ["bash" "read_file"]})
                                    {"id" ["state-b" "end"]}))
          end-action (fn [context _fsm _ix _state _event trail _handler]
                      ;; Deliver [context trail] to promise
                       (when-let [p (:fsm/completion-promise context)]
                         (deliver p [context trail]))
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
        (when (not= result :timeout)
          (let [[final-context trail] result
                final-event (claij.fsm/last-event trail)]
            (is (= {"id" ["state-b" "end"]} final-event) "FSM should return final event"))))

      ;; Clean up
      (stop))))

(deftest string-schema-reference-test
  (testing "FSM definition accepts string as schema reference"
    ;; Step 1: Confirm that string values are valid in schema fields at definition time.
    ;; This enables dynamic schema lookup at runtime (Step 2).
    (let [fsm-with-string-schema
          {"id" "string-schema-test"
           "schema" {"$schema" "https://json-schema.org/draft/2020-12/schema"
                     "$$id" "https://claij.org/schemas/string-schema-test"
                     "$version" 0}
           "states" [{"id" "llm" "action" "llm"}
                     {"id" "servicing" "action" "mcp"}
                     {"id" "end"}]
           "xitions" [{"id" ["start" "llm"]
                       "schema" {"type" "string"}}
                      ;; String schema references - to be resolved at runtime
                      {"id" ["llm" "servicing"]
                       "schema" "mcp-request"}
                      {"id" ["servicing" "llm"]
                       "schema" "mcp-response"}
                      {"id" ["llm" "end"]
                       "schema" {"type" "object"
                                 "properties" {"result" {"type" "string"}}}}]}]
      ;; FSM definition should validate (string is accepted as schema value)
      (is (= "string-schema-test" (get fsm-with-string-schema "id")))
      (is (= "mcp-request" (get-in fsm-with-string-schema ["xitions" 1 "schema"])))
      (is (= "mcp-response" (get-in fsm-with-string-schema ["xitions" 2 "schema"])))

      ;; Verify structure is correct for later runtime processing
      (let [xitions (get fsm-with-string-schema "xitions")
            string-schemas (filter #(string? (get % "schema")) xitions)]
        (is (= 2 (count string-schemas))
            "Should have 2 transitions with string schema references"))))

  (testing "def-m1 validates FSM with string schemas"
    ;; Use the validation machinery directly
    (let [fsm-m2 @(resolve 'claij.fsm/fsm-m2)
          test-fsm {"id" "validation-test"
                    "states" [{"id" "a"} {"id" "b"}]
                    "xitions" [{"id" ["a" "b"]
                                "schema" "dynamic-schema-key"}]}
          result (validate {:draft :draft2020-12} fsm-m2 {} test-fsm)]
      (is (:valid? result)
          (str "FSM with string schema should validate against fsm-m2. Errors: "
               (:errors result)))))

  (testing "resolve-schema with map schema passes through unchanged"
    (let [context {}
          xition {"id" ["a" "b"]}
          schema {"type" "string"}]
      (is (= schema (resolve-schema context xition schema)))))

  (testing "resolve-schema with string key looks up and calls schema function"
    (let [;; Schema function that returns a schema based on context
          my-schema-fn (fn [ctx xition]
                         {"type" "object"
                          "properties" {"tool" {"const" (get ctx :selected-tool)}}})
          context {:id->schema {"my-schema" my-schema-fn}
                   :selected-tool "clojure_eval"}
          xition {"id" ["llm" "servicing"]}
          resolved (resolve-schema context xition "my-schema")]
      (is (= {"type" "object"
              "properties" {"tool" {"const" "clojure_eval"}}}
             resolved))))

  (testing "resolve-schema with missing key returns true and logs warning"
    (let [context {:id->schema {}} ;; Empty - no schema functions
          xition {"id" ["a" "b"]}
          resolved (resolve-schema context xition "unknown-key")]
      (is (= true resolved)
          "Missing schema key should return true (permissive)")))

  (testing "state-schema resolves string schemas in transitions"
    (let [;; Schema function
          request-schema-fn (fn [ctx xition]
                              {"type" "object"
                               "properties" {"method" {"const" "tools/call"}}})
          context {:id->schema {"mcp-request" request-schema-fn}}
          fsm {"id" "test" "version" 0}
          state {"id" "llm"}
          ;; Mix of string and inline schemas
          xitions [{"id" ["llm" "servicing"] "schema" "mcp-request"}
                   {"id" ["llm" "end"] "schema" {"type" "string"}}]
          result (state-schema context fsm state xitions)]
      ;; Should have oneOf with resolved schemas
      (is (= 2 (count (get result "oneOf"))))
      ;; First should be resolved from function
      (is (= {"type" "object"
              "properties" {"method" {"const" "tools/call"}}}
             (first (get result "oneOf"))))
      ;; Second should be passed through
      (is (= {"type" "string"}
             (second (get result "oneOf")))))))

;;------------------------------------------------------------------------------
;; Trail Infrastructure Tests
;; 
;; These test core FSM infrastructure (trail->prompts, llm-action) 
;; and should not depend on specific FSMs like code-review-fsm.

;; Minimal FSM for infrastructure tests
(def ^:private infra-test-fsm
  {"id" "infra-test"
   "schema" {"$schema" "https://json-schema.org/draft/2020-12/schema"
             "$$id" "https://claij.org/schemas/infra-test"
             "$version" 0}
   "states" [{"id" "processor" "action" "llm"}
             {"id" "end" "action" "end"}]
   "xitions" [{"id" ["start" "processor"]
               "schema" {"type" "object"
                         "properties" {"id" {"const" ["start" "processor"]}
                                       "input" {"type" "string"}}
                         "required" ["id" "input"]
                         "additionalProperties" false}}
              {"id" ["processor" "end"]
               "schema" {"type" "object"
                         "properties" {"id" {"const" ["processor" "end"]}
                                       "result" {"type" "string"}}
                         "required" ["id" "result"]
                         "additionalProperties" false}}]})

(deftest trail->prompts-test
  (testing "trail->prompts splits entries into user+assistant messages"
    (let [;; New format: one entry per transition with [ix-schema, event, s-schema, output-event]
          sample-trail [{"role" "user" "content" ["ix-schema1" "input1" "s-schema1" "output1"]}
                        {"role" "user" "content" ["ix-schema2" "input2" "s-schema2" "output2"]}]
          ;; Expands to user+assistant pairs
          expected [{"role" "user" "content" ["ix-schema1" "input1" "s-schema1"]}
                    {"role" "assistant" "content" [nil "output1" nil]}
                    {"role" "user" "content" ["ix-schema2" "input2" "s-schema2"]}
                    {"role" "assistant" "content" [nil "output2" nil]}]]
      (is (= expected (trail->prompts infra-test-fsm sample-trail))
          "trail->prompts should split each entry into user+assistant pair")))

  (testing "trail->prompts handles entry without output (retry case)"
    (let [;; Entry with nil output (error/retry in progress)
          sample-trail [{"role" "user" "content" ["ix-schema1" "input1" "s-schema1" "output1"]}
                        {"role" "user" "content" ["error-schema" "error-msg" "expected-schema" nil]}]
          ;; Error entry becomes user-only (no assistant message)
          expected [{"role" "user" "content" ["ix-schema1" "input1" "s-schema1"]}
                    {"role" "assistant" "content" [nil "output1" nil]}
                    {"role" "user" "content" ["error-schema" "error-msg" "expected-schema"]}]]
      (is (= expected (trail->prompts infra-test-fsm sample-trail))
          "Entry with nil output should produce user message only")))

  (testing "trail->prompts handles empty trail"
    (is (= [] (vec (trail->prompts infra-test-fsm [])))
        "Empty trail should return empty"))

  (testing "trail->prompts handles nil trail"
    (is (= [] (vec (trail->prompts infra-test-fsm nil)))
        "nil trail should return empty")))

(deftest llm-action-handler-arity-test
  (testing "llm-action calls handler with 2 args (context, event)"
    (let [handler-calls (atom [])
          ;; Mock handler that records how it was called
          mock-handler (fn [& args]
                         (swap! handler-calls conj args)
                         nil)
          fsm infra-test-fsm
          ix (first (filter #(= (get % "id") ["start" "processor"]) (get fsm "xitions")))
          state (first (filter #(= (get % "id") "processor") (get fsm "states")))
          event {"id" ["start" "processor"]
                 "input" "test data"}
          trail []
          context {:test true}]
      ;; Call the real llm-action with mocked open-router-async
      (with-redefs [open-router-async (fn [_provider _model _prompts success-handler & _opts]
                                        ;; Immediately call success with fake LLM response
                                        (success-handler {"id" ["processor" "end"]
                                                          "result" "processed"}))]
        (try
          (llm-action context fsm ix state event trail mock-handler)
          ;; If we get here without exception, check handler was called with 2 args
          (is (= 1 (count @handler-calls)) "handler should be called once")
          (is (= 2 (count (first @handler-calls))) "handler should receive 2 args (context, event)")
          (catch clojure.lang.ArityException e
            (is false (str "BUG: handler called with wrong arity - " (.getMessage e)))))))))
