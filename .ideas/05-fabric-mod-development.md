# Skill brief: `fabric-mod-development`

> **This is a brief, not the skill.** Build `SKILL.md` from it; verify every path against the repo before writing.

## Why this skill

`mods/AGENTS.md` is 449 lines and is the densest, most incident-derived document in the repo. It is also structurally hostile to an agent: the verification loop — the part that decides whether a change is safe to ship — starts at line 138, after 130 lines of portal-subsystem architecture notes. An agent that skims will build a jar, see `BUILD SUCCESSFUL`, and ship a dev jar that crash-loops production. That has happened.

Three things in that file are pure procedure and belong in a skill:

- **Verify the artefact, not the build** — Gradle reports success while `remapJar` emits an empty or unremapped jar.
- **The fast local loop** — and specifically that `./dev up` *destroys* it by overwriting your jar with the bundle's.
- **The Carpet fake-player harness** — a genuinely reusable headless test rig for player-dependent paths, buried in a bullet list with ~25 hard-won gotchas.

The architecture notes (portal system, immersive, auras) should stay in `mods/AGENTS.md` and be *referenced*, not copied — they change often and duplicating them guarantees drift.

## Scope

**In:** building, verifying, testing and shipping an in-house Fabric mod. Toolchain, mixin conventions, the artefact checks, the local iteration loop, the Carpet bot harness, soak testing, the threading and world-lifecycle rules, and the release path.

**Out:** authoring dimension *configs* → the existing `custom-dimension-authoring` skill. Dimension lifecycle on a live world → brief 15. Cutting the release → brief 06 (cross-reference).

## Source material

| File | What to mine |
| --- | --- |
| `mods/AGENTS.md § Environment, § Conventions` | mise/Java 21, Loom, Yarn vs intermediary, mixin registration, `defaultRequire: 1` |
| `mods/AGENTS.md § Verification loop` (lines 138-298) | **The core.** All five stages, verbatim commands, every gotcha |
| `mods/AGENTS.md § Worldgen self-containment rules` | Noise ids seed-bearing, DF ids not; never copy a vanilla-shipped `minecraft:` id; pack order via `datapack list` |
| `mods/AGENTS.md § Structure placement lessons` | CubicSpline extrapolation; `/locate` is first-found-in-radius-order; `force` guarantees the attempt not the biome check |
| `mods/AGENTS.md § Architecture` | The component tree — reference it, don't reproduce it |
| `AGENTS.md § In-house mods` (lines 214-224) | The delivery pipeline; the remapped-vs-dev jar rule; iterate-locally economics |
| `AGENTS.md:162` | `mise exec` required — a global Java takes precedence and Gradle fails with a misleading task-creation error |
| `mods/mise.toml` | The pinned toolchain |
| `.github/workflows/mod-build.yml`, `release.yml` | The CI class-count and refmap verification |
| `.github/workflows/smoke-test.yml` | The full CI harness: builds the mod, boots with all mods, spawns a Carpet bot, asserts traversal, asserts the piston-mixin patch |
| `scripts/patch-mod-data.py` | The carpet mixin strip applied on every deploy and `./dev up` |
| `docs/known-issues/carpet-supplementaries-piston-crash.md` | Why carpet needs patching |
| `mods/custom-dimensions/README.md` | Commands, config reference, build/test/install sections |

## Required structure

```
fabric-mod-development/
├── SKILL.md
└── references/
    ├── jar-verification.md      # the artefact checks, the dev-jar trap, what CI enforces
    ├── carpet-bot-harness.md    # the full headless player-test rig + every gotcha
    └── runtime-invariants.md    # tick-loop threading, ServerWorldEvents, c2me DFC, never sync-load a chunk
```

### SKILL.md must contain

1. **The build command with `mise exec`, not bare `./gradlew`:**
   ```bash
   cd mods/<name> && mise exec -- ./gradlew build
   ```
   with the reason — a global Java (e.g. 25) wins and Gradle fails with a misleading task-creation error, not a wrong-Java message.
