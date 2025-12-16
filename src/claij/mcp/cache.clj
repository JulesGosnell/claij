(ns claij.mcp.cache
  "MCP cache management for tools, prompts, and resources.
   
   The MCP cache tracks the current state of server capabilities.
   Each capability (tools, prompts, resources) can be:
   - nil: needs to be loaded/refreshed
   - map: contains the cached data from the server
   
   Cache invalidation happens when the server sends list_changed notifications.")

;; =============================================================================
;; Cache Operations
;; =============================================================================

(defn initialize-mcp-cache
  "Initialize MCP cache structure from initialization response capabilities.
  
  Takes current MCP cache state map and a capabilities map from an MCP initialize
  response. Returns updated cache with keys for each capability that supports list
  changes or subscriptions, initialized to nil to indicate they need to be loaded."
  [mcp-cache capabilities]
  (reduce-kv
   (fn [acc k {lc "listChanged" s "subscribe"}]
     (if (or lc s) (assoc acc k nil) acc))
   mcp-cache
   capabilities))

(defn invalidate-mcp-cache-item
  "Invalidate a specific MCP cache item by setting it to nil.
  
  Takes the current MCP cache map and a capability name (tools/prompts/resources).
  Returns updated cache with that capability set to nil, indicating it needs refresh."
  [mcp-cache capability]
  (assoc mcp-cache capability nil))

(defn refresh-mcp-cache-item
  "Merge fresh list response data into MCP cache.
  
  Takes the current MCP cache map and a result map from a list response.
  Returns updated cache with the result data merged in."
  [mcp-cache result]
  (merge mcp-cache result))
