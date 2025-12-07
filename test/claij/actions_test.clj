(ns claij.actions-test
  "Tests for def-action macro and action helpers."
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.action :refer [def-action action? action-name action-config-schema]]))

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
