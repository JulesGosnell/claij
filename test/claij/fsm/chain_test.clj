(ns claij.fsm.chain-test
  "Tests for FSM chaining and action lifting."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.tools.logging :as log]
   [claij.action :refer [def-action]]
   [claij.fsm :refer [lift chain start-fsm last-event run-sync]]))

;;------------------------------------------------------------------------------
;; Test Fixtures
;;------------------------------------------------------------------------------

(defn quiet-logging [f]
  (with-redefs [log/log* (fn [& _])]
    (f)))

(use-fixtures :each quiet-logging)

;;------------------------------------------------------------------------------
;; Lift Tests
;;------------------------------------------------------------------------------

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
    (let [;; Function that increments a value
          inc-fn (fn [event]
                   (-> event
                       (update "value" inc)
                       (assoc "id" ["process" "end"])))
          action (lift inc-fn)
          ;; Create the runtime function (f2)
          f2 (action {} {} {} {})
          ;; Track handler calls
          results (atom nil)]
      ;; Call the action
      (f2 {:test true}
          {"id" ["start" "process"] "value" 41}
          []
          (fn [ctx event] (reset! results {:ctx ctx :event event})))
      ;; Verify transformation
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
;; Identity FSM for Testing
;;------------------------------------------------------------------------------

(defn make-identity-fsm
  "Create an FSM that passes input through unchanged.
   Input: {\"id\" [\"start\" \"process\"] \"value\" any}
   Output: {\"id\" [\"process\" \"end\"] \"value\" same}"
  []
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
  "Action that transforms start->process event to process->end."
  (lift (fn [event]
          (-> event
              (assoc "id" ["process" "end"])))))

;;------------------------------------------------------------------------------
;; Increment FSM for Testing
;;------------------------------------------------------------------------------

(defn make-inc-fsm
  "Create an FSM that increments a value by 1.
   Input: {\"id\" [\"start\" \"process\"] \"value\" int}
   Output: {\"id\" [\"process\" \"end\"] \"value\" int+1}"
  []
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
  "Action that increments the value field."
  (lift (fn [event]
          (-> event
              (update "value" inc)
              (assoc "id" ["process" "end"])))
        {:name "increment"}))

;;------------------------------------------------------------------------------
;; End Action (delivers to completion promise)
;;------------------------------------------------------------------------------

(def-action end-action
  "End action - delivers completion to promise."
  :any
  [_config _fsm _ix _state]
  (fn [context _event trail _handler]
    (when-let [p (:fsm/completion-promise context)]
      (deliver p [context trail]))))

;;------------------------------------------------------------------------------
;; Test Context
;;------------------------------------------------------------------------------

(def test-context
  {:id->action {"pass-through" pass-through-action
                "increment" increment-action
                "end" #'end-action}})

;;------------------------------------------------------------------------------
;; Single FSM Tests (verify test infrastructure)
;;------------------------------------------------------------------------------

(deftest single-fsm-with-lift-test
  (testing "Single FSM with lifted action works"
    (let [fsm (make-identity-fsm)
          result (run-sync fsm test-context
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
          result (run-sync fsm test-context
                           {"id" ["start" "process"] "value" 0}
                           5000)]
      (is (not= :timeout result))
      (when (not= :timeout result)
        (let [[_ctx trail] result
              output (last-event trail)]
          (is (= 1 (get output "value"))))))))

;;------------------------------------------------------------------------------
;; Chain Tests
;;------------------------------------------------------------------------------

(deftest chain-validation-test
  (testing "chain requires at least 2 FSMs"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"chain requires at least 2 FSMs"
                          (chain test-context (make-inc-fsm))))))

(deftest chain-lifecycle-test
  (testing "chain returns expected interface"
    (let [fsm (make-inc-fsm)
          result (chain test-context fsm fsm)]
      (is (fn? (:start result)))
      (is (fn? (:stop result)))
      (is (fn? (:submit result)))
      (is (fn? (:await result)))))

  (testing "submit before start throws"
    (let [fsm (make-inc-fsm)
          {:keys [submit]} (chain test-context fsm fsm)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Chain not started"
                            (submit {"id" ["start" "process"] "value" 0})))))

  (testing "await before start throws"
    (let [fsm (make-inc-fsm)
          {:keys [await]} (chain test-context fsm fsm)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Chain not started"
                            (await 1000)))))

  (testing "double start throws"
    (let [fsm (make-inc-fsm)
          {:keys [start stop]} (chain test-context fsm fsm)]
      (start)
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Chain already started"
                              (start)))
        (finally
          (stop))))))

;;------------------------------------------------------------------------------
;; Chain Execution Tests
;;------------------------------------------------------------------------------

(deftest chain-two-fsms-test
  (testing "Chain of 2 increment FSMs adds 2"
    (let [fsm (make-inc-fsm)
          {:keys [start stop submit await]} (chain test-context fsm fsm)]
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
          {:keys [start stop submit await]} (chain test-context fsm fsm fsm)]
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
          {:keys [start stop submit await]} (chain test-context id-fsm inc-fsm)]
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
  (testing "Stop is called without errors"
    (let [fsm (make-inc-fsm)
          ;; We'll verify stop doesn't throw by running a full cycle
          {:keys [start stop submit await]} (chain test-context fsm fsm)]
      (start)
      (submit {"id" ["start" "process"] "value" 0})
      (await 5000)
      ;; Stop should complete without throwing
      (is (do (stop) true) "Stop should complete cleanly"))))

;;------------------------------------------------------------------------------
;; Edge Cases
;;------------------------------------------------------------------------------

(deftest chain-timeout-test
  (testing "Chain await respects timeout"
    (let [;; Create an FSM that will hang (no end action)
          hanging-fsm {"id" "hanging"
                       "states" [{"id" "process" "action" "hang"}]
                       "xitions" [{"id" ["start" "process"]
                                   "schema" [:map ["id" [:= ["start" "process"]]]]}]}
          hang-action (lift (fn [_] nil) {:name "hang"}) ;; Returns nil, never transitions
          ctx {:id->action {"hang" hang-action}}
          {:keys [start stop submit await]} (chain ctx hanging-fsm hanging-fsm)]
      (start)
      (submit {"id" ["start" "process"]})
      (let [result (await 100)]
        (is (= :timeout result) "Should timeout waiting for completion"))
      (stop))))
