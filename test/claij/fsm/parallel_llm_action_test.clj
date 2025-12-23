(ns claij.fsm.parallel-llm-action-test
  "Tests for parallel-llm-action in claij.fsm"
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.fsm :refer [parallel-llm-action]]
   [claij.llm :as llm]))

;;==============================================================================
;; Test fixtures and helpers
;;==============================================================================

(def mock-fsm
  {"id" "test-fsm"
   "states" [{"id" "parallel" "action" "parallel-llm"}
             {"id" "next" "action" "end"}]
   "xitions" [{"id" ["parallel" "next"]}]})

(def mock-ix {"id" ["start" "parallel"]})

(def mock-state {"id" "parallel" "action" "parallel-llm"})

(def mock-config
  {"timeout-ms" 5000
   "parallel?" true
   "llms" [{"id" "llm-a" "service" "test" "model" "model-a"}
           {"id" "llm-b" "service" "test" "model" "model-b"}]})

;;==============================================================================
;; Unit tests
;;==============================================================================

(deftest parallel-llm-action-all-succeed
  (testing "All LLMs respond successfully"
    (let [action-fn (parallel-llm-action mock-config mock-fsm mock-ix mock-state)
          result (atom nil)
          handler (fn [_ctx event] (reset! result event))]
      (with-redefs [llm/call (fn [service model _prompts on-success _opts]
                               ;; Simulate async response
                               (future
                                 (Thread/sleep 10)
                                 (on-success {"id" "response"
                                              "from" (str service "/" model)})))]
        (action-fn {} {"input" "test"} [] handler)
        ;; Wait for completion
        (Thread/sleep 200)
        (is (some? @result))
        (is (= "parallel-complete" (get @result "id")))
        (is (= :success (get-in @result ["responses" "llm-a" :status])))
        (is (= :success (get-in @result ["responses" "llm-b" :status])))
        (is (true? (get-in @result ["summary" :all-succeeded?])))
        (is (= 2 (get-in @result ["summary" :completed-count])))
        (is (empty? (get-in @result ["summary" :timed-out-ids])))))))

(deftest parallel-llm-action-one-fails
  (testing "One LLM fails, others succeed"
    (let [action-fn (parallel-llm-action mock-config mock-fsm mock-ix mock-state)
          result (atom nil)
          handler (fn [_ctx event] (reset! result event))]
      (with-redefs [llm/call (fn [service model _prompts on-success opts]
                               (future
                                 (Thread/sleep 10)
                                 (if (= model "model-a")
                                   (on-success {"id" "response" "from" "a"})
                                   ;; model-b fails
                                   ((:error opts) {:msg "API error"}))))]
        (action-fn {} {"input" "test"} [] handler)
        (Thread/sleep 200)
        (is (some? @result))
        (is (= :success (get-in @result ["responses" "llm-a" :status])))
        (is (= :error (get-in @result ["responses" "llm-b" :status])))
        (is (false? (get-in @result ["summary" :all-succeeded?])))
        (is (= 2 (get-in @result ["summary" :completed-count])))
        (is (= ["llm-b"] (get-in @result ["summary" :failed-ids])))))))

(deftest parallel-llm-action-timeout
  (testing "Timeout with partial results"
    (let [config (assoc mock-config "timeout-ms" 50) ;; Very short timeout
          action-fn (parallel-llm-action config mock-fsm mock-ix mock-state)
          result (atom nil)
          handler (fn [_ctx event] (reset! result event))]
      (with-redefs [llm/call (fn [_service model _prompts on-success _opts]
                               (future
                                 (if (= model "model-a")
                                   (do (Thread/sleep 10) (on-success {"id" "fast"}))
                                   ;; model-b is slow - will timeout
                                   (do (Thread/sleep 5000) (on-success {"id" "slow"})))))]
        (action-fn {} {"input" "test"} [] handler)
        (Thread/sleep 200)
        (is (some? @result))
        (is (= :success (get-in @result ["responses" "llm-a" :status])))
        (is (= :timeout (get-in @result ["responses" "llm-b" :status])))
        (is (false? (get-in @result ["summary" :all-succeeded?])))
        (is (= 1 (get-in @result ["summary" :completed-count])))
        (is (= ["llm-b"] (get-in @result ["summary" :timed-out-ids])))))))

