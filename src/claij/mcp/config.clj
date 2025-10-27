(ns claij.mcp.config)

(def config
  {:claude

   {:api-key
    (System/getenv "ANTHROPIC_API_KEY")

    :mcpServers
    {"clojure-tools"
     {:command "bash"
      :args ["-c" "cd /home/jules/src/m3 && ./bin/mcp-claude.sh"]
      :transport "stdio"}
     "clojure-language-server"
     {:command "bash"
      :args ["-c" "cd /home/jules/src/m3 && ./bin/mcp-language-server.sh"]
      :transport "stdio"}
     "emacs"
     {:command "socat"
      :args ["-" "UNIX-CONNECT:/home/jules/.emacs.d/emacs-mcp-server.sock"]
      :transport "stdio"}}}})
