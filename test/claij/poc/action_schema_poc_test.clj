(ns claij.poc.action-schema-poc-test
  "POC: def-action macro - validated functions with input/output schemas.
   
   The pattern:
   - def-action defines a function with input and output schemas
   - The function is wrapped with validation: input checked before call, output after
   - Schemas and name are available as metadata for FSM pre-validation
   - Throws on validation failure"
  (:require
   [clojure.test :refer [deftest testing is]]
   [malli.core :as m]))

;;==============================================================================
;; Action Exception
;;==============================================================================

(defn action-exception [type action-name schema value explanation]
  (ex-info (str "Action " type " validation failed: " action-name)
           {:type type
            :action action-name
            :schema schema
            :value value
            :explanation explanation}))

;;==============================================================================
;; def-action macro
;;==============================================================================

(defmacro def-action
  "Define a validated action function.
   
   Usage:
   (def-action my-action
     \"Documentation string\"
     [:tuple :string :int]           ;; input schema (tuple-n for multiple args)
     :string                          ;; output schema
     [name age]                       ;; params (must match input tuple arity)
     (str name \" is \" age \" years old\"))
   
   The defined var has metadata:
   - :action/name - the action name as string
   - :action/input-schema - Malli schema for input
   - :action/output-schema - Malli schema for output
   - :action/doc - documentation string
   
   The function validates input before calling and output after.
   Throws ExceptionInfo on validation failure."
  [name doc input-schema output-schema params & body]
  (let [action-name (str name)]
    `(def ~(with-meta name
             {:action/name action-name
              :action/input-schema input-schema
              :action/output-schema output-schema
              :action/doc doc})
       (fn [& args#]
         (let [input# (vec args#)]
           ;; Validate input
           (when-not (m/validate ~input-schema input#)
             (throw (action-exception :input-validation
                                      ~action-name
                                      ~input-schema
                                      input#
                                      (m/explain ~input-schema input#))))
           ;; Call the actual function
           (let [result# (apply (fn ~params ~@body) args#)]
             ;; Validate output
             (when-not (m/validate ~output-schema result#)
               (throw (action-exception :output-validation
                                        ~action-name
                                        ~output-schema
                                        result#
                                        (m/explain ~output-schema result#))))
             result#))))))

;;==============================================================================
;; Helper to inspect action metadata
;;==============================================================================

(defn action-name [action-var]
  (-> action-var meta :action/name))

(defn action-input-schema [action-var]
  (-> action-var meta :action/input-schema))

(defn action-output-schema [action-var]
  (-> action-var meta :action/output-schema))

(defn action-doc [action-var]
  (-> action-var meta :action/doc))

;;==============================================================================
;; Example Actions
;;==============================================================================

(def-action greet
  "Greet a person by name and age."
  [:tuple :string :int]
  :string
  [name age]
  (str "Hello " name ", you are " age " years old!"))

(def-action add-numbers
  "Add two numbers together."
  [:tuple :int :int]
  :int
  [a b]
  (+ a b))

(def-action parse-config
  "Parse a config string into a map."
  [:tuple :string]
  [:map ["host" :string] ["port" :int]]
  [config-str]
  ;; Simplified - in reality would parse the string
  {"host" "localhost" "port" 8080})

(def-action broken-output
  "An action that returns wrong type (for testing)."
  [:tuple :string]
  :int
  [input]
  ;; Returns string instead of int - should fail output validation
  (str "not an int: " input))

;;==============================================================================
;; Tests
;;==============================================================================

(deftest def-action-metadata-test
  (testing "Action has name in metadata"
    (is (= "greet" (action-name #'greet)))
    (is (= "add-numbers" (action-name #'add-numbers))))
  
  (testing "Action has input schema in metadata"
    (is (= [:tuple :string :int] (action-input-schema #'greet)))
    (is (= [:tuple :int :int] (action-input-schema #'add-numbers))))
  
  (testing "Action has output schema in metadata"
    (is (= :string (action-output-schema #'greet)))
    (is (= :int (action-output-schema #'add-numbers)))
    (is (= [:map ["host" :string] ["port" :int]] 
           (action-output-schema #'parse-config))))
  
  (testing "Action has documentation in metadata"
    (is (= "Greet a person by name and age." (action-doc #'greet)))))

(deftest def-action-valid-call-test
  (testing "Valid input and output passes"
    (is (= "Hello Jules, you are 42 years old!" (greet "Jules" 42)))
    (is (= 7 (add-numbers 3 4)))
    (is (= {"host" "localhost" "port" 8080} (parse-config "any")))))

(deftest def-action-input-validation-test
  (testing "Wrong type for first arg throws"
    (let [ex (try (greet 123 42) nil
                  (catch Exception e e))]
      (is (some? ex))
      (is (= :input-validation (:type (ex-data ex))))
      (is (= "greet" (:action (ex-data ex))))))
  
  (testing "Wrong type for second arg throws"
    (let [ex (try (greet "Jules" "not-an-int") nil
                  (catch Exception e e))]
      (is (some? ex))
      (is (= :input-validation (:type (ex-data ex))))))
  
  (testing "Wrong arity throws"
    (let [ex (try (greet "Jules") nil
                  (catch Exception e e))]
      (is (some? ex))
      (is (= :input-validation (:type (ex-data ex))))))
  
  (testing "Extra args throws"
    (let [ex (try (greet "Jules" 42 "extra") nil
                  (catch Exception e e))]
      (is (some? ex))
      (is (= :input-validation (:type (ex-data ex)))))))

(deftest def-action-output-validation-test
  (testing "Wrong output type throws"
    (let [ex (try (broken-output "test") nil
                  (catch Exception e e))]
      (is (some? ex))
      (is (= :output-validation (:type (ex-data ex))))
      (is (= "broken-output" (:action (ex-data ex))))
      (is (= :int (:schema (ex-data ex))))
      (is (= "not an int: test" (:value (ex-data ex)))))))

(deftest action-registry-pattern-test
  (testing "Can build registry from action vars"
    (let [registry (->> [#'greet #'add-numbers #'parse-config]
                        (map (fn [v] [(action-name v) v]))
                        (into {}))]
      (is (= #'greet (get registry "greet")))
      (is (= [:tuple :string :int] 
             (action-input-schema (get registry "greet"))))
      (is (= :string 
             (action-output-schema (get registry "greet")))))))
