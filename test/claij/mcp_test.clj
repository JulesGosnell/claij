(ns claij.mcp-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
   [clojure.data.json :refer [write-str read-str]]
   [clojure.core.async :refer [chan <!! >!! go-loop <!]]
   [claij.mcp.bridge :refer [start-mcp-bridge]]
   [claij.mcp :refer [initialise-request initialised-notification list-tools-request list-prompts-request list-resources-request]]))

;; send:

;; (>!! ic initialise-request)
;; (>!! ic initialised-notification)
;; (>!! ic list-tools-request)
;; (>!! ic list-prompts-request)
;; (>!! ic list-resources-request)

;; x16
(def tools-list-changed-notification
  {"jsonrpc" "2.0", "method" "notifications/tools/list_changed"})
;; x 5
(def resources-list-changed-notification
  {"jsonrpc" "2.0", "method" "notifications/resources/list_changed"})
;; x 8
(def prompts-list-changed-notification
  {"jsonrpc" "2.0", "method" "notifications/prompts/list_changed"})

;; initialize reponse - x1
(def initialize-response
  {"jsonrpc" "2.0",
   "id" 1,
   "result"
   {"protocolVersion" "2024-11-05",
    "capabilities"
    {"logging" {},
     "prompts" {"listChanged" true},
     "resources" {"subscribe" true, "listChanged" true},
     "tools" {"listChanged" true}},
    "serverInfo" {"name" "clojure-server", "version" "0.1.0"}}})

