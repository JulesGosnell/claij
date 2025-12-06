(ns claij.poc.malli-edn-poc-test
  "Minimal POC: Prove LLMs can communicate via EDN conforming to Malli schemas.
   
   This is the CORE of CLAIJ - if this doesn't work reliably, nothing else matters.
   
   The concept:
   1. Send [input-schema, input-doc, output-schema] tuple to LLM
   2. LLM responds with EDN conforming to output-schema
   3. Output-schema offers choices via [:or ...]
   4. Response validates against chosen schema branch
   5. The 'id' field (a const tuple-2) determines which branch was chosen
   
   Run (test-all-llms 5) to verify reliability across all providers."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clj-http.client :as http]
   [cheshire.core :as json]
   [malli.core :as m]
   [claij.util :refer [assert-env-var]]))

;;------------------------------------------------------------------------------
;; Configuration

(defn api-key [] (assert-env-var "OPENROUTER_API_KEY"))
(def api-url "https://openrouter.ai/api/v1/chat/completions")

;; LLMs ordered by reliability
(def reliable-llms
  [{:provider "google" :model "gemini-3-pro-preview"}
   {:provider "anthropic" :model "claude-sonnet-4.5"}
   {:provider "openai" :model "gpt-5.1-codex"}
   {:provider "x-ai" :model "grok-code-fast-1"}])

(def all-llms
  (conj reliable-llms
        {:provider "meta-llama" :model "llama-4-maverick"}))

;;------------------------------------------------------------------------------
;; EDN Parsing

(defn parse-edn
  "Parse EDN string."
  [s]
  (edn/read-string (str/trim s)))

;;------------------------------------------------------------------------------
;; LLM Call

(defn call-llm
  "Make a synchronous call to LLM via OpenRouter.
   Returns the content string from the response."
  [provider model messages]
  (let [response (http/post api-url
                            {:headers {"Authorization" (str "Bearer " (api-key))
                                       "Content-Type" "application/json"}
                             :body (json/generate-string
                                    {:model (str provider "/" model)
                                     :messages messages})
                             :as :json})]
    (get-in response [:body :choices 0 :message :content])))

;;------------------------------------------------------------------------------
;; System Prompt - CRITICAL: This is what makes EDN/Malli work

