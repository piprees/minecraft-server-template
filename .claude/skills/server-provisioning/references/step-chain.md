---
title: Step Chain
description: Every script in the provisioning chain — preconditions, what it does, what it restarts or destroys, and how to re-run it alone
tags:
  [
    setup,
    preflight,
    provision,
    harden,
    prepare-droplet,
    cloudflare-setup,
    initial-setup,
    github-env-sync,
    teardown,
    idempotent,
  ]
---

# Step Chain

Detailed breakdown of every script `./ops setup` walks through in sequence, plus the standalone-only scripts (`server-power.sh`, `ddns-update.sh`, `destroy-server.sh`) that sit alongside the chain. Read a script's own header (`head -30 scripts/<name>.sh`) for anything not covered here — headers are kept current and are the authoritative source.

## setup.sh — the wizard

`./ops setup` (consumer) / `./scripts/setup.sh` (platform repo). 18-phase interactive wizard; every phase writes straight to `.env` after each prompt (`update_dotenv`), so a crash mid-wizard loses nothing — re-run and it resumes with previous answers as defaults.

- **Re-run behaviour:** if `.env` already has every required var set (non-placeholder), it offers to skip straight to testing/deployment (`SKIP_CREDENTIALS` fast-forward).
- **Delegates to, in order:** `preflight-check.sh` → (optional `dev-up.sh` local test) → (optional seed rolling) → `provision.sh` → `harden.sh --remote` → `prepare-droplet.sh` → SSH `initial-setup.sh` on the server → `cloudflare-setup.sh` → `github-env-sync.sh`.
- **1Password:** if `op` is installed and signed in, every secret is also stored in a per-brand vault item (`Minecraft Server - <BRAND_SLUG>`) and read back to verify the write succeeded. A verify failure is counted and reported at the end, never aborts the wizard.
- Accepts `--target local|hetzner|digitalocean` to skip the target-selection prompt.

## preflight-check.sh — validation gate

`./ops preflight` / `--target local|hetzner|digitalocean` (auto-detects from `CLOUD_PROVIDER` in `.env` if omitted).

- Checks (never aborts on a single failure — tallies PASS/FAIL/WARN and reports at the end): `.env` exists and is git-ignored (and not already tracked — a tracked `.env` is a `fail`, not a warning), Minecraft settings, admin/player access configured, cloud provider token + CLI present, **live Cloudflare token verification** plus zone-match check, R2 keypair format (regex check), Discord vars present, deploy key exists on disk, **live GitHub environment wiring check** (`gh variable get DROPLET_HOST --env production`), Docker + Compose v2, CLI tools (`gh`, `python3`, `jq`, `curl`), `/etc/hosts` entries for local target.
- Exit code 1 if any check hard-fails; exit 0 (with warnings printed) otherwise.
- Safe to run any time, any number of times — read-only except for sourcing `.env`.

## provision.sh — provider router

`./ops provision` / `--provider hetzner|digitalocean|local`. Reads `CLOUD_PROVIDER` from `.env` if no flag given; prompts interactively if neither is set.

- Routes to `provision-hetzner.sh` or `provision-droplet.sh` via `exec` (replaces the process). `local` just prints manual next steps — there's no cloud resource to create.
- Calls `require_provider_cli` first — dies with an install hint if `hcloud`/`doctl` is missing.

### provision-hetzner.sh

- **Idempotent by name:** checks `hcloud server ip mc-<BRAND_SLUG>` first; if it already exists, reports the IP and does nothing else.
- On create: uploads any local SSH pubkeys found (`id_ed25519.pub`, `id_rsa.pub`, `mc_deploy_key.pub`) if Hetzner has none registered, creates a firewall (`mc-<slug>-fw`, allowing SSH + game port + voice port from anywhere), then creates the server (`ubuntu-24.04`, default type `cx33`, location `fsn1`).
- **ARM/x86 fallback:** if the chosen type/location combo is sold out, an interactive menu offers retry / pick another type (live Hetzner API pricing) / pick another location / abort. This is common for ARM (`cax*`) types.
- Clears any stale SSH host key for the IP (`ssh-keygen -R`), waits up to 60s for SSH to answer.
- Writes `DROPLET_HOST` to `.env` on success. Does **not** harden or configure anything else — single responsibility, by design comment in the header.

### provision-droplet.sh

- **Idempotent by name:** checks `doctl compute droplet list` for `mc-<BRAND_SLUG>` first; if found, prints the IP and exits — does not offer to reconfigure. To rebuild, destroy the droplet manually via the DO dashboard first.
- Requires at least one SSH key already registered with DigitalOcean (`doctl compute ssh-key create`) — dies with instructions if none found.
- Creates with `--enable-monitoring --wait`; default size `s-4vcpu-8gb`, region `lon1`, image `ubuntu-24-04-x64`.
- Does **not** write `DROPLET_HOST` to `.env` automatically (unlike the Hetzner path) — the script's own output tells you to add it by hand. Confirm this manually after provisioning on DigitalOcean.

