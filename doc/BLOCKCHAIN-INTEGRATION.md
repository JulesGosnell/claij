# CLAIJ Blockchain Integration Patterns

## Executive Summary

This document explores how CLAIJ's FSM+schema architecture could interact with smart contracts and blockchain systems. The intersection of LLM orchestration and blockchain presents unique opportunities for:

1. **AI-driven transaction workflows** - FSMs coordinating multi-step blockchain operations
2. **Smart contract as state machine** - Mirroring on-chain state in FSM definitions
3. **Blockchain-backed audit trails** - Persisting trails for immutable verification
4. **Multi-agent multi-sig** - Coordinating approval workflows across agents
5. **Oracle patterns** - AI providing external intelligence to smart contracts

## Landscape Overview

### Existing Clojure/ClojureScript Libraries

| Library | Platform | Status | Notes |
|---------|----------|--------|-------|
| cljs-web3-next | CLJS | Active | Web3.js wrapper, district0x maintained |
| cloth | CLJ/CLJS | Dormant | Promesa-based, nice contract macros |
| web3j interop | CLJ | Viable | Direct Java interop with Web3j |

**Assessment**: ClojureScript has better support (Web3.js ecosystem). For Clojure, Web3j interop is the path forward.

### Emerging Web3+AI Patterns

1. **ElizaOS** - Open-source Web3-native AI agent OS (TypeScript)
2. **Context3** - MCP server for blockchain interaction
3. **ChainGPT** - Web3-specialized LLM with live chain data
4. **Chainlink + AI** - Oracle networks feeding AI outputs to contracts
5. **aelf AI Oracle** - On-chain AI agent services

Key insight: **MCP (Model Context Protocol) is becoming the standard interface** between LLMs and blockchain infrastructure.

## Key Insight

**Smart contracts are state machines. CLAIJ orchestrates state machines. The composition is natural.**

---

## Three Levels of Integration

There are fundamentally different ways CLAIJ could relate to blockchain:

```
Level 1: CLAIJ reviews/builds contracts     (LLM as developer)
Level 2: CLAIJ interacts with contracts     (LLM as user/trader)  
Level 3: CLAIJ IS the contract              (LLM as execution engine)
```

### Level 1: Solidity Review FSM

CLAIJ as smart contract developer/auditor:

```clojure
(def-fsm solidity-review
  {:states
   {"analyzing"
    {:actions [{:name "static-analysis" :fn run-slither}
               {:name "llm-review" :fn review-for-patterns}]}
    "vulnerability-check"
    {:actions [{:name "check-reentrancy"}
               {:name "check-overflow"}
               {:name "check-access-control"}]}
    "gas-optimization"
    {:actions [{:name "suggest-optimizations"}]}
    "deploy-decision"
    {:transitions [{:to "deploy" :when safe-and-approved?}
                   {:to "revise" :when needs-fixes?}]}}})
```

Value: LLMs catch semantic/logic bugs that static analyzers miss. FSM ensures systematic coverage.

### Level 2: Trading Bot / Wallet Manager

CLAIJ as the "brain" coordinating blockchain interactions:

```clojure
(def-fsm trading-bot
  {:states
   {"monitoring"
    {:actions [{:name "analyze-market"
                :fn (fn [_ _ _ state]
                      {:signal "buy" :confidence 0.85 :reasoning "..."})}]
     :transitions [{:to "execute-trade" :when high-confidence-signal?}]}
    "execute-trade"
    {:actions [{:name "submit-swap" :fn blockchain/submit-swap}]
     :transitions [{:to "confirming"}]}
    "confirming"
    {:wait-for-signal "tx-confirmed"
     :transitions [{:to "monitoring" :when confirmed?}]}}})
```

Key insight: **The trail becomes a complete audit log of every trading decision** - compliance dream.

### Level 3: CLAIJ AS the Contract 🤯

