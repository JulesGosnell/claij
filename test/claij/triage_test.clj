(ns claij.triage-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.tools.logging :as log]
   [clojure.data.json :as json]
   [claij.actions :as actions]
   [claij.triage :refer [start-triage]]
   [claij.store :as store]
   [claij.fsm.code-review-fsm :refer [code-review-fsm]]
   [next.jdbc :as jdbc])
  (:import
   [org.testcontainers.containers PostgreSQLContainer]))

;;------------------------------------------------------------------------------
;; Test Infrastructure
;;------------------------------------------------------------------------------

(def ^:dynamic *store* nil)
(def ^:dynamic *container* nil)

(defn setup-test-db []
  (let [container (doto (PostgreSQLContainer. "postgres:15-alpine")
                    (.start))
        store (store/init-postgres-store
               (.getHost container)
               (.getFirstMappedPort container)
               (.getDatabaseName container)
               (.getUsername container)
               (.getPassword container))]

    ;; Create the database schema
    (jdbc/with-transaction [tx store]
      (jdbc/execute! tx
                     ["CREATE TABLE IF NOT EXISTS fsm (
                         id VARCHAR(255) NOT NULL,
                         version INTEGER NOT NULL,
                         document TEXT NOT NULL,
                         created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         PRIMARY KEY (id, version)
                       )"]))

    {:container container :store store}))

(defn teardown-test-db [{:keys [container]}]
  (when container
    (.stop container)))

(defn db-fixture [f]
  (let [{:keys [container store]} (setup-test-db)]
    (binding [*container* container
              *store* store]
      (try
        (f)
        (finally
          (teardown-test-db {:container container}))))))

(use-fixtures :each db-fixture)

;;------------------------------------------------------------------------------
;; Tests
;;------------------------------------------------------------------------------

(deftest ^:integration triage-fsm-delegation-test
  (testing "Triage FSM can delegate to code-review FSM"
    (let [;; Store the code-review FSM in the database
          _ (store/fsm-store! *store* "code-review" (assoc code-review-fsm "$version" 0))

          ;; Mock LLM actions that simulate the decision flow
          mock-triage-llm
          (fn [context fsm ix state event trail handler]
            (log/info "   Mock Triage: Choosing code-review FSM")
            ;; Simulate LLM deciding to use code-review FSM
            (handler context {"id" ["triage" "reuse"]
                              "fsm-id" "code-review"
                              "fsm-version" 0
                              "rationale" "User asked for code review"}))

          ;; Mock code-review LLM that provides a simple response
          mock-review-llm
          (fn [context fsm ix state event trail handler]
            (let [{xid "id"} event]
              (log/info (str "   Mock Review: Processing " xid))
              (cond
                ;; Entry: submit code for review
                (= xid ["start" "mc"])
                (handler context {"id" ["mc" "reviewer"]
                                  "code" {"language" {"name" "clojure"}
                                          "text" "(defn fib [n] (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))"}
                                  "notes" "Please review this fibonacci implementation"
                                  "concerns" ["Performance: Consider algorithmic efficiency"
                                              "Functional style: Use pure functions"]
                                  "llm" {"provider" "openai" "model" "gpt-4o"}})

                ;; Reviewer responds
                (= xid ["mc" "reviewer"])
                (handler context {"id" ["reviewer" "mc"]
                                  "code" {"language" {"name" "clojure"}
                                          "text" "(def fib (memoize (fn [n] (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))))"}
                                  "comments" ["Add memoization for performance"]
                                  "notes" "Improved version with memoization"})

                ;; MC ends the review
                (= xid ["reviewer" "mc"])
                (handler context {"id" ["mc" "end"]
                                  "code" {"language" {"name" "clojure"}
                                          "text" "(def fib (memoize (fn [n] (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))))"}
                                  "notes" "Review complete"}))))

          ;; Create context with mock actions
          context (actions/make-context
                   {:store *store*
                    :provider "mock"
                    :model "mock"
                    :id->action (merge actions/default-actions
                                       {"triage" mock-triage-llm
                                        "llm" mock-review-llm})})

          [submit await stop-fsm] (start-triage context)]

      (try
        ;; Submit a code review request to the triage FSM
        (submit "Please review my fibonacci function")

        ;; Wait for result using new await API
        (let [result (await 10000)]
          (is (not= result :timeout) "Triage FSM should complete")
          (when (not= result :timeout)
            (let [[final-context trail] result
                  final-result (claij.fsm/last-event trail)]
              (is (= (get final-result "id") ["reuse" "end"]) "Should delegate through reuse")
              (is (get final-result "success") "Delegation should succeed")
              (log/info "Final result:" final-result))))

        (finally
          (stop-fsm))))))

(deftest ^:integration triage-empty-store-test
  (testing "Triage FSM handles empty store gracefully"
    (let [;; No FSMs in store - triage action should route to generate
          context (actions/make-context
                   {:store *store*
                    :provider "mock"
                    :model "mock"})
          ;; Use real actions - no need to mock since triage-action handles empty store

          [submit await stop-fsm] (start-triage context)]

      (try
        (submit "Some request")

        ;; Wait for result using new await API
        (let [result (await 10000)]
          (is (not= result :timeout) "Triage FSM should complete")
          (when (not= result :timeout)
            (let [[final-context trail] result
                  final-result (claij.fsm/last-event trail)]
              ;; With empty store, it should route to generate
              (is (= (get final-result "id") ["generate" "end"]) "Should route to generate when no FSMs available")
              (is (not (get final-result "success")) "Should fail when generation not implemented"))))

        (finally
          (stop-fsm))))))
