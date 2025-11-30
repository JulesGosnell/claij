(ns claij.server
  (:require
   [clojure.tools.cli :refer [cli]]
   [clojure.core.async :refer [>!! <!!]]
   [clojure.tools.logging :as log]
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.util.response :refer [response content-type not-found]]

   ;; tmp
   [clojure.string :refer [split trim join starts-with?]]
   [clojure.core.async :refer [go-loop <! >! chan]]
   [clj-http.client :refer [post]]
   [claij.util :refer [assert-env-var clj->json json->clj]]

   ;; FSM graph endpoint
   [clojure.java.shell :refer [sh]]
   [claij.graph :refer [fsm->dot]]
   [claij.fsm.code-review-fsm :refer [code-review-fsm]]
   [claij.fsm.mcp-fsm :refer [mcp-fsm]])
  (:import
   [java.net URL])
  (:gen-class))

(set! *warn-on-reflection* true)

;;------------------------------------------------------------------------------
;; agent stuff

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

(defn trc [prefix x] (prn prefix x) x)

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
    (join
     ","
     (map
      (comp
       (fn [[answer new-summary]] (compare-and-set! summary old-summary (trim new-summary)) (trim answer))
       (fn [s] (split s pattern))
       :content
       :message)
      (:choices
       (json->clj (:body answer)))))))

(defn ai [f input-channel output-channel]
  (go-loop []
    (when-some [m (<! input-channel)]
      (>! output-channel (f m))
      (recur))))

(def ai->chat (chan 1024 (map (partial trc "ai->chat:"))))
(def chat->ai (chan 1024 (map (partial trc "chat->ai:"))))

;;------------------------------------------------------------------------------

(def state (atom initial-summary))

(def llms
  {"grok" (partial open-router "grok" "x-ai" "grok-code-fast-1" state)
   "gpt" (partial open-router "gpt" "openai" "gpt-5-codex" state)
   "claude" (partial open-router "claude" "anthropic" "claude-sonnet-4.5" state)
   "gemini" (partial open-router "gemini" "google" "gemini-2.5-flash" state)})

;; FSM registry - hardcoded for now, will come from storage later
(def fsms
  {"code-review-fsm" code-review-fsm
   "mcp-fsm" mcp-fsm})

(defn dot->svg [dot-str]
  (let [{:keys [out err exit]} (sh "dot" "-Tsvg" :in dot-str)]
    (if (zero? exit)
      out
      (throw (ex-info "GraphViz rendering failed" {:stderr err :exit exit})))))

;; (defn handler [request]
;;   (let [uri (:uri request)
;;         parts (split uri #"/")
;;         llm (if (= (count parts) 3) (lower-case (nth parts 2)) "claude")
;;         backend (get backends llm)]
;;     (if backend
;;       (content-type (response (backend (slurp (:body request)))) "text/plain")
;;       (not-found "LLM not found"))))

;; (defn handler [ic oc {uri :uri body :body}]
;;   (prn "HAHA:" (last (split uri #"/")))
;;   (>!! ic (slurp body))
;;   (content-type (response (<!! oc)) "text/plain"))

(defn handler [ic oc {uri :uri body :body}]
  (cond
    ;; Health check endpoint
    (= uri "/health")
    (content-type (response "ok v0.1.1") "text/plain")

    ;; FSM graph endpoint: /fsm/graph/<fsm-id>
    (starts-with? uri "/fsm/graph/")
    (let [suffix (subs uri (count "/fsm/graph/"))
          [fsm-id fmt] (cond
                         (clojure.string/ends-with? suffix ".svg")
                         [(subs suffix 0 (- (count suffix) 4)) :svg]
                         (clojure.string/ends-with? suffix ".dot")
                         [(subs suffix 0 (- (count suffix) 4)) :dot]
                         :else [suffix :dot])]
      (if-let [fsm (get fsms fsm-id)]
        (let [dot (fsm->dot fsm)]
          (case fmt
            :dot (content-type (response dot) "text/vnd.graphviz")
            :svg (content-type (response (dot->svg dot)) "image/svg+xml")))
        (not-found (str "FSM not found: " fsm-id))))

    ;; LLM endpoints
    :else
    (if-let [llm (last (split uri #"/"))]
      (let [input (slurp body)
            output ((llms llm) input)]
        (log/info (str llm "->: " input))
        (log/info (str llm "<-: " output))
        (content-type (response output) "text/plain"))
      (not-found uri))))

(defn start [port ic oc]
  (run-jetty (partial handler ic oc) {:port port}))

(defn string->url [s] (URL. s))

;;------------------------------------------------------------------------------

(defn -main
  [& args]
  (let [[options _ documentation]
        (cli
         args
         "server: integrate a given LLM into a CLAIJ team"
         ["-p" "--port" "http port" :parse-fn #(Integer/parseInt %) :default 8000]
         ["-t" "--tts-url" "tts url" :parse-fn string->url :default (string->url "http://localhost:8001/synthesize")]
         ["-l" "--llm" "llm" :parse-fn identity :default "/claude-sonnet-4.5"])
        {:keys [port tts-url llm]} options]

    ;; join up pipework

    (ai
     (partial open-router "claude" "anthropic" "claude-sonnet-4.5" (atom initial-summary))
     chat->ai
     ai->chat)

    (start port chat->ai ai->chat)))

;;------------------------------------------------------------------------------

;; next steps
;; - move memory strategy into e.g. accumulating-memory-strategy
;; - integrate speech to text input
;; - integrate text to speech output
;; - integrate LLM switching (very cool)
;; - integrate a REPL and a repl-results-strategy
;; - encourage AIs to do their own mcp access via REPL
;; - begin to buld an MCP dsl for use at REPL
;; - each different LLM gets a differnt voice
;; - looking very good !
;; - handle timeouts
