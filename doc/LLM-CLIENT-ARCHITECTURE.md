# LLM Client Architecture Analysis

## Current State

### Module Overview

```
claij.llm/
├── open_router.clj   ✅ Active - async client used by FSM
├── claude.clj        ⚠️ Stale - sync, direct API, not used by FSM
├── gpt.clj           ⚠️ Stale - sync, direct API, not used by FSM
├── grok.clj          ❌ Broken - references non-existent claij.agent.util
└── gemini.clj        ❌ Broken - references non-existent claij.agent.util
```

### Active Code Path

```
                      ┌─────────────────────┐
                      │   claij.fsm         │
                      │   llm-action        │
                      └─────────┬───────────┘
                                │
                      ┌─────────▼───────────┐
                      │ claij.llm.open-router│
                      │ open-router-async   │
                      └─────────┬───────────┘
                                │
                      ┌─────────▼───────────┐
                      │  OpenRouter API     │
                      │  (proxies to LLMs)  │
                      └─────────────────────┘
```

All FSM LLM calls go through `open-router-async`. The direct provider clients
(`claude.clj`, `gpt.clj`, etc.) are historical artifacts from early experiments.

### Issues Found

1. **Broken namespaces**: `grok.clj` and `gemini.clj` require `claij.agent.util`
   which doesn't exist (should be `claij.util`)

2. **No common interface**: Direct clients have different signatures than OpenRouter

3. **Sync vs Async**: Direct clients are synchronous, FSM needs async

4. **Duplication**: `open-router.clj` contains partial definitions like:
   ```clojure
   (def grok (partial open-router "grok" "x-ai" "grok-code-fast-1" ...))
   (def claude (partial open-router "claude" "anthropic" "claude-sonnet-4.5" ...))
   ```
   These duplicate the model knowledge in the direct clients.

5. **Model configs scattered**: `fsm.clj` has `llm-configs` map with prompts per
   provider/model, but this is separate from the client implementations.

## Cost Analysis

OpenRouter charges a commission on top of provider costs. For high-volume usage,
direct provider APIs are cheaper:

| Provider | OpenRouter Markup | Notes |
|----------|------------------|-------|
| Anthropic | ~5-10% | Direct API is cheaper |
| OpenAI | ~5-10% | Direct API is cheaper |
| Google | ~5-10% | Often has free tier |
| xAI | ~5-10% | Direct API is cheaper |

For development/testing: OpenRouter is convenient (one API key, all models).
For production: Direct APIs save money.

## Proposed Architecture

### Option A: Multimethod Dispatch (User's Suggestion)

```clojure
(ns claij.llm.client
  (:require [claij.llm.http :as http]))

;; Protocol for HTTP operations (injectable for testing)
(defprotocol HttpClient
  (post-json [this url headers body]))

;; Multimethod dispatches on [provider model] or just provider
(defmulti call-llm 
  (fn [http-client provider model messages opts]
    ;; Try specific [provider model] first, fall back to provider, then :default
    (cond
      (get-method call-llm [provider model]) [provider model]
      (get-method call-llm provider) provider
      :else :default)))

;; Direct Anthropic implementation
(defmethod call-llm "anthropic" 
  [http-client _provider model messages opts]
  (http/post-json http-client
    "https://api.anthropic.com/v1/messages"
    {"x-api-key" (env "ANTHROPIC_API_KEY")
     "anthropic-version" "2023-06-01"}
    {:model model :messages messages :max_tokens 4096}))

;; Direct OpenAI implementation  
(defmethod call-llm "openai"
  [http-client _provider model messages opts]
  (http/post-json http-client
    "https://api.openai.com/v1/chat/completions"
    {"Authorization" (str "Bearer " (env "OPENAI_API_KEY"))}
    {:model model :messages messages}))

;; Fallback to OpenRouter for unknown providers
(defmethod call-llm :default
  [http-client provider model messages opts]
  (http/post-json http-client
    "https://openrouter.ai/api/v1/chat/completions"
    {"Authorization" (str "Bearer " (env "OPENROUTER_API_KEY"))}
    {:model (str provider "/" model) :messages messages}))
```

### Option B: Strategy Pattern with Explicit Fallback

