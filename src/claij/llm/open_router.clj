(ns claij.llm.open-router
  (:require
   [clojure.string :refer [join split trim]]
   [clojure.tools.logging :as log]
   [clojure.edn :as edn]
   [clojure.pprint :refer [pprint]]
   [clojure.core.async :refer [<! <!! >! >!! chan go-loop]]
   [clj-http.client :refer [post]]
   [cheshire.core :as json]
   [claij.util :refer [assert-env-var clj->json json->clj make-retrier]]))

;; https://openrouter.ai/docs/quickstart

(defn api-key [] (assert-env-var "OPENROUTER_API_KEY"))

(def api-base "https://openrouter.ai")
(def api-url (str api-base "/api/v1"))

(defn headers []
  {"Authorization" (str "Bearer " (api-key))
   "content-type" "application/json"})

(def separator "041083c4-7bb7-4cb7-884b-3dc4b2bd0413")
(def pattern (re-pattern separator))

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
            [{:role "system" :content (str "You're name is:" id ", you are a Clojure developer, talking to a clojure.core.server.prepl - output from the pREPL is returned as tuples: Either `[tag id user val form ns ms]` or `[tag val]` to save tokens")}
             {:role "user" :content (str "Here is a summary of your conversational state: " old-summary)}
             {:role "user" :content (str "Here is the latest asynchronous tuple from the pREPL: " message)}
             {:role "user" :content "Please reply with EITHER: a single well-formed Clojure expression which will elicit further requests from the pREPL - infinitely - so be sure that you want to prompt another request before doing this, OR a ';' followed by a comment which will finish this conversational thread"}
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

(def initial-summary "This is the beginning of our conversation")

;; Prepl is capturing *out* on its thread (which is where the output transducer is running) - dodge it...
(let [captured-out *out*]
  (defn trc [prefix x] (binding [*out* captured-out] (prn prefix x)) x))

(def grok (partial open-router "grok" "x-ai" "grok-code-fast-1" (atom initial-summary)))
(def gpt (partial open-router "gpt" "openai" "gpt-5-codex" (atom initial-summary)))
(def claude (partial open-router "claude" "anthropic" "claude-sonnet-4.5" (atom initial-summary)))
(def gemini (partial open-router "gemini" "google" "gemini-2.5-flash" (atom initial-summary)))

;; we need some protocols
;; say - relate an id to an sexpr
;; comment - say something with no side-effects
;; sync - for wen a new AI joins the group
;; expel - for when an Ai just can't get the protocols right
;; vote - propose/vote/accept

;; (defn ai [f input-channel output-channel]
;;   (go-loop []
;;     (when-some [m (<! input-channel)]
;;       (let [clj (trc "AI SAYS:" (prn-str (f m)))]
;;         (if-let [l (lint clj)]
;;           (do
;;             (f (trc "LINT SAYS:" (prn-str l))))
;;           (>! output-channel clj)))
;;       (recur))))

;;------------------------------------------------------------------------------
;; in/out transformers

(def id (atom 0))

(defn x-in [user s]
  (str "[" (swap! id inc) " " user " " s "]"))

(defmulti x-out :tag)

(defmethod x-out :ret [{[id user v] :val :keys [form ns ms]}]
  ;; returns [tag id user val form ns ms]
  [:ret id user v (second (re-find (re-pattern (str "^\\[" id " " user " (.*)\\]$")) form)) ns ms])

(defmethod x-out :out [{v :val}]
  [:out v])

(defmethod x-out :err [{v :val}]
  [:err v])

(defmethod x-out :tap [{v :val}]
  [:tap v])

;;------------------------------------------------------------------------------

;; Unused old code - commented out
;; (def ai->clj (chan 1024 (map (comp (partial trc "ai->clj:") (partial x-in :gpt)))))
;; (def clj->ai (chan 1024 (map (comp (partial trc "clj->ai:") x-out))))
;; (defmacro i [form] `(>!! ai->clj (pr-str '~form)))
;; (defn o [] (<!! clj->ai))
;; (defn start []
;;   (ai gpt clj->ai ai->clj))

;; (comment
;;   [{:role "system" :content (str "You're name is:" id ", you are a Clojure developer, talking to a clojure.core.server.prepl - results from the pREPL are returned as tuples: Either `[tag id user val form ns ms]` or `[tag val]` to save tokens")}
;;    {:role "user" :content (str "Here is a summary of your conversational state: " old-summary)}
;;    {:role "user" :content (str "Here is the latest response from the pREPL: " message)}
;;    {:role "user" :content "Please reply with EITHER: a single well-formed Clojure s-expression which will elucidate further conversation, OR a ';' followed by a comment which will finish this conversational thread"}
;;    {:role "user" :content (str "Please append this separator to your response: '" separator "'.")}
;;    {:role "user" :content "Please merge your summary and answer tersely into a fresh summary and append to your response after the separator. It will be passed back to you in the next request."}])

;; (comment
;;   [{:role "system" :content (str "You're name is:" id ", you are a Clojure developer, talking to a clojure.core.server.prepl")}
;;    {:role "user" :content (str "Here is a summary of your conversational state: " old-summary)}
;;    {:role "user" :content (str "Here is the latest response from the pREPL: " message)}
;;    {:role "user" :content "Please reply with EITHER: a single well-formed Clojure s-expression which will elucidate further conversation, OR a ';' followed by a comment which will finish this conversational thread"}
;;    {:role "user" :content (str "Please append this separator to your response: '" separator "'.")}
;;    {:role "user" :content "Please merge your summary and answer tersely into a fresh summary and append to your response after the separator. It will be passed back to you in the next request."}])

;; (i "Hi, I am your user, Jules. I can talk to you by dropping strings on the pREPL. The results of the evaluation will be echoed back to you. If you reply with a string literal, I will see that echoed back to me. All our conversation must be in terms of Clojure s-exprs as it will all happen via this pREPL. Do you understand ?")

;;------------------------------------------------------------------------------
;; new code for fsm...

;; for claude ?

(defn strip-md-json [s]
  (let [m (re-matches #"(?s)\s*```(?:json|edn|clojure)?\s*(.*)\s*```\s*" s)]
    (if m (second m) s)))

(defn unpack [{b :body}]
  (let [{[{{c :content} :message}] :choices} (json/parse-string b true)]
    c))

(defn ppr-str [x]
  (with-out-str (pprint x)))

;; DEBUG atoms for capturing LLM calls
(defonce llm-call-capture (atom nil))
(defonce llm-response-capture (atom nil))

(defn open-router-async
  "Call OpenRouter API asynchronously. Optionally accepts a JSON schema for structured output.
  Automatically retries on JSON parse errors with feedback to the LLM.
  
  Args:
    provider - Provider name (e.g., 'openai')
    model - Model name (e.g., 'gpt-4o')
    prompts - Vector of message maps with :role and :content
    handler - Function to call with successful response
    schema - (Optional) Malli schema for response validation
    error - (Optional) Function to call on error
    retry-count - (Internal) Current retry attempt number
    max-retries - Maximum number of retries for malformed EDN (default: 3)"
  [provider model prompts handler & [{:keys [schema error retry-count max-retries]
                                      :or {retry-count 0 max-retries 3}}]]
  (log/info (str "      LLM Call: " provider "/" model
                 (when (> retry-count 0) (str " (retry " retry-count "/" max-retries ")"))))
  (let [body-map (cond-> {:model (str provider "/" model)
                          :messages prompts})]
    (reset! llm-call-capture {:provider provider :model model :prompts prompts :body-map body-map :timestamp (java.time.Instant/now)})
    (post
     (str api-url "/chat/completions")
     {:async? true
      :headers (headers)
      :body (clj->json body-map)}
     (fn [r]
       (try
         (let [d (strip-md-json (unpack r))]
           (reset! llm-response-capture {:raw d :status :received :timestamp (java.time.Instant/now)})
           (try
             (let [j (edn/read-string (trim d))]
               (log/info "      [OK] LLM Response: Valid EDN received")
               (swap! llm-response-capture assoc :parsed j :status :success)
               (handler j))
             (catch Exception e
               (let [retrier (make-retrier max-retries)]
                 (retrier
                  retry-count
                   ;; Retry operation: send error feedback and try again
                  (fn []
                    (let [error-msg (str "We could not unmarshal your EDN - it must be badly formed.\n\n"
                                         "Here is the exception:\n"
                                         (.getMessage e) "\n\n"
                                         "Here is your malformed response:\n" d "\n\n"
                                         "Please try again. Your response should only contain the relevant EDN document.")
                          retry-prompts (conj (vec prompts) {"role" "user" "content" error-msg})]
                      (log/warn (str "      [X] EDN Parse Error: " (.getMessage e)))
                      (log/info (str "      [>>] Sending error feedback to LLM"))
                      (open-router-async provider model retry-prompts handler
                                         {:error error
                                          :retry-count (inc retry-count)
                                          :max-retries max-retries})))
                   ;; Max retries handler
                  (fn []
                    (log/debug (str "Final malformed response: " d))
                    (when error (error {:error "max-retries-exceeded"
                                        :raw-response d
                                        :exception (.getMessage e)}))))))))
         (catch Throwable t
           (log/error t "Error processing LLM response"))))
     (fn [exception]
       (reset! llm-response-capture {:exception exception :status :error :timestamp (java.time.Instant/now)})
       (try
         (let [m (json/parse-string (:body (.getData exception)) true)]
           (log/error (str "      [X] LLM Request Failed: " (get m "error")))
           (swap! llm-response-capture assoc :error-body m)
           (when error (error m)))
         (catch Throwable t
           (log/error t "Error handling LLM failure")))))))
