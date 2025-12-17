(ns claij.hat-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.hat :refer [make-hat static-echo-hat-maker dynamic-counter-hat-maker
                      make-hat-registry register-hat get-hat-maker
                      merge-fragment don-hats
                      add-stop-hook run-stop-hooks]]))

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

;;------------------------------------------------------------------------------
;; Task 3: Registry Tests
;;------------------------------------------------------------------------------

(deftest registry-test
  (testing "create empty registry"
    (is (= {} (make-hat-registry))))

  (testing "register and retrieve hat-maker"
    (let [reg (-> (make-hat-registry)
                  (register-hat "echo" static-echo-hat-maker))]
      (is (= static-echo-hat-maker (get-hat-maker reg "echo")))))

  (testing "unknown hat returns nil"
    (let [reg (make-hat-registry)]
      (is (nil? (get-hat-maker reg "unknown"))))))

;;------------------------------------------------------------------------------
;; Task 3: Fragment Merging Tests
;;------------------------------------------------------------------------------

(deftest merge-fragment-test
  (let [base-fsm {"id" "test"
                  "states" [{"id" "mc" "prompts" ["Original prompt"]}
                            {"id" "end"}]
                  "xitions" [{"id" ["start" "mc"] "schema" :any}]}]

    (testing "merge adds states"
      (let [fragment {"states" [{"id" "mc-echo" "action" "echo"}]}
            result (merge-fragment base-fsm fragment "mc")]
        (is (= 3 (count (get result "states"))))
        (is (some #(= "mc-echo" (get % "id")) (get result "states")))))

    (testing "merge adds xitions"
      (let [fragment {"xitions" [{"id" ["mc" "mc-echo"] "schema" :any}]}
            result (merge-fragment base-fsm fragment "mc")]
        (is (= 2 (count (get result "xitions"))))))

    (testing "merge adds prompts to target state"
      (let [fragment {"prompts" ["New prompt"]}
            result (merge-fragment base-fsm fragment "mc")
            mc-state (first (filter #(= "mc" (get % "id")) (get result "states")))]
        (is (= ["Original prompt" "New prompt"] (get mc-state "prompts")))))

    (testing "merge prompts to state without existing prompts"
      (let [fragment {"prompts" ["New prompt"]}
            result (merge-fragment base-fsm fragment "end")
            end-state (first (filter #(= "end" (get % "id")) (get result "states")))]
        (is (= ["New prompt"] (get end-state "prompts")))))))

;;------------------------------------------------------------------------------
;; Task 3: Don Hats Tests
;;------------------------------------------------------------------------------

(deftest don-hats-state-level-test
  (let [registry (-> (make-hat-registry)
                     (register-hat "echo" static-echo-hat-maker))]

    (testing "don state-level hat (string form)"
      (let [fsm {"id" "test"
                 "states" [{"id" "mc" "hats" ["echo"]}
                           {"id" "end"}]
                 "xitions" []}
            [ctx' fsm'] (don-hats {} fsm registry)]
        ;; Should have added echo state
        (is (= 3 (count (get fsm' "states"))))
        (is (some #(= "mc-echo" (get % "id")) (get fsm' "states")))
        ;; Should have added xitions
        (is (= 2 (count (get fsm' "xitions"))))
        ;; Should have added prompts
        (let [mc (first (filter #(= "mc" (get % "id")) (get fsm' "states")))]
          (is (= ["You can echo messages via mc-echo"] (get mc "prompts"))))))

    (testing "don state-level hat (map form with config)"
      (let [fsm {"id" "test"
                 "states" [{"id" "mc" "hats" [{"echo" {:some "config"}}]}
                           {"id" "end"}]
                 "xitions" []}
            [ctx' fsm'] (don-hats {} fsm registry)]
        (is (= 3 (count (get fsm' "states"))))))

    (testing "multiple hats on same state"
      (let [registry' (register-hat registry "counter" dynamic-counter-hat-maker)
            fsm {"id" "test"
                 "states" [{"id" "mc" "hats" ["echo" "counter"]}
                           {"id" "end"}]
                 "xitions" []}
            [ctx' fsm'] (don-hats {} fsm registry')]
        ;; Each hat adds one state
        (is (= 4 (count (get fsm' "states"))))))))

(deftest don-hats-context-modification-test
  (let [registry (-> (make-hat-registry)
                     (register-hat "counter" dynamic-counter-hat-maker))]

    (testing "dynamic hat can modify context"
      (let [fsm {"id" "test"
                 "states" [{"id" "mc" "hats" ["counter"]}]
                 "xitions" []}
            [ctx' fsm'] (don-hats {} fsm registry)]
        ;; Counter hat initializes :counter/value
        (is (= 100 (:counter/value ctx')))))))

(deftest don-hats-unknown-hat-test
  (let [registry (make-hat-registry)]

    (testing "unknown hat is skipped with warning"
      (let [fsm {"id" "test"
                 "states" [{"id" "mc" "hats" ["unknown"]}]
                 "xitions" []}
            [ctx' fsm'] (don-hats {} fsm registry)]
        ;; Should be unchanged (no states added)
        (is (= 1 (count (get fsm' "states"))))))))

(deftest don-hats-fsm-level-test
  (testing "FSM-level hats work"
    (let [;; Create a linking hat that adds error-handler state
          link-hat-maker (fn [state-ids config]
                           (fn [context]
                             [context
                              {"states" [{"id" "error-handler" "action" "error"}]
                               "xitions" (mapv (fn [sid]
                                                 {"id" [sid "error-handler"]
                                                  "schema" :any})
                                               state-ids)}]))
          registry (-> (make-hat-registry)
                       (register-hat "link" link-hat-maker))
          fsm {"id" "test"
               "hats" [["link" ["mc" "worker"]]]
               "states" [{"id" "mc"}
                         {"id" "worker"}]
               "xitions" []}
          [ctx' fsm'] (don-hats {} fsm registry)]
      ;; Should have added error-handler state
      (is (= 3 (count (get fsm' "states"))))
      ;; Should have added xitions from both states to error-handler
      (is (= 2 (count (get fsm' "xitions")))))))

;;------------------------------------------------------------------------------
;; Task 4: Stop Hooks Tests
;;------------------------------------------------------------------------------

(deftest add-stop-hook-test
  (testing "add-stop-hook adds to :fsm/stop-hooks"
    (let [hook (fn [ctx] ctx)
          ctx (add-stop-hook {} hook)]
      (is (= [hook] (:fsm/stop-hooks ctx)))))

  (testing "add-stop-hook appends to existing hooks"
    (let [hook1 (fn [ctx] ctx)
          hook2 (fn [ctx] ctx)
          ctx (-> {}
                  (add-stop-hook hook1)
                  (add-stop-hook hook2))]
      (is (= [hook1 hook2] (:fsm/stop-hooks ctx))))))

(deftest run-stop-hooks-test
  (testing "run-stop-hooks calls hooks in reverse order (LIFO)"
    (let [call-order (atom [])
          hook1 (fn [ctx] (swap! call-order conj 1) ctx)
          hook2 (fn [ctx] (swap! call-order conj 2) ctx)
          hook3 (fn [ctx] (swap! call-order conj 3) ctx)
          ctx (-> {}
                  (add-stop-hook hook1)
                  (add-stop-hook hook2)
                  (add-stop-hook hook3))]
      (run-stop-hooks ctx)
      (is (= [3 2 1] @call-order) "Hooks should run in reverse order")))

  (testing "run-stop-hooks handles errors gracefully"
    (let [call-order (atom [])
          hook1 (fn [ctx] (swap! call-order conj 1) ctx)
          hook-error (fn [_ctx] (throw (ex-info "Test error" {})))
          hook3 (fn [ctx] (swap! call-order conj 3) ctx)
          ctx (-> {}
                  (add-stop-hook hook1)
                  (add-stop-hook hook-error)
                  (add-stop-hook hook3))]
      ;; Should not throw, should continue after error
      (run-stop-hooks ctx)
      (is (= [3 1] @call-order) "Should continue after error")))

  (testing "run-stop-hooks with no hooks"
    (let [ctx {}]
      ;; Should not throw
      (is (= ctx (run-stop-hooks ctx))))))
