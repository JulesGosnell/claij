(ns claij.dsl.mcp.codegen
  "Code generation for MCP DSL - creates namespace-qualified functions from tool definitions."
  (:require [clojure.string :as str]))

(defn- json-schema->params
  "Extract parameter names from JSON schema.
  
  Returns vector of symbols representing parameters."
  [input-schema]
  (let [props (get input-schema :properties {})
        required (set (get input-schema :required []))]
    (vec (for [[param-name param-spec] props]
           (symbol (name param-name))))))

(defn- generate-fn-docstring
  "Generate docstring for a tool function.
  
  Includes tool description and parameter descriptions."
  [tool-name tool-description input-schema]
  (let [props (get input-schema :properties {})
        param-docs (for [[param-name param-spec] props]
                     (str "  " (name param-name) " - " 
                          (get param-spec :description "No description")))]
    (str tool-description
         (when (seq param-docs)
           (str "\n\nParameters:\n" (str/join "\n" param-docs))))))

(defn- generate-tool-fn-code
  "Generate code for a single tool function.
  
  Returns a string of Clojure code defining the function."
  [bridge-id tool]
  (let [tool-name (:name tool)
        fn-sym (symbol tool-name)
        params (json-schema->params (:inputSchema tool))
        docstring (generate-fn-docstring 
                   tool-name 
                   (:description tool)
                   (:inputSchema tool))
        args-map (zipmap (map keyword params) params)]
    `(defn ~fn-sym
       ~docstring
       [~@params]
       (claij.dsl.mcp/call ~bridge-id ~tool-name ~args-map))))

(defn generate-dsl-namespace
  "Generate a complete namespace with functions for all tools.
  
  Parameters:
  - ns-name: Symbol for the namespace (e.g., 'claij.dsl.mcp.mock-server)
  - bridge-id: Bridge identifier keyword
  - tools: Vector of tool maps from bridge initialization
  
  Returns:
  - String containing complete namespace definition"
  [ns-name bridge-id tools]
  (let [ns-form `(~'ns ~ns-name
                   ~(str "Auto-generated MCP DSL for bridge " bridge-id)
                   (:require [claij.dsl.mcp]))
        tool-fns (map #(generate-tool-fn-code bridge-id %) tools)
        all-forms (cons ns-form tool-fns)]
    (str/join "\n\n" (map pr-str all-forms))))

(defn install-dsl-namespace!
  "Create and load a DSL namespace for a bridge.
  
  Parameters:
  - ns-name: Symbol for the namespace
  - bridge-id: Bridge identifier keyword  
  - tools: Vector of tool maps
  
  Returns:
  - The created namespace
  
  Example:
    (install-dsl-namespace! 'mock-server :bridge-1 tools)"
  [ns-name bridge-id tools]
  (let [code (generate-dsl-namespace ns-name bridge-id tools)]
    ;; Evaluate the generated code to create the namespace
    (load-string code)
    ;; Return the namespace
    (find-ns ns-name)))

(comment
  ;; Example usage
  (def tools
    [{:name "echo"
      :description "Echoes back the input text"
      :inputSchema
      {:type "object"
       :properties {:text {:type "string" :description "Text to echo"}}
       :required ["text"]}}])
  
  (generate-dsl-namespace 'my.mock-server :bridge-1 tools)
  
  ;; Install and use
  (install-dsl-namespace! 'my.mock-server :bridge-1 tools)
  (require '[my.mock-server :as ms])
  (ms/echo "Hello!"))
