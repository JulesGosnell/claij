(ns claij.util
  (:require
   [clojure.tools.logging :as log]
   [clojure.data.json :refer [read-str write-str]]
   [m3.validate :refer [validate $schema->m2]]
   [claij.env :refer [getenv]]))

(defn assert-env-var
  "Get environment variable, throwing if not found.
   Checks claij.env/env map which includes .env file and System/getenv."
  [v]
  (if-let [k (getenv v)]
    k
    (throw (ex-info (str "Missing environment variable: " v) {:var v}))))

(defn trace [m s]
  ;;(prn m ":" s)
  s)

(defn json->clj [json]
  (read-str json :key-fn keyword))

(defn clj->json [clj]
  (write-str clj))

(defn index-by [f es]
  (reduce (fn [acc e] (assoc acc (f e) e)) {} es))

(defn map-values [f m] (reduce-kv (fn [acc k v] (assoc acc k (f k v))) {} m))

(defn ->key [s] (fn [m] (m s)))

;;------------------------------------------------------------------------------
;; Retry utilities

(defn should-retry?
  "Returns true if attempt-num is less than max-retries.
   Attempt numbers are 0-indexed (0 = first attempt)."
  [attempt-num max-retries]
  (< attempt-num max-retries))

(defn make-retrier
  "Creates a retry function that executes operations with retry logic.
   
   Returns a function that takes:
   - attempt-num: Current attempt number (0-indexed, 0 = first attempt)
   - operation: Function to execute if retry should happen
   - on-max-retries: Function to call if max retries exceeded (optional)
   
   The retrier logs appropriately and returns:
   - Result of operation if retry happens
   - Result of on-max-retries if max exceeded (or nil if not provided)
   
   Example:
   (let [retrier (make-retrier 3)]
     (retrier 0 
              #(try-something)
              #(log/error \"Failed after 3 attempts\")))"
  [max-retries]
  (fn
    ([attempt-num operation]
     ((make-retrier max-retries) attempt-num operation nil))
    ([attempt-num operation on-max-retries]
     (if (should-retry? attempt-num max-retries)
       (do
         (when (> attempt-num 0)
           (log/info (str "Retry attempt " attempt-num "/" (dec max-retries))))
         (operation))
       (do
         (log/error (str "Max retries (" max-retries ") exceeded"))
         (when on-max-retries (on-max-retries)))))))

;;------------------------------------------------------------------------------
;; TODO: this should go back into m3

(defn valid-m2? [{m3-id "$schema" m2-id "$id" :as m2}]
  (let [{v? :valid? es :errors} (validate {} ($schema->m2 m3-id) {} m2)]
    (if v?
      true
      (do
        (log/errorf "bad schema: %s - %s" m2-id (pr-str es))
        false))))

(defmacro def-m2 [name schema]
  `(def ~name
     (let [s# ~schema]
       (assert (valid-m2? s#) "Invalid schema")
       s#)))

(defn valid-m1? [m2 m1]
  (let [{v? :valid? es :errors} (validate {} m2 {} m1)]
    (if v?
      true
      (do
        (log/errorf "bad document: %s - %s" (pr-str m1) (pr-str es))
        false))))

(defmacro def-m1 [name m2 m1]
  `(def ~name
     (let [t2# ~m2 t1# ~m1]
       (assert (valid-m1? t2# t1#) "Invalid schema")
       t1#)))