(deftest parallel-llm-action-all-timeout
  (testing "All LLMs timeout"
    (let [config (assoc mock-config "timeout-ms" 10) ;; Very short timeout
          action-fn (parallel-llm-action config mock-fsm mock-ix mock-state)
          result (atom nil)
          handler (fn [_ctx event] (reset! result event))]
      (with-redefs [llm/call (fn [_service _model _prompts on-success _opts]
                               ;; Both are slow
                               (future
                                 (Thread/sleep 5000)
                                 (on-success {"id" "never"})))]
        (action-fn {} {"input" "test"} [] handler)
        (Thread/sleep 200)
        (is (some? @result))
        (is (= :timeout (get-in @result ["responses" "llm-a" :status])))
        (is (= :timeout (get-in @result ["responses" "llm-b" :status])))
        (is (false? (get-in @result ["summary" :all-succeeded?])))
        (is (= 0 (get-in @result ["summary" :completed-count])))
        (is (= 2 (count (get-in @result ["summary" :timed-out-ids]))))))))

(deftest parallel-llm-action-sequential-mode
  (testing "Sequential mode executes in order"
    (let [config (assoc mock-config "parallel?" false)
          action-fn (parallel-llm-action config mock-fsm mock-ix mock-state)
          result (atom nil)
          order (atom [])
          handler (fn [_ctx event] (reset! result event))]
      (with-redefs [llm/call (fn [_service model _prompts on-success _opts]
                               (swap! order conj model)
                               (on-success {"id" "response" "model" model}))]
        (action-fn {} {"input" "test"} [] handler)
        (Thread/sleep 100)
        (is (some? @result))
        ;; Sequential should maintain order
        (is (= ["model-a" "model-b"] @order))
        (is (true? (get-in @result ["summary" :all-succeeded?])))))))

(deftest parallel-llm-action-symbolic-ids-flow-through
  (testing "Symbolic IDs flow through request to response"
    (let [config {"timeout-ms" 5000
                  "llms" [{"id" "analyst-alpha" "service" "s1" "model" "m1"}
                          {"id" "analyst-beta" "service" "s2" "model" "m2"}
                          {"id" "analyst-gamma" "service" "s3" "model" "m3"}]}
          action-fn (parallel-llm-action config mock-fsm mock-ix mock-state)
          result (atom nil)
          handler (fn [_ctx event] (reset! result event))]
      (with-redefs [llm/call (fn [service _model _prompts on-success _opts]
                               (future
                                 (Thread/sleep 10)
                                 (on-success {"id" "done" "source" service})))]
        (action-fn {} {"input" "test"} [] handler)
        (Thread/sleep 200)
        (is (some? @result))
        ;; All symbolic IDs should be in results
        (is (contains? (get @result "responses") "analyst-alpha"))
        (is (contains? (get @result "responses") "analyst-beta"))
        (is (contains? (get @result "responses") "analyst-gamma"))
        ;; Each should have the correct value
        (is (= "s1" (get-in @result ["responses" "analyst-alpha" :value "source"])))
        (is (= "s2" (get-in @result ["responses" "analyst-beta" :value "source"])))
        (is (= "s3" (get-in @result ["responses" "analyst-gamma" :value "source"])))))))

(deftest parallel-llm-action-default-options
  (testing "Default options are applied correctly"
    (let [config {"llms" [{"id" "x" "service" "s" "model" "m"}]}
          action-fn (parallel-llm-action config mock-fsm mock-ix mock-state)
          result (atom nil)
          handler (fn [_ctx event] (reset! result event))]
      (with-redefs [llm/call (fn [_service _model _prompts on-success _opts]
                               (on-success {"id" "ok"}))]
        (action-fn {} {"input" "test"} [] handler)
        (Thread/sleep 100)
        (is (some? @result))
        (is (true? (get-in @result ["summary" :all-succeeded?])))))))
