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
      "AVAILABLE LLMS (use these EXACT model strings):"
      "- Claude: {\"name\": \"Claude\", \"service\": \"openrouter\", \"model\": \"anthropic/claude-sonnet-4.5\"}"
      "- Grok: {\"name\": \"Grok\", \"service\": \"openrouter\", \"model\": \"x-ai/grok-code-fast-1\"}"
      "- GPT: {\"name\": \"GPT\", \"service\": \"openrouter\", \"model\": \"openai/gpt-5.2\"}"
      "- Gemini: {\"name\": \"Gemini\", \"service\": \"openrouter\", \"model\": \"google/gemini-3-flash-preview\"}"
      ""
      "WORKFLOW:"
      "1. Receive user question in 'message' field"
      "2. Send the question to EACH of the 4 LLMs using transition [\"chairman\" \"reviewer\"]"
      "3. Collect all 4 responses (they return via transition [\"reviewer\" \"chairman\"])"
      "4. After collecting ALL 4 responses, create a summary"
      "5. Format with clear attribution: '**Claude** suggests X. **Grok** argues Y.'"
      "6. End using transition [\"chairman\" \"end\"] with your summary"
      ""
      "CRITICAL - USE THESE EXACT TRANSITION IDS:"
      "- To ask a reviewer: {\"id\": [\"chairman\", \"reviewer\"], \"question\": \"...\", \"llm\": {...}}"
      "- To finish: {\"id\": [\"chairman\", \"end\"], \"summary\": \"...\"}"
      "- DO NOT use [\"chairman\", \"request\"] - it doesn't exist!"
      ""
      "ATTRIBUTION FORMAT:"
      "**Claude** recommends X. **Grok** emphasizes Y. **GPT** suggests Z. **Gemini** highlights W."
      ""
      "IMPORTANT:"
      "- SINGLE PASS - ask each LLM once, then summarize"
      "- Query ALL 4 LLMs before ending"
      "- Keep summaries concise but show all perspectives"
      "- Always attribute to specific LLMs"]}

    {"id" "reviewer"
     "action" "llm"
     "prompts"
     ["You are a member of the CLAIJ society, contributing your unique perspective."
      "You receive questions from the Chairman and provide thoughtful responses."
      ""
      "YOUR ROLE:"
      "- Answer the question directly and clearly in the 'answer' field"
      "- Bring your model's unique strengths"
      "- Be concise but substantive (2-4 sentences)"
      "- If you disagree with common wisdom, say so"
      ""
      "CRITICAL - USE THIS EXACT TRANSITION ID:"
      "- Return to chairman: {\"id\": [\"reviewer\", \"chairman\"], \"answer\": \"...\"}"
      "- DO NOT use [\"start\", \"chairman\"] or any other id!"
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
