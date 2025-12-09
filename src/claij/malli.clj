(ns claij.malli
  "Malli schema utilities for CLAIJ.
   
   Provides:
   - Schema validation with rich error reporting
   - Form transformations (vector ↔ map) for walkability
   - Transitive closure analysis for LLM emission
   - Token-optimized schema emission for prompts
   - Schema subsumption for type-safe FSM composition
   - def-m2/def-m1 macros for schema/document validation
   - Custom :json-schema type for embedding JSON Schema in Malli
   
   See doc/SELF-DESCRIPTIVE-SYSTEMS.md for architectural rationale."
  (:require
   [clojure.set :as set]
   [clojure.walk :refer [postwalk]]
   [clojure.string :refer [includes?]]
   [clojure.tools.logging :as log]
   [malli.core :as m]
   [malli.error :refer [humanize]]
   [malli.registry :as mr]
   [m3.validate :as m3]))

;;==============================================================================
;; Schema Validation
;;==============================================================================

(defn valid-schema?
  "Check if x is a valid Malli schema syntax.
   Returns true if schema can be compiled, false otherwise."
  [x]
  (try
    (m/schema x)
    true
    (catch Exception _
      false)))

(defn validate-schema
  "Validate that x is a valid Malli schema syntax.
   Returns {:valid? bool :error string :data exception-data}"
  [x]
  (try
    (m/schema x)
    {:valid? true}
    (catch Exception e
      {:valid? false
       :error (.getMessage e)
       :data (ex-data e)})))

(defn validate
  "Validate a value against a schema.
   Returns {:valid? bool :errors human-readable-errors}"
  ([schema value]
   (validate schema value nil))
  ([schema value opts]
   (let [valid? (m/validate schema value opts)]
     (if valid?
       {:valid? true}
       {:valid? false
        :errors (humanize (m/explain schema value opts))}))))

;;==============================================================================
;; Custom Schema Types
;;==============================================================================

(def MalliSchema
  "Schema type that validates Malli schema syntax.
   Use this in registries to allow schemas-as-values in documents."
  (m/-simple-schema
   {:type :malli/schema
    :pred valid-schema?
    :type-properties {:error/message "must be a valid Malli schema"
                      :description "A valid Malli schema (e.g. :string, [:map [:x :int]])"}}))

