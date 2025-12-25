(ns claij.fsm-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.edn :as edn]
   [clojure.tools.logging :as log]
   [claij.schema :as schema]
   [claij.action :refer [def-action action-input-schema action-output-schema]]
   [claij.actions :as actions]
   [claij.fsm :refer [state-schema resolve-schema start-fsm llm-action trail->prompts
                      build-fsm-registry validate-event last-event llm-configs
                      make-prompts lift chain run-sync fsm-schemas
                      ;; Story #62: state→action schema bridge
                      state-action state-action-input-schema state-action-output-schema]]
   [claij.llm :refer [call]]))

;;------------------------------------------------------------------------------
;; Test Helpers (using claij.schema/JSON Schema)
;;------------------------------------------------------------------------------

(def base-registry
  "Test registry with JSON Schema definitions."
  {})

(defn valid-fsm?
  "Basic FSM structure validation for tests."
  [fsm]
  (and (map? fsm)
       (string? (get fsm "id"))
       (vector? (get fsm "states"))
       (vector? (get fsm "xitions"))))

(defn schema-valid?
  "Validate data against a JSON Schema, optionally with a registry for $ref resolution."
  ([schema data]
   (schema-valid? schema data {}))
  ([schema data registry]
   (:valid? (schema/validate schema data registry))))

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
        (is (schema-valid? {"type" "string"} "hello" registry))
        (is (schema-valid? {"type" "integer"} 42 registry))
        (is (not (schema-valid? {"type" "integer"} "not-int" registry)))))

    (testing "includes FSM schemas when provided"
      (let [fsm {"schemas" {"custom-type" {"type" "string" "minLength" 1}}}
            registry (build-fsm-registry fsm {})]
        (is (schema-valid? {"$ref" "#/$defs/custom-type"} "valid" registry))
        (is (not (schema-valid? {"$ref" "#/$defs/custom-type"} "" registry)))))

    (testing "includes context registry when provided"
      (let [context {:schema/defs {"ctx-type" {"type" "integer"}}}
            registry (build-fsm-registry {} context)]
        (is (schema-valid? {"$ref" "#/$defs/ctx-type"} 42 registry))
        (is (not (schema-valid? {"$ref" "#/$defs/ctx-type"} "not-int" registry))))))

  (testing "validate-event"
    (testing "returns valid for matching schema"
      (let [result (validate-event nil {"type" "string"} "hello")]
        (is (:valid? result))))

    (testing "returns invalid with errors for non-matching schema"
      (let [result (validate-event nil {"type" "integer"} "not-an-int")]
        (is (not (:valid? result)))
        (is (some? (:errors result)))))

    (testing "validates map schemas"
      (let [schema {"type" "object"
                    "required" ["name" "age"]
                    "properties" {"name" {"type" "string"}
                                  "age" {"type" "integer"}}}
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

  (testing "llm-configs structure"
    (is (map? llm-configs))
    (doseq [[service-model config] llm-configs]
      (is (vector? service-model) (str "Key should be [service model] tuple"))
      (is (= 2 (count service-model)) (str "Key should have exactly 2 elements"))
      (is (string? (first service-model)) (str "Service should be string"))
      (is (string? (second service-model)) (str "Model should be string"))
      (is (map? config) (str "Config for " service-model " should be a map"))))

  (testing "anthropic config includes system prompts for EDN parsing"
    (let [anthropic-config (get llm-configs ["anthropic" "claude-sonnet-4-20250514"])]
      (is (some? anthropic-config) "Should have anthropic config")
      (is (vector? (:prompts anthropic-config)) "Should have prompts vector")
      (is (pos? (count (:prompts anthropic-config))) "Should have at least one prompt")))

  (testing "covers expected services"
    (let [services (set (map first (keys llm-configs)))]
      (is (contains? services "anthropic"))
      (is (contains? services "google"))
      ;; These may or may not be present depending on config
      (is (or (contains? services "openrouter")
              (contains? services "xai")
              (contains? services "ollama:local"))))))

;;==============================================================================
;; Context Threading Tests
;;==============================================================================

(def-action ctx-state-a-action
  "Action A - adds cache to context and transitions to state-b."
  true
  [_config _fsm _ix _state]
  (fn [context _event _trail handler]
    (handler (assoc context :cache {:tools []})
             {"id" ["state-a" "state-b"]
              "data" "test"})))

(def-action ctx-state-b-action
  "Action B - asserts cache from A, adds more, transitions to end."
  true
  [_config _fsm _ix _state]
  (fn [context _event _trail handler]
    (assert (= {:tools []} (:cache context)) "Cache should be present from state-a")
    (handler (assoc context :cache {:tools ["bash" "read_file"]})
             {"id" ["state-b" "end"]})))

(def-action ctx-end-action
  "End action - delivers completion promise and verifies final context."
  true
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
                                "schema" {"type" "object"
                                          "additionalProperties" false
                                          "required" ["id" "input"]
                                          "properties" {"id" {"const" ["start" "state-a"]}
                                                        "input" {"type" "string"}}}}
                               {"id" ["state-a" "state-b"]
                                "schema" {"type" "object"
                                          "additionalProperties" false
                                          "required" ["id" "data"]
                                          "properties" {"id" {"const" ["state-a" "state-b"]}
                                                        "data" {"type" "string"}}}}
                               {"id" ["state-b" "end"]
                                "schema" {"type" "object"
                                          "additionalProperties" false
                                          "required" ["id"]
                                          "properties" {"id" {"const" ["state-b" "end"]}}}}]}
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
                       "schema" {"type" "string"}}
                      {"id" ["llm" "servicing"]
                       "schema" "mcp-request"}
                      {"id" ["servicing" "llm"]
                       "schema" "mcp-response"}
                      {"id" ["llm" "end"]
                       "schema" {"type" "object"
                                 "required" ["result"]
                                 "properties" {"result" {"type" "string"}}}}]}]
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
          schema {"type" "object" "properties" {"name" {"type" "string"}}}]
      (is (= schema (resolve-schema context xition schema)))))

  (testing "resolve-schema with string key looks up and calls schema function"
    (let [my-schema-fn (fn [ctx xition]
                         {"type" "object"
                          "additionalProperties" false
                          "required" ["tool"]
                          "properties" {"tool" {"const" (get ctx :selected-tool)}}})
          context {:id->schema {"my-schema" my-schema-fn}
                   :selected-tool "clojure_eval"}
          xition {"id" ["llm" "servicing"]}
          resolved (resolve-schema context xition "my-schema")]
      (is (= {"type" "object"
              "additionalProperties" false
              "required" ["tool"]
              "properties" {"tool" {"const" "clojure_eval"}}}
             resolved))))

  (testing "resolve-schema with missing key returns true and logs warning"
    (let [context {:id->schema {}}
          xition {"id" ["a" "b"]}
          resolved (resolve-schema context xition "unknown-key")]
      (is (= true resolved)
          "Missing schema key should return true (permissive)")))

  (testing "state-schema resolves string schemas in transitions"
    (let [request-schema-fn (fn [ctx xition]
                              {"type" "object"
                               "additionalProperties" false
                               "required" ["method"]
                               "properties" {"method" {"const" "tools/call"}}})
          context {:id->schema {"mcp-request" request-schema-fn}}
          fsm {"id" "test" "version" 0}
          state {"id" "llm"}
          xitions [{"id" ["llm" "servicing"] "schema" "mcp-request"}
                   {"id" ["llm" "end"] "schema" {"type" "string"}}]
          result (state-schema context fsm state xitions)]
      (is (contains? result "oneOf") "state-schema should return {\"oneOf\": [...]}")
      (is (= 2 (count (get result "oneOf"))) "Should have 2 alternatives")
      (is (= {"type" "object"
              "additionalProperties" false
              "required" ["method"]
              "properties" {"method" {"const" "tools/call"}}}
             (first (get result "oneOf"))))
      (is (= {"type" "string"} (second (get result "oneOf")))))))

