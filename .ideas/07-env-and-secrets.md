# Skill brief: `env-and-secrets`

> **This is a brief, not the skill.** Build `SKILL.md` from it; verify every path against the repo before writing.

## Why this skill

The environment model is the most-cited trap class in the repo and the one with the widest blast radius. Two facts do the damage:

1. **Every full CI deploy regenerates the server's `.env` from GitHub environment secrets.** A hand-edit on the server is wiped, silently, on the next deploy. Agents fix things by editing the server `.env` constantly — it is the obvious move and it is always wrong.
2. **Adding a secret means updating four places** (`.env.example`, 1Password + `config/1password.env`, the GitHub environment, and the reusable workflow's secrets list). Missing any one produces a failure far from the cause. The `KUMA_API_KEY` incident (2026-07-11) is exactly this: the consumer `.env` had no `KUMA_API_KEY` line, so `github-env-sync.sh` never pushed one, so every full deploy wiped the hand-installed token, so a Kuma maintenance window stayed open for hours.

There is also a genuinely dangerous formatting rule: **every `.env` value must be single-quoted** via `set_env_var`/`env_quote` in `lib.sh`. An unquoted MOTD once executed itself as a command on a production server.

## Scope

**In:** the `.env` model end to end. Where truth lives, adding/rotating/removing a setting or secret, the GitHub environment, 1Password backup and restore, quoting rules, and which variables the compose file defaults for you.

**Out:** obtaining credentials from providers for the first time → brief 12 (cross-reference `docs/credentials.md` there). Kuma's auth specifically → brief 03.

## Source material

| File | What to mine |
| --- | --- |
| `AGENTS.md § The environment model` (lines 68-74) | The four-places rule; CI regeneration; never commit `.env`/`data/`/`cache/` |
| `AGENTS.md § Conventions → .env writing` (line 202) | `set_env_var`/`env_quote`; embedded `'` → `’`; the MOTD incident |
| `AGENTS.md` trap 7 | The `KUMA_API_KEY` wipe — the canonical worked example of the four-places rule failing |
| `.env.example` (267 lines) | **The reference.** Its own conventions block: `VAR=` required, `# VAR=` optional override, `# e.g.` sample values |
| `scripts/github-env-sync.sh` (header) | The only place GitHub gets wired; `REQUIRED_SECRETS`/`OPTIONAL_SECRETS`/`*_VARS`; `--check`, `--allow-missing`, `--env-name`; `DEPLOY_SSH_KEY` read from the key file, not `.env` |
| `scripts/op-env.sh`, `scripts/op-sync-env.sh` (headers) | 1Password round trip; per-brand item names; the "rotate locally then sync, never the reverse" rule |
| `config/1password.env` | The `op://` reference map |
| `scripts/lib.sh` (header + `load_env`, `set_env_var`, `env_quote`) | Precedence: `CONSUMER_DIR` > pre-set `PROJECT_DIR` > derived |
| `.github/workflows/deploy-reusable.yml` | The `secrets:` block — the fourth place; and the server `.env` generator applying the same quoting rule |
| `docker-compose.yml` | Every `${VAR}` carries an inline `${VAR:-default}` — platform defaults live here, overrides live in `.env` |
| `docs/credentials.md` | The per-variable table and rotation quick reference |
| `scripts/preflight-check.sh` (header) | Validates `.env` values, tools and credentials |

## Required structure

```
env-and-secrets/
├── SKILL.md
└── references/
    ├── adding-a-setting.md   # the four-places checklist, worked end to end for one secret and one plain var
    └── env-file-anatomy.md   # the .env.example conventions, quoting rules, compose fallbacks, precedence
```

### SKILL.md must contain

1. **The truth hierarchy, first, as a diagram or table:**
   - **GitHub `production` environment** (vars + secrets) — source of record for the server
   - **1Password** (`Dev` vault, `Minecraft Server - <BRAND_SLUG>`) — recovery store
   - **local `.env`** — git-ignored working copy; what `github-env-sync.sh` pushes *from*
   - **the server's `.env`** — CI-generated output, **never** an input
2. **The rule in one sentence, prominent:** hand-edits to the server's `.env` do not survive the next full deploy. Change the source of truth. Hand-edit only as a stop-gap and say so.
3. **The four-places checklist** as a literal checklist:
   - [ ] `.env.example` (with a `# e.g.` sample, following the file's own conventions)
   - [ ] 1Password: `config/1password.env` reference + `./ops op-sync-env`
   - [ ] GitHub: `gh secret set X --env production` (or `./ops github-env-sync`)
   - [ ] `.github/workflows/deploy-reusable.yml` `secrets:` block — **only if the server needs it at runtime**
4. **A "secret or variable?" rule.** Secrets are masked and unreadable; variables (`DROPLET_HOST`, `DEPLOY_USER`) are readable and gate jobs — `deploy.yml` is silently *skipped* when `vars.DROPLET_HOST` is empty. That silent skip deserves its own line.
5. **The quoting rule**, non-negotiable: never write a raw `KEY=$value` line. Use `set_env_var`/`env_quote` from `lib.sh`. Embedded `'` maps to `’`. User-pasted values arriving pre-wrapped in quotes are stripped on input.
6. **Compose fallbacks**: every `${VAR}` in `docker-compose.yml` carries `${VAR:-default}`, so a lean consumer `.env` with only filled-in lines is a complete config. An agent should *not* add a variable to `.env` just to restate the default.
7. **Rotation**, from `docs/credentials.md`: rotate in the provider → paste into local `.env` → `./ops op-sync-env` → `./ops github-env-sync` → full deploy if the server needs it. And `RESTIC_PASSWORD` is the exception: do not rotate; all existing backups become unreadable.

### Traps to capture

1. **Server `.env` hand-edits are wiped by the next full deploy.** Symptom: a fix works, then stops working days later with no change.
2. **An empty GitHub secret actively wipes a working server value.** `github-env-sync.sh` pushes what is in your local `.env`; a variable missing locally is never pushed; the CI generator then writes an empty value over the server's. This is the `KUMA_API_KEY` incident.
3. **An unquoted value can execute.** The MOTD incident.
4. **`DEPLOY_SSH_KEY` is not in `.env`** — it is read from the private key file at `DEPLOY_KEY_PATH` (default `~/.ssh/mc_deploy_key`).
5. **`op-sync-env` pushes local *over* 1Password.** Empty local values are skipped, non-empty ones overwrite. Rotate locally first, then sync — never sync while a stale `.env` exists anywhere.
6. **One 1Password item per server/brand.** A shared item name means one repo's sync clobbers another's freshly rotated credentials. Keep custom names free of parentheses and punctuation — `op://` references reject them.
7. **`REQUIRED_SECRETS`/`OPTIONAL_SECRETS`/`*_VARS` in `github-env-sync.sh` must stay in sync with `secrets.*`/`vars.*` usage across `.github/workflows/*.yml`.** Nothing enforces this.
8. **Never commit `.env`, `data/`, or `cache/`.**

### Validation section

```bash
./ops github-env-sync --check     # read-only report
./ops preflight                   # validates values, tools, credentials
gh secret list --env production
gh variable list --env production
```

Silent failure to call out: a variable that exists locally and in 1Password but not in GitHub works perfectly in local dev and is empty on the server. Only `--check` catches it before a deploy does.

## Done when

- An agent asked to add a new setting produces all four edits without being prompted, and never proposes editing the server's `.env` as the fix.
- The skill can be used to diagnose "this worked and then stopped after a deploy" in under a minute.
