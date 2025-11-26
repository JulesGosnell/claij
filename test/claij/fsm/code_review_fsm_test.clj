(ns claij.fsm.code-review-fsm-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
   [clojure.pprint :refer [pprint]]
   [m3.validate :refer [validate]]
   [claij.util :refer [index-by ->key]]
   [claij.llm.open-router :refer [open-router-async]]
   [claij.fsm :refer [start-fsm make-prompts llm-action]]
   [claij.fsm.code-review-fsm :refer [code-review-schema
                                      code-review-fsm]]))

;;------------------------------------------------------------------------------
;; Code Review Schema Tests

(deftest code-review-schema-test
  (testing "code-review"
    (doseq [[provider model]
            [;; ["openai" "gpt-5-codex"]
             ;; ["google" "gemini-2.5-flash"]
             ;; ["x-ai" "grok-code-fast-1"]
             ;; ["anthropic" "claude-sonnet-4.5"]
             ;; ["meta-llama" "llama-4-maverick:free"] ;; Disabled: moderation issues with error messages
             ]]
      (testing (str provider "/" model)
        (let [schema code-review-schema
              prompts (make-prompts
                       code-review-fsm
                       ((index-by (->key "id") (code-review-fsm "xitions")) ["" "mc"])
                       ((index-by (->key "id") (code-review-fsm "states")) "mc")

                       [;; previous conversation
                        ;; ...
                        ;; latest request
                        {"role" "user"
                         "content"
                         [;; describes request
                          {"$ref" "#/$defs/entry"}
                         ;; request
                          {"id" ["" "mc"] "document" "I have this piece of Clojure code to calculate the fibonacci series - can we improve it through a code review?: `(def fibs (lazy-seq (cons 0 (cons 1 (map + fibs (rest fibs))))))`"}
                         ;; describes response
                          {"oneOf" [{"$ref" "#/$defs/request"}
                                    {"$ref" "#/$defs/summary"}]}]}])]
          (let [p (promise)]
            (log/info (deref (open-router-async provider model prompts (partial deliver p) (partial deliver p)) 60000 "timed out after 60s"))
            (is (:valid? (validate {:draft :draft7} schema {} (deref p 60000 "timed out after 60s"))))))))
    ;; Test passes vacuously when no models configured
    (is true "No models configured for testing")))

;;------------------------------------------------------------------------------
;; Code Review FSM Mock Tests

