(ns claij.fsm.bmad-converter-fsm
  "Meta-FSM that converts BMAD workflows to CLAIJ FSMs.
  
  Input: Path to a BMAD workflow directory
  Output: CLAIJ FSM definition (as data, not code)
  
  This FSM automates the transformation patterns documented in:
  docs/bmad-to-fsm-transformation-patterns.md"
  (:require
   [claij.schema :refer [def-fsm]]))

;; ============================================================================
;; Schemas
;; ============================================================================

(def bmad-converter-schemas
  "Schema definitions for BMAD converter flow."

  {;; Entry: user provides BMAD workflow path
   "entry"
   {"type" "object"
    "description" "Path to BMAD workflow to convert"
    "additionalProperties" false
    "required" ["id" "workflow-path"]
    "properties"
    {"id" {"const" ["start" "init"]}
     "workflow-path" {"type" "string"
                      "description" "Path to BMAD workflow directory (e.g., _bmad/bmm/workflows/bmad-quick-flow/create-tech-spec/)"}}}

   ;; Init → analyze
   "init-to-analyze"
   {"type" "object"
    "additionalProperties" false
    "required" ["id" "workflow-path"]
    "properties"
    {"id" {"const" ["init" "analyze-workflow"]}
     "workflow-path" {"type" "string"}}}

   ;; Analyze → extract-persona
   "analyze-to-persona"
   {"type" "object"
    "additionalProperties" false
    "required" ["id" "workflow-metadata" "agent-file" "step-files"]
    "properties"
    {"id" {"const" ["analyze-workflow" "extract-persona"]}
     "workflow-metadata" {"type" "object"
                          "properties"
                          {"id" {"type" "string"}
                           "name" {"type" "string"}
                           "description" {"type" "string"}}}
     "agent-file" {"type" "string"
                   "description" "Path to agent .md file"}
     "step-files" {"type" "array"
                   "items" {"type" "string"}
                   "description" "Paths to step-NN-*.md files"}
     "has-standards" {"type" "boolean"}}}

   ;; Extract-persona → extract-standards (or skip to build-schemas)
   "persona-to-standards"
   {"type" "object"
    "additionalProperties" false
    "required" ["id" "persona-text" "step-files" "has-standards"]
    "properties"
    {"id" {"const" ["extract-persona" "extract-standards"]}
     "persona-text" {"type" "string"}
     "step-files" {"type" "array" "items" {"type" "string"}}
     "has-standards" {"type" "boolean"}}}

   ;; Extract-standards → build-schemas
   "standards-to-schemas"
   {"type" "object"
    "additionalProperties" false
    "required" ["id" "persona-text" "standards-text" "step-files" "workflow-metadata"]
    "properties"
    {"id" {"const" ["extract-standards" "build-schemas"]}
     "persona-text" {"type" "string"}
     "standards-text" {"type" "string"}
     "step-files" {"type" "array" "items" {"type" "string"}}
     "workflow-metadata" {"type" "object"}}}

   ;; Build-schemas → build-states
   "schemas-to-states"
   {"type" "object"
    "additionalProperties" false
    "required" ["id" "schemas" "step-files" "persona-text"]
    "properties"
    {"id" {"const" ["build-schemas" "build-states"]}
     "schemas" {"type" "object"
                "description" "JSON Schema definitions for transitions"}
     "step-files" {"type" "array" "items" {"type" "string"}}
     "persona-text" {"type" "string"}
     "standards-text" {"type" "string"}}}

   ;; Build-states → assemble-fsm
   "states-to-assemble"
   {"type" "object"
    "additionalProperties" false
    "required" ["id" "states" "workflow-metadata" "persona-text" "schemas"]
    "properties"
    {"id" {"const" ["build-states" "assemble-fsm"]}
     "states" {"type" "array"
               "items" {"type" "object"}
               "description" "FSM state definitions"}
     "workflow-metadata" {"type" "object"}
     "persona-text" {"type" "string"}
     "standards-text" {"type" "string"}
     "schemas" {"type" "object"}}}

   ;; Assemble → validate
   "assemble-to-validate"
   {"type" "object"
    "additionalProperties" false
    "required" ["id" "fsm-data"]
    "properties"
    {"id" {"const" ["assemble-fsm" "validate"]}
     "fsm-data" {"type" "object"
                 "description" "Complete CLAIJ FSM definition"}}}

   ;; Validate → end
   "exit"
   {"type" "object"
    "additionalProperties" false
    "required" ["id" "fsm-data" "validation-results"]
    "properties"
    {"id" {"const" ["validate" "end"]}
     "fsm-data" {"type" "object"
                 "description" "Validated CLAIJ FSM definition"}
     "validation-results" {"type" "object"
                           "properties"
                           {"can-load" {"type" "boolean"}
                            "can-register" {"type" "boolean"}
                            "can-start" {"type" "boolean"}
                            "state-count" {"type" "integer"}
                            "transition-count" {"type" "integer"}}}}}})

