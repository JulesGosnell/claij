(ns claij.fsm-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.malli :refer [valid-fsm?]]
   [claij.fsm :refer [state-schema resolve-schema start-fsm llm-action trail->prompts]]
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
  ;; xition-schema and expand-schema tests removed during Malli migration.
  ;; These functions produced JSON Schema format and were never used in production.
  ;; 
  ;; state-schema now produces Malli :or schemas and is tested via:
  ;; - string-schema-reference-test (dynamic schema resolution)
  ;; - claij.fsm.state-schema-test (comprehensive state schema tests)
  ;; - claij.mcp-test (MCP-specific xition schema tests)
  (testing "placeholder - see other test files for state-schema tests"
    (is true)))

;;------------------------------------------------------------------------------
;; Weather Schema Integration Test

;; weather-schema-test removed during Malli migration.
;; JSON Schema integration tests with LLMs are superseded by Malli POC tests
;; in claij.malli-poc-test which demonstrate LLMs understanding Malli schemas.

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
                    "states" [{"id" "state-a" "action" "action-a"}
                              {"id" "state-b" "action" "action-b"}
                              {"id" "end" "action" "end"}]
                    "xitions" [{"id" ["start" "state-a"]
                                "schema" [:map {:closed true}
                                          ["id" [:= ["start" "state-a"]]]
                                          ["input" :string]]}
                               {"id" ["state-a" "state-b"]
                                "schema" [:map {:closed true}
                                          ["id" [:= ["state-a" "state-b"]]]
                                          ["data" :string]]}
                               {"id" ["state-b" "end"]
                                "schema" [:map {:closed true}
                                          ["id" [:= ["state-b" "end"]]]]}]}
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
                       "schema" {:type :string}}
                      ;; String schema references - to be resolved at runtime
                      {"id" ["llm" "servicing"]
                       "schema" "mcp-request"}
                      {"id" ["servicing" "llm"]
                       "schema" "mcp-response"}
                      {"id" ["llm" "end"]
                       "schema" [:map ["result" :string]]}]}]
      ;; FSM definition should validate (string is accepted as schema value)
      (is (= "string-schema-test" (get fsm-with-string-schema "id")))
      (is (= "mcp-request" (get-in fsm-with-string-schema ["xitions" 1 "schema"])))
      (is (= "mcp-response" (get-in fsm-with-string-schema ["xitions" 2 "schema"])))

      ;; Verify structure is correct for later runtime processing
      (let [xitions (get fsm-with-string-schema "xitions")
            string-schemas (filter #(string? (get % "schema")) xitions)]
        (is (= 2 (count string-schemas))
            "Should have 2 transitions with string schema references"))))

  (testing "valid-fsm? validates FSM with string schemas"
    ;; Use Malli validation (FSM schema migrated from JSON Schema to Malli)
    (let [test-fsm {"id" "validation-test"
                    "states" [{"id" "a"} {"id" "b"}]
                    "xitions" [{"id" ["a" "b"]
                                "schema" "dynamic-schema-key"}]}]
      (is (valid-fsm? test-fsm)
          "FSM with string schema should validate against Malli fsm-schema")))

  (testing "resolve-schema with map schema passes through unchanged"
    (let [context {}
          xition {"id" ["a" "b"]}
          schema [:map ["name" :string]]]
      (is (= schema (resolve-schema context xition schema)))))

  (testing "resolve-schema with string key looks up and calls schema function"
    (let [;; Schema function that returns a Malli schema based on context
          my-schema-fn (fn [ctx xition]
                         [:map {:closed true}
                          ["tool" [:= (get ctx :selected-tool)]]])
          context {:id->schema {"my-schema" my-schema-fn}
                   :selected-tool "clojure_eval"}
          xition {"id" ["llm" "servicing"]}
          resolved (resolve-schema context xition "my-schema")]
      (is (= [:map {:closed true}
              ["tool" [:= "clojure_eval"]]]
             resolved))))

  (testing "resolve-schema with missing key returns true and logs warning"
    (let [context {:id->schema {}} ;; Empty - no schema functions
          xition {"id" ["a" "b"]}
          resolved (resolve-schema context xition "unknown-key")]
      (is (= true resolved)
          "Missing schema key should return true (permissive)")))

  (testing "state-schema resolves string schemas in transitions"
    (let [;; Schema function returns Malli
          request-schema-fn (fn [ctx xition]
                              [:map {:closed true}
                               ["method" [:= "tools/call"]]])
          context {:id->schema {"mcp-request" request-schema-fn}}
          fsm {"id" "test" "version" 0}
          state {"id" "llm"}
          ;; Mix of string and inline Malli schemas
          xitions [{"id" ["llm" "servicing"] "schema" "mcp-request"}
                   {"id" ["llm" "end"] "schema" :string}]
          result (state-schema context fsm state xitions)]
      ;; Should be [:or ...] with resolved schemas
      (is (= :or (first result)) "state-schema should return [:or ...]")
      (is (= 2 (dec (count result))) "Should have 2 alternatives")
      ;; First should be resolved from function
      (is (= [:map {:closed true} ["method" [:= "tools/call"]]]
             (second result)))
      ;; Second should be passed through
      (is (= :string (nth result 2))))))

