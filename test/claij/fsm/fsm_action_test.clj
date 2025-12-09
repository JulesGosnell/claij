(ns claij.fsm.fsm-action-test
  "Tests for fsm-action - composing FSMs as actions within other FSMs."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [claij.action :refer [def-action]]
   [claij.actions :refer [end-action fsm-action-factory default-actions]]
   [claij.fsm :refer [start-fsm last-event]]
   [claij.malli :refer [def-fsm]]))

;;==============================================================================
;; Test FSMs
;;==============================================================================

;; Simple child FSM: start -> process -> end
;; Takes {"value" n} and returns {"result" (* n 2)}

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

;; NOTE: We use the real end-action from claij.actions which handles
;; both :fsm/on-complete (for async composition) and :fsm/completion-promise
;; (for sync/await patterns)

;; Child FSM uses the real end-action which calls :fsm/on-complete
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

;; Parent FSM that uses child FSM via fsm-action
;; start -> delegate -> collect -> end

(def-action parent-start-action
  "Prepares data for child FSM."
  {:config [:map]
   :input :any
   :output :any}
  [_config _fsm _ix _state]
  (fn [context event _trail handler]
    ;; Pass through to delegate state with the value
    (handler context {"id" ["prepare" "delegate"]
                      "value" (get event "input")})))

(def-action parent-collect-action
  "Collects result from child FSM."
  {:config [:map]
   :input :any
   :output :any}
  [_config _fsm _ix _state]
  (fn [context event _trail handler]
    ;; Extract child result and format final output
    (let [child-result (get-in event ["result" "result"])]
      (handler context {"id" ["collect" "end"]
                        "final-result" child-result
                        "source" "child-fsm"}))))

;; Parent FSM also uses the real end-action from claij.actions

;;==============================================================================
;; Mock Store
;;==============================================================================

(def mock-fsm-store
  "In-memory FSM store for testing."
  (atom {"child-doubler" {1 child-fsm}}))

(defn mock-fsm-latest-version [_store fsm-id]
  (when-let [versions (get @mock-fsm-store fsm-id)]
    (apply max (keys versions))))

(defn mock-fsm-load-version [_store fsm-id version]
  (get-in @mock-fsm-store [fsm-id version]))

;;==============================================================================
;; Test Fixtures
;;==============================================================================

;; We need to temporarily replace store functions for testing
;; Using with-redefs in each test

;;==============================================================================
;; Unit Tests
;;==============================================================================

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
    (with-redefs [claij.store/fsm-latest-version mock-fsm-latest-version
                  claij.store/fsm-load-version mock-fsm-load-version]
      (let [;; Config for fsm-action
            config {"fsm-id" "child-doubler"
                    "success-to" "collect"
                    "trail-mode" :summary}
            mock-state {"id" "delegate"}

            ;; Create the runtime function
            f1 (fsm-action-factory config {} {} mock-state)

            ;; Track what handler receives
            handler-result (promise)
            mock-handler (fn [ctx event]
                           (deliver handler-result {:context ctx :event event}))

            ;; Context with child FSM actions and mock store
            context {:store :mock-store
                     :id->action child-fsm-actions}

            ;; Input event
            input-event {"id" ["prepare" "delegate"]
                         "value" 21}]

        ;; Call the runtime function
        (f1 context input-event [] mock-handler)

        ;; Wait for async completion
        (let [result (deref handler-result 5000 :timeout)]
          (is (not= :timeout result) "Handler should be called")
          (when (not= :timeout result)
            (let [{:keys [event]} result]
              ;; Check output event structure
              (is (= ["delegate" "collect"] (get event "id")))
              ;; Child FSM should have doubled 21 to 42
              (is (= 42 (get-in event ["result" "result"])))
              ;; Trail summary should be present
              (is (map? (get event "child-trail")))
              (is (= "child-doubler" (get-in event ["child-trail" :child-fsm]))))))))))

