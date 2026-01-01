# CLAIJ Society FSM - Proof of Concept Working! 🎉

## Multi-LLM Collaboration via OpenAI-Compatible Endpoint

We've successfully demonstrated CLAIJ's **society FSM** working through an OpenAI-compatible chat interface (Open WebUI), coordinating **4 different LLMs** to provide diverse perspectives on user questions.

### What Works

**OpenAI-Compatible Endpoint** (`/v1/chat/completions`)
- ✅ Any OpenAI-compatible chat UI can now use CLAIJ as a backend
- ✅ Message transformation (OpenAI format → FSM trail format)
- ✅ Model routing (`claij/society` → society FSM)
- ✅ Content extraction (structured FSM output → plain text response)
- ✅ CORS support for browser-based clients

**Society FSM - Smart Delegation**
- ✅ **Substantive questions**: Consults all 4 LLMs (Claude, Grok, GPT, Gemini) via OpenRouter
- ✅ **Mechanical tasks**: Chairman handles directly (tagging, titles, follow-up suggestions)
- ✅ **Intelligent routing**: Chairman decides when collaboration adds value
- ✅ **Clear attribution**: Responses show which LLM said what

**Example Query**: "What are the pros and cons of using Open WebUI?"

**Society Response**:
```
Claude: Open WebUI is self-hosted and privacy-focused with offline functionality 
and extensive customization, but requires technical setup and ongoing maintenance...

Grok: Provides a customizable, open-source interface with privacy through 
self-hosting and support for multiple backends like Ollama. However, it demands 
technical expertise for setup...

GPT: Pros include being open-source/self-hostable (better privacy/control), 
supporting multiple backends with multi-model chat...

Gemini: Excels for local-first workflows with high privacy through self-hosting 
and a feature-rich interface for switching between backends...
```

### Performance Optimization

**Smart Delegation Savings**:
- **Substantive question**: 4 LLM calls (~60 seconds) - full society consultation
- **Follow-up suggestions**: 1 LLM call (~4 seconds) - Chairman only
- **Title generation**: 1 LLM call (~2 seconds) - Chairman only  
- **Tag generation**: 1 LLM call (~2 seconds) - Chairman only

**Without smart delegation**: All 4 requests would require 16 LLM calls (~240 seconds)
**With smart delegation**: 7 LLM calls (~68 seconds) - **56% fewer calls, 71% faster!**

### Current Limitations & Workarounds

**⚠️ Streaming Not Yet Supported**
- Open WebUI defaults to requesting streaming responses (`stream: true`)
- CLAIJ currently returns complete responses only (non-streaming)
- **WORKAROUND**: In Open WebUI settings, disable **"Stream Chat Response"**
- **Phase 2**: Implement proper Server-Sent Events (SSE) streaming

**⚠️ Intermittent Timeouts**
- Some requests timeout despite succeeding (investigating)
- FSM completes in 60-76 seconds (within 2-minute timeout limit)
- May be client-side timeout or HTTP layer issue
- **TODO**: Debug timeout behavior, possibly add configurable timeout settings

### Technical Achievement

This demonstrates CLAIJ's core value proposition:
1. **FSM-orchestrated workflows** with multiple LLM coordination
2. **OpenAI compatibility layer** - any chat UI can become a CLAIJ frontend
3. **Intelligent delegation** - optimize costs by routing appropriately
4. **Structured output → plain text** - bridge between FSM capabilities and chat UIs

### Files Modified

- `src/claij/openai/compat.clj` - OpenAI endpoint, message transformation, content extraction
- `src/claij/fsm/society_fsm.clj` - Smart Chairman delegation logic
- `src/claij/server.clj` - FSM registry, CORS middleware, string key configuration

### Next Steps

**Immediate (Issue #139 completion)**:
- [ ] Debug timeout behavior
- [ ] Run test suite
- [ ] Commit and push
- [ ] Update README with Open WebUI integration example

**Phase 2 - Streaming Support**:
- [ ] Implement Server-Sent Events (SSE) for `stream: true` requests
- [ ] Stream Chairman's thought process as reviewers respond
- [ ] Progressive display of multi-LLM responses

**Performance Optimization**:
- [ ] **Parallel LLM calls** - Query all 4 reviewers simultaneously instead of sequentially
- [ ] Expected speedup: ~75% reduction in wall-clock time (from 60s to ~15s)
- [ ] Use existing `claij.parallel` namespace

**Future Enhancements** (Issue #148):
- [ ] Request multiplexing - anticipate auto-tagging requests
- [ ] Cache structured output (tags, titles) from first request
- [ ] Eliminate redundant LLM calls for mechanical tasks

### Demo Setup

1. **Start CLAIJ server**:
   ```bash
   ./bin/claij.sh --no-ssl
   ```

2. **Configure Open WebUI**:
   - Add CLAIJ as OpenAI-compatible endpoint: `http://localhost:8080/v1`
   - Select model: `claij/society`
   - **IMPORTANT**: Disable "Stream Chat Response" in settings

3. **Ask a question requiring multiple perspectives**:
   - "What are the pros and cons of X?"
   - "Compare approach A vs B for Y"
   - "What should I consider when choosing Z?"

4. **Watch the society collaborate**:
   - Chairman distributes question to 4 LLMs
   - Each provides unique perspective
   - Chairman synthesizes with attribution

### Attribution

- OpenRouter used for LLM access (avoiding direct API rate limits)
- Models: Claude Sonnet 4.5, Grok Code Fast, GPT-5.2, Gemini 3 Flash
- Test platform: Open WebUI (open-source ChatGPT alternative)

---

**Status**: Proof of Concept ✅ | **Issue**: #139 | **Date**: 2026-01-01
