(ns claij.hat.openapi
  "OpenAPI Hat - enables FSM states to call OpenAPI-described services.
   
   Usage:
   ```clojure
   {\"id\" \"worker\"
    \"action\" \"llm\"
    \"hats\" [{\"openapi\" {:spec-url \"http://localhost:8765/openapi.json\"}}]}
   ```
   
   The hat:
   1. Fetches the OpenAPI spec (memoized per FSM lifetime)
   2. Extracts operations with their JSON schemas AS-IS (no translation)
   3. Registers schema functions that embed raw JSON schemas in Malli [:json-schema ...]
   4. The FSM machinery presents schemas to LLM via tuple-3 protocol
   5. Adds a service state for executing HTTP calls"
  (:require
   [clojure.data.json :as json]
   [clojure.string :as str]
   [clj-http.client :as http]
   [claij.hat :as hat]))

;;; ============================================================
;;; OpenAPI Spec Fetching & Parsing
;;; ============================================================

(defn fetch-openapi-spec
  "Fetch and parse an OpenAPI spec from a URL.
   Returns the parsed spec as a map."
  [spec-url]
  (let [response (http/get spec-url {:as :json-string-keys
                                     :throw-exceptions true})]
    (:body response)))

(defn resolve-ref
  "Resolve a $ref in the spec. Returns the referenced schema."
  [spec ref]
  (when ref
    (let [path (-> ref
                   (str/replace "#/" "")
                   (str/split #"/"))]
      (get-in spec path))))

(defn resolve-schema
  "Resolve a schema, following $ref if present."
  [spec schema]
  (if-let [ref (get schema "$ref")]
    (resolve-ref spec ref)
    schema))

(defn get-request-schema
  "Extract the request body JSON schema from an operation.
   Returns the raw JSON schema or nil."
  [spec operation]
  (when-let [schema (get-in operation ["requestBody" "content" "application/json" "schema"])]
    (resolve-schema spec schema)))

(defn get-response-schema
  "Extract the success response JSON schema from an operation.
   Returns the raw JSON schema or nil."
  [spec operation]
  (let [responses (get operation "responses" {})
        ;; Try 200, 201, then first 2xx
        success-key (or (when (contains? responses "200") "200")
                        (when (contains? responses "201") "201")
                        (first (filter #(str/starts-with? % "2") (keys responses))))]
    (when-let [schema (get-in responses [success-key "content" "application/json" "schema"])]
      (resolve-schema spec schema))))

(defn extract-operations
  "Extract operations from an OpenAPI spec.
   Returns a vector of operation maps with raw JSON schemas embedded."
  [spec]
  (let [paths (get spec "paths" {})]
    (vec
     (for [[path methods] paths
           [method operation] methods
           :when (and (map? operation) (get operation "operationId"))]
       {:operation-id (get operation "operationId")
        :method (keyword method)
        :path path
        :summary (get operation "summary" "")
        :description (get operation "description" "")
        :parameters (get operation "parameters" [])
        ;; Embed raw JSON schemas - NO TRANSLATION
        :request-schema (get-request-schema spec operation)
        :response-schema (get-response-schema spec operation)}))))

(defn operation->tool
  "Convert an OpenAPI operation to internal tool format.
   Preserves raw JSON schemas."
  [{:keys [parameters] :as operation}]
  (let [path-params (filterv #(= "path" (get % "in")) parameters)
        query-params (filterv #(= "query" (get % "in")) parameters)]
    (assoc operation
           :path-params (mapv #(get % "name") path-params)
           :query-params (mapv #(get % "name") query-params)
           :has-body (some? (:request-schema operation)))))

(defn operations->tools
  "Convert all operations to tools."
  [operations]
  (mapv operation->tool operations))

;;; ============================================================
;;; Schema Functions - Embed raw JSON schemas via [:json-schema ...]
;;; ============================================================

(defn tool->call-schema
  "Build Malli schema for a single tool call.
   Embeds the raw JSON schema for params."
  [{:keys [operation-id request-schema path-params query-params]}]
  (let [;; Build params schema from path/query params + body
        path-props (into {} (for [p path-params] [p {"type" "string"}]))
        query-props (into {} (for [q query-params] [q {"type" "string"}]))
        body-prop (when request-schema {"body" request-schema})
        all-props (merge path-props query-props body-prop)
        params-schema (if (seq all-props)
                        {"type" "object"
                         "required" (vec path-params)
                         "properties" all-props}
                        {})]
    {"type" "object"
     "additionalProperties" false
     "required" ["operation"]
     "properties" {"operation" {"const" operation-id}
                   "params" params-schema}}))

(defn tools->request-schema
  "Build JSON Schema for OpenAPI request (multiple tool calls).
   Returns schema for {\"calls\": [{\"operation\": \"...\", \"params\": {...}}, ...]}"
  [tools]
  (let [call-schemas (mapv tool->call-schema tools)]
    {"type" "object"
     "additionalProperties" false
     "required" ["calls"]
     "properties" {"calls" {"type" "array"
                            "items" (if (seq call-schemas)
                                      {"oneOf" call-schemas}
                                      {})}}}))

(defn tools->response-schema
  "Build JSON Schema for OpenAPI response.
   Results contain HTTP status and body (raw JSON, no schema translation)."
  [_tools]
  {"type" "object"
   "additionalProperties" false
   "required" ["results"]
   "properties" {"results" {"type" "array"
                            "items" {"oneOf"
                                     [{"type" "object"
                                       "required" ["status"]
                                       "properties" {"status" {"type" "integer"}
                                                     "body" {}}}
                                      {"type" "object"
                                       "required" ["error"]
                                       "properties" {"error" {"type" "string"}}}]}}}})

(defn openapi-request-schema-fn
  "Schema function for OpenAPI requests.
   Looks up tools from context and builds dynamic schema."
  [context {xid "id" :as _xition}]
  (let [[state-id _service-id] xid
        tools (get-in context [:hats :openapi state-id :tools] [])]
    [:map {:closed true}
     ["id" [:= xid]]
     ["calls" [:vector
               (if (seq tools)
                 (into [:or] (mapv tool->call-schema tools))
                 :any)]]]))

(defn openapi-response-schema-fn
  "Schema function for OpenAPI responses."
  [_context {xid "id" :as _xition}]
  [:map {:closed true}
   ["id" [:= xid]]
   ["results" [:vector
               [:or
                [:map
                 ["status" :int]
                 ["body" :any]]
                [:map
                 ["error" :string]]]]]])

;;; ============================================================
;;; Prompt Generation - Tool documentation for LLM
;;; ============================================================

(defn format-tool-for-prompt
  "Format a single tool for prompt documentation.
   Schema details come through xitions, not prompts."
  [{:keys [operation-id method path summary description]}]
  (str "- " operation-id " (" (str/upper-case (name method)) " " path ")"
       (when (seq summary) (str " - " summary))
       (when (seq description) (str "\n  " description))))

(defn generate-tools-prompt
  "Generate prompt text with schema definitions for OpenAPI operations.
   
   Schema definitions are stated once here in the prompt.
   Xition schemas reference them via [:json-schema ...] for efficiency."
  [state-id service-id tools spec]
  (if (seq tools)
    (let [;; Include component schemas from spec (definitions used by $ref)
          components (get-in spec ["components" "schemas"] {})
          schema-defs (when (seq components)
                        (str "## Schema Definitions\n\n"
                             "```json\n"
                             (json/write-str components)
                             "\n```\n\n"))
          ;; List operations with their schemas inline
          ops-text (str/join "\n\n"
                             (for [{:keys [operation-id method path summary
                                           request-schema response-schema]} tools]
                               (str "### " operation-id "\n"
                                    (str/upper-case (name method)) " " path
                                    (when summary (str " - " summary))
                                    (when request-schema
                                      (str "\n**Request:** `" (json/write-str request-schema) "`"))
                                    (when response-schema
                                      (str "\n**Response:** `" (json/write-str response-schema) "`")))))]
      (str (or schema-defs "")
           "## OpenAPI Operations\n\n"
           ops-text
           "\n\n"
           "To call, use id: [\"" state-id "\", \"" service-id "\"]"))
    "No OpenAPI tools available."))

;;; ============================================================
;;; HTTP Request Execution
;;; ============================================================

(defn substitute-path-params
  "Substitute path parameters into a URL path.
   e.g., /items/{id} with {\"id\" \"123\"} -> /items/123"
  [path params]
  (reduce (fn [p [k v]]
            (str/replace p (str "{" k "}") (str v)))
          path
          params))

(defn execute-operation
  "Execute a single OpenAPI operation via HTTP.
   Returns {:status <int> :body <parsed-json>} or {:error <string>}"
  [{:keys [base-url auth timeout-ms]} tool call]
  (let [{:keys [method path path-params query-params has-body]} tool
        params (get call "params" {})
        ;; Separate path params from others
        path-param-vals (select-keys params path-params)
        query-param-vals (select-keys params query-params)
        body-val (get params "body")
        ;; Build URL
        url (str base-url (substitute-path-params path path-param-vals))
        ;; Build request options
        opts (cond-> {:as :json-string-keys
                      :throw-exceptions false
                      :socket-timeout (or timeout-ms 30000)
                      :connection-timeout (or timeout-ms 30000)}
               (seq query-param-vals) (assoc :query-params query-param-vals)
               (and has-body body-val) (assoc :body (json/write-str body-val)
                                              :content-type :json)
               ;; Auth handling
               (= (:type auth) :api-key)
               (assoc-in [:headers (:header auth)] (:value auth))

               (= (:type auth) :bearer)
               (assoc-in [:headers "Authorization"] (str "Bearer " (:token auth))))]
    (try
      (let [response (case method
                       :get (http/get url opts)
                       :post (http/post url opts)
                       :put (http/put url opts)
                       :delete (http/delete url opts)
                       :patch (http/patch url opts)
                       (throw (ex-info "Unsupported method" {:method method})))]
        {"status" (:status response)
         "body" (:body response)})
      (catch Exception e
        {"error" (.getMessage e)}))))

(defn execute-calls
  "Execute multiple OpenAPI calls.
   Returns a vector of results."
  [config tools calls]
  (let [tool-by-id (into {} (map (juxt :operation-id identity) tools))]
    (mapv (fn [call]
            (let [op-id (get call "operation")
                  tool (get tool-by-id op-id)]
              (if tool
                (execute-operation config tool call)
                {"error" (str "Unknown operation: " op-id)})))
          calls)))

;;; ============================================================
;;; OpenAPI Service Action
;;; ============================================================

(defn openapi-service-action
  "Action that executes OpenAPI calls from an LLM.
   
   Simple function - not using def-action macro.
   Takes [config fsm ix state] and returns handler fn."
  [_config _fsm _ix {state-id "id" :as _state}]
  (fn [context event _trail handler]
    (let [[from _to] (get event "id")
          base-state-id (str/replace from #"-openapi$" "")
          {:keys [config tools]} (get-in context [:hats :openapi base-state-id])
          calls (get event "calls" [])
          results (execute-calls config tools calls)]
      (handler context
               {"id" [state-id base-state-id]
                "results" results}))))

;;; ============================================================
;;; Hat Maker
;;; ============================================================

(defn openapi-hat-maker
  "Create an OpenAPI hat maker for a given state.
   
   Config options:
   - :spec-url - URL to fetch OpenAPI spec (required)
   - :base-url - Base URL for API calls (defaults to first server in spec)
   - :auth - Auth config {:type :api-key/:bearer, ...}
   - :timeout-ms - Request timeout (default 30000)"
  [state-id config]
  (let [service-id (str state-id "-openapi")
        request-schema-id (str state-id "-openapi-request")
        response-schema-id (str state-id "-openapi-response")]
    (fn [context]
      (let [{:keys [spec-url base-url auth timeout-ms]} config
            ;; Fetch and parse spec
            spec (fetch-openapi-spec spec-url)
            ;; Extract base URL from spec if not provided
            base-url (or base-url
                         (get-in spec ["servers" 0 "url"])
                         (throw (ex-info "No base-url provided and none in spec"
                                         {:spec-url spec-url})))
            ;; Parse operations → tools
            operations (extract-operations spec)
            tools (operations->tools operations)
            ;; Generate prompts
            tools-prompt (generate-tools-prompt state-id service-id tools spec)
            ;; Update context with tools, config, schema functions, action
            context' (-> context
                         (assoc-in [:hats :openapi state-id]
                                   {:config {:base-url base-url
                                             :auth auth
                                             :timeout-ms timeout-ms}
                                    :tools tools
                                    :spec spec})
                         (update :id->schema merge
                                 {request-schema-id openapi-request-schema-fn
                                  response-schema-id openapi-response-schema-fn})
                         (update :id->action assoc "openapi-service" openapi-service-action))]
        [context'
         {"states" [{"id" service-id
                     "action" "openapi-service"}]
          "xitions" [{"id" [state-id service-id]
                      "schema" request-schema-id}
                     {"id" [service-id state-id]
                      "schema" response-schema-id}]
          "prompts" [tools-prompt]}]))))

;;; ============================================================
;;; Registration
;;; ============================================================

(defn register-openapi-hat
  "Register the OpenAPI hat in a hat registry."
  [registry]
  (hat/register-hat registry "openapi" openapi-hat-maker))
