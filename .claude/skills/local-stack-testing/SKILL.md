---
name: local-stack-testing
description: |
  The canonical workflow for developing an unreleased platform change against
  a local consumer repo, and the diagnosis of a local test that passes for the
  wrong reason. Covers `./dev link` (the once-per-consumer symlink farm that
  makes a platform checkout's scripts, compose files and freshly-built mod jars
  live on the next `./dev up`, and what it does not reach), the three cases it
  serves — in-house mod development, platform configs and features, seed roller
  work — and the two
  independent config-seeding paths (stack-config/stack-mods named volumes from
  the defaults-seed image, versus the data/config bind mount seeded
  skip-if-exists by dev-up.sh), plus the c2me density-function-compiler key
  stripped from c2me.toml on every boot.

  Use when: linking a consumer to a platform checkout; iterating on an
  in-house Fabric mod or the seed viewer without cutting a release; testing
  unreleased defaults-seed content; choosing between `./dev up`, `./dev
  refresh-config`, `./dev restart <service>` and a raw `docker restart`; or
  working out why a local pass didn't reflect the change under test. Consult
  before trusting a `./dev up` boot as proof a change is live, and when
  debugging "Removing config entry
  .vanillaWorldGenOptimizations.useDensityFunctionCompiler".
---

# Local Stack Testing

You are testing an unreleased change against the local Docker stack (this platform repo, or a consumer repo like `elfydd`) and need the test to actually exercise the change — not silently fall back to whatever was already there. Every trap here has the same shape: **the boot succeeded, the assertion passed, and the change under test never ran.**

## MANDATORY: the decision table

Read this before touching the stack. Find the row that matches what you changed, and run the command in that row — not `./dev up` by reflex.

