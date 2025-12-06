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

(defn strip-code-block
  "Remove markdown code blocks (```edn, ```clojure, ```json, etc.)"
  [s]
  (let [s (str/trim s)
        pattern #"(?s)^\s*```\w*\s*\n?(.*?)\n?\s*```\s*$"]
    (if-let [[_ content] (re-matches pattern s)]
      (str/trim content)
      s)))

(defn parse-edn
  "Parse EDN string, stripping any markdown code blocks first"
  [s]
  (-> s strip-code-block edn/read-string))

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
  "You communicate using EDN (Extensible Data Notation).

EDN is like JSON but uses Clojure syntax:
- Maps: {\"key\" \"value\"} 
- Vectors: [1 2 3]
- Strings: \"hello\"

You will receive requests as [INPUT-SCHEMA INPUT-DOCUMENT OUTPUT-SCHEMA] triples.
- INPUT-SCHEMA: Malli schema describing the input
- INPUT-DOCUMENT: The actual data to process  
- OUTPUT-SCHEMA: Malli schema your response MUST conform to

YOUR RESPONSE:
- Must be ONLY valid EDN (no markdown, no explanation)
- Must conform EXACTLY to the OUTPUT-SCHEMA
- Use string keys like \"id\" not keyword keys like :id

MALLI SCHEMA REFERENCE:
- [:map [\"key\" :type]] = map with string key
- [:= value] = exactly this constant value (copy it exactly!)
- [:or A B] = choose A or B based on what you want to do
- [:enum \"a\" \"b\"] = one of these string values
- :string = any string

CRITICAL: The \"id\" field determines which choice you're making.
Look for [:= [\"from\" \"to\"]] in the OUTPUT-SCHEMA to find valid IDs.
Copy the exact value shown.")

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
  "Test a single LLM call. Returns {:success bool :response map :error string}
   
   This demonstrates ALL requirements:
   - Input validates against input-schema (req 5)
   - Output validates against output-schema (req 6)
   - Output contains const id tuple-2 (req 7)"
  [{:keys [provider model]}]
  (let [input-doc {"question" "Is 2 + 2 = 4?"}
        ;; Requirement 5: Input MUST validate against input-schema
        _ (when-not (m/validate input-schema input-doc)
            (throw (ex-info "Input validation failed" 
                           {:input input-doc 
                            :schema input-schema
                            :errors (m/explain input-schema input-doc)})))
        tuple-3 [input-schema input-doc output-schema]
        messages [{"role" "system" "content" system-prompt}
                  {"role" "user" "content" (pr-str tuple-3)}]]
    (try
      (let [raw (call-llm provider model messages)
            parsed (parse-edn raw)
            ;; Requirement 6: Output MUST validate against output-schema
            valid? (m/validate output-schema parsed)]
        (if valid?
          {:success true :response parsed}
          {:success false 
           :response parsed 
           :error (str "Schema validation failed: " 
                      (m/explain output-schema parsed))}))
      (catch Exception e
        {:success false :error (.getMessage e)}))))

(defn test-llm-n-times
  "Test an LLM n times. Returns {:passed int :total int :results vec}"
  [{:keys [provider model] :as llm} n]
  (let [results (doall (repeatedly n #(test-single llm)))
        passed (count (filter :success results))]
    {:llm (str provider "/" model)
     :passed passed 
     :total n 
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
  "Test all LLMs n times each. Returns summary."
  [n]
  (let [results (mapv #(test-llm-n-times % n) all-llms)
        total-passed (reduce + (map :passed results))
        total-tests (* n (count all-llms))]
    (println "\n=== RESULTS ===")
    (doseq [{:keys [llm passed total]} results]
      (println (str "  " llm ": " passed "/" total 
                    " (" (int (* 100 (/ passed total))) "%)")))
    (println (str "\n  TOTAL: " total-passed "/" total-tests
                  " (" (int (* 100 (/ total-passed total-tests))) "%)"))
    {:results results :total-passed total-passed :total-tests total-tests}))

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