This is the mind-bender. Traditional smart contracts require:
1. Deterministic execution (same inputs → same outputs)
2. Verifiable by all parties
3. Immutable history
4. Consensus on state

**Reframing for LLM non-determinism:**

```
┌─────────────────────────────────────────────────────────────┐
│                    CLAIJ "Soft Contract"                     │
│                                                              │
│  FSM Definition: Deterministic (same for all parties)       │
│  State Transitions: Deterministic (schema-validated)        │
│  Actions: May include non-deterministic LLM calls           │
│  Trail: Immutable record of WHAT happened (not replay)      │
│                                                              │
│  Key Insight: We don't need replay-determinism              │
│               We need AGREEMENT on what happened            │
└─────────────────────────────────────────────────────────────┘
```

#### Handling Non-Determinism

**Option A: Oracle Pattern (Corda-style)**
- Party A executes LLM call → gets result
- Party A signs result
- All parties verify signature + schema compliance
- Result becomes "oracle input" in the trail

**Option B: Designated Executor**
- FSM definition specifies: "Agent X executes this action"
- Agent X runs the LLM, records result to shared trail
- Other parties observe but don't re-execute
- Disputes trigger arbitration (another FSM!)

**Option C: Consensus on Schema, Not Content**
- Multiple parties execute independently
- Results must pass same schema validation
- If schemas match, accept (fuzzy consensus)
- If schemas conflict, escalate to arbitration

### Store API Abstraction

**Critical insight**: If we design the store interface right, swapping in blockchain storage gives us multi-party contracts "for free":

```clojure
(defprotocol TrailStore
  "Persistence layer for FSM trails"
  (append-event! [store fsm-id event] 
    "Append event, return receipt/proof")
  (get-trail [store fsm-id] 
    "Retrieve full trail")
  (get-state [store fsm-id] 
    "Get current computed state")
  (verify-event [store fsm-id event-id proof]
    "Verify event exists and is authentic")
  ;; Multi-party specific
  (propose-event! [store event]
    "Propose event, await consensus")
  (attest-event! [store event-id signature]
    "Add attestation to proposed event"))

;; Implementations:
(defrecord InMemoryStore [atom])           ;; Dev/test
(defrecord PostgresStore [datasource])     ;; Single-party production
(defrecord IPFSAnchoredStore [ipfs chain]) ;; Immutable, auditable
(defrecord BlockchainStore [contract])     ;; Multi-party consensus
```

### Event Structure for Multi-Party

```clojure
(def Event
  [:map
   ["id" :uuid]
   ["fsm-id" :string]
   ["type" [:enum "state-entered" "action-started" "action-completed" 
            "transition" "signal-received"]]
   ["timestamp" :int]
   ["executor" {:optional true} :string]  ;; Who executed this?
   ["signature" {:optional true} :string] ;; Their attestation
   ["data" :any]
   ["prev-hash" :string]])  ;; Chain events together
```

---

## Journey-Based Document Assembly

### Background: Prior Art from Financial Products

Experience from a Clojure project modelling complex financial products (bonds) reveals a pattern that CLAIJ could generalize:

**The Journey Model:**
```
┌─────────────────────────────────────────────────────────────┐
│                    Product Schema (Large)                    │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐          │
│  │ View 1  │ │ View 2  │ │ View 3  │ │ View N  │          │
│  │ (subset)│ │ (subset)│ │ (subset)│ │ (subset)│          │
│  └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘          │
│       │          │          │          │                   │
│       ▼          ▼          ▼          ▼                   │
│   [Step 1]──▶[Step 2]──▶[Step 3]──▶[Step N]──▶ [Complete] │
│                                                             │
│  Output: PDF (legal) + Smart Contract instantiation        │
└─────────────────────────────────────────────────────────────┘
```

**Key Concepts:**

1. **Large Schema, Small Views**: The full product schema is complex; each journey step presents a manageable subset (view/projection)