;; list tools response - x1
(def list-tools-response
  {"jsonrpc" "2.0",
   "id" 2,
   "result"
   {"tools"
    [{"name" "LS",
      "description"
      "Returns a recursive tree view of files and directories starting from the specified path. The path parameter must be an absolute path, not a relative path. You should generally prefer the `glob_files` and `fx_grep` tools, if you know which directories to search.",
      "inputSchema"
      {"type" "object",
       "properties"
       {"path" {"type" "string"},
        "max_depth"
        {"type" "integer",
         "description" "Maximum depth to traverse (optional)"},
        "limit"
        {"type" "integer",
         "description"
         "Maximum number of entries to show (default: 100)"}},
       "required" ["path"]}}
     {"name" "read_file",
      "description"
      "Smart file reader with pattern-based exploration for Clojure files.\n   \nFor Clojure files (.clj, .cljc, .cljs, .bb):\n\nThis tool defaults to an expandable collapsed view to quickly grab the information you need from a Clojure file.\nIf called without `name_pattern` or `content_pattern` it will return the file content where \nyou will see only function signatures. This gives you a quick overview of the file.\n\nWhen you want to see more, this tool has a grep functionality where you\ncan give patterns to match the names for top level definitions\n`name_pattern` or match the bodies of top level definitions `content_pattern`.\n\nThe functions that match these patterns will be the only functions expanded in collapsed view.\n\nFor defmethod forms:\n- The name includes both the method name and the dispatch value (e.g., \"area :rectangle\")\n- For vector dispatch values, use the exact pattern (e.g., \"dispatch-with-vector \\[:feet :inches\\]\")\n- For qualified/namespaced multimethod names, include the namespace (e.g., \"tool-system/validate-inputs :clojure-eval\")\n\nFor text files (non-Clojure):\n- When `collapsed: true` and a pattern is provided (`content_pattern` or `name_pattern`), shows only lines matching the pattern with 10 lines of context before and after\n\nFor all other file types:\n- Collapsed view will not be applied and will return the raw contents of the file\n\nParameters:\n- path: Path to the file (required)\n- collapsed: Show collapsed view (default: true)\n\nCollapsed View mode function expansion parameters:\n\n- name_pattern: Regex to match function names (e.g., \"validate.*\")\n- content_pattern: Regex to match function content (e.g., \"try|catch\")\n\nExample usage for defmethod forms:\n- Find all implementations of a multimethod: `name_pattern: \"area\"`\n- Find specific dispatch values: `name_pattern: \"area :circle\"`\n- Find vector dispatch values: `name_pattern: \"convert-length \\\\[:feet :inches\\\\]\"`\n- Find namespace-qualified methods: `name_pattern: \"tool-system/validate-inputs\"`\n\nExample usage for text files:\n- Find error messages: `content_pattern: \"ERROR|WARN\"`\n- Find specific log entries: `content_pattern: \"\\\\[2024-01-15.*ERROR\\\\]\"`\n\nNon collapsed view mode respects these parameters:\n\n- line_offset: Line to start reading from (non-collapsed mode only, default: 0)\n- limit: Maximum lines to read (non-collapsed mode only, default: 2000)\n\nBy default, reads up to 2000 lines, truncating lines longer than 1000 characters.",
      "inputSchema"
      {"type" "object",
       "properties"
       {"path"
        {"type" "string", "description" "Path to the file to read"},
        "collapsed"
        {"type" "boolean",
         "description"
         "Whether to show collapsed view for Clojure files (default: true)"},
        "name_pattern"
        {"type" "string",
         "description"
         "Pattern to match function names (e.g., \"validate.*\")"},
        "content_pattern"
        {"type" "string",
         "description"
         "Pattern to match function content (e.g., \"try|catch\")"},
        "line_offset"
        {"type" "integer",
         "description"
         "Line to start reading from for raw mode (default: 0)"},
        "limit"
        {"type" "integer",
         "description" "Maximum lines to read (default: 2000)"}},
       "required" ["path"]}}
     {"name" "grep",
      "description"
      "Fast content search tool that works with any codebase size.\n- Finds the paths to files that have matching contents using regular expressions.\n- Supports full regex syntax (eg. \"log.*Error\", \"function\\s+\\w+\", etc.).\n- Filter files by pattern with the include parameter (eg. \"*.js\", \"*.{ts,tsx}\").\n- Returns matching file paths sorted by modification time.\n- Use this tool when you need to find files containing specific patterns.\n- When you are doing an open ended search that may require multiple rounds of globbing and grepping, use the `dispatch_agent` tool instead",
      "inputSchema"
      {"type" "object",
       "properties"
       {"path"
        {"type" "string",
         "description"
         "The directory to search in. Defaults to the current working directory."},
        "pattern"
        {"type" "string",
         "description"
         "The regular expression pattern to search for in file contents"},
        "include"
        {"type" "string",
         "description"
         "File pattern to include in the search (e.g. \"*.clj\", \"*.{clj,cljs}\")"},
        "max_results"
        {"type" "integer",
         "description"
         "Maximum number of results to return (default: 1000)"}},
       "required" ["pattern"]}}
     {"name" "glob_files",
      "description"
      "Fast file pattern matching tool that works with any codebase size.\n - Supports glob patterns like \"**/*.clj\" or \"src/**/*.cljs\".\n - Returns matching file paths sorted by modification time (most recent first).\n - Use this tool when you need to find files by name patterns.\n - When you are doing an open ended search that may require multiple rounds of globbing and grepping, use the `dispatch_agent` tool instead",
      "inputSchema"
      {"type" "object",
       "properties"
       {"path"
        {"type" "string",
         "description"
         "Root directory to start the search from (defaults to current working directory)"},
        "pattern"
        {"type" "string",
         "description"
         "Glob pattern (e.g. \"**/*.clj\", \"src/**/*.tsx\")"},
        "max_results"
        {"type" "integer",
         "description"
         "Maximum number of results to return (default: 1000)"}},
       "required" ["pattern"]}}
     {"name" "think",
      "description"
      "Use the tool to think about something. It will not obtain new information or make any changes to the repository, but just log the thought. Use it when complex reasoning or brainstorming is needed. \nCommon use cases:\n* when having difficulty finding/reading the files you need\n* when having difficulty writing out code\n* when having difficulty using the tools or the clojure tools\n* When exploring a repository and discovering the source of a bug, call this tool to brainstorm several unique ways of fixing the bug, and assess which change(s) are likely to be simplest and most effective.\n* After receiving test results, use this tool to brainstorm ways to fix failing tests.\n* When planning a complex refactoring, use this tool to outline different approaches and their tradeoffs.\n* When designing a new feature, use this tool to think through architecture decisions and implementation details.\n* When debugging a complex issue, use this tool to organize your thoughts and hypotheses.\nThe tool simply logs your thought process for better transparency and does not execute any code or make changes.",
      "inputSchema"
      {"type" "object",
       "properties"
       {"thought"
        {"type" "string", "description" "The thought to log"}},
       "required" ["thought"]}}
     {"name" "scratch_pad",
      "description"
      "A persistent scratch pad for storing structured data between tool calls. Accepts any JSON value (objects, arrays, strings, numbers, booleans, null) and stores them at nested paths using set_path, get_path, delete_path operations.\n\nTHIS IS YOUR GO-TO TOOL FOR PLANNING.\n\nYour persistent workspace for planning, organizing thoughts, and maintaining state across tool invocations.\n\nThis tool can be used to:\n * develop and track multi-step plans\n * maintain task lists and task tracking\n * store intermediate results between operations\n * keep notes about your current approach or strategy\n * maintain a list of files or resources to process\n * build up complex data structures incrementally\n * share context between different tool calls\n * any other persistent storage needs during your work\n\nThe scratch pad is for persistent storage and state management, not for formatting output. Display information directly in your chat responses. However, when the scratch pad contains user-relevant state (like tasks lists or tracked progress), you should retrieve and display updates after modifications.\n\nUse the scratch pad when you need to:\n- Track progress across multiple tool calls\n- Build data structures incrementally  \n- Maintain state between operations\n- Store intermediate results for later use\n\nDon't use the scratch pad to:\n- Format text for display\n- Store static information just to retrieve and show it\n- Replace direct chat responses\n\nCORE OPERATIONS:\n- set_path: Store a value (not null) at a path, returns the parent container\n- get_path: Retrieve a value from a path, returns the value or nil\n- delete_path: Remove only the leaf value from a path, returns a confirmation message\n- inspect: Display the entire structure (or a specific path) with truncation at specified depth\n\nWARNING: null values can not be stored in the scratch pad, if you attempt to store a null value the set_path will fail.\n\nTRACKING PLANS WITH TASK LISTS:\n\nRecommended schema for a Task:\n{\n  task: \"Description of the task\",\n  done: false,\n  priority: \"high\", // optional: \"high\", \"medium\", \"low\"\n  context: \"Additional details\", // optional\n  subtasks: [  // optional: array of subtask objects\n    {task: \"Subtask 1\", done: false},\n    {task: \"Subtask 2\", done: true}\n  ]\n}\n\nFirst add multiple tasks items at once as an array:\n- Entire array:\n  op: set_path\n  path: [\"tasks\"]\n  value: [\n    {task: \"Write tests\", done: false, priority: \"high\"},\n    {task: \"Review PR\", done: false, priority: \"high\"},\n    {task: \"Update docs\", done: false, priority: \"medium\", subtasks: [\n      {task: \"Update API docs\", done: false},\n      {task: \"Update README\", done: false}\n    ]}\n  ]\n  explanation: Adding multiple tasks at once\n\nAdding tasks items:\n- First item:\n  op: set_path\n  path: [\"tasks\", 3]\n  value: {task: \"Run tests\", done: false}\n  explanation: Adding write tests task\n\n- Next item:\n  op: set_path\n  path: [\"tasks\", 4]\n  value: {task: \"Notify user\", done: false, priority: \"high\"}\n  explanation: Adding high priority task\n\nChecking off completed tasks:\n- Mark as done:\n  op: set_path\n  path: [\"tasks\", 0, \"done\"]\n  value: true\n  explanation: Completed writing tests\n\n- Mark subtask as done:\n  op: set_path\n  path: [\"tasks\", 2, \"subtasks\", 0, \"done\"]\n  value: true\n  explanation: Completed API docs update\n\nAdding a new task to the array:\n- Append to array:\n  op: set_path\n  path: [\"tasks\", 3]\n  value: {task: \"Deploy to production\", done: false, priority: \"high\"}\n  explanation: Adding deployment task\n\nViewing tasks:\n- All tasks:\n  op: get_path\n  path: [\"tasks\"]\n  explanation: Get all tasks\n\n- Specific task:\n  op: get_path\n  path: [\"tasks\", 0]\n  explanation: Checking first task details\n\n- View with depth limit:\n  op: inspect\n  path: [\"tasks\"]\n  depth: 2\n  explanation: View tasks with limited nesting",
      "inputSchema"
      {"type" "object",
       "properties"
       {"op"
        {"type" "string",
         "enum" ["set_path" "get_path" "delete_path" "inspect"],
         "description"
         "The operation to perform:\n * set_path: set a value at a path\n * get_path: retrieve a value at a path\n * delete_path: remove the value at the path from the data structure\n * inspect: view the datastructure (or a specific path within it) up to a certain depth\n"},
        "path"
        {"type" "array",
         "items" {"type" "string"},
         "description"
         "Path to the data location (array of string or number keys) - used for set_path, get_path, delete_path, and optionally inspect"},
        "value"
        {"description"
         "Value to store (for set_path). Can be ANY JSON value EXCEPT null: object, array, string, number, boolean.",
         "type" "object"},
        "explanation"
        {"type" "string",
         "description"
         "Explanation of why this operation is being performed"},
        "depth"
        {"type" "number",
         "description"
         "(Optional) For inspect operation: Maximum depth to display (default: 5). Must be a positive integer."}},
       "required" ["op" "explanation"]}}
     {"name" "clojure_eval",
      "description"
      "Takes a Clojure Expression and evaluates it in the current namespace. For example, providing \"(+ 1 2)\" will evaluate to 3.\n\nThis tool is intended to execute Clojure code. This is very helpful for verifying that code is working as expected. It's also helpful for REPL driven development.\n\nIf you send multiple expressions they will all be evaluated individually and their output will be clearly partitioned.\n\nIf the returned value is too long it will be truncated.\n\nIMPORTANT: When using `require` to reload namespaces ALWAYS use `:reload` to ensure you get the latest version of files.\n\nREPL helper functions are automatically loaded in the 'clj-mcp.repl-tools' namespace, providing convenient namespace and symbol exploration:\n\nNamespace/Symbol inspection functions:\n  clj-mcp.repl-tools/list-ns           - List all available namespaces\n  clj-mcp.repl-tools/list-vars         - List all vars in namespace\n  clj-mcp.repl-tools/doc-symbol        - Show documentation for symbol\n  clj-mcp.repl-tools/source-symbol     - Show source code for symbol\n  clj-mcp.repl-tools/find-symbols      - Find symbols matching pattern\n  clj-mcp.repl-tools/complete          - Find completions for prefix\n  clj-mcp.repl-tools/help              - Show this help message\n\nExamples:\n  (clj-mcp.repl-tools/list-ns)                     ; List all namespaces\n  (clj-mcp.repl-tools/list-vars 'clojure.string)   ; List functions in clojure.string\n  (clj-mcp.repl-tools/doc-symbol 'map)             ; Show documentation for map\n  (clj-mcp.repl-tools/source-symbol 'map)          ; Show source code for map\n  (clj-mcp.repl-tools/find-symbols \"seq\")          ; Find symbols containing \"seq\"\n  (clj-mcp.repl-tools/complete \"clojure.string/j\") ; Find completions for prefix",
      "inputSchema"
      {"type" "object",
       "properties"
       {"code"
        {"type" "string",
         "description" "The Clojure code to evaluate."}},
       "required" ["code"]}}
     {"name" "bash",
      "description"
      "Execute bash shell commands on the host system.\n\nExamples:\n1. List files: bash(command: \"ls -la\")\n2. Find text in files: bash(command: \"grep -r 'pattern' /path/to/search\")\n3. With working directory: bash(command: \"ls -la\", working_directory: \"/tmp\")\n4. With timeout: bash(command: \"sleep 10\", timeout_ms: 5000)\n5. Git commands \n\nFor long running processes like running tests increase the timeout_ms so that the process can complete.\n\nNote: Non-zero exit codes are NOT treated as tool errors - check exit_code\nin the response to determine command success.",
      "inputSchema"
      {"type" "object",
       "properties"
       {"command"
        {"type" "string",
         "description" "The shell command to execute"},
        "working_directory"
        {"type" "string",
         "description" "Directory to run the command in (optional)"},
        "timeout_ms"
        {"type" "integer",
         "description"
         "Maximum execution time in milliseconds (optional, default: 30000)"}},
       "required" ["command"]}}
     {"name" "clojure_edit",
      "description"
      "Edits a top-level form (`defn`, `def`, `defmethod`, `ns`, `deftest`) in a Clojure file using the specified operation.\n   \nPREFER this unified tool over generic file editing tools for Clojure files (`.clj` `.cljs` `.cljc` `.bb`)\n\nThis tool MAKES it EASIER to match a definition that exists in the file AS you only have to match the type of definition `form_type` and the complete identifier `form_identifier` of the definition. This prevents the repeated mismatch errors that occur when trying match an entire string of text for replacement.\nThis tool validates the structure of the Clojure code that is being inserted into the file and will provide linting feedback for things such as parenthetical errors.\nThis tool reduces the number of tokens that need to be generated.\n \nWARNING: you will receive errors if the syntax is wrong, the most common error is an extra or missing parenthesis in the `content`, so be careful with parenthesis.\n\n   Operations:\n   - \"replace\": Replaces the form with new content\n   - \"insert_before\": Inserts content before the form\n   - \"insert_after\": Inserts content after the form\n   \n   The form is identified by its type (defn, def, deftest, s/def, ns, defmethod etc.) and complete identifier.\n   \n   Example: Replace a function definition:\n   - file_path: \"/path/to/file.clj\"\n   - form_identifier: \"example-fn\"\n   - form_type: \"defn\"\n   - operation: \"replace\"\n   - content: \"(defn example-fn [x] (* x 2))\"\n   \n   Example: Insert a helper function before a function:\n   - file_path: \"/path/to/file.clj\"\n   - form_identifier: \"example-fn\"\n   - form_type: \"defn\"\n   - operation: \"insert_before\"\n   - content: \"(defn helper-fn [x] (* x 2))\"\n   \n   Example: Insert a test after a function:\n   - file_path: \"/path/to/file.clj\"\n   - form_identifier: \"example-fn\"\n   - form_type: \"defn\"\n   - operation: \"insert_after\"\n   - content: \"(deftest example-fn-test\n  (is (= 4 (example-fn 2))))\"\n   \nNote: For `defmethod` forms, be sure to include the dispatch value (`area :rectangle` or `qualified/area :rectangle`) in the `form_identifier`. Many `defmethod` definitions have qualified names (they include a namespace alias in their identifier like `shape/area`), so it's crucial to use the complete identifier that appears in the file.\n\n   Example: Replace a specific `defmethod` implementation:\n   - file_path: \"/path/to/file.clj\"\n   - form_identifier: \"shape/area :square\"\n   - form_type: \"defmethod\"\n   - operation: \"replace\"\n   - content: \"(defmethod shape/area :square [{:keys [w h]}] (* w h))\"\n\n   Example: Work with vector dispatch values:\n   - file_path: \"/path/to/file.clj\"\n   - form_identifier: \"convert-length [:feet :inches]\"\n   - form_type: \"defmethod\"\n   - operation: \"insert_before\"\n   - content: \"(def inches-per-foot 12)\"\n\n   The tool will find the form, perform the requested operation, and format the result.\n   It returns a diff showing the changes made to the file.",
      "inputSchema"
      {"type" "object",
       "properties"
       {"file_path"
        {"type" "string",
         "description" "Path to the file containing the form to edit"},
        "form_identifier"
        {"type" "string",
         "description"
         "Complete identifier of the form (e.g., function name, \"shape/area\", or \"convert-length [:feet :inches]\")"},
        "form_type"
        {"type" "string",
         "description"
         "Type of the form (e.g., \"defn\", \"def\", \"ns\")"},
        "operation"
        {"type" "string",
         "enum" ["replace" "insert_before" "insert_after"],
         "description" "The editing operation to perform"},
        "content"
        {"type" "string",
         "description" "New content to use for the operation"}},
       "required"
       ["file_path"
        "form_identifier"
        "form_type"
        "operation"
        "content"]}}
     {"name" "clojure_edit_replace_sexp",
      "description"
      "Edits a file by finding and replacing specific s-expressions.\n\nUse this tool for targeted edits of sub-expressions within forms. For complete top-level form replacements, prefer specialized tools like `clojure_edit_replace_definition` and friends.\n\nKEY BENEFITS:\n- Syntax-aware matching that understands Clojure code structure\n- Ignores whitespace differences by default, focusing on actual code meaning\n- Matches expressions regardless of formatting, indentation, or spacing\n- Prevents errors from mismatched text or irrelevant formatting differences\n- Can find and replace all occurrences with replace_all: true\n\nCONSTRAINTS:\n- match_form must contain only a SINGLE s-expression (error otherwise)\n- Requires syntactically valid Clojure expressions in both match_form and new_form\n\nWARNING: You will receive errors if the syntax is wrong. The most common error is an extra or missing parenthesis in either match_form or new_form, so count your parentheses carefully!\n\nCOMMON APPLICATIONS:\n- Renaming symbols throughout the file: {\"match_form\": \"old-name\", \"new_form\": \"new-name\", \"replace_all\": true}\n- Editing special forms like if/cond/let/when within functions:\n  * Changing if branches: {\"match_form\": \"(if condition expr1 expr2)\", \"new_form\": \"(if condition (do expr1) expr2)\"}\n  * Enhancing let bindings: {\"match_form\": \"(let [x 10] (+ x 2))\", \"new_form\": \"(let [x 10 y 20] (+ x y))\"}\n  * Converting cond to case: {\"match_form\": \"(cond (= x :a) \\\"A\\\" (= x :b) \\\"B\\\")\", \"new_form\": \"(case x :a \\\"A\\\" :b \\\"B\\\")\"}\n- Modifying nested function calls: {\"match_form\": \"(str (+ a b))\", \"new_form\": \"(str (+ a b c))\"}\n- Changing anonymous function syntax: {\"match_form\": \"#(update % :count inc)\", \"new_form\": \"(fn [x] (update x :count inc))\"}\n\nOther Examples:\n- Replace a calculation: {\"match_form\": \"(+ x 2)\", \"new_form\": \"(+ x 10)\"}\n  * Will match regardless of spaces: (+ x 2), (+   x   2), etc.\n- Delete an expression: {\"match_form\": \"(println debug-info)\", \"new_form\": \"\"}\n- Edit anonymous function: {\"match_form\": \"#(inc %)\", \"new_form\": \"#(+ % 2)\"}\n- Replace multiple occurrences: {\"match_form\": \"(inc x)\", \"new_form\": \"(+ x 1)\", \"replace_all\": true}\n\nReturns a diff showing the changes made to the file.",
      "inputSchema"
      {"type" "object",
       "properties"
       {"file_path"
        {"type" "string", "description" "Path to the file to edit"},
        "match_form"
        {"type" "string",
         "description"
         "The s-expression to find and replace (include # for anonymous functions)"},
        "new_form"
        {"type" "string",
         "description" "The s-expression to replace with"},
        "replace_all"
        {"type" "boolean",
         "description"
         "Whether to replace all occurrences (default: false)"}},
       "required" ["file_path" "match_form" "new_form"]}}
     {"name" "file_edit",
      "description"
      "Edit a file by replacing a specific text string with a new one. For safety, this tool requires that the string to replace appears exactly once in the file. \n\nFIRST: the clojure_edit tool is prefered because they use precise structural rewrite-clj editing. They also incorporate linting and ensure balance parenthesis.\n\nWHEN the clojure_edit tool won't work or you have a small easy edit \n\nPREFER the file_write tool for replacing more than half a file, this saves on tokens\n\nFor Clojure files (.clj, .cljs, .cljc, .edn):\n- Content will be linted for syntax errors before writing\n- Content will be formatted according to Clojure standards\n- Writing will fail if linting detects syntax errors\n\nTo make a file edit, provide the file_path, old_string (the text to replace), and new_string (the replacement text). The old_string must uniquely identify the specific instance you want to change, so include several lines of context before and after the change point. To create a new file, use file_write instead.",
      "inputSchema"
      {"type" "object",
       "properties"
       {"file_path"
        {"type" "string",
         "description"
         "The absolute path to the file to modify (must be absolute, not relative)"},
        "old_string"
        {"type" "string",
         "description"
         "The text to replace (must match the file contents exactly, including all whitespace and indentation)."},
        "new_string"
        {"type" "string",
         "description" "The edited text to replace the old_string"}},
       "required" ["file_path" "old_string" "new_string"]}}
     {"name" "file_write",
      "description"
      "Write a file to the local filesystem. Overwrites the existing file if there is one.\n\nFor Clojure files (.clj, .cljs, .cljc, .edn):\n- Content will be linted for syntax errors before writing\n- Content will be formatted according to Clojure standards\n- Writing will fail if linting detects syntax errors\n\nFor other file types:\n- Content is written directly without linting or formatting\n\nIMPORTANT SAFETY FEATURE:\n- If the file has been modified since it was last read, writing will fail\n- You must use read_file to get the current content before editing a file\n- This prevents accidental overwrites of external changes\n\nReturns information about whether the file was created or updated, along with a diff \nshowing the changes made.\n\nBefore using this tool:\n1. Use the read_file tool to understand the file's contents and context\n2. Directory Verification (only applicable when creating new files):\n   - Use the list_directory tool to verify the parent directory exists and is the correct location\n3. For Clojure files, ensure code is syntactically correct\n\n# Example:\n# file_write(\n#   file_path: \"/absolute/path/to/file.clj\",\n#   content: \"(ns my.namespace)\\n\\n(defn my-function [x]\\n  (* x 2))\"\n# )",
      "inputSchema"
      {"type" "object",
       "properties"
       {"file_path"
        {"type" "string",
         "description"
         "The absolute path to the file to write (must be absolute, not relative)"},
        "content"
        {"type" "string",
         "description" "The content to write to the file"}},
       "required" ["file_path" "content"]}}
     {"name" "clojure_inspect_project",
      "description"
      "Analyzes and provides detailed information about a Clojure project's structure, \nincluding dependencies, source files, namespaces, and environment details.\n\nThis tool helps you understand project organization without having to manually \nexplore multiple configuration files. It works with both deps.edn and Leiningen projects.\n\nThe tool provides information about:\n- Project environment (working directory, Clojure version, Java version)\n- Source and test paths\n- Dependencies and their versions\n- Aliases and their configurations\n- Available namespaces\n- Source file structure\n\nUse this tool to quickly get oriented in an unfamiliar Clojure codebase or to \nget a high-level overview of your current project.\n\n# Example:\nclojure_inspect_project()",
      "inputSchema"
      {"type" "object",
       "properties"
       {"explanation"
        {"type" "string",
         "description" "Short explanation why you chose this tool"}},
       "required" ["explanation"]}}
     {"name" "dispatch_agent",
      "description"
      "Launch a new agent that has access to read-only tools. When you are searching for a keyword or file and are not confident that you will find the right match on the first try, use the Agent tool to perform the search for you. For example:\n\n- If you are searching for a keyword like \"config\" or \"logger\", the Agent tool is appropriate\n- If you want to read a specific file path, use the read_file or glob_files tool instead of the Agent tool, to find the match more quickly\n- If you are searching for a specific class definition like \"class Foo\", use the glob_files tool instead, to find the match more quickly\n\nUsage notes:\n1. Launch multiple agents concurrently whenever possible, to maximize performance; to do that, use a single message with multiple `agent` tool uses\n2. When the agent is done, it will return a single message back to you. The result returned by the agent is not visible to the user. To show the user the result, you should send a text message back to the user with a concise summary of the result.\n3. Each agent invocation is stateless. You will not be able to send additional messages to the agent, nor will the agent be able to communicate with you outside of its final report. Therefore, your prompt should contain a highly detailed task description for the agent to perform autonomously and you should specify exactly what information the agent should return back to you in its final and only message to you.\n4. The agent's outputs should generally be trusted",
      "inputSchema"
      {"type" "object",
       "properties"
       {"prompt"
        {"type" "string",
         "description" "The prompt to send to the agent"}},
       "required" ["prompt"]}}
     {"name" "architect",
      "description"
      "Your go-to tool for any technical or coding task. Analyzes requirements and breaks them down into clear, actionable implementation steps. Use this whenever you need help planning how to implement a feature, solve a technical problem, or structure your code.",
      "inputSchema"
      {"type" "object",
       "properties"
       {"prompt"
        {"type" "string",
         "description"
         "The technical request or coding task to analyze"},
        "context"
        {"type" "string",
         "description"
         "Optional context from previous conversation or system state"}},
       "required" ["prompt"]}}
     {"name" "code_critique",
      "description"
      "Starts an interactive code review conversation that provides constructive feedback on your Clojure code.\n  \n  HOW TO USE THIS TOOL:\n  1. Submit your Clojure code for initial critique\n  2. Review the suggestions and implement improvements\n  3. Test your revised code in the REPL\n  4. Submit the updated code for additional feedback\n  5. Continue this cycle until the critique is satisfied\n  \n  This tool initiates a feedback loop where you:\n  - Receive detailed analysis of your code\n  - Implement suggested improvements\n  - Test and verify changes\n  - Get follow-up critique on your revisions\n  \n  The critique examines:\n  - Adherence to Clojure style conventions\n  - Functional programming best practices\n  - Performance optimizations\n  - Readability and maintainability improvements\n  - Idiomatic Clojure patterns\n  \n  Example conversation flow:\n  - You: Submit initial function implementation\n  - Tool: Provides feedback on style and structure\n  - You: Revise code based on suggestions and test in REPL\n  - Tool: Reviews updates and suggests further refinements\n  - Repeat until code quality goals are achieved\n  \n  Perfect for iterative learning and continuous code improvement.",
      "inputSchema"
      {"type" "object",
       "properties"
       {"code"
        {"type" "string",
         "description" "The Clojure code to analyze and critique"},
        "context"
        {"type" "string",
         "description"
         "Optional context from previous conversation or system state"}},
       "required" ["code"]}}]}})

