(ns claij.util
  (:require
   [clojure.tools.logging :as log]
   [clojure.data.json :refer [read-str write-str]]))

(defn assert-env-var
  "Get environment variable, throwing if not found."
  [v]
  (if-let [k (System/getenv v)]
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