2. **Role-Based Field Access**: Different roles see/edit different fields of the same underlying data
   ```clojure
   {:view "pricing-terms"
    :fields {"notional" {:read #{:trader :risk :legal}
                         :write #{:trader}}
             "rate" {:read #{:trader :risk}
                     :write #{:trader}}
             "legal-entity" {:read #{:legal :compliance}
                             :write #{:legal}}}}
   ```

3. **Schema Versioning**: Documents snapshot a schema version at creation, stay with it unless explicitly migrated

4. **Reflexive Foundation**: Schemas ground out in JSON Schema (m3!) - enabling schema editing tools

5. **Journey as FSM**: The workflow through views is itself a state machine

### What Was Missing (Lessons Learned)

| Issue | CLAIJ Solution |
|-------|----------------|
| Journey hardwired, not data-driven | FSM definition IS pure data |
| No smart transitions | Schema-matching transition predicates |
| Manual view definitions | Could derive views from role + schema |
| Single-party only | Store abstraction enables multi-party |
| No LLM assistance | Actions can include LLM for guidance/validation |

### The Multi-Party Fork-Join Vision

The Kotlin/Corda successor project attempted multi-party but struggled because each party only saw their data slice, making coherent views difficult.

**Better model: FSM that forks, travels, and recombines:**

```
                         ┌──────────────┐
                         │ Initiate     │
                         │ (Originator) │
                         └──────┬───────┘
                                │
                    ┌───────────┼───────────┐
                    ▼           ▼           ▼
              ┌──────────┐ ┌──────────┐ ┌──────────┐
              │ Legal    │ │ Risk     │ │ Client   │
              │ Review   │ │ Review   │ │ Sign-off │
              │ (Party A)│ │ (Party B)│ │ (Party C)│
              └────┬─────┘ └────┬─────┘ └────┬─────┘
                   │            │            │
                   │   Each party fills      │
                   │   their schema subset   │
                   │            │            │
                    └───────────┼───────────┘
                                ▼
                         ┌──────────────┐
                         │ Merge &      │
                         │ Validate     │
                         └──────┬───────┘
                                │
                    Schema ensures all parts
                    fit together correctly
                                │
                                ▼
                         ┌──────────────┐
                         │ Instantiate  │
                         │ Contract     │
                         └──────────────┘
```

### CLAIJ as Journey Engine

```clojure
(def-fsm bond-issuance-journey
  {:id "bond-issuance"
   :document-schema BondProductSchema  ;; The large target schema
   :store (->MultiPartyStore ...)
   :states
   {"initiate"
    {:executor "originator"
     :view (derive-view BondProductSchema :originator :initiate)
     :actions [{:name "capture-basics"
                :input-schema BasicTermsView
                :output-schema BasicTermsView}]
     :transitions [{:to "parallel-review" :when basics-complete?}]}
    
    "parallel-review"
    {:fork [{:to "legal-review" :party "legal" :view LegalView}
            {:to "risk-review" :party "risk" :view RiskView}
            {:to "client-approval" :party "client" :view ClientView}]}
    
    "legal-review"
    {:executor "legal"
     :view LegalView
     :actions [{:name "review-legal-terms"
                :fn (fn [config fsm ix state]
                      ;; LLM assists with legal clause suggestions
                      ...)}]
     :transitions [{:to "legal-complete" :when legal-approved?}]}
    
    ;; ... similar for risk, client ...
    
    "merge-reviews"
    {:join ["legal-complete" "risk-complete" "client-approved"]
     :actions [{:name "validate-combined"
                :fn (fn [config fsm ix state]
                      ;; Schema validates all parts fit together
                      (validate BondProductSchema 
                                (merge-document-parts state)))}]
     :transitions [{:to "generate-outputs" :when valid?}
                   {:to "reconcile-conflicts" :when conflicts?}]}
    
    "generate-outputs"
    {:actions [{:name "render-pdf" :fn render-legal-pdf}
               {:name "instantiate-contract" 
                :fn (fn [config fsm ix state]
                      ;; Generate CSL/Solidity and deploy
                      (deploy-smart-contract 
                        (compile-to-csl (get state "document"))))}]}
    
    "complete" {:terminal true}}})
```

