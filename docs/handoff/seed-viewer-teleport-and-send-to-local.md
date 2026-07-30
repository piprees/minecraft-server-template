# Handoff — items 2 and 3: `/customdim teleport` and "Send to local"

Written 2026-07-30. Items 1, 4 and 5 of the five-item batch are committed
(`4b5c824`, `e629d7d`, `f73f4f9`). These two are **not started in code** — this
file is the design work, including three traps that are expensive to find by
hand. Nothing in the repo is half-built; the tree is clean of this work.

## Why they were left

They are one change, not two: both add a `/customdim` subcommand and both need a
built jar installed into `~/Projects/elfydd/data/mods/`, a `docker restart mc`
with the c2me re-patch, and an RCON exercise before anything can be called done.
That verification loop is the bulk of the work and it cannot be split across the
two items. Starting it without finishing it would leave an unverified mod change
in a repo that a second agent session is concurrently writing to (see § Concurrent
session below).

---

## Trap 1 — the permission level cannot be lowered on a child node

Item 2 asks for `teleport` at **permission level 2** (vanilla `/teleport`'s
level). The `customdim` root currently carries:

```java
CommandManager.literal("customdim")
    .requires(source -> source.hasPermissionLevel(4))
```

Brigadier ANDs a child's `requires` with its parents', so a child can only ever
be **more** restrictive. `teleport` under this root is level 4 whatever you write
on it.

**Registering the root a second time does not work either**, and it fails
silently, which is worse. `CommandNode.addChild` merges by name: when the literal
already exists it copies the *new* node's children onto the *existing* node and
discards everything else about it — including its `requires` predicate. So:

```java
// DOES NOT WORK. teleport ends up at level 4 and nothing warns you.
dispatcher.register(CommandManager.literal("customdim")
    .requires(s -> s.hasPermissionLevel(2))
    .then(CommandManager.literal("teleport") /* ... */));
```

**The correct change:** lower the root to `hasPermissionLevel(2)` and add an
explicit `.requires(s -> s.hasPermissionLevel(4))` to **every** existing
subcommand. There are 13, and missing one silently drops an operator command to
level 2:

`create` · `destroy` · `list` · `load` · `locate` · `locate-result` ·
`dump-biome-params` · `debug-prng` · `sample-noise` · `sample-biome-grid` ·
`structure-audit` · `structure-census` · `dump-structure-pools`

Add a unit test asserting each subcommand's required level, or this regresses the
first time someone adds a subcommand and forgets the `requires`. The test is the
only thing that makes this safe — there is no compile-time signal.

## Trap 2 — the dimension must be loaded, and you cannot load it inline

`execute in <ns>:<dim>` fails with "Unknown dimension" as soon as the 5-minute
idle unloader drops the world (`idleUnloadMinutes`, default 5 in
`settings.json`). A teleport command therefore has to load the world first.

**Never call `getOrCreateDimension` / `getOrCreateDimensionDirect` from command
context — it deadlocks the main thread** (`mods/AGENTS.md`, dynamic world
lifecycle rule; also trap 5 in the `fabric-mod-development` skill). Queue via
`DimensionManager.requestWorldLoad(name)`, which appends to `pendingWorldLoads`
and is drained by `processPendingWorldLoads()` from `END_SERVER_TICK` at
`WORLD_LOADS_PER_TICK = 1`.

So the teleport cannot complete in the same tick it is issued. Two options:

1. **Queue the teleport too** (better UX): a small deferred-action list in the
   command class, drained from the same `END_SERVER_TICK` hook, retrying until
   the world exists with a bounded attempt count (~100 ticks) so a
   never-loading dimension reports a failure instead of retrying forever.
2. **Two-step**: queue the load, tell the caller to re-issue. Simpler, and
   acceptable because the viewer only ever *copies the command to the clipboard*
   — the human runs it, and running it twice is not a hardship.

Option 2 is the smaller change and matches how the viewer actually uses it. Say
so in the feedback message (`"loading <dim> — run this again in a moment"`), or
the first invocation reads as a failure.

Whichever is chosen, resolve the dimension argument through the existing
`resolveWorld(ctx)` helper, not `getWorld` directly: Brigadier's identifier
argument defaults a bare name to `minecraft:`, so `teleport the_underdark` asks
about `minecraft:the_underdark`. `resolveWorld` already falls back to the
configured namespace, and `customdim load` already accepts bare names — a third
spelling convention in the same command tree is a trap.

## Trap 3 — `destroy` already exists; `fromfile` is the only new one for item 3