;;------------------------------------------------------------------------------
;; Trail Infrastructure Tests
;; 
;; These test core FSM infrastructure (trail->prompts, llm-action) 
;; and should not depend on specific FSMs like code-review-fsm.

;; Minimal FSM for infrastructure tests
(def ^:private infra-test-fsm
  "Test FSM using Malli schemas for transitions."
  {"id" "infra-test"
   "schema" nil ;; No FSM-level schema needed for this test
   "states" [{"id" "processor" "action" "llm"}
             {"id" "end" "action" "end"}]
   "xitions" [{"id" ["start" "processor"]
               "schema" [:map {:closed true}
                         ["id" [:= ["start" "processor"]]]
                         ["input" :string]]}
              {"id" ["processor" "end"]
               "schema" [:map {:closed true}
                         ["id" [:= ["processor" "end"]]]
                         ["result" :string]]}]})

(deftest trail->prompts-test
  (testing "trail->prompts assigns role based on source state"
    (let [;; Audit-style entries - one event per transition
          ;; Entry 0: from "start" (not LLM) → user
          ;; Entry 1: from "processor" (LLM) → assistant
          sample-trail [{:from "start" :to "processor"
                         :event {"id" ["start" "processor"] "input" "test1"}}
                        {:from "processor" :to "end"
                         :event {"id" ["processor" "end"] "result" "done1"}}]
          prompts (trail->prompts {} infra-test-fsm sample-trail)]
      ;; Should have 2 messages
      (is (= 2 (count prompts)))
      ;; First: from "start" (no action) → user
      (is (= "user" (get (nth prompts 0) "role")))
      (is (= {"id" ["start" "processor"] "input" "test1"}
             (get-in (nth prompts 0) ["content" 1])))
      ;; Second: from "processor" (action="llm") → assistant
      (is (= "assistant" (get (nth prompts 1) "role")))
      (is (= {"id" ["processor" "end"] "result" "done1"}
             (get-in (nth prompts 1) ["content" 1])))))

  (testing "trail->prompts handles error entries as user messages"
    (let [sample-trail [{:from "start" :to "processor"
                         :event {"id" ["start" "processor"] "input" "test"}}
                        {:from "processor" :to "end"
                         :event {"id" ["processor" "end"] "result" "bad"}
                         :error {:message "Validation failed" :errors [] :attempt 1}}]
          prompts (trail->prompts {} infra-test-fsm sample-trail)]
      ;; 2 messages: user (from start), user (error feedback)
      (is (= 2 (count prompts)))
      ;; Error entry always becomes user message
      (is (= "user" (get (nth prompts 1) "role")))
      (is (= "Validation failed" (get-in (nth prompts 1) ["content" 1])))))

  (testing "trail->prompts handles empty trail"
    (is (= [] (vec (trail->prompts {} infra-test-fsm [])))
        "Empty trail should return empty"))

  (testing "trail->prompts handles nil trail"
    (is (= [] (vec (trail->prompts {} infra-test-fsm nil)))
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
