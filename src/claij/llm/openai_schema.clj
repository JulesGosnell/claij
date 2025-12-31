(ns claij.llm.openai-schema
  "OpenAI API schemas extracted from official OpenAPI specification.
   
   Source: https://app.stainless.com/api/spec/documented/openai/openapi.documented.yml
   
   These schemas are the ground truth for:
   1. Integration tests - validate real API responses
   2. Unit tests - validate recorded fixtures
   3. CLAIJ's OpenRouter-compatible endpoint (future)"
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]))

;;------------------------------------------------------------------------------
;; Schema Loading

(defonce ^:private openapi-spec
  (delay
    (-> (io/resource "schemas/openai-openapi.json")
        slurp
        (json/parse-string))))

(defn get-component-schema
  "Get a schema from components/schemas by name.
   Example: (get-component-schema \"CreateChatCompletionResponse\")"
  [schema-name]
  (get-in @openapi-spec ["components" "schemas" schema-name]))

;;------------------------------------------------------------------------------
;; Chat Completion Schemas (OpenAI-compatible / OpenRouter)

(def chat-completion-response-schema
  "Schema for POST /v1/chat/completions response.
   Use for validating responses from OpenAI, OpenRouter, xAI, Ollama."
  (delay (get-component-schema "CreateChatCompletionResponse")))

(def chat-completion-request-schema
  "Schema for POST /v1/chat/completions request body.
   Use for validating requests before sending."
  (delay (get-component-schema "CreateChatCompletionRequest")))

(def chat-completion-message-schema
  "Schema for assistant message in response.
   Contains content, role, tool_calls, etc."
  (delay (get-component-schema "ChatCompletionResponseMessage")))

(def tool-call-schema
  "Schema for a single tool call in response.
   Contains id, type, function.name, function.arguments."
  (delay (get-component-schema "ChatCompletionMessageToolCall")))

;;------------------------------------------------------------------------------
;; Simplified Schemas (resolved, no $refs)
;; These are flattened versions for direct m3 validation

(def response-schema
  "Simplified response schema for validation (no $refs).
   Based on CreateChatCompletionResponse but with refs resolved."
  {"type" "object"
   "required" ["choices" "created" "id" "model" "object"]
   "properties"
   {"id" {"type" "string"}
    "object" {"type" "string"
              "enum" ["chat.completion"]}
    "created" {"type" "integer"}
    "model" {"type" "string"}
    "choices" {"type" "array"
               "items" {"type" "object"
                        "required" ["finish_reason" "index" "message"]
                        "properties"
                        {"finish_reason" {"type" "string"
                                          "enum" ["stop" "length" "tool_calls" "content_filter" "function_call"]}
                         "index" {"type" "integer"}
                         "message" {"type" "object"
                                    "required" ["role"]
                                    "properties"
                                    {"role" {"type" "string"
                                             "enum" ["assistant"]}
                                     "content" {"anyOf" [{"type" "string"}
                                                         {"type" "null"}]}
                                     "tool_calls" {"type" "array"
                                                   "items" {"type" "object"
                                                            "required" ["id" "type" "function"]
                                                            "properties"
                                                            {"id" {"type" "string"}
                                                             "type" {"type" "string"
                                                                     "enum" ["function"]}
                                                             "function" {"type" "object"
                                                                         "required" ["name" "arguments"]
                                                                         "properties"
                                                                         {"name" {"type" "string"}
                                                                          "arguments" {"type" "string"}}}}}}
                                     "refusal" {"anyOf" [{"type" "string"}
                                                         {"type" "null"}]}}}}}}
    "usage" {"type" "object"
             "properties"
             {"prompt_tokens" {"type" "integer"}
              "completion_tokens" {"type" "integer"}
              "total_tokens" {"type" "integer"}}}}})

(def request-schema
  "Simplified request schema for validation (no $refs).
   Based on CreateChatCompletionRequest but with refs resolved."
  {"type" "object"
   "required" ["messages" "model"]
   "properties"
   {"model" {"type" "string"}
    "messages" {"type" "array"
                "minItems" 1
                "items" {"type" "object"
                         "required" ["role"]
                         "properties"
                         {"role" {"type" "string"
                                  "enum" ["system" "user" "assistant" "tool"]}
                          "content" {"anyOf" [{"type" "string"}
                                              {"type" "null"}
                                              {"type" "array"}]}
                          "tool_calls" {"type" "array"}
                          "tool_call_id" {"type" "string"}}}}
    "tools" {"type" "array"
             "items" {"type" "object"
                      "required" ["type" "function"]
                      "properties"
                      {"type" {"type" "string"
                               "enum" ["function"]}
                       "function" {"type" "object"
                                   "required" ["name"]
                                   "properties"
                                   {"name" {"type" "string"}
                                    "description" {"type" "string"}
                                    "parameters" {"type" "object"}}}}}}
    "stream" {"type" "boolean"}
    "max_tokens" {"type" "integer"}
    "temperature" {"type" "number"}
    "top_p" {"type" "number"}}})

(def tool-call-item-schema
  "Schema for a single tool_call item in response."
  {"type" "object"
   "required" ["id" "type" "function"]
   "properties"
   {"id" {"type" "string"}
    "type" {"type" "string"
            "enum" ["function"]}
    "function" {"type" "object"
                "required" ["name" "arguments"]
                "properties"
                {"name" {"type" "string"}
                 "arguments" {"type" "string"}}}}})

;;------------------------------------------------------------------------------
;; Utility Functions

(defn schema-info
  "Return metadata about loaded schemas"
  []
  {:openapi-version (get @openapi-spec "openapi")
   :api-title (get-in @openapi-spec ["info" "title"])
   :api-version (get-in @openapi-spec ["info" "version"])
   :schema-count (count (get-in @openapi-spec ["components" "schemas"]))})
