(ns claij.parallel-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [claij.parallel :as p]))

;;==============================================================================
;; collect-async tests
;;==============================================================================

(deftest collect-async-all-succeed-parallel
  (testing "All operations succeed in parallel mode"
    (let [result (p/collect-async
                  [{:id "a" :fn (fn [ok _] (ok 1))}
                   {:id "b" :fn (fn [ok _] (ok 2))}
                   {:id "c" :fn (fn [ok _] (ok 3))}]
                  {:timeout-ms 1000 :parallel? true})]
      (is (= {:status :success :value 1} (get-in result [:results "a"])))
      (is (= {:status :success :value 2} (get-in result [:results "b"])))
      (is (= {:status :success :value 3} (get-in result [:results "c"])))
      (is (:all-succeeded? result))
      (is (= 3 (:completed-count result)))
      (is (empty? (:failed-ids result)))
      (is (empty? (:timed-out-ids result))))))

(deftest collect-async-all-succeed-sequential
  (testing "All operations succeed in sequential mode"
    (let [order (atom [])
          result (p/collect-async
                  [{:id "a" :fn (fn [ok _] (swap! order conj "a") (ok 1))}
                   {:id "b" :fn (fn [ok _] (swap! order conj "b") (ok 2))}
                   {:id "c" :fn (fn [ok _] (swap! order conj "c") (ok 3))}]
                  {:timeout-ms 1000 :parallel? false})]
      (is (:all-succeeded? result))
      (is (= 3 (:completed-count result)))
      ;; Sequential should execute in order
      (is (= ["a" "b" "c"] @order)))))

(deftest collect-async-some-fail
  (testing "Some operations fail, others succeed"
    (let [result (p/collect-async
                  [{:id "ok-1" :fn (fn [ok _] (ok "good"))}
                   {:id "fail" :fn (fn [_ err] (err {:msg "bad"}))}
                   {:id "ok-2" :fn (fn [ok _] (ok "also good"))}]
                  {:timeout-ms 1000})]
      (is (= :success (get-in result [:results "ok-1" :status])))
      (is (= :error (get-in result [:results "fail" :status])))
      (is (= {:msg "bad"} (get-in result [:results "fail" :error])))
      (is (= :success (get-in result [:results "ok-2" :status])))
      (is (not (:all-succeeded? result)))
      (is (= 3 (:completed-count result)))
      (is (= ["fail"] (:failed-ids result)))
      (is (empty? (:timed-out-ids result))))))

(deftest collect-async-timeout-partial
  (testing "Timeout with partial results"
    (let [result (p/collect-async
                  [{:id "fast" :fn (fn [ok _] (ok "done"))}
                   {:id "slow" :fn (fn [ok _] (Thread/sleep 5000) (ok "never"))}]
                  {:timeout-ms 100})]
      (is (= :success (get-in result [:results "fast" :status])))
      (is (= :timeout (get-in result [:results "slow" :status])))
      (is (not (:all-succeeded? result)))
      (is (= 1 (:completed-count result)))
      (is (empty? (:failed-ids result)))
      (is (= ["slow"] (:timed-out-ids result))))))

(deftest collect-async-all-timeout
  (testing "All operations timeout"
    (let [result (p/collect-async
                  [{:id "a" :fn (fn [ok _] (Thread/sleep 5000) (ok 1))}
                   {:id "b" :fn (fn [ok _] (Thread/sleep 5000) (ok 2))}]
                  {:timeout-ms 50})]
      (is (= :timeout (get-in result [:results "a" :status])))
      (is (= :timeout (get-in result [:results "b" :status])))
      (is (not (:all-succeeded? result)))
      (is (= 0 (:completed-count result)))
      (is (= 2 (count (:timed-out-ids result)))))))

(deftest collect-async-empty-operations
  (testing "Empty operations list"
    (let [result (p/collect-async [] {:timeout-ms 1000})]
      (is (= {} (:results result)))
      (is (:all-succeeded? result))
      (is (= 0 (:completed-count result)))
      (is (empty? (:failed-ids result)))
      (is (empty? (:timed-out-ids result))))))

(deftest collect-async-single-operation
  (testing "Single operation (degenerate case)"
    (let [result (p/collect-async
                  [{:id "only" :fn (fn [ok _] (ok 42))}]
                  {:timeout-ms 1000})]
      (is (= {:status :success :value 42} (get-in result [:results "only"])))
      (is (:all-succeeded? result))
      (is (= 1 (:completed-count result))))))

