(ns claij.actions-test
  "Tests for def-action macro, action helpers, and fsm-action-factory."
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.action :refer [def-action action? action-name action-config-schema]]
   [claij.actions :refer [default-actions end-action fork-action generate-action
                          with-actions with-default-actions make-context
                          fsm-action-factory reuse-action]]
   [claij.fsm :refer [start-fsm last-event]]
   [claij.schema :refer [def-fsm]]
   [claij.store :as store]
   [claij.model :as model]))

;;==============================================================================
;; Example Actions for Testing
;;==============================================================================

(def-action test-llm-action
  "LLM action - calls configured provider and model."
  {"type" "object"
   "required" ["provider" "model"]
   "properties"
   {"provider" {"enum" ["anthropic" "google" "openai" "xai"]}
    "model" {"type" "string"}}}
  [config fsm ix state]
  (fn [context event trail handler]
    (let [provider (get config "provider")
          model (get config "model")]
      (handler context {"id" "mock-response"
                        "provider" provider
                        "model" model
                        "input" event}))))

(def-action test-end-action
  "Terminal action - delivers result to completion promise.
   No config needed, but must accept empty map."
  {"type" "object"}
  [config fsm ix state]
  (fn [context _event trail _handler]
    (when-let [p (:fsm/completion-promise context)]
      (deliver p [context trail]))))

(def-action test-timeout-action
  "Action with optional timeout config."
  {"type" "object"
   "properties" {"timeout-ms" {"type" "integer"}}}
  [config fsm ix state]
  (fn [context event trail handler]
    (let [timeout (get config "timeout-ms" 5000)]
      (handler context {"id" "done" "timeout-used" timeout}))))

(def-action test-broken-config-action
  "Action that requires specific config for testing validation."
  {"type" "object"
   "required" ["required-field" "count"]
   "properties" {"required-field" {"type" "string"}
                 "count" {"type" "integer"}}}
  [config fsm ix state]
  (fn [context event trail handler]
    (handler context {"id" "ok"})))

;;==============================================================================
;; Mock FSM structures for testing
;;==============================================================================

(def mock-fsm
  {"id" "test-fsm"
   "states" [{"id" "start"} {"id" "end"}]
   "xitions" [{"id" ["start" "end"]}]})

(def mock-ix
  {"id" ["start" "end"]
   "schema" true})

(def mock-state
  {"id" "test-state"
   "action" "llm"})

;;==============================================================================
;; Tests
;;==============================================================================

