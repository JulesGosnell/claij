(ns claij.fsm.society-fsm
  "Simple society FSM for demonstrating multi-LLM collaboration.
   
   Takes plain text queries and distributes them across multiple LLMs
   (Claude, Grok, GPT, Gemini), then summarizes their responses with
   clear attribution.
   
   Perfect for Open WebUI and other chat interfaces - just ask a question
   and get perspectives from multiple AI models!"
  (:require
   [claij.schema :refer [def-fsm]]))

(def society-schemas
  {"entry"
   {:type "object"
    :properties {"message" {:type "string"
                            :description "The user's question or message"}}
    :required ["message"]
    :additionalProperties false}

   "request"
   {:type "object"
    :properties {"question" {:type "string"
                             :description "The question to ask this LLM"}
                 "llm" {:type "object"
                        :properties {"name" {:type "string"
                                             :description "Human-friendly name (e.g. 'Claude', 'Grok')"}
                                     "service" {:type "string"}
                                     "model" {:type "string"}}
                        :required ["name" "service" "model"]}}
    :required ["question" "llm"]
    :additionalProperties false}

   "response"
   {:type "object"
    :properties {"answer" {:type "string"
                           :description "The LLM's response to the question"}
                 "notes" {:type "string"
                          :description "Optional notes about the response"}}
    :required ["answer"]
    :additionalProperties false}

   "summary"
   {:type "object"
    :properties {"summary" {:type "string"
                            :description "Consolidated summary with attribution (e.g. '**Claude**: suggests X. **Grok**: argues Y.')"}
                 "perspectives" {:type "array"
                                 :description "List of individual perspectives (optional, for structure)"
                                 :items {:type "object"
                                         :properties {"llm" {:type "string"}
                                                      "point" {:type "string"}}}}}
    :required ["summary"]
    :additionalProperties false}})

(def-fsm
  society-fsm
  {"id" "society"
   "schemas" society-schemas
   "prompts" ["You are CLAIJ, a society of LLMs working together to provide helpful responses."
              "Each LLM brings unique perspectives and capabilities to answer questions comprehensively."]
   "states"
   [{"id" "chairman"
     "action" "llm"
     "prompts"
     ["You are the Chairman of CLAIJ, coordinating a society of LLMs."
      "When you receive a user's question, you act as their proxy to consult the society."
      ""
      "YOUR ROLE:"
      "- Answer to the name 'CLAIJ' (not 'Chairman' or your individual model name)"
      "- Distribute the user's question to ALL available LLMs in the society"
      "- Each LLM will provide their perspective independently"
      "- After gathering all responses, create a clear summary with attribution"
      ""
      "AVAILABLE LLMS:"
      "You have access to these LLMs via OpenRouter:"
      "- Claude (Anthropic): {\"service\": \"openrouter\", \"model\": \"anthropic/claude-sonnet-4\"}"
      "- Grok (xAI): {\"service\": \"openrouter\", \"model\": \"x-ai/grok-2-1212\"}"
      "- GPT (OpenAI): {\"service\": \"openrouter\", \"model\": \"openai/gpt-4o\"}"
      "- Gemini (Google): {\"service\": \"openrouter\", \"model\": \"google/gemini-2.0-flash-exp:free\"}"
      ""
      "WORKFLOW:"
      "1. Receive user question in 'message' field"
      "2. Send the question to each LLM using the 'request' transition"
      "3. Collect all responses (they'll come back via 'response' transition)"
      "4. Create a summary showing what each LLM contributed"
      "5. Format attribution clearly (e.g., '**Claude** suggests... **Grok** argues... **GPT** adds...')"
      "6. End with your summary using the 'summary' transition"
      ""
      "ATTRIBUTION FORMAT:"
      "Make it clear who said what! For example:"
      "\"**Claude** recommends using functional patterns. **Grok** emphasizes performance optimization. **GPT** suggests adding error handling. **Gemini** highlights testing strategies.\""
      ""
      "IMPORTANT:"
      "- This is a SINGLE PASS - consult each LLM once, then summarize"
      "- No iteration or agreement needed"
      "- Keep summaries concise but show diverse perspectives"
      "- Always attribute points to specific LLMs"]}

    {"id" "reviewer"
     "action" "llm"
     "prompts"
     ["You are a member of the CLAIJ society, contributing your unique perspective."
      "When you receive a question, provide your honest, thoughtful response."
      ""
      "YOUR ROLE:"
      "- Answer the question directly and clearly"
      "- Bring your model's unique strengths (reasoning, creativity, technical depth, etc.)"
      "- Be concise but substantive"
      "- If you disagree with common wisdom, say so"
      ""
      "The Chairman will combine your response with others to provide a comprehensive answer."]}

    {"id" "end"
     "action" "end"}]

   "xitions"
   [{"id" ["start" "chairman"]
     "schema" {"$ref" "#/$defs/entry"}}

    {"id" ["chairman" "reviewer"]
     "prompts" []
     "schema" {"$ref" "#/$defs/request"}}

    {"id" ["reviewer" "chairman"]
     "prompts" []
     "schema" {"$ref" "#/$defs/response"}}

    {"id" ["chairman" "end"]
     "prompts" []
     "schema" {"$ref" "#/$defs/summary"}}]})
