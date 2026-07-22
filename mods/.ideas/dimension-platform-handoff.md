# Dimension platform — work plan

Written 2026-07-22 for whoever picks this up next. Self-contained: this
file + AGENTS.md (repo root), mods/AGENTS.md, and
mods/.ideas/vanilla-custom-world-settings.md are the required reading.

## Objective

Turn custom-dimensions into a complete authoring platform: dimensions are
designed in config (biomes, structures, environment, terrain), verified by
the pure-Python seed pipeline, rendered to the static map site, and —
eventually — edited through a GUI. Two flagship examples of what the
config must be able to express: a small, mob-heavy dimension with an
endgame structure placed near spawn; an empty scenic world with a single
out-of-context structure (a crash-landed end ship) and nothing else.

## Working agreements

- Commit directly to main. No PRs, no git worktrees — work in
  ~/Projects/minecraft-server-template. If two agents need the repo
  simultaneously, coordinate rather than isolate.
- Docs live in mods/.ideas/ on main.
- Never push main while a release.yml run is in progress
  (`gh run list --limit 3` first). After a release, refresh the
  force-moved major tag: `git fetch origin '+refs/tags/v3:refs/tags/v3'`
  — a stale copy makes every fetch in that checkout exit non-zero.
- Never `git stash` (shared stack); park work as a WIP commit.
- The local elfydd server (~/Projects/elfydd) is a shared resource —
  confirm nobody else is mid-task on it before restarting containers.

## Current state (verify, don't assume)

- Latest release: v3.2.0. Landed on main after it (so a release cut is
  owed): map.DOMAIN static shell + BlueMap removal (476967f), Tier 1
  environment fields (a21fff4), support-matrix docs, unmined-render
  Dockerfile fix. All seven GHCR images build green including
  unmined-render (multi-arch).
- Semver image tagging works again as of v3.1.1 (publish.yml takes the
  version as an input; the ref-derived form never fired after the
  workflow_call refactor).
- Outstanding immediate tasks:
  1. Cut the release that ships map.DOMAIN, then `./dev update` elfydd.
  2. Tier 1 live boot oracle (below) — unit tests passed, live pass has
     NOT run yet.
- Recent commits to be aware of before touching scripts/seed/: db8d838
  (world-type-faithful rendering, mod-exact biome mixing, cave
  conversions, 3 pocket dimensions), c89e1e1 (seed profiles honour
  borders.player + v4 structures block), 59976af (critters-and-companions
  version hold).

### Tier 1 oracle (run when the local server is free)

Add a fixture dim with
`"environment": {"effects": "minecraft:the_nether", "monsterSpawnLightLevel": 15}`,
restart local mc, assert the boot log line
`Registered dimension type: adventure:<slug>_type`, probe spawn behaviour
via RCON, then delete the fixture. Standard loop from mods/AGENTS.md
(jar → data/mods → restart → RCON → cleanup).

## Workstream 1 — biomePatches

Goal: exact biomes at exact coordinates, layered over any generator.

Config (per dimension file):

    "biomePatches": [
      { "biome": "minecraft:cherry_grove", "x": 0, "z": 0, "radius": 96 },
      { "biome": "terralith:moonlight_grove", "x": 1500, "z": -800, "radius": 200 }
    ]

Implementation:
- New `PatchedBiomeSource extends BiomeSource` wrapping (delegate,
  patches): `getBiomes()` = delegate ∪ patch entries;
  `getBiomeForNoiseGen(x,y,z)` returns the patch biome inside a patch,
  else delegates. Biome-source coordinates are QUARTS (block >> 2) —
  convert radius accordingly. CODEC is a required abstract in 1.21.1;
  a delegate-codec + patch-list codec that round-trips server-side is
  sufficient.
- Wire in `DimensionManager.createDimensionOptions` — every generator case
  already constructs its biome source there; wrap the result when patches
  are configured.
- Blend patch edges with 1–2 chunks of position-hash jitter so boundaries
  don't look stamped.
- Terrain caveat (1.18+ worldgen): patches change surface rules, features,
  spawns, and tints — not terrain shape. A desert patch on mountains is a
  sandy mountain. Choose patch sites with the dimension's terrain in mind.

Seed-pipeline parity (required in the same change):
- `scripts/seed/biome_sampler.py` applies the same override before
  scoring; a patch covering (0,0) makes the spawn filter a constant pass.
  This removes the spawn-filter lottery (historically 99.5%+ rejection on
  narrow filters) for patched dimensions.

Oracle: fixture dim with a cherry_grove patch at (0,0);
`execute in <dim> run locate biome minecraft:cherry_grove` must return
distance ~0; a probe outside the radius must return the base source's
biome.

## Workstream 2 — structure control

Goal: per-dimension structure policy — none / allowlist / rejectlist —
plus exact placements.

Config:

    "structures": {
      "mode": "allow" | "reject" | "none",
      "list": ["minecraft:ancient_city", ...],
      "force": [
        { "structure": "minecraft:end_city", "x": 780, "z": -120 }
      ]
    }

NOTE: DimensionConfig already has a `Structures` class (seedRoll banding
uses it, and c89e1e1 recently changed how profiles read it) — read it
before extending; the new fields must coexist with the existing shape.

