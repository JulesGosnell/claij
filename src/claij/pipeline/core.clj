(ns claij.pipeline.core
  "Channel-based pipeline for composing audio/AI components."
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]))

(defprotocol PipelineComponent
  (start! [this] "Start the component.")
  (stop! [this] "Stop the component.")
  (input-chan [this] "Get input channel.")
  (output-chan [this] "Get output channel."))

(defn source [producer-fn]
  (let [out (async/chan 10)
        running? (atom false)]
    (reify PipelineComponent
      (start! [this]
        (when (compare-and-set! running? false true)
          (async/go
            (try
              (producer-fn out)
              (catch Exception e
                (log/error e "Source failed"))
              (finally (async/close! out)))))
        this)
      (stop! [this]
        (reset! running? false)
        (async/close! out))
      (input-chan [_] nil)
      (output-chan [_] out))))

(defn transformer [transform-fn]
  (let [in (async/chan 10)
        out (async/chan 10)
        running? (atom false)]
    (reify PipelineComponent
      (start! [this]
        (when (compare-and-set! running? false true)
          (async/go
            (try
              (transform-fn in out)
              (catch Exception e
                (log/error e "Transformer failed"))
              (finally (async/close! out)))))
        this)
      (stop! [this]
        (reset! running? false)
        (async/close! in)
        (async/close! out))
      (input-chan [_] in)
      (output-chan [_] out))))

(defn sink [consumer-fn]
  (let [in (async/chan 10)
        running? (atom false)]
    (reify PipelineComponent
      (start! [this]
        (when (compare-and-set! running? false true)
          (async/go
            (try
              (consumer-fn in)
              (catch Exception e
                (log/error e "Sink failed")))))
        this)
      (stop! [this]
        (reset! running? false)
        (async/close! in))
      (input-chan [_] in)
      (output-chan [_] nil))))

(defn connect [upstream downstream]
  (when-let [out (output-chan upstream)]
    (when-let [in (input-chan downstream)]
      (async/pipe out in)))
  downstream)

(defn pipeline [& components]
  (reduce connect components)
  (reify PipelineComponent
    (start! [this]
      (doseq [c components] (start! c))
      this)
    (stop! [this]
      (doseq [c (reverse components)] (stop! c)))
    (input-chan [_] (input-chan (first components)))
    (output-chan [_] (output-chan (last components)))))

(defmacro ->pipeline [& components]
  `(pipeline ~@components))
