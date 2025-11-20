(ns claij.graph
  (:require [clojure.string :refer [join replace]]))

;; fsm->dot: Generates Graphviz DOT for FSM visualization.
;; - Uses exact Graphviz layout (server-side DOT → client-side d3-graphviz or Cytoscape.js for perfect fidelity).
;; - For real-time animation in browser: keep this function unchanged, render with d3-graphviz (WebAssembly Graphviz), then highlight active state via JS/CSS.
;;   Example: g.select("#" current-state ").classed("active", true)
;; - Alternatives: Cytoscape.js or vis.js (easier animation but different layout).
;; Recommendation: d3-graphviz = same look + easy real-time highlighting.

;; TODO: Enhance transition labels by using JSON Schema documentation.
;; JSON Schema supports top-level "title" (short name) and "description" (detailed explanation).
;; Plan: When schemas are updated, add "title" and/or top-level "description" to each transition's schema.
;; Then update label logic:
;;   prefer xition "label" → xition "description" → schema "title" → schema "description" → fallback to 'to' state
;; This gives concise edge labels (title) while keeping full details available if needed.


;; thanks Grok

(defn fsm->dot [fsm]
  (let [{fsm-id     "id"
         fsm-desc   "description"
         fsm-prompts "prompts"
         states     "states"
         xitions    "xitions"
         :or        {fsm-id "fsm" states [] xitions []}} fsm
        title-text (or fsm-desc (some->> fsm-prompts (join "\\n")))]
    (str "digraph \"" fsm-id "\" {\n"
         "  rankdir=TB;\n"
         "  node [shape=box, style=rounded, fontname=\"Helvetica\", fontsize=10];\n"
         "  edge [fontname=\"Helvetica\", fontsize=9];\n"
         (when title-text
           (str "  title [shape=plaintext label=<<FONT POINT-SIZE=\"14\" COLOR=\"gray40\">"
                (replace title-text "\n" "<BR/>")
                "</FONT>>];\n"
                "  { rank=same title }\n"
                "  title -> start [style=invis];\n"))
         "  start [shape=doublecircle, fillcolor=lightgreen, style=filled];\n"
         "  end   [shape=doublecircle, fillcolor=lightcoral, style=filled];\n"
         "\n  // states\n"
         (apply str
                (for [{id "id" action "action" prompts "prompts"} states
                      :when (and id (not= id "start") (not= id "end"))
                      :let [prompt-label (when (seq prompts)
                                           (str "\\n" (replace (join "\\n" prompts) "\n" "\\n")))]]
                  (format "  %s [label=\"%s%s%s\"];\n"
                          id id (if action (str "\\n(" action ")") "") (or prompt-label ""))))
         "\n  // transitions\n"
         (apply str
                (for [{[from to] "id" label "label" desc "description"} xitions
                      :let [texts (filter seq [label desc])
                            text  (if (seq texts) (join "\\n" texts) to)
                            label (replace text "\n" "\\n")]]
                  (format "  %s -> %s [label=\"%s\"];\n"
                          (if (= from "start") "start" from)
                          (if (= to "end") "end" to)
                          label)))
         "}\n")))