## harden.sh — one-time lockdown

`./ops harden` (auto re-execs itself against `DROPLET_HOST` as `--remote root@IP` when run from a workstation) / `--remote root@IP` / `--non-interactive` (used by the wizard and CI).

Runs **once** at provision time. In order: (1) creates the `deploy` user with passwordless sudo and copies root's + the GitHub deploy key's authorized_keys; (2) writes SSH hardening config (root login off, password auth off) — **applied only at step 9, the very end**, deliberately deferred so the session running the script doesn't get killed mid-run; (3) UFW default-deny inbound, allow SSH + game port + voice port; (4) installs Docker, hardens the daemon (`iptables: false`, log rotation), adds NAT + rate-limit rules to `ufw before.rules`, **restarts Docker**; (5) fail2ban (SSH, MC connection-spam, MC login-flood, nginx exploit-scan jails; Cloudflare IP ranges + Docker bridge + caller IP whitelisted); (6) unattended-upgrades; (7) 2G swap file at swappiness 10; (8) journald capped at 200MB; (9) installs `restic`+`zip`; (10) reloads SSH — root access ends here.

- **Remote mode uploads itself and runs in the background via `nohup`** so a dropped SSH connection doesn't kill a multi-minute Docker install; polls for `/root/.harden-done` or `/root/.harden-failed` up to 10 minutes.
- Idempotent (checks `id "$NEWUSER"`, re-runnable), but **restarts Docker every time** — never run it near a CI deploy.
- **CRITICAL:** verify a new terminal can SSH in as `deploy` before closing the session that ran this script. Root SSH is gone the moment it finishes.

## prepare-droplet.sh — bridge to first deploy

`./ops prepare` / `DROPLET_HOST=1.2.3.4 ./ops prepare` (override IP). Run after `harden.sh`, before the first deploy.

Six steps, each independently idempotent (checked and skipped if already done): (1) registers the deploy public key with the GitHub repo (`gh repo deploy-key add`); (2) copies the deploy **private** key to the droplet at `~/.ssh/github_deploy_key`; (3) creates `~/server/{overlay,cloudflared,modpack-dist,data,.stack}` — no git clone, bundle model; (4) uploads `stack-pull.sh` and pulls the initial stack bundle; (4b) optionally rsyncs locally-downloaded mod JARs to the server and pre-seeds the Modrinth-sync hash so first boot skips a ~150-download burst; (5) writes a production `.env` (forces `ONLINE_MODE=TRUE`, strips `DEPLOY_KEY_PUB`); (6) **calls `github-env-sync.sh` itself** — so a normal `prepare` run already wires GitHub, you don't need a separate `github-env-sync` call unless secrets changed afterward.

- **Auto-creates a private GitHub repo** if none exists yet (asks first, `gh repo create --private --source . --push`) — this is how a `degit`'d consumer folder with no git history gets one.
- Resolves the droplet IP from `.env`, or `doctl`/`hcloud` list output, in that order.

## github-env-sync.sh — the GitHub wiring

`./ops github-env-sync` / `--check` (read-only report, no writes) / `--allow-missing` (exit 0 even with gaps) / `--env-name staging`.

- Creates the `production` GitHub environment (idempotent PUT) and pushes every secret/variable the workflows read, sourced from `.env`. `DEPLOY_SSH_KEY` is the one exception — read from the private key file on disk, never from `.env`.
- **Required vars (`DROPLET_HOST`, `DEPLOY_USER`) are pushed to both the environment scope and the repo scope** — `deploy.yml`'s job-level `if: vars.DROPLET_HOST != ''` gate runs before the environment attaches and can only see repo-scoped variables. Missing this is the single most common cause of "CI goes green but nothing happens" — except it's actually silently skipped, not green.
- `--check` mode is safe to run any time — it only reads GitHub, needs no `.env` at all if you just want to confirm existing wiring.
- Keep `REQUIRED_SECRETS`/`OPTIONAL_SECRETS`/`*_VARS` arrays in this script in sync with `secrets.*`/`vars.*` usage in `.github/workflows/*.yml` if either changes.

## cloudflare-setup.sh — DNS, tunnel, R2, Worker

`./ops cloudflare` / `--non-interactive` / `--local-host` (home hosting: detects your own public IP instead of requiring `DROPLET_HOST`).

