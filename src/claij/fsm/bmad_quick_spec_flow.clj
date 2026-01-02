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
   [malli.core :as m]))

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
;; FSM Schema
;; ============================================================================

(def schema
  [:map
   [:id :keyword]
   [:name :string]
   [:description :string]
   [:version :string]
   [:states
    [:vector
     [:map
      [:id :keyword]
      [:type [:enum :llm-state :action-state :decision-state :terminal-state]]
      [:prompt {:optional true} :string]
      [:actions {:optional true} [:vector :map]]
      [:transitions {:optional true}
       [:vector
        [:map
         [:to :keyword]
         [:condition {:optional true} :string]]]]]]]
   [:initial-state :keyword]
   [:context-schema {:optional true} :map]])

;; ============================================================================
;; Quick Spec Flow FSM Definition
;; ============================================================================

(def fsm
  {:id :bmad-quick-spec-flow
   :name "BMAD Quick Spec Flow"
   :description "Create implementation-ready technical specifications through conversational discovery, code investigation, and structured documentation. Converted from BMAD Method's create-tech-spec workflow."
   :version "0.1.0-poc"

   :states
   [;; ======================================================================
    ;; INIT: Load config, greet user, get feature request
    ;; ======================================================================
    ;; Source: workflow.md initialization + step-01-understand.md sections 1-2
    ;; Persona: Barry from quick-flow-solo-dev.md
    {:id :init
     :type :llm-state
     :prompt (str barry-persona "\n\n"
                  "You are starting a new Quick Spec Flow session.\n\n"
                  "**Your Role:**\n"
                  "- Greet the user warmly and professionally\n"
                  "- Ask what feature or capability they want to build\n"
                  "- Keep it brief - just get the initial description\n"
                  "- Don't ask detailed questions yet\n\n"
                  "Output your greeting and question as a natural conversation starter.")
     :transitions
     [{:to :understand-requirements}]}

    ;; ======================================================================
    ;; UNDERSTAND REQUIREMENTS: Quick scan, informed questions, capture core understanding
    ;; ======================================================================
    ;; Source: step-01-understand.md sections 2-4
    {:id :understand-requirements
     :type :llm-state
     :prompt (str barry-persona "\n\n"
                  "**Step 1 of 4: Analyze Requirement Delta**\n\n"
                  "The user has described what they want to build: {{user-request}}\n\n"
                  "**Your Task:**\n\n"
                  "1. **Quick Orient Scan** (< 30 seconds):\n"
                  "   - Search for relevant files/classes/functions the user mentioned\n"
                  "   - Skim the structure (don't deep-dive yet - that's next step)\n"
                  "   - Check for project-context.md\n"
                  "   - Note: tech stack, obvious patterns, file locations\n"
                  "   - Build mental model of the likely landscape\n\n"
                  "2. **Ask Informed Questions**:\n"
                  "   Instead of generic questions, ask specific ones based on what you found:\n"
                  "   - Reference specific files/classes/patterns you found\n"
                  "   - Ask about architectural decisions\n"
                  "   - If no code found, ask about intended patterns\n\n"
                  "3. **Capture Core Understanding** and confirm:\n"
                  "   - **Title**: Clear, concise name for this work\n"
                  "   - **Problem Statement**: What problem are we solving?\n"
                  "   - **Solution**: High-level approach (1-2 sentences)\n"
                  "   - **In Scope**: What's included\n"
                  "   - **Out of Scope**: What's explicitly NOT included\n\n"
                  "**Output Format:**\n"
                  "Present your questions and understanding as natural dialogue.\n"
                  "End with a clear confirmation request.")
     :transitions
     [{:to :investigate-codebase}]}

    ;; ======================================================================
    ;; INVESTIGATE CODEBASE: Deep code investigation, map technical constraints
    ;; ======================================================================
    ;; Source: step-02-investigate.md
    {:id :investigate-codebase
     :type :llm-state
     :prompt (str barry-persona "\n\n"
                  "**Step 2 of 4: Map Technical Constraints & Anchor Points**\n\n"
                  "**Confirmed Understanding:**\n"
                  "- Title: {{title}}\n"
                  "- Problem: {{problem-statement}}\n"
                  "- Solution: {{solution}}\n"
                  "- Scope: {{scope}}\n\n"
                  "**Your Task:**\n\n"
                  "Execute deep code investigation:\n\n"
                  "1. **Read and Analyze Code**:\n"
                  "   For each relevant file:\n"
                  "   - Read the complete file(s)\n"
                  "   - Identify patterns, conventions, coding style\n"
                  "   - Note dependencies and imports\n"
                  "   - Find related test files\n\n"
                  "2. **If NO relevant code found (Clean Slate)**:\n"
                  "   - Identify target directory where feature should live\n"
                  "   - Scan parent directories for architectural context\n"
                  "   - Identify standard project utilities or boilerplate\n"
                  "   - Document this as 'Confirmed Clean Slate'\n\n"
                  "3. **Document Technical Context**:\n"
                  "   Capture and confirm:\n"
                  "   - **Tech Stack**: Languages, frameworks, libraries\n"
                  "   - **Code Patterns**: Architecture patterns, naming conventions, file structure\n"
                  "   - **Files to Modify/Create**: Specific files that need changes or creation\n"
                  "   - **Test Patterns**: How tests are structured, test frameworks used\n\n"
                  "4. **Check for project-context.md**:\n"
                  "   If exists, extract patterns and conventions\n\n"
                  "**Output Format:**\n"
                  "Present findings as a structured summary with clear sections.")
     :transitions
     [{:to :generate-plan}]}

    ;; ======================================================================
    ;; GENERATE PLAN: Create implementation tasks and acceptance criteria
    ;; ======================================================================
    ;; Source: step-03-generate.md
    {:id :generate-plan
     :type :llm-state
     :prompt (str barry-persona "\n\n"
                  ready-for-dev-standard "\n\n"
                  "**Step 3 of 4: Generate Implementation Plan**\n\n"
                  "**Context:**\n"
                  "- Title: {{title}}\n"
                  "- Problem: {{problem-statement}}\n"
                  "- Solution: {{solution}}\n"
                  "- Tech Stack: {{tech-stack}}\n"
                  "- Code Patterns: {{code-patterns}}\n"
                  "- Files to Modify: {{files-to-modify}}\n"
                  "- Test Patterns: {{test-patterns}}\n\n"
                  "**Your Task:**\n\n"
                  "Generate specific implementation tasks:\n\n"
                  "1. **Task Breakdown**:\n"
                  "   - Each task should be discrete, completable unit of work\n"
                  "   - Order logically (dependencies first)\n"
                  "   - Include specific files to modify in each task\n"
                  "   - Be explicit about what changes to make\n\n"
                  "   Format:\n"
                  "   ```\n"
                  "   - [ ] Task N: Clear action description\n"
                  "     - File: `path/to/file.ext`\n"
                  "     - Action: Specific change to make\n"
                  "     - Notes: Any implementation details\n"
                  "   ```\n\n"
                  "2. **Acceptance Criteria** (Given/When/Then format):\n"
                  "   ```\n"
                  "   - [ ] AC N: Given [precondition], when [action], then [expected result]\n"
                  "   ```\n\n"
                  "   Cover:\n"
                  "   - Happy path functionality\n"
                  "   - Error handling\n"
                  "   - Edge cases (if relevant)\n"
                  "   - Integration points (if relevant)\n\n"
                  "3. **Additional Context**:\n"
                  "   - Dependencies (external libraries, services, etc.)\n"
                  "   - Testing Strategy (unit tests, integration tests, manual testing)\n"
                  "   - Notes (high-risk items, known limitations, future considerations)\n\n"
                  "**Output Format:**\n"
                  "Present the complete plan in a clear, structured format.\n"
                  "Remember: This must meet the Ready for Development standard!")
     :transitions
     [{:to :review}]}

    ;; ======================================================================
    ;; REVIEW: Present spec, get feedback, verify standard
    ;; ======================================================================
    ;; Source: step-04-review.md sections 1-2
    {:id :review
     :type :llm-state
     :prompt (str barry-persona "\n\n"
                  ready-for-dev-standard "\n\n"
                  "**Step 4 of 4: Review & Finalize**\n\n"
                  "**Complete Tech-Spec:**\n"
                  "{{generated-spec}}\n\n"
                  "**Your Task:**\n\n"
                  "1. **Present the complete spec** to the user\n"
                  "2. **Provide a quick summary**:\n"
                  "   - Number of tasks\n"
                  "   - Number of acceptance criteria\n"
                  "   - Number of files to modify\n\n"
                  "3. **Ask**: 'Does this capture your intent? Any changes needed?'\n\n"
                  "4. **If changes requested**:\n"
                  "   - Make the edits\n"
                  "   - Re-present affected sections\n"
                  "   - Loop until satisfied\n\n"
                  "5. **Verify Ready for Development Standard**:\n"
                  "   - If spec doesn't meet standard, point out gaps\n"
                  "   - Propose improvements\n"
                  "   - Make edits once user agrees\n\n"
                  "**Output Format:**\n"
                  "Natural dialogue with clear presentation of the spec and summary.")
     :transitions
     [{:to :complete}]}

    ;; ======================================================================
    ;; COMPLETE: Terminal state - success
    ;; ======================================================================
    ;; Source: step-04-review.md section 5
    {:id :complete
     :type :terminal-state
     :prompt (str "**Tech-Spec Complete!**\n\n"
                  "Saved to: {{final-spec-path}}\n\n"
                  "**Summary:**\n"
                  "- {{task-count}} implementation tasks\n"
                  "- {{ac-count}} acceptance criteria\n"
                  "- {{files-count}} files to modify\n\n"
                  "**Next Steps:**\n"
                  "The spec is ready for implementation. A fresh dev agent can now "
                  "use this spec to implement the feature without needing context "
                  "from this conversation.\n\n"
                  "Ship it!")}]

   :initial-state :init

   :context-schema
   {:user-request :string
    :title :string
    :problem-statement :string
    :solution :string
    :scope {:in-scope :string
            :out-of-scope :string}
    :tech-stack [:vector :string]
    :code-patterns [:vector :string]
    :files-to-modify [:vector :string]
    :test-patterns [:vector :string]
    :generated-spec :string
    :final-spec-path :string
    :task-count :int
    :ac-count :int
    :files-count :int}})

;; ============================================================================
;; Validation
;; ============================================================================

(defn validate-fsm
  "Validate the FSM against schema."
  []
  (m/validate schema fsm))

(comment
  ;; Validate FSM structure
  (validate-fsm)
  ;; => true (if valid)

  ;; Inspect FSM
  fsm)
