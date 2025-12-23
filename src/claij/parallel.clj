(ns claij.parallel
  "Shared parallel/batch execution for async operations.
   
   Used by both MCP (multi-server tool calls) and LLM (multi-model queries)
   to execute operations concurrently with timeout and partial result handling."
  (:require
   [clojure.tools.logging :as log])
  (:import
   [java.util.concurrent CountDownLatch TimeUnit]))

(defn collect-async
  "Execute multiple async operations, collect results with timeout.
   
   Each operation is a map with:
   - :id       - unique identifier for this operation (string)
   - :fn       - function of (on-success on-error) that initiates async work
                 on-success: (fn [result] ...) - call with successful result
                 on-error: (fn [error] ...) - call with error info
   
   Options:
   - :timeout-ms - max time to wait for all operations (default: 30000)
   - :parallel?  - true for concurrent execution, false for sequential (default: true)
   
   Returns:
   {:results {\"op-1\" {:status :success :value result}
              \"op-2\" {:status :error :error error-info}
              \"op-3\" {:status :timeout}}
    :all-succeeded? boolean
    :completed-count n
    :failed-ids [...]
    :timed-out-ids [...]}"
  [operations {:keys [timeout-ms parallel?]
               :or {timeout-ms 30000 parallel? true}}]
  (if (empty? operations)
    {:results {}
     :all-succeeded? true
     :completed-count 0
     :failed-ids []
     :timed-out-ids []}

    (let [n (count operations)
          latch (CountDownLatch. n)
          results (atom {})

          ;; Create wrapped operation that updates results and counts down
          wrap-op (clojure.core/fn [{:keys [id] op-fn :fn}]
                    (clojure.core/fn []
                      (try
                        (op-fn
                         ;; on-success callback
                         (clojure.core/fn [value]
                           (swap! results assoc id {:status :success :value value})
                           (.countDown latch))
                         ;; on-error callback
                         (clojure.core/fn [error]
                           (swap! results assoc id {:status :error :error error})
                           (.countDown latch)))
                        (catch Throwable t
                          (log/error t "Exception in operation" id)
                          (swap! results assoc id {:status :error
                                                   :error {:exception (.getMessage t)
                                                           :type (str (type t))}})
                          (.countDown latch)))))

          ;; Execute operations
          _ (if parallel?
              ;; Parallel: launch all in futures
              (doseq [op operations]
                (future ((wrap-op op))))
              ;; Sequential: run one at a time
              (doseq [op operations]
                ((wrap-op op))))

          ;; Wait for completion or timeout
          completed? (.await latch timeout-ms TimeUnit/MILLISECONDS)

          ;; Mark any incomplete operations as timed out
          final-results (reduce
                         (fn [acc {:keys [id]}]
                           (if (contains? acc id)
                             acc
                             (assoc acc id {:status :timeout})))
                         @results
                         operations)

          ;; Compute summary
          succeeded (filter (fn [[_ v]] (= :success (:status v))) final-results)
          failed (filter (fn [[_ v]] (= :error (:status v))) final-results)
          timed-out (filter (fn [[_ v]] (= :timeout (:status v))) final-results)]

      {:results final-results
       :all-succeeded? (and completed? (= n (count succeeded)))
       :completed-count (+ (count succeeded) (count failed))
       :failed-ids (mapv first failed)
       :timed-out-ids (mapv first timed-out)})))

(defn collect-sync
  "Convenience wrapper for synchronous operations.
   
   Each operation is a map with:
   - :id - unique identifier
   - :fn - function of () that returns result or throws
   
   Wraps sync functions to work with collect-async."
  [operations opts]
  (let [async-ops (mapv (clojure.core/fn [{:keys [id] op-fn :fn}]
                          {:id id
                           :fn (clojure.core/fn [on-success on-error]
                                 (try
                                   (on-success (op-fn))
                                   (catch Throwable t
                                     (on-error {:exception (.getMessage t)
                                                :type (str (type t))}))))})
                        operations)]
    (collect-async async-ops opts)))