;;==============================================================================
;; Trail Infrastructure Tests
;;==============================================================================

(def ^:private infra-test-fsm
  "Test FSM using JSON Schema for transitions."
  {"id" "infra-test"
   "schema" nil
   "states" [{"id" "processor" "action" "llm"}
             {"id" "end" "action" "end"}]
   "xitions" [{"id" ["start" "processor"]
               "schema" {"type" "object"
                         "additionalProperties" false
                         "required" ["id" "input"]
                         "properties" {"id" {"const" ["start" "processor"]}
                                       "input" {"type" "string"}}}}
              {"id" ["processor" "end"]
               "schema" {"type" "object"
                         "additionalProperties" false
                         "required" ["id" "result"]
                         "properties" {"id" {"const" ["processor" "end"]}
                                       "result" {"type" "string"}}}}]})

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
             (get (nth prompts 1) "content")))))

  (testing "trail->prompts handles error entries as user messages"
    (let [sample-trail [{:from "start" :to "processor"
                         :event {"id" ["start" "processor"] "input" "test"}}
                        {:from "processor" :to "end"
                         :event {"id" ["processor" "end"] "result" "bad"}
                         :error {:message "Validation failed" :errors [] :attempt 1}}]
          prompts (trail->prompts {} infra-test-fsm sample-trail)]
      (is (= 2 (count prompts)))
      (is (= "user" (get (nth prompts 1) "role")))
      (is (= "Validation failed" (get-in (nth prompts 1) ["content" 0])))))

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

      (testing "system message contains JSON format instructions"
        (let [content (get (first prompts) "content")]
          ;; Verify structural elements are present (not specific wording)
          (is (re-find #"(?i)JSON" content)
              "Should mention JSON format")
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
      (is (= true (-> action meta :action/config-schema)))
      (is (= true (-> action meta :action/input-schema)))
      (is (= true (-> action meta :action/output-schema)))))

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
               "schema" {"type" "object"
                         "required" ["id" "value"]
                         "properties" {"id" {"const" ["start" "process"]}
                                       "value" {}}}}
              {"id" ["process" "end"]
               "schema" {"type" "object"
                         "required" ["id" "value"]
                         "properties" {"id" {"const" ["process" "end"]}
                                       "value" {}}}}]})