2. **Artefact verification as a mandatory gate**, immediately after build, with the three checks runnable as written (class count non-zero, refmap present, intermediary `class_XXXX` names in a mixin). Plus the dev-jar trap: `build/devlibs/<mod>-<version>-dev.jar` uses Yarn names and no refmap; on a real server every mixin fails with `could not find any targets … No refMap loaded` and the server crash-loops. **Only ever ship `build/libs/`.**
3. **The fast local loop**, with its prohibition stated first:
   > **Never use `./dev up` to test a local mod build.** `dev-up.sh` copies `stack/local-mods/*.jar` over your jar on every run. Your test then runs the OLD code and passes.

   Use `docker stop mc && docker start mc`. Include the 2026-07-25 incident (a lazy-init feature "didn't work" across four boot cycles because every test ran the bundle jar) — the date is the evidence.
4. **The c2me re-patch rule**, adjacent to the loop, because it bites *every* restart: c2me strips `useDensityFunctionCompiler` from `data/config/c2me.toml` on every boot after reading it, so a patch does not survive repeated cycles. Re-patch before each `stop/start`, and **verify by log grep, never by inspecting the config file** — the key's absence afterwards is expected.
5. **RCON exercise recipes** for headless verification, with the namespace-resolution snippet from `mods/AGENTS.md:189-200`.
6. **The Carpet bot harness**, summarised in SKILL.md and expanded in the reference. The load-bearing gotchas that must survive summarisation:
   - bot stands **inside** the frame and `look down` at the bottom frame block
   - `player Bot attack once` does not break a multi-click block — use `attack continuous` … `attack stop`
   - RCON `fill`/`setblock` into an unloaded dimension silently does nothing (answers `Unknown dimension`) — teleport a player there first
   - assert with `data get entity` and log greps, never RCON chat echoes
   - `touch /data/.skip-pause` or autopause kicks the bot
   - source portal interiors have **no** portal blocks — assert traversal, never probe the source interior
   - profile an arrival column **before** traversing
7. **Soak testing** for anything on a timer, with the RestartCount assertion.
8. **Ship-and-verify at each layer**: script counters count commands *sent*, not commands that succeeded — check the persisted result instead.

### Traps to capture

1. Gradle reports `BUILD SUCCESSFUL` while producing an empty or unremapped jar. (Shipped a production crash loop once.)
2. Unlisted mixins silently don't apply and cause `ClassCastException` when accessor interfaces are used. `@Accessor`/`@Invoker` go in the same `mixins` array.
3. **Never mutate the server's worlds map from a world-tick mixin** — `ConcurrentModificationException` when the timer fires. Defer to `END_SERVER_TICK`.
4. **Any path adding a `ServerWorld` must fire `ServerWorldEvents.LOAD`**; removing one must fire `UNLOAD` before `close()`. DH, BlueMap and c2me build per-level state exclusively from these events — skipping LOAD NPE'd DH and locked a player out of production (2026-07-12).
5. **Never call `getOrCreateDimension` synchronously from command context** — it deadlocks the main thread. Queue via `requestWorldLoad`.
6. **Never sync-load a chunk from a world tick** — that is the Epic Dungeons + c2me wedge.
7. **Fields serialised into `portal_links.json` must stay parseable by every jar that might read them back** — deploys roll back. A `#tag` in a persisted `frameBlock` crash-loops older jars (2026-07-23).
8. If the persisted state format changed, **delete the mod's state files under `data/config/` before restarting** — stale state masks bugs and creates ghosts.
9. **Never hand-copy jars into consumer repos and never publish to Modrinth.** The pipeline is release → bundle `stack/local-mods/` → `deploy.sh` step 8b / `dev-up.sh`.

### Validation section

```bash
unzip -l build/libs/<mod>-<version>.jar | grep -c '\.class$'   # non-zero
unzip -l build/libs/<mod>-<version>.jar | grep refmap
docker inspect mc --format 'Health={{.State.Health.Status}} Restarts={{.RestartCount}}'
docker logs mc 2>&1 | grep -iE 'mixin apply|<modid>|error' | tail -20
```

## Done when

- An agent that changes a mod runs the artefact checks before ever restarting a container, and never uses `./dev up` to test its own build.
- The Carpet harness reference is complete enough that an agent can write a new player-dependent test without reading `mods/AGENTS.md`.
- The architecture sections are *linked*, not copied — `mods/AGENTS.md` stays the source of truth for how the mod works.
