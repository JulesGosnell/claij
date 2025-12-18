(ns poc.openapi.server
  "Minimal OpenAPI test server for hat integration testing.
   
   Endpoints:
   - GET  /items       - list all items
   - GET  /items/:id   - get item by id
   - POST /items       - create item
   - GET  /openapi.json - OpenAPI 3.0 spec"
  (:require
   [clojure.data.json :as json]
   [clojure.string :as str]
   [ring.adapter.jetty :as jetty])
  (:import
   [java.io BufferedReader]))

;; In-memory store
(defonce items (atom {"1" {"id" "1" "name" "First Item" "status" "active"}
                      "2" {"id" "2" "name" "Second Item" "status" "pending"}}))

(defonce id-counter (atom 2))

;; OpenAPI 3.0 Spec
(def openapi-spec
  {"openapi" "3.0.0"
   "info" {"title" "Items API"
           "description" "Minimal test API for OpenAPI hat integration"
           "version" "1.0.0"}
   "servers" [{"url" "http://localhost:8765"}]
   "paths"
   {"/items"
    {"get"
     {"operationId" "listItems"
      "summary" "List all items"
      "responses"
      {"200" {"description" "List of items"
              "content"
              {"application/json"
               {"schema"
                {"type" "object"
                 "properties"
                 {"items" {"type" "array"
                           "items" {"$ref" "#/components/schemas/Item"}}}}}}}}}
     "post"
     {"operationId" "createItem"
      "summary" "Create a new item"
      "requestBody"
      {"required" true
       "content"
       {"application/json"
        {"schema"
         {"type" "object"
          "required" ["name"]
          "properties"
          {"name" {"type" "string"}
           "status" {"type" "string" "default" "pending"}}}}}}
      "responses"
      {"201" {"description" "Item created"
              "content"
              {"application/json"
               {"schema" {"$ref" "#/components/schemas/Item"}}}}}}}

    "/items/{id}"
    {"get"
     {"operationId" "getItemById"
      "summary" "Get item by ID"
      "parameters"
      [{"name" "id"
        "in" "path"
        "required" true
        "schema" {"type" "string"}}]
      "responses"
      {"200" {"description" "Item found"
              "content"
              {"application/json"
               {"schema" {"$ref" "#/components/schemas/Item"}}}}
       "404" {"description" "Item not found"}}}}}

   "components"
   {"schemas"
    {"Item"
     {"type" "object"
      "properties"
      {"id" {"type" "string"}
       "name" {"type" "string"}
       "status" {"type" "string"
                 "enum" ["active" "pending" "completed"]}}}}}})

;; Handlers
(defn json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/write-str body)})

(defn list-items [_request]
  (json-response 200 {"items" (vals @items)}))

(defn get-item [request]
  (let [id (get-in request [:path-params :id])]
    (if-let [item (get @items id)]
      (json-response 200 item)
      (json-response 404 {"error" "Item not found"}))))

(defn read-body [request]
  (when-let [body (:body request)]
    (cond
      (string? body) body
      (instance? BufferedReader body) (slurp body)
      (instance? java.io.InputStream body) (slurp body)
      :else (str body))))

(defn create-item [request]
  (let [body-str (read-body request)
        body (when body-str (json/read-str body-str))
        id (str (swap! id-counter inc))
        item {"id" id
              "name" (get body "name")
              "status" (get body "status" "pending")}]
    (swap! items assoc id item)
    (json-response 201 item)))

(defn get-openapi-spec [_request]
  (json-response 200 openapi-spec))

;; Simple router
(defn parse-path [uri]
  (let [parts (filterv (complement str/blank?) (str/split uri #"/"))]
    (cond
      (= parts ["openapi.json"]) {:handler :openapi}
      (= parts ["items"]) {:handler :items-list}
      (and (= (first parts) "items") (second parts))
      {:handler :item-by-id :path-params {:id (second parts)}}
      :else {:handler :not-found})))

(defn handler [request]
  (let [{:keys [handler path-params]} (parse-path (:uri request))
        request (assoc request :path-params path-params)]
    (case [(:request-method request) handler]
      [:get :openapi] (get-openapi-spec request)
      [:get :items-list] (list-items request)
      [:post :items-list] (create-item request)
      [:get :item-by-id] (get-item request)
      (json-response 404 {"error" "Not found"}))))

;; Server management
(defonce server (atom nil))

(declare stop-server)

(defn start-server
  "Start the test server on the given port (default 8765)"
  ([] (start-server 8765))
  ([port]
   (when @server
     (stop-server))
   (reset! server (jetty/run-jetty handler {:port port :join? false}))
   (println (str "OpenAPI test server started on http://localhost:" port))
   (println "  GET  /openapi.json - OpenAPI spec")
   (println "  GET  /items        - List items")
   (println "  GET  /items/:id    - Get item")
   (println "  POST /items        - Create item")
   port))

(defn stop-server
  "Stop the test server"
  []
  (when-let [s @server]
    (.stop s)
    (reset! server nil)
    (println "Server stopped")))

(defn reset-data!
  "Reset test data to initial state"
  []
  (reset! items {"1" {"id" "1" "name" "First Item" "status" "active"}
                 "2" {"id" "2" "name" "Second Item" "status" "pending"}})
  (reset! id-counter 2))

(comment
  ;; REPL usage
  (start-server)
  (stop-server)
  (reset-data!)

  ;; Test with curl:
  ;; curl http://localhost:8765/openapi.json
  ;; curl http://localhost:8765/items
  ;; curl http://localhost:8765/items/1
  ;; curl -X POST http://localhost:8765/items -d '{"name":"New"}' -H "Content-Type: application/json"
  )
