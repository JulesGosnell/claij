# Development Practices Skill

## Core Values
- Think first, code second (1:100 ratio)
- Simplicity is ultimate sophistication
- Always ask: "Can this be simpler?"
- YAGNI - build for today not tomorrow
- Run tests before declaring done

## Dependencies
Prefer: stdlib > open-source > minimal custom > complex abstractions

## Structure
- No boilerplate
- Inline when used once
- Decompose complexity
- One function one responsibility
- <20 lines ideal

## LLM Collaboration
Small functions work better with LLMs
If can't explain in one sentence, too complex

## Decisions
Consider 1-2 likely next steps
Choose extensible patterns
Document reasoning

## Anti-Patterns
- Premature abstraction (wait for 3 uses)
- Deep nesting
- Clever code
- Copy-paste
- Defensive programming

## Git Practices
- Always use `git mv` (not `mv`) to move/rename files - preserves history
- Commit related changes together
- Clear commit messages
- **NEVER git push** - only Jules pushes. Claude commits locally, Jules reviews and pushes.

## GitHub Issue Tracking
- **IMMEDIATELY after committing**, update the GitHub issue to tick off completed tasks
- Do not wait - context can be lost if conversation crashes
- Update issue body with `[x]` for completed task checkboxes
- Add `status:doing` label when starting work, `status:done` when closing