(def system-prompt
  "
We are living in a Clojure world.
All communications will be in EDN (Extensible Data Notation) format.

YOUR REQUEST:
- will contain [INPUT-SCHEMA INPUT-DOCUMENT OUTPUT-SCHEMA] triples.
- INPUT-SCHEMA: Malli schema describing the INPUT-DOCUMENT
- INPUT-DOCUMENT: The actual data to process  
- OUTPUT-SCHEMA: Malli schema your response MUST conform to

YOUR RESPONSE:
- Must be ONLY valid EDN (no markdown, no explanation, no colons between keys and values)
- Must conform EXACTLY to the OUTPUT-SCHEMA
- Must Use string keys like \"id\" not keyword keys like :id

CRITICAL:
- The OUTPUT-SCHEMA will offer you a set (possibly only one) of choices/sub-schemas
- Your OUTPUT-DOCUMENT must conform to one of these.
- Each sub-schema will contain a discriminator called id. You must include this in your OUTPUT-DOCUMENT
- You must include all non-optional fields with a valid value
")

;;------------------------------------------------------------------------------
;; Test Schemas

(def input-schema
  [:map {:closed true}
   ["question" :string]])

(def output-schema-agree
  [:map {:closed true}
   ["id" [:= ["user" "agree"]]]
   ["message" :string]])

(def output-schema-disagree  
  [:map {:closed true}
   ["id" [:= ["user" "disagree"]]]
   ["reason" :string]])

(def output-schema
  [:or output-schema-agree output-schema-disagree])

;;------------------------------------------------------------------------------
;; Test Implementation

(defn test-single
  "Test a single LLM call. Returns map with:
   - :success bool
   - :response map (the parsed EDN)
   - :time-ms response time in milliseconds
   - :error string (on failure)"
  [{:keys [provider model]}]
  (let [input-doc {"question" "Is 2 + 2 = 4?"}
        _ (when-not (m/validate input-schema input-doc)
            (throw (ex-info "Input validation failed" 
                           {:input input-doc 
                            :schema input-schema
                            :errors (m/explain input-schema input-doc)})))
        tuple-3 [input-schema input-doc output-schema]
        messages [{"role" "system" "content" system-prompt}
                  {"role" "user" "content" (pr-str tuple-3)}]]
    (try
      (let [start (System/currentTimeMillis)
            raw (call-llm provider model messages)
            end (System/currentTimeMillis)
            time-ms (- end start)
            parsed (parse-edn raw)
            valid? (m/validate output-schema parsed)]
        (if valid?
          {:success true :response parsed :time-ms time-ms}
          {:success false 
           :response parsed 
           :time-ms time-ms
           :error (str "Schema validation failed: " 
                      (m/explain output-schema parsed))}))
      (catch Exception e
        {:success false :error (.getMessage e)}))))

(defn test-llm-n-times
  "Test an LLM n times. Returns aggregated stats including timing."
  [{:keys [provider model] :as llm} n]
  (let [results (doall (repeatedly n #(test-single llm)))
        passed (count (filter :success results))
        times (keep :time-ms results)
        avg-time-ms (when (seq times) (/ (reduce + times) (count times)))]
    {:llm (str provider "/" model)
     :passed passed 
     :total n 
     :avg-time-ms (when avg-time-ms (Math/round (double avg-time-ms)))
     :results results}))

;;------------------------------------------------------------------------------
;; REPL helpers

(defn test-gemini 
  ([] (test-gemini 1))
  ([n] (test-llm-n-times {:provider "google" :model "gemini-3-pro-preview"} n)))

(defn test-claude 
  ([] (test-claude 1))
  ([n] (test-llm-n-times {:provider "anthropic" :model "claude-sonnet-4.5"} n)))

(defn test-gpt 
  ([] (test-gpt 1))
  ([n] (test-llm-n-times {:provider "openai" :model "gpt-5.1-codex"} n)))

(defn test-grok 
  ([] (test-grok 1))
  ([n] (test-llm-n-times {:provider "x-ai" :model "grok-code-fast-1"} n)))

(defn test-llama 
  ([] (test-llama 1))
  ([n] (test-llm-n-times {:provider "meta-llama" :model "llama-4-maverick"} n)))

(defn test-all-llms
  "Test all LLMs n times each. Returns summary with reliability ranking."
  [n]
  (let [results (mapv #(test-llm-n-times % n) all-llms)
        total-passed (reduce + (map :passed results))
        total-tests (* n (count all-llms))
        ;; Sort by pass rate descending, then by avg time ascending
        ranked (sort-by (fn [{:keys [passed total avg-time-ms]}]
                          [(- (/ passed total)) (or avg-time-ms 999999)])
                        results)]
    (println "\n=== RELIABILITY REPORT ===")
    (println (format "%-35s %8s %10s" "LLM" "Score" "Avg (ms)"))
    (println (apply str (repeat 55 "-")))
    (doseq [{:keys [llm passed total avg-time-ms]} ranked]
      (println (format "%-35s %3d/%-3d %8s" 
                       llm 
                       passed total
                       (if avg-time-ms (str avg-time-ms) "n/a"))))
    (println (apply str (repeat 55 "-")))
    (println (format "TOTAL: %d/%d (%.0f%%)" 
                     total-passed total-tests 
                     (* 100.0 (/ total-passed total-tests))))
    {:results ranked :total-passed total-passed :total-tests total-tests}))

;;------------------------------------------------------------------------------
;; Integration Tests

(deftest ^:integration malli-edn-poc-single-test
  (testing "Single LLM (Gemini) can respond with valid EDN matching Malli schema"
    (let [{:keys [success response error]} (test-single {:provider "google" 
                                                          :model "gemini-3-pro-preview"})]
      (is success (str "Should succeed. Error: " error))
      (when success
        (is (= ["user" "agree"] (get response "id")) 
            "Should choose 'agree' for 2+2=4")))))

(deftest ^:integration malli-edn-poc-all-reliable-llms-test
  (testing "All reliable LLMs (4) pass at least once"
    (let [results (mapv #(test-llm-n-times % 3) reliable-llms)
          all-passed? (every? #(pos? (:passed %)) results)]
      (is all-passed? 
          (str "All reliable LLMs should pass at least once. Results: "
               (mapv #(select-keys % [:llm :passed :total]) results))))))

(deftest ^:integration malli-edn-poc-reliability-test
  (testing "94%+ reliability across all LLMs (10 runs each)"
    (let [{:keys [total-passed total-tests]} (test-all-llms 10)
          pass-rate (/ total-passed total-tests)]
      (is (>= pass-rate 0.90)
          (str "Should achieve 90%+ reliability. Got: " 
               (int (* 100 pass-rate)) "%")))))

;; Quick test at REPL:
;; (require '[claij.poc.malli-edn-poc-test :refer :all] :reload)
;; (test-gemini)
;; (test-gemini 5)
;; (test-all-llms 5)
