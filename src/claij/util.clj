(ns claij.util
  (:require
   [clojure.tools.logging :as log]
   [clojure.data.json :refer [read-str write-str]]
   [m3.validate :refer [validate $schema->m2]]))

(defn assert-env-var [v]
  (if-let [k (System/getenv v)] k (throw (ex-info v {}))))

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
