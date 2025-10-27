(ns claij.util.xform-test
  (:require [clojure.test :refer [deftest is testing]]
            [claij.util.xform :refer [batching-transducer]]))

(deftest test-batching-transducer
  (testing "Batches up to max-count"
    (let [events [{:tag :ret, :val "1", :time-ms 0}
                  {:tag :ret, :val "2", :time-ms 0}
                  {:tag :ret, :val "3", :time-ms 0}]
          txf (batching-transducer 3 3000)]
      (is (= [events] (transduce txf conj [] events)))))
  (testing "Emits remaining batch on complete"
    (let [events [{:tag :ret, :val "1", :time-ms 0}
                  {:tag :ret, :val "2", :time-ms 0}]
          txf (batching-transducer 3 3000)]
      (is (= [events] (transduce txf conj [] events)))))
  #_(testing "Batches after timeout-ms"
      (let [events [{:tag :ret, :val "1", :time-ms 0}
                    {:tag :ret, :val "2", :time-ms 2000}
                    {:tag :ret, :val "3", :time-ms 4000}]
            txf (batching-transducer 100 3000)]
        (is (= [[{:tag :ret, :val "1", :time-ms 0}
                 {:tag :ret, :val "2", :time-ms 2000}]
                [{:tag :ret, :val "3", :time-ms 4000}]]
               (transduce txf conj [] events)))))
  (testing "Handles empty input"
    (let [events []
          txf (batching-transducer 3 3000)]
      (is (= [] (transduce txf conj [] events))))))
