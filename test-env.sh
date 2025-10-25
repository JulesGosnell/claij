#!/usr/bin/env bash
# Test that .env loading works

cd /home/jules/src/claij

echo "Testing .env loading..."
clojure -M:dev -e "
(require 'user)
(println \"OPENROUTER_API_KEY found:\") 
(println (boolean (System/getProperty \"OPENROUTER_API_KEY\")))
(println \"First 20 chars:\")
(println (subs (or (System/getProperty \"OPENROUTER_API_KEY\") \"NOTFOUND\") 0 20))
" 2>&1 | grep -v "Downloading" | tail -10
