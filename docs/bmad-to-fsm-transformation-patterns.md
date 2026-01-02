# BMAD to FSM Transformation Patterns

**Author:** Claude  
**Date:** 2026-01-02  
**Story:** #157 (Manual BMAD Quick Spec Flow Conversion)  
**Purpose:** Document patterns for meta-FSM builder (#158)

This document captures the transformation patterns discovered during manual conversion of BMAD's Quick Spec Flow workflow to a CLAIJ FSM. These patterns will inform the design of the meta-FSM that automatically converts BMAD workflows.

## Overview

**Source:** BMAD v6.0.0-alpha.22 Quick Spec Flow  
**Location:** `_bmad/bmm/workflows/bmad-quick-flow/create-tech-spec/`  
**Result:** `src/claij/fsm/bmad_quick_spec_flow.clj`

## Transformation Patterns

### 1. Agent → Persona Constant

**Difficulty:** Easy ⭐

**BMAD Format:**
```markdown
<!-- _bmad/bmm/agents/quick-flow-solo-dev.md -->
---
name: "quick flow solo dev"
description: "Quick Flow Solo Dev"
---

<agent id="quick-flow-solo-dev.agent.yaml" name="Barry" ...>
  <persona>
    <role>Elite Full-Stack Developer + Quick Flow Specialist</role>
    <identity>Barry handles Quick Flow...</identity>
    <communication_style>Direct, confident...</communication_style>
    <principles>- Planning and execution are two sides...</principles>
  </persona>
</agent>
```

**FSM Output:**
```clojure
(def barry-persona
  "Barry: Elite Full-Stack Developer + Quick Flow Specialist
  
  Handles Quick Flow - from tech spec creation through implementation.
  Minimum ceremony, lean artifacts, ruthless efficiency.
  
  Communication Style: Direct, confident, and implementation-focused.
  
  Principles:
  - Planning and execution are two sides of the same coin
  - Specs are for building, not bureaucracy
  - Code that ships is better than perfect code that doesn't")
```

**Pattern:**
1. Parse XML `<persona>` section
2. Extract `<role>`, `<identity>`, `<communication_style>`, `<principles>`
3. Format as multi-line docstring
4. Create Clojure `def` with kebab-case name derived from agent ID

### 2. Workflow Metadata → FSM Metadata

**Difficulty:** Easy ⭐

**BMAD Format:**
```markdown
<!-- workflow.md -->
---
name: create-tech-spec
description: Conversational spec engineering...
main_config: '{project-root}/_bmad/bmm/config.yaml'
web_bundle: true
---
```

**FSM Output:**
```clojure
{:id :bmad-quick-spec-flow
 :name "BMAD Quick Spec Flow"
 :description "Create implementation-ready technical specifications..."
 :version "0.1.0-poc"}
```

**Pattern:**
1. Extract frontmatter from `workflow.md`
2. Map `name` → `:id` (as keyword)
3. Title-case `name` → `:name` (as string)
4. Copy `description` → `:description`
5. Generate initial version `:version "0.1.0-bmad"`

### 3. Sequential Steps → FSM States

**Difficulty:** Medium ⭐⭐

**BMAD Format:**
```
steps/
  step-01-understand.md
  step-02-investigate.md
  step-03-generate.md
  step-04-review.md
```

**FSM Output:**
```clojure
:states
[{:id :init
  :type :llm-state
  ...
  :transitions [{:to :understand-requirements}]}
 {:id :understand-requirements
  :type :llm-state
  ...
  :transitions [{:to :investigate-codebase}]}
 {:id :investigate-codebase
  :type :llm-state
  ...
  :transitions [{:to :generate-plan}]}
 ...]
```

**Pattern:**
1. Parse each `step-NN-*.md` file in order
2. Extract `name` and `description` from frontmatter
3. Generate state ID from filename (e.g., `step-01-understand` → `:understand-requirements`)
4. Create linear transitions to next step
5. Last step transitions to terminal state

**Complexity Note:**
- Step 01 often splits into multiple states (`:init` + `:understand-requirements`)
- Final step needs terminal state creation (`:review` → `:complete`)

### 4. Step Instructions → State Prompts

**Difficulty:** Easy ⭐

**BMAD Format:**
```markdown
<!-- step-01-understand.md -->
# Step 1: Analyze Requirement Delta

## SEQUENCE OF INSTRUCTIONS

### 1. Greet and Ask for Initial Request

a) **Greet the user briefly:**
"Hey {user_name}! What are we building today?"

b) **Get their initial description.**

### 2. Quick Orient Scan
...
```

**FSM Output:**
```clojure
{:id :understand-requirements
 :type :llm-state
 :prompt (str barry-persona "\n\n"
              "**Step 1 of 4: Analyze Requirement Delta**\n\n"
              "The user has described what they want to build: {{user-request}}\n\n"
              "**Your Task:**\n\n"
              "1. **Quick Orient Scan** (< 30 seconds):\n"
              "   - Search for relevant files...\n"
              "...")}
```

**Pattern:**
1. Extract main heading and numbered sections
2. Convert BMAD variables `{user_name}` → FSM template vars `{{user-name}}`
3. Prepend persona definition
4. Add step progress indicator ("Step N of M")
5. Preserve formatting and structure
6. Concatenate as multi-line string

### 5. Templates → Output Format Specs

**Difficulty:** Medium ⭐⭐

**BMAD Format:**
```markdown
<!-- tech-spec-template.md -->
---
title: '{title}'
slug: '{slug}'
created: '{date}'
status: 'in-progress'
tech_stack: []
---

# Tech-Spec: {title}

## Overview
### Problem Statement
{problem_statement}
...
```

**FSM Output:**
*Not implemented in POC - would need action states for file generation*

**Pattern (for future implementation):**
1. Parse template file
2. Identify all placeholder variables (`{variable}`)
3. Map to FSM context schema
4. Create file-write action with template rendering
5. Add to appropriate state (e.g., final state or dedicated action state)

### 6. WIP Files → Context Schema

**Difficulty:** Medium ⭐⭐

**BMAD Pattern:**
```yaml
# WIP file frontmatter
---
title: 'Add CSV Export'
slug: 'add-csv-export'
status: 'in-progress'
stepsCompleted: [1, 2]
tech_stack: ['Clojure', 'Ring', 'Hiccup']
files_to_modify: ['src/app/core.clj', 'src/app/export.clj']
---
```

**FSM Output:**
```clojure
:context-schema
{:title :string
 :slug :string
 :status :string
 :steps-completed [:vector :int]
 :tech-stack [:vector :string]
 :files-to-modify [:vector :string]
 :user-request :string
 :problem-statement :string
 ...}
```

**Pattern:**
1. Extract all frontmatter fields from template
2. Convert to Malli schema types
3. Add fields referenced in step prompts
4. Use kebab-case for field names
5. Track state progression via `:steps-completed` vector

### 7. Checkpoint Menus → Conditional Transitions

**Difficulty:** Hard ⭐⭐⭐ (Skipped in POC)

**BMAD Format:**
```markdown
### 6. Present Checkpoint Menu

```
[a] Advanced Elicitation - dig deeper
[c] Continue - proceed to next step
[p] Party Mode - bring in other experts
```

**Menu Handling:**
- [a]: Load and execute {advanced_elicitation}
- [c]: Load and execute {nextStepFile}
- [p]: Load and execute {party_mode_exec}
```

**FSM Output (Future):**
```clojure
{:id :checkpoint
 :type :decision-state
 :transitions
 [{:to :advanced-elicitation :condition "user-choice == 'a'"}
  {:to :next-step :condition "user-choice == 'c'"}
  {:to :party-mode :condition "user-choice == 'p'"}]}
```

**Pattern (for future):**
1. Identify menu sections in steps
2. Extract menu options `[key] Description`
3. Parse menu handling instructions
4. Create `:decision-state` node
5. Generate conditional transitions
6. For sub-workflows (a, p), create FSM invocation states

**POC Simplification:** Removed menus entirely, use linear flow.

### 8. Standards/Requirements → Def Constants

**Difficulty:** Easy ⭐

**BMAD Format:**
```markdown
**READY FOR DEVELOPMENT STANDARD:**

A specification is considered "Ready for Development" ONLY if:
- **Actionable**: Every task has a clear file path...
- **Logical**: Tasks are ordered by dependency...
...
```

**FSM Output:**
```clojure
(def ready-for-dev-standard
  "A specification is considered 'Ready for Development' ONLY if it meets:
  
  - **Actionable**: Every task has a clear file path and specific action
  - **Logical**: Tasks are ordered by dependency (lowest level first)
  ...")
```

**Pattern:**
1. Identify standard/requirement sections in workflow files
2. Extract as multi-line string
3. Create Clojure `def` with descriptive name
4. Inject into relevant state prompts

## FSM Schema Requirements

### Must Support (for meta-FSM)

1. **Multi-line string prompts with embedded variables**
   - Template variable syntax: `{{var-name}}`
   - Variable substitution at runtime
   
2. **Context variable substitution**
   - Extract variables from prompts
   - Generate context schema automatically
   
3. **Sequential state transitions**
   - Linear flow for simple workflows
   - Support for deterministic progression
   
4. **Terminal states with final output**
   - `:terminal-state` type
   - Output template rendering
   
5. **Persona/system-prompt injection**
   - Prepend agent persona to state prompts
   - Maintain consistent agent identity

### Nice to Have (future enhancements)

1. **Conditional transitions for menu branching**
   - `:decision-state` type
   - User input-based routing
   
2. **Action states for file operations**
   - `:action-state` type
   - File read/write/update operations
   
3. **Sub-FSM invocation**
   - Call other workflows (party mode, advanced elicitation)
   - Return to calling state after completion
   
4. **Resume/checkpoint logic**
   - Load WIP files
   - Jump to appropriate state based on progress

### Not Needed (handled by meta-FSM)

1. **YAML parsing** - Meta-FSM does this
2. **Step-file loading** - Meta-FSM pre-processes
3. **Menu parsing** - Meta-FSM simplifies or expands

## Transformation Challenges

### Easy to Automate ⭐

- Agent persona extraction
- Workflow metadata mapping
- Standard/requirement extraction
- Step-to-state basic mapping
- Template variable identification

### Medium Complexity ⭐⭐

- Step instructions → state prompts
- WIP file structure → context schema
- Template files → output specs
- Multi-state step splitting (step-01 → init + understand)

### Hard to Automate ⭐⭐⭐

- Checkpoint menu → decision states
- Sub-workflow invocation (party mode, advanced elicitation)
- File operation action generation
- Resume/checkpoint logic
- Dynamic code scanning action generation

## Simplifications Made in POC

For the manual POC conversion, the following simplifications were made:

1. **Removed checkpoint menus** - Linear flow only, no [a/c/p] branching
2. **No WIP resume logic** - Don't check for existing WIP files
3. **Skipped sub-workflows** - No party mode or advanced elicitation
4. **No file operations** - All states are `:llm-state`, no actual file creation
5. **No code scanning actions** - Would need bash/file tool integration
6. **Linear happy path only** - No error handling or retry logic

These can be added incrementally in future iterations.

## Recommendations for Meta-FSM (#158)

### Phase 1: Core Transformation
1. Implement easy patterns first (agent, metadata, standards)
2. Build step-to-state linear mapping
3. Generate basic prompts with variable substitution
4. Create context schema from template frontmatter

### Phase 2: Advanced Features
1. Add checkpoint menu → decision state logic
2. Implement file operation actions
3. Support sub-FSM invocation
4. Add resume/checkpoint capabilities

### Phase 3: Optimization
1. Detect and merge related steps into single states
2. Optimize prompt verbosity
3. Add validation for Ready-for-Dev standard
4. Generate test cases from workflow structure

## Example: Complete Transformation

**Input:** `_bmad/bmm/workflows/bmad-quick-flow/create-tech-spec/`

**Output:** `src/claij/fsm/bmad_quick_spec_flow.clj` (309 lines)

**Transformation Results:**
- 4 step files → 6 FSM states (including init and terminal)
- 1 agent file → 1 persona constant
- 1 workflow file → FSM metadata
- 1 template file → context schema (12 fields)
- 1 standard section → 1 requirement constant

**Statistics:**
- Manual conversion time: ~2 hours (design + implementation)
- Lines of code: 309 (including comments and formatting)
- Test coverage: Not yet implemented

## Next Steps

See Story #158 for meta-FSM implementation that automates these patterns.
