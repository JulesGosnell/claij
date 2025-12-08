(ns claij.server
  (:require
   [clojure.tools.cli :refer [cli]]
   [clojure.tools.logging :as log]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]

   ;; Ring
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.util.response :as resp]

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

   ;; Internal
   [claij.util :refer [assert-env-var clj->json json->clj]]
   [claij.graph :refer [fsm->dot]]
   [claij.fsm.code-review-fsm :refer [code-review-fsm]]
   [claij.fsm.mcp-fsm :refer [mcp-fsm]])
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
   "gpt" (partial open-router "gpt" "openai" "gpt-5-codex" state)
   "claude" (partial open-router "claude" "anthropic" "claude-sonnet-4.5" state)
   "gemini" (partial open-router "gemini" "google" "gemini-2.5-flash" state)})

;;------------------------------------------------------------------------------
;; FSM Registry

(def fsms
  {"code-review-fsm" code-review-fsm
   "mcp-fsm" mcp-fsm})

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
   :body (vec (keys fsms))})

(defn fsm-document-handler [{{:keys [fsm-id]} :path-params}]
  (if-let [fsm (get fsms fsm-id)]
    {:status 200
     :body fsm}
    {:status 404
     :body {:error (str "FSM not found: " fsm-id)}}))

(defn fsm-graph-svg-handler [{{:keys [fsm-id]} :path-params}]
  (if-let [fsm (get fsms fsm-id)]
    {:status 200
     :headers {"content-type" "image/svg+xml"}
     :body (dot->svg (fsm->dot fsm))}
    {:status 404
     :body {:error (str "FSM not found: " fsm-id)}}))

(defn fsm-graph-dot-handler [{{:keys [fsm-id]} :path-params}]
  (if-let [fsm (get fsms fsm-id)]
    {:status 200
     :headers {"content-type" "text/vnd.graphviz"}
     :body (fsm->dot fsm)}
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

   ["/fsms"
    ["/list"
     {:get {:summary "List available FSMs"
            :responses {200 {:body [:vector :string]}}
            :handler list-fsms-handler}}]]

   ["/fsm/:fsm-id"
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
  (ring/ring-handler
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
    (swagger-ui/create-swagger-ui-handler
     {:path "/swagger"
      :config {:validatorUrl nil}})
    (ring/create-default-handler))))

;;------------------------------------------------------------------------------
;; Server

(defn start [port]
  (run-jetty app {:port port :join? false}))

(defn string->url [s] (URL. s))

(defn -main
  [& args]
  (let [[options _ _]
        (cli
         args
         "server: claij API server"
         ["-p" "--port" "http port" :parse-fn #(Integer/parseInt %) :default 8080])
        {:keys [port]} options]
    (log/info (str "Starting claij server on port " port))
    (start port)))
