# AGENTS.md

> Read [`README.md`](README.md) before any task — architecture, config model, how-tos. This file is the agent contract: constraints, traps, access. On production, mistakes are permanent — the world and player progress can't be replaced.

## Mission

A fun, adapted default Minecraft server with no rough edges — stable enough to host for years, full of interesting worlds and mechanics for players and admins alike. Triage follows from it: a feature that enables greater customisability and narrative expression is kept and enhanced; one that doesn't is reshaped until it does, or de-prioritised.

## Quick reference

- `./scripts/test-scripts.sh --quick` before pushing.
- Mod changes: the dependency checklist is MANDATORY ([DEPENDENCIES.md § Adding a mod](DEPENDENCIES.md#adding-a-mod)).
- Never `gh release create` — use `gh workflow run release.yml -f version=vX.Y.Z`.
- Never stream logs, pipe a filter into tailed output, or use unbounded loops — snapshot only (`--tail N`, `gh run view --json`); safety rules 10–11.
- Subagent idle/finished signals mean nothing — verify from artefacts.
- `.env` on the server is CI-generated. Change GitHub secrets, not the server file.

| Need | Go to |
| --- | --- |
| Every known trap, quirk, and open issue (T/P/D/K ids) | [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) |
| Task → file → command lookup | [`docs/common-tasks.md`](docs/common-tasks.md) |
| Architecture, services, full script catalogue, how-tos | [`README.md`](README.md) |
| Dependencies: adding, updating, holding, versioning, releasing | [`DEPENDENCIES.md`](DEPENDENCIES.md) |
| Which network calls exist and how each is mitigated | [`docs/network-dependencies.md`](docs/network-dependencies.md) |
| Web page markup, styles, nav injection | [`docs/web-surfaces.md`](docs/web-surfaces.md); tokens in [`DESIGN.md`](DESIGN.md) |
| In-house mod development contract | [`mods/AGENTS.md`](mods/AGENTS.md) |
| Procedures (release, provisioning, mods, dimensions, triage) | `.claude/skills/` |
| custom-dimensions design: decisions, worldgen, schema v2, placeness, roller, portals, bridges | [`docs/design/rebuild-26/`](docs/design/rebuild-26/README.md) |

## Operating contract

Requests are tasks; tasks go on a list. Finish each before starting the next, then take the next open one; when the list empties, do what was deferred. A request arriving mid-task goes on the list — finish, then pick it up; if the user says switch, park the current task with its state in the log and come back to it. Never ask "should I continue" or "what next": check the list. Don't stop while tasks are open. **Complete** means the code is written AND run AND tested — the user could use the output right now without touching it.

**Testing:** in the same turn as the work. Read the output, check the values, fix it before reporting done. For long-running work: run one item, verify it, then start the batch, and check the first batch result before walking away.

**Logging:** one line after every finding, decision or error, before the next action: `HH:MM — <what happened>`. This is how work survives between sessions.

**Bugs:** read the error, fix that specific failure, re-run, verify, move on. Don't theorise, redesign or guess. A bug found mid-task goes on the list and is FIXED before handing back — never reported-and-deferred. A feature is done when no more bugs can be found and tests cover the happy path, the bad paths and the reasonable edge cases. **Building:** extend, fix and improve what exists; replacing something needs the user's agreement first. Anything over 50 lines goes to a subagent with a requirements checklist, and every item is verified when it returns.

**Briefing a subagent:** it starts empty and pays full price to rediscover anything you leave out. Put in the brief: the `file:line` locations you already have, every measurement you took with its units and method, the suspects you ruled out, the commands that work here (build line, how to trigger it, where the logs are), and your constraints with verified and guessed ones labelled separately. Bound it with a step budget and a required one-line report per step. Don't reverse a brief mid-run — it makes the agent redo finished work. Don't ask for a build-deploy-verify loop unless that loop is the deliverable; ask for the measurement and decide yourself.

**Stay involved.** A subagent is capable but literal, has none of your judgement about what matters, and will pursue a brief long past the point it stopped making sense. Read its output as it arrives — relaying its own account of its work back to the user unchecked makes you a repeater, not a reviewer. Check the artefacts yourself and read them in full. Correct the brief the moment it is wrong: an impossible acceptance test will not be questioned, it will be satisfied by quietly weakening the change, so if an agent retreats from what you asked, suspect your instructions first. Do the one-line changes (a constant, a flag, a rename) yourself. Sanity-check its conclusions against the code — they are often right and occasionally confidently wrong, and the difference doesn't show in how they're written. **Subagent signals mean nothing:** `idle_notification`/`available`/`finished` fire identically whether an agent is working, between turns, exited or empty-handed, and `ListAgents` has shown nothing while three agents were provably writing files. Judge only by what is written (mtimes, the diff, the deliverable) plus an answer to a direct question. Never `TaskStop` on a hunch — `SendMessage` resumes an agent with its full history, so ask before killing. **Stage explicit paths when committing while an agent runs** — `git add -A` sweeps a shared working tree, another agent's half-finished work included.

**Dependencies:** a tool, library or credential that isn't installed — ask NOW, not after working around it. **Feedback:** when corrected, act in the same turn; being asked twice means the first acknowledgement didn't result in it happening. **Context budget:** every token costs. Work until context is exhausted, produce deliverables rather than infrastructure, don't idle, don't ask permission to continue, don't pad turns with summaries of what you're about to do.

**Concise means SHORT.** Not "dense", not "thorough but well-organised" — short. A request to be concise is a request for fewer words, so the answer is measured in sentences: one or two unless told otherwise. Say the thing, stop. Do not add the reasoning, the caveat, the worked example and the summary table because each is individually defensible; that is how a two-line note becomes an essay. If it doesn't fit, cut scope rather than expanding length, and never treat "explain" as licence to lengthen.

**Commentary:** the code is the documentation. Comments are present-tense statements of what is true now; no comment beats a pointless comment, and if the purpose isn't clear from the code, refactor the code rather than narrating it. Never write a comment that narrates a change, retells an incident, dates a decision or attributes one — incidents live in `TROUBLESHOOTING.md` with an id, so cite the id, never the story. If a comment would still read correctly with every "used to", "was" and date removed, remove them. One line of why where the why is non-obvious; no paragraph, no date, no before-and-after.

**This rule governs every file in the repo, not just code.** Docs, tables, index rows, changelog-adjacent prose and commit-adjacent notes state the CURRENT state and nothing else. Git holds the history; a doc that also holds it is two sources of truth, one of which rots. Never write "fixed", "retired", "removed", "renamed", "now", "no longer", "previously", "used to" or a date into a doc to explain how it got this way — delete the entry and say what is true. When something stops existing, its row goes; do not leave a tombstone explaining the absence. The one exception is a rule that constrains future work ("never reuse a retired id"), which is an instruction, not a record.

## Fixed decisions (template defaults)

Consumer repos can override some via the overlay — understand the consequence first. Each is load-bearing.

- **Minecraft 26.3, Java 25.** `.minecraft-version` selects it; `scripts/minecraft-versions.tsv` carries its image, loader and Java.
- **Fabric** loader. Not Forge, NeoForge or Quilt.
- **`ONLINE_MODE=TRUE` + `ENFORCE_WHITELIST=TRUE`** in production. Both stay on.
- **Cloudflare tunnels carry HTTP only.** The game port uses a plain DNS A record — the free tier can't tunnel it and fails silently.
- **One worldgen owner: custom-dimensions.** Terrain, biomes and surfaces in every dimension are ours; the worldgen firewall refuses to start beside a mod that shapes them, and structure mods join through reviewed bridges in `config/custom-dimensions/bridges/`.
- **Server-side only.** An unmodded 26.3 Java client and Bedrock (Geyser and Floodgate, `BEDROCK_PORT` over TCP and UDP) join and play everything; the client pack is optional presentation.
- **Conventional networking.** No VPN, no Tailscale. Friends connect directly.
- **`itzg/minecraft-server` owns the mc container lifecycle.**
- **discord-sync owns all Discord slash commands** (guild-scoped). dcintegration is chat-bridge only; its command feature stays disabled.
- **`.env` on the server is CI-generated**, never the source of truth.
- **Every dimension is managed the same way** — [§ Dimensions](#dimensions).

## Dimensions

`overworld`, `the_nether`, `the_end` and `the_aviary` are the base worlds. Here
they are four dimensions among 82, and every field of a definition applies to
them as it does to the rest: `seed`, `spawn`, `generator`, `terrain`, `biomes`,
`biomeLayout`, `liquids`, `materials`, `carvers`, `ores`, `zones`,
`dimensionType`, `atmosphere`, `rules`, `gameRules`, `borders`, `difficulty`,
`structures`, `portals`, `exitPortal`, `exits`, `exitShrines`, `deaths`, `wins`,
`roll`. They are rolled, surveyed and
retuned like any other.

Two operational differences: they load at start from the pack and are never
unloaded, and they are the worlds the map shows. Leaving one out of a change
needs a reason specific to that dimension — a progression gate, a forced
coordinate, a border invariant.

`custom-dimensions` owns every generator in the pack. Each definition in
`config/custom-dimensions/dimensions/<slug>.json` (schema v2,
`mods/custom-dimensions-26/schema/definition.schema.json`) compiles at every
start-up into the world's datapack: vanilla's noise generator with noise
settings, terrain from our library, biomes from vanilla 26.3 and our biome
library, zones, structure control and the dimension type. Everything in a
generator is changeable here; the 1.21.1 conversion's record (each 1.21.1
definition and the id mapping) is in `mods/custom-dimensions-26/definitions/`.

## Production access

Host: `DROPLET_HOST` in `.env`. Server directory: `~/server`. User: `deploy` (passwordless sudo, docker group).

```bash
./ops doctor                                            # full health triage - START HERE when anything seems wrong
./ops rcon "list"                                       # any RCON command (auto local/production)
./ops logs mc --tail 200 --grep ERROR                   # log snapshot (returns immediately)
./ops stats --once                                      # system + container + TPS snapshot
./ops ssh '<command>'                                       # anything else
```

`./ops ssh` resolves the deploy key from `BRAND_SLUG` automatically
(`~/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key`). If SSH fails with an
agent-related error, the key resolution in `lib.sh` already passes
`-o IdentitiesOnly=yes`.

**Snapshot, never stream.** `docker logs --tail N` and `gh run view` are fine; `docker logs -f`, `live-logs.sh` and `gh run watch` in the foreground block forever. **RCON silence usually means autopause**, not an outage: the JVM freezes when the server is empty for 10 minutes and `docker ps` still shows healthy. Never add anything that touches the game port on an interval — it defeats autopause.

## The environment model (critical)

- All settings and secrets live in `.env` (git-ignored, 1Password-backed). Source of record: the GitHub `production` environment (vars + secrets, pushed by `github-env-sync.sh`) and 1Password (`Dev` vault, `Minecraft Server` item).
- **Every full CI deploy regenerates the server's `.env`** from the GitHub environment secrets. Hand-edits are wiped — change the source of truth, and say so if you hand-edit as a stop-gap.
- Adding a secret means four places: `.env.example`, 1Password (`op-sync-env.sh` + `config/1password.env`), `gh secret set X --env production`, and the reusable workflow's secrets list if the server needs it at runtime.
- Never commit secrets, world data (`data/`), or `cache/`.

## CI discipline

A push to `main` in a consumer repo triggers the caller workflow, which invokes `deploy-reusable.yml`. Tier detection is two-stage: the stack pin (the consumer's committed `.stack-version`) is resolved to a concrete release tag and compared against the bundle the server actually runs (`readlink .stack/current`) — any difference forces a full deploy, and this is what rolls platform releases out; then consumer files are diffed against the server's deployed commit, with `FULL_PATTERNS` in the reusable workflow listing the consumer paths that force a full restart (`overlay/config/`, mod lists). See the [deploy modes table](README.md#deploy-to-production) and the `deploy-pipeline-operations` skill.

**Before pushing:** `gh run list --limit 3` — if a run is in progress, **wait** (concurrent deploys race: SSH timeouts, broken healthchecks). Check who's online if the change is full-tier (`ssh ... 'docker exec -i mc rcon-cli "list"'`) — the countdown handles players, but don't restart mid-event. Batch related changes into one commit; each push is a deploy.

**After pushing:** resolve the run id **by commit sha**, never `--limit 1` (a fresh push races run creation): `gh run list --workflow deploy.yml --commit <sha> --json databaseId,status`, retrying if empty. Poll `gh run view <id> --json status,conclusion`; never `gh run watch`. Full deploys take 3–15 min, longer when the mod list changed. Snapshot boot errors with `ssh ... 'docker logs mc --tail 50'`. A failed deploy can leave containers stopped or configs half-applied — **fix it immediately** and verify with `docker exec -i mc rcon-cli "list"`. No manual changes on the server while CI runs; never run `harden.sh` or `deploy.sh` by hand during a deploy. `client_loop: send disconnect: Broken pipe` means a concurrent Docker restart delayed the healthcheck — wait for CI, verify health, re-run. An in-flight deploy executes the **pre-pull** `deploy.sh`, so a change to deploy.sh itself takes effect on the _next_ deploy.

## Cutting a release (platform repo only)

- Full procedure, compatibility promise and consumer impact: the `platform-release-management` skill.
- **Never use `gh release create`** — use `gh workflow run release.yml -f version=vX.Y.Z`. Releases are immutable, so assets can't be attached after publish and a hand-created release ships with no bundle.
- **Avoid pushing to `main` while release.yml is in progress.** A versioned image build has its own concurrency group and `cancel-in-progress: false` (`publish.yml`), so a push cannot cancel it, but the `publish` job commits `CHANGELOG.md` back to `main` with three rebase attempts, and a push races that. The release stays a draft until its version-tagged images exist, so a failed `images` job leaves an unpublished draft: `gh run rerun <release-run-id> --failed` finishes it. Production pulls version tags via `IMAGE_TAG="${STACK_VERSION#v}"`.
- A published tag can't be reused, even after deleting the release. Fix the cause and cut the **next patch version** — a release without a bundle is broken; never delete and re-cut the same tag. Draft releases stay mutable, so validate every asset before publishing.

## Problems, traps, and known issues

All of it lives in [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) — architecture traps (T1–T293), macOS local-dev quirks (P1–P6), dimension lifecycle (D1–D9), open issues (K1–K17). Every entry has a permanent anchor; cite them precisely (`TROUBLESHOOTING.md#t14`). Start any diagnosis with `./ops doctor`; the forbidden-actions list is at the top of that file.

**A retired id is never reused.** Retired: T15, T20, T21, T22, T23, T26, T28, T29, T81, T86, T89, T93, T94, T96; D1, D4, D5, D7; K3, K4, K8, K10, K11, K13, K14, K15, K16. A new entry takes the next number above the prefix's highest, never a gap.

|  |  |
| --- | --- |
| [T4](TROUBLESHOOTING.md#t4) | Mods resolve in the seed, never via the API at boot. Don't reintroduce `MODRINTH_PROJECTS`. |
| [T7](TROUBLESHOOTING.md#t7) | Kuma auth is human-gated. On `authIncorrectCreds`, **stop** — do not retry. |
| [T9](TROUBLESHOOTING.md#t9) | `--no-recreate` on infra deploys is load-bearing. Only deploy.sh may recreate mc. |
| [T11](TROUBLESHOOTING.md#t11) | `.deployed` can lie: CI green while the server runs nothing. |
| [T12](TROUBLESHOOTING.md#t12) | Hand-patched volumes are reverted by every seed run — verify the rendered state, not the patch. |
| [T49](TROUBLESHOOTING.md#t49) | Jar scans resolve `c:*` tags wrongly. `registries.json` (via `/customdim catalogue`) is ground truth. |
| [T50](TROUBLESHOOTING.md#t50) | `run say` returns nothing over RCON; unloaded positions cannot be probed; biomes are 3D. |
| [T53](TROUBLESHOOTING.md#t53) | The biome bypass belongs to a site's ASSIGNED structure only. Given to a re-draw candidate, one want becomes a universal filler. |
| [T54](TROUBLESHOOTING.md#t54) | Jigsaw `size`/`max_distance_from_center` are not a footprint. `structure-sizes.json` is measured; re-measure, never proxy. |
| [T77](TROUBLESHOOTING.md#t77) | A linked local stack runs the seed IMAGE's baked mod list, not the repo's. Diff resolved filenames against `data/mods` before any roll. |
| [T78](TROUBLESHOOTING.md#t78) | `data/config/` is seeded skip-if-exists. A config change needs `./dev refresh-config`; `./dev up` and `./dev update` boot green on the OLD file. |
| [D2](TROUBLESHOOTING.md#d2) | All worldgen config is creation-time-only. Changing it needs a world wipe. |
| [K6](TROUBLESHOOTING.md#k6) | Concurrent first-time chunk generation wedges the main thread. Activation is one dimension at a time; never make it concurrent again. |

## Scripts

Three categories, catalogued in [README § Scripts](README.md#scripts): **bundle** (shipped in the stack tarball, run by consumers via `./ops`), **image** (baked into a GHCR image, never run directly), **template** (platform development only). Authoring rules: the `bundle-script-authoring` skill.

- **End-to-end harness:** `scripts/e2e/` drives a linked local consumer and the real client through ignition, traversal, idle unload and companion suppression — the behaviour no unit test reaches ([`scripts/e2e/README.md`](scripts/e2e/README.md)). Template-only; referenced by neither `ops` nor `dev`, so it stays out of `MANIFEST`. **Build the rig before measuring anything** — the portal it reads through has gone missing twice, and an absent rig reads as a null result rather than a broken instrument: [`scripts/e2e/build-the-rig.md`](scripts/e2e/build-the-rig.md).
- **Bundle manifest trap:** a new bundle script must be added to the `MANIFEST` array in `scripts/build-stack-bundle.sh` or it never ships to consumers. `lint.yml` checks that every `.sh` referenced by `ops` or imported by another bundle script is in the manifest.
- **Consumer scaffold sync trap:** files in `examples/consumer/` reach consumers only through the three explicit loops in the `update)` case of `examples/consumer/dev` — every entry named, the three workflows listed individually (**not** a `*.yml` glob). Add each new file to that list or existing consumers never receive it. `README.md` is copied only when the consumer has none and `overlay/` is never touched — both are consumer-owned. The bundle puller is not a scaffold file: it ships in the bundle (`scripts/stack-pull.sh`), and `dev` carries only an inline bootstrap for the first-ever pull.

## Conventions

**Scripting:** `#!/usr/bin/env bash` + `set -euo pipefail`. Must run on **macOS bash 3.2** — no `declare -A`, `${var,,}`, `|&` or `mapfile`, and **no `grep -P`** (BSD grep has no PCRE; use `grep -oE` or `sed`). Idempotent — safe to run twice. Back up before overwriting (`backup()` in lib.sh → `file.bak.TIMESTAMP`). Support `--non-interactive` for CI. Every script carries a header with purpose, context, usage and gotchas — keep it current when changing behaviour; it's the authoritative reference.

**.env writing:** every value is written single-quoted with embedded `'` mapped to `’`, via `set_env_var`/`env_quote` in lib.sh (the reusable workflow's generator applies the same rule); user-pasted values arriving pre-wrapped in quotes are stripped on input. Never write a raw `KEY=$value` line — an unquoted MOTD executes itself as a command.

**Quality gates:** `./scripts/test-scripts.sh --quick` before pushing (shellcheck `--severity=warning`, `py_compile`, compose validation). lint.yml runs the same plus yamllint and blocks on failure. **Strings:** every player/Discord message lives in `config/messages.json` — never hard-code them. British English in docs and strings.

**Docker Compose:** two profiles, `local` and `cloud`, and exactly ONE env file: `.env`. Every `${VAR}` carries an inline fallback (`${VAR:-default}`) so a lean consumer `.env` never interpolates to blank — platform defaults live in the compose file, overrides in `.env`. New services need profiles, `mem_limit`, `logging: *default-logging`, and a healthcheck if others depend on it.

**Git:** conventional commits, imperative mood (`fix:`, `feat:`, `chore:`). Commit straight to main (no PRs/worktrees); park unfinished work as a WIP commit, never `git stash` (the stash stack is shared across agent sessions); after any release refresh the major tag with `git fetch origin '+refs/tags/v4:refs/tags/v4'`. The local consumer server (`~/Projects/elfydd`) is shared — check nobody is mid-test before restarting its containers.

**Versions (images, actions, tools):** never take a version number from training data — look it up live for every `image:` tag, `uses:` ref and pinned version ([DEPENDENCIES.md § Looking up versions](DEPENDENCIES.md#looking-up-versions)).

## In-house mods

`mods/` holds Fabric mod projects built and maintained here — standalone Gradle projects targeting Minecraft 26.3 and Java 25, each pinned by its own `mise.toml`, built with Loom's non-remapping plugin (no refmap). In the bundle: `mods/custom-dimensions-26/` (the worldgen compiler, lazy levels, portals, difficulty, exits, deaths and wins, survey, render and roll; `mods/local-mods.manifest`) and the optional companion `mods/custom-dimensions-client-26/` (live portal previews; `mods/client-mods.manifest`). Mixin conventions, verification loop and architecture: [`mods/AGENTS.md`](mods/AGENTS.md).

**Delivery — never hand-copy jars into consumer repos, never publish to Modrinth.** `release.yml` builds each manifest row through `scripts/local-mods.sh` and stages the jar as `dist/local-mods/<jar>`; `build-stack-bundle.sh` packs it into the bundle as `stack/local-mods/`; `deploy.sh` (step 8b, while mc is stopped) and `dev-up.sh` (every `./dev up`) copy it into `data/mods/`. `mod26.yml` and `release.yml` verify the jar has compiled classes and is a server mod — Gradle reports BUILD SUCCESSFUL for an empty jar, which boots as a production crash loop. **Never hand-place or delete a jar in a consumer's `data/mods/`** — it is managed, and `dev-up.sh`/`deploy.sh` prune everything they can't account for.

**Iterate locally before releasing.** A release→deploy→sync cycle costs ~25–35 minutes and restarts production; the local loop costs minutes and catches almost everything. From `mods/custom-dimensions-26/`: `./gradlew build`, then `./gradlew :server:runServer -PselfTest=/tmp/st.txt` (the in-game self-test; `-PselfTestOnly=<section>` for one section). For the whole stack, `./dev link` a consumer (`~/Projects/elfydd`) to this checkout **once**, then `./dev up` → exercise via RCON → soak time-based paths (a `config/` edit also needs `./dev refresh-config`). **`./dev up` runs a `--profile local down` before it starts, so the new jar is served on the container it creates. `./dev restart mc` installs nothing — verify the served artefact, not the jar on disk.** Workflow: [`.claude/skills/local-stack-testing/SKILL.md`](.claude/skills/local-stack-testing/SKILL.md) § Linked local development; mod detail: [verification loop](mods/AGENTS.md#verification-loop).

## Mods

Rules with their reasons, the semver contract and every playbook: [`DEPENDENCIES.md`](DEPENDENCIES.md). The pack's inclusion rule and every project's decision: [`docs/design/rebuild-26/pack.md`](docs/design/rebuild-26/pack.md) and `mod-register.csv`.

- **The dependency checklist is mandatory before adding any mod** — [§ Adding a mod](DEPENDENCIES.md#adding-a-mod). Libraries go in required, never optional.
- **A mod joins only if an unmodded 26.3 client can still join with it** (the smoke test's vanilla join gate), unless it is an optional client mod that is purely effects, shaders or UI.
- **No mod shapes terrain, biomes or surfaces.** The worldgen firewall refuses to start beside one; a structure mod joins through a reviewed bridge, and its structures ship only in a major ([§ Versioning contract](DEPENDENCIES.md#versioning-contract)).
- **Never guess a config key or command syntax** — fetch the mod's current docs (`npx ctx7@latest docs`, Modrinth, the mod's wiki) or read the jar before editing configs or using commands.
- **Never bump a slug listed in `_holds` by hand** — [§ Holds](DEPENDENCIES.md#holds).
- **Never merge the `mod-updates/next-major` PR** except when cutting a major.

## Config sync

Mod configs live in `config/<modname>/`, or flat `config/<file>` when the mod reads a bare path — verify against the jar (Geyser reads `config/Geyser-Fabric/config.yml`). Every Serilum mod reads `config/serilum/<modid>.json`: Collective moves any other copy there on each boot ([T136](TROUBLESHOOTING.md#t136)). `deploy.sh` step 8 copies them into `data/config/` on every full deploy and `dev-up.sh` does it locally skip-if-exists, both **before mc starts** so no mod writes its own defaults first. Adding a mod with config means two places: the files in `config/<modname>/`, and a `COPY` line in `docker/defaults-seed/Dockerfile` so the seed volume carries the default for the consumer overlay to merge over.

**Game rules** have two layers. The server-wide store is set in one place: `mc_game_rules` in `scripts/lib.sh`, which `deploy.sh` (`restore_game_rules`) and `dev-up.sh` apply over RCON, each rule's 26.3 id through `mc_game_rule` and `scripts/game-rules.tsv`. A dimension holds its own values over that store: its definition's `gameRules`, else `settings.json`'s `defaults.gameRules` (decisions.md RB-38; `advance_time` is refused, `atmosphere.fixedTime` and `dayLength` set a dimension's time).

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
12. **Don't repeatedly poll CI runs.** Check once after dispatching; if it's in progress, give the user the Actions URL and stop. A release run's time is mostly the smoke test's boot and vanilla join. One background check with a generous timeout is fine; five manual polls is not.
