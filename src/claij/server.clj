(ns claij.server
  (:require
   [clojure.tools.cli :refer [cli]]
   [clojure.tools.logging :as log]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]

   ;; Ring
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.util.response :as resp]
   [ring.middleware.multipart-params :refer [wrap-multipart-params]]
   [ring.middleware.resource :refer [wrap-resource]]

   ;; Reitit
   [reitit.ring :as ring]
   [reitit.swagger :as swagger]
   [reitit.swagger-ui :as swagger-ui]
   [reitit.ring.coercion :as coercion]
   [reitit.coercion.malli :as malli-coercion]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [muuntaja.core :as m]

   ;; HTTP client
   [clj-http.client :refer [post]]

   ;; HTML generation
   [hiccup.core :refer [html]]
   [hiccup.page :refer [html5]]

   ;; Internal
   [claij.util :refer [assert-env-var clj->json json->clj]]
   [claij.graph :as graph]
   [claij.hat :as hat]
   [claij.fsm :as fsm]
   [claij.fsm.registry :as registry]
   [claij.fsm.code-review-fsm :refer [code-review-fsm]]
   [claij.fsm.bdd-fsm :as bdd]
   [claij.stt.whisper.multipart :refer [extract-bytes validate-audio]])
  (:import
   [java.net URL])
  (:gen-class))

;;------------------------------------------------------------------------------
;; LLM Backend

(defn api-key []
  (assert-env-var "OPENROUTER_API_KEY"))

(def api-base "https://openrouter.ai")
(def api-url (str api-base "/api/v1"))

(defn headers []
  {"Authorization" (str "Bearer " (api-key))
   "content-type" "application/json"})

(def separator "041083c4-7bb7-4cb7-884b-3dc4b2bd0413")
(def pattern (re-pattern separator))

(def initial-summary "This is the beginning of our conversation")

(defn open-router [id provider model summary message]
  (let [old-summary @summary
        answer
        (post
         (str api-url "/chat/completions")
         {:headers (headers)
          :body
          (clj->json
           {:model (str provider "/" model)
            :messages
            [{:role "system" :content (str "You're name is:" id)}
             {:role "user" :content (str "Here is a summary of your conversational state: " old-summary)}
             {:role "user" :content (str "Here is a question for you to answer: " message)}
             {:role "user" :content (str "Please append this separator to your response: '" separator "'.")}
             {:role "user" :content "Please merge your summary and answer tersely into a fresh summary and append to your response after the separator. It will be passed back to you in the next request."}]})
          :throw-exceptions false})]
    (str/join
     ","
     (map
      (comp
       (fn [[answer new-summary]] (compare-and-set! summary old-summary (str/trim new-summary)) (str/trim answer))
       (fn [s] (str/split s pattern))
       :content
       :message)
      (:choices
       (json->clj (:body answer)))))))

(def state (atom initial-summary))

(def llms
  {"grok" (partial open-router "grok" "x-ai" "grok-code-fast-1" state)
   "gpt" (partial open-router "gpt" "openai" "gpt-5.2-chat" state)
   "claude" (partial open-router "claude" "anthropic" "claude-sonnet-4.5" state)
   "gemini" (partial open-router "gemini" "google" "gemini-3-pro-preview" state)})

;;------------------------------------------------------------------------------
;; FSM Registry

;; Initialize FSM registry with built-in FSMs
;; The registry maintains FSMs and auto-generates OpenAPI specs
(defonce _init-registry
  (do
    (registry/register-fsm! "code-review-fsm" code-review-fsm)
    (registry/register-fsm! "bdd" bdd/bdd-fsm)
    :initialized))

;; Backwards-compatible accessor (returns map of id -> definition)
(defn fsms []
  (into {}
        (for [[id {:keys [definition]}] @registry/fsm-registry]
          [id definition])))

;;------------------------------------------------------------------------------
;; Handlers

(defn dot->svg [dot-str]
  (let [{:keys [out err exit]} (sh "dot" "-Tsvg" :in dot-str)]
    (if (zero? exit)
      out
      (throw (ex-info "GraphViz rendering failed" {:stderr err :exit exit})))))

(defn health-handler [_]
  {:status 200
   :headers {"content-type" "text/plain"}
   :body "ok v0.1.2"})

