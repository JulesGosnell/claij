(ns claij.fsm.bmad-quick-spec-flow
  "Quick Spec Flow FSM - Manual conversion from BMAD workflow.
  
  This is a POC conversion of BMAD's create-tech-spec workflow to demonstrate
  that BMAD's battle-tested workflows can be made fully executable with CLAIJ.
  
  Original BMAD workflow:
  _bmad/bmm/workflows/bmad-quick-flow/create-tech-spec/
  
  Agent: Barry (Quick Flow Solo Dev)
  From: _bmad/bmm/agents/quick-flow-solo-dev.md
  
  Transformation patterns documented for meta-FSM builder (#158)."
  (:require
   [claij.schema :refer [def-fsm]]))

;; ============================================================================
;; BMAD Agent Persona (Extracted from quick-flow-solo-dev.md)
;; ============================================================================

(def barry-persona
  "Barry: Elite Full-Stack Developer + Quick Flow Specialist
  
  Handles Quick Flow - from tech spec creation through implementation.
  Minimum ceremony, lean artifacts, ruthless efficiency.
  
  Communication Style: Direct, confident, and implementation-focused. 
  Uses tech slang (e.g., refactor, patch, extract, spike) and gets straight 
  to the point. No fluff, just results. Stays focused on the task at hand.
  
  Principles:
  - Planning and execution are two sides of the same coin
  - Specs are for building, not bureaucracy
  - Code that ships is better than perfect code that doesn't
  - If project-context.md exists, follow it. If absent, proceed without.")

;; ============================================================================
;; Ready for Development Standard (Extracted from workflow.md)
;; ============================================================================

(def ready-for-dev-standard
  "A specification is considered 'Ready for Development' ONLY if it meets:
  
  - **Actionable**: Every task has a clear file path and specific action
  - **Logical**: Tasks are ordered by dependency (lowest level first)
  - **Testable**: All ACs follow Given/When/Then and cover happy path and edge cases
  - **Complete**: All investigation results are inlined; no placeholders or 'TBD'
  - **Self-Contained**: A fresh agent can implement the feature without reading workflow history")

;; ============================================================================
;; JSON Schemas
;; ============================================================================

(def bmad-quick-spec-schemas
  "Schema definitions for Quick Spec Flow events."

  {;; Entry: user provides feature request
   "entry"
   {"type" "object"
    "description" "Initial feature request from user"
    "additionalProperties" false
    "required" ["id" "message"]
    "properties"
    {"id" {"const" ["start" "init"]}
     "message" {"type" "string"
                "description" "What feature or capability to build"}}}

   ;; Init → understand: greeting sent, request captured
   "init-to-understand"
   {"type" "object"
    "additionalProperties" false
    "required" ["id" "user-request"]
    "properties"
    {"id" {"const" ["init" "understand-requirements"]}
     "user-request" {"type" "string"
                     "description" "User's feature request"}}}

   ;; Understand → investigate: requirements captured
   "understand-to-investigate"
   {"type" "object"
    "additionalProperties" false
    "required" ["id" "title" "problem-statement" "solution" "scope"]
    "properties"
    {"id" {"const" ["understand-requirements" "investigate-codebase"]}
     "title" {"type" "string"}
     "problem-statement" {"type" "string"}
     "solution" {"type" "string"}
     "scope" {"type" "object"
              "properties"
              {"in-scope" {"type" "string"}
               "out-of-scope" {"type" "string"}}}}}

   ;; Investigate → generate: technical context gathered
   "investigate-to-generate"
   {"type" "object"
    "additionalProperties" false
    "required" ["id" "tech-stack" "code-patterns" "files-to-modify" "test-patterns"]
    "properties"
    {"id" {"const" ["investigate-codebase" "generate-plan"]}
     "tech-stack" {"type" "array" "items" {"type" "string"}}
     "code-patterns" {"type" "array" "items" {"type" "string"}}
     "files-to-modify" {"type" "array" "items" {"type" "string"}}
     "test-patterns" {"type" "array" "items" {"type" "string"}}}}

   ;; Generate → review: implementation plan created
   "generate-to-review"
   {"type" "object"
    "additionalProperties" false
    "required" ["id" "generated-spec"]
    "properties"
    {"id" {"const" ["generate-plan" "review"]}
     "generated-spec" {"type" "string"
                       "description" "Complete tech spec with tasks and ACs"}}}

   ;; Review → end: spec finalized
   "exit"
   {"type" "object"
    "additionalProperties" false
    "required" ["id" "final-spec-path" "task-count" "ac-count" "files-count"]
    "properties"
    {"id" {"const" ["review" "end"]}
     "final-spec-path" {"type" "string"}
     "task-count" {"type" "integer"}
     "ac-count" {"type" "integer"}
     "files-count" {"type" "integer"}}}})

;; ============================================================================
;; FSM Definition
;; ============================================================================

(def-fsm
  bmad-quick-spec-flow

  {"id" "bmad-quick-spec-flow"
   "schemas" bmad-quick-spec-schemas
   "prompts" [barry-persona
              "You are helping a user create an implementation-ready technical specification."]

   "states"
   [;; ======================================================================
    ;; INIT: Load config, greet user, get feature request
    ;; ======================================================================
    {"id" "init"
     "action" "llm"
     "prompts"
     ["You are starting a new Quick Spec Flow session."
      ""
      "**Your Role:**"
      "- Greet the user warmly and professionally"
      "- Ask what feature or capability they want to build"
      "- Keep it brief - just get the initial description"
      "- Don't ask detailed questions yet"
      ""
      "Look for the user's message in the 'message' field."
      ""
      "Output using transition {\"id\": [\"init\", \"understand-requirements\"], \"user-request\": \"...\"}"]}

    ;; ======================================================================
    ;; UNDERSTAND REQUIREMENTS: Quick scan, informed questions, capture core understanding
    ;; ======================================================================
    {"id" "understand-requirements"
     "action" "llm"
     "prompts"
     ["**Step 1 of 4: Analyze Requirement Delta**"
      ""
      "The user wants to build: {{user-request}}"
      ""
      "**Your Task:**"
      ""
      "1. **Quick Orient Scan** (< 30 seconds):"
      "   - Search for relevant files/classes/functions the user mentioned"
      "   - Skim the structure (don't deep-dive yet - that's next step)"
      "   - Check for project-context.md"
      "   - Note: tech stack, obvious patterns, file locations"
      "   - Build mental model of the likely landscape"
      ""
      "2. **Ask Informed Questions**:"
      "   Instead of generic questions, ask specific ones based on what you found:"
      "   - Reference specific files/classes/patterns you found"
      "   - Ask about architectural decisions"
      "   - If no code found, ask about intended patterns"
      ""
      "3. **Capture Core Understanding** and confirm:"
      "   - **Title**: Clear, concise name for this work"
      "   - **Problem Statement**: What problem are we solving?"
      "   - **Solution**: High-level approach (1-2 sentences)"
      "   - **In Scope**: What's included"
      "   - **Out of Scope**: What's explicitly NOT included"
      ""
      "Present your questions and understanding as natural dialogue."
      "End with a clear confirmation request."
      ""
      "Output using transition:"
      "{\"id\": [\"understand-requirements\", \"investigate-codebase\"],"
      " \"title\": \"...\","
      " \"problem-statement\": \"...\","
      " \"solution\": \"...\","
      " \"scope\": {\"in-scope\": \"...\", \"out-of-scope\": \"...\"}}"]}

    ;; ======================================================================
    ;; INVESTIGATE CODEBASE: Deep code investigation, map technical constraints
    ;; ======================================================================
    {"id" "investigate-codebase"
     "action" "llm"
     "prompts"
     ["**Step 2 of 4: Map Technical Constraints & Anchor Points**"
      ""
      "**Confirmed Understanding:**"
      "- Title: {{title}}"
      "- Problem: {{problem-statement}}"
      "- Solution: {{solution}}"
      "- Scope: {{scope.in-scope}}"
      ""
      "**Your Task:**"
      ""
      "Execute deep code investigation:"
      ""
      "1. **Read and Analyze Code**:"
      "   For each relevant file:"
      "   - Read the complete file(s)"
      "   - Identify patterns, conventions, coding style"
      "   - Note dependencies and imports"
      "   - Find related test files"
      ""
      "2. **If NO relevant code found (Clean Slate)**:"
      "   - Identify target directory where feature should live"
      "   - Scan parent directories for architectural context"
      "   - Identify standard project utilities or boilerplate"
      "   - Document this as 'Confirmed Clean Slate'"
      ""
      "3. **Document Technical Context**:"
      "   Capture and confirm:"
      "   - **Tech Stack**: Languages, frameworks, libraries"
      "   - **Code Patterns**: Architecture patterns, naming conventions, file structure"
      "   - **Files to Modify/Create**: Specific files that need changes or creation"
      "   - **Test Patterns**: How tests are structured, test frameworks used"
      ""
      "4. **Check for project-context.md**:"
      "   If exists, extract patterns and conventions"
      ""
      "Present findings as a structured summary with clear sections."
      ""
      "Output using transition:"
      "{\"id\": [\"investigate-codebase\", \"generate-plan\"],"
      " \"tech-stack\": [\"...\", \"...\"],"
      " \"code-patterns\": [\"...\", \"...\"],"
      " \"files-to-modify\": [\"...\", \"...\"],"
      " \"test-patterns\": [\"...\", \"...\"]}"]}

    ;; ======================================================================
    ;; GENERATE PLAN: Create implementation tasks and acceptance criteria
    ;; ======================================================================
    {"id" "generate-plan"
     "action" "llm"
     "prompts"
     [ready-for-dev-standard
      ""
      "**Step 3 of 4: Generate Implementation Plan**"
      ""
      "**Context:**"
      "- Title: {{title}}"
      "- Problem: {{problem-statement}}"
      "- Solution: {{solution}}"
      "- Tech Stack: {{tech-stack}}"
      "- Code Patterns: {{code-patterns}}"
      "- Files to Modify: {{files-to-modify}}"
      "- Test Patterns: {{test-patterns}}"
      ""
      "**Your Task:**"
      ""
      "Generate specific implementation tasks:"
      ""
      "1. **Task Breakdown**:"
      "   - Each task should be discrete, completable unit of work"
      "   - Order logically (dependencies first)"
      "   - Include specific files to modify in each task"
      "   - Be explicit about what changes to make"
      ""
      "   Format:"
      "   ```"
      "   - [ ] Task N: Clear action description"
      "     - File: `path/to/file.ext`"
      "     - Action: Specific change to make"
      "     - Notes: Any implementation details"
      "   ```"
      ""
      "2. **Acceptance Criteria** (Given/When/Then format):"
      "   ```"
      "   - [ ] AC N: Given [precondition], when [action], then [expected result]"
      "   ```"
      ""
      "   Cover:"
      "   - Happy path functionality"
      "   - Error handling"
      "   - Edge cases (if relevant)"
      "   - Integration points (if relevant)"
      ""
      "3. **Additional Context**:"
      "   - Dependencies (external libraries, services, etc.)"
      "   - Testing Strategy (unit tests, integration tests, manual testing)"
      "   - Notes (high-risk items, known limitations, future considerations)"
      ""
      "Present the complete plan in a clear, structured format."
      "Remember: This must meet the Ready for Development standard!"
      ""
      "Output using transition:"
      "{\"id\": [\"generate-plan\", \"review\"],"
      " \"generated-spec\": \"...complete spec as markdown...\"}"]}

    ;; ======================================================================
    ;; REVIEW: Present spec, get feedback, verify standard
    ;; ======================================================================
    {"id" "review"
     "action" "llm"
     "prompts"
     [ready-for-dev-standard
      ""
      "**Step 4 of 4: Review & Finalize**"
      ""
      "**Complete Tech-Spec:**"
      "{{generated-spec}}"
      ""
      "**Your Task:**"
      ""
      "1. **Present the complete spec** to the user"
      "2. **Provide a quick summary**:"
      "   - Number of tasks"
      "   - Number of acceptance criteria"
      "   - Number of files to modify"
      ""
      "3. **Ask**: 'Does this capture your intent? Any changes needed?'"
      ""
      "4. **If changes requested**:"
      "   - Make the edits"
      "   - Re-present affected sections"
      "   - Loop until satisfied"
      ""
      "5. **Verify Ready for Development Standard**:"
      "   - If spec doesn't meet standard, point out gaps"
      "   - Propose improvements"
      "   - Make edits once user agrees"
      ""
      "Use natural dialogue with clear presentation of the spec and summary."
      ""
      "When user confirms the spec is good, output using transition:"
      "{\"id\": [\"review\", \"end\"],"
      " \"final-spec-path\": \"/path/to/spec.md\","
      " \"task-count\": N,"
      " \"ac-count\": N,"
      " \"files-count\": N}"]}

    ;; ======================================================================
    ;; END: Terminal state - success
    ;; ======================================================================
    {"id" "end"
     "action" "end"}]

   "xitions"
   [;; Entry
    {"id" ["start" "init"]
     "schema" {"$ref" "#/$defs/entry"}}

    ;; Init → understand
    {"id" ["init" "understand-requirements"]
     "schema" {"$ref" "#/$defs/init-to-understand"}}

    ;; Understand → investigate
    {"id" ["understand-requirements" "investigate-codebase"]
     "schema" {"$ref" "#/$defs/understand-to-investigate"}}

    ;; Investigate → generate
    {"id" ["investigate-codebase" "generate-plan"]
     "schema" {"$ref" "#/$defs/investigate-to-generate"}}

    ;; Generate → review
    {"id" ["generate-plan" "review"]
     "schema" {"$ref" "#/$defs/generate-to-review"}}

    ;; Review → end
    {"id" ["review" "end"]
     "schema" {"$ref" "#/$defs/exit"}}]})

;; ============================================================================
;; Inspection
;; ============================================================================

(comment
  ;; Inspect FSM
  bmad-quick-spec-flow

  ;; Check states
  (count (get bmad-quick-spec-flow "states"))
  (mapv #(get % "id") (get bmad-quick-spec-flow "states"))

  ;; Check transitions
  (->> (get bmad-quick-spec-flow "xitions")
       (map #(get % "id"))))