;; list prompts response - x1
(def list-prompts-response
    {"jsonrpc" "2.0",
     "id" 3,
     "result"
     {"prompts"
      [{"name" "ACT/scratch_pad_load",
        "description"
        "Loads a file into the scratch pad state. Returns status messages and a shallow inspect of the loaded data.",
        "arguments"
        [{"name" "file_path",
          "description" "Optional file path: default scratch_pad.edn",
          "required" false}]}
       {"name" "clojure_repl_system_prompt",
        "description"
        "Provides instructions and guidelines for Clojure development, including style and best practices.",
        "arguments" []}
       {"name" "create-update-project-summary",
        "description"
        "Generates a prompt instructing the LLM to create a summary of a project.",
        "arguments" []}
       {"name" "chat-session-summarize",
        "description"
        "Instructs the assistant to create a summary of the current chat session and store it in the scratch pad. `chat_session_key` is optional and will default to `chat_session_summary`",
        "arguments"
        [{"name" "chat_session_key",
          "description" "[Optional] key to store the session summary in",
          "required" false}]}
       {"name" "ACT/scratch_pad_save_as",
        "description"
        "Saves the current scratch pad state to a specified file.",
        "arguments"
        [{"name" "file_path",
          "description" "File path: relative to .clojure-mcp/ directory",
          "required" true}]}
       {"name" "chat-session-resume",
        "description"
        "Instructs the assistant to resume a previous chat session by loading context from the scratch pad. `chat_session_key` is optional and will default to `chat_session_summary`",
        "arguments"
        [{"name" "chat_session_key",
          "description" "[Optional] key where session summary is stored",
          "required" false}]}
       {"name" "ACT/add-dir",
        "description"
        "Adds a directory to the allowed-directories list, giving the LLM access to it",
        "arguments"
        [{"name" "directory",
          "description"
          "Directory path to add (can be relative or absolute)",
          "required" true}]}
       {"name" "plan-and-execute",
        "description"
        "Use the scratch pad tool to plan and execute an change",
        "arguments" []}]}})