Implementation:
- Filtering: `DimensionStructures` (driven by
  `ServerChunkLoadingManagerMixin`) already rebuilds each world's
  StructurePlacementCalculator from UNREGISTERED placement copies — the
  global registry is never mutated; keep that invariant. "none" = drop all
  sets (whole-set drop machinery exists via structureDensity); allow/
  reject filter which sets survive the rebuild. Density rescaling composes
  before filtering; the peaceful overlay's set-drops should merge into the
  same path rather than remain parallel.
- Fixed placement: register a `customdimensions:fixed`
  StructurePlacementType at mod init whose placement returns exactly the
  configured chunk positions. Inject synthetic (structure-set → fixed
  placement) pairs during the calculator rebuild. This yields
  generation-time placement with terrain adaptation, /locate support, and
  map visibility. Access points: `StructurePlacementAccessor`,
  `StructurePlacementCalculatorInvoker` (the private ctor — the public
  Stream create() zeroes concentric-ring seeds).
- Reference data already in-repo: customising-structures.md/.csv,
  structure-sets-extracted.csv.

Seed-pipeline parity:
- `scripts/seed/structure_placement.py` must treat filtered-out sets as
  absent and forced structures as constants (known distance, no
  measurement). Scoring then counts forced structures as guaranteed hits
  and stops spending rolls hunting for them.

Oracle: fixture dim forcing an end_city near spawn;
`locate structure minecraft:end_city` returns the configured position;
with `"mode": "none"`, every locate returns "Could not find".

## Workstream 3 — client-side mod

Goal: ship custom-dimensions in the client pack, unlocking per-dimension
sky/fog colours (the config's skyColor/fogColor fields, currently ignored
server-side with a log note) and richer dimension effects beyond the three
vanilla presets. Distribution is transparent to players — they already
auto-update via the mrpack/packwiz flow.

Implementation notes:
- Make the jar environment-safe: `fabric.mod.json` environment `*`,
  guarded client entrypoint, server-only mixins gated.
- Delivery: in-house jars are not on Modrinth. Servers get them via the
  stack bundle (local-mods). Clients need the mirror route: pack-web
  already serves /mods/ — add the jar to the pack build + packwiz index
  in build-modpack.sh. Read the delivery-pipeline section of
  mods/AGENTS.md first; hash-verification and the packwiz index are
  load-bearing.

## Workstream 4 — dimension designer GUI (after 1+2)

Direction only, design when its dependencies exist: a local web UI
unifying config editing, seed rolling, rendering, and review. Existing
foundations: viewer-server.py (HTTP + POST endpoints: /pick /reroll
/edit-config /create-dimension /shortlist /hide-dimension
/remove-dimension), the viewer SPA, biome_renderer.py (pure-Python maps),
fast_roller (≈1578 seeds/sec tier-1), map shell house style in
docker/unmined-render/webshell/, and prior notes in
fork-dimension-config-gui.md. The GUI's value depends on workstreams 1–2
being configurable, so don't start it first.

## Key files

    mods/custom-dimensions/src/main/java/com/customdimensions/
      config/DimensionConfig.java         per-dim schema (Environment/Borders/Difficulty/Structures/Portal/SeedRoll)
      config/DimensionConfigLoader.java   directory loader + overlay merge (replace / "overrides" / {})
      dimension/DimensionManager.java     generator construction (10 type cases), world lifecycle
      dimension/DimensionTypeBuilder.java environment → DimensionType (Tier 1 fields)
      dimension/DimensionStructures.java  per-dim placement rebuild (workstream 2 home)
      dimension/WorldBorderManager.java   borders.player at boot; 0 = borderless
      mixin/ServerChunkLoadingManagerMixin.java  calculator swap hook

Build: `cd mods/custom-dimensions && mise exec -- ./gradlew build` (a
global newer Java breaks Gradle with a misleading task-creation error;
mise pins temurin-21). Ship only the REMAPPED jar from build/libs/ —
verify class count + refmap per mods/AGENTS.md. Run
`./scripts/test-scripts.sh --quick` before pushing.

## Standing constraints

- c2me's density-function compiler stays disabled (deploy.sh step 8c and
  dev-up.sh re-patch it; a bare `docker restart mc` boots WITHOUT the
  patch — re-run ./dev up before trusting any seed result).
- Worldgen changes and seed-pipeline changes land together, always: the
  roller measures reality, and a mod-side change without matching
  scripts/seed/ support silently invalidates every candidate score.
  Rescoring is cheap (`./dev seed-rescore`); re-rolling is not.
- Seed/height changes affect only newly generated chunks. A final world
  wipe is planned before production relaunch, so creation-time-ish
  settings are still freely changeable until then.
- Only whole-set drops apply to non-random-spread placement types today;
  concentric-ring placements go through the private calculator ctor.

## Deferred items folded in from cleared reports

- Wire biome_survey into scoring (collected and displayed, not scored).
- biome_renderer polish: shoreline brightening, depth-aware water,
  per-block surface texture variation.
- Consider scoring structure COUNT within the border, not just nearest
  distance.
- optional-mods-hardening.md remains open and unrelated to this
  programme.

## Suggested order

1. Release cut (ships map.DOMAIN) → elfydd `./dev update` → Tier 1 oracle.
2. Workstream 1 (biomePatches + sampler parity + oracle).
3. Workstream 2 (filters, then fixed placement + placement parity + oracle).
4. Workstream 3 (client jar), then Workstream 4 discovery.