;; ============================================================================
;; FSM Definition
;; ============================================================================

(def-fsm
  bmad-converter-fsm

  {"id" "bmad-converter"
   "schemas" bmad-converter-schemas
   "prompts" ["You are a meta-FSM that converts BMAD workflows to CLAIJ FSMs."
              "You have access to file reading tools and can analyze BMAD structure."
              "Your goal is to produce a working CLAIJ FSM data structure (not code!)."]

   "states"
   [;; ======================================================================
    ;; INIT: Get workflow path, explain process
    ;; ======================================================================
    {"id" "init"
     "action" "llm"
     "prompts"
     ["You are starting a BMAD to CLAIJ FSM conversion."
      ""
      "**Your Task:**"
      "1. Acknowledge the workflow path from the user: {{workflow-path}}"
      "2. Explain what you'll do:"
      "   - Analyze workflow structure"
      "   - Extract agent persona"
      "   - Extract standards (if present)"
      "   - Build JSON schemas for transitions"
      "   - Convert steps to FSM states"
      "   - Assemble complete FSM"
      "   - Validate it works"
      ""
      "3. Confirm you're ready to proceed"
      ""
      "Keep it brief and professional."
      ""
      "Output using transition:"
      "{\"id\": [\"init\", \"analyze-workflow\"],"
      " \"workflow-path\": \"{{workflow-path}}\"}"]}

    ;; ======================================================================
    ;; ANALYZE WORKFLOW: Read workflow.md, find agent, list steps
    ;; ======================================================================
    {"id" "analyze-workflow"
     "action" "llm"
     "prompts"
     ["**Step 1/7: Analyze Workflow Structure**"
      ""
      "Workflow path: {{workflow-path}}"
      ""
      "**Your Task:**"
      ""
      "1. Read workflow.md:"
      "   - Extract frontmatter (name, description, main_config)"
      "   - Find agent reference (look for 'agent:' in frontmatter)"
      "   - Check for standards sections"
      ""
      "2. List step files:"
      "   - Use bash: ls {{workflow-path}}/steps/step-*.md | sort"
      "   - You need these in order: step-01, step-02, etc."
      ""
      "3. Locate agent file:"
      "   - Usually in _bmad/bmm/agents/"
      "   - Common ones: quick-flow-solo-dev.md, brownfield-flow-agent.md"
      ""
      "4. Extract metadata for FSM:"
      "   - Generate FSM id (kebab-case from workflow name)"
      "   - Use workflow name and description"
      ""
      "**Output Format:**"
      "{\"id\": [\"analyze-workflow\", \"extract-persona\"],"
      " \"workflow-metadata\": {\"id\": \"...\", \"name\": \"...\", \"description\": \"...\"},"
      " \"agent-file\": \"/path/to/agent.md\","
      " \"step-files\": [\"/path/to/step-01.md\", \"/path/to/step-02.md\", ...],"
      " \"has-standards\": true/false}"]}

    ;; ======================================================================
    ;; EXTRACT PERSONA: Read agent file, extract persona XML
    ;; ======================================================================
    {"id" "extract-persona"
     "action" "llm"
     "prompts"
     ["**Step 2/7: Extract Agent Persona**"
      ""
      "Agent file: {{agent-file}}"
      ""
      "**Your Task:**"
      ""
      "1. Read the agent file"
      ""
      "2. Extract persona sections (usually XML-formatted):"
      "   - <persona> or **Persona:** section"
      "   - Role/identity"
      "   - Communication style"
      "   - Key principles"
      ""
      "3. Format as a clean string constant"
      "   - Remove XML tags if present"
      "   - Keep formatting readable"
      "   - This will become a `def` constant in the FSM"
      ""
      "**Output Format:**"
      "{\"id\": [\"extract-persona\", \"extract-standards\"],"
      " \"persona-text\": \"Agent Name: Description...\\n\\nCommunication Style: ...\\n\\nPrinciples:\\n- ...\","
      " \"step-files\": {{step-files}},"
      " \"has-standards\": {{has-standards}}}"]}

    ;; ======================================================================
    ;; EXTRACT STANDARDS: Find standards/requirements in workflow
    ;; ======================================================================
    {"id" "extract-standards"
     "action" "llm"
     "prompts"
     ["**Step 3/7: Extract Standards**"
      ""
      "Has standards: {{has-standards}}"
      ""
      "**Your Task:**"
      ""
      "If has-standards is true:"
      "1. Read workflow.md and step files"
      "2. Look for sections like:"
      "   - 'Ready for Development'"
      "   - 'Definition of Done'"
      "   - 'Quality Standards'"
      "   - 'Acceptance Criteria Format'"
      ""
      "3. Extract as clean text"
      "   - Keep formatting"
      "   - This becomes another `def` constant"
      ""
      "If has-standards is false:"
      "1. Set standards-text to empty string"
      ""
      "**Output Format:**"
      "{\"id\": [\"extract-standards\", \"build-schemas\"],"
      " \"persona-text\": \"{{persona-text}}\","
      " \"standards-text\": \"...\" or \"\","
      " \"step-files\": {{step-files}},"
      " \"workflow-metadata\": {{workflow-metadata}}}"]}

    ;; ======================================================================
    ;; BUILD SCHEMAS: Create JSON schemas for each transition
    ;; ======================================================================
    {"id" "build-schemas"
     "action" "llm"
     "prompts"
     ["**Step 4/7: Build JSON Schemas**"
      ""
      "Step files: {{step-files}}"
      ""
      "**Your Task:**"
      ""
      "1. For each step file, read it and identify:"
      "   - What context it needs as input"
      "   - What context it produces as output"
      ""
      "2. Create JSON schemas following this pattern:"
      ""
      "Entry schema (start → first-state):"
      "{\"entry\": {\"type\": \"object\","
      "             \"required\": [\"id\", \"message\"],"
      "             \"properties\": {\"id\": {\"const\": [\"start\", \"init\"]},"
      "                            \"message\": {\"type\": \"string\"}}}}"
      ""
      "Step transition schemas:"
      "{\"step-1-to-2\": {\"type\": \"object\","
      "                   \"required\": [\"id\", \"captured-data\"],"
      "                   \"properties\": {\"id\": {\"const\": [\"state-1\", \"state-2\"]},"
      "                                  \"captured-data\": {\"type\": \"string\"}}}}"
      ""
      "Exit schema (last-state → end):"
      "{\"exit\": {\"type\": \"object\","
      "           \"required\": [\"id\", \"final-output\"],"
      "           \"properties\": {\"id\": {\"const\": [\"review\", \"end\"]},"
      "                          \"final-output\": {\"type\": \"string\"}}}}"
      ""
      "3. Return complete schemas map"
      ""
      "**REFERENCE:** Look at bmad_quick_spec_flow.clj for schema examples"
      ""
      "**Output Format:**"
      "{\"id\": [\"build-schemas\", \"build-states\"],"
      " \"schemas\": {\"entry\": {...}, \"step-1-to-2\": {...}, \"exit\": {...}},"
      " \"step-files\": {{step-files}},"
      " \"persona-text\": \"{{persona-text}}\","
      " \"standards-text\": \"{{standards-text}}\"}"]}

    ;; ======================================================================
    ;; BUILD STATES: Convert BMAD steps to FSM state definitions
    ;; ======================================================================
    {"id" "build-states"
     "action" "llm"
     "prompts"
     ["**Step 5/7: Build FSM States**"
      ""
      "Step files: {{step-files}}"
      "Persona: {{persona-text}}"
      ""
      "**Your Task:**"
      ""
      "1. For each step file:"
      "   - Read the step content"
      "   - Extract instructions and guidance"
      "   - Convert to FSM state prompts"
      ""
      "2. Create state definitions following this pattern:"
      ""
      "[{\"id\": \"init\","
      "  \"action\": \"llm\","
      "  \"prompts\": [\"You are starting...\","
      "               \"\","
      "               \"**Your Task:**\","
      "               \"1. Do this\","
      "               \"2. Do that\","
      "               \"\","
      "               \"Output using transition:\","
      "               \"{\\\"id\\\": [\\\"init\\\", \\\"next-state\\\"], ...}\"]},"
      " {\"id\": \"next-state\","
      "  \"action\": \"llm\","
      "  \"prompts\": [...]},"
      " ..., "
      " {\"id\": \"end\","
      "  \"action\": \"end\"}]"
      ""
      "3. Key principles:"
      "   - Extract step instructions verbatim when helpful"
      "   - Add context variables like {{user-request}}, {{title}}, etc."
      "   - Include persona in first state or as global prompt"
      "   - Include standards in relevant states (generation, review)"
      "   - Tell LLM how to use transitions in prompts"
      "   - Last state should always be {\"id\": \"end\", \"action\": \"end\"}"
      ""
      "**REFERENCE:** Look at bmad_quick_spec_flow.clj for state examples"
      ""
      "**Output Format:**"
      "{\"id\": [\"build-states\", \"assemble-fsm\"],"
      " \"states\": [{...}, {...}, ...],"
      " \"workflow-metadata\": {{workflow-metadata}},"
      " \"persona-text\": \"{{persona-text}}\","
      " \"standards-text\": \"{{standards-text}}\","
      " \"schemas\": {{schemas}}}"]}

    ;; ======================================================================
    ;; ASSEMBLE FSM: Combine all pieces into final FSM data structure
    ;; ======================================================================
    {"id" "assemble-fsm"
     "action" "llm"
     "prompts"
     ["**Step 6/7: Assemble Complete FSM**"
      ""
      "**Your Task:**"
      ""
      "Combine all the pieces into a complete CLAIJ FSM data structure:"
      ""
      "{\"id\": \"{{workflow-metadata.id}}\","
      " \"schemas\": {{schemas}},"
      " \"prompts\": [\"{{persona-text}}\","
      "              \"Additional context...\"],"
      " \"states\": {{states}},"
      " \"xitions\": [{\"id\": [\"start\", \"init\"],"
      "               \"schema\": {\"$ref\": \"#/$defs/entry\"}},"
      "              {\"id\": [\"init\", \"next-state\"],"
      "               \"schema\": {\"$ref\": \"#/$defs/init-to-next\"}},"
      "              ...,"
      "              {\"id\": [\"last-state\", \"end\"],"
      "               \"schema\": {\"$ref\": \"#/$defs/exit\"}}]}"
      ""
      "**Critical:**"
      "- One transition for each state change"
      "- Transitions reference schemas using $ref"
      "- First transition is ALWAYS [\"start\", \"init\"] or [\"start\", first-state-id]"
      "- Last transition ALWAYS goes to \"end\""
      ""
      "**Output Format:**"
      "{\"id\": [\"assemble-fsm\", \"validate\"],"
      " \"fsm-data\": {\"id\": \"...\", \"schemas\": {...}, \"prompts\": [...], \"states\": [...], \"xitions\": [...]}}"]}

    ;; ======================================================================
    ;; VALIDATE: Test that FSM loads and can be started
    ;; ======================================================================
    {"id" "validate"
     "action" "llm"
     "prompts"
     ["**Step 7/7: Validate FSM**"
      ""
      "FSM data: {{fsm-data}}"
      ""
      "**Your Task:**"
      ""
      "Test that the FSM works:"
      ""
      "1. Try to register it (simulated):"
      "   - Check it has required fields: id, schemas, states, xitions"
      "   - Check states array is not empty"
      "   - Check xitions array is not empty"
      "   - Check first transition is [\"start\", ...]"
      "   - Check last state has \"action\": \"end\""
      ""
      "2. Count elements:"
      "   - state-count: length of states array"
      "   - transition-count: length of xitions array"
      ""
      "3. Report results"
      ""
      "**Output Format:**"
      "{\"id\": [\"validate\", \"end\"],"
      " \"fsm-data\": {{fsm-data}},"
      " \"validation-results\": {\"can-load\": true,"
      "                         \"can-register\": true,"
      "                         \"can-start\": true,"
      "                         \"state-count\": N,"
      "                         \"transition-count\": N}}"]}

    ;; ======================================================================
    ;; END: Terminal state
    ;; ======================================================================
    {"id" "end"
     "action" "end"}]

   "xitions"
   [;; Entry
    {"id" ["start" "init"]
     "schema" {"$ref" "#/$defs/entry"}}

    ;; Init → analyze
    {"id" ["init" "analyze-workflow"]
     "schema" {"$ref" "#/$defs/init-to-analyze"}}

    ;; Analyze → extract-persona
    {"id" ["analyze-workflow" "extract-persona"]
     "schema" {"$ref" "#/$defs/analyze-to-persona"}}

    ;; Extract-persona → extract-standards
    {"id" ["extract-persona" "extract-standards"]
     "schema" {"$ref" "#/$defs/persona-to-standards"}}

    ;; Extract-standards → build-schemas
    {"id" ["extract-standards" "build-schemas"]
     "schema" {"$ref" "#/$defs/standards-to-schemas"}}

    ;; Build-schemas → build-states
    {"id" ["build-schemas" "build-states"]
     "schema" {"$ref" "#/$defs/schemas-to-states"}}

    ;; Build-states → assemble-fsm
    {"id" ["build-states" "assemble-fsm"]
     "schema" {"$ref" "#/$defs/states-to-assemble"}}

    ;; Assemble → validate
    {"id" ["assemble-fsm" "validate"]
     "schema" {"$ref" "#/$defs/assemble-to-validate"}}

    ;; Validate → end
    {"id" ["validate" "end"]
     "schema" {"$ref" "#/$defs/exit"}}]})

;; ============================================================================
;; Inspection
;; ============================================================================

(comment
  ;; Inspect FSM
  bmad-converter-fsm

  ;; Check states
  (count (get bmad-converter-fsm "states"))
  ;; => 9 states

  (mapv #(get % "id") (get bmad-converter-fsm "states"))
  ;; => ["init" "analyze-workflow" "extract-persona" "extract-standards"
  ;;     "build-schemas" "build-states" "assemble-fsm" "validate" "end"]

  ;; Check transitions
  (mapv #(get % "id") (get bmad-converter-fsm "xitions"))
  ;; => [["start" "init"] ["init" "analyze-workflow"] ...]
  )
