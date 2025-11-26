(ns claij.env
  "Environment configuration with .env file support.
   
   Loads environment variables from:
   1. .env file in project root (if exists)
   2. System environment variables (higher priority)
   
   Keys are keywordized: OPENROUTER_API_KEY -> :openrouter-api-key
   
   Usage:
     (require '[claij.env :refer [env]])
     (:openrouter-api-key env)
     
   Or with the helper:
     (require '[claij.env :refer [getenv]])
     (getenv \"OPENROUTER_API_KEY\")  ; returns nil if not found
     (getenv :openrouter-api-key)     ; keyword form also works"
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]))

(defn- keywordize-env-name
  "Convert env var name to keyword: OPENROUTER_API_KEY -> :openrouter-api-key"
  [s]
  (-> s
      str/trim
      str/lower-case
      (str/replace "_" "-")
      keyword))

(defn- parse-dotenv
  "Parse .env file into a map of keyword->value.
   Supports:
   - KEY=value
   - KEY=\"quoted value\"
   - export KEY=value (bash-style)
   - # comments
   - blank lines"
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (->> (slurp f)
           str/split-lines
           (map str/trim)
           (remove #(or (empty? %) (str/starts-with? % "#")))
           ;; Strip 'export ' prefix if present
           (map #(str/replace % #"^export\s+" ""))
           (filter #(str/includes? % "="))
           (map #(str/split % #"=" 2))
           (map (fn [[k v]]
                  [(keywordize-env-name k)
                   (-> v
                       str/trim
                       ;; Strip surrounding quotes if present
                       (str/replace #"^[\"']|[\"']$" ""))]))
           (into {})))))

(defn- system-env-map
  "Convert System/getenv to keyword map."
  []
  (->> (System/getenv)
       (map (fn [[k v]] [(keywordize-env-name k) v]))
       (into {})))

(def env
  "Environment map - merges .env file with system env vars.
   System env vars take priority over .env file.
   Keys are keywordized: OPENROUTER_API_KEY -> :openrouter-api-key"
  (merge (parse-dotenv ".env")
         (system-env-map)))

(defn getenv
  "Get environment variable by name (string or keyword).
   Returns nil if not found.
   
   Examples:
     (getenv \"OPENROUTER_API_KEY\")
     (getenv :openrouter-api-key)"
  [k]
  (if (keyword? k)
    (get env k)
    (get env (keywordize-env-name k))))
