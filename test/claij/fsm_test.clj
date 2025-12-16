(ns claij.fsm-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.edn :as edn]
   [clojure.tools.logging :as log]
   [malli.core :as m]
   [malli.registry :as mr]
   [claij.action :refer [def-action action-input-schema action-output-schema]]
   [claij.actions :as actions]
   [claij.malli :refer [valid-fsm? base-registry]]
   [claij.fsm :refer [state-schema resolve-schema start-fsm llm-action trail->prompts
                      build-fsm-registry validate-event last-event llm-configs
                      make-prompts lift chain run-sync fsm-schemas
                      ;; Story #62: state→action schema bridge
                      state-action state-action-input-schema state-action-output-schema]]
   [claij.llm :refer [call]]))

;;------------------------------------------------------------------------------
;; Test Fixtures
;;------------------------------------------------------------------------------

(defn quiet-logging [f]
  (with-redefs [log/log* (fn [& _])]
    (f)))

(use-fixtures :each quiet-logging)

;;==============================================================================
;; Basic FSM Tests
;;==============================================================================

(deftest fsm-test

  (testing "build-fsm-registry"
    (testing "returns a registry that can validate base types"
      (let [registry (build-fsm-registry {} {})]
        (is (m/validate :string "hello" {:registry registry}))
        (is (m/validate :int 42 {:registry registry}))
        (is (not (m/validate :int "not-int" {:registry registry})))))

    (testing "includes FSM schemas when provided"
      (let [fsm {"schemas" {"custom-type" [:string {:min 1}]}}
            registry (build-fsm-registry fsm {})]
        (is (m/validate [:ref "custom-type"] "valid" {:registry registry}))
        (is (not (m/validate [:ref "custom-type"] "" {:registry registry})))))

    (testing "includes context registry when provided"
      (let [context {:malli/registry {"ctx-type" :int}}
            registry (build-fsm-registry {} context)]
        (is (m/validate [:ref "ctx-type"] 42 {:registry registry}))
        (is (not (m/validate [:ref "ctx-type"] "not-int" {:registry registry}))))))

  (testing "validate-event"
    (testing "returns valid for matching schema"
      (let [result (validate-event nil :string "hello")]
        (is (:valid? result))))

    (testing "returns invalid with errors for non-matching schema"
      (let [result (validate-event nil :int "not-an-int")]
        (is (not (:valid? result)))
        (is (some? (:errors result)))))

    (testing "validates map schemas"
      (let [schema [:map ["name" :string] ["age" :int]]
            valid-data {"name" "Alice" "age" 30}
            invalid-data {"name" "Bob" "age" "thirty"}]
        (is (:valid? (validate-event nil schema valid-data)))
        (is (not (:valid? (validate-event nil schema invalid-data)))))))

  (testing "last-event"
    (testing "returns event from last trail entry"
      (let [trail [{:from "a" :to "b" :event {"id" "first"}}
                   {:from "b" :to "c" :event {"id" "second"}}
                   {:from "c" :to "d" :event {"id" "last"}}]]
        (is (= {"id" "last"} (last-event trail)))))

    (testing "returns nil for empty trail"
      (is (nil? (last-event []))))

    (testing "returns nil for nil trail"
      (is (nil? (last-event nil)))))

  (testing "llm-configs"
    (testing "keys are [provider model] tuples with map values"
      (doseq [[provider-model config] llm-configs]
        (is (vector? provider-model) (str "Key should be [provider model] tuple"))
        (is (= 2 (count provider-model)) (str "Key should have exactly 2 elements"))
        (is (string? (first provider-model)) (str "Provider should be string"))
        (is (string? (second provider-model)) (str "Model should be string"))
        (is (map? config) (str "Config for " provider-model " should be a map"))))

    (testing "anthropic config includes system prompts for EDN parsing"
      (let [anthropic-config (get llm-configs ["anthropic" "claude-sonnet-4.5"])]
        (is (some? anthropic-config) "Should have anthropic config")
        (is (vector? (:prompts anthropic-config)) "Should have prompts vector")
        (is (pos? (count (:prompts anthropic-config))) "Should have at least one prompt")))

    (testing "covers expected providers"
      (let [providers (set (map first (keys llm-configs)))]
        (is (contains? providers "anthropic"))
        (is (contains? providers "openai"))
        (is (contains? providers "x-ai"))
        (is (contains? providers "google"))))))

;;==============================================================================
;; Context Threading Tests
;;==============================================================================

(def-action ctx-state-a-action
  "Action A - adds cache to context and transitions to state-b."
  :any
  [_config _fsm _ix _state]
  (fn [context _event _trail handler]
    (handler (assoc context :cache {:tools []})
             {"id" ["state-a" "state-b"]
              "data" "test"})))

