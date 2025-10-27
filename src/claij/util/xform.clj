(ns claij.util.xform)

(defn batching-transducer
  "Transducer that batches events up to max-count or after timeout-ms milliseconds.
   Returns a vector of events when count >= max-count, time >= timeout-ms, or on complete.
   Events must include :time-ms (milliseconds) for time-based batching."
  [max-count timeout-ms]
  (fn [rf]
    (let [batch (volatile! [])
          start-time (volatile! nil)]
      (fn
        ([] (rf)) ; Init
        ([result] ; Complete
         (let [b @batch]
           (if (empty? b)
             (rf result)
             (rf result b))))
        ([result event] ; Step
         (let [current-time (:time-ms event 0)
               start (or @start-time current-time)]
           (vswap! batch conj event)
           (let [b @batch
                 elapsed (- current-time start)]
             (if (or (>= (count b) max-count) (>= elapsed timeout-ms))
               (do
                 (vreset! batch [])
                 (vreset! start-time nil) ; Reset for next batch
                 (rf result b))
               (do
                 (when-not @start-time
                   (vreset! start-time current-time)) ; Set start for first event
                 result)))))))))
