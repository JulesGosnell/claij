# Deploying claij to Fly.io

## Overview

This document describes the manual deployment process for claij. 
Once we've done this manually, we'll encode it into an FSM.

## Prerequisites

1. Install Fly.io CLI: `curl -L https://fly.io/install.sh | sh`
2. Add to PATH (add to ~/.bash_profile for permanence):
   ```bash
   export FLYCTL_INSTALL="$HOME/.fly"
   export PATH="$FLYCTL_INSTALL/bin:$PATH"
   ```
3. Sign up / log in: `fly auth login`
   - Browser opens to fly.io
   - Click "Authorize Fly.io" (via GitHub or email)
   - Click button to confirm your email address
   - Terminal shows "successfully logged in as ..."
4. Have OPENROUTER_API_KEY ready

## One-Time Setup (First Deployment)

### Step 1: Create the Fly.io app

```bash
cd /home/jules/src/claij
fly apps create claij
```

If `claij` is taken, try `claij-app` or similar.

### Step 2: Set secrets

```bash
fly secrets set OPENROUTER_API_KEY=your-key-here
```

### Step 3: Deploy

```bash
fly deploy
```

This will:
- Build the Docker image (using our Dockerfile)
- Push to Fly.io's registry
- Deploy to their infrastructure
- Run health checks

### Step 4: Verify

```bash
fly status
fly logs
curl https://claij.fly.dev/health
```

## Subsequent Deployments

Just run:

```bash
fly deploy
```

Or, once we have CI/CD set up:

```bash
git push  # triggers GitHub Action which runs fly deploy
```

## Custom Domain Setup

### Step 1: Buy domain (Cloudflare)

1. Go to https://dash.cloudflare.com
2. Register `claij.org` (~$10/year)

### Step 2: Add domain to Fly.io

```bash
fly certs add claij.org
fly certs add www.claij.org
```

This will give you CNAME targets.

### Step 3: Configure DNS (in Cloudflare)

Add CNAME records:
- `claij.org` → `claij.fly.dev`
- `www.claij.org` → `claij.fly.dev`

(Fly.io handles SSL automatically)

### Step 4: Verify

```bash
fly certs show claij.org
curl https://claij.org/health
```

## Troubleshooting

### View logs
```bash
fly logs
```

### SSH into running container
```bash
fly ssh console
```

### Check machine status
```bash
fly status
fly machines list
```

### Restart
```bash
fly machines restart
```

## Costs

- **Fly.io free tier**: 3 shared-cpu VMs, 256MB RAM each
- Our config uses shared-cpu-1x with 512MB
- Should be free or ~$2-5/month for low traffic
- Only charges when machine is running (auto-stop enabled)

## Files Created

- `Dockerfile` - Multi-stage build for uberjar
- `fly.toml` - Fly.io configuration  
- `build.clj` - Clojure tools.build script

## Next Steps

1. [x] Run manual deployment - **DONE 2024-11-29** 
2. [x] Verify health endpoint works - **https://claij.fly.dev/health returns "ok"**
3. [ ] Set up custom domain (claij.org)
4. [ ] Create GitHub Action for auto-deploy
5. [ ] Encode redeploy into FSM
6. [ ] Add webhook listener for self-deployment

## TODO / Technical Debt

- [ ] **Reduce dependencies in uberjar** - Currently pulling in too many deps (104MB jar). 
  Need to create a slim `:server` alias with only runtime deps, excluding:
  - Dev/test dependencies
  - MCP dependencies  
  - Python interop (libpython-clj)
  - TTS/STT services
  - Database deps (if not used by server)
- [ ] **DRY up OpenRouter code** - `claij.server` has its own copy of OpenRouter logic.
  Should use `claij.llm.open-router` instead. Caused compile-time env var issue.