(deftest collect-async-operations-complete-different-orders
  (testing "Operations complete in different orders"
    (let [result (p/collect-async
                  [{:id "slow" :fn (fn [ok _] (Thread/sleep 50) (ok "slow-done"))}
                   {:id "fast" :fn (fn [ok _] (ok "fast-done"))}
                   {:id "medium" :fn (fn [ok _] (Thread/sleep 25) (ok "medium-done"))}]
                  {:timeout-ms 1000 :parallel? true})]
      ;; All should succeed regardless of completion order
      (is (= "slow-done" (get-in result [:results "slow" :value])))
      (is (= "fast-done" (get-in result [:results "fast" :value])))
      (is (= "medium-done" (get-in result [:results "medium" :value])))
      (is (:all-succeeded? result))
      (is (= 3 (:completed-count result))))))

(deftest collect-async-error-doesnt-affect-others
  (testing "Error in one operation doesn't affect others"
    (let [result (p/collect-async
                  [{:id "before" :fn (fn [ok _] (ok "ok-before"))}
                   {:id "throws" :fn (fn [_ _] (throw (Exception. "kaboom")))}
                   {:id "after" :fn (fn [ok _] (ok "ok-after"))}]
                  {:timeout-ms 1000 :parallel? true})]
      (is (= :success (get-in result [:results "before" :status])))
      (is (= :error (get-in result [:results "throws" :status])))
      (is (= "kaboom" (get-in result [:results "throws" :error :exception])))
      (is (= :success (get-in result [:results "after" :status])))
      (is (= 3 (:completed-count result)))
      (is (= ["throws"] (:failed-ids result))))))

(deftest collect-async-default-options
  (testing "Default options work correctly"
    (let [result (p/collect-async
                  [{:id "a" :fn (fn [ok _] (ok 1))}]
                  {})] ;; Empty opts - should use defaults
      (is (:all-succeeded? result)))))

;;==============================================================================
;; collect-sync tests
;;==============================================================================

(deftest collect-sync-basic
  (testing "Sync wrapper works for successful operations"
    (let [result (p/collect-sync
                  [{:id "a" :fn (fn [] (+ 1 2))}
                   {:id "b" :fn (fn [] (* 3 4))}]
                  {:parallel? true})]
      (is (= 3 (get-in result [:results "a" :value])))
      (is (= 12 (get-in result [:results "b" :value])))
      (is (:all-succeeded? result)))))

(deftest collect-sync-with-exceptions
  (testing "Sync wrapper handles exceptions"
    (let [result (p/collect-sync
                  [{:id "ok" :fn (fn [] "success")}
                   {:id "throws" :fn (fn [] (throw (Exception. "sync error")))}]
                  {:parallel? true})]
      (is (= :success (get-in result [:results "ok" :status])))
      (is (= :error (get-in result [:results "throws" :status])))
      (is (= "sync error" (get-in result [:results "throws" :error :exception]))))))

(deftest collect-sync-sequential
  (testing "Sync wrapper works in sequential mode"
    (let [order (atom [])
          result (p/collect-sync
                  [{:id "a" :fn (fn [] (swap! order conj "a") 1)}
                   {:id "b" :fn (fn [] (swap! order conj "b") 2)}]
                  {:parallel? false})]
      (is (:all-succeeded? result))
      (is (= ["a" "b"] @order)))))

;;==============================================================================
;; Concurrency verification tests
;;==============================================================================

(deftest parallel-actually-parallel
  (testing "Parallel mode runs operations concurrently"
    (let [start (System/currentTimeMillis)
          result (p/collect-async
                  [{:id "a" :fn (fn [ok _] (Thread/sleep 100) (ok 1))}
                   {:id "b" :fn (fn [ok _] (Thread/sleep 100) (ok 2))}
                   {:id "c" :fn (fn [ok _] (Thread/sleep 100) (ok 3))}]
                  {:timeout-ms 5000 :parallel? true})
          elapsed (- (System/currentTimeMillis) start)]
      (is (:all-succeeded? result))
      ;; If parallel, should take ~100ms, not ~300ms
      ;; Allow some buffer for test execution overhead
      (is (< elapsed 250) (str "Parallel execution took " elapsed "ms, expected < 250ms")))))

(deftest sequential-actually-sequential
  (testing "Sequential mode runs operations one at a time"
    (let [start (System/currentTimeMillis)
          result (p/collect-async
                  [{:id "a" :fn (fn [ok _] (Thread/sleep 50) (ok 1))}
                   {:id "b" :fn (fn [ok _] (Thread/sleep 50) (ok 2))}
                   {:id "c" :fn (fn [ok _] (Thread/sleep 50) (ok 3))}]
                  {:timeout-ms 5000 :parallel? false})
          elapsed (- (System/currentTimeMillis) start)]
      (is (:all-succeeded? result))
      ;; If sequential, should take ~150ms minimum
      (is (>= elapsed 150) (str "Sequential execution took " elapsed "ms, expected >= 150ms")))))
