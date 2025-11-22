# Token Optimization Skill

**Mission:** Keep token consumption low. Warn Jules when waste detected.

## Core Rules

**ALWAYS use `collapsed: true` by default**
- Only set `collapsed: false` when you need full file content
- Use `name_pattern` / `content_pattern` to target specific functions
- Default assumption: collapsed view first, expand only if needed

**Never re-read the same file**
- Check if you already have the information
- Ask yourself: "Did I already read this?"
- Cache what you know in working memory

**Watch for large inline data**
- Schemas, test data, large maps/vectors in source files
- These add 1k-5k tokens per read
- Flag to Jules if detected in frequently-read files

**Tool call efficiency**
- Before calling tool: "Is this necessary?"
- Before full file read: "Can I use name_pattern instead?"
- Before searching: "Do I already know this?"

## Warning Thresholds

**Warn Jules when:**
- Single tool result > 3k tokens
- Same file read 3+ times in session
- Total session tokens > 100k
- Inline data structure > 1k lines detected

**How to warn:**
```
⚠️ TOKEN WARNING: [brief reason]
Suggestion: [what to do instead]
```

## Heavy Files in CLAIJ

- `doc/CODING_GUIDELINES.md` - ~600 lines, use sparingly
- `doc/MCP.md` - ~300 lines, read when needed
- Any file with test data / schemas - watch for inline data

## Remember

**Token efficiency = faster work, lower cost, more iterations.**