(deftest fsm-action-trail-modes-test
  (testing "trail-mode :omit excludes child trail"
    (with-redefs [claij.store/fsm-latest-version mock-fsm-latest-version
                  claij.store/fsm-load-version mock-fsm-load-version]
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
    (with-redefs [claij.store/fsm-latest-version mock-fsm-latest-version
                  claij.store/fsm-load-version mock-fsm-load-version]
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
    (with-redefs [claij.store/fsm-latest-version mock-fsm-latest-version
                  claij.store/fsm-load-version mock-fsm-load-version]
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
            ;; Full trail is a vector of trail entries
            (is (vector? trail))
            (is (pos? (count trail)))
            ;; Each entry has :from :to :event
            (is (every? #(contains? % :from) trail))
            (is (every? #(contains? % :to) trail))
            (is (every? #(contains? % :event) trail))))))))

(deftest fsm-action-version-handling-test
  (testing "loads latest version when no version specified"
    (with-redefs [claij.store/fsm-latest-version mock-fsm-latest-version
                  claij.store/fsm-load-version mock-fsm-load-version]
      ;; Add version 2 to mock store
      (swap! mock-fsm-store assoc-in ["child-doubler" 2]
             (assoc child-fsm "version" 2))

      (let [config {"fsm-id" "child-doubler"
                    "success-to" "next"}
            f1 (fsm-action-factory config {} {} {"id" "test"})
            handler-result (promise)
            mock-handler (fn [_ctx event] (deliver handler-result event))
            context {:store :mock-store :id->action child-fsm-actions}]

        (f1 context {"value" 10} [] mock-handler)

        (let [event (deref handler-result 5000 :timeout)]
          (is (not= :timeout event))
          ;; Should work (version doesn't affect behavior in this test)
          (is (= 20 (get-in event ["result" "result"])))))

      ;; Clean up
      (swap! mock-fsm-store update "child-doubler" dissoc 2)))

  (testing "loads specific version when specified"
    (with-redefs [claij.store/fsm-latest-version mock-fsm-latest-version
                  claij.store/fsm-load-version mock-fsm-load-version]
      (let [config {"fsm-id" "child-doubler"
                    "fsm-version" 1
                    "success-to" "next"}
            f1 (fsm-action-factory config {} {} {"id" "test"})
            handler-result (promise)
            mock-handler (fn [_ctx event] (deliver handler-result event))
            context {:store :mock-store :id->action child-fsm-actions}]

        (f1 context {"value" 4} [] mock-handler)

        (let [event (deref handler-result 5000 :timeout)]
          (is (not= :timeout event))
          (is (= 8 (get-in event ["result" "result"]))))))))

(deftest fsm-action-error-handling-test
  (testing "throws when FSM not found in store"
    (with-redefs [claij.store/fsm-latest-version (constantly nil)
                  claij.store/fsm-load-version (constantly nil)]
      (let [config {"fsm-id" "nonexistent-fsm"
                    "success-to" "next"}
            f1 (fsm-action-factory config {} {} {"id" "test"})
            context {:store :mock-store :id->action child-fsm-actions}]

        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Child FSM not found"
             (f1 context {"value" 1} [] (fn [_ _]))))))))

(deftest fsm-action-output-event-structure-test
  (testing "output event has correct structure"
    (with-redefs [claij.store/fsm-latest-version mock-fsm-latest-version
                  claij.store/fsm-load-version mock-fsm-load-version]
      (let [config {"fsm-id" "child-doubler"
                    "success-to" "next-state"
                    "trail-mode" :summary}
            ;; State id comes from config-time state parameter
            f1 (fsm-action-factory config {} {} {"id" "current-state"})
            handler-result (promise)
            mock-handler (fn [_ctx event] (deliver handler-result event))
            context {:store :mock-store :id->action child-fsm-actions}]

        (f1 context {"value" 100} [] mock-handler)

        (let [event (deref handler-result 5000 :timeout)]
          (is (not= :timeout event))
          ;; id should be [current-state, success-to]
          (is (= ["current-state" "next-state"] (get event "id")))
          ;; result contains child's final event
          (is (= 200 (get-in event ["result" "result"])))
          ;; child-trail present in summary mode
          (is (some? (get event "child-trail"))))))))

;;==============================================================================
;; Integration Test - Parent FSM using fsm-action
;;==============================================================================

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
     "schema" [:map ["id" :any] ["input" :int]]}
    {"id" ["prepare" "delegate"]
     "schema" [:map ["id" :any] ["value" :int]]}
    {"id" ["delegate" "collect"]
     "schema" [:map ["id" :any] ["result" :any] ["child-trail" {:optional true} :any]]}
    {"id" ["collect" "end"]
     "schema" [:map ["id" :any] ["final-result" :int] ["source" :string]]}]})

(deftest parent-child-fsm-integration-test
  (testing "Parent FSM can delegate to child FSM via fsm-action"
    (with-redefs [claij.store/fsm-latest-version mock-fsm-latest-version
                  claij.store/fsm-load-version mock-fsm-load-version]
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
