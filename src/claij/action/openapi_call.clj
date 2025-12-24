(ns claij.action.openapi-call
  "Direct OpenAPI call action for FSM states.
   
   Unlike the OpenAPI hat (which provides tools for LLM to choose from),
   this action directly calls a specific operation. Perfect for non-LLM
   states in FSMs like voice processing.
   
   Supports:
   - JSON request/response (standard REST)
   - Binary responses (audio/wav, etc.)
   - Multipart form-data with binary uploads (for file uploads)
   
   Routing:
   This action derives its output event id from the output xition schema,
   the same way llm-action does. For deterministic states with a single
   output xition, the schema constrains the id to a constant value like
   [:= [\"stt\" \"mc\"]], and we extract that constant.
   
   Usage in FSM:
   ```clojure
   {\"id\" \"stt\"
    \"action\" \"openapi-call\"
    \"config\" {:spec-url \"http://prognathodon:8000/openapi.json\"
               :base-url \"http://prognathodon:8000\"
               :operation \"transcribe\"}}
   ```"
  (:require
   [clojure.data.json :as json]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [clj-http.client :as http]
   [claij.action :refer [def-action]]
   [claij.hat.openapi :as openapi]
   [claij.fsm :as fsm]
   [claij.schema :as schema]))

;;------------------------------------------------------------------------------
;; Schema-derived Routing
;;------------------------------------------------------------------------------

(defn- extract-id-from-schema
  "Extract the constrained id value from an output schema.
   
   For deterministic states, the output schema is either:
   - JSON Schema: {\"oneOf\": [{\"properties\": {\"id\": {\"const\": [from, to]}} ...}]}
   - JSON Schema: {\"anyOf\": [...]} (legacy, same structure)
   - Or a single schema without oneOf/anyOf
   
   Returns the id vector, or throws if schema doesn't have exactly one deterministic route."
  [output-schema]
  (let [;; Handle oneOf (preferred), anyOf (legacy), and single schema cases
        alternatives (or (get output-schema "oneOf")
                         (get output-schema "anyOf")
                         [output-schema])

        _ (when (not= 1 (count alternatives))
            (throw (ex-info "Deterministic action requires exactly one output xition"
                            {:alternatives-count (count alternatives)
                             :schema output-schema})))

        ;; Get the single schema
        single-schema (first alternatives)

        ;; Extract id from properties
        id-schema (get-in single-schema ["properties" "id"])

        _ (when-not id-schema
            (throw (ex-info "Schema missing 'id' property" {:schema output-schema})))

        ;; id-schema should have {"const": [from, to]}
        id-value (get id-schema "const")

        _ (when-not id-value
            (throw (ex-info "Expected {\"const\": id} constraint for deterministic routing"
                            {:id-schema id-schema})))]

    id-value))

;;------------------------------------------------------------------------------
;; Spec Cache (memoized per URL)
;;------------------------------------------------------------------------------

(defonce ^:private spec-cache (atom {}))

(defn- get-cached-spec
  "Get spec from cache or fetch and cache it."
  [spec-url]
  (if-let [cached (get @spec-cache spec-url)]
    cached
    (let [spec (openapi/fetch-openapi-spec spec-url)]
      (swap! spec-cache assoc spec-url spec)
      spec)))

(defn clear-spec-cache!
  "Clear the spec cache. Useful for testing or when specs change."
  []
  (reset! spec-cache {}))

(defn- find-operation
  "Find operation by operationId in the extracted operations."
  [operations operation-id]
  (first (filter #(= (:operation-id %) operation-id) operations)))

;;------------------------------------------------------------------------------
;; Content Type Detection from OpenAPI Spec
;;------------------------------------------------------------------------------

(defn- get-operation-from-spec
  "Find operation definition in spec by operationId."
  [spec operation-id]
  (let [paths (get spec "paths")]
    (->> paths
         (mapcat (fn [[_path path-item]]
                   (for [[_method op] path-item
                         :when (and (map? op)
                                    (= (get op "operationId") operation-id))]
                     op)))
         first)))

(defn- get-response-content-type
  "Extract the primary response content type from an OpenAPI operation.
   Returns the first content-type key from the 200 response."
  [spec operation-id]
  (when-let [op (get-operation-from-spec spec operation-id)]
    (-> op
        (get-in ["responses" "200" "content"])
        keys
        first)))

(defn- get-request-content-type
  "Extract the primary request content type from an OpenAPI operation."
  [spec operation-id]
  (when-let [op (get-operation-from-spec spec operation-id)]
    (-> op
        (get-in ["requestBody" "content"])
        keys
        first)))

(defn binary-content-type?
  "Returns true if the content-type indicates binary data."
  [content-type]
  (when content-type
    (or (str/starts-with? content-type "audio/")
        (str/starts-with? content-type "image/")
        (str/starts-with? content-type "video/")
        (= content-type "application/octet-stream"))))

(defn multipart-content-type?
  "Returns true if the content-type indicates multipart form data."
  [content-type]
  (when content-type
    (str/starts-with? content-type "multipart/")))

;;------------------------------------------------------------------------------
;; HTTP Execution with Binary Support
;;------------------------------------------------------------------------------

(defn- build-multipart-body
  "Build multipart body for clj-http from params map.
   Handles both binary (byte arrays) and text values.
   For audio/binary, sets appropriate content-type."
  [params]
  (vec (for [[k v] params]
         (cond
           ;; Byte array - send as binary file with proper content-type
           (bytes? v)
           {:name (name k)
            :content v
            :filename (str (name k) ".wav")
            :content-type "audio/wav"}

           ;; Input stream - send as binary
           (instance? java.io.InputStream v)
           {:name (name k)
            :content v
            :filename (str (name k) ".bin")
            :content-type "application/octet-stream"}

           ;; Everything else - convert to string
           :else
           {:name (name k)
            :content (str v)}))))

(defn- execute-operation-binary
  "Execute an OpenAPI operation with proper content-type handling.
   
   Supports:
   - JSON request/response
   - Binary responses (returns byte array)
   - Multipart form-data requests (for file uploads)"
  [{:keys [base-url auth timeout-ms]} tool params
   {:keys [request-content-type response-content-type]}]
  (let [{:keys [method path path-params query-params]} tool
        ;; Separate path params from others
        path-param-vals (select-keys params (map name path-params))
        query-param-vals (select-keys params (map name query-params))

        ;; Body params = everything except id, path params, query params
        body-params (not-empty (apply dissoc params "id" :id
                                      (concat (map name path-params)
                                              (map name query-params))))

        ;; Explicit body takes precedence, otherwise use remaining params
        body-val (or (get params "body") (get params :body) body-params)

        ;; Determine if we have a body based on content-type
        has-request-body (and request-content-type
                              (or (multipart-content-type? request-content-type)
                                  body-val))

        ;; Build URL
        url (str base-url (openapi/substitute-path-params path path-param-vals))

        ;; Determine response handling
        binary-response? (binary-content-type? response-content-type)

        ;; Build request options
        opts (cond-> {:throw-exceptions false
                      :socket-timeout (or timeout-ms 30000)
                      :connection-timeout (or timeout-ms 30000)}

               ;; Response type
               binary-response?
               (assoc :as :byte-array)

               (not binary-response?)
               (assoc :as :json-string-keys)

               ;; Query params
               (seq query-param-vals)
               (assoc :query-params query-param-vals)

               ;; Multipart request body
               (and has-request-body (multipart-content-type? request-content-type))
               (assoc :multipart (build-multipart-body body-val))

               ;; JSON request body
               (and has-request-body body-val (not (multipart-content-type? request-content-type)))
               (assoc :body (json/write-str body-val)
                      :content-type :json)

               ;; Auth: API key header
               (= (:type auth) :api-key)
               (assoc-in [:headers (:header auth)] (:value auth))

               ;; Auth: Bearer token
               (= (:type auth) :bearer)
               (assoc-in [:headers "Authorization"] (str "Bearer " (:token auth))))]

    (log/debug "Executing OpenAPI call:"
               {:url url
                :method method
                :request-content-type request-content-type
                :response-content-type response-content-type
                :binary-response? binary-response?
                :has-request-body has-request-body
                :body-val body-val
                :multipart? (multipart-content-type? request-content-type)})

    (try
      (let [response (case method
                       :get (http/get url opts)
                       :post (http/post url opts)
                       :put (http/put url opts)
                       :delete (http/delete url opts)
                       :patch (http/patch url opts)
                       (throw (ex-info "Unsupported method" {:method method})))
            body (:body response)]
        {"status" (:status response)
         "body" body
         "content-type" (get-in response [:headers "Content-Type"])})
      (catch Exception e
        (log/error e "OpenAPI call failed")
        {"error" (.getMessage e)}))))

;;------------------------------------------------------------------------------
;; OpenAPI Call Action
;;------------------------------------------------------------------------------

(def-action openapi-call-action
  "Direct OpenAPI call action - calls a specific operation without LLM mediation.
   
   Config:
   - :spec-url - URL to fetch OpenAPI spec (required)
   - :operation - operationId to call (required)
   - :base-url - Base URL for API calls (required)
   - :auth - Auth config {:type :api-key/:bearer, ...} (optional)
   - :timeout-ms - Request timeout in ms (optional, default 30000)
   
   Event input:
   - Parameters for the operation (path, query, body)
   - Or \"params\" key containing parameters
   - Or \"body\" key for request body
   
   Event output:
   - \"id\" - Derived from output xition schema (e.g., [\"stt\" \"mc\"])
   - \"status\" - HTTP status code
   - \"body\" - Response body (parsed JSON or byte array for binary)
   - \"content-type\" - Response content type
   - \"error\" - Error message if failed"
  {:config {"type" "object"
            "required" ["spec-url" "operation" "base-url"]
            "properties"
            {"spec-url" {"type" "string"}
             "operation" {"type" "string"}
             "base-url" {"type" "string"}
             "auth" {}
             "timeout-ms" {"type" "integer"}}}
   :input true
   :output true}
  [config {xs "xitions" :as fsm} _ix {state-id "id" :as state}]

  ;; Factory time: fetch spec and find operation
  (let [{:keys [spec-url operation base-url auth timeout-ms]} config
        spec (get-cached-spec spec-url)
        operations (openapi/extract-operations spec)
        tools (openapi/operations->tools operations)
        tool (find-operation tools operation)

        _ (when-not tool
            (throw (ex-info (str "Operation not found: " operation)
                            {:spec-url spec-url
                             :operation operation
                             :available (mapv :operation-id tools)})))

        ;; Build registry to resolve refs in schemas
        registry (fsm/build-fsm-registry fsm)

        ;; Compute output schema from outgoing xitions (same as llm-action)
        ;; This is THE CONTRACT - we derive routing from the schema
        output-xitions (filter (fn [{[from _to] "id"}] (= from state-id)) xs)
        output-schema-raw (fsm/state-schema {} fsm state output-xitions)

        ;; Expand refs so we can extract the id constraint
        output-schema (schema/expand-refs output-schema-raw registry)

        ;; Extract the constrained id from the schema
        ;; For deterministic states, schema is {"anyOf": [{"properties": {"id": {"const": [from, to]}}}...]}
        output-id (extract-id-from-schema output-schema)

        ;; Get content types from spec
        request-content-type (get-request-content-type spec operation)
        response-content-type (get-response-content-type spec operation)

        exec-config {:base-url base-url
                     :auth auth
                     :timeout-ms (or timeout-ms 30000)}

        content-types {:request-content-type request-content-type
                       :response-content-type response-content-type}]

    (log/info "OpenAPI call action configured:"
              {:state-id state-id
               :operation operation
               :base-url base-url
               :output-id output-id
               :request-content-type request-content-type
               :response-content-type response-content-type})

    ;; Runtime function
    (fn [context event _trail handler]
      (let [;; Get params from event - support multiple conventions
            params (or (get event "params")
                       (dissoc event "id"))

            _ (log/debug "OpenAPI call params:" {:operation operation :params-keys (keys params)})

            ;; Execute the HTTP call
            result (execute-operation-binary exec-config tool params content-types)

            _ (log/info "OpenAPI call result:"
                        {:operation operation
                         :status (get result "status")
                         :has-body (some? (get result "body"))
                         :body-type (type (get result "body"))
                         :has-error (contains? result "error")})]

        ;; Call handler with result - id derived from output schema
        (handler context
                 (merge {"id" output-id}
                        result))))))

;;------------------------------------------------------------------------------
;; Registration Helper
;;------------------------------------------------------------------------------

(def openapi-call-actions
  "Action map for registration with context."
  {"openapi-call" #'openapi-call-action})
