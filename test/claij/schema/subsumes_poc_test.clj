(ns claij.schema.subsumes-poc-test
  "POC test for JSON Schema subsumption checking.
   
   subsumes?(input, output) returns true if every valid instance of output
   is also valid for input. Mathematically: instances(output) ⊆ instances(input)
   
   This enables compile-time verification that FSM transitions are type-safe:
   the next state's input schema must subsume the previous action's output."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.set :as set]))

;;==============================================================================
;; JSON Schema Subsumption Implementation
;;==============================================================================

(declare subsumes?)

(defn- schema-type
  "Extract the type from a JSON Schema. Returns nil for complex schemas."
  [schema]
  (cond
    (= true schema) :any
    (= false schema) :none
    (map? schema) (let [t (get schema "type")]
                    (cond
                      (string? t) (keyword t)
                      (vector? t) :union-type
                      (contains? schema "const") :const
                      (contains? schema "enum") :enum
                      (contains? schema "oneOf") :oneOf
                      (contains? schema "anyOf") :anyOf
                      (contains? schema "allOf") :allOf
                      (contains? schema "$ref") :ref
                      ;; Empty object {} is equivalent to true (accepts anything)
                      (empty? schema) :any
                      :else nil))
    :else nil))

(defmulti -subsumes?
  "Internal multimethod for type-specific subsumption rules.
   Dispatches on [input-type output-type]."
  (fn [input output]
    [(schema-type input) (schema-type output)]))

(defn subsumes?
  "True if input-schema subsumes output-schema.
   i.e., every valid instance of output is also valid for input.
   
   Returns {:subsumed? bool :reason string (when false)}
   
   Use at FSM boundaries to verify type safety:
     (subsumes? next-state-input previous-action-output)"
  [input output]
  (let [in-type (schema-type input)
        out-type (schema-type output)]
    (cond
      ;; Equality is trivially true
      (= input output)
      {:subsumed? true}
      
      ;; true/{} (any) subsumes everything
      (= :any in-type)
      {:subsumed? true}
      
      ;; false (none) is only subsumed by itself (already handled by equality)
      (= :none out-type)
      {:subsumed? true}  ;; Nothing validates against false, so vacuously true
      
      ;; Nothing subsumes false except false itself
      (= :none in-type)
      {:subsumed? false :reason "false schema accepts nothing"}
      
      ;; oneOf/anyOf on output: input must subsume ALL branches
      (#{:oneOf :anyOf} out-type)
      (let [branches (or (get output "oneOf") (get output "anyOf"))
            results (map #(subsumes? input %) branches)]
        (if (every? :subsumed? results)
          {:subsumed? true}
          {:subsumed? false 
           :reason (str "input doesn't subsume all " (name out-type) " branches")}))
      
      ;; oneOf/anyOf on input: ANY branch subsumes output
      (#{:oneOf :anyOf} in-type)
      (let [branches (or (get input "oneOf") (get input "anyOf"))
            results (map #(subsumes? % output) branches)]
        (if (some :subsumed? results)
          {:subsumed? true}
          {:subsumed? false 
           :reason (str "no " (name in-type) " branch subsumes output")}))
      
      ;; allOf on output: output satisfies intersection of all branches
      ;; For input to subsume this, input must subsume EACH branch
      ;; (since valid output instances must satisfy all branches)
      (= :allOf out-type)
      (let [branches (get output "allOf")
            results (map #(subsumes? input %) branches)]
        (if (every? :subsumed? results)
          {:subsumed? true}
          {:subsumed? false
           :reason "input doesn't subsume all allOf branches in output"}))
      
      ;; allOf on input: input is intersection of all branches
      ;; ALL branches must subsume output for input to subsume output
      ;; (since input requires satisfying every constraint)
      (= :allOf in-type)
      (let [branches (get input "allOf")
            results (map #(subsumes? % output) branches)]
        (if (every? :subsumed? results)
          {:subsumed? true}
          {:subsumed? false
           :reason "not all allOf branches in input subsume output"}))
      
      ;; Delegate to multimethod for specific type pairs
      :else
      (try
        (-subsumes? input output)
        (catch IllegalArgumentException _
          {:subsumed? false
           :reason (str "no subsumption rule for [" in-type " " out-type "]")})))))

;; Default - unknown type pairs don't subsume
(defmethod -subsumes? :default [input output]
  (let [in-type (schema-type input)
        out-type (schema-type output)]
    (if (= in-type out-type)
      ;; Same type without specific rule - warn and be conservative
      {:subsumed? false
       :reason (str "no rule for same type: " in-type)}
      {:subsumed? false
       :reason (str "incompatible types: " in-type " vs " out-type)})))

;;------------------------------------------------------------------------------
;; Constraint Checking Helpers
;;------------------------------------------------------------------------------

(defn- check-numeric-constraints
  "Check if input's numeric constraints subsume output's.
   Input subsumes output if input's range contains output's range."
  [input output]
  (let [;; Get bounds, treating exclusive as slightly tighter
        in-min (or (get input "minimum") 
                   (some-> (get input "exclusiveMinimum") (+ 0.0001))
                   Double/NEGATIVE_INFINITY)
        in-max (or (get input "maximum")
                   (some-> (get input "exclusiveMaximum") (- 0.0001))
                   Double/POSITIVE_INFINITY)
        out-min (or (get output "minimum")
                    (some-> (get output "exclusiveMinimum") (+ 0.0001))
                    Double/NEGATIVE_INFINITY)
        out-max (or (get output "maximum")
                    (some-> (get output "exclusiveMaximum") (- 0.0001))
                    Double/POSITIVE_INFINITY)
        ;; multipleOf: input's must divide output's evenly
        in-mult (get input "multipleOf")
        out-mult (get output "multipleOf")]
    (cond
      ;; Output's range must be within input's range
      (< out-min in-min)
      {:subsumed? false :reason (str "output minimum " out-min " < input minimum " in-min)}
      
      (> out-max in-max)
      {:subsumed? false :reason (str "output maximum " out-max " > input maximum " in-max)}
      
      ;; multipleOf: if input requires multipleOf, output must too (and be compatible)
      (and in-mult (not out-mult))
      {:subsumed? false :reason (str "input requires multipleOf " in-mult ", output doesn't")}
      
      (and in-mult out-mult (not (zero? (mod out-mult in-mult))))
      {:subsumed? false :reason (str "output multipleOf " out-mult " not divisible by input " in-mult)}
      
      :else
      {:subsumed? true})))

(defn- check-string-constraints
  "Check if input's string constraints subsume output's."
  [input output]
  (let [in-min-len (get input "minLength" 0)
        in-max-len (get input "maxLength" Long/MAX_VALUE)
        out-min-len (get output "minLength" 0)
        out-max-len (get output "maxLength" Long/MAX_VALUE)
        in-pattern (get input "pattern")
        out-pattern (get output "pattern")]
    (cond
      ;; Output's length range must be within input's
      (< out-min-len in-min-len)
      {:subsumed? false :reason (str "output minLength " out-min-len " < input minLength " in-min-len)}
      
      (> out-max-len in-max-len)
      {:subsumed? false :reason (str "output maxLength " out-max-len " > input maxLength " in-max-len)}
      
      ;; Pattern: if input has pattern, output must have same or compatible pattern
      ;; (Pattern subsumption is undecidable in general, so we're conservative)
      (and in-pattern (not out-pattern))
      {:subsumed? false :reason "input requires pattern, output doesn't"}
      
      (and in-pattern out-pattern (not= in-pattern out-pattern))
      ;; Different patterns - can't determine subsumption, be conservative
      {:subsumed? false :reason "pattern subsumption cannot be determined"}
      
      :else
      {:subsumed? true})))

(defn- check-array-constraints
  "Check if input's array constraints subsume output's."
  [input output]
  (let [in-min (get input "minItems" 0)
        in-max (get input "maxItems" Long/MAX_VALUE)
        out-min (get output "minItems" 0)
        out-max (get output "maxItems" Long/MAX_VALUE)
        in-unique (get input "uniqueItems" false)
        out-unique (get output "uniqueItems" false)]
    (cond
      (< out-min in-min)
      {:subsumed? false :reason (str "output minItems " out-min " < input minItems " in-min)}
      
      (> out-max in-max)
      {:subsumed? false :reason (str "output maxItems " out-max " > input maxItems " in-max)}
      
      ;; If input requires uniqueItems, output must too
      (and in-unique (not out-unique))
      {:subsumed? false :reason "input requires uniqueItems, output doesn't"}
      
      :else
      {:subsumed? true})))

;;------------------------------------------------------------------------------
;; Primitive Types
;;------------------------------------------------------------------------------

;; String with constraint checking
(defmethod -subsumes? [:string :string] [input output]
  (check-string-constraints input output))

;; Boolean - no constraints beyond type
(defmethod -subsumes? [:boolean :boolean] [_ _]
  {:subsumed? true})

;; Null - no constraints beyond type  
(defmethod -subsumes? [:null :null] [_ _]
  {:subsumed? true})

;; Integer with constraint checking
(defmethod -subsumes? [:integer :integer] [input output]
  (check-numeric-constraints input output))

;; Number with constraint checking
(defmethod -subsumes? [:number :number] [input output]
  (check-numeric-constraints input output))

;; Cross-numeric: number subsumes integer (every integer is a valid number)
(defmethod -subsumes? [:number :integer] [_ _]
  {:subsumed? true})

;; integer does NOT subsume number (1.5 is a number but not an integer)
(defmethod -subsumes? [:integer :number] [_ _]
  {:subsumed? false :reason "number values may not be integers"})

;;------------------------------------------------------------------------------
;; Enum and Const
;;------------------------------------------------------------------------------

;; Enums - output values must be subset of input values
(defmethod -subsumes? [:enum :enum] [input output]
  (let [in-vals (set (get input "enum"))
        out-vals (set (get output "enum"))]
    (if (set/subset? out-vals in-vals)
      {:subsumed? true}
      {:subsumed? false 
       :reason (str "enum values " (set/difference out-vals in-vals) " not in input")})))

;; const subsumes const if equal
(defmethod -subsumes? [:const :const] [input output]
  (if (= (get input "const") (get output "const"))
    {:subsumed? true}
    {:subsumed? false :reason "const values differ"}))

;; enum subsumes const if const value is in enum
(defmethod -subsumes? [:enum :const] [input output]
  (let [in-vals (set (get input "enum"))
        const-val (get output "const")]
    (if (contains? in-vals const-val)
      {:subsumed? true}
      {:subsumed? false :reason "const value not in enum"})))

;; const subsumes enum only if enum has single matching value
(defmethod -subsumes? [:const :enum] [input output]
  (let [const-val (get input "const")
        enum-vals (get output "enum")]
    (if (and (= 1 (count enum-vals))
             (= const-val (first enum-vals)))
      {:subsumed? true}
      {:subsumed? false :reason "enum has multiple values or doesn't match const"})))

;; Primitive type subsumes matching const
(doseq [t [:string :integer :number :boolean]]
  (defmethod -subsumes? [t :const] [input output]
    (let [const-val (get output "const")
          expected-type (case t
                          :string string?
                          :integer integer?
                          :number number?
                          :boolean boolean?)]
      (if (expected-type const-val)
        {:subsumed? true}
        {:subsumed? false :reason (str "const value is not a " (name t))}))))

;; Primitive type subsumes matching enum if all values are of that type
(doseq [t [:string :integer :number :boolean]]
  (defmethod -subsumes? [t :enum] [input output]
    (let [enum-vals (get output "enum")
          expected-type (case t
                          :string string?
                          :integer integer?
                          :number number?
                          :boolean boolean?)]
      (if (every? expected-type enum-vals)
        {:subsumed? true}
        {:subsumed? false :reason (str "not all enum values are " (name t) "s")}))))

;;------------------------------------------------------------------------------
;; Objects (Maps)
;;------------------------------------------------------------------------------

(defmethod -subsumes? [:object :object] [input output]
  (let [in-props (get input "properties" {})
        out-props (get output "properties" {})
        in-required (set (get input "required" []))
        out-required (set (get output "required" []))]
    ;; For input to subsume output:
    ;; 1. Every required key in input must exist in output (required or optional)
    ;; 2. For keys that exist in both, input's schema must subsume output's
    (loop [keys-to-check (keys in-props)
           errors []]
      (if (empty? keys-to-check)
        (if (empty? errors)
          {:subsumed? true}
          {:subsumed? false :reason (first errors)})
        (let [k (first keys-to-check)
              in-schema (get in-props k)
              out-schema (get out-props k)]
          (cond
            ;; Key not in output
            (nil? out-schema)
            (if (contains? in-required k)
              ;; Required in input but missing in output - fail
              (recur (rest keys-to-check)
                     (conj errors (str "required key '" k "' missing from output")))
              ;; Optional in input, missing in output - ok
              (recur (rest keys-to-check) errors))
            
            ;; Key exists in both - check subsumption
            :else
            (let [result (subsumes? in-schema out-schema)]
              (if (:subsumed? result)
                (recur (rest keys-to-check) errors)
                (recur (rest keys-to-check)
                       (conj errors (str "key '" k "': " (:reason result))))))))))))

;;------------------------------------------------------------------------------
;; Arrays
;;------------------------------------------------------------------------------

(defmethod -subsumes? [:array :array] [input output]
  (let [;; First check array-level constraints
        constraint-result (check-array-constraints input output)]
    (if-not (:subsumed? constraint-result)
      constraint-result
      ;; Then check items schema
      (let [in-items (get input "items" true)  ;; default: any items allowed
            out-items (get output "items" true)]
        (subsumes? in-items out-items)))))

;;------------------------------------------------------------------------------
;; Union Types (type: ["string", "integer"])
;;------------------------------------------------------------------------------

(defmethod -subsumes? [:union-type :union-type] [input output]
  (let [in-types (set (get input "type"))
        out-types (set (get output "type"))]
    (if (set/subset? out-types in-types)
      {:subsumed? true}
      {:subsumed? false 
       :reason (str "output types " (set/difference out-types in-types) " not in input")})))

;; Single type subsumes union-type if union has only that type
(doseq [t [:string :integer :number :boolean :null :object :array]]
  (defmethod -subsumes? [t :union-type] [input output]
    (let [out-types (get output "type")]
      (if (and (= 1 (count out-types))
               (= (name t) (first out-types)))
        {:subsumed? true}
        {:subsumed? false :reason "union-type has multiple or different types"}))))

;; Union-type subsumes single type if type is in union
(doseq [t [:string :integer :number :boolean :null :object :array]]
  (defmethod -subsumes? [:union-type t] [input output]
    (let [in-types (set (get input "type"))]
      (if (contains? in-types (name t))
        {:subsumed? true}
        {:subsumed? false :reason (str (name t) " not in union types")}))))

;;==============================================================================
;; Tests
;;==============================================================================

(deftest test-trivial-cases
  (testing "Equality"
    (is (:subsumed? (subsumes? {"type" "string"} {"type" "string"})))
    (is (:subsumed? (subsumes? {"type" "integer"} {"type" "integer"}))))
  
  (testing "true/any subsumes everything"
    (is (:subsumed? (subsumes? true {"type" "string"})))
    (is (:subsumed? (subsumes? true {"type" "object" "properties" {"x" {"type" "integer"}}})))
    (is (:subsumed? (subsumes? {} {"type" "array"})))  ;; empty object = any
    (is (:subsumed? (subsumes? true true))))
  
  (testing "false/none"
    (is (:subsumed? (subsumes? false false)))
    (is (:subsumed? (subsumes? {"type" "string"} false)))  ;; vacuously true
    (is (not (:subsumed? (subsumes? false {"type" "string"}))))))

(deftest test-primitive-types
  (testing "Same primitives subsume"
    (is (:subsumed? (subsumes? {"type" "string"} {"type" "string"})))
    (is (:subsumed? (subsumes? {"type" "integer"} {"type" "integer"})))
    (is (:subsumed? (subsumes? {"type" "boolean"} {"type" "boolean"}))))
  
  (testing "number subsumes integer"
    (is (:subsumed? (subsumes? {"type" "number"} {"type" "integer"})))
    (is (not (:subsumed? (subsumes? {"type" "integer"} {"type" "number"})))))
  
  (testing "Different primitives don't subsume"
    (is (not (:subsumed? (subsumes? {"type" "string"} {"type" "integer"}))))
    (is (not (:subsumed? (subsumes? {"type" "boolean"} {"type" "string"})))))
  
  (testing "Unconstrained subsumes constrained"
    (is (:subsumed? (subsumes? {"type" "string"} 
                               {"type" "string" "minLength" 5})))
    (is (:subsumed? (subsumes? {"type" "integer"}
                               {"type" "integer" "minimum" 0})))))

(deftest test-numeric-constraints
  (testing "Wider range subsumes narrower range"
    (is (:subsumed? (subsumes? {"type" "integer" "minimum" 0 "maximum" 100}
                               {"type" "integer" "minimum" 10 "maximum" 50})))
    (is (:subsumed? (subsumes? {"type" "number" "minimum" 0}
                               {"type" "number" "minimum" 5 "maximum" 10}))))
  
  (testing "Narrower range doesn't subsume wider"
    (is (not (:subsumed? (subsumes? {"type" "integer" "minimum" 10 "maximum" 50}
                                    {"type" "integer" "minimum" 0 "maximum" 100}))))
    (is (not (:subsumed? (subsumes? {"type" "number" "maximum" 100}
                                    {"type" "number"})))))
  
  (testing "Exclusive bounds"
    (is (:subsumed? (subsumes? {"type" "integer" "exclusiveMinimum" 0}
                               {"type" "integer" "minimum" 1})))
    (is (:subsumed? (subsumes? {"type" "number" "exclusiveMaximum" 100}
                               {"type" "number" "maximum" 99}))))
  
  (testing "multipleOf constraints"
    (is (:subsumed? (subsumes? {"type" "integer" "multipleOf" 2}
                               {"type" "integer" "multipleOf" 4})))  ;; 4 divides by 2
    (is (not (:subsumed? (subsumes? {"type" "integer" "multipleOf" 4}
                                    {"type" "integer" "multipleOf" 2}))))  ;; 2 doesn't divide by 4
    (is (not (:subsumed? (subsumes? {"type" "integer" "multipleOf" 3}
                                    {"type" "integer"}))))))  ;; no multipleOf

(deftest test-string-constraints
  (testing "Length constraints"
    (is (:subsumed? (subsumes? {"type" "string" "minLength" 1 "maxLength" 100}
                               {"type" "string" "minLength" 5 "maxLength" 50})))
    (is (not (:subsumed? (subsumes? {"type" "string" "minLength" 10}
                                    {"type" "string" "minLength" 5}))))
    (is (not (:subsumed? (subsumes? {"type" "string" "maxLength" 50}
                                    {"type" "string" "maxLength" 100})))))
  
  (testing "Pattern constraints"
    (is (:subsumed? (subsumes? {"type" "string" "pattern" "^[a-z]+$"}
                               {"type" "string" "pattern" "^[a-z]+$"})))  ;; same pattern
    (is (not (:subsumed? (subsumes? {"type" "string" "pattern" "^[a-z]+$"}
                                    {"type" "string"}))))  ;; no pattern
    (is (not (:subsumed? (subsumes? {"type" "string" "pattern" "^[a-z]+$"}
                                    {"type" "string" "pattern" "^[0-9]+$"}))))))  ;; different pattern

(deftest test-array-constraints
  (testing "Items count constraints"
    (is (:subsumed? (subsumes? {"type" "array" "minItems" 1 "maxItems" 10}
                               {"type" "array" "minItems" 2 "maxItems" 5})))
    (is (not (:subsumed? (subsumes? {"type" "array" "minItems" 5}
                                    {"type" "array" "minItems" 1}))))
    (is (not (:subsumed? (subsumes? {"type" "array" "maxItems" 5}
                                    {"type" "array" "maxItems" 10})))))
  
  (testing "uniqueItems constraint"
    (is (:subsumed? (subsumes? {"type" "array" "uniqueItems" true}
                               {"type" "array" "uniqueItems" true})))
    (is (:subsumed? (subsumes? {"type" "array"}
                               {"type" "array" "uniqueItems" true})))  ;; more constrained output
    (is (not (:subsumed? (subsumes? {"type" "array" "uniqueItems" true}
                                    {"type" "array"})))))  ;; input requires unique, output doesn't
  
  (testing "Combined items schema and constraints"
    (is (:subsumed? (subsumes? {"type" "array" "items" {"type" "number"} "minItems" 1}
                               {"type" "array" "items" {"type" "integer"} "minItems" 2})))
    (is (not (:subsumed? (subsumes? {"type" "array" "items" {"type" "integer"} "minItems" 1}
                                    {"type" "array" "items" {"type" "number"} "minItems" 2}))))))

(deftest test-enum-and-const
  (testing "Enum subsumption"
    (is (:subsumed? (subsumes? {"enum" ["a" "b" "c"]} {"enum" ["a" "b"]})))
    (is (:subsumed? (subsumes? {"enum" ["a" "b" "c"]} {"enum" ["a"]})))
    (is (not (:subsumed? (subsumes? {"enum" ["a" "b"]} {"enum" ["a" "b" "c"]})))))
  
  (testing "Const subsumption"
    (is (:subsumed? (subsumes? {"const" "hello"} {"const" "hello"})))
    (is (not (:subsumed? (subsumes? {"const" "hello"} {"const" "world"})))))
  
  (testing "Enum subsumes const"
    (is (:subsumed? (subsumes? {"enum" ["a" "b" "c"]} {"const" "b"})))
    (is (not (:subsumed? (subsumes? {"enum" ["a" "b"]} {"const" "c"})))))
  
  (testing "Const subsumes single-value enum"
    (is (:subsumed? (subsumes? {"const" "a"} {"enum" ["a"]})))
    (is (not (:subsumed? (subsumes? {"const" "a"} {"enum" ["a" "b"]}))))))

(deftest test-type-subsumes-enum-const
  (testing "String type subsumes string enum/const"
    (is (:subsumed? (subsumes? {"type" "string"} {"const" "hello"})))
    (is (:subsumed? (subsumes? {"type" "string"} {"enum" ["a" "b" "c"]})))
    (is (not (:subsumed? (subsumes? {"type" "string"} {"enum" ["a" 1 "b"]})))))
  
  (testing "Integer type subsumes integer enum/const"
    (is (:subsumed? (subsumes? {"type" "integer"} {"const" 42})))
    (is (:subsumed? (subsumes? {"type" "integer"} {"enum" [1 2 3]})))
    (is (not (:subsumed? (subsumes? {"type" "integer"} {"enum" [1 2.5 3]}))))))

(deftest test-objects
  (testing "Same structure subsumes"
    (is (:subsumed? (subsumes? 
                     {"type" "object" "properties" {"x" {"type" "integer"}}}
                     {"type" "object" "properties" {"x" {"type" "integer"}}}))))
  
  (testing "Fewer required keys subsumes more keys"
    (is (:subsumed? (subsumes?
                     {"type" "object" 
                      "properties" {"x" {"type" "integer"}}}
                     {"type" "object" 
                      "properties" {"x" {"type" "integer"} "y" {"type" "string"}}}))))
  
  (testing "Required key missing in output fails"
    (is (not (:subsumed? (subsumes?
                          {"type" "object"
                           "properties" {"x" {"type" "integer"} "y" {"type" "string"}}
                           "required" ["x" "y"]}
                          {"type" "object"
                           "properties" {"x" {"type" "integer"}}
                           "required" ["x"]})))))
  
  (testing "Property schema must subsume"
    (is (:subsumed? (subsumes?
                     {"type" "object" "properties" {"x" {"type" "number"}}}
                     {"type" "object" "properties" {"x" {"type" "integer"}}})))
    (is (not (:subsumed? (subsumes?
                          {"type" "object" "properties" {"x" {"type" "integer"}}}
                          {"type" "object" "properties" {"x" {"type" "number"}}}))))))

(deftest test-arrays
  (testing "Same items schema subsumes"
    (is (:subsumed? (subsumes?
                     {"type" "array" "items" {"type" "string"}}
                     {"type" "array" "items" {"type" "string"}}))))
  
  (testing "Items schema must subsume"
    (is (:subsumed? (subsumes?
                     {"type" "array" "items" {"type" "number"}}
                     {"type" "array" "items" {"type" "integer"}})))
    (is (not (:subsumed? (subsumes?
                          {"type" "array" "items" {"type" "integer"}}
                          {"type" "array" "items" {"type" "number"}}))))))

(deftest test-union-types
  (testing "Union type subsumption"
    (is (:subsumed? (subsumes?
                     {"type" ["string" "integer" "null"]}
                     {"type" ["string" "integer"]})))
    (is (not (:subsumed? (subsumes?
                          {"type" ["string" "integer"]}
                          {"type" ["string" "integer" "null"]})))))
  
  (testing "Single type subsumes matching union"
    (is (:subsumed? (subsumes?
                     {"type" "string"}
                     {"type" ["string"]}))))
  
  (testing "Union subsumes single type in union"
    (is (:subsumed? (subsumes?
                     {"type" ["string" "integer"]}
                     {"type" "string"})))))

(deftest test-oneOf-anyOf
  (testing "Input subsumes all oneOf branches"
    (is (:subsumed? (subsumes?
                     {"type" "number"}
                     {"oneOf" [{"type" "integer"} 
                               {"type" "number" "minimum" 0}]})))
    (is (not (:subsumed? (subsumes?
                          {"type" "integer"}
                          {"oneOf" [{"type" "integer"} {"type" "string"}]})))))
  
  (testing "Any oneOf branch subsumes output"
    (is (:subsumed? (subsumes?
                     {"oneOf" [{"type" "string"} {"type" "integer"}]}
                     {"type" "string"})))
    (is (not (:subsumed? (subsumes?
                          {"oneOf" [{"type" "string"} {"type" "integer"}]}
                          {"type" "boolean"}))))))

(deftest test-allOf
  (testing "allOf on output - input must subsume each branch"
    ;; Output is intersection: must be both >= 0 AND <= 100
    ;; Input (number) subsumes both constraints
    (is (:subsumed? (subsumes?
                     {"type" "number"}
                     {"allOf" [{"type" "number" "minimum" 0}
                               {"type" "number" "maximum" 100}]})))
    ;; Input has narrower range than one branch
    (is (not (:subsumed? (subsumes?
                          {"type" "number" "minimum" 50}
                          {"allOf" [{"type" "number" "minimum" 0}
                                    {"type" "number" "maximum" 100}]})))))
  
  (testing "allOf on input - all branches must subsume output"
    ;; Input requires both string AND minLength 5
    ;; Output (string with minLength 10) satisfies both
    (is (:subsumed? (subsumes?
                     {"allOf" [{"type" "string"}
                               {"type" "string" "minLength" 5}]}
                     {"type" "string" "minLength" 10})))
    ;; Output doesn't satisfy minLength constraint
    (is (not (:subsumed? (subsumes?
                          {"allOf" [{"type" "string"}
                                    {"type" "string" "minLength" 10}]}
                          {"type" "string" "minLength" 5})))))
  
  (testing "allOf with object schemas (common pattern)"
    ;; Combining base object with additional properties
    (is (:subsumed? (subsumes?
                     {"allOf" [{"type" "object" "properties" {"id" {"type" "string"}}}
                               {"type" "object" "properties" {"name" {"type" "string"}}}]}
                     {"type" "object" 
                      "properties" {"id" {"type" "string"} "name" {"type" "string"}}
                      "required" ["id" "name"]})))))

(deftest test-fsm-realistic-scenarios
  (testing "LLM action input (any) subsumes transition output"
    (is (:subsumed? (subsumes?
                     true  ;; llm-action accepts anything
                     {"type" "object"
                      "properties" {"id" {"type" "array" "items" {"type" "string"}}
                                    "message" {"type" "string"}}
                      "required" ["id" "message"]}))))
  
  (testing "Transition schema compatibility"
    ;; State A outputs: {id, decision, reasoning}
    ;; State B accepts: {id, decision} (subset - should work)
    (is (:subsumed? (subsumes?
                     {"type" "object"
                      "properties" {"id" {"const" ["A" "B"]}
                                    "decision" {"type" "string"}}}
                     {"type" "object"
                      "properties" {"id" {"const" ["A" "B"]}
                                    "decision" {"type" "string"}
                                    "reasoning" {"type" "string"}}
                      "required" ["id" "decision" "reasoning"]}))))
  
  (testing "Schema evolution - backward compatible"
    ;; Old producer: outputs {name: string}
    ;; New consumer: accepts {name: string, age?: integer}
    (is (:subsumed? (subsumes?
                     {"type" "object"
                      "properties" {"name" {"type" "string"}
                                    "age" {"type" "integer"}}}
                     {"type" "object"
                      "properties" {"name" {"type" "string"}}
                      "required" ["name"]}))))
  
  (testing "Schema evolution - NOT backward compatible"
    ;; New consumer requires age, old producer doesn't provide it
    (is (not (:subsumed? (subsumes?
                          {"type" "object"
                           "properties" {"name" {"type" "string"}
                                         "age" {"type" "integer"}}
                           "required" ["name" "age"]}
                          {"type" "object"
                           "properties" {"name" {"type" "string"}}
                           "required" ["name"]}))))))

(deftest test-error-messages
  (testing "Error messages are informative"
    (let [result (subsumes? {"type" "integer"} {"type" "string"})]
      (is (not (:subsumed? result)))
      (is (string? (:reason result))))
    
    (let [result (subsumes? 
                  {"type" "object" "properties" {"x" {"type" "integer"}} "required" ["x"]}
                  {"type" "object" "properties" {"y" {"type" "string"}}})]
      (is (not (:subsumed? result)))
      (is (re-find #"required.*missing" (:reason result))))))

(deftest test-ref-handling
  (testing "$ref requires expansion before subsumption"
    ;; $ref is detected as a distinct type - can't subsume without expansion
    (let [result (subsumes? {"type" "string"} {"$ref" "#/$defs/foo"})]
      (is (not (:subsumed? result)))
      (is (re-find #"ref" (:reason result))))
    
    ;; NOTE: In practice, expand refs first using claij.schema/expand-refs
    ;; Example workflow:
    ;; (require '[claij.schema :as schema])
    ;; (let [defs (get fsm-schemas "$defs")
    ;;       expanded (schema/expand-refs output-schema defs)]
    ;;   (subsumes? input-schema expanded))
    ))
