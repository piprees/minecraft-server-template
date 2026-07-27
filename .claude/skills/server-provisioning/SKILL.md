---
name: server-provisioning
description: Provisions a Minecraft server through the setup.sh -> preflight-check.sh -> provision.sh -> harden.sh -> prepare-droplet.sh -> cloudflare-setup.sh -> initial-setup.sh chain, and re-runs any single step safely without redoing the whole thing. Explains provider routing (Hetzner/DigitalOcean/local), what each idempotent step assumes and restarts (harden.sh restarts Docker), the GitHub wiring that silently skips deploys when missing, and destroy-server.sh (platform-only, never shipped to consumers) versus teardown.sh (full reverse-of-setup, in every bundle). Covers the three Cloudflare credential systems and the grey-cloud DNS-only game-port A record versus the tunnel's HTTP-only web services. Use when creating a cloud server, re-running hardening or Cloudflare setup after a failure, fixing a broken Cloudflare token, or diagnosing why pushes to main aren't auto-deploying. Resolves "Invalid API Token", "Zone 'DOMAIN' not visible to this token", and deploys silently skipped because vars.DROPLET_HOST is empty.
---

# Server Provisioning

Zero-to-running-server is a chain of idempotent scripts, each with its own preconditions and its own failure mode if you enter mid-chain. This skill covers **obtaining credentials and running the provisioning chain** — creating the server, hardening it, wiring Cloudflare and GitHub, first boot, and teardown.

**Not this skill:** the `.env` model, the four-places-to-update rule, secret rotation → `env-and-secrets`. Ongoing deploys after first boot → `deploy-pipeline-operations`. This skill covers *obtaining* credentials; `env-and-secrets` covers *managing* them once obtained.

## MANDATORY: read before touching Cloudflare

- `references/credentials.md` — the credential table and the three-Cloudflare-systems section. Read this in full before creating any Cloudflare token.
- `references/setup-walkthrough.md` — the human walkthrough: Discord bot creation with intents, the GitHub environment secrets/variables tables, per-OS notes (WSL2 networking). Not duplicated here.
- `references/credential-sourcing.md` (this skill) — obtaining-order checklist, permission matrix, format-validation regexes.
- `references/step-chain.md` (this skill) — every script's preconditions, what it does, what it restarts, how to re-run it alone.

Every script also carries its own header comment — read it before running (`head -30 scripts/<name>.sh`); AGENTS.md calls headers "the authoritative reference" and they're kept current.

## The chain

`./ops <cmd>` is the consumer-facing dispatcher (`examples/consumer/ops`) — it loads `.env` and delegates to the matching bundle script. Platform-repo development uses `./scripts/<name>.sh` directly. Not every script in `scripts/` has an `./ops` command — see the gaps below.

| Step | Command | Assumes | Restarts / destroys |
| --- | --- | --- | --- |
| Wizard | `./ops setup` | nothing (fresh clone) | — (delegates to every step below) |
| Validate | `./ops preflight` | `.env` exists | — |
| Create server | `./ops provision` | provider token (`HCLOUD_TOKEN` or `DO_API_TOKEN`) in `.env` | — (idempotent; reports the existing IP if the server already exists) |
| Lock down | `./ops harden` | root SSH to a **fresh** Ubuntu 24.04 box | **Docker** — restart at the very end of the script, plus a UFW reload |
| Bridge | `./ops prepare` | hardened server reachable as `deploy@`, `gh` authenticated | — (also runs `github-env-sync.sh` as its step 6) |
| Wire CI | `./ops github-env-sync` | `gh` authenticated, a GitHub remote on the repo | — (safe to re-run any time secrets change) |
| DNS/tunnel/R2 | `./ops cloudflare` | `CLOUDFLARE_API_TOKEN` + `_ACCOUNT_ID` + `_ZONE_ID` + `DOMAIN` in `.env` | restarts `cloudflared` on the server automatically at the end of the script (only if `DROPLET_HOST` is set) |
| First boot | `ssh -i ~/.ssh/<brand>_mc_deploy_key deploy@IP 'cd ~/server && .stack/current/stack/scripts/initial-setup.sh'` | `.stack/current` already installed (prepare-droplet.sh did this) | — (delegates to `deploy.sh --non-interactive`) |

`./ops setup` runs this whole sequence interactively and re-entrantly: it fast-forwards past anything already set in `.env`, and every phase can be declined and re-run later with the standalone command shown in its own output.

