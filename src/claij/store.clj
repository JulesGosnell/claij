(ns claij.store
  "FSM document store with versioning and audit trail support"
  (:require
   [clojure.tools.logging :as log]
   [clojure.data.json :as json]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as sql]
   [next.jdbc.prepare :as prepare]
   [next.jdbc.result-set :as rs])
  (:import
   [java.sql Timestamp PreparedStatement]
   [org.postgresql.util PGobject]))

;;------------------------------------------------------------------------------
;; PostgreSQL JSON support

(defn ->pgobject
  "Convert a Clojure value to a PostgreSQL JSONB object"
  [value]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-str value))))

(defn <-pgobject
  "Convert a PostgreSQL JSONB object to a Clojure value"
  [^PGobject pg-obj]
  (when pg-obj
    (json/read-str (.getValue pg-obj))))

;; Extend protocols for automatic JSON conversion
(extend-protocol prepare/SettableParameter
  clojure.lang.IPersistentMap
  (set-parameter [m ^PreparedStatement ps ^long i]
    (.setObject ps i (->pgobject m)))

  clojure.lang.IPersistentVector
  (set-parameter [v ^PreparedStatement ps ^long i]
    (.setObject ps i (->pgobject v))))

(extend-protocol rs/ReadableColumn
  PGobject
  (read-column-by-label [^PGobject v _]
    (<-pgobject v))
  (read-column-by-index [^PGobject v _2 _3]
    (<-pgobject v)))

;;------------------------------------------------------------------------------
;; Connection

(defn init-postgres-store
  "Initialize a PostgreSQL connection for FSM storage"
  [host port database user password]
  (let [db-spec {:dbtype "postgresql"
                 :host host
                 :port port
                 :dbname database
                 :user user
                 :password password}]
    (jdbc/get-datasource db-spec)))

;;------------------------------------------------------------------------------
;; Core operations

(defn fsm-latest-version
  "Get the latest version number for an FSM id, or nil if none exists"
  [conn id]
  (when-let [result (jdbc/execute-one!
                     conn
                     ["SELECT MAX(version) as max_version FROM fsm WHERE id = ?" id]
                     {:builder-fn rs/as-unqualified-kebab-maps})]
    (:max-version result)))

(defn fsm-load-version
  "Load a specific version of an FSM document"
  [conn id version]
  (when (nil? version)
    (throw (IllegalArgumentException. "Version cannot be nil")))
  (log/info (str "loaded fsm: " id "[" version "]"))
  (let [result (jdbc/execute-one!
                conn
                ["SELECT document FROM fsm WHERE id = ? AND version = ?" id version]
                {:builder-fn rs/as-unqualified-kebab-maps})]
    (:document result)))

(defn fsm-store!
  "Store an FSM document with its version"
  [conn id document]
  (let [version (get document "$version")]
    ;; Check if this version already exists
    (let [existing (jdbc/execute-one!
                    conn
                    ["SELECT version FROM fsm WHERE id = ? AND version = ?" id version]
                    {:builder-fn rs/as-unqualified-kebab-maps})]
      (when-not existing
        (sql/insert! conn :fsm
                     {:id id
                      :version version
                      :document document
                      :created_at (Timestamp. (System/currentTimeMillis))})))
    document))

(defn fsm-latest-versions-for-ids
  "Get a map of id -> latest version for multiple FSM ids"
  [conn ids]
  (let [placeholders (clojure.string/join ", " (repeat (count ids) "?"))
        query (str "SELECT id, MAX(version) as version FROM fsm WHERE id IN (" placeholders ") GROUP BY id")
        results (jdbc/execute! conn (into [query] ids)
                               {:builder-fn rs/as-unqualified-kebab-maps})]
    (into {} (map (juxt :id :version) results))))

(defn fsm-list-all
  "List all FSMs with their latest version and description.
   
   Returns a sequence of maps with keys:
   - \"id\" - FSM identifier
   - \"version\" - Latest version number  
   - \"description\" - Description from FSM schema (if present)"
  [conn]
  (let [;; Get all distinct FSM IDs with their latest version
        query "SELECT id, MAX(version) as version FROM fsm GROUP BY id ORDER BY id"
        id-versions (jdbc/execute! conn [query]
                                   {:builder-fn rs/as-unqualified-kebab-maps})

        ;; Load each FSM's document to extract description
        fsms (mapv (fn [{:keys [id version]}]
                     (let [doc (fsm-load-version conn id version)
                           description (get-in doc ["schema" "description"] "No description")]
                       {"id" id
                        "version" version
                        "description" description}))
                   id-versions)]

    (log/info (str "Listed " (count fsms) " FSMs from store"))
    fsms))

(defn make-json-bytes-loader
  "Create a loader function that returns the given data"
  [data]
  (fn [] (json/write-str data)))

(defn fsm-refresh!
  "Load or refresh an FSM document, storing a new version if content changed"
  [conn id loader]
  (let [current-version (fsm-latest-version conn id)
        _ (log/info (str "refresh! " id " current-version: " current-version))
        current-doc (when current-version
                      (fsm-load-version conn id current-version))
        new-data (json/read-str (loader))
        new-version (inc (or current-version 0))
        new-doc (if (map? new-data)
                  (assoc new-data "$version" new-version)
                  new-data)]

    (log/info (str "refresh! " id " new-version: " new-version))
    (log/info (str "refresh! " id " docs-equal: " (= (dissoc current-doc "$version") (dissoc new-doc "$version"))))

    (cond
      ;; No current version - store and return
      (nil? current-version)
      (do
        (log/info (str "refreshing: fsm : " id " : version " new-version " (no current)"))
        (fsm-store! conn id new-doc)
        (fsm-load-version conn id new-version))

      ;; Document changed - store new version and return
      (not= (dissoc current-doc "$version")
            (dissoc new-doc "$version"))
      (do
        (log/info (str "refreshing: fsm : " id " : version " new-version " (changed)"))
        (fsm-store! conn id new-doc)
        (fsm-load-version conn id new-version))

      ;; No change - return current
      :else
      (do
        (log/info (str "refreshing: fsm : " id " : version " new-version " (no change)"))
        current-doc))))

(defn fsm-load-audit-trail
  "Load the complete audit trail for an FSM id"
  [conn id]
  (jdbc/execute! conn
                 ["SELECT id, version, document, created_at FROM fsm WHERE id = ? ORDER BY version ASC" id]
                 {:builder-fn rs/as-unqualified-kebab-maps}))

(defn fsm-load-audit-trail-for-ids
  "Load audit trails for multiple FSM ids"
  [conn ids]
  (let [placeholders (clojure.string/join ", " (repeat (count ids) "?"))
        query (str "SELECT id, version, document, created_at FROM fsm WHERE id IN (" placeholders ") ORDER BY id, version ASC")]
    (jdbc/execute! conn (into [query] ids)
                   {:builder-fn rs/as-unqualified-kebab-maps})))

;; Update and swizzle functions for compatibility
(defn fsm-update!
  "Update an FSM document using a transformation function"
  [conn id f]
  (let [current-version (fsm-latest-version conn id)
        current-doc (when current-version
                      (fsm-load-version conn id current-version))
        new-doc (f current-doc)
        new-version (inc (or current-version 0))
        new-doc-with-version (if (map? new-doc)
                               (assoc new-doc "$version" new-version)
                               new-doc)]

    (when (not= current-doc new-doc)
      (fsm-store! conn id new-doc-with-version))

    new-doc-with-version))
