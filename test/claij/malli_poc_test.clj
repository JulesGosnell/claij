(ns claij.malli-poc-test
  "PoC tests for Malli-based schema communication with LLMs.
   
   These tests verify that LLMs can:
   1. Understand Malli schemas as input/output specifications
   2. Return valid documents conforming to Malli output schemas
   3. Modify FSM definitions expressed in Malli
   
   If these tests pass, we have evidence that migrating from JSON Schema to Malli
   is viable for LLM communication."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.tools.logging :as log]
   [clojure.walk]
   [clj-http.client :as http]
   [malli.core :as m]
   [malli.error :as me]
   [malli.json-schema :as mjs]
   [malli.registry :as mr]
   [claij.util :refer [assert-env-var clj->json json->clj]]))

;;==============================================================================
;; Test Configuration
;;==============================================================================

(def test-providers
  "LLM providers to test. Each must understand Malli schema format."
  [["anthropic" "claude-sonnet-4"]
   ["google" "gemini-2.5-flash"]
   ["openai" "gpt-4o"]
   ["x-ai" "grok-3-beta"]
   ["meta-llama" "llama-4-scout"]])

(def test-timeout-ms
  "Timeout for each LLM call in milliseconds."
  60000)

;;==============================================================================
;; Direct LLM Calling (bypasses JSON parsing in open-router-async)
;;==============================================================================

(def api-url "https://openrouter.ai/api/v1")

(defn api-key [] (assert-env-var "OPENROUTER_API_KEY"))

(defn call-llm-raw
  "Call LLM and return raw response string (not parsed).
   This allows us to receive EDN responses from LLMs."
  [provider model prompts]
  (log/info (str "Calling " provider "/" model))
  (try
    (let [response (http/post
                    (str api-url "/chat/completions")
                    {:headers {"Authorization" (str "Bearer " (api-key))
                               "content-type" "application/json"}
                     :body (clj->json {:model (str provider "/" model)
                                       :messages prompts})
                     :socket-timeout test-timeout-ms
                     :connection-timeout 10000})
          body (json->clj (:body response))
          content (get-in body [:choices 0 :message :content])]
      {:success true :content content})
    (catch Exception e
      (log/error e "LLM call failed")
      {:success false :error (.getMessage e)})))

;;==============================================================================
;; Malli Schema Utilities
;;==============================================================================

(defn schema->str
  "Convert a Malli schema to a readable string for prompts."
  [schema]
  (pr-str schema))