### The Accumulating Document

Key insight: **The FSM isn't just managing flow - it's BUILDING something.**

```clojure
;; Trail events accumulate into a document
(defn trail->document [trail document-schema]
  (let [fragments (->> trail
                       (filter #(= "data-captured" (get % "type")))
                       (map #(get % "data")))]
    (reduce deep-merge {} fragments)))

;; At any point, we can:
;; 1. See what's been captured
;; 2. See what's missing (schema diff)
;; 3. Validate partial document
;; 4. Project views for different roles
```

### Schema-Driven View Derivation

Instead of manually defining views, derive them:

```clojure
(defn derive-view [full-schema role journey-step]
  (let [role-fields (get-role-permissions role)
        step-fields (get-step-fields journey-step)
        visible-fields (intersection role-fields step-fields)]
    (project-schema full-schema visible-fields)))

;; The view IS a schema - validation comes free
;; LLM can generate form UI from schema
;; Same schema validates input AND guides collection
```

### Why This Beats the Kotlin Approach

> "The way forwards is the smallest possible platform written in the fastest development strategy possible and everyone's time thence invested in the model rather than the code."

| Kotlin/Platform Approach | CLAIJ/Model Approach |
|--------------------------|----------------------|
| Big system, lots of platform code | Minimal platform, rich model |
| Manual stitching for multi-party | Store abstraction handles it |
| Proprietary RDF-like model | Standard JSON Schema (m3) |
| Hard to see coherent view | Schema always provides full picture |
| Slow to change | Model changes are data changes |
| No AI assistance | LLM integrated throughout |

### The AI Advantage

What the CTO missed: AI changes the economics completely.

```clojure
;; LLM can:
;; 1. Guide users through complex forms
{:name "assist-field-entry"
 :fn (fn [config fsm ix state]
       (llm/chat {:system "Help user understand this financial term"
                  :user (get-in state ["event" "question"])}))}

;; 2. Validate semantic correctness (not just schema)
{:name "validate-terms-consistency"
 :fn (fn [config fsm ix state]
       (llm/chat {:system "Check if these bond terms are internally consistent"
                  :user (json/encode (get state "document"))}))}

;; 3. Generate document sections
{:name "draft-legal-clause"
 :fn (fn [config fsm ix state]
       (llm/chat {:system "Draft covenant clause for this bond type"
                  :user (get-in state ["document" "terms"])}))}

;; 4. Explain implications to different roles
{:name "explain-to-client"
 :fn (fn [config fsm ix state]
       (llm/chat {:system "Explain these terms in plain English for the client"
                  :user (json/encode (project-view state :client))}))}
```

### Generalized Pattern: FSM as Document Factory

This isn't just for bonds. The pattern applies to:

- **Insurance policies** - Complex products, multiple parties, regulatory docs
- **Real estate transactions** - Buyer, seller, agents, lawyers, lenders
- **Grant applications** - Multiple reviewers, staged approvals
- **Clinical trials** - Protocol development, multi-site, regulatory
- **M&A deals** - Due diligence, multiple workstreams, final docs

All share:
1. Complex target document/product
2. Multiple parties contributing
3. Role-based visibility
4. Staged workflow
5. Final instantiation (contract, policy, filing)

### Implementation Path

1. **Document accumulation** - Trail events build document
2. **View derivation** - Schema + role → subset schema
3. **Fork-join states** - Parallel party workflows
4. **Merge validation** - Schema ensures coherence
5. **Output generation** - PDF, smart contract, etc.

The m3 library becomes even more critical - it's not just validation, it's the foundation for:
- Document structure
- View derivation
- Migration between versions
- Contract generation

---

## Integration Patterns (Original)

### Pattern 1: FSM as Transaction Coordinator

