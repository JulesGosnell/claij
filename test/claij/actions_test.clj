(ns claij.actions-test
  "Tests for def-action macro, action helpers, and fsm-action-factory."
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.action :refer [def-action action? action-name action-config-schema]]
   [claij.actions :refer [default-actions end-action fork-action generate-action
                          with-actions with-default-actions make-context
                          fsm-action-factory]]
   [claij.fsm :refer [start-fsm last-event]]
   [claij.malli :refer [def-fsm]]
   [claij.store :as store]))

;;==============================================================================
;; Example Actions for Testing
;;==============================================================================

(def-action test-llm-action
  "LLM action - calls configured provider and model."
  [:map
   ["provider" [:enum "anthropic" "google" "openai" "xai"]]
   ["model" :string]]
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
  [:map]
  [config fsm ix state]
  (fn [context _event trail _handler]
    (when-let [p (:fsm/completion-promise context)]
      (deliver p [context trail]))))

(def-action test-timeout-action
  "Action with optional timeout config."
  [:map
   ["timeout-ms" {:optional true} :int]]
  [config fsm ix state]
  (fn [context event trail handler]
    (let [timeout (get config "timeout-ms" 5000)]
      (handler context {"id" "done" "timeout-used" timeout}))))

(def-action test-broken-config-action
  "Action that requires specific config for testing validation."
  [:map
   ["required-field" :string]
   ["count" :int]]
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
   "schema" :any})

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
    (is (= [:map
            ["provider" [:enum "anthropic" "google" "openai" "xai"]]
            ["model" :string]]
           (action-config-schema #'test-llm-action)))
    (is (= [:map] (action-config-schema #'test-end-action))))

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
  (testing "Factory returns runtime function when config is valid"
    (let [config {"provider" "anthropic" "model" "claude-sonnet-4"}
          f2 (test-llm-action config mock-fsm mock-ix mock-state)]
      (is (fn? f2))))

  (testing "Empty config works for actions that don't need config"
    (let [f2 (test-end-action {} mock-fsm mock-ix mock-state)]
      (is (fn? f2))))

  (testing "Optional config fields work"
    (let [f2-default (test-timeout-action {} mock-fsm mock-ix mock-state)
          f2-custom (test-timeout-action {"timeout-ms" 10000} mock-fsm mock-ix mock-state)]
      (is (fn? f2-default))
      (is (fn? f2-custom)))))

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
    (let [config {"provider" "google" "model" "gemini-2.5-flash"}
          f2 (test-llm-action config mock-fsm mock-ix mock-state)
          result (atom nil)
          mock-handler (fn [ctx out] (reset! result out))
          mock-context {:test true}
          mock-event {"document" "test input"}
          mock-trail []]
      (f2 mock-context mock-event mock-trail mock-handler)
      (is (= "google" (get @result "provider")))
      (is (= "gemini-2.5-flash" (get @result "model")))
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
      (is (= [:map
              ["provider" [:enum "anthropic" "google" "openai" "xai"]]
              ["model" :string]]
             (action-config-schema (get registry "llm"))))
      (is (= [:map] (action-config-schema (get registry "end"))))))

  (testing "Can instantiate actions from registry"
    (let [registry {"llm" test-llm-action
                    "end" test-end-action}
          llm-factory (get registry "llm")
          config {"provider" "xai" "model" "grok-4"}
          f2 (llm-factory config mock-fsm mock-ix mock-state)]
      (is (fn? f2)))))

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
          (is (= context ctx))
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
                               :model "claude-sonnet-4"})]
        (is (= :mock-store (:store ctx)))
        (is (= "anthropic" (:provider ctx)))
        (is (= "claude-sonnet-4" (:model ctx)))
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
  {:config [:map]
   :input :any
   :output :any}
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
     "schema" [:map ["id" :any] ["value" :int]]}
    {"id" ["process" "end"]
     "schema" [:map ["id" :any] ["result" :int]]}]})

(def mock-fsm-store
  (atom {"child-doubler" {1 child-fsm}}))

(defn mock-fsm-latest-version [_store fsm-id]
  (when-let [versions (get @mock-fsm-store fsm-id)]
    (apply max (keys versions))))

(defn mock-fsm-load-version [_store fsm-id version]
  (get-in @mock-fsm-store [fsm-id version]))

(deftest fsm-action-factory-test
  (testing "fsm-action-factory returns a factory function"
    (is (fn? fsm-action-factory)))

  (testing "factory returns config-time function"
    (let [config {"fsm-id" "child-doubler"
                  "success-to" "collect"}
          mock-state {"id" "delegate"}
          f1 (fsm-action-factory config {} {} mock-state)]
      (is (fn? f1)))))

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
    (with-redefs [store/fsm-latest-version mock-fsm-latest-version
                  store/fsm-load-version mock-fsm-load-version]
      (let [config {"fsm-id" "child-doubler"
                    "success-to" "next"
                    "trail-mode" :omit}
            f1 (fsm-action-factory config {} {} {"id" "test"})
            handler-result (promise)
            mock-handler (fn [_ctx event] (deliver handler-result event))
            context {:store :mock-store :id->action child-fsm-actions}]

        (f1 context {"value" 5} [] mock-handler)

        (let [event (deref handler-result 5000 :timeout)]
          (is (not= :timeout event))
          (is (nil? (get event "child-trail")))))))

  (testing "trail-mode :summary includes summary"
    (with-redefs [store/fsm-latest-version mock-fsm-latest-version
                  store/fsm-load-version mock-fsm-load-version]
      (let [config {"fsm-id" "child-doubler"
                    "success-to" "next"
                    "trail-mode" :summary}
            f1 (fsm-action-factory config {} {} {"id" "test"})
            handler-result (promise)
            mock-handler (fn [_ctx event] (deliver handler-result event))
            context {:store :mock-store :id->action child-fsm-actions}]

        (f1 context {"value" 7} [] mock-handler)

        (let [event (deref handler-result 5000 :timeout)]
          (is (not= :timeout event))
          (let [trail (get event "child-trail")]
            (is (map? trail))
            (is (= "child-doubler" (:child-fsm trail)))
            (is (number? (:steps trail)))
            (is (some? (:first-event trail)))
            (is (some? (:last-event trail))))))))

  (testing "trail-mode :full includes complete trail"
    (with-redefs [store/fsm-latest-version mock-fsm-latest-version
                  store/fsm-load-version mock-fsm-load-version]
      (let [config {"fsm-id" "child-doubler"
                    "success-to" "next"
                    "trail-mode" :full}
            f1 (fsm-action-factory config {} {} {"id" "test"})
            handler-result (promise)
            mock-handler (fn [_ctx event] (deliver handler-result event))
            context {:store :mock-store :id->action child-fsm-actions}]

        (f1 context {"value" 3} [] mock-handler)

        (let [event (deref handler-result 5000 :timeout)]
          (is (not= :timeout event))
          (let [trail (get event "child-trail")]
            (is (vector? trail))
            (is (pos? (count trail)))
            (is (every? #(contains? % :from) trail))
            (is (every? #(contains? % :to) trail))
            (is (every? #(contains? % :event) trail))))))))

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
