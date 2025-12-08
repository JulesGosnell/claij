(ns user
  "User namespace loaded automatically in dev/REPL environments.
  
  Loads environment variables from .env file for development."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn load-env-file
  "Load environment variables from .env file.
  
  Parses lines like: export KEY=\"value\"
  and sets them as Java system properties."
  []
  (let [env-file (io/file ".env")]
    (when (.exists env-file)
      ;;(println "Loading environment variables from .env...")
      (doseq [line (line-seq (io/reader env-file))
              :let [line (str/trim line)]
              :when (and (not (str/blank? line))
                         (not (str/starts-with? line "#"))
                         (str/starts-with? line "export"))]
        (when-let [[_ key value] (re-matches #"export\s+([^=]+)=\"([^\"]*)\"" line)]
          (System/setProperty key value)
          ;;(println "  -" key "=" (subs value 0 (min 20 (count value))) "...")
          ))
      ;;(println "Environment variables loaded!")
      )))

;; Load .env automatically when this namespace loads
(load-env-file)

;;(println "User namespace loaded. .env variables available.")
