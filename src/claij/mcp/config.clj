(ns claij.mcp.config)

(def config
  {:claude

   {:api-key
    (System/getenv "ANTHROPIC_API_KEY")

    :mcpServers
    {"claij-clojure-tools"
     {:command "bash"
      :args ["-c" "cd /home/jules/src/claij && ./bin/mcp-clojure-tools.sh"]
      :transport "stdio"}
     "claij-language-server"
     {:command "bash"
      :args ["-c" "cd /home/jules/src/claij && ./bin/mcp-language-server.sh"]
      :transport "stdio"}
     "emacs"
     {:command "socat"
      :args ["-" "UNIX-CONNECT:/home/jules/.emacs.d/emacs-mcp-server.sock"]
      :transport "stdio"}}}})
