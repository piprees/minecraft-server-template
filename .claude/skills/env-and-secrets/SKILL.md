---
name: env-and-secrets
description: |
  Explains the .env truth hierarchy for this Minecraft server platform —
  GitHub's `production` environment, 1Password, the local `.env`, and the
  server's CI-generated `.env` — plus the mandatory quoting rule
  (`set_env_var`/`env_quote` in `scripts/lib.sh`) every writer must use.
  Covers the four places a new setting or secret must be added, the
  `./ops github-env-sync` and `./ops op-sync-env` round trips, and why the
  server's `.env` must never be hand-edited as a fix.

  Use when: adding or rotating a `.env` variable or secret; a value that
  works locally is empty or wrong on the server after a deploy; diagnosing
  `authIncorrectCreds` after a Kuma token vanished; deciding whether a
  change needs `.env.example`, 1Password, GitHub, or the reusable
  workflow's `secrets:` block; or auditing drift with
  `./ops github-env-sync --check` before it becomes a deploy incident.
---

# Env and Secrets

You are working with the `.env` model for this platform (or a consumer repo built from it). Before touching any variable or secret, understand where the truth lives — most incidents in this area happen because an agent edited the wrong copy.

## MANDATORY: read these before changing anything env-related

| File | Why you need it |
| --- | --- |
| `.env.example` | The committed reference. Every variable this stack understands, with its convention markers (below). |
| `scripts/lib.sh` (`load_env`, `set_env_var`, `env_quote`, `strip_surrounding_quotes`) | The only correct way to write a `.env` line. Never write `KEY=$value` by hand. |
| `scripts/github-env-sync.sh` | The only place GitHub gets wired. `REQUIRED_SECRETS`/`OPTIONAL_SECRETS`/`REQUIRED_VARS`/`OPTIONAL_VARS` are the authoritative "what does CI need" lists. |
| `config/1password.env` | The `op://` reference map — field names here must match what `op-sync-env.sh` writes. |
| `.github/workflows/deploy-reusable.yml` (`secrets:` block + "Generate server .env" step) | The fourth place, and the actual mechanism that regenerates the server's `.env` on every full deploy. |

Also skim the credentials reference in the `server-provisioning` skill for the per-variable provider/format table and the rotation reference.

## The truth hierarchy

Four layers, one direction of truth. Nothing flows "up" except by a human deliberately running a sync script.

| Layer | Role | Written by | Read by |
| --- | --- | --- | --- |
| **GitHub `production` environment** (vars + secrets) | Source of record for the server | `./ops github-env-sync` (wraps `gh secret set` / `gh variable set`) | The "Generate server .env" step in `deploy-reusable.yml` |
| **1Password** (`Dev` vault, item `Minecraft Server - <BRAND_SLUG>`) | Recovery store, not source of record | `./ops op-sync-env` (pushes local → 1Password) | `./ops op-env` (pulls 1Password → stdout, pipe to `.env`) |
| **Local `.env`** (git-ignored) | Your working copy — the thing you actually edit | You, by hand, or `./ops op-env > .env` | Every bundle script via `load_env()`; the source for both `github-env-sync.sh` and `op-sync-env.sh` |
| **Server's `.env`** (`~/server/.env`) | **CI-generated OUTPUT, never an input** | The "Generate server .env" step, on every **full** deploy | `docker compose` on the server, nothing else |

**The rule, in one sentence: hand-edits to the server's `.env` do not survive the next full deploy.** If a value is wrong on the server, the fix is in the GitHub environment (or the local `.env` that feeds it), never on the box. Hand-edit only as a stop-gap to unblock players _right now_, and say so out loud — it will be silently reverted.

## Adding, rotating, or removing a setting: the four-places checklist

Every setting or secret this stack reads must exist in up to four places. Miss one and the failure surfaces somewhere far from the cause (see the KUMA_API_KEY trap below).

