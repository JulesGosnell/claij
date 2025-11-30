;; Deployment Research Task
;; 
;; THIS IS NOT CODE TO REVIEW - THIS IS A RESEARCH TASK
;; Please answer the questions below and provide recommendations.

(ns claij.tasks.deploy-research
  "Research task: How to deploy claij as a live service")

;; =============================================================================
;; TASK: Research and recommend a deployment strategy for claij
;; =============================================================================
;;
;; We need your help researching and recommending how to deploy a Clojure
;; web service to the cloud. Please provide concrete recommendations.
;;
;; CONTEXT:
;; - claij is an open-source (Apache 2.0) Clojure project
;; - We will have a release artifact: claij.jar (uberjar)
;; - The JAR runs a web server (Ring/Jetty) serving dynamic content
;; - NOT static HTML - this is a running JVM service
;;
;; CONSTRAINTS:
;; - Free or very cheap (we're bootstrapping an open source project)
;; - Clojure ecosystem preferred (tools, libraries, patterns)
;; - Simple as possible - well-trodden path
;; - Must support automated deployment from GitHub Actions
;; - API-driven (so FSMs can eventually do deployments)
;;
;; =============================================================================
;; QUESTIONS - PLEASE ANSWER THESE
;; =============================================================================
;;
;; 1. HOSTING RECOMMENDATION
;;    Which cloud platform should we use for a JVM service?
;;    Options we've heard of: Fly.io, Railway, Render, Heroku
;;    What do Clojure open-source projects typically use?
;;    What are the trade-offs?
;;
;; 2. ARTIFACT FORMAT  
;;    Should we deploy an uberjar directly or a Docker container?
;;    Where should we publish artifacts? (ghcr.io, Docker Hub, etc.)
;;
;; 3. DEPLOYMENT PIPELINE
;;    What does the GitHub Actions workflow look like?
;;    Build → Publish → Deploy → Verify
;;    What secrets/config are needed?
;;
;; 4. DOMAIN & SSL
;;    How do we point claij.org at our service?
;;    Who handles SSL certificates?
;;
;; 5. ESTIMATED COSTS
;;    What will this cost monthly for a low-traffic open source project?
;;
;; =============================================================================
;; DELIVERABLES REQUESTED
;; =============================================================================
;;
;; Please provide:
;; 1. A clear recommendation for hosting provider with rationale
;; 2. Step-by-step deployment checklist
;; 3. Draft GitHub Actions workflow (YAML)
;; 4. Estimated monthly cost
;;
;; =============================================================================

(def research-concerns
  "Focus areas for this research task"
  ["Simplicity: What's the simplest path that works?"
   "Cost: Must be free or nearly free for open source"
   "Clojure ecosystem: Prefer tools/patterns used by Clojure community"
   "Automation: Must be fully automatable via GitHub Actions"
   "Reliability: Should be production-ready, not just a demo"
   "Documentation: Plan must be clear enough to execute"])