```clojure
(defprotocol LLMProvider
  (call [this messages opts])
  (supports-model? [this model]))

(defrecord AnthropicProvider [api-key]
  LLMProvider
  (supports-model? [_ model] 
    (re-matches #"claude-.*" model))
  (call [this messages opts]
    ;; Direct Anthropic API call
    ))

(defrecord OpenRouterProvider [api-key]
  LLMProvider
  (supports-model? [_ _model] true)  ; supports everything
  (call [this messages opts]
    ;; OpenRouter API call
    ))

(defn make-provider-chain [& providers]
  "Returns a provider that tries each in order until one supports the model"
  (reify LLMProvider
    (supports-model? [_ model]
      (some #(supports-model? % model) providers))
    (call [_ messages {:keys [model] :as opts}]
      (let [provider (first (filter #(supports-model? % model) providers))]
        (call provider messages opts)))))

;; Usage: Try direct APIs first, fall back to OpenRouter
(def provider-chain
  (make-provider-chain
    (->AnthropicProvider (env "ANTHROPIC_API_KEY"))
    (->OpenAIProvider (env "OPENAI_API_KEY"))
    (->OpenRouterProvider (env "OPENROUTER_API_KEY"))))
```

### Testing Strategy

Either architecture supports HTTP injection for testing:

```clojure
(deftest anthropic-request-format-test
  (let [captured-request (atom nil)
        mock-http (reify HttpClient
                    (post-json [_ url headers body]
                      (reset! captured-request {:url url :headers headers :body body})
                      {:status 200 :body "{\"content\":[{\"text\":\"ok\"}]}"}))]
    
    (call-llm mock-http "anthropic" "claude-sonnet-4" 
              [{"role" "user" "content" "test"}] {})
    
    (testing "request format matches Anthropic API spec"
      (is (= "https://api.anthropic.com/v1/messages" 
             (:url @captured-request)))
      (is (contains? (:headers @captured-request) "x-api-key"))
      (is (= "claude-sonnet-4" 
             (get-in @captured-request [:body :model]))))))

(deftest openrouter-fallback-test
  (let [mock-http ...]
    (call-llm mock-http "some-unknown-provider" "mystery-model" 
              [{"role" "user" "content" "test"}] {})
    
    (testing "unknown provider falls back to OpenRouter"
      (is (= "https://openrouter.ai/api/v1/chat/completions"
             (:url @captured-request)))
      (is (= "some-unknown-provider/mystery-model"
             (get-in @captured-request [:body :model]))))))
```

## Recommended Next Steps

### Phase 1: Cleanup (Low Risk)

1. **Fix broken namespaces**: Change `claij.agent.util` → `claij.util` in
   `grok.clj` and `gemini.clj`

2. **Delete dead code**: The sync `open-router` function and its partials
   (`grok`, `gpt`, `claude`, `gemini` in `open_router.clj`) appear unused

3. **Consolidate model configs**: Move `llm-configs` from `fsm.clj` to a
   dedicated `claij.llm.config` namespace

### Phase 2: HTTP Injection (Medium Risk)

1. **Define HttpClient protocol** in `claij.llm.http`

2. **Wrap clj-http** with default implementation

3. **Pass http-client through context** to `open-router-async`

4. **Add provider-specific tests** with mocked HTTP

### Phase 3: Direct Provider Support (Higher Risk)

1. **Implement multimethod dispatch** with fallback

2. **Add direct implementations** for Anthropic, OpenAI, Google, xAI

3. **Test each provider** with HTTP mocks

4. **Add long-running integration tests** for each direct provider

### Phase 4: Configuration

1. **Environment-based provider selection**:
   ```clojure
   ;; If ANTHROPIC_API_KEY set, use direct for Claude models
   ;; If not, fall back to OpenRouter
   ```

2. **Per-request override**:
   ```clojure
   {:provider "anthropic"
    :model "claude-sonnet-4"
    :use-direct? true}  ; Force direct API even if OpenRouter available
   ```

## Files to Change

| File | Change | Risk |
|------|--------|------|
| `claij.llm.grok` | Fix require | Low |
| `claij.llm.gemini` | Fix require | Low |
| `claij.llm.open-router` | Delete dead sync code | Low |
| `claij.fsm` | Extract `llm-configs` | Low |
| `claij.llm.http` | New: HTTP protocol | Medium |
| `claij.llm.client` | New: Unified client | Medium |
| `claij.actions` | Use new client | Medium |

## Decision Points

1. **Multimethod vs Protocol?** 
   - Multimethod: Simpler, easier to extend
   - Protocol: More explicit, better for complex providers

2. **When to use direct APIs?**
   - Always when available? (cost optimization)
   - Only when API key present? (flexible deployment)
   - Per-model configuration? (fine-grained control)

3. **Async model?**
   - Keep current callback style?
   - Switch to core.async channels?
   - Support both?

## Conclusion

The current architecture works but has accumulated cruft. The main code path
(FSM → open-router-async → OpenRouter) is solid. The direct provider clients
are broken/unused but represent a cost-saving opportunity.

Recommendation: Start with Phase 1 cleanup, then add HTTP injection for testing.
Direct provider support can wait until there's actual cost pressure.