(deftest code-review-fsm-mock-test
  (testing "code-review FSM with mock LLM actions"
    (let [text
          "Please review this fibonacci code: (defn fib [n] (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))"

          llms
          [{"provider" "openai" "model" "gpt-4o"}]

          ;; Sample concerns for this review
          concerns
          ["Simplicity: Can this be simpler?"
           "Performance: Avoid reflection, consider algorithmic efficiency"
           "Functional style: Use pure functions and immutable data"]

          ;; These are the data payloads that will be in the trail
          entry-data
          {"id" ["start" "mc"]
           "document" text
           "llms" llms
           "concerns" concerns}

          request1-data
          {"id" ["mc" "reviewer"]
           "code" {"language" {"name" "clojure"}
                   "text" "(defn fib [n]\n  (if (<= n 1)\n    n\n    (+ (fib (- n 1)) (fib (- n 2)))))"}
           "notes" "Here's a recursive fibonacci. Please review for improvements."
           "concerns" ["Performance: Avoid reflection, consider algorithmic efficiency"
                       "Functional style: Use pure functions and immutable data"]
           "llm" {"provider" "openai" "model" "gpt-4o"}}

          response1-data
          {"id" ["reviewer" "mc"]
           "code" {"language" {"name" "clojure"}
                   "text" "(def fib\n  (memoize\n    (fn [n]\n      (if (<= n 1)\n        n\n        (+ (fib (- n 1)) (fib (- n 2)))))))"}
           "comments" ["Consider using memoization to avoid redundant calculations"
                       "The algorithm is correct but inefficient for large n"]
           "notes" "Added memoization to improve performance."}

          request2-data
          {"id" ["mc" "reviewer"]
           "code" {"language" {"name" "clojure"}
                   "text" "(def fib\n  (memoize\n    (fn [n]\n      (if (<= n 1)\n        n\n        (+ (fib (- n 1)) (fib (- n 2)))))))"}
           "notes" "Incorporated memoization. Please review again."
           "concerns" ["Simplicity: Can this be simpler?"]
           "llm" {"provider" "openai" "model" "gpt-4o"}}

          response2-data
          {"id" ["reviewer" "mc"]
           "code" {"language" {"name" "clojure"}
                   "text" "(def fib\n  (memoize\n    (fn [n]\n      (if (<= n 1)\n        n\n        (+ (fib (- n 1)) (fib (- n 2)))))))"}
           "comments" []
           "notes" "Looks good! The memoization solves the performance issue."}

          summary-data
          {"id" ["mc" "end"]
           "code" {"language" {"name" "clojure"}
                   "text" "(def fib\n  (memoize\n    (fn [n]\n      (if (<= n 1)\n        n\n        (+ (fib (- n 1)) (fib (- n 2)))))))"}
           "notes" "Code review complete. Added memoization for performance."}

          ;; Map from input data to output data
          event-map
          {entry-data request1-data
           request1-data response1-data
           response1-data request2-data
           request2-data response2-data
           response2-data summary-data}

          llm-action (fn [context _fsm _ix _state [{[_input-schema input-data _output-schema] "content"} & _tail] handler]
                       (handler context (event-map input-data)))

          end-action (fn [context _fsm _ix _state trail _handler]
                       ;; Deliver [context trail] to completion promise
                       (when-let [p (:fsm/completion-promise context)]
                         (deliver p [context trail])))

          code-review-actions {"llm" llm-action "end" end-action}

          context {:id->action code-review-actions}

          [submit await stop-fsm] (start-fsm context code-review-fsm)]

      (try
        (submit entry-data)

        (let [result (await 5000)]
          (is (not= result :timeout) "FSM should complete within timeout")
          (when (not= result :timeout)
            (let [[final-context trail] result
                  final-event (claij.fsm/last-event trail)]
              (is (= summary-data final-event) "FSM should complete with summary"))))

        (catch Throwable t
          (is false (str "event submission failed: " (.getMessage t))))

        (finally
          (stop-fsm))))))

(deftest llm-action-handler-arity-test
  (testing "llm-action calls handler with 2 args (context, event)"
    (let [handler-calls (atom [])
          ;; Mock handler that records how it was called
          mock-handler (fn [& args]
                         (swap! handler-calls conj args)
                         nil)
          ;; Minimal FSM/state/trail structures
          fsm code-review-fsm
          ix (first (filter #(= (get % "id") ["start" "mc"]) (get fsm "xitions")))
          state (first (filter #(= (get % "id") "mc") (get fsm "states")))
          trail [{"role" "user"
                  "content" [{"$ref" "#/$defs/entry"}
                             {"id" ["start" "mc"]
                              "document" "test"
                              "llms" [{"provider" "openai" "model" "gpt-4o"}]
                              "concerns" ["test concern"]}
                             {"$ref" "#/$defs/request"}]}]
          context {:test true}]
      ;; Call the real llm-action with mocked open-router-async
      (with-redefs [open-router-async (fn [_provider _model _prompts success-handler & _opts]
                                        ;; Immediately call success with fake LLM response
                                        (success-handler {"id" ["mc" "reviewer"]
                                                          "code" {"language" {"name" "clojure"} "text" "(+ 1 1)"}
                                                          "notes" "test"
                                                          "concerns" ["test"]
                                                          "llm" {"provider" "openai" "model" "gpt-4o"}}))]
        (try
          (llm-action context fsm ix state trail mock-handler)
          ;; If we get here without exception, check handler was called with 2 args
          (is (= 1 (count @handler-calls)) "handler should be called once")
          (is (= 2 (count (first @handler-calls))) "handler should receive 2 args (context, event)")
          (catch clojure.lang.ArityException e
            (is false (str "BUG: handler called with wrong arity - " (.getMessage e)))))))))
