(ns claij.agent.open-router
  (:require
   [claij.repl :refer [start-prepl]]
   [claij.util :refer [assert-env-var clj->json json->clj]]
   [clj-http.client :refer [post]]
   [clojure-mcp.linting :refer [lint]]
   [clojure.core.async :refer [<! <!! >! >!! chan go-loop]]
   [clojure.string :refer [join split trim]]))

;; https://openrouter.ai/docs/quickstart

(def api-key (assert-env-var "OPENROUTER_API_KEY"))

(def api-base "https://openrouter.ai")
(def api-url (str api-base "/api/v1"))

(def headers
  {"Authorization" (str "Bearer " api-key)
   "content-type" "application/json"})

(def separator "041083c4-7bb7-4cb7-884b-3dc4b2bd0413")
(def pattern (re-pattern separator))

(defn open-router [id provider model summary message]
  (let [old-summary @summary
        answer
        (post
         (str api-url "/chat/completions")
         {:headers headers
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

(def grok   (partial open-router "grok"   "x-ai"      "grok-code-fast-1" (atom initial-summary)))
(def gpt    (partial open-router "gpt"    "openai"    "gpt-5-codex"      (atom initial-summary)))
(def claude (partial open-router "claude" "anthropic" "claude-opus-4.5"  (atom initial-summary)))
(def gemini (partial open-router "gemini" "google"    "gemini-2.5-flash" (atom initial-summary)))

;; we need some protocols
;; say - relate an id to an sexpr
;; comment - say something with no side-effects
;; sync - for wen a new AI joins the group
;; expel - for when an Ai just can't get the protocols right
;; vote - propose/vote/accept

(defn ai [f input-channel output-channel]
  (go-loop []
    (when-some [m (<! input-channel)]
      (let [clj (trc "AI SAYS:" (prn-str (f m)))]
        (if-let [l (lint clj)]
          (do
            (f (trc "LINT SAYS:" (prn-str l))))
          (>! output-channel clj)))
      (recur))))


;;------------------------------------------------------------------------------
;; in/out transformers

(def id (atom 0))

(defn x-in [user s]
  (str "[" (swap! id inc) " " user " " s "]"))


(defmulti x-out :tag)

(defmethod x-out :ret [{[id user v] :val :keys [form ns ms]}]
  ;; returns [tag id user val form ns ms]
  [:ret id user v (second (re-find (re-pattern (str "^\\[" id " " user " (.*)\\]$")) form)) ns ms])

(defmethod x-out :out  [{v :val}]
  [:out v])

(defmethod x-out :err  [{v :val}]
  [:err v])

(defmethod x-out :tap [{v :val}]
  [:tap v])

;;------------------------------------------------------------------------------

(def ai->clj (chan 1024 (map (comp (partial trc "ai->clj:") (partial x-in :gpt)))))
(def clj->ai (chan 1024 (map (comp (partial trc "clj->ai:") x-out))))

(defmacro i [form] `(>!! ai->clj (pr-str '~form)))
(defn o [] (<!! clj->ai))

(def stop-prepl (start-prepl ai->clj clj->ai))

(defn start []
  (ai gpt clj->ai ai->clj))

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