(def pass-through-action
  (lift (fn [event]
          (-> event
              (assoc "id" ["process" "end"])))))

(defn make-inc-fsm []
  {"id" "increment"
   "states" [{"id" "process" "action" "increment"}
             {"id" "end" "action" "end"}]
   "xitions" [{"id" ["start" "process"]
               "schema" {"type" "object"
                         "required" ["id" "value"]
                         "properties" {"id" {"const" ["start" "process"]}
                                       "value" {"type" "integer"}}}}
              {"id" ["process" "end"]
               "schema" {"type" "object"
                         "required" ["id" "value"]
                         "properties" {"id" {"const" ["process" "end"]}
                                       "value" {"type" "integer"}}}}]})

(def increment-action
  (lift (fn [event]
          (-> event
              (update "value" inc)
              (assoc "id" ["process" "end"])))
        {:name "increment"}))

(def-action chain-end-action
  "End action - delivers completion to promise."
  true
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
                                   "schema" {"type" "object"
                                             "required" ["id"]
                                             "properties" {"id" {"const" ["start" "process"]}}}}]}
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
     "schema" {"type" "object"
               "additionalProperties" false
               "required" ["id" "data"]
               "properties" {"id" {"const" ["start" "middle"]}
                             "data" {"type" "string"}}}}
    {"id" ["middle" "end"]
     "schema" {"type" "object"
               "additionalProperties" false
               "required" ["id" "result"]
               "properties" {"id" {"const" ["middle" "end"]}
                             "result" {"type" "string"}}}}
    {"id" ["end" "final"]
     "schema" {"type" "object"
               "additionalProperties" false
               "required" ["id" "done"]
               "properties" {"id" {"const" ["end" "final"]}
                             "done" {"type" "boolean"}}}}]})