Use Case: User requests "swap 1 ETH for USDC on the best DEX"

```clojure
(def-fsm defi-swap
  {:id "defi-swap"
   :initial-state "analyzing"
   :states
   {"analyzing"
    {:actions [{:name "query-dex-prices"
                :fn (fn [config fsm ix state]
                      ;; LLM analyzes best route
                      {:dex "uniswap-v3"
                       :expected-output "1850 USDC"
                       :slippage "0.5%"})}]
     :transitions [{:to "awaiting-approval"
                    :when (fn [state] (get-in state ["event" "dex"]))}]}
    
    "awaiting-approval"
    {:wait-for-signal "user-approval"  ;; External signal!
     :transitions [{:to "executing" :when #(= "approved" (get % "signal"))}
                   {:to "cancelled" :when #(= "rejected" (get % "signal"))}]}
    
    "executing"
    {:actions [{:name "submit-swap-tx"
                :fn blockchain-action/submit-swap}]
     :transitions [{:to "confirming"}]}
    
    "confirming"
    {:wait-for-signal "tx-confirmed"  ;; Blockchain event!
     :timeout-ms 300000
     :transitions [{:to "complete" :when #(get % "tx-hash")}
                   {:to "failed" :when #(get % "timeout")}]}
    
    "complete" {:terminal true}
    "cancelled" {:terminal true}
    "failed" {:terminal true}}})
```

### Pattern 2: Multi-Agent Multi-Sig

Multi-signature wallets require multiple approvals. CLAIJ's "society of agents" maps perfectly:

- Agent A (Risk Assessment) - Evaluates transaction risk
- Agent B (Compliance) - Checks regulatory requirements  
- Agent C (Treasury) - Verifies budget alignment

Threshold: 2 of 3 required for approval.

### Pattern 3: Trail as Blockchain Anchor

Use blockchain for immutable audit anchoring:

```clojure
(defn anchor-trail-to-chain
  "Periodically hash trail and anchor to blockchain"
  [trail contract-address]
  (let [trail-hash (hash-trail trail)
        timestamp (System/currentTimeMillis)]
    (web3/contract-send audit-contract :anchorTrail
                        [trail-hash timestamp])))
```

### Pattern 4: AI Oracle (CLAIJ as Oracle Provider)

CLAIJ workflows could serve as oracle providers - AI analyzing off-chain data and submitting verified results to smart contracts.

---

## Security Considerations

### Critical Risks

| Risk | Mitigation |
|------|------------|
| Private key exposure | Never store keys in FSM state; use secure enclave |
| LLM hallucination | Require human approval for high-value transactions |
| Front-running | Use commit-reveal or private mempools |
| Oracle manipulation | Multi-source verification, stake-based reputation |
| Reentrancy | Stateless actions, idempotent operations |

### Recommended Patterns

1. **Value thresholds** - Automatic approval below threshold, human approval above
2. **Timelocks** - Delay execution to allow intervention
3. **Rate limiting** - Cap transaction frequency and value per period
4. **Kill switch** - Emergency pause mechanism

---

## Connection to Workflow Survey

From the workflow systems survey, the patterns most relevant for blockchain:

1. **Signals/External Events** (HIGH priority) - Critical for blockchain events
2. **Dynamic Parallel** - Useful for multi-chain operations  
3. **Continue-As-New** - Important for long-running monitoring workflows
4. **Fork-Join** - Essential for multi-party document assembly

The signal pattern we identified as high-priority directly enables blockchain event integration.

---

## References

- Context3 - MCP for Web3: https://context3.io/
- ElizaOS: https://engisphere.com/elizaos-bridging-ai-agents-with-web3-applications/
- Chainlink AI Oracle: https://blog.chain.link/oracle-networks-ai/
- cljs-web3-next: https://github.com/district0x/cljs-web3-next
- Web3 AI Agents Survey: https://arxiv.org/pdf/2508.02773
