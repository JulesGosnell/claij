# Story #113: OpenAPI-First REST API Design

## Research Summary

### Available Libraries

1. **navi** (lispyclouds/navi v0.1.5)
   - Converts OpenAPI 3 spec → Reitit routes
   - Handles path params, body params, responses
   - **Issue**: Uses Malli internally for coercion
   - Could potentially be forked/modified to use JSON Schema

2. **reitit** built-in coercion
   - `reitit.coercion.malli` - Malli (current)
   - `reitit.coercion.schema` - Plumatic Schema
   - `reitit.coercion.spec` - clojure.spec
   - **No JSON Schema coercion available**

3. **OpenAPI 3.1.0 in reitit** (v0.7.x)
   - Full JSON Schema support in OpenAPI output
   - Can generate OpenAPI spec from routes
   - But still needs Malli/Schema/Spec for coercion

### Options for Eliminating Malli

#### Option A: Custom JSON Schema Coercion for Reitit
Create `reitit.coercion.jsonschema` using m3 library:
```clojure
(defrecord JSONSchemaCoercion []
  Coercion
  (-request-coercer [this model] ...)
  (-response-coercer [this model] ...))
```

**Pros**: Clean integration with reitit ecosystem
**Cons**: More work, need to handle transformers

#### Option B: No Coercion Middleware - Validate in Handlers
Remove coercion middleware entirely, validate manually:
```clojure
{:handler (fn [req]
            (let [result (schema/validate MySchema (:body-params req))]
              (if (:valid? result)
                (handle-request (:value result))
                {:status 400 :body (:errors result)})))}
```

**Pros**: Simple, data-first, no middleware magic
**Cons**: Repetitive, less automatic

#### Option C: Fork/Modify navi for JSON Schema
Modify navi to output JSON Schema validation instead of Malli.

**Pros**: Spec-first approach maintained
**Cons**: Maintaining a fork

### Recommended Approach

**Hybrid: Option A + Dynamic FSM Endpoints**

1. Create a minimal JSON Schema coercion wrapper for reitit
2. Define static routes as OpenAPI spec (or data)
3. Implement dynamic FSM endpoint registration

## Dynamic FSM Endpoint Design

### FSM Schema Analysis
Each FSM contains:
- `"schemas"` - JSON Schema definitions (already native!)
- `"xitions"` - Transitions with entry/terminal info
- Entry event: `["start" "state-name"]`
- Terminal events: `["state-name" "end"]`

### Dynamic Route Structure
When FSM `code-review` is loaded:
```
POST /fsm/code-review/run
  Request:  { FSM entry schema }
  Response: { FSM terminal schema(s) via oneOf }
```

### Implementation Sketch
```clojure
(defn fsm->openapi-path [fsm]
  (let [id (get fsm "id")
        entry-schema (get-in fsm ["schemas" "$defs" "entry"])
        terminal-schemas (find-terminal-schemas fsm)]
    {(str "/fsm/" id "/run")
     {:post
      {:summary (str "Run " id " FSM")
       :operationId (str "run-" id)
       :requestBody {:content {"application/json" {:schema entry-schema}}}
       :responses {200 {:content {"application/json" 
                                   {:schema {"oneOf" terminal-schemas}}}}}}}}))

(defn register-fsm! [fsm]
  (let [path-spec (fsm->openapi-path fsm)
        handler (create-fsm-handler fsm)]
    (swap! dynamic-routes conj [path-spec handler])
    (rebuild-router!)))

(defn unregister-fsm! [fsm-id]
  (swap! dynamic-routes (fn [routes] (remove #(= fsm-id (:fsm-id %)) routes)))
  (rebuild-router!))
```

### Router Hot-Reload
Reitit supports router replacement. The app can swap routers atomically:
```clojure
(def router-atom (atom nil))

(defn rebuild-router! []
  (reset! router-atom
    (ring/router
      (concat static-routes @dynamic-routes)
      router-opts)))

(def app
  (fn [req]
    ((@router-atom) req)))
```

## Pipeline Simplification

### Current Mixed Schema Flow
```
FSM JSON Schema → Malli (server.clj) → OpenAPI/Swagger
                  ↓
MCP JSON Schema → Malli conversion → Validation
```

### Simplified Flow
```
FSM JSON Schema → JSON Schema validation (m3) → OpenAPI 3.1
                  ↓
MCP JSON Schema → Direct passthrough → OpenAPI 3.1
```

## Implementation Tasks

### Phase 1: Remove Redundant Endpoints
- [ ] Remove `/fsm/:id/graph.svg`
- [ ] Remove `/fsm/:id/graph.dot`
- [ ] Verify FSM doc page still works

### Phase 2: JSON Schema Coercion
- [ ] Create `claij.coercion.jsonschema` namespace
- [ ] Implement basic request validation using m3
- [ ] Implement basic response validation
- [ ] Test with existing routes

### Phase 3: Static OpenAPI Spec
- [ ] Create `resources/openapi.yaml` for static routes
- [ ] Generate routes from spec (or use data-driven definition)
- [ ] Replace Malli schemas with JSON Schema refs

### Phase 4: Dynamic FSM Endpoints
- [ ] Implement `fsm->openapi-path` 
- [ ] Implement `register-fsm!` / `unregister-fsm!`
- [ ] Router hot-reload mechanism
- [ ] Update Swagger UI to reflect dynamic routes

### Phase 5: Cleanup
- [ ] Remove Malli from deps.edn
- [ ] Update documentation
- [ ] Final test pass

## Open Questions for Jules

1. **Router hot-reload strategy**: Rebuild on every FSM change, or batch updates?
2. **FSM lifecycle**: Should `start-fsm` auto-register, or explicit `register-fsm!`?
3. **Endpoint naming**: `/fsm/{id}/run` or `/api/{id}` or configurable?
4. **Authentication**: Should dynamic FSM endpoints inherit auth from static config?
