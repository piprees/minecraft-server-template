---
name: consumer-repo-operations
description: |
  Guides work inside a consumer repo built from minecraft-server-template's examples/consumer scaffold (e.g. ~/Projects/elfydd) — a thin dev/ops dispatcher pair plus overlay/ and .env, where the compose file, scripts, and default configs are NOT checked out locally but arrive as a versioned bundle in .stack/current/stack/. Covers the overlay contract and its real merge semantics (overlay/config/, mods-extra.txt/mods-remove.txt, the custom-dimensions staging exception, the modpack manifest add/remove patch), the dev vs ops split and commands.json's production flag, and the update paths (dev pull/update, ops update/sync).

  Use when: dropped into a consumer repo and docker-compose.yml or scripts/ can't be found; deciding whether a change belongs in overlay/ or needs a template-repo PR; adding/removing a server or client mod; reading a platform script like deploy.sh that isn't checked out; distinguishing dev update from ops sync; or a hand-edited dev/ops/AGENTS.md change vanished after "./dev update".
---

# Consumer repo operations

You are working inside a **consumer repo** — a server owner's copy of `examples/consumer/` from `minecraft-server-template`, not the platform repo itself. The live example is `~/Projects/elfydd`. Read `README.md` and `AGENTS.md` in the repo root first; this skill is the task-shaped index over both.

## The one fact that explains the whole repo

**You do not have the platform repo checked out.** `docker-compose.yml`, `scripts/`, and the default `config/` don't exist here — they're pulled as a versioned tarball into `.stack/current/stack/` by the bundle puller. If you go looking for the compose file or a script and it isn't there, this is why; it isn't a broken repo.

To read a platform script, either read the cached bundle (the version actually running) or fetch from GitHub:

```bash
cat .stack/current/stack/scripts/deploy.sh
curl -sL https://raw.githubusercontent.com/piprees/minecraft-server-template/main/scripts/deploy.sh
```

Prefer the bundle cache — `main` on GitHub may be ahead of what this repo is pinned to.

## What lives where

| This repo (consumer)                               | Template repo (platform)                                    |
| -------------------------------------------------- | ----------------------------------------------------------- |
| `overlay/mods-extra.txt` — extra server mods       | `config/modrinth-mods.txt` — default mod list               |
| `overlay/mods-remove.txt` — mods to exclude        | `docker/` — all GHCR image Dockerfiles                      |
| `overlay/config/` — config overrides               | `config/` — default configs                                 |
| `overlay/assets/` — branding                       | `scripts/` — all operational scripts                        |
| `overlay/modpack/` — client pack patch + overrides | `docker-compose.yml` — the stack definition                 |
| `.env` — secrets and settings                      | `.github/workflows/deploy-reusable.yml` — CI implementation |
| `ops` / `dev` — thin dispatchers                   | the actual scripts they dispatch to                         |

## The overlay contract (short form)

Full merge semantics, worked examples, and the client-pack patch schema are in [`references/overlay-contract.md`](references/overlay-contract.md) — read it before touching any overlay path for the first time. Short version:

- `overlay/config/<path>` rsyncs straight over the platform default at the same path. Your file wins, wholesale — no merging within a file.
- `overlay/config/custom-dimensions/` is the one exception: it does **not** land over `config/custom-dimensions/`. The seed container stages it into `config/custom-dimensions/overlay/`, and the custom-dimensions mod merges it itself (no `"overrides"` key = full replacement, `"overrides": {...}` = deep merge, `{}` = disable that dimension, new filename = new dimension namespaced `{BRAND_SLUG}:<slug>`).
- `overlay/mods-extra.txt` / `overlay/mods-remove.txt` are applied against the platform mod list by `seed.sh` (in the `defaults-seed` image) — remove wins over default, extra either overrides a default line by slug or appends.
- `overlay/assets/` is branding (icon, logo, cover, favicon) — all optional, platform ships placeholders.
- `overlay/modpack/manifest.json` is a **patch**, not a replacement — merged by `docker/modpack-builder/entrypoint.sh` + `merge-manifest.py` before the pack builds. Keys: `add.required`/`add.optional` (arrays of `slug:versionId`), `remove` (slugs), plus scalar overrides. This means **you can add/remove an existing-catalogue client mod from the consumer repo** — contradicting the blanket "not here" row in this repo's own `AGENTS.md` table; see Trap 8.

## Can I change this here?

| Yes — local change | No — PR to the template repo |
| --- | --- |
| Server mods (`overlay/mods-extra.txt`, `overlay/mods-remove.txt`) | Default server mod list (`config/modrinth-mods.txt`) |
| Config overrides (`overlay/config/<path>`) | Default configs (`config/<path>`) |
| Branding (`.env` `BRAND_*`/`MOTD`, `overlay/assets/`) | Game rules (`config/boring_default_game_rules/`) |
| Client mod add/remove for an **already-catalogued** slug (`overlay/modpack/manifest.json` `add`/`remove`) | A genuinely new client mod entering the catalogue, or anything needing `_clientMods.stableOnly`/`holds` |
| Custom dimensions: add, override, or disable per-file (`overlay/config/custom-dimensions/dimensions/`) | Platform-default dimension files (`config/custom-dimensions/dimensions/`) |
| World seed / reset (dimension config `seed`/`spawn`, `.env` `SEED`/`SPAWN_*` as legacy fallback, + `./ops reset-seed`) | LuckPerms permission defaults |
| `.env` secrets and settings | Compose structure, scripts, Dockerfiles |

## Platform-owned files `./dev update` overwrites

