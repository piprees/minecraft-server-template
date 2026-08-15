# AGENTS.md

> Read [`README.md`](README.md) before any task — architecture, config model, how-tos. This file is the agent contract: constraints, traps, access. On production, mistakes are permanent — the world and player progress can't be replaced.

## Mission

A fun, adapted default Minecraft server with no rough edges — stable enough to host for years, full of interesting worlds and mechanics for players and admins alike. Triage follows from it: a feature that enables greater customisability and narrative expression is kept and enhanced; one that doesn't is reshaped until it does, or de-prioritised.

## Quick reference

- `./scripts/test-scripts.sh --quick` before pushing.
- Mod changes: the dependency checklist is MANDATORY ([§ Mods](#mods)).
- Never `gh release create` — use `gh workflow run release.yml -f version=vX.Y.Z`.
- Never stream logs, pipe a filter into tailed output, or use unbounded loops — snapshot only (`--tail N`, `gh run view --json`); safety rules 10–11.
- Subagent idle/finished signals mean nothing — verify from artefacts.
- `.env` on the server is CI-generated. Change GitHub secrets, not the server file.

| Need | Go to |
| --- | --- |
| Every known trap, quirk, and open issue (T/P/D/K ids) | [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) |
| Task → file → command lookup | [`docs/common-tasks.md`](docs/common-tasks.md) |
| Architecture, services, full script catalogue, how-tos | [`README.md`](README.md) |
| Which network calls exist and how each is mitigated | [`docs/network-dependencies.md`](docs/network-dependencies.md) |
| Web page markup, styles, nav injection | [`docs/web-surfaces.md`](docs/web-surfaces.md); tokens in [`DESIGN.md`](DESIGN.md) |
| In-house mod development contract | [`mods/AGENTS.md`](mods/AGENTS.md) |
| Procedures (release, provisioning, mods, dimensions, triage) | `.claude/skills/` |

## Operating contract

Requests are tasks; tasks go on a list. Finish each before starting the next, then take the next open one; when the list empties, do what was deferred. A request arriving mid-task goes on the list — finish, then pick it up; if the user says switch, park the current task with its state in the log and come back to it. Never ask "should I continue" or "what next": check the list. Don't stop while tasks are open. **Complete** means the code is written AND run AND tested — the user could use the output right now without touching it.

**Testing:** in the same turn as the work. Read the output, check the values, fix it before reporting done. For long-running work: run one item, verify it, then start the batch, and check the first batch result before walking away.

**Logging:** one line after every finding, decision or error, before the next action: `HH:MM — <what happened>`. This is how work survives between sessions.

**Bugs:** read the error, fix that specific failure, re-run, verify, move on. Don't theorise, redesign or guess. A bug found mid-task goes on the list and is FIXED before handing back — never reported-and-deferred. A feature is done when no more bugs can be found and tests cover the happy path, the bad paths and the reasonable edge cases. **Building:** extend, fix and improve what exists; replacing something needs the user's agreement first. Anything over 50 lines goes to a subagent with a requirements checklist, and every item is verified when it returns.

**Briefing a subagent:** it starts empty and pays full price to rediscover anything you leave out. Put in the brief: the `file:line` locations you already have, every measurement you took with its units and method, the suspects you ruled out, the commands that work here (build line, how to trigger it, where the logs are), and your constraints with verified and guessed ones labelled separately. Bound it with a step budget and a required one-line report per step. Don't reverse a brief mid-run — it makes the agent redo finished work. Don't ask for a build-deploy-verify loop unless that loop is the deliverable; ask for the measurement and decide yourself.

**Stay involved.** A subagent is capable but literal, has none of your judgement about what matters, and will pursue a brief long past the point it stopped making sense. Read its output as it arrives — relaying its own account of its work back to the user unchecked makes you a repeater, not a reviewer. Check the artefacts yourself and read them in full. Correct the brief the moment it is wrong: an impossible acceptance test will not be questioned, it will be satisfied by quietly weakening the change, so if an agent retreats from what you asked, suspect your instructions first. Do the one-line changes (a constant, a flag, a rename) yourself. Sanity-check its conclusions against the code — they are often right and occasionally confidently wrong, and the difference doesn't show in how they're written. **Subagent signals mean nothing:** `idle_notification`/`available`/`finished` fire identically whether an agent is working, between turns, exited or empty-handed, and `ListAgents` has shown nothing while three agents were provably writing files. Judge only by what is written (mtimes, the diff, the deliverable) plus an answer to a direct question. Never `TaskStop` on a hunch — `SendMessage` resumes an agent with its full history, so ask before killing.

**Dependencies:** a tool, library or credential that isn't installed — ask NOW, not after working around it. **Feedback:** when corrected, act in the same turn; being asked twice means the first acknowledgement didn't result in it happening. **Context budget:** every token costs. Work until context is exhausted, produce deliverables rather than infrastructure, don't idle, don't ask permission to continue, don't pad turns with summaries of what you're about to do.

**Concise means SHORT.** Not "dense", not "thorough but well-organised" — short. A request to be concise is a request for fewer words, so the answer is measured in sentences: one or two unless told otherwise. Say the thing, stop. Do not add the reasoning, the caveat, the worked example and the summary table because each is individually defensible; that is how a two-line note becomes an essay. If it doesn't fit, cut scope rather than expanding length, and never treat "explain" as licence to lengthen.

**Commentary:** the code is the documentation. Comments are present-tense statements of what is true now; no comment beats a pointless comment, and if the purpose isn't clear from the code, refactor the code rather than narrating it. Never write a comment that narrates a change, retells an incident, dates a decision or attributes one — incidents live in `TROUBLESHOOTING.md` with an id, so cite the id, never the story. If a comment would still read correctly with every "used to", "was" and date removed, remove them. One line of why where the why is non-obvious; no paragraph, no date, no before-and-after.

## Fixed decisions (template defaults)

Consumer repos can override some via the overlay — understand the consequence first. Each is load-bearing.

- **Minecraft 1.21.1** (not newer). Most mods only target it.
- **Fabric** loader. Not Forge, NeoForge or Quilt.
- **`ONLINE_MODE=TRUE` + `ENFORCE_WHITELIST=TRUE`** in production. Both stay on.
- **Cloudflare tunnels carry HTTP only.** The game port uses a plain DNS A record — the free tier can't tunnel it and fails silently.
- **One Nether overhaul.** Incendium owns the Nether; no competing Nether worldgen mods.
- **Conventional networking.** No VPN, no Tailscale. Friends connect directly.
- **`itzg/minecraft-server` owns the mc container lifecycle.**
- **discord-sync owns all Discord slash commands** (guild-scoped). dcintegration is chat-bridge only; its command feature stays disabled.
- **`.env` on the server is CI-generated**, never the source of truth.

## Production access

Host: `DROPLET_HOST` in `.env`. Server directory: `~/server`. User: `deploy` (passwordless sudo, docker group).

```bash
./ops doctor                                            # full health triage - START HERE when anything seems wrong
./ops rcon "list"                                       # any RCON command (auto local/production)
./ops logs mc --tail 200 --grep ERROR                   # log snapshot (returns immediately)
./ops stats --once                                      # system + container + TPS snapshot
ssh -i ~/.ssh/mc_deploy_key deploy@$DROPLET_HOST '<command>'   # anything else
```

**Snapshot, never stream.** `docker logs --tail N` and `gh run view` are fine; `docker logs -f`, `live-logs.sh` and `gh run watch` in the foreground block forever. **RCON silence usually means autopause**, not an outage: the JVM freezes when the server is empty for 10 minutes and `docker ps` still shows healthy. Never add anything that touches the game port on an interval — it defeats autopause.

## The environment model (critical)

- All settings and secrets live in `.env` (git-ignored, 1Password-backed). Source of record: the GitHub `production` environment (vars + secrets, pushed by `github-env-sync.sh`) and 1Password (`Dev` vault, `Minecraft Server` item).
- **Every full CI deploy regenerates the server's `.env`** from the GitHub environment secrets. Hand-edits are wiped — change the source of truth, and say so if you hand-edit as a stop-gap.
- Adding a secret means four places: `.env.example`, 1Password (`op-sync-env.sh` + `config/1password.env`), `gh secret set X --env production`, and the reusable workflow's secrets list if the server needs it at runtime.
- Never commit secrets, world data (`data/`), or `cache/`.

## CI discipline

A push to `main` in a consumer repo triggers the caller workflow, which invokes `deploy-reusable.yml`. Tier detection is two-stage: the symbolic `STACK_VERSION` pin is resolved to a concrete release tag and compared against the bundle the server actually runs (`readlink .stack/current`) — any difference forces a full deploy, and this is what rolls platform releases out; then consumer files are diffed against the server's deployed commit, with `FULL_PATTERNS` in the reusable workflow listing the consumer paths that force a full restart (`overlay/config/`, mod lists). See the [deploy modes table](README.md#deploy-to-production) and the `deploy-pipeline-operations` skill.

**Before pushing:** `gh run list --limit 3` — if a run is in progress, **wait** (concurrent deploys race: SSH timeouts, broken healthchecks). Check who's online if the change is full-tier (`ssh ... 'docker exec -i mc rcon-cli "list"'`) — the countdown handles players, but don't restart mid-event. Batch related changes into one commit; each push is a deploy.

**After pushing:** resolve the run id **by commit sha**, never `--limit 1` (a fresh push races run creation): `gh run list --workflow deploy.yml --commit <sha> --json databaseId,status`, retrying if empty. Poll `gh run view <id> --json status,conclusion`; never `gh run watch`. Full deploys take 3–15 min, longer when the mod list changed. Snapshot boot errors with `ssh ... 'docker logs mc --tail 50'`. A failed deploy can leave containers stopped or configs half-applied — **fix it immediately** and verify with `docker exec -i mc rcon-cli "list"`. No manual changes on the server while CI runs; never run `harden.sh` or `deploy.sh` by hand during a deploy. `client_loop: send disconnect: Broken pipe` means a concurrent Docker restart delayed the healthcheck — wait for CI, verify health, re-run. An in-flight deploy executes the **pre-pull** `deploy.sh`, so a change to deploy.sh itself takes effect on the _next_ deploy.

## Cutting a release (platform repo only)

- Full procedure, compatibility promise and consumer impact: the `platform-release-management` skill.
- **Never use `gh release create`** — use `gh workflow run release.yml -f version=vX.Y.Z`. Releases are immutable, so assets can't be attached after publish and a hand-created release ships with no bundle.
- **Never push to `main` while release.yml is in progress.** The image builds (publish.yml) use concurrency group `publish-${{ github.ref }}` with `cancel-in-progress: true`, so a push cancels the release's in-flight image builds and leaves the release published without its version-tagged images (production pulls version tags via `IMAGE_TAG="${STACK_VERSION#v}"`). Recovery: `gh run rerun <release-run-id> --failed` rebuilds only the cancelled jobs.
- A published tag can't be reused, even after deleting the release. Fix the cause and cut the **next patch version** — a release without a bundle is broken; never delete and re-cut the same tag. Draft releases stay mutable, so validate every asset before publishing.

## Problems, traps, and known issues

All of it lives in [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) — architecture traps (T1–T25, no T15), macOS local-dev quirks (P1–P4), dimension lifecycle (D1–D8), open issues (K1–K2). Every entry has a permanent anchor; cite them precisely (`TROUBLESHOOTING.md#t14`). Start any diagnosis with `./ops doctor`; the forbidden-actions list is at the top of that file.

|  |  |
| --- | --- |
| [T4](TROUBLESHOOTING.md#t4) | Mods resolve in the seed, never via the API at boot. Don't reintroduce `MODRINTH_PROJECTS`. |
| [T7](TROUBLESHOOTING.md#t7) | Kuma auth is human-gated. On `authIncorrectCreds`, **stop** — do not retry. |
| [T9](TROUBLESHOOTING.md#t9) | `--no-recreate` on infra deploys is load-bearing. Only deploy.sh may recreate mc. |
| [T11](TROUBLESHOOTING.md#t11) | `.deployed` can lie: CI green while the server runs nothing. |
| [T12](TROUBLESHOOTING.md#t12) | Hand-patched volumes are reverted by every seed run — verify the rendered state, not the patch. |
| [D2](TROUBLESHOOTING.md#d2) | All worldgen config is creation-time-only. Changing it needs a world wipe. |

## Scripts

Three categories, catalogued in [README § Scripts](README.md#scripts): **bundle** (shipped in the stack tarball, run by consumers via `./ops`), **image** (baked into a GHCR image, never run directly), **template** (platform development only). Authoring rules: the `bundle-script-authoring` skill.

- **Bundle manifest trap:** a new bundle script must be added to the `MANIFEST` array in `scripts/build-stack-bundle.sh` or it never ships to consumers. `lint.yml` checks that every `.sh` referenced by `ops` or imported by another bundle script is in the manifest.
- **Consumer scaffold sync trap:** files in `examples/consumer/` reach consumers only through the three explicit loops in the `update)` case of `examples/consumer/dev` — every entry named, the three workflows listed individually (**not** a `*.yml` glob). Add each new file to that list or existing consumers never receive it. `README.md` is copied only when the consumer has none and `overlay/` is never touched — both are consumer-owned. The bundle puller is not a scaffold file: it ships in the bundle (`scripts/stack-pull.sh`), and `dev` carries only an inline bootstrap for the first-ever pull.

## Conventions

**Scripting:** `#!/usr/bin/env bash` + `set -euo pipefail`. Must run on **macOS bash 3.2** — no `declare -A`, `${var,,}`, `|&` or `mapfile`, and **no `grep -P`** (BSD grep has no PCRE; use `grep -oE` or `sed`). Idempotent — safe to run twice. Back up before overwriting (`backup()` in lib.sh → `file.bak.TIMESTAMP`). Support `--non-interactive` for CI. Every script carries a header with purpose, context, usage and gotchas — keep it current when changing behaviour; it's the authoritative reference.

**.env writing:** every value is written single-quoted with embedded `'` mapped to `’`, via `set_env_var`/`env_quote` in lib.sh (the reusable workflow's generator applies the same rule); user-pasted values arriving pre-wrapped in quotes are stripped on input. Never write a raw `KEY=$value` line — an unquoted MOTD executes itself as a command.

**Quality gates:** `./scripts/test-scripts.sh --quick` before pushing (shellcheck `--severity=warning`, `py_compile`, compose validation). lint.yml runs the same plus yamllint and blocks on failure. **Strings:** every player/Discord message lives in `config/messages.json` — never hard-code them. British English in docs and strings.

**Docker Compose:** two profiles, `local` and `cloud`, and exactly ONE env file: `.env`. Every `${VAR}` carries an inline fallback (`${VAR:-default}`) so a lean consumer `.env` never interpolates to blank — platform defaults live in the compose file, overrides in `.env`. New services need profiles, `mem_limit`, `logging: *default-logging`, and a healthcheck if others depend on it.

**Git:** conventional commits, imperative mood (`fix:`, `feat:`, `chore:`). Commit straight to main (no PRs/worktrees); park unfinished work as a WIP commit, never `git stash` (the stash stack is shared across agent sessions); after any release refresh the major tag with `git fetch origin '+refs/tags/v4:refs/tags/v4'`. The local consumer server (`~/Projects/elfydd`) is shared — check nobody is mid-test before restarting its containers.

**Versions (images, actions, tools):** never take a version number from training data. Look it up live — `gh release list --repo <owner/repo> --limit 5`, Context7 (`npx ctx7@latest docs`), or the project's releases page — for every `image:` tag in compose, every `uses:` in a workflow, and every pinned version in a script. Two traps: `--limit 1` returns the most recently _published_ release, not the highest version, so use `--limit 5 --json tagName,isLatest`; and a GitHub release tag doesn't guarantee a Docker Hub tag, so verify with `docker pull` before pinning.

## In-house mods

`mods/` holds Fabric mod projects built and maintained here — standalone Gradle projects targeting MC 1.21.1 + Java 21 (pinned via `mods/mise.toml`). Currently one: `mods/custom-dimensions/` (runtime dimension creation, custom portal frames, coordinate scaling, bidirectional travel). Mixin conventions, verification loop and architecture: [`mods/AGENTS.md`](mods/AGENTS.md).

**Delivery — never hand-copy jars into consumer repos, never publish to Modrinth.** `release.yml` builds each mod and stages the **remapped** jar from `build/libs/` (never the `-dev` jar from `build/devlibs/`) as `dist/local-mods/<mod>.jar`; `build-stack-bundle.sh` packs it into the bundle as `stack/local-mods/`; `deploy.sh` (step 8b, while mc is stopped) and `dev-up.sh` (every `./dev up`) copy it into `data/mods/`. Both `mod-build.yml` and `release.yml` verify the jar has compiled classes and the Loom-generated refmap — Gradle reports BUILD SUCCESSFUL for an unremapped or empty jar, which boots as a production crash loop. **Never hand-place or delete a jar in a consumer's `data/mods/`** — it is managed, and `dev-up.sh`/`deploy.sh` prune everything they can't account for.

**Iterate locally before releasing.** A release→deploy→sync cycle costs ~10–15 minutes and restarts production; the local loop costs ~1 minute and catches almost everything. `./dev link` a consumer (`~/Projects/elfydd`) to this checkout **once**, then: `mise exec -- ./gradlew build` → `./dev up` → exercise via RCON → soak time-based paths (a `config/` edit also needs `./dev refresh-config`). Only cut a release once that loop passes end to end. Workflow: [`.claude/skills/local-stack-testing/SKILL.md`](.claude/skills/local-stack-testing/SKILL.md) § Linked local development; mod detail: [verification loop](mods/AGENTS.md#verification-loop).

## Mods

Server list: `config/modrinth-mods.txt` (`slug:versionId`, `?` = optional, `datapack:` prefix for datapacks). Client list: `modpack/adventure.mrpack.json` `_clientMods`. All worldgen/dimension mods must be present from chunk zero. **Never guess a config key or command syntax** — fetch the mod's current docs (`npx ctx7@latest docs`, Modrinth, the mod's wiki) before editing configs or using commands.

**The dependency checklist is mandatory before adding any mod** — the Modrinth API queries that resolve a mod's dependencies are in the `server-mod-management` skill. Every required dependency must already be in the pack or be added alongside; libraries (`fabric-api`, `yungs-api`, `moonlight`, `balm`, `lithostitched`, `fabric-language-kotlin`) go in required, never optional. Verify the resolved version really targets 1.21.1 — Modrinth metadata is sometimes wrong. Then pin: `./scripts/pin-mod-versions.sh --apply`.

**Never bump a slug listed in `_holds`** (top level of `modpack/adventure.mrpack.json`, slug → reason; `pin-mod-versions.sh` reads it for the server list and the client manifest alike). Remove a hold only when its stated blocker clears, and move era-pairs together — Xaero's minimap and world map share code. Holds and resource/shader pack manifests (`_resourcePacks`/`_shaderPacks`, filename-pinned in `options.txt`): the `server-mod-management` and `consumer-customisation` skills.

## Config sync

Mod configs live in `config/<modname>/`, or flat `config/<file>` when the mod reads a bare path — verify against the jar (Tectonic reads `config/tectonic.json`). `deploy.sh` step 8 copies them into `data/config/` on every full deploy and `dev-up.sh` does it locally skip-if-exists, both **before mc starts** so no mod writes its own defaults first. Adding a mod with config means two places: the files in `config/<modname>/`, and a `COPY` line in `docker/defaults-seed/Dockerfile` so the seed volume carries the default for the consumer overlay to merge over.

**Game rules** live in two places that must match: `config/boring_default_game_rules/config.json` (new-world defaults) and the RCON enforcement block in `scripts/deploy.sh` (existing world). Each comments the other. **World spawn** is config-driven: the overworld dimension config's `spawn` field wins, and `deploy.sh` falls back to `SPAWN_X/Y/Z` in `config/.env` only when no spawn is chosen (`[0, 64, 0]` is the sentinel) — see [T31](TROUBLESHOOTING.md#t31). An in-game `/setworldspawn` doesn't stick; deploy.sh re-applies the resolved value every deploy.

## Confirm before proceeding

Allowed, but irreversible — ask a human first:

- **Cutting a release** — a burnt tag can't be reused; a broken release breaks all consumer updates.
- **`./ops reset-seed`** — deletes the world, map renders, Chunky and DH data.
- **Changing `FULL_PATTERNS`** in the reusable workflow — alters deploy behaviour for all consumers.
- **Modifying `config/custom-dimensions/`** — worldgen changes can't be undone on existing chunks.
- **Deleting or modifying `data/` on production** — the world can't be replaced.
- **`./ops teardown`** — destroys cloud resources.

## Safety rules

1. Never disable `ONLINE_MODE` or `ENFORCE_WHITELIST` on production.
2. Never tunnel the game port through Cloudflare.
3. Back up before version changes, mod changes or world migrations: `./scripts/backup-now.sh`.
4. Never overwrite a file without a `file.bak.TIMESTAMP` backup.
5. Never delete `data/` on production. That's the world, and it can't be replaced.
6. Test locally (`local` profile) before deploying.
7. `RESTIC_PASSWORD` is unrecoverable if lost — all backups die with it. It's in 1Password.
8. Never restart `mc` directly on production (`docker restart mc` skips the countdown, kick, save and whitelist dance) — use `deploy.sh`, or `/mc restart` in Discord.
9. `harden.sh` restarts Docker — run at provision time only, never during or near a CI deploy.
10. **Never use unbounded wait loops over SSH.** A loop waiting on a container, healthcheck or log line that may never arrive traps you with no way out (a crashing container never becomes healthy). Allowed: a single `sleep N` outside a loop. Forbidden: `docker logs -f`, `gh run watch`, any interactive shell, any loop whose exit condition you can't guarantee. Use `./ops` commands, `docker logs --tail N` snapshots, or `gh run view --json` polls with a finite cap.
11. **Never combine a filter with tailed output.** `tail -f … | grep X`, `docker logs -f … | grep X` — any filter over a stream that may keep running waits forever when the pattern never matches, and a pattern chosen to save tokens is exactly the one that misses. Write the output to a file and poll the file (`cmd > /tmp/x.log 2>&1 &`), or hand the watching to a subagent. Filter only what has finished and been written down, and under-filter rather than over-filter.
12. **Don't repeatedly poll CI runs.** Check once after dispatching; if it's in progress, give the user the Actions URL and stop. Smoke tests boot ~150 mods and take 5–10 minutes. One background check with a generous timeout is fine; five manual polls is not.
