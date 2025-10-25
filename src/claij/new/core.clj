(ns claij.new.core
  "Core orchestration for structured LLM interactions.
  
  Provides the validation-retry loop and main interaction flow."
  (:require [claij.new.schema :refer [base-schema schema->json format-schema-for-prompt]]
            [claij.new.validation :refer [validation-error-message validate-response validate-schema]]
            [claij.new.interceptor :refer [execute-pre-schema execute-pre-prompt execute-post-response memory-interceptor]]
            [clojure.data.json :as json]))

(def max-validation-retries
  "Maximum number of times to retry on validation failure"
  3)

(defn parse-json-response
  "Parse JSON response from LLM.
  
  Returns:
  - {:success true :data <parsed>} if valid JSON
  - {:success false :error <message>} if invalid"
  [response-text]
  (try
    {:success true
     :data (json/read-str response-text :key-fn keyword)}
    (catch Exception e
      {:success false
       :error (str "Failed to parse JSON: " (.getMessage e))
       :raw response-text})))

(defn make-retry-prompt
  "Create a retry prompt when validation fails.
  
  Includes:
  - Original request
  - Failed response
  - Validation error with path
  - Instructions to fix"
  [original-request failed-response validation-error schema]
  {:system
   (str "Your previous response failed schema validation.\n\n"
        "ORIGINAL REQUEST: " (:user original-request) "\n\n"
        "YOUR RESPONSE: " failed-response "\n\n"
        "VALIDATION ERROR: " (validation-error-message validation-error) "\n\n"
        "EXPECTED SCHEMA:\n" (schema->json schema) "\n\n"
        "Please provide a corrected response that conforms to the schema.\n"
        "Focus specifically on fixing the validation error above.")

   :user "Please retry with a valid response."})

(defn send-with-validation
  "Send request to LLM with validation-retry loop.
  
  Parameters:
  - llm-fn: Function that takes prompts and returns response text
  - prompts: Map with :system and :user prompts
  - schema: JSON schema for validation
  - ctx: Context map
  
  Returns:
  - {:success true :response <validated-response> :ctx <updated-ctx>} if successful
  - {:success false :error <message> :attempts <n>} if all retries failed
  
  The validation-retry loop:
  1. Send request to LLM
  2. Parse JSON response
  3. Validate against schema
  4. If invalid, create retry prompt and loop (max 3 times)
  5. If valid, return response"
  [llm-fn prompts schema ctx]
  (loop [attempt 1
         current-prompts prompts
         last-error nil
         last-response nil]

    (let [;; Call LLM
          response-text (llm-fn current-prompts)

          ;; Parse JSON
          parse-result (parse-json-response response-text)]

      (if-not (:success parse-result)
        ;; JSON parse failed
        (if (< attempt max-validation-retries)
          (do
            (println (format "Attempt %d: JSON parse failed - %s"
                             attempt (:error parse-result)))
            (recur (inc attempt)
                   (make-retry-prompt prompts response-text
                                      {:error (:error parse-result)} schema)
                   (:error parse-result)
                   response-text))
          {:success false
           :error (str "Max retries exceeded. Last error: " (:error parse-result))
           :attempts attempt
           :last-response response-text})

        ;; JSON parsed successfully, now validate against schema
        (let [response-data (:data parse-result)
              validation-result (validate-response response-data schema)]

          (if (:valid? validation-result)
            ;; Success!
            {:success true
             :response response-data
             :ctx ctx
             :attempts attempt}

            ;; Validation failed
            (if (< attempt max-validation-retries)
              (do
                (println (format "Attempt %d: Validation failed - %s"
                                 attempt (:error validation-result)))
                (recur (inc attempt)
                       (make-retry-prompt prompts response-text
                                          validation-result schema)
                       validation-result
                       response-text))
              {:success false
               :error (str "Max retries exceeded. Last error: "
                           (validation-error-message validation-result))
               :attempts attempt
               :last-response response-text
               :validation-error validation-result})))))))

(defn call-llm
  "High-level function to call LLM with interceptors and validation.
  
  Parameters:
  - llm-fn: Function that takes prompts and returns response text
  - user-message: The user's message/question
  - interceptors: Vector of interceptors to apply
  - ctx: Context map (optional, default {})
  
  Returns:
  - {:success true :response <response> :ctx <updated-ctx>} if successful
  - {:success false :error <message>} if failed
  
  Flow:
  1. Execute pre-schema hooks -> compose schema
  2. Validate composed schema
  3. Execute pre-prompt hooks -> finalize prompts
  4. Send to LLM with validation-retry loop
  5. Execute post-response hooks -> update context
  6. Return result"
  ([llm-fn user-message interceptors]
   (call-llm llm-fn user-message interceptors {}))

  ([llm-fn user-message interceptors ctx]
   (try
     ;; 1. Compose schema
     (let [[composed-schema ctx] (execute-pre-schema
                                  interceptors
                                  base-schema
                                  ctx)

           ;; 2. Validate schema
           schema-validation (validate-schema composed-schema)]

       (if-not (:valid? schema-validation)
         {:success false
          :error (str "Invalid schema: " (:error schema-validation))}

         ;; 3. Prepare prompts with schema
         (let [base-prompts {:system (format-schema-for-prompt composed-schema)
                             :user user-message}
               [final-prompts ctx] (execute-pre-prompt
                                    interceptors
                                    base-prompts
                                    ctx)

               ;; 4. Send with validation
               result (send-with-validation llm-fn final-prompts composed-schema ctx)]

           (if (:success result)
             ;; 5. Execute post-response hooks
             (let [updated-ctx (execute-post-response
                                interceptors
                                (:response result)
                                (:ctx result))]
               {:success true
                :response (:response result)
                :ctx updated-ctx
                :attempts (:attempts result)})

             ;; Failed
             result))))

     (catch Exception e
       {:success false
        :error (str "Exception: " (.getMessage e))
        :exception e}))))

(comment
  ;; Example usage

  ;; Mock LLM function for testing
  (defn mock-llm [prompts]
    (json/write-str
     {:answer "I understand you like blue!"
      :state "ready"
      :summary "User's favorite color is blue"}))

  ;; Call with memory interceptor
  (call-llm
   mock-llm
   "My favorite color is blue. Remember that!"
   [memory-interceptor]
   {})
  ;=> {:success true
  ;    :response {:answer "..." :state "ready" :summary "..."}
  ;    :ctx {:memory "User's favorite color is blue"}
  ;    :attempts 1}
  )
