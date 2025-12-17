(ns claij.hat-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.hat :refer [make-hat static-echo-hat-maker dynamic-counter-hat-maker]]))

;;------------------------------------------------------------------------------
;; Task 2: Hat-maker Contract Tests
;;------------------------------------------------------------------------------

(deftest make-hat-contract-test
  (testing "make-hat returns a hat-fn"
    (let [hat-fn (make-hat static-echo-hat-maker "mc" nil)]
      (is (fn? hat-fn) "make-hat should return a function"))))

(deftest static-hat-test
  (testing "static hat returns same fragment regardless of context"
    (let [hat-fn (make-hat static-echo-hat-maker "mc" nil)
          [ctx1 frag1] (hat-fn {})
          [ctx2 frag2] (hat-fn {:some "context"})]

      ;; Context unchanged
      (is (= {} ctx1) "Static hat should not modify empty context")
      (is (= {:some "context"} ctx2) "Static hat should not modify existing context")

      ;; Fragment structure is correct
      (is (= 1 (count (get frag1 "states"))) "Should have one state")
      (is (= 2 (count (get frag1 "xitions"))) "Should have two xitions (in/out)")
      (is (= 1 (count (get frag1 "prompts"))) "Should have one prompt")

      ;; State naming follows convention
      (is (= "mc-echo" (get-in frag1 ["states" 0 "id"])) "Service state should be state-id + suffix")

      ;; Xitions form loopback
      (let [xitions (get frag1 "xitions")
            [out-x in-x] xitions]
        (is (= ["mc" "mc-echo"] (get out-x "id")) "Out xition: mc -> mc-echo")
        (is (= ["mc-echo" "mc"] (get in-x "id")) "In xition: mc-echo -> mc"))))

  (testing "static hat with config"
    (let [hat-fn (make-hat static-echo-hat-maker "worker" {:some "config"})
          [ctx frag] (hat-fn {})]
      ;; Config is available to hat-maker but echo hat ignores it
      (is (= "worker-echo" (get-in frag ["states" 0 "id"]))))))

(deftest dynamic-hat-test
  (testing "dynamic hat without context initializes resources"
    (let [hat-fn (make-hat dynamic-counter-hat-maker "mc" nil)
          [ctx frag] (hat-fn {})]

      ;; Context should be enriched
      (is (= 100 (:counter/value ctx)) "Should initialize counter in context")

      ;; Fragment should have loose schema
      (let [out-xition (first (get frag "xitions"))]
        (is (= [:map ["increment" :int]] (get out-xition "schema"))
            "Should have loose schema when initializing"))

      ;; Prompts indicate initialization
      (is (some #(re-find #"initializing" %) (get frag "prompts"))
          "Prompts should indicate initialization")))

  (testing "dynamic hat with context uses existing resources"
    (let [hat-fn (make-hat dynamic-counter-hat-maker "mc" nil)
          existing-ctx {:counter/value 50}
          [ctx frag] (hat-fn existing-ctx)]

      ;; Context should be unchanged (resource already exists)
      (is (= 50 (:counter/value ctx)) "Should not modify existing counter")

      ;; Fragment should have specific schema based on context
      (let [out-xition (first (get frag "xitions"))]
        (is (= [:map ["increment" [:int {:min 1 :max 50}]]] (get out-xition "schema"))
            "Should have specific schema from context"))

      ;; Prompts should reflect the context
      (is (some #(re-find #"max increment: 50" %) (get frag "prompts"))
          "Prompts should reflect context value")))

  (testing "dynamic hat produces different output based on context"
    (let [hat-fn (make-hat dynamic-counter-hat-maker "mc" nil)
          [ctx1 frag1] (hat-fn {})
          [ctx2 frag2] (hat-fn {:counter/value 25})]

      ;; Different contexts produce different fragments
      (let [schema1 (get-in frag1 ["xitions" 0 "schema"])
            schema2 (get-in frag2 ["xitions" 0 "schema"])]
        (is (not= schema1 schema2) "Different contexts should produce different schemas")))))

(deftest fragment-structure-test
  (testing "fragment has required structure"
    (let [hat-fn (make-hat static-echo-hat-maker "mc" nil)
          [_ frag] (hat-fn {})]

      ;; All required keys present
      (is (contains? frag "states") "Fragment should have states")
      (is (contains? frag "xitions") "Fragment should have xitions")
      (is (contains? frag "prompts") "Fragment should have prompts")

      ;; States are valid
      (doseq [state (get frag "states")]
        (is (string? (get state "id")) "State should have string id")
        (is (string? (get state "action")) "State should have action"))

      ;; Xitions are valid
      (doseq [xition (get frag "xitions")]
        (let [[from to] (get xition "id")]
          (is (string? from) "Xition should have string from-state")
          (is (string? to) "Xition should have string to-state"))
        (is (some? (get xition "schema")) "Xition should have schema")))))