(defn list-fsms-handler [_]
  {:status 200
   :body (vec (keys (fsms)))})

(defn fsms-html-handler
  "Return HTML page listing all FSMs"
  [_]
  (let [all-fsms (fsms)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (html5
            [:head
             [:meta {:charset "UTF-8"}]
             [:title "CLAIJ FSM Catalogue"]
             [:style "body { font-family: system-ui; max-width: 900px; margin: 2em auto; padding: 1em; }
                    table { border-collapse: collapse; width: 100%; }
                    th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                    th { background: #f9f9f9; }
                    tr:hover { background: #f5f5f5; }
                    a { color: #0066cc; }
                    h1 { margin-bottom: 0.5em; }
                    .subtitle { color: #666; margin-bottom: 1.5em; }
                    .count { color: #666; font-size: 0.9em; }
                    .links { white-space: nowrap; }"]]
            [:body
             [:h1 "FSM Catalogue"]
             [:p.subtitle (str (count all-fsms) " finite state machines")]
             [:table
              [:thead [:tr [:th "ID"] [:th "States"] [:th "Transitions"] [:th "Schemas"] [:th "Links"]]]
              [:tbody
               (for [id (sort (keys all-fsms))
                     :let [fsm (get all-fsms id)
                           states (get fsm "states")
                           xitions (get fsm "xitions")
                           schemas (get fsm "schemas")]]
                 [:tr
                  [:td [:a {:href (str "/fsm/" id)} id]]
                  [:td.count (count states)]
                  [:td.count (count xitions)]
                  [:td.count (count schemas)]
                  [:td.links
                   [:a {:href (str "/fsm/" id "/graph.svg")} "SVG"]
                   " | "
                   [:a {:href (str "/fsm/" id "/document")} "JSON"]]])]]])}))

(defn fsm-html-handler
  "Return HTML page showing single FSM definition - sectioned view"
  [{{:keys [fsm-id]} :path-params}]
  (if-let [fsm (get (fsms) fsm-id)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (html5
            [:head
             [:meta {:charset "UTF-8"}]
             [:title (str "FSM: " fsm-id)]
             [:style "body { font-family: system-ui; max-width: 1200px; margin: 2em auto; padding: 1em; }
                      pre { background: #f5f5f5; padding: 1em; overflow-x: auto; border-radius: 4px; 
                            max-height: 300px; overflow-y: auto; font-size: 0.85em; margin: 0; }
                      h2 { margin-top: 1.5em; border-bottom: 1px solid #ddd; padding-bottom: 0.3em; }
                      a { color: #0066cc; }
                      .nav { margin-bottom: 1em; }
                      .section { margin-bottom: 2em; }
                      table { border-collapse: collapse; width: 100%; }
                      th, td { border: 1px solid #ddd; padding: 8px; text-align: left; vertical-align: top; }
                      th { background: #f9f9f9; }
                      tr:hover { background: #f5f5f5; }
                      tr:target { background: #fffde7; }
                      .badge { display: inline-block; padding: 2px 6px; border-radius: 3px; 
                               font-size: 0.85em; margin-right: 4px; }
                      .badge-action { background: #e3f2fd; color: #1565c0; }
                      .badge-hat { background: #f3e5f5; color: #7b1fa2; }
                      .arrow { color: #666; }
                      .schema-name { font-weight: bold; font-family: monospace; }
                      .schema-desc { color: #666; font-style: italic; }
                      .schema-link { text-decoration: none; }
                      .schema-link:hover { text-decoration: underline; }
                      code { background: #f5f5f5; padding: 2px 4px; border-radius: 3px; font-size: 0.9em; }
                      ol { line-height: 1.6; }
                      details { margin-top: 0.5em; }
                      details summary { cursor: pointer; color: #0066cc; font-size: 0.85em; }
                      details summary:hover { text-decoration: underline; }
                      details[open] summary { margin-bottom: 0.5em; }
                      .graph-container { background: #fafafa; border: 1px solid #ddd; border-radius: 4px;
                                         padding: 1em; overflow-x: auto; }
                      .graph-container object { display: block; margin: 0 auto; max-width: 100%; }"]]
            [:body
             [:div.nav
              [:a {:href "/fsms"} "\u2190 Back to Catalogue"]
              " | "
              [:a {:href (str "/fsm/" fsm-id "/graph.svg")} "SVG Graph"]
              " | "
              [:a {:href (str "/fsm/" fsm-id "/document")} "JSON"]]
             [:h1 fsm-id]

             ;; Graph section - inline SVG
             [:div.section
              [:h2 "Graph"]
              [:div.graph-container
               [:object {:type "image/svg+xml"
                         :data (str "/fsm/" fsm-id "/graph.svg?hats=true")
                         :style "min-height: 150px; max-height: 500px;"}
                "Your browser does not support SVG"]]]

             ;; Prompts section
             (when-let [prompts (get fsm "prompts")]
               [:div.section
                [:h2 (str "Prompts (" (count prompts) ")")]
                [:ol
                 (for [prompt prompts]
                   [:li prompt])]])

             ;; Schemas section - as table with expandable details
             (when-let [schemas (get fsm "schemas")]
               [:div.section
                [:h2 (str "Schemas (" (count schemas) ")")]
                [:table
                 [:thead
                  [:tr [:th "Name"] [:th "Type"] [:th "Description"]]]
                 [:tbody
                  (for [[schema-name schema-def] schemas]
                    (let [schema-type (when (vector? schema-def) (first schema-def))
                          schema-props (when (and (vector? schema-def)
                                                  (> (count schema-def) 1)
                                                  (map? (second schema-def)))
                                         (second schema-def))
                          description (get schema-props :description)]
                      [:tr {:id (str "schema-" schema-name)}
                       [:td
                        [:span.schema-name schema-name]
                        [:details
                         [:summary "show definition"]
                         [:pre (with-out-str (clojure.pprint/pprint schema-def))]]]
                       [:td [:code (str schema-type)]]
                       [:td (or description [:span.schema-desc "—"])]]))]]])

             ;; States section - as table
             (when-let [states (get fsm "states")]
               [:div.section
                [:h2 (str "States (" (count states) ")")]
                [:table
                 [:thead
                  [:tr [:th "ID"] [:th "Description"] [:th "Action"] [:th "Hats"]]]
                 [:tbody
                  (for [state states]
                    [:tr
                     [:td [:strong (get state "id")]]
                     [:td (get state "description" "—")]
                     [:td [:span.badge.badge-action (get state "action" "—")]]
                     [:td (if-let [hats (get state "hats")]
                            (for [hat hats]
                              [:span.badge.badge-hat
                               (if (map? hat)
                                 (first (keys hat))
                                 (str hat))])
                            "—")]])]]])

             ;; Transitions section - as table
             (when-let [xitions (get fsm "xitions")]
               [:div.section
                [:h2 (str "Transitions (" (count xitions) ")")]
                [:table
                 [:thead
                  [:tr [:th "From \u2192 To"] [:th "Label"] [:th "Schema"]]]
                 [:tbody
                  (for [xition xitions]
                    (let [[from to] (get xition "id")]
                      [:tr
                       [:td [:strong from] [:span.arrow " \u2192 "] [:strong to]]
                       [:td (get xition "label" "—")]
                       [:td (when-let [schema (get xition "schema")]
                              (when (and (vector? schema)
                                         (= :ref (first schema)))
                                (let [ref-name (second schema)]
                                  [:a.schema-link {:href (str "#schema-" ref-name)}
                                   [:code ref-name]])))]]))]]])])}
    {:status 404
     :headers {"Content-Type" "text/html"}
     :body (html5
            [:head [:title "FSM Not Found"]]
            [:body
             [:h1 "FSM Not Found"]
             [:p (str "No FSM with id: " fsm-id)]
             [:a {:href "/fsms"} "\u2190 Back to Catalogue"]])}))

(defn fsm-document-handler [{{:keys [fsm-id]} :path-params}]
  (if-let [fsm (get (fsms) fsm-id)]
    {:status 200
     :body fsm}
    {:status 404
     :body {:error (str "FSM not found: " fsm-id)}}))

(defn fsm-graph-svg-handler [{{:keys [fsm-id]} :path-params
                              {:strs [hats]} :query-params}]
  (if-let [fsm (get (fsms) fsm-id)]
    (let [dot-str (if hats
                    ;; Expand hats for visualization (no MCP connection needed)
                    (graph/fsm->dot-with-hats fsm)
                    (graph/fsm->dot fsm))]
      {:status 200
       :headers {"content-type" "image/svg+xml"
                 "cache-control" "no-cache, no-store, must-revalidate"}
       :body (dot->svg dot-str)})
    {:status 404
     :body {:error (str "FSM not found: " fsm-id)}}))

(defn fsm-graph-dot-handler [{{:keys [fsm-id]} :path-params
                              {:strs [hats]} :query-params}]
  (if-let [fsm (get (fsms) fsm-id)]
    (let [dot-str (if hats
                    (let [ctx (case fsm-id
                                "bdd" (bdd/make-bdd-context {})
                                {:hats {:registry (hat/make-hat-registry)}})
                          registry (get-in ctx [:hats :registry])]
                      (graph/fsm->dot-with-hats fsm registry ctx))
                    (graph/fsm->dot fsm))]
      {:status 200
       :headers {"content-type" "text/vnd.graphviz"}
       :body dot-str})
    {:status 404
     :body {:error (str "FSM not found: " fsm-id)}}))

(defn llm-handler [{{:keys [provider]} :path-params :keys [body-params]}]
  (if-let [llm-fn (get llms provider)]
    (let [input (or (:message body-params) (str body-params))
          output (llm-fn input)]
      (log/info (str provider "->: " input))
      (log/info (str provider "<-: " output))
      {:status 200
       :body {:response output}})
    {:status 404
     :body {:error (str "LLM not found: " provider)}}))

(defn claij-api-key []
  (System/getenv "CLAIJ_API_KEY"))

(defn wrap-auth
  "Middleware that checks for valid Bearer token.
   Returns 401 if token missing or invalid."
  [handler]
  (fn [request]
    (let [auth-header (get-in request [:headers "authorization"])
          token (when auth-header
                  (second (re-matches #"Bearer\s+(.*)" auth-header)))
          expected (claij-api-key)]
      (cond
        ;; No API key configured - allow all (dev mode)
        (str/blank? expected)
        (handler request)

        ;; Valid token
        (= token expected)
        (handler request)

        ;; Missing or invalid token
        :else
        {:status 401
         :headers {"WWW-Authenticate" "Bearer realm=\"claij\""
                   "content-type" "application/json"}
         :body {:error "Unauthorized"
                :message "Valid Bearer token required"}}))))

;;------------------------------------------------------------------------------
;; Voice Handler (BDD FSM)

(defn voice-handler
  "Handler for POST /voice endpoint.
   Accepts multipart audio, runs BDD FSM (STT → LLM → TTS), returns audio.
   
   Request: multipart/form-data with 'audio' field (WAV bytes)
   Response: audio/wav bytes"
  [request]
  (try
    (let [;; Extract audio from multipart
          audio-part (get-in request [:multipart-params "audio"])
          _ (when-not audio-part
              (throw (ex-info "Missing 'audio' field in multipart form" {})))
          audio-bytes (extract-bytes audio-part)
          _ (validate-audio audio-bytes)

          ;; Create FSM context
          context (bdd/make-bdd-context {})

          ;; Run the BDD FSM synchronously
          ;; Input: {"id" ["start" "stt"], "audio" <bytes>}
          ;; Expected output: {"id" ["tts" "end"], "status" 200, "body" <audio-bytes>}
          input {"id" ["start" "stt"]
                 "audio" audio-bytes}

          _ (log/info "Running BDD FSM with" (count audio-bytes) "bytes of audio")
          result (fsm/run-sync bdd/bdd-fsm context input 120000)] ;; 2 min timeout

      (if (= result :timeout)
        {:status 504
         :headers {"Content-Type" "application/json"}
         :body (clj->json {:error "FSM timeout"})}

        (let [[_final-context trail] result
              ;; Get the last event (TTS output) using fsm/last-event helper
              final-event (fsm/last-event trail)
              response-audio (get final-event "body")]

          (if (bytes? response-audio)
            {:status 200
             :headers {"Content-Type" "audio/wav"}
             :body (java.io.ByteArrayInputStream. response-audio)}
            {:status 500
             :headers {"Content-Type" "application/json"}
             :body (clj->json {:error "No audio in FSM response"
                               :last-event (-> final-event
                                               (dissoc "body" "audio")
                                               (update "body" (fn [b] (when b (str (type b))))))})}))))

    (catch Exception e
      (log/error e "Voice handler error")
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (clj->json {:error (.getMessage e)})})))

;;------------------------------------------------------------------------------
;; Routes

(def routes
  [["/swagger.json"
    {:get {:no-doc true
           :swagger {:info {:title "claij API"
                            :description "Clojure AI Integration Junction"
                            :version "0.1.2"}
                     :securityDefinitions {:bearer {:type "apiKey"
                                                    :name "Authorization"
                                                    :in "header"
                                                    :description "Bearer token (format: 'Bearer <token>')"}}}
           :handler (swagger/create-swagger-handler)}}]

   ;; Public endpoints
   ["/health"
    {:get {:summary "Health check"
           :responses {200 {:body string?}}
           :handler health-handler}}]

   ;; Certificate download for iOS
   ["/install-cert"
    {:get {:no-doc true
           :handler (fn [_]
                      {:status 200
                       :headers {"Content-Type" "text/html"}
                       :body "<!DOCTYPE html>
<html><head><title>Install CLAIJ Certificate</title>
<meta name='viewport' content='width=device-width, initial-scale=1'>
<style>body{font-family:system-ui;max-width:600px;margin:2em auto;padding:1em;background:#1a1a2e;color:#eee}
a{color:#00ff88;font-size:1.2em}ol{line-height:2}</style></head>
<body>
<h1>📱 Install CLAIJ Certificate</h1>
<p><a href='/claij.crt'>⬇️ Download Certificate</a></p>
<h2>iOS Instructions:</h2>
<ol>
<li>Tap the download link above</li>
<li>Tap 'Allow' when prompted</li>
<li>Go to <b>Settings → General → VPN & Device Management</b></li>
<li>Tap the downloaded profile and tap <b>Install</b></li>
<li>Go to <b>Settings → General → About → Certificate Trust Settings</b></li>
<li>Enable trust for the CLAIJ certificate</li>
</ol>
<p>Then visit: <a href='https://megalodon:8443'>https://megalodon:8443</a></p>
</body></html>"})}}]

   ["/claij.crt"
    {:get {:no-doc true
           :handler (fn [_]
                      (log/info "Certificate download requested")
                      (let [crt-file (clojure.java.io/file "claij-dev.crt")]
                        (if (.exists crt-file)
                          (do
                            (log/info "Serving certificate:" (.getAbsolutePath crt-file))
                            {:status 200
                             :headers {"Content-Type" "application/x-pem-file"
                                       "Content-Length" (str (.length crt-file))}
                             :body (clojure.java.io/input-stream crt-file)})
                          (do
                            (log/warn "Certificate file not found")
                            {:status 404
                             :headers {"Content-Type" "text/plain"}
                             :body "Certificate not found. Run bin/gen-ssl-cert.sh first."}))))}}]

   ["/fsms"
    [""
     {:get {:no-doc true
            :produces ["text/html"]
            :handler fsms-html-handler}}]
    ["/list"
     {:get {:summary "List available FSMs (JSON)"
            :responses {200 {:body [:vector :string]}}
            :handler list-fsms-handler}}]]

   ["/fsm/:fsm-id"
    [""
     {:get {:no-doc true
            :parameters {:path {:fsm-id :string}}
            :produces ["text/html"]
            :handler fsm-html-handler}}]
    ["/document"
     {:get {:summary "Get FSM definition"
            :parameters {:path {:fsm-id :string}}
            :responses {200 {:body :map}
                        404 {:body :map}}
            :handler fsm-document-handler}}]
    ["/graph.svg"
     {:get {:summary "Get FSM as SVG visualization"
            :parameters {:path {:fsm-id :string}}
            :produces ["image/svg+xml"]
            :handler fsm-graph-svg-handler}}]
    ["/graph.dot"
     {:get {:summary "Get FSM as DOT source"
            :parameters {:path {:fsm-id :string}}
            :produces ["text/vnd.graphviz"]
            :handler fsm-graph-dot-handler}}]]

   ;; Voice endpoint (BDD FSM)
   ["/voice"
    {:post {:summary "Voice interaction (STT → LLM → TTS)"
            :description "Submit audio, receive spoken response. Runs BDD FSM with MCP tools."
            :middleware [wrap-multipart-params]
            :consumes ["multipart/form-data"]
            :produces ["audio/wav"]
            :swagger {:parameters {:formData {:audio {:type "file"
                                                      :description "WAV audio file"}}}}
            :responses {200 {:description "Audio response (WAV)"}
                        400 {:body {:error :string}}
                        500 {:body {:error :string}}
                        504 {:body {:error :string}}}
            :handler voice-handler}}]

   ;; Protected endpoints (require Bearer token)
   ["/llm/:provider"
    {:post {:summary "Send message to LLM (requires auth)"
            :middleware [wrap-auth]
            :swagger {:security [{:bearer []}]}
            :parameters {:path {:provider [:enum "grok" "gpt" "claude" "gemini"]}
                         :body {:message :string}}
            :responses {200 {:body {:response :string}}
                        401 {:body {:error :string :message :string}}
                        404 {:body :map}}
            :handler llm-handler}}]])

;;------------------------------------------------------------------------------
;; App

(def app
  (-> (ring/ring-handler
       (ring/router
        routes
        {:data {:coercion malli-coercion/coercion
                :muuntaja m/instance
                :middleware [swagger/swagger-feature
                             parameters/parameters-middleware
                             muuntaja/format-negotiate-middleware
                             muuntaja/format-response-middleware
                             muuntaja/format-request-middleware
                             coercion/coerce-exceptions-middleware
                             coercion/coerce-request-middleware
                             coercion/coerce-response-middleware]}})
       (ring/routes
        ;; Redirect root to voice UI
        (ring/create-resource-handler {:path "/"})
        (swagger-ui/create-swagger-ui-handler
         {:path "/swagger"
          :config {:validatorUrl nil}})
        ;; Redirect / to /voice.html
        (fn [request]
          (if (= "/" (:uri request))
            (resp/redirect "/voice.html")
            nil))
        (ring/create-default-handler)))
      (wrap-resource "public")))

;;------------------------------------------------------------------------------
;; Server

(defn start
  "Start the Jetty server.
   
   Options:
   - :port - HTTP port (default 8080, nil to disable HTTP)
   - :ssl-port - HTTPS port (default nil, set to enable HTTPS)
   - :keystore - Path to Java keystore file (required for HTTPS)
   - :key-password - Keystore password (required for HTTPS)
   - :join? - Block the calling thread (default false)"
  [{:keys [port ssl-port keystore key-password join?]
    :or {port 8080 join? false}}]
  (let [opts (cond-> {:join? join?}
               ;; HTTP
               port (assoc :port port)
               (nil? port) (assoc :port -1) ;; Disable HTTP if nil

               ;; HTTPS
               ssl-port (assoc :ssl? true
                               :ssl-port ssl-port
                               :keystore keystore
                               :key-password key-password))]
    (run-jetty app opts)))

(defn string->url [s] (URL. s))

(defn -main
  [& args]
  (let [[options _ _]
        (cli
         args
         "server: claij API server"
         ["-p" "--port" "HTTP port (0 to disable)" :parse-fn #(Integer/parseInt %) :default 8080]
         ["-s" "--ssl-port" "HTTPS port" :parse-fn #(Integer/parseInt %)]
         ["-k" "--keystore" "Path to Java keystore (.jks) for HTTPS"]
         ["-w" "--key-password" "Keystore password" :default "changeit"])
        {:keys [port ssl-port keystore key-password]} options
        ;; Convert port 0 to nil to disable HTTP
        port (when (and port (pos? port)) port)]

    (when (and ssl-port (not keystore))
      (println "Error: --keystore required when using --ssl-port")
      (println "Generate one with: bin/gen-ssl-cert.sh")
      (System/exit 1))

    (log/info (str "Starting claij server"
                   (when port (str " HTTP:" port))
                   (when ssl-port (str " HTTPS:" ssl-port))))

    (start {:port port
            :ssl-port ssl-port
            :keystore keystore
            :key-password key-password
            :join? true})))
