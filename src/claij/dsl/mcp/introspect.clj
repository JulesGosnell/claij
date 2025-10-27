(ns claij.dsl.mcp.introspect
  "Introspection utilities for MCP DSL namespaces."
  (:require [clojure.string :as str]))

(defn describe-function
  "Describe a single function with its signature and docstring.
  
  Returns a map with :name, :arglists, :doc"
  [var-obj]
  (let [m (meta var-obj)]
    {:name (symbol (str (:ns m)) (str (:name m)))
     :arglists (:arglists m)
     :doc (:doc m)}))

(defn list-namespace-api
  "List all public functions in a namespace with their documentation.
  
  Parameters:
  - ns-name: Symbol or namespace object
  
  Returns:
  - Vector of maps, each with :name, :arglists, :doc"
  [ns-name]
  (let [ns-obj (if (symbol? ns-name)
                 (find-ns ns-name)
                 ns-name)]
    (when ns-obj
      (vec
       (for [[_sym var-obj] (ns-publics ns-obj)]
         (describe-function var-obj))))))

(defn format-function-signature
  "Format a function signature for display to LLM.
  
  Example: (mock-server/echo text) - Echoes back the input text"
  [{:keys [name arglists doc]}]
  (let [arglist (first arglists) ; Use first arglist
        sig (str "(" name " " (str/join " " arglist) ")")]
    (str sig (when doc (str " - " (first (str/split-lines doc)))))))

(defn format-namespace-api
  "Format complete namespace API for LLM consumption.
  
  Returns a string describing all functions in the namespace."
  [ns-name]
  (let [api (list-namespace-api ns-name)]
    (when (seq api)
      (str "Functions in " ns-name ":\n"
           (str/join "\n" 
                     (map #(str "  " (format-function-signature %))
                          api))))))

(defn get-all-mcp-namespaces
  "Get all loaded namespaces that start with 'claij.dsl.mcp.' (excluding core/codegen/introspect).
  
  Returns vector of namespace symbols."
  []
  (vec
   (for [ns-obj (all-ns)
         :let [ns-name (ns-name ns-obj)]
         :when (and (str/starts-with? (str ns-name) "claij.dsl.mcp.")
                    (not (re-find #"\.(codegen|introspect)$" (str ns-name))))]
     ns-name)))

(defn format-all-mcp-apis
  "Format all MCP DSL namespace APIs for LLM consumption.
  
  Returns a string describing all available MCP DSLs."
  []
  (let [namespaces (get-all-mcp-namespaces)]
    (if (empty? namespaces)
      "No MCP services currently available."
      (str/join "\n\n" (map format-namespace-api namespaces)))))

(comment
  ;; Example usage
  (list-namespace-api 'claij.dsl.mcp)
  
  (format-namespace-api 'claij.dsl.mcp)
  
  (get-all-mcp-namespaces)
  
  (format-all-mcp-apis))
