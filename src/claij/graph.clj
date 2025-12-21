(ns claij.graph
  (:require [clojure.string :refer [join replace] :rename {replace string-replace}]))

;; fsm->dot: Generates Graphviz DOT for FSM visualization.
;; - Uses exact Graphviz layout (server-side DOT → client-side d3-graphviz or Cytoscape.js for perfect fidelity).
;; - For real-time animation in browser: keep this function unchanged, render with d3-graphviz (WebAssembly Graphviz), then highlight active state via JS/CSS.
;;   Example: g.select("#" current-state ").classed("active", true)
;; - Alternatives: Cytoscape.js or vis.js (easier animation but different layout).
;; Recommendation: d3-graphviz = same look + easy real-time highlighting.

;; thanks Grok

(defn fsm->dot [fsm]
  (let [{fsm-id "id"
         fsm-desc "description"
         fsm-prompts "prompts"
         states "states"
         xitions "xitions"
         :or {fsm-id "fsm" states [] xitions []}} fsm
        title-text (or fsm-desc (some->> fsm-prompts (join "\\n")))
        quote-id (fn [id] (str "\"" id "\""))
        escape-label (fn [s]
                       (-> s
                           (string-replace "\"" "\\\"")
                           (string-replace "\n" "\\n")))
        truncate-label (fn [s max-len]
                         (if (> (count s) max-len)
                           (str (subs s 0 max-len) "...")
                           s))
        format-hats (fn [hats]
                      (when (seq hats)
                        (str "\\n["
                             (join ", " (map (fn [h]
                                               (if (map? h)
                                                 (name (first (keys h)))
                                                 (str h)))
                                             hats))
                             "]")))]
    (str "digraph \"" fsm-id "\" {\n"
         "  rankdir=TB;\n"
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
                (for [{id "id" desc "description" action "action" prompts "prompts" hats "hats"} states
                      :when (and id (not= id "start") (not= id "end"))
                      :let [display-name (or desc id)
                            prompt-text (when (seq prompts)
                                          (truncate-label (join " " prompts) 80))
                            prompt-label (when prompt-text
                                           (str "\\n" (escape-label prompt-text)))
                            hat-label (format-hats hats)]]
                  (format "  %s [label=\"%s%s%s%s\"];\n"
                          (quote-id id) display-name
                          (if action (str "\\n(" action ")") "")
                          (or hat-label "")
                          (or prompt-label ""))))
         "\n  // transitions\n"
         (apply str
                (for [{[from to] "id" label "label" desc "description"} xitions
                      :let [texts (filter seq [label desc])
                            text (if (seq texts) (join "\\n" texts) to)
                            edge-label (escape-label text)]]
                  (format "  %s -> %s [label=\"%s\"];\n"
                          (quote-id (if (= from "start") "start" from))
                          (quote-id (if (= to "end") "end" to))
                          edge-label)))
         "}\n")))