(def captured-trail-at-middle (atom nil))
(def captured-trail-at-end (atom nil))

(def-action omit-start-action
  "Start action - transitions to middle state."
  true
  [_config _fsm _ix _state]
  (fn [context _event _trail handler]
    (handler context {"id" ["start" "middle"] "data" "going to middle"})))

(def-action omit-middle-action
  "Middle action - captures trail and transitions to end."
  true
  [_config _fsm _ix _state]
  (fn [context _event trail handler]
    (reset! captured-trail-at-middle trail)
    (handler context {"id" ["middle" "end"] "result" "finished"})))

(def-action omit-end-action
  "End action - captures trail and transitions to final."
  true
  [_config _fsm _ix _state]
  (fn [context _event trail handler]
    (reset! captured-trail-at-end trail)
    (handler context {"id" ["end" "final"] "done" true})))

(def-action omit-final-action
  "Final action - delivers completion promise."
  true
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
     "schema" {"type" "object"
               "additionalProperties" false
               "required" ["id" "value"]
               "properties" {"id" {"const" ["choice" "option-a"]}
                             "value" {"type" "string"}}}}
    {"id" ["choice" "option-b"]
     "schema" {"type" "object"
               "additionalProperties" false
               "required" ["id" "count"]
               "properties" {"id" {"const" ["choice" "option-b"]}
                             "count" {"type" "integer"}}}}
    {"id" ["choice" "option-c"]
     "schema" {"type" "object"
               "additionalProperties" false
               "required" ["id" "flag"]
               "properties" {"id" {"const" ["choice" "option-c"]}
                             "flag" {"type" "boolean"}}}}]})

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
  {"type" "object"
   "additionalProperties" false
   "required" ["id" "x-data"]
   "properties" {"id" {"const" ["router" "handler-x"]}
                 "x-data" {"type" "array"}}})

(defn resolve-dynamic-y [_context _xition]
  {"type" "object"
   "additionalProperties" false
   "required" ["id" "y-data"]
   "properties" {"id" {"const" ["router" "handler-y"]}
                 "y-data" {"type" "number"}}})

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
     "schema" {"type" "object"
               "additionalProperties" false
               "required" ["id" "data"]
               "properties" {"id" {"const" ["start" "processor"]}
                             "data" {"type" "string"}}}}
    {"id" ["start" "validator"]
     "schema" {"type" "object"
               "additionalProperties" false
               "required" ["id" "payload"]
               "properties" {"id" {"const" ["start" "validator"]}
                             "payload" {"type" "integer"}}}}
    {"id" ["processor" "validator"]
     "schema" {"type" "object"
               "additionalProperties" false
               "required" ["id" "processed"]
               "properties" {"id" {"const" ["processor" "validator"]}
                             "processed" {"type" "boolean"}}}}
    {"id" ["processor" "end"]
     "schema" {"type" "object"
               "additionalProperties" false
               "required" ["id" "result"]
               "properties" {"id" {"const" ["processor" "end"]}
                             "result" {"type" "string"}}}}
    {"id" ["validator" "end"]
     "schema" {"type" "object"
               "additionalProperties" false
               "required" ["id" "valid"]
               "properties" {"id" {"const" ["validator" "end"]}
                             "valid" {"type" "boolean"}
                             "errors" {"type" "array" "items" {"type" "string"}}}}}]})

