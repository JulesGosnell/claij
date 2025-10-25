(ns claij.new.schema
  "JSON Schema definition and composition for structured LLM responses.
  
  Provides base schema and functions for composing extended schemas
  from interceptor contributions."
  (:require [clojure.data.json :as json]))

;; Base schema that all LLM responses must conform to
(def base-schema
  "Minimal schema for LLM responses.
  
  Required fields:
  - answer: The LLM's response to the user
  - state: FSM state to transition to"
  {:$schema "http://json-schema.org/draft-07/schema#"
   :type "object"
   :required ["answer" "state"]
   :properties {:answer {:type "string"
                         :description "Your response to the user"}
                :state {:type "string"
                        :description "Next FSM state to transition to"}}})

(defn merge-properties
  "Merge properties from multiple schema extensions.
  
  Later extensions override earlier ones for the same property name."
  [& property-maps]
  (apply merge property-maps))

(defn add-property
  "Add a single property to a schema.
  
  Returns updated schema with the new property added to :properties."
  [schema property-name property-spec]
  (assoc-in schema [:properties property-name] property-spec))

(defn add-required
  "Add a field to the required list.
  
  Returns updated schema with field added to :required array."
  [schema field-name]
  (update schema :required (fnil conj []) field-name))

(defn compose-schema
  "Compose a final schema from base schema and extensions.
  
  Extensions are maps with:
  - :properties - map of property-name -> property-spec
  - :required - vector of required field names (optional)
  
  Extensions must NOT override core schema fields like :type, :$schema.
  
  Example:
    (compose-schema base-schema
                    [{:properties {:summary {:type \"string\"}}}
                     {:properties {:confidence {:type \"number\"}}
                      :required [\"confidence\"]}])"
  [base extensions]
  ;; Validate that extensions don't try to override core schema fields
  (doseq [ext extensions]
    (when (or (:type ext) (:$schema ext))
      (throw (ex-info "Invalid schema extension: cannot override :type or :$schema"
                      {:extension ext
                       :invalid-fields (filter ext [:type :$schema])}))))

  (reduce
   (fn [schema ext]
     (cond-> schema
       (:properties ext)
       (update :properties merge (:properties ext))

       (:required ext)
       (update :required into (:required ext))))
   base
   extensions))

(defn schema->json
  "Convert schema map to JSON string for inclusion in prompts."
  [schema]
  (json/write-str schema :escape-slash false))

(defn format-schema-for-prompt
  "Format schema as a clear prompt instruction.
  
  Returns a string suitable for inclusion in system prompt."
  [schema]
  (str "You must respond with valid JSON matching this schema:\n\n"
       (schema->json schema)
       "\n\n"
       "CRITICAL INSTRUCTIONS:\n"
       "- Return ONLY raw JSON - nothing else\n"
       "- Do NOT wrap in markdown code blocks (no ``` or ```json)\n"
       "- Do NOT include any explanatory text before or after\n"
       "- Your ENTIRE response must be parseable JSON\n"
       "- Start with { and end with }\n"
       "\n"
       "Example of CORRECT response:\n"
       "{\"answer\":\"Hello\",\"state\":\"ready\"}\n"
       "\n"
       "Example of INCORRECT response:\n"
       "```json\n{\"answer\":\"Hello\",\"state\":\"ready\"}```"))

(comment
  ;; Example usage
  (def memory-extension
    {:properties {:summary {:type "string"
                            :description "Brief summary of key facts"}}})

  (def tool-extension
    {:properties {:tools {:type "array"
                          :description "Tool calls to execute"
                          :items {:type "object"}}}})

  (compose-schema base-schema [memory-extension tool-extension])
  ;=> Schema with answer, state, summary, and tools

  (format-schema-for-prompt base-schema)
  ;=> Formatted prompt text
  )
