(ns claij.store-test
  "Tests for FSM document storage with versioning"
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [claij.store :as store]
   [clojure.tools.logging :as log]
   [next.jdbc :as jdbc])
  (:import
   [org.testcontainers.containers PostgreSQLContainer]))

;;------------------------------------------------------------------------------
;; Test infrastructure

(def ^:dynamic *store* nil)

(defn create-test-container
  "Start a PostgreSQL test container and return [stop-fn connection]"
  []
  (log/info "Initializing test database container")
  (let [pg-container (doto (PostgreSQLContainer. "postgres:16")
                       (.withDatabaseName "test_db")
                       (.withUsername "test_user")
                       (.withPassword "test_pass")
                       (.start))
        conn (store/init-postgres-store
              (.getHost pg-container)
              (.getFirstMappedPort pg-container)
              (.getDatabaseName pg-container)
              (.getUsername pg-container)
              (.getPassword pg-container))]

    (log/info "Creating database schema")
    (jdbc/with-transaction [tx conn]
      (jdbc/execute! tx
                     ["CREATE TABLE IF NOT EXISTS fsm (
            id VARCHAR(255) NOT NULL,
            version INTEGER NOT NULL,
            document TEXT NOT NULL,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id, version)
          )"]))

    (log/info "Test database ready")
    [(fn [] (.stop pg-container)) conn]))

(defn with-test-store
  "Fixture that provides a test store in *store*"
  [test-fn]
  (let [[cleanup conn] (create-test-container)]
    (binding [*store* conn]
      (try
        (test-fn)
        (finally
          (cleanup))))))

(use-fixtures :once with-test-store)

;;------------------------------------------------------------------------------
;; Test data

(def doc-a-v1 {"data" "alpha" "$version" 1})
(def doc-a-v2 {"data" "alpha-updated" "$version" 2})
(def doc-b-v1 {"data" "beta" "$version" 1})
(def doc-c-v1 {"data" "gamma" "$version" 1})
(def doc-c-v2 {"data" "gamma-updated" "$version" 2})

;;------------------------------------------------------------------------------
;; Tests

(deftest fsm-storage-test
  (testing "Error handling"
    (testing "Loading with nil version throws exception"
      (is (thrown? IllegalArgumentException
                   (store/fsm-load-version *store* "doc-x" nil)))))

  (testing "Empty store behavior"
    (testing "Latest version returns nil for non-existent documents"
      (is (nil? (store/fsm-latest-version *store* "doc-a")))
      (is (nil? (store/fsm-latest-version *store* "doc-b")))))

  (testing "Initial document storage"
    (store/fsm-store! *store* "doc-a" doc-a-v1)
    (store/fsm-store! *store* "doc-b" doc-b-v1)

    (testing "Documents are stored"
      (is (= 1 (store/fsm-latest-version *store* "doc-a")))
      (is (= 1 (store/fsm-latest-version *store* "doc-b")))))

  (testing "Duplicate storage (idempotency)"
    (store/fsm-store! *store* "doc-a" doc-a-v1)
    (store/fsm-store! *store* "doc-b" doc-b-v1)

    (testing "Duplicate stores don't create new versions"
      (is (= 1 (store/fsm-latest-version *store* "doc-a")))
      (is (= 1 (store/fsm-latest-version *store* "doc-b")))))

  (testing "Version updates"
    (store/fsm-store! *store* "doc-a" doc-a-v2)

    (testing "New version is created"
      (is (= 2 (store/fsm-latest-version *store* "doc-a")))
      (is (= 1 (store/fsm-latest-version *store* "doc-b")))))

  (testing "Loading specific versions"
    (is (= doc-a-v1 (store/fsm-load-version *store* "doc-a" 1)))
    (is (= doc-a-v2 (store/fsm-load-version *store* "doc-a" 2)))
    (is (= doc-b-v1 (store/fsm-load-version *store* "doc-b" 1))))

  (testing "Bulk version queries"
    (let [versions (store/fsm-latest-versions-for-ids *store* ["doc-a" "doc-b"])]
      (is (= {"doc-a" 2 "doc-b" 1} versions))))

  (testing "Refresh operations - initial load"
    (let [loader (store/make-edn-loader doc-c-v1)
          result (store/fsm-refresh! *store* "doc-c" loader)]
      (is (= doc-c-v1 result))
      (is (= 1 (store/fsm-latest-version *store* "doc-c")))
      (is (= doc-c-v1 (store/fsm-load-version *store* "doc-c" 1)))))

  (testing "Refresh operations - with changes"
    ;; Wait to ensure timestamp difference
    (Thread/sleep 1000)

    (let [loader (store/make-edn-loader doc-c-v2)
          result (store/fsm-refresh! *store* "doc-c" loader)]
      (is (= doc-c-v2 result))
      (is (= 2 (store/fsm-latest-version *store* "doc-c")))
      (is (= doc-c-v1 (store/fsm-load-version *store* "doc-c" 1)))
      (is (= doc-c-v2 (store/fsm-load-version *store* "doc-c" 2)))))

  (testing "Refresh operations - no changes"
    ;; Wait to ensure timestamp difference
    (Thread/sleep 1000)

    (let [loader (store/make-edn-loader doc-c-v2)
          result (store/fsm-refresh! *store* "doc-c" loader)]
      (is (= doc-c-v2 result))
      (is (= 2 (store/fsm-latest-version *store* "doc-c")))
      (is (= doc-c-v1 (store/fsm-load-version *store* "doc-c" 1)))
      (is (= doc-c-v2 (store/fsm-load-version *store* "doc-c" 2)))))

  (testing "Audit trail"
    (let [remove-timestamps (fn [records]
                              (mapv #(dissoc % :created-at) records))]

      (testing "Audit trail for doc-a"
        (let [trail (store/fsm-load-audit-trail *store* "doc-a")]
          (is (= [{:id "doc-a" :version 1 :document doc-a-v1}
                  {:id "doc-a" :version 2 :document doc-a-v2}]
                 (remove-timestamps trail)))))

      (testing "Audit trail for doc-b"
        (let [trail (store/fsm-load-audit-trail *store* "doc-b")]
          (is (= [{:id "doc-b" :version 1 :document doc-b-v1}]
                 (remove-timestamps trail)))))

      (testing "Audit trail for doc-c"
        (let [trail (store/fsm-load-audit-trail *store* "doc-c")]
          (is (= [{:id "doc-c" :version 1 :document doc-c-v1}
                  {:id "doc-c" :version 2 :document doc-c-v2}]
                 (remove-timestamps trail))))))))