(deftest state-schema-test
  (testing "static schemas produce oneOf with all output transitions"
    (let [choice-state {"id" "choice" "action" "choice-action"}
          output-xitions (get static-schema-fsm "xitions")
          result (state-schema {} static-schema-fsm choice-state output-xitions)]

      (is (contains? result "oneOf") "Result should be {\"oneOf\": [...]}")
      (is (= 3 (count (get result "oneOf"))) "oneOf should have 3 alternatives")

      (let [schemas (get result "oneOf")
            extract-id (fn [schema]
                         (get-in schema ["properties" "id" "const"]))
            ids (map extract-id schemas)]
        (is (some #(= ["choice" "option-a"] %) ids) "Should include option-a schema")
        (is (some #(= ["choice" "option-b"] %) ids) "Should include option-b schema")
        (is (some #(= ["choice" "option-c"] %) ids) "Should include option-c schema"))))

  (testing "dynamic schemas produce oneOf with resolved schemas"
    (let [router-state {"id" "router" "action" "router-action"}
          output-xitions (get dynamic-schema-fsm "xitions")
          result (state-schema dynamic-context dynamic-schema-fsm router-state output-xitions)]

      (is (contains? result "oneOf") "Result should be {\"oneOf\": [...]}")
      (is (= 2 (count (get result "oneOf"))) "oneOf should have 2 alternatives")

      (let [schemas (get result "oneOf")]
        (is (every? map? schemas) "All schemas should be resolved maps, not strings")

        (let [extract-id (fn [schema]
                           (get-in schema ["properties" "id" "const"]))
              ids (map extract-id schemas)]
          (is (some #(= ["router" "handler-x"] %) ids) "Should include handler-x schema")
          (is (some #(= ["router" "handler-y"] %) ids) "Should include handler-y schema"))

        (let [has-field? (fn [schema field-name]
                           (contains? (get schema "properties") field-name))
              x-schema (first (filter #(has-field? % "x-data") schemas))
              y-schema (first (filter #(has-field? % "y-data") schemas))]
          (is (some? x-schema) "x schema should have x-data field")
          (is (some? y-schema) "y schema should have y-data field")))))

  (testing "empty output transitions produce empty oneOf"
    (let [result (state-schema {} static-schema-fsm {"id" "terminal"} [])]
      (is (= {"oneOf" []} result) "Empty transitions should produce {\"oneOf\": []}"))))

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

      (testing ":input-schema is oneOf of all transitions FROM start"
        (let [input-schema (:input-schema result)]
          (is (contains? input-schema "oneOf") "input-schema should be {\"oneOf\": [...]}")
          (is (= 2 (count (get input-schema "oneOf"))) "should have 2 entry transitions")

          (let [extract-id (fn [schema]
                             (get-in schema ["properties" "id" "const"]))
                ids (set (map extract-id (get input-schema "oneOf")))]
            (is (contains? ids ["start" "processor"]) "should include start->processor")
            (is (contains? ids ["start" "validator"]) "should include start->validator"))))

      (testing ":output-schema is oneOf of all transitions TO end"
        (let [output-schema (:output-schema result)]
          (is (contains? output-schema "oneOf") "output-schema should be {\"oneOf\": [...]}")
          (is (= 2 (count (get output-schema "oneOf"))) "should have 2 exit transitions")

          (let [extract-id (fn [schema]
                             (get-in schema ["properties" "id" "const"]))
                ids (set (map extract-id (get output-schema "oneOf")))]
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
                                  "schema" {"type" "object"
                                            "required" ["id" "input"]
                                            "properties" {"id" {"const" ["start" "work"]}
                                                          "input" {"type" "string"}}}}
                                 {"id" ["work" "end"]
                                  "schema" {"type" "object"
                                            "required" ["id" "output"]
                                            "properties" {"id" {"const" ["work" "end"]}
                                                          "output" {"type" "integer"}}}}]}
          result (fsm-schemas simple-fsm)]

      (testing "works without explicit context"
        (is (map? (:input-schema result)) "should return input schema as map")
        (is (map? (:output-schema result)) "should return output schema as map")))))

;;==============================================================================
;; Minimal FSM Integration Test (Long-Running)
;;==============================================================================

(def minimal-schemas
  {"input" {"type" "object"
            "additionalProperties" false
            "required" ["question"]
            "properties" {"question" {"type" "string"}}}
   "output" {"type" "object"
             "additionalProperties" false
             "required" ["id" "answer" "agree"]
             "properties"
             {"id" {"const" ["responder" "end"]}
              "answer" {"type" "string"}
              "agree" {"type" "boolean"}}}})

(def minimal-registry
  minimal-schemas)

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
     "schema" {"$ref" "#/$defs/input"}}
    {"id" ["responder" "end"]
     "schema" {"$ref" "#/$defs/output"}}]})

(defn test-minimal-fsm []
  (let [actions {"llm" #'claij.fsm/llm-action "end" #'actions/end-action}
        context {:id->action actions
                 :llm/service "anthropic"
                 :llm/model "claude-sonnet-4-20250514"}
        input {"question" "Is 2 + 2 = 4?"}
        result (run-sync minimal-fsm context input 60000)]
    (if (= result :timeout)
      {:success false :error "Timeout"}
      (let [[_ctx trail] result
            last-evt (last-event trail)]
        (if (schema-valid? (get minimal-schemas "output") last-evt)
          {:success true :response last-evt}
          {:success false :response last-evt :error "Validation failed"})))))

(deftest ^:long-running ^:integration minimal-fsm-test
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
  {:config true
   :input {"type" "object" "required" ["value"] "properties" {"value" {"type" "integer"}}}
   :output {"type" "object" "required" ["result" "id"]
            "properties" {"result" {"type" "integer"} "id" {"type" "array"}}}}
  [_config _fsm _ix _state]
  (fn [context event _trail handler]
    (let [result {"result" (* 2 (get event "value"))
                  "id" ["processor" "end"]}]
      (handler context result))))

(def-action untyped-action
  "Action with no input/output schemas (legacy form)"
  {"type" "object"} ; config-only schema
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
      (is (= {"type" "object" "required" ["value"] "properties" {"value" {"type" "integer"}}}
             (state-action-input-schema context {"id" "x" "action" "typed"}))
          "Should return declared input schema")

      ;; Untyped action defaults to true
      (is (= true
             (state-action-input-schema context {"id" "x" "action" "untyped"}))
          "Should return true for legacy actions")

      ;; Missing action returns true
      (is (= true
             (state-action-input-schema context {"id" "x" "action" "missing"}))
          "Should return true when action not found")

      ;; No action key returns true
      (is (= true
             (state-action-input-schema context {"id" "x"}))
          "Should return true when state has no action")))

  (testing "state-action-output-schema extracts output schema"
    (let [context {:id->action {"typed" #'typed-processor-action
                                "untyped" #'untyped-action}}]

      ;; Typed action has explicit output schema
      (is (= {"type" "object" "required" ["result" "id"]
              "properties" {"result" {"type" "integer"} "id" {"type" "array"}}}
             (state-action-output-schema context {"id" "x" "action" "typed"}))
          "Should return declared output schema")

      ;; Untyped action defaults to true
      (is (= true
             (state-action-output-schema context {"id" "x" "action" "untyped"}))
          "Should return true for legacy actions")

      ;; Missing action returns true
      (is (= true
             (state-action-output-schema context {"id" "x" "action" "missing"}))
          "Should return true when action not found")))

  (testing "same code path works at all three times"
    ;; The functions work identically regardless of when called
    ;; because they only depend on what's in context
    (let [config-ctx {} ; Empty context at config time
          start-ctx {:id->action {"typed" #'typed-processor-action}} ; Populated at start
          runtime-ctx {:id->action {"typed" #'typed-processor-action}
                       :extra "runtime-data"} ; Additional runtime state
          state {"id" "x" "action" "typed"}
          expected-schema {"type" "object" "required" ["value"] "properties" {"value" {"type" "integer"}}}]

      ;; Config time: no actions yet
      (is (= true (state-action-input-schema config-ctx state))
          "Config time returns true (no actions)")

      ;; Start time: actions available
      (is (= expected-schema (state-action-input-schema start-ctx state))
          "Start time returns declared schema")

      ;; Runtime: same result (extra context doesn't affect lookup)
      (is (= expected-schema (state-action-input-schema runtime-ctx state))
          "Runtime returns declared schema"))))

;;------------------------------------------------------------------------------
;; Story #62 Phase 2: Xition Schema Fallback Tests
;;------------------------------------------------------------------------------

(deftest resolve-schema-fallback-test
  (testing "resolve-schema with explicit schema (no fallback needed)"
    (let [context {:id->action {"typed" #'typed-processor-action}}
          xition {"id" ["a" "b"]}
          explicit-schema {"type" "object" "properties" {"x" {"type" "integer"}}}]

      ;; Explicit schema returned as-is
      (is (= explicit-schema (resolve-schema context xition explicit-schema))
          "Explicit schema returned unchanged")

      ;; Even with state+direction, explicit schema wins
      (is (= explicit-schema
             (resolve-schema context xition explicit-schema
                             {"id" "a" "action" "typed"} :input))
          "Explicit schema not overridden by fallback")))

  (testing "resolve-schema with nil schema falls back to action schema"
    (let [context {:id->action {"typed" #'typed-processor-action}}
          xition {"id" ["processor" "end"]}
          typed-state {"id" "processor" "action" "typed"}
          expected-input {"type" "object" "required" ["value"] "properties" {"value" {"type" "integer"}}}
          expected-output {"type" "object" "required" ["result" "id"]
                           "properties" {"result" {"type" "integer"} "id" {"type" "array"}}}]

      ;; Nil schema + state + :input -> action input schema
      (is (= expected-input
             (resolve-schema context xition nil typed-state :input))
          "Falls back to action input schema")

      ;; Nil schema + state + :output -> action output schema
      (is (= expected-output
             (resolve-schema context xition nil typed-state :output))
          "Falls back to action output schema")))

  (testing "resolve-schema nil without fallback returns true (permissive)"
    (let [context {}
          xition {"id" ["a" "b"]}]

      ;; 3-arity: no fallback available
      (is (true? (resolve-schema context xition nil))
          "Nil schema without fallback returns true")

      ;; 5-arity with nil state: no fallback
      (is (true? (resolve-schema context xition nil nil :input))
          "Nil state means no fallback")

      ;; 5-arity with nil direction: no fallback
      (is (true? (resolve-schema context xition nil {"id" "x"} nil))
          "Nil direction means no fallback")))

  (testing "resolve-schema fallback with untyped action returns true"
    (let [context {:id->action {"untyped" #'untyped-action}}
          xition {"id" ["a" "b"]}
          untyped-state {"id" "processor" "action" "untyped"}]

      (is (= true (resolve-schema context xition nil untyped-state :input))
          "Untyped action falls back to true")
      (is (= true (resolve-schema context xition nil untyped-state :output))
          "Untyped action falls back to true")))

  (testing "resolve-schema string lookup still works"
    (let [dynamic-schema-fn (fn [_ctx _xition] {"type" "object" "properties" {"dynamic" {"type" "string"}}})
          context {:id->schema {"my-schema" dynamic-schema-fn}}
          xition {"id" ["a" "b"]}]

      (is (= {"type" "object" "properties" {"dynamic" {"type" "string"}}}
             (resolve-schema context xition "my-schema"))
          "String schema still resolves via :id->schema")))

  (testing "same code path at all three times"
    ;; Fallback works consistently regardless of when called
    (let [typed-state {"id" "x" "action" "typed"}
          xition {"id" ["x" "y"]}
          expected-schema {"type" "object" "required" ["value"] "properties" {"value" {"type" "integer"}}}]

      ;; Config time: no actions -> fallback to true
      (is (= true (resolve-schema {} xition nil typed-state :input))
          "Config time: action not found, falls back to true")

      ;; Start/Runtime: actions available -> falls back to declared schema
      (let [ctx {:id->action {"typed" #'typed-processor-action}}]
        (is (= expected-schema
               (resolve-schema ctx xition nil typed-state :input))
            "Start/Runtime: falls back to declared schema")))))

