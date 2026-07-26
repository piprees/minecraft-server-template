# Skill brief: `consumer-repo-operations`

> **This is a brief, not the skill.** Build `SKILL.md` from it; verify every path against the repo before writing.

## Why this skill

A consumer repo (`~/Projects/elfydd` is the live one) looks almost empty: `dev`, `ops`, `.env`, `overlay/`, one workflow. Everything an agent would want to read is *not there* — the compose file, the scripts, the default configs all arrive as a versioned tarball in `.stack/current/stack/`. An agent dropped into a consumer repo with no skill will:

- look for `docker-compose.yml`, not find it, and conclude the repo is broken
- edit `data/mods/` directly (managed; overwritten)
- edit `dev`, `ops`, `.env.example` or `AGENTS.md` (platform-owned; overwritten by `./dev update`)
- try to change a game rule or a client mod there (both belong upstream)
- assume its own file diff decides the deploy tier (it usually doesn't)

`examples/consumer/AGENTS.md` covers most of this in 112 lines and is genuinely good — but it is a *repo contract*, not a task guide, and it is the file most likely to be skipped because it says "read README.md first".

## Scope

**In:** everything an agent does while working *inside* a consumer repo. The overlay contract and its merge semantics, the `dev`/`ops` command surface, reading platform scripts you don't have checked out, the update/sync flow, what you may and may not change locally, and the escalation path to the platform repo.

**Out:** the local Docker stack's internals and testing traps → brief 11. Production diagnosis → brief 03. Mod list mechanics → brief 01 (cross-reference).

## Source material

| File | What to mine |
| --- | --- |
| `examples/consumer/AGENTS.md` | The whole contract. This is the skill's backbone — restructure it task-first |
| `examples/consumer/README.md` (319 lines) | The consumer-facing quick start, customisation, directory structure |
| `examples/consumer/commands.json` | **The authoritative `dev`/`ops` command list with descriptions and a `production: true/false` flag.** Render it as a table in the skill |
| `examples/consumer/dev` — the `update)` case | The scaffold sync list; the write-to-temp+mv self-update trick; what is deliberately excluded (`README.md`, `overlay/`) |
| `examples/consumer/ops` — the dispatch table (lines ~170-240) | The command → script mapping, including the renames (`preflight`→`preflight-check`, `update`→`remote-update`, `reauth-kuma`→`kuma-token`) |
| `examples/consumer/overlay/**/README.md` | What each overlay subdir is for |
| `docker/defaults-seed/seed.sh` | **The overlay merge semantics.** `rsync -a --delete` defaults, then overlay over it — *except* `custom-dimensions`, which lands in `custom-dimensions/overlay/` for the mod to merge itself |
| `AGENTS.md:196` | The consumer scaffold sync trap — adding a file to `examples/consumer/` without adding it to the sync list |
| `scripts/stack-pull.sh` (header) | How the bundle resolves and caches; `STACK_VERSION` semantics (`v2` floats, `v2.1.3` pins); offline tolerance |
| `README.md § Configuration` | The three-layer model: platform defaults → consumer overlay → `.env` |

## Required structure

```
consumer-repo-operations/
├── SKILL.md
└── references/
    ├── overlay-contract.md    # every overlay path, merge semantics, worked examples incl. custom-dimensions
    └── command-surface.md     # dev + ops full table, generated from commands.json, with the script each dispatches to
```

### SKILL.md must contain

1. **"You do not have the platform repo" as the opening fact**, with both ways to read a platform script:
   ```bash
   cat .stack/current/stack/scripts/deploy.sh
   curl -sL https://raw.githubusercontent.com/piprees/minecraft-server-template/main/scripts/deploy.sh
   ```
   Prefer the bundle cache — it is the version actually running.
2. **A "what lives where" table** (consumer vs platform), lifted and tightened from `examples/consumer/AGENTS.md:11-19`.
3. **The overlay contract with real merge semantics**, not just a directory list. Specifically:
   - `overlay/config/` rsyncs over the platform defaults
   - `overlay/config/custom-dimensions/` is the exception — it is staged into `custom-dimensions/overlay/` and merged *by the mod*: no `"overrides"` key = full replacement, `"overrides": {...}` = deep merge, `{}` = disable, new filename = new dimension namespaced by `BRAND_SLUG`
   - `overlay/mods-extra.txt` / `mods-remove.txt` are applied against the platform list by `seed.sh`
   - `overlay/assets/` is branding
4. **A "can I change this here?" decision table.** Yes: server mods, config overrides, branding, `.env`, custom dimensions, seed. No — PR to the platform repo: client mods, game rules, permissions, default configs, scripts, compose.
5. **Platform-owned files that `./dev update` overwrites**: `dev`, `ops`, `.env.example`, `.gitignore`, `AGENTS.md`, `commands.json`, and the three workflows. Never customise them. `README.md` and `overlay/` are consumer-owned and never touched.
6. **The three update commands, distinguished plainly**, because they are easy to confuse:
   | Command | Scope |
   | --- | --- |
   | `./dev pull` | Fetch the bundle only |
   | `./dev update` | Bundle + images + scaffold resync (local) |
   | `./dev sync` | Everything: local down → update → env sync to GitHub → server update → local up |
   | `./ops update` | Production server only (bundle, images, full redeploy) |
7. **The `dev` vs `ops` split as a safety property**: `dev` is local, `ops` is production. `commands.json` carries the `production` flag per command — say that it exists and is the source of truth.

### Traps to capture

1. **Never hand-edit `data/mods/`** — managed by Modrinth sync and bundle installs; your jar is overwritten.
2. **Never edit platform-owned scaffold files** — `./dev update` overwrites them silently. Symptom: your change disappears after an update with no error.
3. **Your file diff usually does not decide the deploy tier.** Consumer repos have almost no deployable files; most full deploys come from the resolved-tag comparison. A docs-only push after a platform release rolls that release out.
4. **A file added to `examples/consumer/` upstream reaches nobody** unless it is added to the `update)` sync list in `examples/consumer/dev`. (Platform-side trap, but consumers see the symptom: "the new file never arrived".)
5. **`STACK_VERSION=v3` floats** — you receive minor and patch releases automatically. Pin exactly for reproducibility; bump majors deliberately and read the migration notes.
6. **The local consumer server is shared** (`AGENTS.md:210`) — check nobody is mid-test before restarting its containers.
7. **A top-level `stack-pull.sh` in the consumer root is stale scaffold** — the puller lives in the bundle; `./dev update` removes it.

### Validation section

```bash
./dev doctor          # local health
./ops doctor          # production health
git status            # nothing platform-owned modified
readlink .stack/current
```

## Done when

- An agent dropped into a consumer repo with no prior context can locate `deploy.sh`, name the four things it may change locally, and correctly refuse to edit `data/mods/` or `ops`.
- The command table matches `commands.json` exactly (it should be *derived* from it, and the skill should say so, so it is obvious when it drifts).
