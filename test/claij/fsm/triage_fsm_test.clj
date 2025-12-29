(ns claij.fsm.triage-fsm-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [clojure.data.json :as json]
   [claij.actions :as actions]
   [claij.llm :as llm]
   [claij.model :as model]
   [claij.fsm.triage-fsm :refer [start-triage triage-action]]
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

(defn teardown-test-db [{:keys [^PostgreSQLContainer container]}]
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
;; Unit Tests - triage-action (no database needed, uses mocks)
;;------------------------------------------------------------------------------

(deftest triage-action-empty-store-test
  (testing "routes to generate when store is empty"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          f2 (triage-action {} {} {"schema" {"type" "object"}} {})
          context {:store nil :llm/service "test" :llm/model "test"}
          event {"document" "Please review my code"}]
      (with-redefs [store/fsm-list-all (fn [_] [])]
        (f2 context event [] handler))

      (is (= ["triage" "generate"] (get-in @result [:event "id"])))
      (is (string? (get-in @result [:event "requirements"])))
      (is (str/includes?
           (get-in @result [:event "requirements"])
           "Please review my code")))))

(deftest triage-action-with-fsms-test
  (testing "calls LLM when FSMs are available"
    (let [result (atom nil)
          llm-called (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          f2 (triage-action {} {} {"schema" {"type" "object"
                                             "required" ["fsm-id"]
                                             "properties" {"fsm-id" {"type" "string"}}}} {})
          context {:store nil :llm/service "openrouter" :llm/model (model/openrouter-model :openai)}
          event {"document" "Please review my code"}
          mock-fsms [{"id" "code-review" "version" 1 "description" "Reviews code"}
                     {"id" "test-gen" "version" 2 "description" "Generates tests"}]]
      (with-redefs [store/fsm-list-all (fn [_] mock-fsms)
                    llm/call (fn [service model prompts handler & _]
                               (reset! llm-called {:service service
                                                   :model model
                                                   :prompts prompts})
                               (handler {"id" ["triage" "reuse"]
                                         "fsm-id" "code-review"
                                         "fsm-version" 1
                                         "rationale" "Best match"}))]
        (f2 context event [] handler))

      (is (some? @llm-called))
      (is (= "openrouter" (:service @llm-called)))
      (is (= (model/openrouter-model :openai) (:model @llm-called)))

      (let [prompt-content (get-in @llm-called [:prompts 0 "content"])]
        (is (str/includes? prompt-content "code-review"))
        (is (str/includes? prompt-content "test-gen"))
        (is (str/includes? prompt-content "Please review my code")))

      (is (= ["triage" "reuse"] (get-in @result [:event "id"])))
      (is (= "code-review" (get-in @result [:event "fsm-id"]))))))

(deftest triage-action-prompt-formatting-test
  (testing "formats FSM list correctly in prompt"
    (let [llm-prompts (atom nil)
          f2 (triage-action {} {} {"schema" {"type" "object"}} {})
          context {:store nil :llm/service "p" :llm/model "m"}
          event {"document" "user request"}
          mock-fsms [{"id" "fsm-a" "version" 3 "description" "Does A things"}
                     {"id" "fsm-b" "version" 7 "description" "Does B things"}]]
      (with-redefs [store/fsm-list-all (fn [_] mock-fsms)
                    llm/call (fn [_ _ prompts _ & _]
                               (reset! llm-prompts prompts))]
        (f2 context event [] (fn [_ _])))

      (let [content (get-in @llm-prompts [0 "content"])]
        (is (str/includes? content "- FSM: fsm-a (v3): Does A things"))
        (is (str/includes? content "- FSM: fsm-b (v7): Does B things"))
        (is (str/includes? content "User's request: user request"))))))

;;------------------------------------------------------------------------------
;; Integration Tests - Full FSM (requires database)
;;------------------------------------------------------------------------------

(deftest ^:long-running triage-fsm-delegation-test
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
                (= xid ["start" "chairman"])
                (handler context {"id" ["chairman" "reviewer"]
                                  "code" {"language" {"name" "clojure"}
                                          "text" "(defn fib [n] (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))"}
                                  "notes" "Please review this fibonacci implementation"
                                  "concerns" ["Performance: Consider algorithmic efficiency"
                                              "Functional style: Use pure functions"]
                                  "llm" {"service" "openrouter" "model" (model/openrouter-model :openai)}})

                ;; Reviewer responds
                (= xid ["chairman" "reviewer"])
                (handler context {"id" ["reviewer" "chairman"]
                                  "code" {"language" {"name" "clojure"}
                                          "text" "(def fib (memoize (fn [n] (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))))"}
                                  "comments" ["Add memoization for performance"]
                                  "notes" "Improved version with memoization"})

                ;; Chairman ends the review
                (= xid ["reviewer" "chairman"])
                (handler context {"id" ["chairman" "end"]
                                  "code" {"language" {"name" "clojure"}
                                          "text" "(def fib (memoize (fn [n] (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))))"}
                                  "notes" "Review complete"}))))

          ;; Create context with mock actions
          context (actions/make-context
                   {:store *store*
                    :llm/service "mock"
                    :llm/model "mock"
                    :id->action (merge actions/default-actions
                                       {"triage" mock-triage-llm
                                        "llm" mock-review-llm})})

          {:keys [submit await stop]} (start-triage context)]

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
          (stop))))))

(deftest ^:long-running triage-empty-store-test
  (testing "Triage FSM handles empty store gracefully"
    (let [;; No FSMs in store - triage action should route to generate
          context (actions/make-context
                   {:store *store*
                    :llm/service "mock"
                    :llm/model "mock"})
          ;; Use real actions - no need to mock since triage-action handles empty store

          {:keys [submit await stop]} (start-triage context)]

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
          (stop))))))