;; =============================================================================
;; JSON Schema Embedding in Malli
;; =============================================================================
;;
;; PROBLEM:
;; --------
;; MCP (Model Context Protocol) services define tools with inputSchema in
;; JSON Schema format. We want all CLAIJ schemas to be Malli for:
;;   - Single validation API
;;   - Malli schemas ARE EDN, sent directly to LLMs in prompts
;;   - Consistent error handling
;;
;; But we can't translate JSON Schema → Malli reliably:
;;   - No official converter exists
;;   - Conversions are lossy in both directions
;;   - Full JSON Schema is more expressive than Malli in some areas
;;
;; SOLUTION:
;; ---------
;; Custom Malli schema type [:json-schema {:schema <json-schema-def>}] that
;; delegates validation to m3 (our JSON Schema library).
;;
;; This gives us:
;;   - Single Malli-based validation API at the surface
;;   - JSON Schema validated by m3 under the hood where needed
;;   - One validation pass, one error path
;;   - No translation needed
;;
;; USAGE:
;; ------
;;   [:json-schema {:schema {"type" "object"
;;                           "properties" {"name" {"type" "string"}}
;;                           "required" ["name"]}}]
;;
;; Typically embedded in larger Malli schemas:
;;
;;   [:map {:closed true}
;;    ["name" [:= "bash"]]
;;    ["arguments" [:json-schema {:schema <inputSchema-from-MCP>}]]]
;;
;; REGISTRY CHAIN:
;; ---------------
;;   base-registry (in claij.malli)
;;     └── includes :json-schema custom type
;;     └── includes :malli/schema for meta-validation
;;
;;   mcp-registry (in claij.mcp)
;;     └── composes base-registry
;;     └── adds MCP-specific refs ("tool-response", "logging-set-level-request", etc.)
;;
;; Always use mcp-registry when validating MCP-related schemas.
;;
;; DEBUGGING VALIDATION FAILURES:
;; ------------------------------
;; When validation fails on a :json-schema type, Malli will report
;; "unknown error" because it doesn't know how to humanize m3's errors.
;;
;; To debug:
;;
;;   1. Extract the JSON Schema and value from the failing validation:
;;
;;      (def json-schema {"type" "object" "properties" {...} "required" [...]})
;;      (def bad-value {"name" "bash" "arguments" {"wrong" "stuff"}})
;;
;;   2. Validate directly with m3 to get detailed errors:
;;
;;      (require '[m3.validate :as m3])
;;      (m3/validate {:draft :draft7} json-schema {} bad-value)
;;      ;; => {:valid? false
;;      ;;     :errors [{:path [...] :message "..." :schema ...}]}
;;
;;   3. The :errors vector contains detailed information about what failed:
;;      - :path - JSON pointer to the failing location in the value
;;      - :message - Human-readable error description
;;      - :schema - The schema that failed
;;      - :keyword - The JSON Schema keyword that failed (e.g. "required", "type")
;;
;; EXAMPLE DEBUG SESSION:
;; ----------------------
;;   ;; Schema expects {"command" <string>} with command required
;;   (def schema {"type" "object"
;;                "properties" {"command" {"type" "string"}}
;;                "required" ["command"]})
;;
;;   ;; Value is missing command
;;   (m3/validate {:draft :draft7} schema {} {})
;;   ;; => {:valid? false
;;   ;;     :errors [{:keyword "required"
;;   ;;               :path []
;;   ;;               :message "required property 'command' not found"
;;   ;;               ...}]}
;;
;;   ;; Value has wrong type for command
;;   (m3/validate {:draft :draft7} schema {} {"command" 123})
;;   ;; => {:valid? false
;;   ;;     :errors [{:keyword "type"
;;   ;;               :path ["command"]
;;   ;;               :message "expected string, got integer"
;;   ;;               ...}]}
;;
;; =============================================================================

(def JsonSchema
  "Custom Malli schema type that validates using JSON Schema via m3.
   
   Enables embedding JSON Schema within Malli schemas for cases where
   external systems (like MCP) provide schemas in JSON Schema format.
   
   Usage: [:json-schema {:schema {\"type\" \"object\" \"properties\" {...}}}]
   
   The JSON Schema is passed via the :schema property to m3 for validation
   using draft-07. This allows a single Malli-based validation pass while
   delegating to m3 for the embedded JSON Schema portions.
   
   See the comment block above for debugging tips when validation fails."
  (m/-simple-schema
   {:type :json-schema
    :min 0 ;; no children required
    :max 0
    :compile (fn [props _children _opts]
               (let [json-schema-def (:schema props)]
                 (when-not json-schema-def
                   (throw (ex-info "[:json-schema] requires :schema property"
                                   {:properties props})))
                 {:pred #(:valid? (m3/validate {:draft :draft7}
                                               json-schema-def
                                               {}
                                               %))}))}))

(def base-registry
  "CLAIJ's base registry with custom schema types.
   
   Includes:
   - All default Malli schemas
   - :malli/schema for meta-schema validation
   - :json-schema for embedding JSON Schema in Malli"
  (mr/composite-registry
   (m/default-schemas)
   {:malli/schema MalliSchema
    :json-schema JsonSchema}))

;;==============================================================================
;; Schema Form Transformations: Vector ↔ Map
;;==============================================================================
;;
;; Two representations of the same schema:
;; - Vector form: token-efficient, what Malli executes, what LLMs see
;; - Map form: walkable, self-describing, what forms library uses
;;
;; Vector: [:map {:closed true} [:name :string] [:age :int]]
;; Map:    {:type :map 
;;          :properties {:closed true}
;;          :entries [{:key :name :schema :string}
;;                    {:key :age :schema :int}]}

(defn ->map-form
  "Transform Malli vector-form schema to walkable map-form.
   Map-form is self-describing and suitable for forms library walking."
  [schema]
  (cond
    (keyword? schema) schema
    (string? schema) schema
    (vector? schema)
    (let [[type & rest] schema
          [props children] (if (and (seq rest) (map? (first rest)))
                             [(first rest) (vec (next rest))]
                             [nil (vec rest)])]
      (merge
       {:type type}
       (when props {:properties props})
       (case type
         :map {:entries (mapv (fn [entry]
                                (if (vector? entry)
                                  (let [[k & rst] entry
                                        [p s] (if (and (seq rst) (map? (first rst)))
                                                [(first rst) (second rst)]
                                                [nil (first rst)])]
                                    (merge {:key k :schema (->map-form s)}
                                           (when p {:props p})))
                                  {:key entry}))
                              children)}

         (:vector :set :sequential :maybe)
         {:children [(->map-form (first children))]}

         (:or :and :tuple)
         {:children (mapv ->map-form children)}

         :enum {:values (vec children)}
         := {:value (first children)}
         :ref {:ref (first children)}
         :map-of {:children (mapv ->map-form children)}

         :schema (let [[props-or-ref maybe-ref] children]
                   (if (map? props-or-ref)
                     {:properties props-or-ref
                      :ref (->map-form maybe-ref)}
                     {:ref (->map-form props-or-ref)}))

         ;; Default: treat children as schemas
         (when (seq children)
           {:children (mapv ->map-form children)}))))
    :else schema))

(defn ->vector-form
  "Transform map-form schema back to Malli vector-form.
   Inverse of ->map-form."
  [schema]
  (cond
    (keyword? schema) schema
    (string? schema) schema
    (map? schema)
    (let [{:keys [type properties children entries values value ref]} schema]
      (cond-> [type]
        properties (conj properties)

        ;; Type-specific children
        entries (into (mapv (fn [{:keys [key props schema]}]
                              (if props
                                [key props (->vector-form schema)]
                                [key (->vector-form schema)]))
                            entries))
        (and children (#{:vector :set :sequential :maybe} type))
        (conj (->vector-form (first children)))

        (and children (#{:or :and :tuple :map-of} type))
        (into (mapv ->vector-form children))

        values (into values)
        value (conj value)
        ref (conj (->vector-form ref))))
    :else schema))

;;==============================================================================
;; Transitive Closure & Ref Analysis
;;==============================================================================
;;
;; For LLM emission, we need to:
;; 1. Find all refs transitively from a starting schema
;; 2. Count how many times each ref is used
;; 3. Decide: inline (1 use) vs define-once (2+ uses)

(defn collect-refs
  "Walk schema form (data), collect all ref targets. Returns #{ref-keys}"
  [form]
  (let [refs (atom #{})]
    (postwalk
     (fn [x]
       (when (and (vector? x) (= :ref (first x)))
         (swap! refs conj (second x)))
       x)
     form)
    @refs))

(defn count-refs
  "Count ref usages in schema form. Returns {ref-key count}"
  [form]
  (let [counts (atom {})]
    (postwalk
     (fn [x]
       (when (and (vector? x) (= :ref (first x)))
         (swap! counts update (second x) (fnil inc 0)))
       x)
     form)
    @counts))

(defn transitive-closure
  "Get all ref keys needed transitively from start-key.
   Returns #{all-keys-including-start}"
  [start-key registry]
  (loop [to-visit #{start-key}
         visited #{}]
    (if (empty? to-visit)
      visited
      (let [current (first to-visit)
            form (get registry current)
            new-refs (when form (collect-refs form))]
        (recur (into (disj to-visit current)
                     (remove visited new-refs))
               (conj visited current))))))

(defn analyze-for-emission
  "Analyze schema for LLM emission. Returns:
   {:closure #{all-keys-needed}
    :usages {ref-key count}
    :inline #{keys-used-once}
    :define #{keys-used-multiple-times}}"
  [start-key registry]
  (let [closure (transitive-closure start-key registry)
        all-usages (apply merge-with +
                          (map #(count-refs (get registry %))
                               closure))]
    {:closure closure
     :usages all-usages
     :inline (set (for [[k v] all-usages :when (= 1 v)] k))
     :define (set (for [[k v] all-usages :when (> v 1)] k))}))

;;==============================================================================
;; LLM Emission: Prepare schemas for token-efficient prompts
;;==============================================================================

(defn inline-refs
  "Replace [:ref k] with actual schema where k is in inline-set.
   Recursively inlines nested refs."
  [form registry inline-set]
  (postwalk
   (fn [x]
     (if (and (vector? x)
              (= :ref (first x))
              (contains? inline-set (second x)))
       (inline-refs (get registry (second x)) registry inline-set)
       x))
   form))

(defn expand-refs-for-llm
  "Recursively expand ALL [:ref k] forms using the registry.
   
   Unlike inline-refs which selectively inlines, this expands every ref.
   Use this to prepare schemas for LLM prompts where the LLM cannot
   resolve Malli refs.
   
   Args:
     form     - A Malli schema form (may contain [:ref k] forms)
     registry - A map or Malli registry for resolving refs
   
   Returns the schema with all refs expanded inline.
   
   Note: For token efficiency with multi-use schemas, consider using
   emit-for-llm instead, which keeps multi-use schemas in a registry."
  [form registry]
  (let [;; Handle maps, functions, and Malli composite registries
        lookup (cond
                 (fn? registry) registry
                 (map? registry) (fn [k] (get registry k))
                 :else (fn [k] (mr/-schema registry k)))]
    (postwalk
     (fn [x]
       (if (and (vector? x)
                (= :ref (first x)))
         (let [ref-key (second x)
               resolved (lookup ref-key)]
           (if resolved
             (expand-refs-for-llm resolved registry)
             x)) ;; Leave unresolved refs as-is
         x))
     form)))

(defn emit-for-llm
  "Prepare schema for LLM prompt. Returns:
   {:registry {only-multi-use-defs}  ;; nil if empty
    :schema <main-schema-with-inlining>}
   
   Single-use refs are inlined for token efficiency.
   Multi-use refs are defined once in registry."
  [start-key registry]
  (let [{:keys [inline define]} (analyze-for-emission start-key registry)
        ;; Apply inlining to all schemas that will be in registry
        inlined-registry (into {}
                               (for [k define]
                                 [k (inline-refs (get registry k) registry inline)]))
        ;; The main schema with inlining applied
        main-schema (inline-refs (get registry start-key) registry inline)]
    {:registry (when (seq inlined-registry) inlined-registry)
     :schema main-schema}))

(defn format-for-prompt
  "Format emitted schema as EDN string for LLM prompt."
  [{:keys [registry schema]}]
  (str
   (when registry
     (str "Registry:\n" (pr-str registry) "\n\n"))
   "Schema:\n" (pr-str schema)))

;;==============================================================================
;; Prompt Notes
;;==============================================================================

(def schema-prompt-note
  "Brief explanation of :malli/schema for LLM system prompts.
   Include this once - much cheaper than a full meta-schema definition.
   LLMs already know Malli syntax from training; this just clarifies the reference."
  ":malli/schema = any valid Malli schema (e.g. :string, [:map [:x :int]], [:vector :keyword], [:or :string :nil])")

;;==============================================================================
;; Document/Schema Validation Macros (m1/m2 pattern)
;;==============================================================================
;;
;; Following the m1/m2/m3 hierarchy:
;; - m1 = document (data)
;; - m2 = schema (describes m1)
;; - m3 = meta-schema (describes m2, which for Malli is m/schema itself)

(defn valid-m2?
  "Check if schema is a valid Malli schema (m2 level).
   Logs error details if invalid. Returns boolean."
  [schema]
  (let [{:keys [valid? error]} (validate-schema schema)]
    (if valid?
      true
      (do
        (log/errorf "Invalid schema: %s - %s" (pr-str schema) error)
        false))))

(defn valid-m1?
  "Check if document validates against schema (m1 level).
   Logs error details if invalid. Returns boolean."
  ([schema doc]
   (valid-m1? schema doc nil))
  ([schema doc opts]
   (let [{:keys [valid? errors]} (validate schema doc opts)]
     (if valid?
       true
       (do
         (log/errorf "Invalid document: %s - %s" (pr-str doc) (pr-str errors))
         false)))))

(defmacro def-m2
  "Define a Malli schema with compile-time validation.
   Asserts that the schema is valid Malli syntax."
  [name schema]
  `(def ~name
     (let [s# ~schema]
       (assert (valid-m2? s#) (str "Invalid schema: " '~name))
       s#)))

(defmacro def-m1
  "Define a document with compile-time validation against its schema.
   Asserts that the document conforms to the schema."
  [name schema doc]
  `(def ~name
     (let [m2# ~schema
           m1# ~doc]
       (assert (valid-m1? m2# m1#) (str "Invalid document: " '~name))
       m1#)))

;;==============================================================================
;; Schema Subsumption
;;==============================================================================
;;
;; (subsumes? input-schema output-schema) returns true if every valid instance
;; of output-schema is guaranteed to be valid for input-schema.
;;
;; Mathematically: instances(output) ⊆ instances(input)
;;
;; This enables compile-time verification that FSM transitions are type-safe:
;; the next state's input schema must subsume the previous action's output.
;;
;; Example:
;;   (subsumes? [:map [:a :int]]                     ; input - what next state accepts
;;              [:map [:a :int] [:b :string]])       ; output - what action produces
;;   => true (output has everything input needs, plus extra)
;;
;; Implementation is conservative and incremental:
;; - Known type pairs have explicit rules
;; - Same-type unknowns warn and return true (permissive)
;; - Different-type unknowns return false (conservative)
;; - Add rules as real mismatches are discovered

(defn- ->malli-schema
  "Normalize to compiled Malli schema."
  [s]
  (if (m/schema? s) s (m/schema s {:registry base-registry})))

(defmulti -subsumes?
  "Internal multimethod for type-specific subsumption rules.
   Dispatches on [(m/type input) (m/type output)]."
  (fn [input output]
    [(m/type input) (m/type output)]))

(defn subsumes?
  "True if input-schema subsumes output-schema.
   i.e., every valid instance of output is also valid for input.
   
   Use at FSM boundaries to verify type safety:
     (subsumes? next-state-input previous-action-output)
   
   Returns true if the transition is type-safe."
  [input output]
  (let [in (->malli-schema input)
        out (->malli-schema output)
        in-type (m/type in)
        out-type (m/type out)]
    (cond
      ;; Equality is trivially true
      (= (m/form in) (m/form out)) true
      ;; :any subsumes everything
      (= :any in-type) true
      ;; Union on output: input must subsume all branches (check BEFORE input union)
      (= :or out-type)
      (every? #(subsumes? input %) (m/children out))
      ;; Union on input: any branch subsumes output
      (= :or in-type)
      (boolean (some #(subsumes? % output) (m/children in)))
      ;; Maybe on output: input must handle both nil and inner (check BEFORE input maybe)
      (= :maybe out-type)
      (let [inner (first (m/children out))]
        (and (subsumes? input inner)
             (subsumes? input :nil)))
      ;; Maybe on input: inner subsumes output, OR output is nil
      (= :maybe in-type)
      (let [inner (first (m/children in))]
        (or (= :nil out-type)
            (subsumes? inner output)))
      ;; Delegate to multimethod for specific type pairs
      :else (-subsumes? in out))))

;; Default - same type without rule warns, different types fail
(defmethod -subsumes? :default [input output]
  (let [in-type (m/type input)
        out-type (m/type output)]
    (if (= in-type out-type)
      (do (log/warnf "No subsumption rule for same type [%s] - assuming true" in-type)
          true)
      false)))

;; Same primitive types - check constraints
(doseq [t [:int :string :boolean :keyword :symbol :uuid :double :float :nil]]
  (defmethod -subsumes? [t t] [input output]
    (let [in-props (m/properties input)
          out-props (m/properties output)]
      (cond
        ;; Both unconstrained - trivially true
        (and (nil? in-props) (nil? out-props)) true
        ;; Input unconstrained, output constrained - true (output is narrower)
        (nil? in-props) true
        ;; Input constrained, output unconstrained - false (output might violate)
        (nil? out-props) false
        ;; Both constrained - check ranges (TODO: implement range checking)
        :else (do (log/warnf "Constrained %s subsumption not fully implemented" t)
                  true)))))

;; Enums - output values must be subset of input values
(defmethod -subsumes? [:enum :enum] [input output]
  (let [in-vals (set (m/children input))
        out-vals (set (m/children output))]
    (set/subset? out-vals in-vals)))

;; Literal := must be equal
(defmethod -subsumes? [:= :=] [input output]
  (= (m/children input) (m/children output)))

;; := subsumes :enum if enum is single-valued and matches
(defmethod -subsumes? [:= :enum] [input output]
  (let [literal (first (m/children input))
        enum-vals (m/children output)]
    (and (= 1 (count enum-vals))
         (= literal (first enum-vals)))))

;; :enum subsumes := if literal is in enum
(defmethod -subsumes? [:enum :=] [input output]
  (let [literal (first (m/children output))
        enum-vals (set (m/children input))]
    (contains? enum-vals literal)))

;; Helper for map entries
(defn- map-entries
  "Extract map entries as {:key k :optional? bool :schema s}"
  [map-schema]
  (for [[k props schema] (m/children map-schema)]
    {:key k
     :optional? (get props :optional false)
     :schema schema}))

;; Maps - every required input key must exist in output with compatible schema
(defmethod -subsumes? [:map :map] [input output]
  (let [in-entries (map-entries input)
        out-by-key (into {} (map (juxt :key identity) (map-entries output)))]
    (every?
     (fn [{:keys [key optional? schema]}]
       (if-let [out-entry (get out-by-key key)]
         ;; Key exists in output - check schema subsumption
         (subsumes? schema (:schema out-entry))
         ;; Key not in output - only ok if optional in input
         optional?))
     in-entries)))

;; Vectors - element schemas must subsume
(defmethod -subsumes? [:vector :vector] [input output]
  (subsumes? (first (m/children input))
             (first (m/children output))))

;; Sequential
(defmethod -subsumes? [:sequential :sequential] [input output]
  (subsumes? (first (m/children input))
             (first (m/children output))))

;; Set
(defmethod -subsumes? [:set :set] [input output]
  (subsumes? (first (m/children input))
             (first (m/children output))))

;; Tuple - positional, all elements must subsume
(defmethod -subsumes? [:tuple :tuple] [input output]
  (let [in-children (m/children input)
        out-children (m/children output)]
    (and (= (count in-children) (count out-children))
         (every? true? (map subsumes? in-children out-children)))))

;; Maybe-Maybe already handled in main function, this is for multimethod completeness
(defmethod -subsumes? [:maybe :maybe] [input output]
  (subsumes? (first (m/children input))
             (first (m/children output))))

;; And - input must subsume ALL branches of the output :and
(defmethod -subsumes? [:and :and] [input output]
  (let [in-children (m/children input)
        out-children (m/children output)]
    ;; Every constraint in input must be satisfied by output
    ;; For practical purposes: output must satisfy at least all input constraints
    (every? (fn [in-child]
              (some #(subsumes? in-child %) out-children))
            in-children)))

;; Map-of - both key and value schemas must subsume
(defmethod -subsumes? [:map-of :map-of] [input output]
  (let [[in-key in-val] (m/children input)
        [out-key out-val] (m/children output)]
    (and (subsumes? in-key out-key)
         (subsumes? in-val out-val))))

;; Cross-collection: vector subsumes sequential (vectors are sequential)
(defmethod -subsumes? [:sequential :vector] [input output]
  (subsumes? (first (m/children input))
             (first (m/children output))))

;; Cross-numeric: double subsumes int (every int is a valid double)
(defmethod -subsumes? [:double :int] [_ _] true)
(defmethod -subsumes? [:float :int] [_ _] true)

;;==============================================================================
;; FSM Schema Definitions
;;==============================================================================
;;
;; These schemas define the structure of CLAIJ Finite State Machines.
;; FSMs use string keys for compatibility with existing definitions.
;;
;; Hierarchy:
;; - fsm-registry: Contains reusable schema definitions
;; - fsm-schema: The main schema for validating FSM definitions

(def fsm-registry
  "Registry of reusable schema definitions for FSMs.
   Use with {:registry fsm-registry} in validation options."
  (mr/composite-registry
   base-registry
   {;; A prompt is just a string (system prompt text)
    :fsm/prompt :string

    ;; Prompts are a vector of prompt strings
    :fsm/prompts [:vector [:ref :fsm/prompt]]

;; A state in the FSM
    :fsm/state [:map {:closed true}
                ["id" :string]
                ["description" {:optional true} :string]
                ["action" {:optional true} :string]
                ["config" {:optional true} :any] ;; Action-specific configuration
                ["prompts" {:optional true} [:ref :fsm/prompts]]]

    ;; A transition ID is a tuple of [from-state, to-state]
    :fsm/xition-id [:tuple :string :string]

    ;; A transition in the FSM
    ;; Note: "schema" field accepts any valid Malli schema or a string (for dynamic lookup)
    :fsm/xition [:map {:closed true}
                 ["id" [:ref :fsm/xition-id]]
                 ["label" {:optional true} :string]
                 ["description" {:optional true} :string]
                 ["prompts" {:optional true} [:ref :fsm/prompts]]
                 ["schema" :any] ;; Can be Malli schema or string key for dynamic lookup
                 ["omit" {:optional true} :boolean]]

    ;; The top-level FSM definition
    :fsm/definition [:map {:closed true}
                     ["id" :string]
                     ["description" {:optional true} :string]
                     ["schema" {:optional true} :any]
                     ["schemas" {:optional true} :any] ;; Map of schema-key -> Malli schema (data)
                     ["prompts" {:optional true} [:ref :fsm/prompts]]
                     ["states" [:vector [:ref :fsm/state]]]
                     ["xitions" [:vector [:ref :fsm/xition]]]]}))

(def fsm-schema
  "Schema for validating FSM definitions.
   Validates the structure of an FSM including states and transitions."
  [:ref :fsm/definition])

(defn valid-fsm?
  "Check if an FSM definition is structurally valid.
   Returns boolean. Logs errors if invalid."
  [fsm]
  (valid-m1? fsm-schema fsm {:registry fsm-registry}))

(defmacro def-fsm
  "Define an FSM with compile-time structural validation.
   Validates that the FSM conforms to the FSM schema."
  [name fsm]
  `(def ~name
     (let [f# ~fsm]
       (assert (valid-fsm? f#) (str "Invalid FSM: " '~name))
       f#)))