;; list resources response - x1
(def list-resources-response
  {"jsonrpc" "2.0",
   "id" 4,
   "result"
   {"resources"
    [{"uri" "custom://readme",
      "name" "README.md",
      "description"
      "A README document for the current Clojure project hosting the REPL",
      "mimeType" "text/markdown"}
     {"uri" "custom://project-info",
      "name" "Clojure Project Info",
      "description"
      "Information about the current Clojure project structure, attached REPL environment and dependencies",
      "mimeType" "text/markdown"}
     {"uri" "custom://llm-code-style",
      "name" "LLM_CODE_STYLE.md",
      "description"
      "Guidelines for writing Clojure code for the current project hosting the REPL",
      "mimeType" "text/markdown"}
     {"uri" "custom://project-summary",
      "name" "PROJECT_SUMMARY.md",
      "description"
      "A Clojure project summary document for the project hosting the REPL, this is intended to provide the LLM with important context to start.",
      "mimeType" "text/markdown"}
     {"uri" "custom://claude",
      "name" "CLAUDE.md",
      "description"
      "The Claude instructions document for the current project hosting the REPL",
      "mimeType" "text/markdown"}]}})


(def read-resources-request
  {"jsonrpc" "2.0" "id" 12345 "method" "resources/read" "params" {"uri" "custom://project-info"}})