| You changed | The trap | Correct local test |
| --- | --- | --- |
| An in-house mod jar (`mods/custom-dimensions/`) | An unlinked consumer runs the release bundle: `./dev up` copies `stack/local-mods/*.jar` into `data/mods/` on **every** run, so it installs the last released jar and prunes anything you put there by hand | `./dev link` the consumer once, then `mise exec -- ./gradlew build` → `./dev up` — the farm symlinks the checkout's built jar, so the current build installs (§ Linked local development, case 1) |
| Content baked into the `defaults-seed` image (`config/modrinth-mods.txt`, `config/nginx/*.template`, anything under `docker/defaults-seed/`) | The `stack-config`/`stack-mods` **named volumes** are filled by the `seed` container from its published image (`docker-compose.yml`, the `seed` service), and `./dev up` runs that container on every invocation (`dev-up.sh`, `compose_up seed`) — restoring image defaults over your hand-patch. **`./dev link` does not reach this content**: the farm gives the checkout's `config/` to the host scripts, not to the seed image | Patch the volume directly, then recreate **only the consuming service** — `./dev restart nav-proxy` / `./dev restart pack-web` (`up -d --force-recreate --no-deps`, `service.sh`, the `restart)` action, which does not run `seed`) — and verify the **rendered** file, never the patch |
| A config under `config/<modname>/` that `dev-up.sh` seeds into `data/config/` (a **bind mount**, not the volume above) | Seeding is `if [[ ! -f "$dest" ]]` — skip-if-exists. An existing `data/config/` file (e.g. from a previous version) keeps its old content forever; your new fields never land | `./dev refresh-config` (backs up `data/config` to `data/config.bak.<timestamp>`, then copies **every** platform default unconditionally and reapplies the overlay) — or delete the one target file yourself before `./dev up` |
| Anything touching per-dimension seeds or worldgen | c2me strips `useDensityFunctionCompiler` from `data/config/c2me.toml` after reading it on **every** boot | Nothing — the mod's preLaunch entrypoint re-supplies the key every boot, so bare restarts stay patched ([TROUBLESHOOTING.md#d6](../../../TROUBLESHOOTING.md#d6)); verify via **log grep**, never the config file |
| An nginx config (`config/nginx/*.conf.template`) | nav-proxy copies the template out of `stack-config` and renders it once at container start (`docker-compose.yml`, the `nav-proxy` service), so editing the checkout's source touches neither the volume nor the running container — and a link does not change this | Patch the volume (row 2), then `./dev restart nav-proxy` or `./dev restart pack-web` |
| Map render config or world data | `unmined-render` renders on its `UNMINED_INTERVAL` schedule and skips dimensions whose region files haven't changed, so nothing appears to happen immediately. With `UNMINED_INTERVAL=0` — the default (`render-loop.sh`, the `UNMINED_INTERVAL` default) — it `exec sleep infinity` and never renders at all (`render-loop.sh`, the disabled branch) | Set `UNMINED_INTERVAL` in `.env`, then `./dev restart unmined-render`: `render_all` runs at the top of the loop, before the sleep (`render-loop.sh`, the daemon loop) |
| A host-side bundle script (`.stack/<version>/stack/scripts/**`) | A symbolic `STACK_VERSION` (`v5`, `latest`) re-resolves on every `./dev`/`./ops` call, so publishing any release repoints `.stack/current` at a directory your patch never touched — silently, mid-session ([TROUBLESHOOTING.md#t30](../../../TROUBLESHOOTING.md#t30)) | `./dev link` the consumer and edit the script in the checkout — the farm symlinks `scripts/`, so the next `./dev` command runs it. Never hand-patch a versioned bundle directory |

Full worked recipes for every row: `references/testing-recipes.md`. The `stack-config`/`stack-mods` volume model — what's in them, who seeds them, who reads them, and how they differ from `data/config` — is fully explained in `references/volume-model.md`. Read that before hand-patching anything; the two "config" areas look similar and are not the same mechanism.

## Verify from artefacts, not from RCON output

Before the table below sends you to a `docker exec … rcon-cli` one-liner: **the mod's diagnostic commands write versioned JSON under `.seed-rolling/` and `data/config/custom-dimensions/`, and answer with one line plus the path.**

```bash
./dev verify        # prints where each verification lives now — it runs no checker itself
```

There is no offline checker script left: worldgen drift is a boot-time WARN (`DimensionFingerprints`), portal state is validated on load (`PortalStateValidator`), the suppress list is `./dev rcon "customdim lint"`, and the old Python checkers are JUnit tests under `mods/custom-dimensions/src/test/java/`.

RCON concatenates feedback lines with no separator, truncates at a few KB, and cannot tell a timeout from a success — so parsing its output is how a broken world produces a green run ([T17](../../../TROUBLESHOOTING.md#t17)). Use RCON to _trigger_ a dump and to run short commands; read the answer from the file.

**Check the mc boot log for a drift WARN first** (`docker logs mc | grep "worldgen config changed"`) whenever you are about to assert anything about worldgen. Worldgen is creation-time-only, so a world created before your config change still generates the OLD world and every other assertion is measuring history — this is the single most likely reason a local test disagrees with the config in front of you.

## The verification principle — read this twice

**Verify the rendered state, never the patch.** Checking that your edit is still sitting in the file you edited proves nothing about what the container is actually serving:

```bash
docker exec nav-proxy grep <expected> /etc/nginx/conf.d/default.conf
```

That's the _rendered_ nginx config (nginx's `docker-entrypoint.sh` envsubsts `/config-vol/nginx/nav-proxy.conf.template` into it once, at container start). Grepping the source template in the volume instead only tells you your patch survived — it says nothing about whether nav-proxy has picked it up (2026-07-13: a nav-proxy upstream change "passed" a check on the source file while the running container proxied to the old upstream).

The same principle applies everywhere in the table above: grep `docker logs` for the line that proves the code path ran, not the config file for the value you set.

## Linked local development — the canonical workflow

**This is the single source of truth for developing an unreleased platform
change against a local consumer repo (`~/Projects/elfydd`). Every other doc
that mentions `./dev link`, installing a mod jar, or a local edit-build-test
loop points here instead of restating it.**

`./dev link` replaces the consumer's downloaded release bundle with a farm of
symlinks over a platform checkout, so `./dev` runs the checkout's compose
files, scripts, configs and built mod jars.

### First-time setup — once per consumer

```bash
cd ~/Projects/elfydd
./dev link                       # default checkout: ../minecraft-server-template
./dev link ~/somewhere/else      # explicit path
readlink .stack/current          # answers: dev
```

`link` refuses a path that lacks either `docker-compose.yml` or `scripts/`
(`dev`, the `link)` case's checkout validation). It creates `.stack/dev/stack` and symlinks six entries into it —
`config`, `scripts`, `examples`, `.env.example`, `docker-compose.yml`,
`docker-compose.local.yml` (`dev`, the `link)` case) — writes a `VERSION` file
containing `dev` (`dev`, the `link)` case), and points `.stack/current` at `dev`
(`dev`, the `link)` case).

`local-mods/` is deleted and recreated (`dev`, the `link)` case), then every jar under
`<checkout>/mods/*/build/libs/` gets a symlink named after that jar
(`dev`, the `link)` case's `ln -sfn`). Skipped: `*-dev.jar` and `*-sources.jar` (`dev`, the `link)` case's jar loop), and any jar
whose `unzip -l` listing has no `refmap` line, which prints
`WARNING: <name> has no refmap — dev/unremapped jar skipped` (`dev`, the `link)` case's refmap check).

Because those entries are symlinks, a rebuild changes what they point at and
needs no re-link. **Re-run `./dev link` after `gradlew clean` or a
`mod_version` change:** the symlink then names a deleted file, the `ls` guard
still matches it so `dev-up.sh` enters the install block, and the `cp` fails —
under `set -euo pipefail` (`dev-up.sh` (`set -euo pipefail`)) that aborts `./dev up` with
`cp: …/local-mods/<jar>: No such file or directory`.

### What the link reaches, and what it does not

`./dev up` execs `.stack/current/stack/scripts/dev-up.sh` (`dev`, the `up)` case), whose
`STACK_DIR` resolves to `.stack/current/stack` — the farm — because both `cd`
calls are logical (`dev-up.sh`, `SCRIPT_DIR`/`STACK_DIR`). Everything below follows from that.

| Checkout path | Route into the running stack | Live after |
| --- | --- | --- |
| `docker-compose.yml`, `docker-compose.local.yml` | passed to every `docker compose` call as `$STACK_DIR/…` (`dev-up.sh`, `compose_up`) | `./dev up` |
| `scripts/**` | `./dev` dispatches into `.stack/current/stack/scripts/` (`dev`, `STACK_SCRIPTS`), and `dev-up.sh` calls its siblings through `$SCRIPT_DIR` (`dev-up.sh`, the `sync-mods.sh` call) | the next `./dev` command |
| `mods/*/build/libs/*.jar` | `cp "$LOCAL_MODS"/*.jar` into `data/mods/` (`dev-up.sh`, "Install in-house mod JARs"); `cp` follows the symlink and writes the target's bytes | `./dev up` |
| `config/datapacks/**` | `rm -rf` then `cp -r` per pack, unconditional (`dev-up.sh`, the datapack sync) | `./dev up` |
| `config/**` — mod configs, `custom-dimensions/dimensions/*.json` | copied only `if [[ ! -f "$dest" ]]` (`dev-up.sh`, the config-seed `if [[ ! -f "$dest" ]]`), so a file `data/config/` already holds is never replaced | `./dev refresh-config` |
| `config/nginx/**`, `config/modrinth-mods.txt`, anything baked into the `defaults-seed` image | **no route.** The `seed` container runs the published image (`docker-compose.yml`, the `seed` service) and fills the `stack-config`/`stack-mods` volumes that nav-proxy and mc read (`docker-compose.yml`, `nav-proxy`’s `stack-config` mount); `./dev up` runs it every time (`dev-up.sh`, `compose_up seed`) | a rebuilt image, or the volume patch in `references/testing-recipes.md` § 2 |

`./dev refresh-config` copies `data/config` to `data/config.bak.<timestamp>`
and then writes every platform config file unconditionally (`dev-up.sh`, the `refresh-config)` backup,
`dev-up.sh`, the `refresh-config)` copy loop). Its exclusion list is narrower than the `up` path's — only
`modrinth-mods.pinned.txt`, `messages.json` and `1password.env`
(`dev-up.sh`, the `refresh-config)` `find` filters) — so it also lands `nginx/`, `datapacks/` and
`uptime-kuma/` into `data/config/`, which the `up` path skips
(`dev-up.sh`, the config-seed `find` filters).

### 1. In-house mod development (`mods/custom-dimensions/`)

```bash
cd ~/Projects/minecraft-server-template/mods/custom-dimensions
mise exec -- ./gradlew build
unzip -l build/libs/customdimensions-*.jar | grep -c '\.class$'
unzip -l build/libs/customdimensions-*.jar | grep refmap

cd ~/Projects/elfydd
./dev up
ls data/mods | grep customdimensions
docker inspect mc --format '{{.State.Health.Status}}'
docker logs mc --tail 80 2>&1 | grep -iE 'mixin apply|customdimensions|error'
```

`mods/mise.toml` pins `java = "temurin-21"`; run Gradle through `mise exec --`
so that pin applies to the build.

**`data/mods/` is managed — never place or delete a jar there by hand.** Each
`./dev up` copies the farm's jars in (`dev-up.sh`, "Install in-house mod JARs"), rewrites
`data/mods/.local-mods-manifest` from their basenames (`dev-up.sh`, the `.local-mods-manifest` write), and
then deletes every `data/mods/*.jar` whose name appears in none of: this
boot's seed manifest, that `.local-mods-manifest`, or `$STACK_DIR/local-mods/`
(`dev-up.sh`, "Prune stale mod jars").

That prune is what keeps one jar per mod id across the two naming schemes.
`gradle.properties` sets `mod_version=0.0.0-local` and
`archives_base_name=customdimensions`, so a linked consumer installs
`customdimensions-0.0.0-local.jar`; a release bundle installs
`customdimensions.jar` (field 1 of `mods/local-mods.manifest`, staged by
`release.yml`, the "Build in-house mods" step). Whichever is not current is unaccounted for and gets
removed.

If the mod's persisted state format changed (config schema, namespace, ids),
delete its state files under `data/config/` before the next boot.

Jar verification and the CI gates: `.claude/skills/fabric-mod-development/SKILL.md`.
Mod internals and the headless exercise recipes: `mods/AGENTS.md`.

### 2. Server features and platform configs (`config/**`, `scripts/**`, compose)

The table above decides the command.

```bash
# Compose file or bundle script
./dev up

# A config under config/ that data/config/ already holds
./dev refresh-config
./dev up

# A service that renders its config at container start
./dev restart nav-proxy    # docker compose up -d --force-recreate --no-deps (`service.sh`, the `restart)` action)
```

`mc` is a valid target for `./dev start|stop|restart` — `service.sh` skips the
raw-lifecycle refusal when `SERVICE_LOCAL=1` (`service.sh`, `validate_targets`), which
`dev` always exports (`dev`, the `start|stop|restart|status)` case); the refusal at `service.sh`, `validate_targets` applies to
`./ops` against production. `./dev restart mc` recreates the container with
`--no-deps`, so the `seed` container does not re-run and no jar is
reinstalled; `./dev up` is what reinstalls jars and re-seeds configs.

Verify the rendered artefact, never the file you edited:

```bash
docker exec nav-proxy grep <expected> /etc/nginx/conf.d/default.conf
docker logs mc --tail 80 | grep -iE '<your marker>'
```

Worldgen fields in a dimension config are creation-time-only
([D2](../../../TROUBLESHOOTING.md#d2)) — a world created before the change
still generates the old terrain. `./dev reset-world` deletes the local world
and player data after a typed `RESET` confirmation, or `--force` to skip it
(`dev`, the `reset-world)` case).

### 3. Seed roller work

The `mc` container hosts the viewer. `docker-compose.local.yml` sets
`SEED_VIEWER_PORT: ${SEED_VIEWER_PORT:-8765}` and publishes
`${SEED_VIEWER_PORT:-8765}:8765`; the shared compose file defaults the same
variable to `0` (`docker-compose.yml`, `mc`’s `SEED_VIEWER_PORT`), so the listener is off outside the
local profile. The published mapping carries no host-IP prefix, so it binds
every interface, and the page has no authentication.

```bash
./dev up
./dev seeds
```

`./dev seeds` takes the port from `SEED_VIEWER_PORT` in `.env`, else the
environment, else 8765; it curls `http://127.0.0.1:<port>/` and exits 1 with
"It is hosted by the mc container — start the stack with ./dev up first" if
nothing answers, otherwise prints the URL and `open`s it on macOS
(`dev`, the `seeds)` case).

**The viewer's markup, CSS and JS are jar resources, so changing one is a mod
rebuild.** `ViewerPage.render` reads `/seed-viewer/template.html` and
`SeedServer.asset` reads `/seed-viewer/web/<name>`, both through
`getResourceAsStream` against the classpath, and `SeedServer.route`
routes `/assets/*` to the second. No mount shadows them: mc mounts
`./data:/data` (`docker-compose.yml`, `mc`’s `./data:/data`) and the roller's artefact directory
(`docker-compose.yml`, `mc`’s `.seed-rolling` mount), neither of which is the jar. Edit under
`mods/custom-dimensions/src/main/resources/seed-viewer/`, then run case 1.

`POST /pick` writes into the consumer's
`overlay/config/custom-dimensions/dimensions/`, mounted at
`/overlay-dimensions` by `docker-compose.local.yml`. Review it with `git diff`
in the consumer repo.

### Finishing

```bash
./dev unlink
./dev up
```

`unlink` prints "Not linked" and exits 0 unless `readlink .stack/current` is
`dev`; otherwise it points `.stack/current` at the highest-numbered
`.stack/v*/` directory, and exits 1 if there is none (`dev`, the `unlink)` case).

Four other commands move `.stack/current` off the link as a side effect.
`./dev pull` and `./dev update` both run `stack-pull.sh`, which ends in
`ln -sfn "$RESOLVED" .stack/current` (`stack-pull.sh`, the `current` symlink
write); `./dev rollback <version>` points it at the named directory (`dev`, the
`rollback)` case); and `./ops sync` runs `./dev update` as its second step
(`ops`, the inline `sync` block), so the full alignment flow drops the link too.

**`./dev clean` deletes named categories, never "everything untracked".**
`clean-dev-state.sh` owns a fixed list per target and touches nothing else, so
notes, `scratch/`, `.claude/` and `overlay/` are never collateral. `dev` passes
arguments straight through (`dev`, the `clean)` case):

```bash
./dev clean --list                # the target table
./dev clean --dry-run             # sizes and paths, deletes nothing
./dev clean                       # default: stack pack cache mods config
./dev clean seeds                 # opt-in targets ask for a typed CLEAN
./dev clean --platform ../minecraft-server-template   # + that checkout's build output
```

The default set costs a download or a boot to rebuild. `world`, `seeds` and
`backups` are opt-in because they cost human time. **A linked platform checkout
is never cleaned implicitly** — that takes `--platform`, and afterwards the
farm's jar symlinks point at deleted files, so rebuild and re-run `./dev link`
before the next `./dev up`.

`./dev clean stack` removes `.stack/` including the link farm, so re-link
afterwards rather than expecting `./dev up` to find it.

`./dev up` prints a LINKED banner for as long as `readlink .stack/current` is
`dev` (`dev`, the `up)` case's LINKED banner), and `deploy.sh` dies on the same test (`deploy.sh`, the `dev`-link guard),
so a link cannot reach production.

## The two local entry points

| Entry point | Use in | Notes |
| --- | --- | --- |
| `./dev up` / `./dev down` / `./dev restart <service>` | Consumer repos (`elfydd`-shaped checkouts) | Thin wrapper (`examples/consumer/dev`) that resolves `CONSUMER_DIR` to the consumer repo root and delegates to the bundle's `dev-up.sh` / `service.sh`. This is the only entry point most contributors need. |
| `docker compose --profile local up -d` | Platform checkout (this repo) | The command CONTRIBUTING.md and README.md both document as the direct alternative. **Prefer this over running `scripts/dev-up.sh` bare.** |

**Do not run `./scripts/dev-up.sh` directly without exporting `CONSUMER_DIR` first.** Its auto-resolution assumes the script sits at `<consumer>/.stack/vX.Y.Z/stack/scripts/dev-up.sh` and walks up four directories to reach the consumer root (`dev-up.sh`, the `CONSUMER_DIR` fallback walk). Invoked bare from this repo's plain `scripts/` directory that walk lands **three levels above the repo**, and the run then tries to `mkdir -p` a `data/mods` and source a `.env` there. If you must call it directly (e.g. to test `dev-up.sh` itself), export `CONSUMER_DIR="$(pwd)"` first, from the repo root.

`mc` is a valid `./dev start|stop|restart` target. `service.sh` skips its raw-lifecycle refusal when `SERVICE_LOCAL=1` (`service.sh`, `validate_targets`), which `dev` always exports (`dev`, the `start|stop|restart|status)` case); the `die` at `service.sh`, `validate_targets` is reached only through `./ops` against production. Locally, `stop` is `docker compose stop`, `start` is `up -d --no-deps`, and `restart` is `up -d --force-recreate --no-deps` (`service.sh`, the `stop)` action, `:143`, `:159`) — all three leave `data/mods/` and `data/config/` alone, because only `./dev up` runs the jar install and config seed.

## What the local profile changes vs production

`docker-compose.local.yml` overrides, and what they mean for what you can and can't validate locally:

- `ONLINE_MODE=FALSE`, whitelist fully disabled (`ENABLE_WHITELIST`, `ENFORCE_WHITELIST`, `OVERRIDE_WHITELIST` all `FALSE`) — you cannot test the whitelist door-lock behaviour, Mojang auth, or the Discord role-to-whitelist sync locally.
- `ENABLE_AUTOPAUSE=FALSE` — autopause behaviour (RCON going silent when empty) is untestable locally; don't mistake local RCON silence for the production autopause symptom.
- MinIO (`localhost:9001`, `minioadmin`/`minioadmin123`) stands in for Cloudflare R2 — the backup _mechanism_ is identical (restic), but nothing exercises real R2 credentials, the tunnel, or DNS.
- `uptime-kuma` and `pack-web` are bound to `localhost` only — the Cloudflare tunnel path is entirely untested locally.
- `OPS` still grants operator via RCON post-boot (offline mode can't resolve usernames to UUIDs through Mojang, so the `OPS` env var itself silently no-ops — `dev-up.sh` works around this with a post-boot RCON loop).

A green local boot proves the mod/config/worldgen path works. It proves nothing about auth, the whitelist dance, autopause, or anything behind the tunnel.

## The c2me patch — automatic

The customdimensions jar's preLaunch entrypoint (`C2meConfigPatch`) forces
`useDensityFunctionCompiler = false` into `data/config/c2me.toml` on every
boot, so a bare `docker restart mc` stays patched — no manual snippet, no
`./dev up` requirement. c2me reads its config at mixin-bootstrap time
(before any entrypoint) and strips the key after honouring it, so each
boot's write is consumed by the NEXT boot's read; `deploy.sh`/`dev-up.sh`
still pre-patch as a second layer, which covers the one gap (the first
boot in a fresh environment). Full mechanics:
[TROUBLESHOOTING.md#d6](../../../TROUBLESHOOTING.md#d6).

**Verify via log grep, never the config file**: the key's _absence_ from
`c2me.toml` after boot is expected and proves nothing either way.

```bash
docker exec mc sh -c 'grep -F "Removing config entry .vanillaWorldGenOptimizations.useDensityFunctionCompiler because it is not used" /data/logs/latest.log'
```

Finding that line confirms the patch was read before c2me wiped it. Its absence means the boot ran unpatched — every custom dimension will silently clone the main world's terrain.

## Reset to clean

- `./dev unlink` then `./dev up` restores the release bundle's in-house mod jars and configs — the way back to the shipped baseline once a local build has been tested.
- On an unlinked consumer, `./dev up` re-seeds any missing `data/config/` file and reinstalls the bundle's jars every run. That is by design, not a bug.
- `./dev down` stops and removes the stack's containers (not volumes or `data/`).
- Worldgen changes (`type`, `noiseSettings`, `biomes`, `seed` in a dimension config) are creation-time-only and survive everything short of wiping `data/world` — see the traps below and `mods/AGENTS.md` for the full level.dat scrub procedure if you need to fully remove a runtime dimension.

## Traps

The decision table's rows are the core of this skill, restated here as full symptom → cause → fix entries. The rest extend it.

1. **Hand-patched shared volumes are reverted by every seed run.** Symptom: a local test of unreleased `defaults-seed` content passes, but the change was never live — the pass came through the old config. Cause: `stack-config`/`stack-mods` are named volumes filled by the `seed` container from its published image (`docker-compose.yml`, the `seed` service), and `./dev up` runs that container every time (`dev-up.sh`, `compose_up seed`). Fix: patch the volume, then recreate only the consuming service (`./dev restart nav-proxy` → `up -d --force-recreate --no-deps`, `service.sh`, the `restart)` action), never a plain `./dev up`.
2. **An unlinked consumer runs the released mod jar, whatever you put in `data/mods/`.** Symptom: a whole feature appears broken across several boot cycles. Cause: `dev-up.sh` copies `stack/local-mods/*.jar` into `data/mods/` on every run and prunes anything unaccounted for, so a hand-copied jar is replaced or deleted. Fix: `./dev link` the consumer once, then build and `./dev up` — the farm's `local-mods/` symlinks the checkout's built jar (§ Linked local development).
3. **`dev-up.sh` skip-if-exists blocks config upgrades.** Symptom: a consumer upgrading keeps an old config file missing new fields, with no warning. Cause: `if [[ ! -f "$dest" ]]` only seeds a file that doesn't already exist. Fix: `./dev refresh-config`, or delete the specific file before `./dev up`.
4. **The c2me DFC patch is automatic, but only the log grep proves it.** The mod's preLaunch entrypoint re-supplies the key every boot ([TROUBLESHOOTING.md#d6](../../../TROUBLESHOOTING.md#d6)); the key's absence from `c2me.toml` afterwards is expected. A boot whose log lacks the `Removing config entry` line ran unpatched — the only case is a fresh environment's very first boot, which the scripts' pre-patch covers on every scripted path.
5. **Restarting `mc` is permitted locally and refused on production.** `service.sh`, `validate_targets` skips the refusal when `SERVICE_LOCAL=1`; `service.sh`, `validate_targets` fires for `./ops`. A restart is not a reinstall: `./dev restart mc` recreates the container with `--no-deps` (`service.sh`, the `restart)` action) and touches no jar, so it proves nothing about a build you have not installed with `./dev up`.
6. **On macOS Docker, bind-mount file-change events are unreliable.** Any container watching a bind-mounted path may never see a change locally. Validate pickup with an explicit `docker restart <service>` rather than waiting on a watcher.
7. **The local consumer server (`~/Projects/elfydd`) is shared.** Check nobody is mid-test before restarting its containers — a `docker restart` there affects a real, currently-in-use dev world, not a disposable one.
8. **`data/logs/latest.log` is reset on every boot** (itzg's `OnStartupTriggeringPolicy`). A crash-loop's real error lives in `docker logs mc`, not in a stale `latest.log` from a previous boot.
9. **Copying `data/config/` wholesale into a test dir brings per-dimension DistantHorizons state**, causing boot warnings about per-level configs that don't match the copied worlds. Delete `config/DistantHorizons` from the copy first.
10. **Build mods through `mise exec`.** `mods/mise.toml` pins `java = "temurin-21"`; `mise exec -- ./gradlew build` is what applies that pin, and a bare `./gradlew` builds against whatever Java is first on `PATH`.
11. **Running `scripts/dev-up.sh` bare from a platform checkout resolves `CONSUMER_DIR` outside the repo.** The path-walk assumes `<consumer>/.stack/vX.Y.Z/stack/scripts/dev-up.sh` nesting (`dev-up.sh`, the `CONSUMER_DIR` fallback walk); from this repo's plain `scripts/` directory it lands three levels above the repo root. Use `docker compose --profile local up -d` directly, or export `CONSUMER_DIR="$(pwd)"` first.

## Validation — do not skip this

Generic "did my change actually take effect" ritual, in order:

```bash
# 1. Container health and restart count (a RestartCount above 0 is a crash
#    you haven't explained yet, not evidence of self-healing)
docker inspect <service> --format '{{.State.Health.Status}} {{.RestartCount}}'

# 2. Read the RENDERED artefact, never the source you edited
docker exec <service> <read the rendered config/file>

# 3. Snapshot logs for the marker line that proves the code path ran
docker logs mc --tail 80 | grep -iE 'error|warn|<your marker>'

# 4. Prove the behaviour via RCON, not just "it booted"
docker exec -i mc rcon-cli "<a command that proves the behaviour>"
```

**Negative control principle** (from `mods/AGENTS.md`): capture a baseline **before** the change — what the log/RCON output looked like on the old code — or a "pass" is unfalsifiable. This is exactly how the c2me seed mixin, the shared-igniter portal bug, and the piston/carpet crash were all proven: a before/after pair, never a single "it worked" observation.

## References

- `references/volume-model.md` — what's baked into the `defaults-seed` image, what lands in the `stack-config`/`stack-mods` named volumes vs the `data/config` bind mount, who seeds each, who reads each, and why they revert differently.
- `references/testing-recipes.md` — full command sequences for every row in the decision table above.