Item 3 asks for a "clear test dimensions" control using `customdim destroy`.
That subcommand is already there (`DimensionCommands.destroy`, line ~458): it
calls `requestWorldUnload`, `forgetRuntimeDefinition` and
`DimensionFingerprints.forget`. Nothing to add — the viewer just calls it once
per dimension it created.

Only `fromfile <slug>` is new. It should read
`config/custom-dimensions/dimensions/<slug>.json` (or the staged overlay), build
a `DimensionConfig` from it, and go through the same
`rememberRuntimeDefinition` → `registerDimension` → `requestWorldLoadDirect`
sequence `create` uses. Reuse `create`'s body rather than writing a second path;
the difference is only where the fields come from.

**RCON reply parsing:** replies concatenate with no separator
(`"...the_starwell1 custom dimension(s) loaded"`). Parse with a regex, never a
whitespace split. This bites the viewer's side, not the mod's.

---

## Viewer side

**Item 2 — click the map to copy a teleport command.** The projection needed for
this already exists and is tested: `window.lbProject` /
`window.lbProjectRadius` in `scripts/seed/web/project.js`, pinned by
`scripts/seed/test_web_projection.py`. Inverting it for a click is
`blockX = (viewBoxX - 50) / 100 * coverage`, where `coverage` must come from
`window.lbMapCoverage()` and never from the `data-coverage` attribute alone (the
lightbox shows the low-res render until the hi-res probe lands, and they cover
different areas). Add the inverse to `project.js` as `lbUnproject` so it is
covered by the same test rather than hand-written at the call site — that is the
whole reason that module exists.

`window.lbCandidate()` (added in `4b5c824`) gives `{dim, seed}` for the open
candidate, which is what names the dimension in the command.

Y coordinate: from the terrain survey if one is banked, else 320. The survey is
in the candidate store under `terrainSurvey` but is **not** currently emitted
into the page, so either add it to the panel markup in
`score-dimensions._terrain_section` or read it from a small endpoint. Note the
survey records `relief`/`grain`/`water`/`land`, **not** a per-point height grid,
so it cannot give the height at an arbitrary clicked x/z — 320 (above terrain,
safe to fall from) is the honest default and the survey only helps if a
height-at-point lookup is added.

**Item 3 — "Send to local".** Per-candidate button POSTing to a new endpoint that
writes `<slug>.json` and then runs `/customdim fromfile <slug>` over RCON. Track
what the viewer created in a file under `<seedtest>/` (alongside
`shortlist.json` and `winner-overrides.json`) so the "clear test dimensions"
control knows what to destroy and can warn when several are live.

Remember the staged-overlay mirror: anything creating a consumer dimension at
runtime must also write into `<config>/overlay/dimensions/`, or
`fast_roller`/`score-dimensions` cannot see it until the next `./dev up`.
`viewer-server._handle_create_dimension` already does this — copy that, do not
re-derive it.

## Shipping checklist

- `mods/local-mods.manifest` already lists custom-dimensions; no manifest change.
- Any new `scripts/seed/web/*.js` file needs **three** lists or it silently does
  nothing: `WEB_ASSETS` in `score-dimensions.py`, a `<script>` tag in
  `viewer_template.html`, and `MANIFEST` in `scripts/build-stack-bundle.sh`.
  `test_web_projection.ShippedTogetherTests` shows the pattern for asserting it.
- `app.css` is the source; `app.built.css` is served. Run
  `scripts/seed/web/build.sh` or the CSS does nothing.
- Local jar: `cp build/libs/*.jar ~/Projects/elfydd/data/mods/` then
  `docker restart mc`. **Never `./dev up`** — it overwrites the jar with the
  bundle's released copy and the test silently runs old code. Re-apply the c2me
  `useDensityFunctionCompiler=false` patch before **every** restart.
- Verify the jar has classes, a refmap and intermediary (`class_NNNN`) names
  before restarting anything. `BUILD SUCCESSFUL` proves none of that.

## Concurrent session

A second `claude` process was writing this repo throughout (pid 7018 at the
time). It made a biome-`weirdness` banding pass across 35 dimension configs,
which is why `git status` shows those modified and unstaged — that work is
**not** mine and was deliberately left alone. It also means:

- Those biome edits move the generation fingerprints of the dimensions they
  touch, which invalidates the terrain surveys cached by the rescore in
  `e629d7d`. They will be recomputed on the next rescore; the *measurements* in
  that commit message were taken against the configs as they stood.
- I briefly discarded one of those edits
  (`the_wuthering_wisteria.json`) with a `git restore` while identifying where it
  came from, and recovered it from the object store. Worth a diff check before
  committing it.
