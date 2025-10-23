(defun claij--chat-internal (llm-name endpoint prompt)
  "Send PROMPT to LLM-NAME at ENDPOINT and return response as string."
  (let* ((base-url (if current-prefix-arg
                       (read-string "Base URL: " "http://localhost:8000")
                     "http://localhost:8000"))
         (url (concat base-url endpoint))
         (url-request-method "POST")
         (url-request-extra-headers
          '(("Content-Type" . "text/plain")))
         (url-request-data
          (encode-coding-string prompt 'utf-8)))
    (with-current-buffer (url-retrieve-synchronously url)
      (goto-char (point-min))
      (re-search-forward "^$")
      (let ((response (string-trim (buffer-substring (point) (point-max)))))
        (kill-buffer)
        response))))

(defun claude (prompt)
  "Send PROMPT to Claude and return response as string.
When called interactively, display in minibuffer.
With prefix argument, prompt for base URL."
  (interactive "sPrompt for Claude: ")
  (let ((response (claij--chat-internal "Claude" "/chat/claude" prompt)))
    (when (called-interactively-p 'any)
      (message "Claude: %s" response))
    response))

(defun grok (prompt)
  "Send PROMPT to Grok and return response as string.
When called interactively, display in minibuffer.
With prefix argument, prompt for base URL."
  (interactive "sPrompt for Grok: ")
  (let ((response (claij--chat-internal "Grok" "/chat/grok" prompt)))
    (when (called-interactively-p 'any)
      (message "Grok: %s" response))
    response))

(defun gpt (prompt)
  "Send PROMPT to GPT and return response as string.
When called interactively, display in minibuffer.
With prefix argument, prompt for base URL."
  (interactive "sPrompt for GPT: ")
  (let ((response (claij--chat-internal "GPT" "/chat/gpt" prompt)))
    (when (called-interactively-p 'any)
      (message "GPT: %s" response))
    response))

(defun gemini (prompt)
  "Send PROMPT to Gemini and return response as string.
When called interactively, display in minibuffer.
With prefix argument, prompt for base URL."
  (interactive "sPrompt for Gemini: ")
  (let ((response (claij--chat-internal "Gemini" "/chat/gemini" prompt)))
    (when (called-interactively-p 'any)
      (message "Gemini: %s" response))
    response))