(def read-resources-response
  {"jsonrpc" "2.0",
   "id" 12345,
   "result"
   {"contents"
    [{"uri" "custom://project-info",
      "mimeType" "text/markdown",
      "text" "..."}]}}) ;; text broke cider...

;;------------------------------------------------------------------------------

(defn list-changed? [m]
  (when-let [[_ capability] (re-matches #"notifications/([^/]+)/list_changed" m)]
    capability))

(defn merge-resources [c rs]
  (let [id->index (reduce (fn [acc [n {id "uri"}]] (assoc acc id n)) {} (map vector (range) c))]
    (reduce
     (fn [acc {id "uri" t "text"}]
       (assoc-in acc [(id->index id) "text"] t))
     c
     rs)))

(defn handle [context {m "method" {contents "contents" cs "capabilities" :as r} "result" :as e}]
  (cond
    ;; initialise cache
    cs [(update context "state" (partial reduce-kv (fn [acc k {lc "listChanged" s "subscribe"}] (if (or lc s) (assoc acc k nil) acc))) cs) nil]
    ;; refresh resource contents
    contents [(update-in context ["state" "resources"] merge-resources contents) nil]
    ;; invalidate cache item
    m  [(assoc-in context ["state" (list-changed? m)] nil) nil]
    ;; refresh cache item
    r  [(update context "state" merge r) nil]
    
    :else
    ;; pass through
    [context e]
    
    ;;TODO: subscriptions
    ))

(defn handle-event [old-context old-event]
  (let [[new-context new-event] (handle old-context old-event)]
    (if new-event
      (reduced [new-context new-event])
      new-context)))

;;------------------------------------------------------------------------------

(deftest mcp-test

  (testing "merge resources"
    (is (=
         [{"uri" "custom://readme",
           "name" "README.md",
           "description"
           "A README document for the current Clojure project hosting the REPL",
           "mimeType" "text/markdown"}
          {"uri" "custom://project-info",
           "name" "Clojure Project Info",
           "description"
           "Information about the current Clojure project structure, attached REPL environment and dependencies",
           "mimeType" "text/markdown"
           "text" "..."}]
         (merge-resources
          [{"uri" "custom://readme",
            "name" "README.md",
            "description"
            "A README document for the current Clojure project hosting the REPL",
            "mimeType" "text/markdown"}
           {"uri" "custom://project-info",
            "name" "Clojure Project Info",
            "description"
            "Information about the current Clojure project structure, attached REPL environment and dependencies",
            "mimeType" "text/markdown"}]
          [{"uri" "custom://project-info",
            "mimeType" "text/markdown",
            "text" "..."}]))))

  (testing "initialize response"
    (is (=
         [{"state" {"tools" nil "prompts" nil "resources" nil}} nil]
         (handle nil initialize-response))))

  (testing "tools list changed notification"
    (is (= [{"state" {"tools" nil "prompts" [] "resources" []}} nil] (handle {"state" {"tools" [] "prompts" [] "resources" []}} tools-list-changed-notification))))

  (testing "prompts list changed notification"
    (is (= [{"state" {"tools" [] "prompts" nil "resources" []}} nil] (handle {"state" {"tools" [] "prompts" [] "resources" []}} prompts-list-changed-notification))))

  (testing "resources list changed notification"
    (is (= [{"state" {"tools" [] "prompts" [] "resources" nil}} nil] (handle {"state" {"tools" [] "prompts" [] "resources" []}} resources-list-changed-notification))))

  (testing "list tools response"
    (is (=
         [{"state" (get list-tools-response "result")} nil]
         (handle {} list-tools-response))))

  (testing "list prompts response"
    (is (=
         [{"state" (get list-prompts-response "result")} nil]
         (handle {} list-prompts-response))))

  (testing "list resources response"
    (is (=
         [{"state" (get list-resources-response "result")} nil]
         (handle {} list-resources-response))))

  (testing "read resources response"
    (is (=
         [{"state" {"resources" [{"uri" "custom://project-info" "text" "..."}]}} nil]
         (handle {"state" {"resources" [{"uri" "custom://project-info"}]}} read-resources-response))))

  ;; we need to go back and flesh out the resources

  ;; TODO:
  ;; what does subscribe mean ?
  ;; - send resources/subscribe for each uri
  ;; - on notifications/resources/updated, invalidate cache for that uri
  ;; - call resources/read to refresh cache

  (testing "handle events"
    (is (=
         {"state" {"tools" nil "prompts" nil "resources" nil}}
         (reduce
          handle-event
          {}
          [initialize-response])))

    (is (=
         {"state" {"tools" (get-in list-tools-response ["result" "tools"])
                   "prompts" nil
                   "resources" nil}}
         (reduce
          handle-event
          {}
          [initialize-response list-tools-response])))

    (is (=
         {"state" {"tools"   (get-in list-tools-response   ["result" "tools"])
                   "prompts" (get-in list-prompts-response ["result" "prompts"])
                   "resources" nil}}
         (reduce
          handle-event
          {}
          [initialize-response list-tools-response list-prompts-response])))

    (is (=
         {"state" {"tools"     (get-in list-tools-response     ["result" "tools"])
                   "prompts"   (get-in list-prompts-response   ["result" "prompts"])
                   "resources" (get-in list-resources-response ["result" "resources"])}}
         (reduce
          handle-event
          {}
          [initialize-response list-tools-response list-prompts-response list-resources-response])))

    (is (=
         {"state" {"tools" nil
                   "prompts"   (get-in list-prompts-response   ["result" "prompts"])
                   "resources" (get-in list-resources-response ["result" "resources"])}}
         (reduce
          handle-event
          {}
          [initialize-response list-tools-response list-prompts-response list-resources-response tools-list-changed-notification])))

    (is (=
         {"state" {"tools" nil
                   "prompts" nil
                   "resources" (get-in list-resources-response ["result" "resources"])}}
         (reduce
          handle-event
          {}
          [initialize-response list-tools-response list-prompts-response list-resources-response tools-list-changed-notification prompts-list-changed-notification])))

    (is (=
         {"state" {"tools" nil
                   "prompts" nil
                   "resources" nil}}
         (reduce
          handle-event
          {}
          [initialize-response list-tools-response list-prompts-response list-resources-response tools-list-changed-notification prompts-list-changed-notification resources-list-changed-notification]))))

  ;; subscriptions:
  
  ;; I tried the following subscription:
  
  {"jsonrpc" "2.0"
   "id" 10
   "method" "resources/subscribe"
   "params"
   {"uri" "custom://readme"}}
  
  ;; and got the following error:
  
  {"jsonrpc" "2.0",
   "id" 10,
   "error"
   {"code" -32601,
    "message" "Method not found: resources/subscribe"}}
  
  ;; so it looks like clojure-mcp does not support subscriptions...
  
  )

;;------------------------------------------------------------------------------
;; actually start an mcp service and walk through its capabiliies:

(defn trace [m f]
  (fn [v]
    (let [r (f v)]
      (prn m "(" f v ")" "->" r)
      r)))

;; I'd like to get this working but it keeps throwing exceptions - I don't think 'stop' is cleaning up properly

;; (deftest ^:integration mcp-walk-through-test
;;   (let [n 100
;;         config {"command" "bash", "args" ["-c", "cd /home/jules/src/claij && ./bin/mcp-clojure-tools.sh"], "transport" "stdio"}
;;         ic (chan n (map write-str))
;;         oc (chan n (comp (map read-str) (remove list-changed?))) ;; TODO - verify that the reducer is having desired effect
;;         stop (start-mcp-bridge config ic oc)]

;;     (try

;;       (>!! ic initialise-request)
;;       (>!! ic initialised-notification)
;;       (>!! ic list-tools-request)
;;       (>!! ic list-prompts-request)
;;       (>!! ic list-resources-request)

;;       (defn make-read-resource-request [id uri]
;;         {"jsonrpc" "2.0" "id" id "method" "resources/read" "params" {"uri" uri}})

;;       (def resource-read-requests
;;         (map
;;          (fn [n {uri "uri" :as resource}] (make-read-resource-request (+ n 5) uri))
;;          (range)
;;          (get-in list-resources-response ["result" "resources"])))

;;       (doseq [r resource-read-requests] (>!! ic r))

;;       (catch Throwable t (log/error "should not have happened:" t))

;;       (finally

;;         (stop)
;;         (is true)))))

;; list of available resources:
;; ("custom://readme"
;;  "custom://project-info"
;;  "custom://llm-code-style"
;;  "custom://project-summary"
;;  "custom://claude")


(comment
  (def n 100)
  (def config {"command" "bash", "args" ["-c", "cd /home/jules/src/claij && ./bin/mcp-clojure-tools.sh"], "transport" "stdio"})
  (def ic (chan n (map write-str)))
  (def oc (chan n (map read-str)))
  (def stop (start-mcp-bridge config ic oc))
  (>!! ic initialise-request)
  (>!! ic initialised-notification)
  (>!! ic read-resources-request)
  
  (<!! oc) ;; many times
  )

;;------------------------------------------------------------------------------
;; TODO:
;; create an mcp schema with all cache stuff stripped out
;; integrate with mcp-fsm
;; tidy up