- [ ] **`.env.example`** — add the line following the file's own conventions (see below). This is what a fresh consumer copies and what `op-env.sh` uses as the "committed config" base.
- [ ] **1Password** — two edits, not one: add the `op://` reference to `config/1password.env` (so `./ops op-env` can restore it), **and** add a matching `sync_field "NAME" "${NAME:-}"` call to `scripts/op-sync-env.sh` (so `./ops op-sync-env` actually pushes it — there's no loop, every field is a hand-written call). Then run `./ops op-sync-env` to push your local value into the vault item.
- [ ] **GitHub `production` environment** — `./ops github-env-sync` (pushes everything sourced from local `.env`), or `gh secret set X --env production` / `gh variable set X --env production` by hand. **Only if it's a secret or var, add it to the matching array (`REQUIRED_SECRETS`/`OPTIONAL_SECRETS`/`REQUIRED_VARS`/`OPTIONAL_VARS`) in `scripts/github-env-sync.sh` first** — the script only pushes what's in those lists.
- [ ] **`.github/workflows/deploy-reusable.yml`** — only if the _server_ needs the value at runtime (i.e. it must land in the server's generated `.env`). This means THREE edits in the same file, not one: the `secrets:` input declaration at the top, an `env:` mapping (`S_FOO: ${{ secrets.FOO }}` or `V_FOO: ${{ vars.FOO }}`) in the "Generate server .env" step, and an `emit FOO "$S_FOO"` line in that step's script. Adding only the top-level `secrets:` declaration does nothing — the value never reaches the file.

A pure CI-only variable (say, something only `github-env-sync.sh` itself consumes) stops at step 3. A value the game server or a sidecar reads at boot needs all four.

## Secret or variable?

- **Secrets** (`gh secret`) are masked in logs and unreadable after creation. Use for tokens, passwords, keys.
- **Variables** (`gh variable`) are plain text, readable, and can **gate jobs**. `deploy-reusable.yml`'s deploy job carries `if: vars.DROPLET_HOST != ''` — an empty `DROPLET_HOST` doesn't fail the workflow, it **silently skips the whole job**. `preflight-check.sh` and `github-env-sync.sh --check` both check for this; nothing else will tell you.
- Required vars are pushed to **both** the environment scope and the repo scope by `github-env-sync.sh` (`push_var`) — the job gate is evaluated before the environment attaches, so an environment-scoped-only `DROPLET_HOST` is invisible to it. `--check` reports this exact state as `env-scoped only - deploy gate can't see it`.

## The quoting rule (non-negotiable)

**Never write a raw `KEY=$value` line to any `.env` file.** Use `set_env_var`/`env_quote` from `scripts/lib.sh`. Every value is written single-quoted, with any embedded `'` mapped to a typographic `’`. An unquoted MOTD containing spaces once executed itself as a command on a production server.

- `env_quote` strips one layer of surrounding quotes first (`strip_surrounding_quotes`) — a user pasting `'My Token'` or `"My Token"` doesn't get double-wrapped.
- `deploy-reusable.yml`'s "Generate server .env" step does **not** call `lib.sh` (GitHub's runner never checks it out in a form that's sourced there) — it re-implements the identical rule inline with its own `emit()` function. If you ever change the quoting convention, both places need the edit.
- The generated file is validated with `bash -n` and a dry `source` before it's ever copied to the server — a quoting bug is caught in CI, not mid-deploy.

## Compose fallbacks — don't restate defaults

Every `${VAR}` in `docker-compose.yml` carries an inline `${VAR:-default}` (e.g. `${MEMORY:-5G}`, `${SERVER_PORT:-25577}`, `${VIEW_DISTANCE:-10}`, `${IMAGE_REGISTRY:-ghcr.io/piprees/minecraft-server-template}`). A lean `.env` with only the filled-in lines is a **complete** config — platform defaults live in the compose file, not in `.env.example`. Don't add a variable to `.env` just to restate the default already shown (commented) in `.env.example`; that's noise a future diff has to explain.

## Rotation

Provider dashboard → paste the new value into local `.env` → `./ops op-sync-env` → `./ops github-env-sync` → trigger a full deploy if the server needs it at runtime. See the credentials reference in the `server-provisioning` skill for the per-credential quick reference.

**`RESTIC_PASSWORD` is the one exception: do not rotate it.** All existing restic backups become permanently unreadable without the original passphrase. If it must change, that's a new backup repository, not a rotation.

## Traps

1. **Server `.env` hand-edits are wiped by the next full deploy.** Symptom: a fix works, then silently reverts days later with no visible change to blame. Cause: every full deploy's "Generate server .env" step overwrites the whole file from GitHub environment secrets/vars, unconditionally.
2. **An empty or missing GitHub secret actively wipes a working server value — this is the `KUMA_API_KEY` incident (2026-07-11).** The "Generate server .env" step `emit`s _every_ value, required or optional, whether or not GitHub actually has it — a missing secret resolves to an empty string via `${{ secrets.KUMA_API_KEY }}`, and `emit KUMA_API_KEY ""` still writes the line, overwriting the real token already on the server. Root cause that day: the consumer `.env` had no `KUMA_API_KEY` line, so `github-env-sync.sh` never pushed one to GitHub (its `push_secret` skips truly-empty optional values rather than clearing an existing GitHub secret) — but the _next full deploy_ still emitted an empty line into the server's `.env`, and the maintenance window stayed open for hours. Lesson: "optional" only protects the _local→GitHub_ push; it does nothing to protect the _GitHub→server_ emit. If a runtime secret is optional and currently unset in GitHub, every full deploy will null it out on the server.
3. **An unquoted value can execute.** Symptom: a production server ran arbitrary shell. Cause: a `.env` line written without `set_env_var`/`env_quote` — an unquoted `MOTD` with spaces executed as a command when sourced. Always go through the helpers, on both the local-write side and the CI-generator side.
4. **`DEPLOY_SSH_KEY` is not in `.env` at all.** It's read straight from the private key file at `DEPLOY_KEY_PATH` (default `~/.ssh/mc_deploy_key`, or `~/.ssh/<BRAND_SLUG>_mc_deploy_key` once `BRAND_SLUG` is set) and pushed as a secret from its file contents. Looking for it in `.env` or `.env.example` wastes time — it isn't there by design.
5. **`op-sync-env` pushes local _over_ 1Password, one-way.** Empty local values are skipped (existing 1Password field left alone); non-empty ones overwrite unconditionally. Rotate locally first, then sync — never sync while a stale `.env` exists anywhere, or the stale copy wins and clobbers a freshly-rotated credential.
6. **One 1Password item per server/brand — a shared item name clobbers across repos.** `op-sync-env.sh`/`op-env.sh` both resolve the item name from `BRAND_SLUG` (`Minecraft Server - <BRAND_SLUG>`, override with `OP_ITEM_NAME`). Keep custom names free of parentheses and punctuation — `op://` references reject them and every restore breaks.
7. **`config/1password.env` field names don't always match the `.env` var name.** `KUMA_API_KEY` reads from `op://.../KUMA_UPTIME_CHECKS_API_KEY` — a different field name in the vault than the variable in `.env`. Check the actual `op://` reference before assuming a 1:1 name mapping when troubleshooting a missing value.
8. **`REQUIRED_SECRETS`/`OPTIONAL_SECRETS`/`*_VARS` in `github-env-sync.sh` must stay in sync with `secrets.*`/`vars.*` usage across `.github/workflows/*.yml` by hand.** Nothing enforces this. Adding a `secrets.FOO` reference to a workflow without adding `FOO` to one of these arrays means `github-env-sync.sh` never pushes it, however carefully you filled in `.env`.
9. **Never commit `.env`, `data/`, or `cache/`.** `preflight-check.sh` checks this two ways (tracked-by-git and not-ignored are different failure modes) because `git add -f` bypasses `.gitignore` silently.

## Validation (do not skip this)

```bash
./ops github-env-sync --check     # read-only: reports every missing/env-scoped-only required item
./ops preflight                   # validates .env values, tools, and credentials
gh secret list --env production
gh variable list --env production
```

**Silent failure to call out:** a variable that exists locally and in 1Password but was never pushed to GitHub works perfectly in local dev and is empty on the server — nothing fails loudly until a player notices. Only `--check` (or a diff of the three sources) catches it before the next full deploy does it for you.

## References

- `references/adding-a-setting.md` — the four-places checklist worked end to end, for one secret and one plain variable.
- `references/env-file-anatomy.md` — `.env.example` conventions, the 1Password `op://` reference format (including per-environment sections), and the load-order precedence rules.
