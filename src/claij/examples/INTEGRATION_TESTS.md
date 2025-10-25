# Memory Interceptor Integration Tests

## Overview

Integration tests for the memory interceptor across all registered LLM models.

Tests verify that:
1. LLMs can store facts in their first response (`summary` field)
2. LLMs can recall stored facts in subsequent exchanges
3. Ultra-tight fill-in-the-blank prompts produce predictable, testable responses

## Running Tests

### Quick Test (Mock LLM - No API calls)

```bash
./bin/integration-test.sh --mock
```

### Full Integration Suite (Real API calls)

First, make the script executable:
```bash
chmod +x ./bin/integration-test.sh
```

Then run:
```bash
./bin/integration-test.sh
```

This tests all models in `openrouter/model-registry`:
- Grok Code Fast 1
- GPT-5 Codex
- Claude Sonnet 4.5
- Gemini 2.5 Flash

**Note:** This makes real API calls and costs approximately $0.10-0.50

### From REPL

```clojure
;; Test with mock (no API calls)
(require '[claij.examples.memory-demo :as demo])
(demo/demo-with-mock)

;; Test specific model
(demo/demo-with-model :claude {:temperature 0.3 :max-tokens 300})

;; Test all models
(demo/demo-all-models {:temperature 0.3 :max-tokens 300})
```

## Test Strategy

Uses ultra-tight fill-in-the-blank prompts to constrain LLM responses:

**Exchange 2:** "Answer with just the one word that goes in the blank: Alice's favorite color is ____."
- Expected: `"blue"` (exactly)

**Exchange 3:** "Answer with just the one numeral that goes in the blank: Alice has ____ cats."
- Expected: `"2"` (exactly)

This follows the principle: **"The smarts should be in the prompt, not the test code."**

## Prompt Engineering for JSON

The schema prompt explicitly instructs LLMs to return pure JSON:

```
CRITICAL: Return ONLY the raw JSON object.
Do NOT wrap it in markdown code blocks, backticks, or any other formatting.
Do NOT include any text before or after the JSON.
Your entire response must be valid JSON that can be parsed directly.
```

This eliminates the need for special parsing code to handle model-specific quirks.

## Model Registry

Models are defined in `claij.new.backend.openrouter/model-registry`:

```clojure
{:grok   {:model "x-ai/grok-code-fast-1" ...}
 :gpt    {:model "openai/gpt-5-codex" ...}
 :claude {:model "anthropic/claude-sonnet-4.5" ...}
 :gemini {:model "google/gemini-2.5-flash" ...}}
```

To add a new model:
1. Add entry to `model-registry`
2. Run integration tests to verify compatibility
3. Update compatibility matrix in STRUCTURED_RESPONSES.md

## Success Criteria

A model passes if:
1. ✅ Summary in Exchange 1 contains: "Alice", "blue", "2.*cats?"
2. ✅ Answer in Exchange 2 equals exactly: "blue"
3. ✅ Answer in Exchange 3 equals exactly: "2"

## Environment Setup

```bash
# Set OpenRouter API key
export OPENROUTER_API_KEY='your-key-here'

# Or source the .env file
source .env
```