**Two scripts in `scripts/` have no `./ops` command at all** — check `examples/consumer/ops`'s `ALLOWED_COMMANDS` array before assuming one exists:
- `ddns-update.sh` — ships in the bundle (`build-stack-bundle.sh` MANIFEST), but a consumer must invoke it directly: `bash .stack/current/stack/scripts/ddns-update.sh --install-cron`. There is no `./ops ddns`.
- `destroy-server.sh` — **not in the bundle manifest at all.** It only exists in this platform repo, is Hetzner-only (calls `hcloud`), and is meant for platform-dev clean-slate testing. A consumer repo cannot run it. See [Teardown vs destroy](#teardown-vs-destroy).

## Cloudflare: three credential systems (read this before pasting anything)

The stack uses two of Cloudflare's three credential systems. They come from different dashboard pages and are not interchangeable.

1. **`CLOUDFLARE_API_TOKEN`** — a custom **API Token** (My Profile → API Tokens → Create Custom Token) with `Account/Cloudflare Tunnel:Edit`, `Account/Workers R2 Storage:Edit`, `Account/Workers Scripts:Edit`, `Zone/DNS:Edit`, `Zone/Zone:Read`, scoped to your `DOMAIN`. Used as a Bearer token for every DNS record, the tunnel, R2 *bucket* creation, and the maintenance Worker.
2. **`R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY`** — an S3 keypair (R2 → Manage API Tokens → Create Account API token → Object Read & Write), used only by restic (the `mc-backup` container) over the S3 protocol.
3. **The Global API Key** — not used anywhere in this stack. Tutorials asking for "your API key and email" mean this legacy scheme; ignore it here.

The trap: the R2 keypair page **also calls its product an "API token"** and shows three values (Token value, Access Key ID, Secret Access Key). Only the last two go in `.env`. Pasting the Token value into `CLOUDFLARE_API_TOKEN` produces `Invalid API Token` on every DNS and tunnel call — it's scoped to R2 only and Cloudflare's DNS/tunnel API rejects it outright. (The Access Key ID is the R2 token's ID and the Secret is the SHA-256 of the token value, which is why the page shows all three.)

`CLOUDFLARE_ACCOUNT_ID` and `CLOUDFLARE_ZONE_ID` are auto-resolved by `./ops setup` and re-verified by `./ops preflight` once the token is valid — a hand-pasted wrong zone ID masks what is actually a token problem, so don't hand-enter these unless the auto-resolve step explicitly fails. `CLOUDFLARE_TUNNEL_ID` is generated by `./ops cloudflare` — never hand-create it.

Full permission matrix, the verify curl, and the R2 keypair walkthrough: `references/credential-sourcing.md` and `references/credentials.md`.

## The game port is never tunnelled

Fixed decision (`AGENTS.md:45`): Cloudflare's free tier tunnel is **HTTP-only** and fails silently for TCP game traffic. `cloudflare-setup.sh` creates `mc.DOMAIN` as a plain **DNS-only (grey-cloud) A record** pointing straight at the server IP — never proxied, never tunnelled. It also creates an SRV record (`_minecraft._tcp.mc.DOMAIN`) so friends can connect with just `mc.DOMAIN`, no port needed. The tunnel exists only to carry `map`/`pack`/`status`/`mods` (all HTTP). If you're ever asked to "just tunnel the Minecraft port for simplicity," the answer is no — it silently doesn't work on the free tier.

## Idempotent, not side-effect-free

Every step in the chain is safe to re-run, but re-running is not free of consequences:

- **`harden.sh` restarts Docker** as its last hardening action (daemon.json hardening + UFW/NAT rules require it) — run it at provision time only, **never** during or near a CI deploy.
- **`harden.sh` also disables root SSH and password auth** as its final step. Verify a new session as the `deploy` user works *before* closing the root session that's running it — getting this wrong locks you out of a fresh server with no recovery path short of the provider's rescue console.
- **`cloudflare-setup.sh` restarts `cloudflared` on the server automatically** at the end of the script (only when `DROPLET_HOST` is set) — you don't need a separate manual restart after a normal run. You DO need one if you hand-edit `config/cloudflared/config.yml` without re-running the script.
- **`provision.sh --provider local`** does nothing but print manual instructions — there's no cloud call to make idempotent.

## The GitHub wiring gate (silent skip)

`deploy.yml`'s job gates on `if: vars.DROPLET_HOST != ''`. Without `./ops github-env-sync` (or the equivalent step inside `prepare-droplet.sh`), pushes to `main` are **silently skipped**, not failed — nothing in the Actions UI screams at you. Required vars (`DROPLET_HOST`, `DEPLOY_USER`) are pushed to **both** the environment scope and the repo scope, because the gate is evaluated before the environment attaches and can't see environment-scoped variables. Check wiring without changing anything: `./ops github-env-sync --check`.

## Teardown vs destroy

- **`./ops teardown`** (→ `teardown.sh`) — the full, target-aware, double-confirmed reverse of setup: stops containers, offers to delete the cloud server (Hetzner or DO), the R2 bucket, `/etc/hosts` entries, local `data/`+`cache/`, and provider CLI auth contexts. Ships in every bundle. Account-level credentials (API tokens, the deploy key, the 1Password item) are deliberately **preserved** so the next `./ops provision` just works.
- **`destroy-server.sh`** — deletes only the Hetzner server + its firewall. Not in the bundle manifest, not reachable via `./ops`, Hetzner-only. It exists purely for platform-repo dev/test clean-slate cycles; run it as `./scripts/destroy-server.sh` from a full checkout of this template repo. A consumer repo has no path to it at all — if a consumer needs equivalent behaviour, `./ops teardown --target hetzner` covers the same ground interactively.

## Traps

1. **Never tunnel the game port.** Free tier is HTTP-only; it fails silently, not loudly.
2. **`harden.sh` restarts Docker** — provision time only, never during or near a CI deploy.
3. **Verify a new SSH session before closing the root one.** `harden.sh` switches SSH to key-only, no root login, as its last act.
4. **`CLOUDFLARE_TUNNEL_ID` is generated by `./ops cloudflare`**, never hand-created.
5. **`CLOUDFLARE_ZONE_ID` and `CLOUDFLARE_ACCOUNT_ID` are auto-resolved** by setup/preflight once the token verifies — a hand-pasted wrong one usually means the token (not the ID) is the real problem.
6. **`R2_ACCOUNT_ID` is the same value as `CLOUDFLARE_ACCOUNT_ID`.** People go looking for a separate one; there isn't one.
7. **`RESTIC_PASSWORD` is invented by you (or auto-generated by setup.sh) and unrecoverable if lost.** It's mirrored to 1Password if connected — check it landed there.
8. **`ddns-update.sh` and `destroy-server.sh` have no `./ops` command.** Run the former via `.stack/current/stack/scripts/ddns-update.sh`; the latter doesn't exist for consumers at all (see [Teardown vs destroy](#teardown-vs-destroy)).
9. **Home hosting needs port forwards for `25577/tcp` and `24454/udp`**, plus either a static IP or a cron'd `ddns-update.sh`. WSL2 needs `networkingMode=mirrored` in `.wslconfig`, or `netsh portproxy` re-applied after every WSL restart (it doesn't persist).
10. **`gh release list --limit 1` returns the most recently *published* release, not the highest version** — a backported patch can publish after a newer minor. Relevant when pinning provider CLI versions or images during setup; use `--limit 5` and check `isLatest`.
11. **A fresh Hetzner droplet may run `unattended-upgrades` on first boot**, holding the dpkg lock. `harden.sh`'s `wait_for_apt_lock` handles this automatically — if you're scripting around it yourself, don't assume `apt-get` is free to run immediately after the server reports "created".

## Validation (do not skip)

```bash
./ops preflight                     # per-item pass/fail before anything is created
curl -s "https://api.cloudflare.com/client/v4/user/tokens/verify" \
  -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN"     # -> "status": "active"
./ops github-env-sync --check       # confirms deploy.yml's gate will actually fire
./ops doctor                        # after first deploy — full health triage
dig mc.example.com                  # -> server IP directly (grey cloud, no CDN IP)
```

Loud failures: missing `.env` values (preflight lists each one), `provision.sh` with no provider token (`HCLOUD_TOKEN`/`DO_API_TOKEN` unset), SSH refused mid-`harden.sh` retry loop. Silent failures: an invalid Cloudflare token (DNS/tunnel calls just fail with `Invalid API Token`, nothing crashes), a missing GitHub environment wiring (deploys are skipped, not failed), a game port accidentally routed through the tunnel (connections just time out).

## References

- `references/credential-sourcing.md` — obtaining-order checklist, full Cloudflare permission matrix, format-validation regexes for every secret.
- `references/step-chain.md` — every script in the chain: preconditions, what it does, what it restarts, how to re-run it alone.
- `references/credentials.md` — canonical credential table and rotation guide.
- `references/setup-walkthrough.md` — full human walkthrough, Discord bot setup, GitHub environment tables, per-OS notes (WSL2 networking, home-hosting, TCPShield, DDNS).
- `SECURITY.md` (repo root) — attack surface table and hardening summary.
- Sibling skills: `env-and-secrets` (`.env` lifecycle, four-places rule, rotation), `deploy-pipeline-operations` (ongoing deploys after first boot), `production-triage` (something's wrong on a running server).