(def-action ctx-state-b-action
  "Action B - asserts cache from A, adds more, transitions to end."
  :any
  [_config _fsm _ix _state]
  (fn [context _event _trail handler]
    (assert (= {:tools []} (:cache context)) "Cache should be present from state-a")
    (handler (assoc context :cache {:tools ["bash" "read_file"]})
             {"id" ["state-b" "end"]})))

(def-action ctx-end-action
  "End action - delivers completion promise and verifies final context."
  :any
  [_config _fsm _ix _state]
  (fn [context _event trail _handler]
    (assert (= {:tools ["bash" "read_file"]} (:cache context)) "Cache should have accumulated")
    (when-let [p (:fsm/completion-promise context)]
      (deliver p [context trail]))))

(deftest context-threading-test
  (testing "Context flows through FSM transitions"
    (let [test-fsm {"id" "context-test"
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
          initial-context {:id->action {"action-a" #'ctx-state-a-action
                                        "action-b" #'ctx-state-b-action
                                        "end" #'ctx-end-action}}
          {:keys [submit await stop]} (start-fsm initial-context test-fsm)]

      (submit {"id" ["start" "state-a"] "input" "test-input"})

      (let [result (await 5000)]
        (is (not= result :timeout) "FSM should complete within timeout")
        (when (not= result :timeout)
          (let [[final-context trail] result
                final-event (last-event trail)]
            (is (= {"id" ["state-b" "end"]} final-event) "FSM should return final event"))))

      (stop))))

;;==============================================================================
;; String Schema Reference Tests
;;==============================================================================

(deftest string-schema-reference-test
  (testing "FSM definition accepts string as schema reference"
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
                      {"id" ["llm" "servicing"]
                       "schema" "mcp-request"}
                      {"id" ["servicing" "llm"]
                       "schema" "mcp-response"}
                      {"id" ["llm" "end"]
                       "schema" [:map ["result" :string]]}]}]
      (is (= "string-schema-test" (get fsm-with-string-schema "id")))
      (is (= "mcp-request" (get-in fsm-with-string-schema ["xitions" 1 "schema"])))
      (is (= "mcp-response" (get-in fsm-with-string-schema ["xitions" 2 "schema"])))

      (let [xitions (get fsm-with-string-schema "xitions")
            string-schemas (filter #(string? (get % "schema")) xitions)]
        (is (= 2 (count string-schemas))
            "Should have 2 transitions with string schema references"))))

  (testing "valid-fsm? validates FSM with string schemas"
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
    (let [my-schema-fn (fn [ctx xition]
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
    (let [context {:id->schema {}}
          xition {"id" ["a" "b"]}
          resolved (resolve-schema context xition "unknown-key")]
      (is (= true resolved)
          "Missing schema key should return true (permissive)")))

  (testing "state-schema resolves string schemas in transitions"
    (let [request-schema-fn (fn [ctx xition]
                              [:map {:closed true}
                               ["method" [:= "tools/call"]]])
          context {:id->schema {"mcp-request" request-schema-fn}}
          fsm {"id" "test" "version" 0}
          state {"id" "llm"}
          xitions [{"id" ["llm" "servicing"] "schema" "mcp-request"}
                   {"id" ["llm" "end"] "schema" :string}]
          result (state-schema context fsm state xitions)]
      (is (= :or (first result)) "state-schema should return [:or ...]")
      (is (= 2 (dec (count result))) "Should have 2 alternatives")
      (is (= [:map {:closed true} ["method" [:= "tools/call"]]]
             (second result)))
      (is (= :string (nth result 2))))))

;;==============================================================================
;; Trail Infrastructure Tests
;;==============================================================================

(def ^:private infra-test-fsm
  "Test FSM using Malli schemas for transitions."
  {"id" "infra-test"
   "schema" nil
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
    (let [sample-trail [{:from "start" :to "processor"
                         :event {"id" ["start" "processor"] "input" "test1"}}
                        {:from "processor" :to "end"
                         :event {"id" ["processor" "end"] "result" "done1"}}]
          prompts (trail->prompts {} infra-test-fsm sample-trail)]
      (is (= 2 (count prompts)))
      (is (= "user" (get (nth prompts 0) "role")))
      (is (= {"id" ["start" "processor"] "input" "test1"}
             (get-in (nth prompts 0) ["content" 1])))
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
      (is (= 2 (count prompts)))
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
          mock-handler (fn [& args]
                         (swap! handler-calls conj args)
                         nil)
          fsm infra-test-fsm
          ix (first (filter #(= (get % "id") ["start" "processor"]) (get fsm "xitions")))
          state (first (filter #(= (get % "id") "processor") (get fsm "states")))
          event {"id" ["start" "processor"]
                 "input" "test data"}
          trail []
          context {:test true}
          action-f2 (llm-action {} fsm ix state)]
      (with-redefs [call (fn [_provider _model _prompts success-handler & _opts]
                           (success-handler {"id" ["processor" "end"]
                                             "result" "processed"}))]
        (try
          (action-f2 context event trail mock-handler)
          (is (= 1 (count @handler-calls)) "handler should be called once")
          (is (= 2 (count (first @handler-calls))) "handler should receive 2 args (context, event)")
          (catch clojure.lang.ArityException e
            (is false (str "BUG: handler called with wrong arity - " (.getMessage e)))))))))

(deftest make-prompts-test
  (testing "make-prompts builds prompt messages"
    (let [fsm {"prompts" ["You are a helpful assistant."]}
          ix {"prompts" ["Focus on code quality."]}
          state {"prompts" ["Be concise."]}
          trail []
          prompts (make-prompts fsm ix state trail)]

      (testing "returns sequence with system message"
        (is (seq prompts))
        (is (= "system" (get (first prompts) "role"))))

      (testing "system message contains context prompts from fsm, ix, and state"
        (let [content (get (first prompts) "content")]
          (is (string? content))
          ;; Verify all input prompts are included
          (is (re-find #"You are a helpful assistant" content)
              "FSM prompts should be included")
          (is (re-find #"Focus on code quality" content)
              "IX prompts should be included")
          (is (re-find #"Be concise" content)
              "State prompts should be included")))

      (testing "system message contains EDN format instructions"
        (let [content (get (first prompts) "content")]
          ;; Verify structural elements are present (not specific wording)
          (is (re-find #"(?i)EDN" content)
              "Should mention EDN format")
          (is (re-find #"(?i)schema" content)
              "Should mention schema")))))

  (testing "make-prompts with trail"
    (let [fsm {"prompts" []}
          ix {"prompts" []}
          state {"prompts" []}
          trail [{"role" "user" "content" {"id" "test"}}
                 {"role" "assistant" "content" {"id" "response"}}]
          prompts (make-prompts fsm ix state trail)]

      (testing "includes trail messages after system message"
        (is (>= (count prompts) 3))
        (let [trail-prompts (rest prompts)]
          (is (= 2 (count trail-prompts)))
          (is (= "user" (get (first trail-prompts) "role")))
          (is (= "assistant" (get (second trail-prompts) "role")))))))

  (testing "make-prompts with provider/model includes LLM-specific config"
    (let [fsm {"prompts" []}
          ix {"prompts" []}
          state {"prompts" []}
          trail []
          prompts (make-prompts fsm ix state trail "anthropic" "claude-sonnet-4.5")]

      (testing "returns prompts with LLM config applied"
        (is (seq prompts))
        (is (= "system" (get (first prompts) "role"))))))

  (testing "make-prompts with nil prompts"
    (let [fsm {"prompts" nil}
          ix {"prompts" nil}
          state {"prompts" nil}
          trail []
          prompts (make-prompts fsm ix state trail)]

      (testing "handles nil prompts gracefully"
        (is (seq prompts))
        (is (= "system" (get (first prompts) "role")))))))

;;==============================================================================
;; Lift and Chain Tests
;;==============================================================================

(deftest lift-test
  (testing "lift creates action with proper metadata"
    (let [action (lift identity)]
      (is (fn? action))
      (is (= "lifted" (-> action meta :action/name)))
      (is (= :any (-> action meta :action/config-schema)))
      (is (= :any (-> action meta :action/input-schema)))
      (is (= :any (-> action meta :action/output-schema)))))

  (testing "lift with custom name"
    (let [action (lift identity {:name "my-processor"})]
      (is (= "my-processor" (-> action meta :action/name)))))

  (testing "lifted action transforms event"
    (let [inc-fn (fn [event]
                   (-> event
                       (update "value" inc)
                       (assoc "id" ["process" "end"])))
          action (lift inc-fn)
          f2 (action {} {} {} {})
          results (atom nil)]
      (f2 {:test true}
          {"id" ["start" "process"] "value" 41}
          []
          (fn [ctx event] (reset! results {:ctx ctx :event event})))
      (is (= 42 (get-in @results [:event "value"])))
      (is (= ["process" "end"] (get-in @results [:event "id"])))
      (is (= {:test true} (:ctx @results)) "Context should pass through")))

  (testing "lifted action passes context unchanged"
    (let [action (lift (fn [e] (assoc e "id" ["a" "b"])))
          f2 (action {} {} {} {})
          ctx {:foo "bar" :nested {:data 123}}
          results (atom nil)]
      (f2 ctx {"input" "data"} [] (fn [c e] (reset! results {:ctx c})))
      (is (= ctx (:ctx @results))))))

;;------------------------------------------------------------------------------
;; FSMs for Chain Tests
;;------------------------------------------------------------------------------

(defn make-identity-fsm []
  {"id" "identity"
   "states" [{"id" "process" "action" "pass-through"}
             {"id" "end" "action" "end"}]
   "xitions" [{"id" ["start" "process"]
               "schema" [:map
                         ["id" [:= ["start" "process"]]]
                         ["value" :any]]}
              {"id" ["process" "end"]
               "schema" [:map
                         ["id" [:= ["process" "end"]]]
                         ["value" :any]]}]})

(def pass-through-action
  (lift (fn [event]
          (-> event
              (assoc "id" ["process" "end"])))))

(defn make-inc-fsm []
  {"id" "increment"
   "states" [{"id" "process" "action" "increment"}
             {"id" "end" "action" "end"}]
   "xitions" [{"id" ["start" "process"]
               "schema" [:map
                         ["id" [:= ["start" "process"]]]
                         ["value" :int]]}
              {"id" ["process" "end"]
               "schema" [:map
                         ["id" [:= ["process" "end"]]]
                         ["value" :int]]}]})

(def increment-action
  (lift (fn [event]
          (-> event
              (update "value" inc)
              (assoc "id" ["process" "end"])))
        {:name "increment"}))

(def-action chain-end-action
  "End action - delivers completion to promise."
  :any
  [_config _fsm _ix _state]
  (fn [context _event trail _handler]
    (when-let [p (:fsm/completion-promise context)]
      (deliver p [context trail]))))

(def chain-test-context
  {:id->action {"pass-through" pass-through-action
                "increment" increment-action
                "end" #'chain-end-action}})

(deftest single-fsm-with-lift-test
  (testing "Single FSM with lifted action works"
    (let [fsm (make-identity-fsm)
          result (run-sync fsm chain-test-context
                           {"id" ["start" "process"] "value" 42}
                           5000)]
      (is (not= :timeout result))
      (when (not= :timeout result)
        (let [[_ctx trail] result
              output (last-event trail)]
          (is (= 42 (get output "value")))
          (is (= ["process" "end"] (get output "id")))))))

  (testing "Increment FSM increments value"
    (let [fsm (make-inc-fsm)
          result (run-sync fsm chain-test-context
                           {"id" ["start" "process"] "value" 0}
                           5000)]
      (is (not= :timeout result))
      (when (not= :timeout result)
        (let [[_ctx trail] result
              output (last-event trail)]
          (is (= 1 (get output "value"))))))))

(deftest chain-validation-test
  (testing "chain requires at least 2 FSMs"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"chain requires at least 2 FSMs"
                          (chain chain-test-context (make-inc-fsm))))))

(deftest chain-lifecycle-test
  (testing "chain returns expected interface"
    (let [fsm (make-inc-fsm)
          result (chain chain-test-context fsm fsm)]
      (is (fn? (:start result)))
      (is (fn? (:stop result)))
      (is (fn? (:submit result)))
      (is (fn? (:await result)))))

  (testing "submit before start throws"
    (let [fsm (make-inc-fsm)
          {:keys [submit]} (chain chain-test-context fsm fsm)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Chain not started"
                            (submit {"id" ["start" "process"] "value" 0})))))

  (testing "await before start throws"
    (let [fsm (make-inc-fsm)
          {:keys [await]} (chain chain-test-context fsm fsm)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Chain not started"
                            (await 1000)))))

  (testing "double start throws"
    (let [fsm (make-inc-fsm)
          {:keys [start stop]} (chain chain-test-context fsm fsm)]
      (start)
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Chain already started"
                              (start)))
        (finally
          (stop))))))

(deftest chain-two-fsms-test
  (testing "Chain of 2 increment FSMs adds 2"
    (let [fsm (make-inc-fsm)
          {:keys [start stop submit await]} (chain chain-test-context fsm fsm)]
      (start)
      (submit {"id" ["start" "process"] "value" 0})
      (let [result (await 5000)]
        (is (not= :timeout result) "Chain should complete")
        (when (not= :timeout result)
          (let [[_ctx trail] result
                output (last-event trail)]
            (is (= 2 (get output "value")) "Two increments: 0 -> 1 -> 2"))))
      (stop))))

(deftest chain-three-fsms-test
  (testing "Chain of 3 increment FSMs adds 3"
    (let [fsm (make-inc-fsm)
          {:keys [start stop submit await]} (chain chain-test-context fsm fsm fsm)]
      (start)
      (submit {"id" ["start" "process"] "value" 10})
      (let [result (await 5000)]
        (is (not= :timeout result) "Chain should complete")
        (when (not= :timeout result)
          (let [[_ctx trail] result
                output (last-event trail)]
            (is (= 13 (get output "value")) "Three increments: 10 -> 11 -> 12 -> 13"))))
      (stop))))

(deftest chain-mixed-fsms-test
  (testing "Chain of identity + increment"
    (let [id-fsm (make-identity-fsm)
          inc-fsm (make-inc-fsm)
          {:keys [start stop submit await]} (chain chain-test-context id-fsm inc-fsm)]
      (start)
      (submit {"id" ["start" "process"] "value" 5})
      (let [result (await 5000)]
        (is (not= :timeout result))
        (when (not= :timeout result)
          (let [[_ctx trail] result
                output (last-event trail)]
            (is (= 6 (get output "value")) "Identity preserves, increment adds 1"))))
      (stop))))

(deftest chain-stop-reverse-order-test
  (testing "Stop cleans up resources and can be called safely"
    (let [fsm (make-inc-fsm)
          {:keys [start stop submit await]} (chain chain-test-context fsm fsm)]
      (start)
      (submit {"id" ["start" "process"] "value" 0})
      (let [result (await 5000)]
        (is (not= :timeout result) "Chain should complete before timeout"))
      ;; Verify stop completes without throwing (may return empty vector or nil)
      (let [stop-result (stop)]
        (is (or (nil? stop-result) (coll? stop-result))
            "Stop should return nil or collection on clean shutdown")))))

(deftest chain-timeout-test
  (testing "Chain await respects timeout"
    (let [hanging-fsm {"id" "hanging"
                       "states" [{"id" "process" "action" "hang"}]
                       "xitions" [{"id" ["start" "process"]
                                   "schema" [:map ["id" [:= ["start" "process"]]]]}]}
          hang-action (lift (fn [_] nil) {:name "hang"})
          ctx {:id->action {"hang" hang-action}}
          {:keys [start stop submit await]} (chain ctx hanging-fsm hanging-fsm)]
      (start)
      (submit {"id" ["start" "process"]})
      (let [result (await 100)]
        (is (= :timeout result) "Should timeout waiting for completion"))
      (stop))))

;;==============================================================================
;; Omit Flag Tests
;;==============================================================================

(def omit-test-fsm
  {"states"
   [{"id" "start" "action" "start-action"}
    {"id" "middle" "action" "middle-action"}
    {"id" "end" "action" "end-action"}
    {"id" "final" "action" "final-action"}]
   "xitions"
   [{"id" ["start" "middle"]
     "omit" true
     "schema" [:map {:closed true}
               ["id" [:= ["start" "middle"]]]
               ["data" :string]]}
    {"id" ["middle" "end"]
     "schema" [:map {:closed true}
               ["id" [:= ["middle" "end"]]]
               ["result" :string]]}
    {"id" ["end" "final"]
     "schema" [:map {:closed true}
               ["id" [:= ["end" "final"]]]
               ["done" :boolean]]}]})

(def captured-trail-at-middle (atom nil))
(def captured-trail-at-end (atom nil))

(def-action omit-start-action
  "Start action - transitions to middle state."
  :any
  [_config _fsm _ix _state]
  (fn [context _event _trail handler]
    (handler context {"id" ["start" "middle"] "data" "going to middle"})))

(def-action omit-middle-action
  "Middle action - captures trail and transitions to end."
  :any
  [_config _fsm _ix _state]
  (fn [context _event trail handler]
    (reset! captured-trail-at-middle trail)
    (handler context {"id" ["middle" "end"] "result" "finished"})))

(def-action omit-end-action
  "End action - captures trail and transitions to final."
  :any
  [_config _fsm _ix _state]
  (fn [context _event trail handler]
    (reset! captured-trail-at-end trail)
    (handler context {"id" ["end" "final"] "done" true})))

(def-action omit-final-action
  "Final action - delivers completion promise."
  :any
  [_config _fsm _ix _state]
  (fn [context _event trail _handler]
    (when-let [p (:fsm/completion-promise context)]
      (deliver p [context trail]))))

(def omit-test-actions
  {"start-action" #'omit-start-action
   "middle-action" #'omit-middle-action
   "end-action" #'omit-end-action
   "final-action" #'omit-final-action})

(defn trail-contains-event-id? [trail event-id]
  (some (fn [{:keys [event]}]
          (= event-id (get event "id")))
        trail))

(deftest omit-test
  (testing "omit=true transition excluded from trail"
    (reset! captured-trail-at-middle nil)
    (reset! captured-trail-at-end nil)
    (let [{:keys [submit await]} (start-fsm {:id->action omit-test-actions} omit-test-fsm)]
      (submit {"id" ["start" "middle"] "data" "initial"})
      (let [[_ctx final-trail] (await 5000)]
        (is (not (trail-contains-event-id? @captured-trail-at-middle ["start" "middle"]))
            "Trail at middle should NOT contain omitted [start middle]")
        (is (not (trail-contains-event-id? @captured-trail-at-end ["start" "middle"]))
            "Trail at end should NOT contain omitted [start middle]")
        (is (not (trail-contains-event-id? final-trail ["start" "middle"]))
            "Final trail should NOT contain omitted [start middle]"))))

  (testing "non-omit transition appears when it becomes input to subsequent step"
    (reset! captured-trail-at-middle nil)
    (reset! captured-trail-at-end nil)
    (let [{:keys [submit await]} (start-fsm {:id->action omit-test-actions} omit-test-fsm)]
      (submit {"id" ["start" "middle"] "data" "initial"})
      (let [[_ctx final-trail] (await 5000)]
        (is (not (trail-contains-event-id? @captured-trail-at-middle ["middle" "end"]))
            "Trail at middle should not yet contain [middle end]")
        (is (trail-contains-event-id? final-trail ["middle" "end"])
            "Final trail should contain [middle end] as input to end->final step")
        (is (trail-contains-event-id? final-trail ["end" "final"])
            "Final trail should contain [end final]")))))

;;==============================================================================
;; State Schema Tests
;;==============================================================================

(def static-schema-fsm
  {"schema" {"$$id" "static-test-fsm" "type" "object"}
   "states"
   [{"id" "choice" "action" "choice-action"}
    {"id" "option-a" "action" "a-action"}
    {"id" "option-b" "action" "b-action"}
    {"id" "option-c" "action" "c-action"}]
   "xitions"
   [{"id" ["choice" "option-a"]
     "schema" [:map {:closed true}
               ["id" [:= ["choice" "option-a"]]]
               ["value" :string]]}
    {"id" ["choice" "option-b"]
     "schema" [:map {:closed true}
               ["id" [:= ["choice" "option-b"]]]
               ["count" :int]]}
    {"id" ["choice" "option-c"]
     "schema" [:map {:closed true}
               ["id" [:= ["choice" "option-c"]]]
               ["flag" :boolean]]}]})

(def dynamic-schema-fsm
  {"schema" {"$$id" "dynamic-test-fsm" "type" "object"}
   "states"
   [{"id" "router" "action" "router-action"}
    {"id" "handler-x" "action" "x-action"}
    {"id" "handler-y" "action" "y-action"}]
   "xitions"
   [{"id" ["router" "handler-x"]
     "schema" "dynamic-schema-x"}
    {"id" ["router" "handler-y"]
     "schema" "dynamic-schema-y"}]})

(defn resolve-dynamic-x [_context _xition]
  [:map {:closed true}
   ["id" [:= ["router" "handler-x"]]]
   ["x-data" [:vector :any]]])

(defn resolve-dynamic-y [_context _xition]
  [:map {:closed true}
   ["id" [:= ["router" "handler-y"]]]
   ["y-data" :double]])

(def dynamic-context
  {:id->schema {"dynamic-schema-x" resolve-dynamic-x
                "dynamic-schema-y" resolve-dynamic-y}})

(def input-output-schema-fsm
  {"id" "io-schema-test"
   "states"
   [{"id" "processor" "action" "process-action"}
    {"id" "validator" "action" "validate-action"}
    {"id" "end" "action" "end"}]
   "xitions"
   [{"id" ["start" "processor"]
     "schema" [:map {:closed true}
               ["id" [:= ["start" "processor"]]]
               ["data" :string]]}
    {"id" ["start" "validator"]
     "schema" [:map {:closed true}
               ["id" [:= ["start" "validator"]]]
               ["payload" :int]]}
    {"id" ["processor" "validator"]
     "schema" [:map {:closed true}
               ["id" [:= ["processor" "validator"]]]
               ["processed" :boolean]]}
    {"id" ["processor" "end"]
     "schema" [:map {:closed true}
               ["id" [:= ["processor" "end"]]]
               ["result" :string]]}
    {"id" ["validator" "end"]
     "schema" [:map {:closed true}
               ["id" [:= ["validator" "end"]]]
               ["valid" :boolean]
               ["errors" {:optional true} [:vector :string]]]}]})

(deftest state-schema-test
  (testing "static schemas produce :or with all output transitions"
    (let [choice-state {"id" "choice" "action" "choice-action"}
          output-xitions (get static-schema-fsm "xitions")
          result (state-schema {} static-schema-fsm choice-state output-xitions)]

      (is (= :or (first result)) "Result should be [:or ...]")
      (is (= 3 (dec (count result))) ":or should have 3 alternatives")

      (let [schemas (rest result)
            extract-id (fn [schema]
                         (->> schema
                              (filter vector?)
                              (filter #(= "id" (first %)))
                              first
                              second
                              second))
            ids (map extract-id schemas)]
        (is (some #(= ["choice" "option-a"] %) ids) "Should include option-a schema")
        (is (some #(= ["choice" "option-b"] %) ids) "Should include option-b schema")
        (is (some #(= ["choice" "option-c"] %) ids) "Should include option-c schema"))))

  (testing "dynamic schemas produce :or with resolved schemas"
    (let [router-state {"id" "router" "action" "router-action"}
          output-xitions (get dynamic-schema-fsm "xitions")
          result (state-schema dynamic-context dynamic-schema-fsm router-state output-xitions)]

      (is (= :or (first result)) "Result should be [:or ...]")
      (is (= 2 (dec (count result))) ":or should have 2 alternatives")

      (let [schemas (rest result)]
        (is (every? vector? schemas) "All schemas should be resolved vectors, not strings")

        (let [extract-id (fn [schema]
                           (->> schema
                                (filter vector?)
                                (filter #(= "id" (first %)))
                                first
                                second
                                second))
              ids (map extract-id schemas)]
          (is (some #(= ["router" "handler-x"] %) ids) "Should include handler-x schema")
          (is (some #(= ["router" "handler-y"] %) ids) "Should include handler-y schema"))

        (let [has-field? (fn [schema field-name]
                           (some #(and (vector? %) (= field-name (first %))) schema))
              x-schema (first (filter #(has-field? % "x-data") schemas))
              y-schema (first (filter #(has-field? % "y-data") schemas))]
          (is (some? x-schema) "x schema should have x-data field")
          (is (some? y-schema) "y schema should have y-data field")))))

  (testing "empty output transitions produce empty :or"
    (let [result (state-schema {} static-schema-fsm {"id" "terminal"} [])]
      (is (= [:or] result) "Empty transitions should produce [:or]"))))

(deftest start-fsm-schema-test
  (testing "start-fsm returns map with :input-schema and :output-schema"
    (let [pass-action (fn [_config _fsm _ix _state]
                        (fn [context event _trail handler]
                          (handler context event)))
          end-action (fn [_config _fsm _ix _state]
                       (fn [context _event trail _handler]
                         (when-let [p (:fsm/completion-promise context)]
                           (deliver p [context trail]))))
          context {:id->action {"process-action" pass-action
                                "validate-action" pass-action
                                "end" end-action}}
          result (start-fsm context input-output-schema-fsm)]

      (testing "returns a map"
        (is (map? result) "start-fsm should return a map"))

      (testing "map contains expected keys"
        (is (contains? result :submit) "should have :submit")
        (is (contains? result :await) "should have :await")
        (is (contains? result :stop) "should have :stop")
        (is (contains? result :input-schema) "should have :input-schema")
        (is (contains? result :output-schema) "should have :output-schema"))

      (testing ":input-schema is :or of all transitions FROM start"
        (let [input-schema (:input-schema result)]
          (is (= :or (first input-schema)) "input-schema should be [:or ...]")
          (is (= 2 (dec (count input-schema))) "should have 2 entry transitions")

          (let [extract-id (fn [schema]
                             (->> schema
                                  (filter vector?)
                                  (filter #(= "id" (first %)))
                                  first
                                  second
                                  second))
                ids (set (map extract-id (rest input-schema)))]
            (is (contains? ids ["start" "processor"]) "should include start->processor")
            (is (contains? ids ["start" "validator"]) "should include start->validator"))))

      (testing ":output-schema is :or of all transitions TO end"
        (let [output-schema (:output-schema result)]
          (is (= :or (first output-schema)) "output-schema should be [:or ...]")
          (is (= 2 (dec (count output-schema))) "should have 2 exit transitions")

          (let [extract-id (fn [schema]
                             (->> schema
                                  (filter vector?)
                                  (filter #(= "id" (first %)))
                                  first
                                  second
                                  second))
                ids (set (map extract-id (rest output-schema)))]
            (is (contains? ids ["processor" "end"]) "should include processor->end")
            (is (contains? ids ["validator" "end"]) "should include validator->end"))))

      ((:stop result)))))

(deftest fsm-schemas-test
  (testing "fsm-schemas extracts schemas without starting FSM"
    (let [result (fsm-schemas {} input-output-schema-fsm)]

      (testing "returns a map with expected keys"
        (is (map? result))
        (is (contains? result :input-schema))
        (is (contains? result :output-schema)))

      (testing "does NOT contain runtime keys"
        (is (not (contains? result :submit)) "should not have :submit")
        (is (not (contains? result :await)) "should not have :await")
        (is (not (contains? result :stop)) "should not have :stop"))))

  (testing "fsm-schemas with single-arity call (no context)"
    (let [simple-fsm {"id" "simple"
                      "states" [{"id" "work" "action" "work-action"}
                                {"id" "end" "action" "end"}]
                      "xitions" [{"id" ["start" "work"]
                                  "schema" [:map ["id" [:= ["start" "work"]]]
                                            ["input" :string]]}
                                 {"id" ["work" "end"]
                                  "schema" [:map ["id" [:= ["work" "end"]]]
                                            ["output" :int]]}]}
          result (fsm-schemas simple-fsm)]

      (testing "works without explicit context"
        (is (= :or (first (:input-schema result))))
        (is (= :or (first (:output-schema result))))))))

;;==============================================================================
;; Minimal FSM Integration Test (Long-Running)
;;==============================================================================

(def minimal-schemas
  {"input" [:map {:closed true}
            ["question" :string]]
   "output" [:map {:closed true}
             ["id" [:= ["responder" "end"]]]
             ["answer" :string]
             ["agree" :boolean]]})

(def minimal-registry
  (mr/composite-registry base-registry minimal-schemas))

(def minimal-fsm
  {"id" "minimal-test"
   "schemas" minimal-schemas
   "prompts" []
   "states"
   [{"id" "responder"
     "action" "llm"
     "prompts" []}
    {"id" "end"
     "action" "end"}]
   "xitions"
   [{"id" ["start" "responder"]
     "schema" [:ref "input"]}
    {"id" ["responder" "end"]
     "schema" [:ref "output"]}]})

(defn test-minimal-fsm []
  (let [actions {"llm" #'claij.fsm/llm-action "end" #'actions/end-action}
        context {:id->action actions
                 :llm/provider "anthropic"
                 :llm/model "claude-sonnet-4.5"}
        input {"question" "Is 2 + 2 = 4?"}
        result (run-sync minimal-fsm context input 60000)]
    (if (= result :timeout)
      {:success false :error "Timeout"}
      (let [[_ctx trail] result
            last-evt (last-event trail)]
        (if (m/validate [:ref "output"] last-evt {:registry minimal-registry})
          {:success true :response last-evt}
          {:success false :response last-evt :error "Validation failed"})))))

(deftest ^:long-running minimal-fsm-test
  (testing "Minimal FSM works with POC-identical schemas"
    (let [{:keys [success response error]} (test-minimal-fsm)]
      (is success (str "Should succeed. Error: " error " Response: " response))
      (when success
        (is (= ["responder" "end"] (get response "id"))
            "Should have correct transition id")
        (is (true? (get response "agree"))
            "Should agree that 2+2=4")))))

;;------------------------------------------------------------------------------
;; Story #62 Phase 1: State→Action Schema Bridge Tests
;;------------------------------------------------------------------------------

;; Define test actions with explicit input/output schemas
(def-action typed-processor-action
  "Action with explicit input/output schemas"
  {:config :any
   :input [:map ["value" :int]]
   :output [:map ["result" :int] ["id" [:tuple :string :string]]]}
  [_config _fsm _ix _state]
  (fn [context event _trail handler]
    (let [result {:result (* 2 (get event "value"))
                  :id ["processor" "end"]}]
      (handler context result))))

(def-action untyped-action
  "Action with no input/output schemas (legacy form)"
  [:map] ; config-only schema
  [_config _fsm _ix _state]
  (fn [context event _trail handler]
    (handler context event)))

(deftest state-action-schema-bridge-test
  (testing "state-action returns action from context"
    (let [context {:id->action {"typed" #'typed-processor-action
                                "untyped" #'untyped-action}}
          state-with-action {"id" "processor" "action" "typed"}
          state-no-action {"id" "passthrough"}]

      (is (= #'typed-processor-action (state-action context state-with-action))
          "Should return the action var")
      (is (nil? (state-action context state-no-action))
          "Should return nil when state has no action")
      (is (nil? (state-action context {"id" "x" "action" "missing"}))
          "Should return nil when action not in context")))

  (testing "state-action-input-schema extracts input schema"
    (let [context {:id->action {"typed" #'typed-processor-action
                                "untyped" #'untyped-action}}]

      ;; Typed action has explicit input schema
      (is (= [:map ["value" :int]]
             (state-action-input-schema context {"id" "x" "action" "typed"}))
          "Should return declared input schema")

      ;; Untyped action defaults to :any
      (is (= :any
             (state-action-input-schema context {"id" "x" "action" "untyped"}))
          "Should return :any for legacy actions")

      ;; Missing action returns :any
      (is (= :any
             (state-action-input-schema context {"id" "x" "action" "missing"}))
          "Should return :any when action not found")

      ;; No action key returns :any
      (is (= :any
             (state-action-input-schema context {"id" "x"}))
          "Should return :any when state has no action")))

  (testing "state-action-output-schema extracts output schema"
    (let [context {:id->action {"typed" #'typed-processor-action
                                "untyped" #'untyped-action}}]

      ;; Typed action has explicit output schema
      (is (= [:map ["result" :int] ["id" [:tuple :string :string]]]
             (state-action-output-schema context {"id" "x" "action" "typed"}))
          "Should return declared output schema")

      ;; Untyped action defaults to :any
      (is (= :any
             (state-action-output-schema context {"id" "x" "action" "untyped"}))
          "Should return :any for legacy actions")

      ;; Missing action returns :any
      (is (= :any
             (state-action-output-schema context {"id" "x" "action" "missing"}))
          "Should return :any when action not found")))

  (testing "same code path works at all three times"
    ;; The functions work identically regardless of when called
    ;; because they only depend on what's in context
    (let [config-ctx {} ; Empty context at config time
          start-ctx {:id->action {"typed" #'typed-processor-action}} ; Populated at start
          runtime-ctx {:id->action {"typed" #'typed-processor-action}
                       :extra "runtime-data"} ; Additional runtime state
          state {"id" "x" "action" "typed"}]

      ;; Config time: no actions yet
      (is (= :any (state-action-input-schema config-ctx state))
          "Config time returns :any (no actions)")

      ;; Start time: actions available
      (is (= [:map ["value" :int]] (state-action-input-schema start-ctx state))
          "Start time returns declared schema")

      ;; Runtime: same result (extra context doesn't affect lookup)
      (is (= [:map ["value" :int]] (state-action-input-schema runtime-ctx state))
          "Runtime returns declared schema"))))