(deftest def-action-metadata-test
  (testing "Action has name in metadata"
    (is (= "test-llm-action" (action-name #'test-llm-action)))
    (is (= "test-end-action" (action-name #'test-end-action))))

  (testing "Action has config schema in metadata"
    (is (= {"type" "object"
            "required" ["provider" "model"]
            "properties"
            {"provider" {"enum" ["anthropic" "google" "openai" "xai"]}
             "model" {"type" "string"}}}
           (action-config-schema #'test-llm-action)))
    (is (= {"type" "object"} (action-config-schema #'test-end-action))))

  (testing "Action has standard Clojure docstring"
    (is (= "LLM action - calls configured provider and model."
           (:doc (meta #'test-llm-action)))))

  (testing "action? recognizes action vars"
    (is (action? #'test-llm-action))
    (is (action? #'test-end-action))
    (is (action? #'test-timeout-action)))

  (testing "action? returns false for non-actions"
    (is (not (action? #'action-name)))
    (is (not (action? #'mock-fsm)))))

(deftest def-action-currying-test
  (testing "Factory returns callable runtime function when config is valid"
    (let [config {"provider" "anthropic" "model" (model/direct-model :anthropic)}
          f2 (test-llm-action config mock-fsm mock-ix mock-state)
          result (atom nil)]
      (is (fn? f2))
      ;; Verify it's actually callable with correct arity
      (f2 {} {} [] (fn [_ctx out] (reset! result out)))
      (is (some? @result) "Runtime function should invoke handler")))

  (testing "Empty config works for actions that don't need config"
    (let [f2 (test-end-action {} mock-fsm mock-ix mock-state)
          called (atom false)]
      (is (fn? f2))
      ;; Verify it's callable - end-action may return nil but shouldn't throw
      (is (nil? (f2 {} {} [] (fn [_ _] (reset! called true)))))))

  (testing "Optional config fields work with defaults"
    (let [f2-default (test-timeout-action {} mock-fsm mock-ix mock-state)
          f2-custom (test-timeout-action {"timeout-ms" 10000} mock-fsm mock-ix mock-state)
          result-default (atom nil)
          result-custom (atom nil)]
      ;; Both should be callable and produce output
      (f2-default {} {} [] (fn [_ctx out] (reset! result-default out)))
      (f2-custom {} {} [] (fn [_ctx out] (reset! result-custom out)))
      (is (= 5000 (get @result-default "timeout-used")) "Default config value")
      (is (= 10000 (get @result-custom "timeout-used")) "Custom config value"))))

(deftest def-action-config-validation-test
  (testing "Invalid config throws at factory call time"
    (let [ex (try
               (test-llm-action {"provider" "invalid-provider" "model" "x"}
                                mock-fsm mock-ix mock-state)
               nil
               (catch Exception e e))]
      (is (some? ex))
      (is (= :config-validation (:type (ex-data ex))))
      (is (= "test-llm-action" (:action (ex-data ex))))))

  (testing "Missing required config field throws"
    (let [ex (try
               (test-broken-config-action {"count" 5}
                                          mock-fsm mock-ix mock-state)
               nil
               (catch Exception e e))]
      (is (some? ex))
      (is (= :config-validation (:type (ex-data ex))))))

  (testing "Wrong type for config field throws"
    (let [ex (try
               (test-broken-config-action {"required-field" "ok" "count" "not-an-int"}
                                          mock-fsm mock-ix mock-state)
               nil
               (catch Exception e e))]
      (is (some? ex))
      (is (= :config-validation (:type (ex-data ex)))))))

(deftest def-action-runtime-execution-test
  (testing "Runtime function executes with closed-over config"
    (let [config {"provider" "google" "model" (model/direct-model :google)}
          f2 (test-llm-action config mock-fsm mock-ix mock-state)
          result (atom nil)
          mock-handler (fn [ctx out] (reset! result out))
          mock-context {:test true}
          mock-event {"document" "test input"}
          mock-trail []]
      (f2 mock-context mock-event mock-trail mock-handler)
      (is (= "google" (get @result "provider")))
      (is (= (model/direct-model :google) (get @result "model")))
      (is (= mock-event (get @result "input")))))

  (testing "Config-time params are available in runtime function"
    (let [f2 (test-timeout-action {"timeout-ms" 3000} mock-fsm mock-ix mock-state)
          result (atom nil)
          mock-handler (fn [ctx out] (reset! result out))]
      (f2 {} {} [] mock-handler)
      (is (= 3000 (get @result "timeout-used")))))

  (testing "Default config values work"
    (let [f2 (test-timeout-action {} mock-fsm mock-ix mock-state)
          result (atom nil)
          mock-handler (fn [ctx out] (reset! result out))]
      (f2 {} {} [] mock-handler)
      (is (= 5000 (get @result "timeout-used"))))))

(deftest def-action-end-action-test
  (testing "End action delivers to completion promise"
    (let [f2 (test-end-action {} mock-fsm mock-ix mock-state)
          completion-promise (promise)
          context {:fsm/completion-promise completion-promise}
          trail [{:from "start" :to "end" :event {"id" "test"}}]]
      (f2 context {"id" ["test" "end"]} trail nil)
      (let [[ctx tr] (deref completion-promise 100 :timeout)]
        (is (not= :timeout ctx))
        (is (= trail tr))))))

(deftest action-registry-pattern-test
  (testing "Can build registry from action vars"
    (let [registry {"llm" #'test-llm-action
                    "end" #'test-end-action
                    "timeout" #'test-timeout-action}]
      (is (= {"type" "object"
              "required" ["provider" "model"]
              "properties"
              {"provider" {"enum" ["anthropic" "google" "openai" "xai"]}
               "model" {"type" "string"}}}
             (action-config-schema (get registry "llm"))))
      (is (= {"type" "object"} (action-config-schema (get registry "end"))))))

  (testing "Can instantiate and invoke actions from registry"
    (let [registry {"llm" test-llm-action
                    "end" test-end-action}
          llm-factory (get registry "llm")
          config {"provider" "xai" "model" (model/direct-model :xai)}
          f2 (llm-factory config mock-fsm mock-ix mock-state)
          result (atom nil)]
      (is (fn? f2))
      ;; Actually invoke it to verify the registry pattern works end-to-end
      (f2 {} {"document" "test"} [] (fn [_ctx out] (reset! result out)))
      (is (= "xai" (get @result "provider"))))))

;;==============================================================================
;; claij.actions Module Tests
;;==============================================================================

(deftest actions-module-test

  (testing "default-actions"
    (testing "contains expected action keys"
      (is (contains? default-actions "llm"))
      (is (contains? default-actions "end"))
      (is (contains? default-actions "triage"))
      (is (contains? default-actions "reuse"))
      (is (contains? default-actions "fork"))
      (is (contains? default-actions "generate"))
      (is (contains? default-actions "fsm")))

    (testing "all values are action vars or factories"
      ;; Most actions are vars, but some (like fsm-action-factory) are
      ;; dynamically created functions that don't have var metadata.
      ;; fsm-action-factory is a special case that's a function, not a var.
      (doseq [[k v] default-actions]
        (if (= k "fsm")
          ;; fsm-action-factory is a function, not a var
          (is (fn? v) (str "Expected fn for dynamic action: " k))
          ;; Standard actions are vars
          (do
            (is (var? v) (str "Expected var for action: " k))
            (is (action? v) (str "Expected action? true for: " k)))))))

  (testing "end-action"
    (testing "delivers to completion promise when present"
      (let [f2 (end-action {} mock-fsm mock-ix mock-state)
            completion-promise (promise)
            context {:fsm/completion-promise completion-promise}
            trail [{:from "start" :to "end"}]]
        (f2 context {"id" ["test" "end"]} trail nil)
        (let [[ctx tr] (deref completion-promise 100 :timeout)]
          (is (not= :timeout ctx))
          ;; Promise is dissoc'd from delivered context to avoid circular reference
          (is (= {} ctx))
          (is (= trail tr)))))

    (testing "does nothing when no promise in context"
      ;; Should not throw
      (let [f2 (end-action {} mock-fsm mock-ix mock-state)]
        (is (nil? (f2 {} {} [] nil))))))

  (testing "fork-action"
    (testing "calls handler with not-implemented response"
      (let [f2 (fork-action {} mock-fsm mock-ix mock-state)
            result (atom nil)
            handler (fn [ctx out] (reset! result out))]
        (f2 {} {} [] handler)
        (is (= ["fork" "end"] (get @result "id")))
        (is (false? (get @result "success"))))))

  (testing "generate-action"
    (testing "calls handler with not-implemented response"
      (let [f2 (generate-action {} mock-fsm mock-ix mock-state)
            result (atom nil)
            handler (fn [ctx out] (reset! result out))]
        (f2 {} {} [] handler)
        (is (= ["generate" "end"] (get @result "id")))
        (is (false? (get @result "success"))))))

  (testing "with-actions"
    (testing "adds actions to empty context"
      (let [ctx (with-actions {} {"custom" #'test-llm-action})]
        (is (= #'test-llm-action (get-in ctx [:id->action "custom"])))))

    (testing "merges with existing actions"
      (let [ctx {:id->action {"existing" #'test-end-action}}
            ctx' (with-actions ctx {"new" #'test-llm-action})]
        (is (= #'test-end-action (get-in ctx' [:id->action "existing"])))
        (is (= #'test-llm-action (get-in ctx' [:id->action "new"]))))))

  (testing "with-default-actions"
    (testing "adds default actions to context"
      (let [ctx (with-default-actions {})]
        (is (= default-actions (:id->action ctx)))))

    (testing "merges with existing actions"
      (let [ctx {:id->action {"custom" #'test-llm-action}}
            ctx' (with-default-actions ctx)]
        ;; defaults should be merged in
        (is (contains? (:id->action ctx') "llm"))
        (is (contains? (:id->action ctx') "end"))
        ;; custom should still be there
        (is (= #'test-llm-action (get-in ctx' [:id->action "custom"]))))))

  (testing "make-context"
    (testing "creates context with defaults"
      (let [ctx (make-context {:store :mock-store
                               :provider "anthropic"
                               :model (model/direct-model :anthropic)})]
        (is (= :mock-store (:store ctx)))
        (is (= "anthropic" (:provider ctx)))
        (is (= (model/direct-model :anthropic) (:model ctx)))
        (is (= default-actions (:id->action ctx)))))

    (testing "allows overriding id->action"
      (let [custom-actions {"llm" #'test-llm-action}
            ctx (make-context {:store :mock
                               :provider "test"
                               :model "test"
                               :id->action custom-actions})]
        (is (= custom-actions (:id->action ctx)))))))

;;==============================================================================
;; FSM Action Factory Tests
;;==============================================================================

(def-action child-process-action
  "Doubles the input value."
  {:config {}
   :input true
   :output true}
  [_config _fsm _ix _state]
  (fn [context event _trail handler]
    (let [value (get event "value")
          result (* value 2)]
      (handler context {"id" ["process" "end"]
                        "result" result}))))

(def child-fsm-actions
  {"process" #'child-process-action
   "end" #'end-action})

(def-fsm child-fsm
  {"id" "child-doubler"
   "description" "Doubles input value"
   "states"
   [{"id" "process" "action" "process"}
    {"id" "end" "action" "end"}]
   "xitions"
   [{"id" ["start" "process"]
     "schema" {"type" "object"
               "required" ["id" "value"]
               "properties" {"id" {} "value" {"type" "integer"}}}}
    {"id" ["process" "end"]
     "schema" {"type" "object"
               "required" ["id" "result"]
               "properties" {"id" {} "result" {"type" "integer"}}}}]})

(def mock-fsm-store
  (atom {"child-doubler" {1 child-fsm}}))

(defn mock-fsm-latest-version [_store fsm-id]
  (when-let [versions (get @mock-fsm-store fsm-id)]
    (apply max (keys versions))))

(defn mock-fsm-load-version [_store fsm-id version]
  (get-in @mock-fsm-store [fsm-id version]))

;; Test helper to reduce boilerplate in fsm-action tests
(defn run-action-with-mock-store
  "Runs an action with mocked store, returning the result or :timeout.
   
   Options:
   - :config - action config map (required)
   - :state-id - current state id (default: \"test\")
   - :input - input event map (required)
   - :context - additional context keys (default: {})
   - :timeout - deref timeout in ms (default: 5000)"
  [{:keys [config state-id input context timeout]
    :or {state-id "test" context {} timeout 5000}}]
  (with-redefs [store/fsm-latest-version mock-fsm-latest-version
                store/fsm-load-version mock-fsm-load-version]
    (let [f1 (fsm-action-factory config {} {} {"id" state-id})
          result-promise (promise)
          handler (fn [ctx event] (deliver result-promise {:ctx ctx :event event}))
          full-context (merge {:store :mock-store :id->action child-fsm-actions} context)]
      (f1 full-context input [] handler)
      (deref result-promise timeout :timeout))))

(deftest fsm-action-factory-test
  (testing "fsm-action-factory returns a factory function with action metadata"
    (is (fn? fsm-action-factory))
    ;; Verify it has proper action metadata for introspection
    (is (map? (meta fsm-action-factory)))
    (is (= "fsm" (-> fsm-action-factory meta :action/name))))

  (testing "factory returns config-time function that's callable"
    (let [config {"fsm-id" "child-doubler"
                  "success-to" "collect"}
          mock-state {"id" "delegate"}
          f1 (fsm-action-factory config {} {} mock-state)]
      (is (fn? f1))
      ;; Verify f1 has correct arity (4 args: context, event, trail, handler)
      ;; by checking it doesn't throw on call - full behavior tested in fsm-action-loads-child-fsm-test
      (with-redefs [store/fsm-latest-version mock-fsm-latest-version
                    store/fsm-load-version mock-fsm-load-version]
        (let [result (promise)]
          (f1 {:store :mock :id->action child-fsm-actions}
              {"id" ["x" "delegate"] "value" 1}
              []
              (fn [_ctx evt] (deliver result evt)))
          (is (not= :timeout (deref result 3000 :timeout))
              "Runtime function should complete"))))))

(deftest fsm-action-loads-child-fsm-test
  (testing "fsm-action loads child FSM from store and runs it"
    (with-redefs [store/fsm-latest-version mock-fsm-latest-version
                  store/fsm-load-version mock-fsm-load-version]
      (let [config {"fsm-id" "child-doubler"
                    "success-to" "collect"
                    "trail-mode" :summary}
            mock-state {"id" "delegate"}
            f1 (fsm-action-factory config {} {} mock-state)
            handler-result (promise)
            mock-handler (fn [ctx event]
                           (deliver handler-result {:context ctx :event event}))
            context {:store :mock-store
                     :id->action child-fsm-actions}
            input-event {"id" ["prepare" "delegate"]
                         "value" 21}]

        (f1 context input-event [] mock-handler)

        (let [result (deref handler-result 5000 :timeout)]
          (is (not= :timeout result) "Handler should be called")
          (when (not= :timeout result)
            (let [{:keys [event]} result]
              (is (= ["delegate" "collect"] (get event "id")))
              (is (= 42 (get-in event ["result" "result"])))
              (is (map? (get event "child-trail")))
              (is (= "child-doubler" (get-in event ["child-trail" :child-fsm]))))))))))

(deftest fsm-action-trail-modes-test
  (testing "trail-mode :omit excludes child trail"
    (let [result (run-action-with-mock-store
                  {:config {"fsm-id" "child-doubler"
                            "success-to" "next"
                            "trail-mode" :omit}
                   :input {"value" 5}})]
      (is (not= :timeout result))
      (is (nil? (get-in result [:event "child-trail"])))))

  (testing "trail-mode :summary includes summary map"
    (let [result (run-action-with-mock-store
                  {:config {"fsm-id" "child-doubler"
                            "success-to" "next"
                            "trail-mode" :summary}
                   :input {"value" 7}})]
      (is (not= :timeout result))
      (let [trail (get-in result [:event "child-trail"])]
        (is (map? trail))
        (is (= "child-doubler" (:child-fsm trail)))
        (is (number? (:steps trail)))
        (is (some? (:first-event trail)))
        (is (some? (:last-event trail))))))

  (testing "trail-mode :full includes complete trail vector"
    (let [result (run-action-with-mock-store
                  {:config {"fsm-id" "child-doubler"
                            "success-to" "next"
                            "trail-mode" :full}
                   :input {"value" 3}})]
      (is (not= :timeout result))
      (let [trail (get-in result [:event "child-trail"])]
        (is (vector? trail))
        (is (pos? (count trail)))
        (is (every? #(contains? % :from) trail))
        (is (every? #(contains? % :to) trail))
        (is (every? #(contains? % :event) trail))))))

(deftest fsm-action-error-handling-test
  (testing "throws when FSM not found in store"
    (with-redefs [store/fsm-latest-version (constantly nil)
                  store/fsm-load-version (constantly nil)]
      (let [config {"fsm-id" "nonexistent-fsm"
                    "success-to" "next"}
            f1 (fsm-action-factory config {} {} {"id" "test"})
            context {:store :mock-store :id->action child-fsm-actions}]

        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Child FSM not found"
             (f1 context {"value" 1} [] (fn [_ _]))))))))

;;==============================================================================
;; Reuse Action Tests
;;==============================================================================

;; Simple FSM for reuse-action testing - accepts any input, echoes it back
(def-fsm reuse-test-fsm
  {"id" "reuse-test"
   "states" [{"id" "echo" "action" "echo"}
             {"id" "end" "action" "end"}]
   "xitions" [{"id" ["start" "echo"] "schema" true}
              {"id" ["echo" "end"] "schema" true}]})

(def-action echo-action
  "Echoes input to output."
  {:config {} :input true :output true}
  [_config _fsm _ix _state]
  (fn [context event _trail handler]
    (handler context {"id" ["echo" "end"]
                      "echoed" event})))

(def reuse-test-actions
  {"echo" #'echo-action
   "end" #'end-action})

(defn mock-reuse-fsm-load [_store fsm-id _version]
  (when (= fsm-id "reuse-test")
    reuse-test-fsm))

(deftest reuse-action-test
  (testing "reuse-action is a valid action"
    (is (action? #'reuse-action)))

  (testing "reuse-action loads and runs child FSM from store"
    (with-redefs [store/fsm-load-version mock-reuse-fsm-load]
      (let [f1 (reuse-action {} {} {} {"id" "reuse"})
            handler-result (promise)
            mock-handler (fn [ctx event] (deliver handler-result {:ctx ctx :event event}))
            context {:store :mock-store
                     :id->action reuse-test-actions
                     :provider "test"
                     :model "test-model"}
            event {"fsm-id" "reuse-test"
                   "fsm-version" 1}
            trail [{"content" [nil {"document" "user request"}]}]]

        (f1 context event trail mock-handler)

        (let [result (deref handler-result 5000 :timeout)]
          (is (not= :timeout result) "Handler should be called")
          (when (not= :timeout result)
            (is (= ["reuse" "end"] (get-in result [:event "id"])))
            (is (true? (get-in result [:event "success"])))
            (is (some? (get-in result [:event "output"]))))))))

  (testing "reuse-action throws when FSM not found"
    (with-redefs [store/fsm-load-version (constantly nil)]
      (let [f1 (reuse-action {} {} {} {"id" "reuse"})
            context {:store :mock-store :id->action reuse-test-actions}
            event {"fsm-id" "nonexistent" "fsm-version" 1}]

        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"FSM not found"
             (f1 context event [] (fn [_ _]))))))))

(deftest fsm-action-version-handling-test
  (testing "loads latest version when no version specified"
    ;; Add version 2 to mock store
    (swap! mock-fsm-store assoc-in ["child-doubler" 2]
           (assoc child-fsm "version" 2))
    (try
      (let [result (run-action-with-mock-store
                    {:config {"fsm-id" "child-doubler" "success-to" "next"}
                     :input {"value" 10}})]
        (is (not= :timeout result))
        (is (= 20 (get-in result [:event "result" "result"]))))
      (finally
        ;; Clean up
        (swap! mock-fsm-store update "child-doubler" dissoc 2))))

  (testing "loads specific version when specified"
    (let [result (run-action-with-mock-store
                  {:config {"fsm-id" "child-doubler"
                            "fsm-version" 1
                            "success-to" "next"}
                   :input {"value" 4}})]
      (is (not= :timeout result))
      (is (= 8 (get-in result [:event "result" "result"]))))))

(deftest fsm-action-output-event-structure-test
  (testing "output event has correct structure"
    (let [result (run-action-with-mock-store
                  {:config {"fsm-id" "child-doubler"
                            "success-to" "next-state"
                            "trail-mode" :summary}
                   :state-id "current-state"
                   :input {"value" 100}})]
      (is (not= :timeout result))
      (let [event (:event result)]
        ;; id should be [current-state, success-to]
        (is (= ["current-state" "next-state"] (get event "id")))
        ;; result contains child's final event
        (is (= 200 (get-in event ["result" "result"])))
        ;; child-trail present in summary mode
        (is (some? (get event "child-trail")))))))

;;==============================================================================
;; Integration Test - Parent FSM using fsm-action
;;==============================================================================

;; start -> delegate -> collect -> end

(def-action parent-start-action
  "Prepares data for child FSM."
  {:config {}
   :input true
   :output true}
  [_config _fsm _ix _state]
  (fn [context event _trail handler]
    ;; Pass through to delegate state with the value
    (handler context {"id" ["prepare" "delegate"]
                      "value" (get event "input")})))

(def-action parent-collect-action
  "Collects result from child FSM."
  {:config {}
   :input true
   :output true}
  [_config _fsm _ix _state]
  (fn [context event _trail handler]
    ;; Extract child result and format final output
    (let [child-result (get-in event ["result" "result"])]
      (handler context {"id" ["collect" "end"]
                        "final-result" child-result
                        "source" "child-fsm"}))))

(def-fsm parent-fsm
  {"id" "parent-orchestrator"
   "description" "Parent FSM that delegates to child"
   "states"
   [{"id" "prepare" "action" "prepare"}
    {"id" "delegate" "action" "fsm"
     "config" {"fsm-id" "child-doubler"
               "success-to" "collect"
               "trail-mode" :summary}}
    {"id" "collect" "action" "collect"}
    {"id" "end" "action" "end"}]
   "xitions"
   [{"id" ["start" "prepare"]
     "schema" {"type" "object" "required" ["id" "input"]
               "properties" {"id" {} "input" {"type" "integer"}}}}
    {"id" ["prepare" "delegate"]
     "schema" {"type" "object" "required" ["id" "value"]
               "properties" {"id" {} "value" {"type" "integer"}}}}
    {"id" ["delegate" "collect"]
     "schema" {"type" "object" "required" ["id" "result"]
               "properties" {"id" {} "result" {} "child-trail" {}}}}
    {"id" ["collect" "end"]
     "schema" {"type" "object" "required" ["id" "final-result" "source"]
               "properties" {"id" {} "final-result" {"type" "integer"} "source" {"type" "string"}}}}]})

(deftest parent-child-fsm-integration-test
  (testing "Parent FSM can delegate to child FSM via fsm-action"
    (with-redefs [store/fsm-latest-version mock-fsm-latest-version
                  store/fsm-load-version mock-fsm-load-version]
      (let [;; Combined actions for parent and child FSMs
            ;; Use end-action for both - it handles both on-complete and promise
            parent-actions (merge child-fsm-actions
                                  {"prepare" #'parent-start-action
                                   "fsm" fsm-action-factory
                                   "collect" #'parent-collect-action
                                   "end" #'end-action})

            context {:store :mock-store
                     :id->action parent-actions}

            {:keys [submit await stop]} (start-fsm context parent-fsm)]

        (try
          ;; Submit input to parent FSM
          (submit {"id" ["start" "prepare"]
                   "input" 50})

          ;; Wait for completion
          (let [result (await 10000)]
            (is (not= :timeout result) "Parent FSM should complete")
            (when (not= :timeout result)
              (let [[_ctx trail] result
                    final-event (last-event trail)]
                ;; Parent should have processed child's result
                ;; 50 -> child doubles to 100 -> parent formats
                (is (= 100 (get final-event "final-result")))
                (is (= "child-fsm" (get final-event "source"))))))
          (finally
            (stop)))))))
