(ns claij.fsm.registry
  "FSM registry with reactive OpenAPI spec generation.
   
   Maintains a registry of FSM definitions and automatically regenerates
   the OpenAPI spec whenever the registry changes.
   
   Usage:
     (register-fsm! \"my-fsm\" my-fsm-definition)
     (get-openapi-spec)  ; Returns current OpenAPI spec
     (unregister-fsm! \"my-fsm\")"
  (:require
   [clojure.tools.logging :as log]
   [claij.fsm :as fsm]))

;; =============================================================================
;; FSM Registry
;; =============================================================================

(defonce ^{:doc "Atom containing registered FSMs.
   Structure: {\"fsm-id\" {:definition {...}
                          :input-schema {...}
                          :output-schema {...}}}"}
  fsm-registry
  (atom {}))

(defn register-fsm!
  "Register an FSM definition. Extracts input/output schemas for OpenAPI generation.
   
   Returns the registered entry."
  [fsm-id fsm-definition]
  (log/info "Registering FSM:" fsm-id)
  (let [{:keys [input-schema output-schema]} (fsm/fsm-schemas {} fsm-definition)
        entry {:definition fsm-definition
               :input-schema input-schema
               :output-schema output-schema}]
    (swap! fsm-registry assoc fsm-id entry)
    entry))

(defn unregister-fsm!
  "Remove an FSM from the registry.
   
   Returns the removed entry or nil if not found."
  [fsm-id]
  (log/info "Unregistering FSM:" fsm-id)
  (let [removed (get @fsm-registry fsm-id)]
    (swap! fsm-registry dissoc fsm-id)
    removed))

(defn get-fsm
  "Get an FSM definition by id. Returns nil if not found."
  [fsm-id]
  (get-in @fsm-registry [fsm-id :definition]))

(defn get-fsm-entry
  "Get the full FSM registry entry (definition + schemas) by id."
  [fsm-id]
  (get @fsm-registry fsm-id))

(defn list-fsm-ids
  "List all registered FSM ids."
  []
  (keys @fsm-registry))

(defn list-fsms
  "List all registered FSMs with their metadata."
  []
  (into {}
        (for [[id {:keys [definition]}] @fsm-registry]
          [id {:states (count (get definition "states"))
               :transitions (count (get definition "xitions"))
               :schemas (count (get definition "schemas"))}])))

;; =============================================================================
;; OpenAPI Spec Generation
;; =============================================================================

(defn- build-schema-with-defs
  "Build a complete JSON Schema with $defs from FSM schemas.
   Resolves the top-level $ref and includes all referenced definitions."
  [schema-ref fsm-schemas]
  (if-let [ref-path (get schema-ref "$ref")]
    ;; It's a $ref - build schema with $defs
    (let [;; Extract schema name from #/$defs/name
          schema-name (when (string? ref-path)
                        (second (re-matches #"#/\$defs/(.+)" ref-path)))]
      (if (and schema-name (contains? fsm-schemas schema-name))
        ;; Build complete schema with $defs
        (merge (get fsm-schemas schema-name)
               {"$defs" fsm-schemas})
        ;; Fallback - return as-is
        schema-ref))
    ;; Not a $ref - return as-is
    (or schema-ref {"type" "object"})))

(defn- fsm-entry->openapi-path
  "Generate OpenAPI path entry for an FSM's /run endpoint."
  [fsm-id {:keys [definition input-schema output-schema]}]
  (let [fsm-schemas (get definition "schemas")
        request-schema (build-schema-with-defs input-schema fsm-schemas)
        response-schema (build-schema-with-defs output-schema fsm-schemas)]
    {(str "/fsm/" fsm-id "/run")
     {"post"
      {"summary" (str "Run " fsm-id " FSM")
       "description" (str "Submit input to the " fsm-id " FSM and receive the result.")
       "operationId" (str "run-" fsm-id)
       "tags" ["FSM Execution"]
       "requestBody"
       {"required" true
        "content"
        {"application/json"
         {"schema" request-schema}}}
       "responses"
       {"200"
        {"description" "FSM completed successfully"
         "content"
         {"application/json"
          {"schema" response-schema}}}
        "400"
        {"description" "Invalid input"
         "content"
         {"application/json"
          {"schema" {"type" "object"
                     "properties" {"error" {"type" "string"}}}}}}
        "500"
        {"description" "FSM execution error"
         "content"
         {"application/json"
          {"schema" {"type" "object"
                     "properties" {"error" {"type" "string"}
                                   "details" {"type" "object"}}}}}}}}}}))

(defn generate-openapi-spec
  "Pure function: Generate OpenAPI 3.1 spec from an FSM registry map.
   
   The registry is a map of {fsm-id -> entry} where entry contains:
   - :definition - The FSM definition map
   - :input-schema - JSON Schema for FSM input (may be a $ref)
   - :output-schema - JSON Schema for FSM output (may be a $ref)
   
   Returns a complete OpenAPI 3.1.0 spec with:
   - /fsm/{id}/run POST endpoint for each FSM
   - Request/response schemas with $defs resolved inline
   - jsonSchemaDialect set to JSON Schema 2020-12 (fully supported)
   
   This is the core pure function. The atom watch in this namespace
   calls this on every registry change (add/update/delete) for a
   brute-force rebuild approach."
  [registry]
  (let [paths (into {} (for [[fsm-id entry] registry]
                         (fsm-entry->openapi-path fsm-id entry)))]
    {"openapi" "3.1.0"
     "info" {"title" "CLAIJ FSM API"
             "description" "Dynamic FSM endpoints - auto-generated from registered FSMs"
             "version" "0.2.0"}
     "jsonSchemaDialect" "https://json-schema.org/draft/2020-12/schema"
     "paths" paths}))

;; =============================================================================
;; Reactive OpenAPI Spec (updates when registry changes)
;; =============================================================================

(defonce ^{:doc "Atom containing the current OpenAPI spec. Updated reactively when fsm-registry changes."}
  openapi-spec
  (atom {}))

(defn- update-openapi-spec!
  "Regenerate OpenAPI spec from current registry state."
  [_key _ref _old-val new-val]
  (log/info "FSM registry changed, regenerating OpenAPI spec...")
  (let [spec (generate-openapi-spec new-val)]
    (reset! openapi-spec spec)
    (log/info "OpenAPI spec updated with" (count new-val) "FSMs")))

;; Set up the watch
(add-watch fsm-registry :openapi-updater update-openapi-spec!)

(defn get-openapi-spec
  "Get the current OpenAPI spec."
  []
  @openapi-spec)

;; =============================================================================
;; Initialization helpers
;; =============================================================================

(defn register-all!
  "Register multiple FSMs at once. Takes a map of {fsm-id fsm-definition}."
  [fsms-map]
  (doseq [[fsm-id fsm-def] fsms-map]
    (register-fsm! fsm-id fsm-def)))

(defn clear-registry!
  "Clear all registered FSMs. Useful for testing."
  []
  (log/info "Clearing FSM registry")
  (reset! fsm-registry {}))