(defn fsm->dot-with-hats
  "Generate DOT graph with hat-generated states expanded.
   
   This version expands hats for VISUALIZATION ONLY - it does not
   actually initialize MCP connections or other services. It generates
   the structural FSM fragments that hats would produce.
   
   Parameters:
   - fsm: The FSM definition with hats
   
   Hat expansion rules (visualization):
   - mcp hat: creates {state-id}-mcp service state with loopback transitions
   - Other hats: shown as labels only (no expansion)"
  [fsm]
  (let [;; Expand MCP hats structurally (no actual connection)
        expand-mcp-hat (fn [state-id config]
                         (let [service-id (str state-id "-mcp")]
                           {"states" [{"id" service-id
                                       "description" "MCP Tools"
                                       "action" "mcp-service"}]
                            "xitions" [{"id" [state-id service-id]
                                        "label" "tool-call"}
                                       {"id" [service-id state-id]
                                        "label" "tool-result"}]}))

        ;; Parse hat declaration to get name and config
        parse-hat (fn [decl]
                    (cond
                      (string? decl) [decl {}]
                      (keyword? decl) [(name decl) {}]
                      (map? decl) (let [[k v] (first decl)]
                                    [(if (keyword? k) (name k) (str k)) v])
                      :else [nil {}]))

        ;; Expand all hats on a state
        expand-state-hats (fn [state]
                            (let [state-id (get state "id")
                                  hats (get state "hats" [])]
                              (reduce
                               (fn [fragments hat-decl]
                                 (let [[hat-name config] (parse-hat hat-decl)]
                                   (case hat-name
                                     "mcp" (conj fragments (expand-mcp-hat state-id config))
                                    ;; Other hats - no expansion for now
                                     fragments)))
                               []
                               hats)))

        ;; Collect all fragments from all states
        all-fragments (mapcat expand-state-hats (get fsm "states" []))

        ;; Merge fragments into FSM
        expanded-fsm (reduce
                      (fn [f fragment]
                        (-> f
                            (update "states" (fnil into []) (get fragment "states" []))
                            (update "xitions" (fnil into []) (get fragment "xitions" []))))
                      fsm
                      all-fragments)

        ;; Get original state IDs before expansion
        original-states (set (map #(get % "id") (get fsm "states")))
        expanded-states (get expanded-fsm "states")
        hat-states (remove #(or (original-states (get % "id"))
                                (#{"start" "end"} (get % "id")))
                           expanded-states)

        ;; Group hat states by parent
        grouped (group-by (fn [state]
                            (let [id (get state "id")]
                              (some #(when (and (not= % id)
                                                (.startsWith ^String id (str % "-")))
                                       %)
                                    original-states)))
                          hat-states)
        hat-groups (dissoc grouped nil)]

    ;; Generate DOT with clusters
    (let [{fsm-id "id"
           fsm-desc "description"
           fsm-prompts "prompts"
           states "states"
           xitions "xitions"
           :or {fsm-id "fsm" states [] xitions []}} expanded-fsm
          title-text (or fsm-desc (some->> fsm-prompts (join "\\n")))
          quote-id (fn [id] (str "\"" id "\""))
          escape-label (fn [s]
                         (-> s
                             (string-replace "\"" "\\\"")
                             (string-replace "\n" "\\n")))
          truncate-label (fn [s max-len]
                           (if (> (count s) max-len)
                             (str (subs s 0 max-len) "...")
                             s))
          format-hats (fn [hats]
                        (when (seq hats)
                          (str "\\n["
                               (join ", " (map (fn [h]
                                                 (let [[n _] (parse-hat h)]
                                                   n))
                                               hats))
                               "]")))
          hat-state-ids (set (map #(get % "id") hat-states))
          render-state (fn [{id "id" desc "description" action "action" prompts "prompts" hats "hats"}]
                         (let [display-name (or desc id)
                               prompt-text (when (seq prompts)
                                             (truncate-label (join " " prompts) 80))
                               prompt-label (when prompt-text
                                              (str "\\n" (escape-label prompt-text)))
                               hat-label (format-hats hats)]
                           (format "    %s [label=\"%s%s%s%s\"];\n"
                                   (quote-id id) display-name
                                   (if action (str "\\n(" action ")") "")
                                   (or hat-label "")
                                   (or prompt-label ""))))]
      (str "digraph \"" fsm-id "\" {\n"
           "  rankdir=TB;\n"
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
                  (for [[parent-id group-states] hat-groups
                        :let [cluster-name (str "cluster_" (string-replace parent-id "-" "_"))
                              cluster-label (str parent-id " hats")]]
                    (str "  subgraph " cluster-name " {\n"
                         "    label=\"" cluster-label "\";\n"
                         "    style=dashed;\n"
                         "    color=gray60;\n"
                         "    fontcolor=gray40;\n"
                         (apply str (map render-state group-states))
                         "  }\n")))
           "\n  // transitions\n"
           (apply str
                  (for [{[from to] "id" label "label" desc "description"} xitions
                        :let [texts (filter seq [label desc])
                              text (if (seq texts) (join "\\n" texts) to)
                              edge-label (escape-label text)]]
                    (format "  %s -> %s [label=\"%s\"];\n"
                            (quote-id (if (= from "start") "start" from))
                            (quote-id (if (= to "end") "end" to))
                            edge-label)))
           "}\n"))))