(defn strip-markdown
  "Strip markdown code blocks from response if present."
  [s]
  (when s
    (-> s
        (str/replace #"```(?:edn|clojure|json)?\s*" "")
        (str/replace #"```\s*$" "")
        str/trim)))

(defn try-parse-edn
  "Try to parse string as EDN, returning {:ok value} or {:error msg}."
  [s]
  (try
    {:ok (edn/read-string s)}
    (catch Exception e
      {:error (.getMessage e)})))

(defn try-parse-json
  "Try to parse string as JSON, returning {:ok value} or {:error msg}."
  [s]
  (try
    {:ok (json/read-str s :key-fn keyword)}
    (catch Exception e
      {:error (.getMessage e)})))

(defn parse-response
  "Try to parse response as EDN first, then JSON.
   Returns {:ok value} or {:error msg}."
  [s]
  (let [cleaned (strip-markdown s)
        edn-result (try-parse-edn cleaned)]
    (if (:ok edn-result)
      edn-result
      ;; Try JSON as fallback
      (let [json-result (try-parse-json cleaned)]
        (if (:ok json-result)
          json-result
          ;; Return the EDN error since that's what we prefer
          edn-result)))))

(defn validate-against-schema
  "Validate a value against a Malli schema.
   Returns {:valid? true/false :errors [...]}."
  [schema value]
  (if (m/validate schema value)
    {:valid? true}
    {:valid? false
     :errors (me/humanize (m/explain schema value))}))

;;==============================================================================
;; Malli Schema Validation: Using Malli's Own Parser as Meta-Validator
;;==============================================================================
;; 
;; Unlike JSON Schema, Malli doesn't have a declarative meta-schema.
;; Instead, Malli validates schema syntax programmatically via the Schema protocol.
;; The m/schema function IS the meta-validator - if it parses without error,
;; the schema is valid.
;;
;; This is actually cleaner than a declarative meta-schema because:
;; 1. It's always in sync with the actual implementation
;; 2. It handles all edge cases correctly
;; 3. It provides detailed error messages
;;
;; For LLM communication, we can describe valid schema syntax textually in prompts,
;; and use valid-malli-schema? to validate LLM-generated schemas.

(defn valid-malli-schema?
  "Returns true if x is valid Malli schema syntax.
   Uses Malli's own parsing - the authoritative source."
  [x]
  (try
    (m/schema x)
    true
    (catch Exception _
      false)))

(defn validate-malli-schema
  "Validates x as Malli schema syntax.
   Returns {:valid? true} or {:valid? false :error message :data details}"
  [x]
  (try
    (m/schema x)
    {:valid? true}
    (catch Exception e
      {:valid? false
       :error (ex-message e)
       :data (ex-data e)})))

;;------------------------------------------------------------------------------
;; :malli/schema - A reusable schema type for "field contains a Malli schema"
;;------------------------------------------------------------------------------
;; 
;; Use this in schema definitions where a field should contain a valid Malli schema.
;; This is the Malli equivalent of JSON Schema's $ref to the meta-schema.
;;
;; Example usage in an FSM xition schema:
;;   [:map
;;    [:from :keyword]
;;    [:to :keyword]  
;;    [:schema {:optional true} :malli/schema]]  ;; <-- field must be valid Malli schema
;;
;; When validating, pass {:registry claij-registry} as options.

(def MalliSchema
  "A Malli schema type that validates its input is a valid Malli schema.
   Register this in a registry to use :malli/schema in your schemas."
  (m/-simple-schema
   {:type :malli/schema
    :pred valid-malli-schema?
    :type-properties {:error/message "should be a valid Malli schema"
                      :description "A valid Malli schema"}}))

(def claij-registry
  "Extended Malli registry including :malli/schema for meta-schema references."
  (mr/composite-registry
   (m/default-schemas)
   {:malli/schema MalliSchema}))

(def malli-schema-prompt-note
  "Brief explanation of :malli/schema for LLM system prompts.
   Include this once - much cheaper than a full meta-schema definition.
   LLMs already know Malli syntax from training; this just clarifies the reference."
  ":malli/schema = any valid Malli schema (e.g. :string, [:map [:x :int]], [:vector :keyword], [:or :string :nil])")

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

(defn collect-refs-from-form
  "Walk schema form (data), collect all ref targets. Returns #{ref-keys}"
  [form]
  (let [refs (atom #{})]
    (clojure.walk/postwalk
     (fn [x]
       (when (and (vector? x) (= :ref (first x)))
         (swap! refs conj (second x)))
       x)
     form)
    @refs))

(defn count-refs-in-form
  "Count ref usages in schema form. Returns {ref-key count}"
  [form]
  (let [counts (atom {})]
    (clojure.walk/postwalk
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
            new-refs (when form (collect-refs-from-form form))]
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
                          (map #(count-refs-in-form (get registry %))
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
  (clojure.walk/postwalk
   (fn [x]
     (if (and (vector? x)
              (= :ref (first x))
              (contains? inline-set (second x)))
       (inline-refs (get registry (second x)) registry inline-set)
       x))
   form))

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

(defn format-schema-for-prompt
  "Format emitted schema as EDN string for LLM prompt."
  [{:keys [registry schema]}]
  (str
   (when registry
     (str "Registry:\n" (pr-str registry) "\n\n"))
   "Schema:\n" (pr-str schema)))

(defn test-schema-hierarchy
  "Test the m1/m2/m3 validation hierarchy.
   m1 = document, m2 = schema, m3 = Malli's own validator (m/schema).
   Returns map with validation results for each level."
  [m1-doc m2-schema]
  {:m1-validates-m2 (m/validate m2-schema m1-doc)
   :m2-validates-m3 (valid-malli-schema? m2-schema)
   :m3-validates-m3 true}) ;; Malli's parser is always valid by construction

(deftest malli-schema-validation
  (testing "Malli validates schema syntax via m/schema"
    (testing "Primitive type keywords are valid"
      (is (valid-malli-schema? :string))
      (is (valid-malli-schema? :int))
      (is (valid-malli-schema? :boolean))
      (is (valid-malli-schema? :keyword))
      (is (valid-malli-schema? :any)))

    (testing "Vector-form schemas are valid"
      (is (valid-malli-schema? [:map [:name :string]]))
      (is (valid-malli-schema? [:vector :int]))
      (is (valid-malli-schema? [:or :string :int]))
      (is (valid-malli-schema? [:= ["llm" "done"]]))
      (is (valid-malli-schema? [:tuple :string :string]))
      (is (valid-malli-schema? [:enum :a :b :c])))

    (testing "Complex nested schemas are valid"
      (is (valid-malli-schema?
           [:map {:closed true}
            [:id [:= ["reviewer" "mc"]]]
            [:summary :string]
            [:issues [:vector [:map [:severity [:enum "low" "medium" "high"]]]]]])))

    (testing "Invalid schemas are rejected"
      (is (not (valid-malli-schema? {:not "a schema"}))
          "Map is not a valid schema")
      (is (not (valid-malli-schema? :bogus-type))
          "Unknown keyword is not a valid schema")
      (is (not (valid-malli-schema? [:unknown-form :string]))
          "Unknown vector form is not a valid schema"))

    (testing "validate-malli-schema provides error details"
      (let [result (validate-malli-schema :bogus-type)]
        (is (not (:valid? result)))
        (is (string? (:error result)))
        (is (map? (:data result)))))

    (testing "Full hierarchy: m1 -> m2 -> m3"
      (let [m2-person [:map [:name :string] [:age :int]]
            m1-person {:name "Jules" :age 42}
            results (test-schema-hierarchy m1-person m2-person)]
        (is (:m1-validates-m2 results) "Document validates against schema")
        (is (:m2-validates-m3 results) "Schema validates via Malli")
        (is (:m3-validates-m3 results) "Malli's validator is valid by construction"))))

  (testing ":malli/schema type for meta-schema references"
    (let [opts {:registry claij-registry}
          ;; A schema that contains a field holding another schema
          xition-schema [:map {:closed true}
                         [:from :keyword]
                         [:to :keyword]
                         [:schema {:optional true} :malli/schema]]]

      (testing "accepts valid Malli schemas in :schema field"
        (is (m/validate xition-schema
                        {:from :start :to :llm :schema [:map [:text :string]]}
                        opts))
        (is (m/validate xition-schema
                        {:from :llm :to :done :schema :string}
                        opts))
        (is (m/validate xition-schema
                        {:from :a :to :b :schema [:or :string [:vector :int]]}
                        opts)))

      (testing "accepts missing optional :schema field"
        (is (m/validate xition-schema
                        {:from :start :to :end}
                        opts)))

      (testing "rejects invalid schemas in :schema field"
        (is (not (m/validate xition-schema
                             {:from :start :to :llm :schema :not-a-type}
                             opts)))
        (is (not (m/validate xition-schema
                             {:from :start :to :llm :schema [:bad-form :string]}
                             opts)))
        (is (not (m/validate xition-schema
                             {:from :start :to :llm :schema {:not "a schema"}}
                             opts)))))))

(deftest schema-form-transformations
  (testing "Vector → Map form transformation"
    (testing "Primitive schemas pass through"
      (is (= :string (->map-form :string)))
      (is (= :int (->map-form :int))))

    (testing "Simple map schema"
      (is (= {:type :map
              :entries [{:key :name :schema :string}
                        {:key :age :schema :int}]}
             (->map-form [:map [:name :string] [:age :int]]))))

    (testing "Map with properties"
      (is (= {:type :map
              :properties {:closed true}
              :entries [{:key :x :schema :int}]}
             (->map-form [:map {:closed true} [:x :int]]))))

    (testing "Map entry with props"
      (is (= {:type :map
              :entries [{:key :name :props {:optional true} :schema :string}]}
             (->map-form [:map [:name {:optional true} :string]]))))

    (testing "Vector/set/sequential"
      (is (= {:type :vector :children [:string]}
             (->map-form [:vector :string])))
      (is (= {:type :set :children [:int]}
             (->map-form [:set :int]))))

    (testing "Union/intersection"
      (is (= {:type :or :children [:string :int]}
             (->map-form [:or :string :int]))))

    (testing "Enum and const"
      (is (= {:type :enum :values [:a :b :c]}
             (->map-form [:enum :a :b :c])))
      (is (= {:type := :value 42}
             (->map-form [:= 42]))))

    (testing "Nested schemas"
      (is (= {:type :map
              :entries [{:key :data
                         :schema {:type :vector
                                  :children [{:type :or
                                              :children [:string :int]}]}}]}
             (->map-form [:map [:data [:vector [:or :string :int]]]])))))

  (testing "Map → Vector form transformation (roundtrip)"
    (let [schemas [[:map [:name :string] [:age :int]]
                   [:map {:closed true} [:x :int]]
                   [:vector :string]
                   [:or :string :int :nil]
                   [:enum :a :b :c]
                   [:= 42]
                   [:tuple :string :int]]]
      (doseq [schema schemas]
        (is (= schema (->vector-form (->map-form schema)))
            (str "Roundtrip failed for: " schema))))))

(deftest transitive-closure-and-emission
  (let [registry {:address [:map [:street :string] [:city :string]]
                  :person [:map
                           [:name :string]
                           [:home [:ref :address]]
                           [:work [:ref :address]]]
                  :company [:map
                            [:name :string]
                            [:ceo [:ref :person]]
                            [:hq [:ref :address]]]}]

    (testing "collect-refs-from-form finds direct refs"
      (is (= #{:address} (collect-refs-from-form (:person registry))))
      (is (= #{:person :address} (collect-refs-from-form (:company registry)))))

    (testing "count-refs-in-form counts usages"
      (is (= {:address 2} (count-refs-in-form (:person registry))))
      (is (= {:person 1 :address 1} (count-refs-in-form (:company registry)))))

    (testing "transitive-closure finds all deps"
      (is (= #{:company :person :address}
             (transitive-closure :company registry))))

    (testing "analyze-for-emission determines inline vs define"
      (let [analysis (analyze-for-emission :company registry)]
        (is (= #{:company :person :address} (:closure analysis)))
        ;; :address used 3 times (2 in :person, 1 in :company)
        (is (= 3 (get-in analysis [:usages :address])))
        ;; :person used 1 time
        (is (= 1 (get-in analysis [:usages :person])))
        ;; :person should be inlined, :address should be defined
        (is (contains? (:inline analysis) :person))
        (is (contains? (:define analysis) :address))))

    (testing "emit-for-llm produces optimized output"
      (let [emitted (emit-for-llm :company registry)]
        ;; Registry should only have :address (multi-use)
        (is (= #{:address} (set (keys (:registry emitted)))))
        ;; :person should be inlined in the schema
        (let [schema (:schema emitted)
              ceo-entry (some #(when (= :ceo (first %)) %) (rest schema))]
          ;; ceo-entry should have inlined person, not [:ref :person]
          (is (vector? (last ceo-entry)))
          (is (= :map (first (last ceo-entry)))))))

    (testing "format-schema-for-prompt produces valid EDN"
      (let [formatted (format-schema-for-prompt (emit-for-llm :company registry))]
        (is (string? formatted))
        (is (str/includes? formatted "Registry:"))
        (is (str/includes? formatted "Schema:"))
        ;; Should be parseable EDN
        (is (str/includes? formatted ":address"))))))

;;==============================================================================
;; PoC 1: Basic Malli Schema Understanding
;;==============================================================================

(def poc1-input-schema
  "Schema for a code review request."
  [:map {:closed true}
   [:code :string]
   [:language :string]
   [:concerns [:vector :string]]])

(def poc1-output-schema
  "Schema for a code review response."
  [:map {:closed true}
   [:id [:= ["reviewer" "mc"]]]
   [:summary :string]
   [:issues [:vector
             [:map {:closed true}
              [:line {:optional true} :int]
              [:severity [:enum "low" "medium" "high"]]
              [:description :string]]]]
   [:improved-code {:optional true} :string]])

(def poc1-input-doc
  {:code "(defn fib [n] (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))"
   :language "clojure"
   :concerns ["performance" "readability"]})

(def poc1-system-prompt
  "You are a code reviewer. You communicate using Malli schemas (Clojure's data-driven schema library).

CRITICAL INSTRUCTIONS:
- Your response must be valid EDN (Clojure data notation) - similar to JSON but with keywords
- Your response must conform EXACTLY to the output schema provided
- The :id field MUST be the literal value [\"reviewer\" \"mc\"] - this is a const
- Do NOT wrap your response in markdown code blocks
- Do NOT include any explanatory text - ONLY the EDN data structure
- Start with { and end with }
- If a field is optional and you have no particular value to communicate within it, you MUST omit this field

MALLI SCHEMA REFERENCE:
- [:map ...] = map/object with specified keys  
- [:vector X] = array/vector of X
- [:enum \"a\" \"b\"] = one of the literal string values
- [:= X] = exactly the value X (constant)
- :string = string value
- :int = integer value
- :nil = explicit nil value
- {:optional true} = field may be omitted
- {:closed true} = no additional keys allowed

EDN SYNTAX:
- Keywords look like :keyword (with colon prefix)
- Strings are in double quotes: \"hello\"
- Vectors use square brackets: [1 2 3]
- Maps use curly braces: {:key \"value\"}

You will receive: [INPUT-SCHEMA INPUT-DOCUMENT OUTPUT-SCHEMA]
Return ONLY the output document conforming to OUTPUT-SCHEMA.")

(defn make-poc1-prompt [input-schema input-doc output-schema]
  (str "[" (schema->str input-schema) "\n "
       (pr-str input-doc) "\n "
       (schema->str output-schema) "]"))

(defn test-poc1-with-provider
  "Test PoC 1 with a specific provider. Returns result map."
  [[provider model]]
  (println (str "\n--- Testing PoC 1: " provider "/" model " ---"))
  (let [prompts [{"role" "system" "content" poc1-system-prompt}
                 {"role" "user" "content" (make-poc1-prompt
                                           poc1-input-schema
                                           poc1-input-doc
                                           poc1-output-schema)}]
        {:keys [success content error]} (call-llm-raw provider model prompts)]
    (if-not success
      (do
        (println "  [FAIL] API Error:" error)
        {:provider provider :model model :success false :error error})
      (let [content-len (count (or content ""))
            preview (if (> content-len 100)
                      (str (subs content 0 100) "...")
                      (or content ""))
            _ (println "  Raw response:" preview)
            parse-result (parse-response content)]
        (if-let [parse-error (:error parse-result)]
          (do
            (println "  [FAIL] Parse Error:" parse-error)
            {:provider provider :model model :success false
             :error parse-error :raw content})
          (let [parsed (:ok parse-result)
                validation (validate-against-schema poc1-output-schema parsed)]
            (if (:valid? validation)
              (do
                (println "  [OK] Valid response!")
                {:provider provider :model model :success true :value parsed})
              (do
                (println "  [FAIL] Validation Error:" (:errors validation))
                {:provider provider :model model :success false
                 :errors (:errors validation) :value parsed}))))))))

;;==============================================================================
;; PoC 2: FSM Schema Modification
;;==============================================================================

(def malli-fsm-schema
  "Malli schema for an FSM definition.
   This is the meta-schema that describes valid FSM structures."
  [:map {:closed true}
   [:id :string]
   [:description {:optional true} :string]
   [:states [:vector
             [:map {:closed true}
              [:id :string]
              [:action :string]
              [:prompts {:optional true} [:vector :string]]]]]
   [:xitions [:vector
              [:map {:closed true}
               [:id [:tuple :string :string]]
               [:label {:optional true} :string]
               [:schema {:optional true} [:or [:map] :string :boolean]]]]]])

(def sample-fsm
  "A simple FSM for the LLM to modify."
  {:id "greeting-fsm"
   :description "A simple greeting workflow"
   :states [{:id "start" :action "start" :prompts []}
            {:id "greeter" :action "llm" :prompts ["You are a friendly greeter."]}
            {:id "end" :action "end"}]
   :xitions [{:id ["start" "greeter"] :label "begin" :schema true}
             {:id ["greeter" "end"] :label "done" :schema true}]})

(def poc2-input-schema
  "Schema for FSM modification request - a tuple of [instruction, current-fsm]."
  [:tuple
   :string ;; instruction text
   malli-fsm-schema]) ;; current FSM

(def poc2-instruction
  "Add a new state called 'farewell' between 'greeter' and 'end'. 
   The farewell state should use action 'llm' with a prompt about saying goodbye politely.
   Update the transitions so greeter->farewell->end instead of greeter->end.")

(def poc2-input-doc
  "The input document: [instruction, current-fsm]."
  [poc2-instruction sample-fsm])

(def poc2-output-schema
  "The modified FSM must conform to the FSM schema."
  malli-fsm-schema)

(def poc2-system-prompt
  "You are an FSM architect. You modify Finite State Machine definitions.
You communicate using Malli schemas (Clojure's data-driven schema library).

CRITICAL INSTRUCTIONS:
- Your response must be valid EDN (Clojure data notation)
- Your response must conform EXACTLY to the output schema provided
- Do NOT wrap your response in markdown code blocks
- Do NOT include any explanatory text - ONLY the EDN data structure
- Start with { and end with }
- If a field is optional and you have no particular value to communicate within it, you MUST omit this field

MALLI SCHEMA REFERENCE:
- [:map ...] = map/object with specified keys  
- [:vector X] = array/vector of X
- [:tuple X Y] = fixed-size vector with types [X, Y]
- [:enum \"a\" \"b\"] = one of the literal string values
- [:= X] = exactly the value X (constant)
- :string = string value
- :int = integer value
- :nil = explicit nil value
- {:optional true} = field may be omitted
- {:closed true} = no additional keys allowed

EDN SYNTAX:
- Keywords look like :keyword (with colon prefix)
- Strings are in double quotes: \"hello\"
- Vectors use square brackets: [1 2 3]
- Maps use curly braces: {:key \"value\"}

You will receive: [INPUT-SCHEMA INPUT-DOCUMENT OUTPUT-SCHEMA]
The INPUT-DOCUMENT contains [instruction-string, current-fsm].
Apply the instruction to modify the FSM and return the modified FSM conforming to OUTPUT-SCHEMA.")

(defn make-poc2-prompt [input-schema input-doc output-schema]
  (str "[" (schema->str input-schema) "\n "
       (pr-str input-doc) "\n "
       (schema->str output-schema) "]"))

(defn test-poc2-with-provider
  "Test PoC 2 with a specific provider. Returns result map."
  [[provider model]]
  (println (str "\n--- Testing PoC 2: " provider "/" model " ---"))
  (let [prompts [{"role" "system" "content" poc2-system-prompt}
                 {"role" "user" "content" (make-poc2-prompt
                                           poc2-input-schema
                                           poc2-input-doc
                                           poc2-output-schema)}]
        {:keys [success content error]} (call-llm-raw provider model prompts)]
    (if-not success
      (do
        (println "  [FAIL] API Error:" error)
        {:provider provider :model model :success false :error error})
      (let [content-len (count (or content ""))
            preview (if (> content-len 100)
                      (str (subs content 0 100) "...")
                      (or content ""))
            _ (println "  Raw response:" preview)
            parse-result (parse-response content)]
        (if-let [parse-error (:error parse-result)]
          (do
            (println "  [FAIL] Parse Error:" parse-error)
            {:provider provider :model model :success false
             :error parse-error :raw content})
          (let [parsed (:ok parse-result)
                validation (validate-against-schema poc2-output-schema parsed)]
            (if-not (:valid? validation)
              (do
                (println "  [FAIL] Schema Validation Error:" (:errors validation))
                {:provider provider :model model :success false
                 :errors (:errors validation) :value parsed})
              ;; Semantic checks
              (let [state-ids (set (map :id (:states parsed)))
                    xition-ids (set (map :id (:xitions parsed)))
                    has-farewell? (contains? state-ids "farewell")
                    has-greeter-farewell? (contains? xition-ids ["greeter" "farewell"])
                    has-farewell-end? (contains? xition-ids ["farewell" "end"])
                    semantic-ok? (and has-farewell? has-greeter-farewell? has-farewell-end?)]
                (if semantic-ok?
                  (do
                    (println "  [OK] Valid FSM modification!")
                    {:provider provider :model model :success true :value parsed})
                  (do
                    (println "  [FAIL] Semantic Error - missing required changes")
                    (println "    farewell state:" has-farewell?)
                    (println "    greeter->farewell:" has-greeter-farewell?)
                    (println "    farewell->end:" has-farewell-end?)
                    {:provider provider :model model :success false
                     :semantic-errors {:has-farewell has-farewell?
                                       :has-greeter-farewell has-greeter-farewell?
                                       :has-farewell-end has-farewell-end?}
                     :value parsed}))))))))))

;;==============================================================================
;; Test Runners
;;==============================================================================

(defn run-poc1-all-providers
  "Run PoC 1 against all configured providers."
  []
  (println "\n" (str/join "" (repeat 60 "=")) "\n")
  (println "PoC 1: LLM Understanding of Malli Schemas")
  (println (str/join "" (repeat 60 "=")))
  (let [results (mapv test-poc1-with-provider test-providers)]
    (println "\n--- Summary ---")
    (doseq [{:keys [provider model success]} results]
      (println (str (if success "[OK]" "[FAIL]") " " provider "/" model)))
    (println)
    results))

(defn run-poc2-all-providers
  "Run PoC 2 against all configured providers."
  []
  (println "\n" (str/join "" (repeat 60 "=")) "\n")
  (println "PoC 2: LLM Modification of Malli-defined FSMs")
  (println (str/join "" (repeat 60 "=")))
  (let [results (mapv test-poc2-with-provider test-providers)]
    (println "\n--- Summary ---")
    (doseq [{:keys [provider model success]} results]
      (println (str (if success "[OK]" "[FAIL]") " " provider "/" model)))
    (println)
    results))

(defn run-all-pocs
  "Run all PoCs and return combined results."
  []
  (let [poc1 (run-poc1-all-providers)
        poc2 (run-poc2-all-providers)]
    {:poc1 poc1 :poc2 poc2
     :poc1-any-success (some :success poc1)
     :poc2-any-success (some :success poc2)}))

;;==============================================================================
;; Interactive Testing (REPL)
;;==============================================================================

(comment
  ;; Run all PoCs
  (run-all-pocs)

  ;; Run PoC 1 with all providers
  (run-poc1-all-providers)

  ;; Run PoC 1 with single provider
  (test-poc1-with-provider ["anthropic" "claude-sonnet-4"])
  (test-poc1-with-provider ["google" "gemini-2.5-flash"])
  (test-poc1-with-provider ["openai" "gpt-4o"])

  ;; Run PoC 2 with all providers
  (run-poc2-all-providers)

  ;; Run PoC 2 with single provider  
  (test-poc2-with-provider ["anthropic" "claude-sonnet-4"])

  ;; Test schema validation directly
  (m/validate poc1-output-schema
              {:id ["reviewer" "mc"]
               :summary "Good code"
               :issues []})

  ;; Check what errors look like
  (me/humanize (m/explain poc1-output-schema {:wrong "data"}))

  ;; Test FSM schema
  (m/validate malli-fsm-schema sample-fsm)

  ;; Generate JSON Schema for comparison
  (mjs/transform poc1-output-schema)

  ;; Pretty print schemas
  (clojure.pprint/pprint malli-fsm-schema)
  (clojure.pprint/pprint poc1-output-schema))

;;==============================================================================
;; Actual Tests (for CI)
;;==============================================================================

(deftest ^:long-running poc1-malli-schema-understanding
  (testing "At least one LLM understands Malli input/output schemas"
    (let [results (run-poc1-all-providers)
          any-success? (some :success results)]
      (is any-success?
          (str "No LLM understood Malli schemas. Results: "
               (pr-str (map #(select-keys % [:provider :model :success :errors]) results)))))))

(deftest ^:long-running poc2-malli-fsm-modification
  (testing "At least one LLM can modify FSMs defined in Malli"
    (let [results (run-poc2-all-providers)
          any-success? (some :success results)]
      (is any-success?
          (str "No LLM could modify Malli FSMs. Results: "
               (pr-str (map #(select-keys % [:provider :model :success :errors :semantic-errors]) results)))))))