`dev`, `ops`, `.env.example`, `.gitignore`, `AGENTS.md`, `commands.json`, and `.github/workflows/{deploy,update,server-power}.yml`. **Never customise these** — the next `./dev update` silently replaces them with the bundle's copy, no diff, no warning. `README.md` is copied once only if missing (so it's yours to customise after that); `overlay/` is never touched by any update path.

## The three-and-a-half update commands

| Command | Scope | What it actually does |
| --- | --- | --- |
| `./dev pull` | Local, bundle only | Fetches/refreshes `.stack/current` — no scaffold sync, no image pull |
| `./dev link [path]` | Local, testing | Points `.stack/current` at a platform checkout's symlink farm (`.stack/dev/stack`) — checkout configs/scripts/compose and its BUILT mod jars are live on the next `./dev up` or seed roll, no release needed. `./dev up` shows a loud LINKED banner while active; deploy paths refuse a `dev` link. `./dev unlink` restores the newest pulled release |
| `./dev update` | Local | `dev pull` + re-syncs the scaffold files above (write-to-temp + `mv`, never `cp` — see Trap 4) + `docker compose ... pull` for local-profile images |
| `./ops update` | Production only | Ships and runs `remote-update.sh` on the server: pulls bundle + images, full redeploy |
| `./ops sync` | Everything | `dev-up.sh down` → `dev update` → `github-env-sync.sh --allow-missing` → `ops update --quick` → `dev-up.sh up` — the one command that touches local, GitHub, and the server in sequence |

Full local + remote alignment is `./ops sync` — it touches production, which is why it lives on `ops`.

## `dev` vs `ops` is a safety boundary, not a naming convention

`dev` only ever touches the local Docker stack. `ops` only ever touches production (with two explicit local exceptions: `op-env`/`op-sync-env`, which move `.env` to/from 1Password). `commands.json` carries a `production: true/false` flag per command — that flag, not the script name, is the authoritative source for "does this touch the live server". See [`references/command-surface.md`](references/command-surface.md) for the full table with the flag and the script each command dispatches to.

## Traps

1. **Never hand-edit `data/mods/`.** Managed by Modrinth sync and bundle installs; your jar is overwritten on the next `./dev up` or deploy. Add mods via `overlay/mods-extra.txt` instead.
2. **Never edit platform-owned scaffold files** (`dev`, `ops`, `.env.example`, `.gitignore`, `AGENTS.md`, `commands.json`, the three workflows). `./dev update` overwrites them silently — no error, no diff shown unless you run `git diff` yourself afterwards.
3. **Your file diff usually doesn't decide the deploy tier.** A consumer repo has almost no deployable files of its own; most full deploys are driven by the resolved `STACK_VERSION` pin no longer matching the bundle the server is running (`readlink .stack/current` on the server) — a docs-only push made after a platform release lands is what actually rolls that release out.
4. **A file added to `examples/consumer/` upstream reaches nobody** unless it's added to the sync list in the `update)` case of `examples/consumer/dev` (platform-side trap — `AGENTS.md:196` in the template repo — but you'll see the symptom here as "the new file never arrived" after `./dev update`).
5. **`STACK_VERSION` pinning**: `v4` (a bare major) floats onto the latest `v4.x.y` — you get minor/patch updates automatically via `./dev update`/`./ops update`. Pin an exact `v4.1.3` for reproducibility; bump majors deliberately and read the platform's migration notes first.
6. **The local consumer server is shared.** Check nobody is mid-test (`docker ps`, ask in Discord) before `./dev down` or restarting local containers — this isn't a personal sandbox.
7. **A top-level `stack-pull.sh` in the consumer root is stale scaffold.** The puller now lives inside the bundle (`.stack/current/stack/scripts/stack-pull.sh`); `dev` only carries a minimal inline bootstrap for the very first pull. `./dev update` deletes a leftover root-level copy.
8. **This repo's own `AGENTS.md` table oversells "Add a client mod: not here."** `overlay/modpack/manifest.json` genuinely can add or remove an existing-catalogue client mod (`{"add": {"required": ["slug:versionId"]}}` / `{"remove": ["slug"]}`) — verified against `docker/modpack-builder/merge-manifest.py`. Trust the patch-key list in `references/overlay-contract.md`, not the worked example in `overlay/modpack/README.md`, which shows the wrong JSON shape (a nested `_clientMods.required` object list) and will not do what it says.
9. **`commands.json` doesn't list every command `ops` actually supports.** `shutdown`, `startup`, and `reboot` (power the cloud VPS off/on/restart) are in the `ops` script's allowlist and dispatch table and printed in `./ops help`, but have no entry in `commands.json`. If you're deriving a command list from `commands.json` alone, you'll miss these three real commands.

## Validation

```bash
./dev doctor          # local health: containers, RCON, seed exit
./ops doctor          # production health triage
git status            # confirm nothing platform-owned was hand-edited
readlink .stack/current   # which bundle version is actually active
```

`./dev doctor` and `./ops doctor` are the loud checks — they exit non-zero on failure. A silent failure looks like: an overlay file that never applied because the seed container didn't re-run (needs a `--no-deps` recreate of the consuming service, not just a config edit), or a scaffold edit that "worked" until the next `./dev update` reverted it with no message.

## References

- [`references/overlay-contract.md`](references/overlay-contract.md) — every overlay path, its merge semantics, and worked examples (including the custom-dimensions staging exception and the modpack manifest patch schema)
- [`references/command-surface.md`](references/command-surface.md) — the full `dev` + `ops` command table, generated from `commands.json`, cross-checked against the `ops` dispatch table for which bundle script each command actually runs

For the custom-dimensions JSON schema itself (not the overlay mechanics), see the template repo's `.claude/skills/custom-dimension-authoring/SKILL.md`.
