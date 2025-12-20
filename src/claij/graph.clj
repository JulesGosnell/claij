(ns claij.graph
  (:require [clojure.string :refer [join replace] :rename {replace string-replace}]))

;; fsm->dot: Generates Graphviz DOT for FSM visualization.
;; - Uses exact Graphviz layout (server-side DOT → client-side d3-graphviz or Cytoscape.js for perfect fidelity).
;; - For real-time animation in browser: keep this function unchanged, render with d3-graphviz (WebAssembly Graphviz), then highlight active state via JS/CSS.
;;   Example: g.select("#" current-state ").classed("active", true)
;; - Alternatives: Cytoscape.js or vis.js (easier animation but different layout).
;; Recommendation: d3-graphviz = same look + easy real-time highlighting.

;; TODO: Enhance transition labels by using Malli schema documentation.
;; Malli schemas support :description in map metadata, e.g. [:map {:description "..."} ...]
;; Plan: When schemas are updated, add :description to each transition's schema.
;; Then update label logic:
;;   prefer xition "label" → xition "description" → schema :description → fallback to 'to' state
;; This gives concise edge labels while keeping full details available if needed.

;; thanks Grok

(defn fsm->dot [fsm]
  (let [{fsm-id "id"
         fsm-desc "description"
         fsm-prompts "prompts"
         states "states"
         xitions "xitions"
         :or {fsm-id "fsm" states [] xitions []}} fsm
        title-text (or fsm-desc (some->> fsm-prompts (join "\\n")))
        ;; Escape quotes and limit label length for readability
        escape-label (fn [s]
                       (-> s
                           (string-replace "\"" "\\\"")
                           (string-replace "\n" "\\n")))
        truncate-label (fn [s max-len]
                         (if (> (count s) max-len)
                           (str (subs s 0 max-len) "...")
                           s))
        ;; Quote node IDs to handle hyphens and special chars
        quote-id (fn [id] (str "\"" id "\""))]
    (str "digraph \"" fsm-id "\" {\n"
         "  rankdir=TB;\n"
         "  splines=curved;\n"
         "  node [shape=box, style=rounded, fontname=\"Helvetica\", fontsize=10];\n"
         "  edge [fontname=\"Helvetica\", fontsize=9];\n"
         (when title-text
           (str "  title [shape=plaintext label=<<FONT POINT-SIZE=\"14\" COLOR=\"gray40\">"
                (string-replace title-text "\n" "<BR/>")
                "</FONT>>];\n"
                "  { rank=same title }\n"
                "  title -> \"start\" [style=invis];\n"))
         "  \"start\" [shape=doublecircle, fillcolor=lightgreen, style=filled];\n"
         "  \"end\"   [shape=doublecircle, fillcolor=lightcoral, style=filled];\n"
         "\n  // states\n"
         (apply str
                (for [{id "id" desc "description" action "action" prompts "prompts"} states
                      :when (and id (not= id "start") (not= id "end"))
                      :let [;; Use description if available, otherwise id
                            display-name (or desc id)
                            ;; Truncate long prompts for graph readability
                            prompt-text (when (seq prompts)
                                          (truncate-label (join " " prompts) 80))
                            prompt-label (when prompt-text
                                           (str "\\n" (escape-label prompt-text)))]]
                  (format "  %s [label=\"%s%s%s\"];\n"
                          (quote-id id) display-name (if action (str "\\n(" action ")") "") (or prompt-label ""))))
         "\n  // transitions\n"
         (apply str
                (for [{[from to] "id" label "label" desc "description"} xitions
                      :let [;; Only show label if explicitly provided (not just destination)
                            texts (filter seq [label desc])
                            edge-label (when (seq texts) (escape-label (join "\\n" texts)))
                            from-id (quote-id (if (= from "start") "start" from))
                            to-id (quote-id (if (= to "end") "end" to))]]
                  (if edge-label
                    (format "  %s -> %s [label=\"%s\"];\n" from-id to-id edge-label)
                    (format "  %s -> %s;\n" from-id to-id))))
         "}\n")))

(defn fsm->dot-with-hats
  "Generate DOT graph with hat-generated states expanded.
   
   Requires claij.hat namespace - will fail if not available.
   Use this to visualize the full FSM including hat fragments.
   Hat-generated states are grouped in subgraph clusters.
   
   Parameters:
   - fsm: The FSM definition with hats
   - registry: Hat registry from make-hat-registry
   - context: Optional initial context (default {})"
  ([fsm registry] (fsm->dot-with-hats fsm registry {}))
  ([fsm registry context]
   (let [don-hats (requiring-resolve 'claij.hat/don-hats)
         ;; Get original state IDs before expansion
         original-states (set (map #(get % "id") (get fsm "states")))
         [_ctx' expanded-fsm] (don-hats context fsm registry)
         ;; Find hat-generated states (not in original, not start/end)
         expanded-states (get expanded-fsm "states")
         hat-states (remove #(or (original-states (get % "id"))
                                 (#{"start" "end"} (get % "id")))
                            expanded-states)
         ;; Group hat states by parent (prefix before first hyphen that matches original)
         grouped (group-by (fn [state]
                             (let [id (get state "id")]
                               (some #(when (and (not= % id)
                                                 (.startsWith ^String id (str % "-")))
                                        %)
                                     original-states)))
                           hat-states)
         ;; Remove nil group (states that don't match pattern)
         hat-groups (dissoc grouped nil)]
     ;; Generate DOT with clusters
     (let [{fsm-id "id"
            fsm-desc "description"
            fsm-prompts "prompts"
            states "states"
            xitions "xitions"
            :or {fsm-id "fsm" states [] xitions []}} expanded-fsm
           title-text (or fsm-desc (some->> fsm-prompts (join "\\n")))
           escape-label (fn [s]
                          (-> s
                              (string-replace "\"" "\\\"")
                              (string-replace "\n" "\\n")))
           truncate-label (fn [s max-len]
                            (if (> (count s) max-len)
                              (str (subs s 0 max-len) "...")
                              s))
           quote-id (fn [id] (str "\"" id "\""))
           hat-state-ids (set (map #(get % "id") hat-states))
           render-state (fn [{id "id" desc "description" action "action" prompts "prompts"}]
                          (let [;; Use description if available, otherwise id
                                display-name (or desc id)
                                prompt-text (when (seq prompts)
                                              (truncate-label (join " " prompts) 80))
                                prompt-label (when prompt-text
                                               (str "\\n" (escape-label prompt-text)))]
                            (format "    %s [label=\"%s%s%s\"];\n"
                                    (quote-id id) display-name
                                    (if action (str "\\n(" action ")") "")
                                    (or prompt-label ""))))]
       (str "digraph \"" fsm-id "\" {\n"
            "  rankdir=TB;\n"
            "  splines=curved;\n"
            "  node [shape=box, style=rounded, fontname=\"Helvetica\", fontsize=10];\n"
            "  edge [fontname=\"Helvetica\", fontsize=9];\n"
            (when title-text
              (str "  title [shape=plaintext label=<<FONT POINT-SIZE=\"14\" COLOR=\"gray40\">"
                   (string-replace title-text "\n" "<BR/>")
                   "</FONT>>];\n"
                   "  { rank=same title }\n"
                   "  title -> \"start\" [style=invis];\n"))
            "  \"start\" [shape=doublecircle, fillcolor=lightgreen, style=filled];\n"
            "  \"end\"   [shape=doublecircle, fillcolor=lightcoral, style=filled];\n"
            "\n  // states (non-hat)\n"
            (apply str
                   (for [state states
                         :let [id (get state "id")]
                         :when (and id
                                    (not= id "start")
                                    (not= id "end")
                                    (not (hat-state-ids id)))]
                     (render-state state)))
            "\n  // hat clusters\n"
            (apply str
                   (for [[parent-id hat-states] hat-groups
                         :let [cluster-name (str "cluster_" (string-replace parent-id "-" "_"))
                               ;; Use first hat state's description for cluster label if available
                               cluster-label (or (some #(get % "description") hat-states)
                                                 (str parent-id " hat"))]]
                     (str "  subgraph " cluster-name " {\n"
                          "    label=\"" cluster-label "\";\n"
                          "    style=dashed;\n"
                          "    color=gray60;\n"
                          "    fontcolor=gray40;\n"
                          (apply str (map render-state hat-states))
                          "  }\n")))
            "\n  // transitions\n"
            (apply str
                   (for [{[from to] "id" label "label" desc "description"} xitions
                         :let [;; Only show label if explicitly provided
                               texts (filter seq [label desc])
                               edge-label (when (seq texts) (escape-label (join "\\n" texts)))
                               from-id (quote-id (if (= from "start") "start" from))
                               to-id (quote-id (if (= to "end") "end" to))]]
                     (if edge-label
                       (format "  %s -> %s [label=\"%s\"];\n" from-id to-id edge-label)
                       (format "  %s -> %s;\n" from-id to-id))))
            "}\n")))))
