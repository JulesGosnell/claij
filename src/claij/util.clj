(ns claij.util
  (:require
   [clojure.data.json :refer [read-str write-str]]))

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

