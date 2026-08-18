---
name: fabric-mod-development
description: Builds, verifies, and ships an in-house Fabric mod under mods/<name> (currently mods/custom-dimensions) — the mise-pinned Gradle build, the pre-build compile of generated resources (the seed viewer's Tailwind stylesheet, via build-viewer-css.sh), the artefact-verification gate that catches an empty or unremapped jar, the fast local iteration loop against a running mc container, the Carpet fake-player harness for player-dependent paths, soak testing timers, and the release path into the stack bundle. Use when building or changing a mod under mods/, editing the seed viewer's CSS, verifying a build before restarting a container with it, iterating locally without corrupting the test with a stale bundle jar, writing a headless RCON test for a portal/zone/timer path, or diagnosing a crash-looping server after a mod change. Also use when troubleshooting "could not find any targets ... No refMap loaded", a ClassCastException from an unlisted accessor mixin, a ConcurrentModificationException from a world-tick mixin, or a green build that shipped the previous stylesheet. Not for authoring dimension JSON (see custom-dimension-authoring) or cutting a release (see AGENTS.md § Cutting a release).
---

# Fabric Mod Development

Building, verifying, and shipping the in-house Fabric mods in `mods/` (currently `mods/custom-dimensions` — target MC 1.21.1, Fabric Loader 0.16+, Java 21). This skill owns **procedure**: does the jar you built actually work, how do you iterate on it without lying to yourself, and how do you prove a player-dependent path is safe before you restart production. It does not own the mod's internal architecture (portal system, immersive portals, auras) — that lives in `mods/AGENTS.md` and changes often enough that duplicating it here would drift. Read it there; this skill tells you what to _do_ with it.

**Out of scope:** authoring or editing a dimension's JSON config (`custom-dimension-authoring` skill, `.claude/skills/custom-dimension-authoring/SKILL.md`); dimension lifecycle on a live world — level.dat scrubs, orphan reconciliation (root `AGENTS.md` § Dimension lifecycle traps); cutting a platform release (root `AGENTS.md` § Cutting a release, `platform-release-management` skill).

## MANDATORY reading before you touch a mod

| File | Why you need it |
| --- | --- |
| `.claude/skills/local-stack-testing/SKILL.md` § Linked local development | The canonical `./dev link` workflow every local loop in this skill depends on |
| `mods/AGENTS.md` § Verification loop | The full 5-stage loop this skill summarises, with every gotcha in place |
| `mods/AGENTS.md` § Architecture (custom-dimensions) | The component tree — reference it, never reproduce it here |
| `mods/AGENTS.md` § Worldgen self-containment rules, § Structure placement lessons | Noise-id vs DF-id seeding rules, CubicSpline extrapolation, `/locate` ordering — only relevant if you touch worldgen |
| Root `AGENTS.md` § In-house mods, § Mods, `:162` | The delivery pipeline and the `mise exec` requirement |
| `mods/mise.toml` | Pinned toolchain (`java = "temurin-21"`) |
| `.github/workflows/mod-build.yml`, `release.yml` | What CI actually enforces on the jar (mirror these locally, don't guess) |
| `.github/workflows/smoke-test.yml` | The full CI harness this skill's local loop is a fast substitute for |
| `scripts/patch-mod-data.py`, `docs/known-issues/carpet-supplementaries-piston-crash.md` | Why the carpet jar you're testing against is already patched, and why it must stay that way |
| `mods/custom-dimensions/README.md` | Commands, config schema, build/test/install for the shipping mod |

## 1. Build

```bash
cd mods/<name>
mise exec -- ./gradlew build
```

**Never bare `./gradlew build`.** `mods/mise.toml` pins `java = "temurin-21"`, and `mise exec --` is what applies that pin to the build ([P4](../../../TROUBLESHOOTING.md#p4)). First time in a project without a wrapper: `mise install && gradle wrapper --gradle-version 8.13`.

**Generated resources are not Gradle's job — compile them first.** `custom-dimensions` ships one: the seed viewer's stylesheet. `src/main/resources/seed-viewer/web/app.css` is Tailwind v4 source and the jar carries `app.built.css`, so a CSS edit needs `./build-viewer-css.sh` (mod root) before the build, with both files committed. Gradle deliberately does not run it — that is what keeps `mod-build.yml` and `release.yml` offline — so an unbuilt edit produces a green build shipping the old stylesheet. Same failure shape as the dev-jar trap below: `BUILD SUCCESSFUL` over a wrong artefact.

## 2. Verify the artefact, not the build — mandatory gate

**Gradle reports `BUILD SUCCESSFUL` even when `remapJar` emits an empty or unremapped jar.** This shipped a production crash loop once. Run these on `build/libs/<mod>-<version>.jar` before you do anything else with it:

```bash
unzip -l build/libs/<mod>-<version>.jar | grep -c '\.class$'      # non-zero (CI's floor is 10)
unzip -l build/libs/<mod>-<version>.jar | grep refmap             # <mod>-refmap.json MUST be present
unzip -p build/libs/<mod>-<version>.jar path/to/SomeMixin.class | strings | grep -m3 'class_'
# intermediary names (class_XXXX) = remapped correctly; Yarn names only = dev jar, will crash
```

**Dev-jar trap:** `build/devlibs/<mod>-<version>-dev.jar` uses Yarn-named mappings and carries no refmap — it only works inside a Loom dev environment. On a real server every mixin fails with `could not find any targets ... No refMap loaded` and the server crash-loops. **Only ever ship `build/libs/<mod>-<version>.jar`.**

CI runs the identical check twice, so a local pass should never surprise it: `mod-build.yml` on every push/PR touching `mods/**` (floor: 10 classes, refmap must be present in the jar named by `mods/local-mods.manifest`'s third field), and `release.yml` again per mod listed in that manifest before it stages `dist/local-mods/<jar_name>` into the bundle. See `references/jar-verification.md` for the full manifest format and CI internals.

`mod-build.yml` also runs `mods/*/build-viewer-css.sh --check` before the Gradle build, which is what catches a committed stylesheet that no longer matches its source.

## 3. Fast local loop

Link a consumer repo to this checkout once, then build and `./dev up`. A release → deploy cycle costs ~50–60 minutes and restarts production; this costs about a minute.

**The canonical workflow — first-time setup, what the link reaches, and the three development cases — is `.claude/skills/local-stack-testing/SKILL.md` § Linked local development.** Read it there; what follows is the mod-shaped summary.

```bash
cd ~/Projects/elfydd && ./dev link        # once per consumer; readlink .stack/current -> dev

cd ~/Projects/minecraft-server-template/mods/<name>
mise exec -- ./gradlew build              # step 2's gate runs on the jar this produces

cd ~/Projects/elfydd
./dev up
ls data/mods | grep <modid>                                       # exactly one line
docker inspect mc --format '{{.State.Health.Status}}'             # must be healthy
docker logs mc --tail 80 2>&1 | grep -iE 'mixin apply|<modid>|error'
```

`./dev link` builds a farm of symlinks at `.stack/dev/stack`, one `local-mods/<jar>` per built jar (`dev`, the `link)` case's `ln -sfn`), so a rebuild changes what the link points at and needs no re-link. `dev-up.sh`, "Install in-house mod JARs" copies those into `data/mods/`, and `cp` follows the symlink, so the current build installs. **Re-run `./dev link` after `gradlew clean` or a `mod_version` change** — the symlink then names a deleted file and that `cp` aborts `./dev up`.

**Never place or delete a jar in `data/mods/` by hand.** It is managed: each `./dev up` installs the farm's jars, rewrites `data/mods/.local-mods-manifest` from their basenames (`dev-up.sh`, the `.local-mods-manifest` write), then deletes every `data/mods/*.jar` named in neither this boot's seed manifest, that file, nor `$STACK_DIR/local-mods/` (`dev-up.sh`, "Prune stale mod jars"). A hand-placed jar matches none of the three.

`./dev pull`, `./dev update` and `./dev rollback` all repoint `.stack/current` at a release bundle and undo the link; `./dev unlink` is the deliberate way back to the shipped jars.

If the persisted state format changed (config schema, namespace, ids), delete the mod's state file(s) under `data/config/` before restarting — stale state from a previous build masks bugs and creates ghosts.

**c2me DFC is self-patching.** The mod's preLaunch entrypoint (`C2meConfigPatch`) forces `useDensityFunctionCompiler = false` into `data/config/c2me.toml` on every boot, so a bare `docker restart mc` stays patched — no manual re-patch in the loop ([TROUBLESHOOTING.md#d6](../../../TROUBLESHOOTING.md#d6) has the mixin-bootstrap timing and the one first-boot gap the scripts still cover).

**Verify via log grep, never by inspecting the config file afterwards** — the key's absence from `c2me.toml` post-boot is expected (c2me strips it after reading it): `docker exec mc grep "Removing config entry .vanillaWorldGenOptimizations.useDensityFunctionCompiler" /data/logs/latest.log`.

## 4. Verify via artefacts, not RCON output

**Start here, not with RCON.** The mod's diagnostic commands write JSON under `.seed-rolling/` (a directory sibling to `data/`, outside the reach of `deploy.sh`'s config sync and `./dev refresh-config`) and answer with one line plus a path; a few compare two measurements and report a capped pass/fail summary inline instead. RCON concatenates feedback lines with no separator, truncates at a few KB, and cannot distinguish a timeout from a success — so parsing its output is how you get a green run over a broken world. There is no offline checker script left to run — the Python checkers were ported to JUnit tests under `mods/custom-dimensions/src/test/java/`. Full contract: `mods/AGENTS.md` § Diagnostic artefacts.

```bash
./dev verify                 # points at where fingerprint/portal/suppress verification lives now
```

Check the mc boot log for a drift WARN FIRST if you are about to assert anything about worldgen (`docker logs mc | grep "worldgen config changed"`). Worldgen is creation-time-only, so a world created before your config change still generates the OLD world, and every other assertion is then measuring history. A stale world is the most likely reason a worldgen assertion disagrees with config — a "failed" structure-filter check is often just an old world, not a wrong filter.

| Question | Instrument | Verified by |
| --- | --- | --- |
| Does this world still match its config? | `data/config/custom-dimensions-fingerprints.json` | the mod, at boot (`DimensionFingerprints`) |
| Which structures reached each group, and where? | `/customdim structure-census <dim>` (needs a LOADED dimension) | live world vs headless `FactsEngine`, compared inline — no file, no separate checker |
| Is persisted portal state sane? | `data/config/portal_links.json` | the mod, on load (`PortalStateValidator`) |
| How was each set classified? | `/customdim structure-audit [group]` | writes `.seed-rolling/lint/<hash>.structure-audit.json`, human-read |

`/locate` is NOT an occupancy instrument — a miss walks placements for minutes and wedges RCON. Use `/customdim occupant <dim> <cx> <cz>` to read a loaded chunk's live `StructureStart`s (never generates, appends `<world save root>/customdimensions/census/occupancy__<ns>__<slug>.json` — this world's own save, not `.seed-rolling`, since it's a fact about chunks that actually generated) and `/customdim carver-draw <dim> <cx> <cz>` to replay vanilla's would-be first draw beside the noise assignment. Locating a vanilla village in the **stock overworld** times out at 120 s, and Chunky pre-generation does not help.

## 5. Exercise via RCON, headless

Dimensions are created automatically at boot from `config/custom-dimensions/` — there is no `/dimension create` or `/portal link` command. Resolve the namespace first (it isn't always `adventure`):

```bash
NS=$(python3 -c "
import json
print(json.load(open('data/config/custom-dimensions/settings.json')).get('namespace', 'adventure'))
")
docker exec -i mc rcon-cli "execute in ${NS}:the_blossom_gardens run seed"   # proves the dimension was created from config
docker exec mc cat /data/logs/latest.log | grep -i "registered dimension\|Created runtime" | tail -10
```

## 6. Player-dependent paths: drive a Carpet fake player

Paths that only trigger on real player presence (portal traversal, zone entry, presence timers) can be tested headlessly by puppeting a bot. **Carpet ships as a platform default** (`config/modrinth-mods.txt`) and is already loaded — there is nothing to install and no extra restart needed. Its known crash next to Supplementaries is pre-patched by `scripts/patch-mod-data.py` on every deploy and every `./dev up`; don't re-investigate that crash, see `docs/known-issues/carpet-supplementaries-piston-crash.md` if you ever bump the carpet pin.

```bash
docker exec -i mc rcon-cli 'carpet commandPlayer true'
docker exec -i mc rcon-cli 'player Bot spawn'          # async — wait ~3s, verify with "list"
```

Full traversal recipes, every assertion pattern, and ~20 gotchas (aiming, igniter sharing, zone-injection shortcuts, aura/shape/gateway recipes) are in `references/carpet-bot-harness.md`. The load-bearing ones, distilled:

- Bot stands **inside** the frame and `look down` at the bottom frame block to ignite — near-horizontal aims at eye-level frame blocks miss inconsistently.
- `player Bot attack once` does not break a multi-click block (including portal blocks) — use `attack continuous` then `attack stop`.
- RCON `fill`/`setblock` into an unloaded dimension silently does nothing, answering `Unknown dimension` — teleport a player there first to load it.
- Assert with `data get entity` and log greps, never RCON chat echoes.
- `touch /data/.skip-pause` or autopause kicks the bot mid-test.
- Source portal interiors have **no** portal blocks — assert traversal (`Dimension`/`Pos`), never probe the source interior for portal state.
- Profile an arrival column **before** traversing — probing afterwards measures the portal you just built, not the ground it was built on.

## 7. Soak time-based paths

Anything on a timer (idle unload, cooldowns, periodic saves) must be soaked through its **real** window, not assumed from reading the code:

```bash
docker inspect mc --format 'Health={{.State.Health.Status}} Restarts={{.RestartCount}}'   # Restarts must be 0
docker exec mc cat /data/logs/latest.log | grep -iE 'Unloading idle|ConcurrentModification'  # feature line present, no CME
```

See `references/runtime-invariants.md` for why a world-tick mixin that mutates the worlds map, adds/removes a `ServerWorld` without firing `ServerWorldEvents`, or sync-loads a chunk from a tick will eventually produce exactly this failure — and how to avoid writing one.

## 8. Ship and verify at each layer

Once the local loop passes: commit → `gh workflow run release.yml -f version=vX.Y.Z` → consumer `./dev update` (or `./ops update` for production). Then verify **outcomes, not script output**:

- Script counters count commands _sent_, not commands that _succeeded_ — a brigadier parse error still increments a "Created: N" counter. Check the persisted result instead (e.g. count entries in the config) and spot-check entities via RCON.
- Snapshot production state, never stream it: `docker logs mc --tail 50`, `docker inspect mc --format '... RestartCount ...'`. A `RestartCount` above 0 means a crash you haven't explained yet.
- Under deploy load (world creation, Chunky, mod sync) RCON responses can time out and come back empty — treat an empty response as a failure to re-check, never as success.
- If production's persisted mod state predates a format/namespace change, stop `mc`, delete the state file, and re-run `deploy.sh` — deploys recreate everything idempotently.

The pipeline itself: `release.yml` builds each mod listed in `mods/local-mods.manifest`, stages the **remapped** jar (never `-dev`) as `dist/local-mods/<jar_name>`; `build-stack-bundle.sh` packs it into the bundle as `stack/local-mods/`; `deploy.sh` (production, step 8b, while `mc` is stopped, before it starts) and `dev-up.sh` (local, every `./dev up`) copy `stack/local-mods/*.jar` into `data/mods/`. **Never hand-copy jars into consumer repos and never publish to Modrinth.**

## Traps (read before you ship anything)

1. **Gradle reports `BUILD SUCCESSFUL` while producing an empty or unremapped jar.** Shipped a production crash loop once — this is why step 2 is a mandatory gate, not a suggestion.
2. **Unlisted mixins silently don't apply**, causing a `ClassCastException` at the point an accessor interface is used. `@Accessor`/`@Invoker` interfaces go in the same `mixins` array as `@Mixin` classes in `<modid>.mixins.json`. Set `"defaultRequire": 1` so a missing target crashes at startup instead of silently skipping.
3. **Never mutate the server's worlds map (or any collection vanilla iterates per tick) from a `ServerWorld.tick`/world-tick mixin.** `ConcurrentModificationException` crash the moment the timer fires. Defer to `ServerTickEvents.END_SERVER_TICK`.
4. **Any path adding a `ServerWorld` must fire `ServerWorldEvents.LOAD`; removing one must fire `UNLOAD` before `close()`.** Distant Horizons and c2me build per-level state exclusively from these events — skipping `LOAD` NPEs DH and can lock a player out of production.
5. **Never call `getOrCreateDimension` synchronously from command context** — it deadlocks the main thread. Queue via `requestWorldLoad` (`END_SERVER_TICK`).
6. **Never sync-load a chunk from a world tick.** That is the Epic Dungeons + c2me wedge — RCON goes i/o-timeout while `docker ps` stays healthy.
7. **Fields serialised into `portal_links.json` must stay parseable by every jar that might read them back** — deploys roll back. A `#tag` in a persisted `frameBlock` crash-loops older jars. General rule: persisted-state fields are a compatibility contract, not just today's schema.
8. **If the persisted state format changed, delete the mod's state files under `data/config/` before restarting** — stale state masks bugs and creates ghosts.
9. **Never hand-copy jars into consumer repos and never publish to Modrinth.** The shipping pipeline is release → bundle `stack/local-mods/` → `deploy.sh` step 8b / `dev-up.sh`; the local iteration path is `./dev link` (§ 3), which symlinks the checkout's built jars into the same slot.

## Validation (do not skip this)

```bash
unzip -l build/libs/<mod>-<version>.jar | grep -c '\.class$'   # non-zero
unzip -l build/libs/<mod>-<version>.jar | grep refmap
docker inspect mc --format 'Health={{.State.Health.Status}} Restarts={{.RestartCount}}'
docker logs mc 2>&1 | grep -iE 'mixin apply|<modid>|error' | tail -20
```

Loud failures: `BUILD FAILED`, missing mixin targets with `"defaultRequire": 1` set, an unhealthy container. Silent failures: an unremapped/empty jar that still reports `BUILD SUCCESSFUL`; a stripped c2me key that looks like "nothing happened" but is actually expected; an unlinked consumer installing the released jar over your build on every `./dev up`; a world-tick mixin that skips `ServerWorldEvents` and only surfaces as a DH NPE much later.

## References

- `references/jar-verification.md` — the artefact checks in full, the `mods/local-mods.manifest` format, exactly what `mod-build.yml`/`release.yml` enforce, the dev-jar trap in depth
- `references/carpet-bot-harness.md` — the complete headless player-test rig: ignition, traversal, breaking, shared igniters, per-part/shape/gateway/aura recipes, and every gotcha
- `references/runtime-invariants.md` — tick-loop threading rules, `ServerWorldEvents`, the c2me DFC trap, sync-chunk-load hazards
