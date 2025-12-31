(ns claij.llm.google-schema
  "Google Gemini API schemas extracted from community-maintained OpenAPI specification.
   
   Source: https://github.com/QuasarByte/google-gemini-api-openapi-specification-revision-20241016
   
   These schemas are the ground truth for:
   1. Integration tests - validate real API responses
   2. Unit tests - validate recorded fixtures"
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]))

;;------------------------------------------------------------------------------
;; Schema Loading

(defonce ^:private openapi-spec
  (delay
    (-> (io/resource "schemas/google-openapi.json")
        slurp
        (json/parse-string))))

(defn get-component-schema
  "Get a schema from components/schemas by name.
   Example: (get-component-schema \"GenerateContentResponse\")"
  [schema-name]
  (get-in @openapi-spec ["components" "schemas" schema-name]))

;;------------------------------------------------------------------------------
;; Simplified Schemas (resolved, no $refs)
;; These are flattened versions for direct validation

(def response-schema
  "Simplified response schema for Google generateContent API.
   Based on GenerateContentResponse but with refs resolved."
  {"type" "object"
   "properties"
   {"candidates" {"type" "array"
                  "items" {"type" "object"
                           "properties"
                           {"content" {"type" "object"
                                       "properties"
                                       {"parts" {"type" "array"
                                                 "items" {"type" "object"
                                                          "properties"
                                                          {"text" {"type" "string"}
                                                           "functionCall" {"type" "object"
                                                                           "properties"
                                                                           {"name" {"type" "string"}
                                                                            "args" {"type" "object"}}}}}}
                                        "role" {"type" "string"}}}
                            "finishReason" {"type" "string"
                                            "enum" ["FINISH_REASON_UNSPECIFIED" "STOP" "MAX_TOKENS"
                                                    "SAFETY" "RECITATION" "OTHER"]}
                            "index" {"type" "integer"}
                            "safetyRatings" {"type" "array"}}}}
    "promptFeedback" {"type" "object"}
    "usageMetadata" {"type" "object"
                     "properties"
                     {"promptTokenCount" {"type" "integer"}
                      "candidatesTokenCount" {"type" "integer"}
                      "totalTokenCount" {"type" "integer"}}}}})

(def request-schema
  "Simplified request schema for Google generateContent API.
   Based on GenerateContentRequest but with refs resolved."
  {"type" "object"
   "required" ["contents"]
   "properties"
   {"contents" {"type" "array"
                "minItems" 1
                "items" {"type" "object"
                         "properties"
                         {"parts" {"type" "array"
                                   "items" {"type" "object"
                                            "properties"
                                            {"text" {"type" "string"}}}}
                          "role" {"type" "string"
                                  "enum" ["user" "model"]}}}}
    "tools" {"type" "array"
             "items" {"type" "object"
                      "properties"
                      {"functionDeclarations" {"type" "array"
                                               "items" {"type" "object"
                                                        "required" ["name"]
                                                        "properties"
                                                        {"name" {"type" "string"}
                                                         "description" {"type" "string"}
                                                         "parameters" {"type" "object"}}}}}}}
    "toolConfig" {"type" "object"}
    "safetySettings" {"type" "array"}
    "generationConfig" {"type" "object"
                        "properties"
                        {"temperature" {"type" "number"}
                         "topP" {"type" "number"}
                         "topK" {"type" "integer"}
                         "maxOutputTokens" {"type" "integer"}
                         "stopSequences" {"type" "array"
                                          "items" {"type" "string"}}}}}})

(def function-call-schema
  "Schema for a functionCall part in Google response."
  {"type" "object"
   "required" ["name" "args"]
   "properties"
   {"name" {"type" "string"}
    "args" {"type" "object"}}})

;;------------------------------------------------------------------------------
;; Utility Functions

(defn schema-info
  "Return metadata about loaded schemas"
  []
  {:openapi-version (get @openapi-spec "openapi")
   :api-title (get-in @openapi-spec ["info" "title"])
   :api-version (get-in @openapi-spec ["info" "version"])
   :schema-count (count (get-in @openapi-spec ["components" "schemas"]))})