Eight steps, each checked for an existing resource before creating: (1) resolves the server's public IP; (2) DNS A record `mc.DOMAIN` → IP, **grey-cloud, never proxied**; (3) SRV record `_minecraft._tcp.mc.DOMAIN` → `SERVER_PORT`; (4) creates the tunnel via the Cloudflare API (writes credentials to `~/.cloudflared/<id>.json`); (5) copies tunnel credentials into `config/cloudflared/`; (5b) creates the R2 bucket if `R2_BUCKET` is set (location hint `weur` by default); (6) writes `config/cloudflared/config.yml` (ingress: map/pack/status/mods → nav-proxy, except the raw mod-JAR path which goes straight to pack-web); (7) CNAME records for `map`/`pack`/`status`/`mods`, proxied; (8) deploys the maintenance Worker via `wrangler` if `config/cloudflare/wrangler.jsonc` exists and `wrangler` is installed.

- **Rate-limited deliberately:** every Cloudflare API call sleeps 1.5s afterward (`cf_sleep`), with a 3-attempt retry loop on HTTP 429.
- **At the very end, if `DROPLET_HOST` is set:** syncs `config.yml` + tunnel credentials to the server and force-recreates the `cloudflared` container — so a normal run already restarts it for you. A hand-edit to `config.yml` without re-running this script needs a manual restart.
- `CLOUDFLARE_TUNNEL_ID` is written back to `.env` on first creation.

## initial-setup.sh — first boot (runs ON the server)

Invoked over SSH, not via `./ops`: `ssh deploy@IP 'cd ~/server && .stack/current/stack/scripts/initial-setup.sh'` / `--offline` (use cached images + mods from `cache/` instead of pulling).

First-boot-only, idempotent work that `deploy.sh` assumes already exists: generates `RCON_PASSWORD` if blank, creates `data/`+`backups/`+`modpack-dist/` dirs, initialises the restic repository against R2 (no-op if already initialised), pulls every Docker image for the `cloud` profile. Then **delegates to `deploy.sh --non-interactive`** for the actual container startup — this script never starts `mc` itself.

## server-power.sh — VPS power state (not part of the create/deploy chain, but adjacent)

`./ops shutdown|startup|reboot`. Detects the provider from `.env` and uses its API — this is OS-level power control, not container control (`./ops start|stop|restart <svc>` is the Docker-level equivalent, and it explicitly refuses to touch `mc`).

- **Graceful, not a hard cut:** sends an in-game countdown via RCON if players are online (skips it if RCON doesn't respond — autopause, not necessarily down), runs `save-all flush`, then calls the provider's shutdown/reboot API and polls for the new state.
- `startup` waits for SSH to become reachable, then relies on `unless-stopped` restart policies to bring containers back — it doesn't start anything itself.
- Local-only setups (no `HCLOUD_TOKEN`/`DO_API_TOKEN`) are told to use `./ops stop|start` (Docker-level) instead — there's no VPS to power-cycle.

## ddns-update.sh — home-hosting only, no `./ops` command

`bash .stack/current/stack/scripts/ddns-update.sh` / `--install-cron` (every 5 minutes). Compares the current public IP against the Cloudflare A record for `DDNS_HOSTNAME` (default `mc.DOMAIN`) and PATCHes it if changed. Silent when unchanged — cron logs stay clean. Cloud servers (static IP) never need this.

## destroy-server.sh — platform-repo-only, not in the bundle

`./scripts/destroy-server.sh` / `--force` (skip the double confirm — CI/scripting only). **Not listed in `build-stack-bundle.sh`'s MANIFEST array and not in `examples/consumer/ops`'s `ALLOWED_COMMANDS`** — a consumer repo cannot reach this script at all. Hetzner-only (calls `hcloud`); deletes the server and its firewall (`mc-<slug>-fw`) only — does not touch DNS, the tunnel, R2, or local `.env`/config. Exists for platform-dev clean-slate testing cycles. A consumer wanting equivalent behaviour should use `./ops teardown --target hetzner` instead.

## teardown.sh — the real reverse of setup, in every bundle

`./ops teardown` / `--target local|hetzner|digitalocean` (auto-detects from `CLOUD_PROVIDER` if omitted). Target-aware, every destructive step double-confirmed independently: stops+removes Docker containers and volumes, offers to delete the cloud server (interactive picker, both Hetzner and DO), offers to delete the R2 bucket (clears restic snapshots first if credentials are present), prints manual instructions for the tunnel + DNS records (not automated — `cloudflare-setup.sh` created them, `teardown.sh` doesn't reverse-engineer that script), removes provider CLI auth contexts, offers to delete local `data/`+`cache/`, removes managed `/etc/hosts` entries, cleans up `.setup-state/`.

- **Explicitly preserves account-level credentials** (deploy SSH key, API tokens, the 1Password item) — these are shared across server repos by design; deleting them here is what caused credentials to "mysteriously die" between test cycles historically. It ends with a checklist of what to revoke by hand only if abandoning the project entirely.
