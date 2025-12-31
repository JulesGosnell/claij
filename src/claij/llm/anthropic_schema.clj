(ns claij.llm.anthropic-schema
  "Anthropic API schemas extracted from community-maintained OpenAPI specification.
   
   Source: https://github.com/laszukdawid/anthropic-openapi-spec
   (derived from Anthropic's TypeScript SDK)
   
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
    (-> (io/resource "schemas/anthropic-openapi.json")
        slurp
        (json/parse-string))))

(defn get-component-schema
  "Get a schema from components/schemas by name.
   Example: (get-component-schema \"Message\")"
  [schema-name]
  (get-in @openapi-spec ["components" "schemas" schema-name]))

;;------------------------------------------------------------------------------
;; Simplified Schemas (resolved, no $refs)
;; These are flattened versions for direct validation

(def response-schema
  "Simplified response schema for Anthropic Messages API.
   Based on Message response but with refs resolved."
  {"type" "object"
   "required" ["id" "type" "role" "content" "model" "stop_reason"]
   "properties"
   {"id" {"type" "string"}
    "type" {"type" "string"
            "enum" ["message"]}
    "role" {"type" "string"
            "enum" ["assistant"]}
    "content" {"type" "array"
               "items" {"type" "object"
                        "properties"
                        {"type" {"type" "string"
                                 "enum" ["text" "tool_use"]}
                         ;; text block
                         "text" {"type" "string"}
                         ;; tool_use block
                         "id" {"type" "string"}
                         "name" {"type" "string"}
                         "input" {"type" "object"}}}}
    "model" {"type" "string"}
    "stop_reason" {"anyOf" [{"type" "string"
                             "enum" ["end_turn" "max_tokens" "stop_sequence" "tool_use"]}
                            {"type" "null"}]}
    "stop_sequence" {"anyOf" [{"type" "string"}
                              {"type" "null"}]}
    "usage" {"type" "object"
             "properties"
             {"input_tokens" {"type" "integer"}
              "output_tokens" {"type" "integer"}}}}})

(def request-schema
  "Simplified request schema for Anthropic Messages API.
   Based on CreateMessageParams but with refs resolved."
  {"type" "object"
   "required" ["model" "max_tokens" "messages"]
   "properties"
   {"model" {"type" "string"}
    "max_tokens" {"type" "integer"}
    "messages" {"type" "array"
                "minItems" 1
                "items" {"type" "object"
                         "required" ["role" "content"]
                         "properties"
                         {"role" {"type" "string"
                                  "enum" ["user" "assistant"]}
                          "content" {"anyOf" [{"type" "string"}
                                              {"type" "array"}]}}}}
    "system" {"anyOf" [{"type" "string"}
                       {"type" "array"}]}
    "tools" {"type" "array"
             "items" {"type" "object"
                      "required" ["name" "input_schema"]
                      "properties"
                      {"name" {"type" "string"}
                       "description" {"type" "string"}
                       "input_schema" {"type" "object"}}}}
    "tool_choice" {"type" "object"}
    "temperature" {"type" "number"}
    "top_p" {"type" "number"}
    "top_k" {"type" "integer"}
    "stream" {"type" "boolean"}}})

(def tool-use-block-schema
  "Schema for a tool_use content block in Anthropic response."
  {"type" "object"
   "required" ["type" "id" "name" "input"]
   "properties"
   {"type" {"type" "string"
            "enum" ["tool_use"]}
    "id" {"type" "string"}
    "name" {"type" "string"}
    "input" {"type" "object"}}})

;;------------------------------------------------------------------------------
;; Utility Functions

(defn schema-info
  "Return metadata about loaded schemas"
  []
  {:openapi-version (get @openapi-spec "openapi")
   :api-title (get-in @openapi-spec ["info" "title"])
   :api-version (get-in @openapi-spec ["info" "version"])
   :schema-count (count (get-in @openapi-spec ["components" "schemas"]))})
