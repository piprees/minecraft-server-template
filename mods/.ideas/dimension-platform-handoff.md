# Handoff: the all-in-one custom dimensions platform

**Date: 2026-07-22. Audience: the next agent. Assume nothing beyond this
file + AGENTS.md (both repo roots) + mods/AGENTS.md.** Pip has approved the
direction below; your job is to implement it.

**Repo workflow (Pip's standing directives — do not deviate).** This is a
three-party shop (Pip + two agents), not a commercial op — keep the
process light:
- **No PRs.** Commit directly to main. PRs only for things that genuinely
  need review, which working-state changes do not.
- **No git worktrees.** Work in the checkout at
  ~/Projects/minecraft-server-template directly. Pip finds worktrees
  confusing and they've caused real friction (stale checkouts, "where is
  the file?" moments). If two agents need the repo at once, coordinate
  through Pip instead of isolating.
- Docs go in mods/.ideas/ on main — no doc PRs either.
- Never push main while a release.yml run is in progress; check
  `gh run list --limit 3` first. After any release, refresh the moved
  major tag: `git fetch origin '+refs/tags/v3:refs/tags/v3'` (a stale
  copy makes every fetch in that checkout exit non-zero).
- (Historical quirk, pre-dating the no-PR rule: the repo only allows
  squash merges, and the gh API token can't merge PRs touching workflow
  files. Irrelevant now — don't rediscover it.)

## The vision (Pip's words, condensed)

An all-in-one custom-dimensions platform: the mod (server AND eventually
client), the pure-Python seed-roller/renderer, the uNmINeD map site, and —
end goal — **a GUI that makes customising, rendering, and working with
dimension configs fun and easy**. Target flavour: "horrible, mob-heavy
small dimensions with a super-endgame structure close to spawn" and
"completely empty worlds full of beautiful vistas and a single,
crash-landed end ship completely out of context".

## State of the world (as of tonight)

- Latest release **v3.2.0** (other agent). v3.1.1 has version-tagged GHCR
  images (first release ever to — the semver bug is fixed in publish.yml;
  release.yml passes `inputs.version` through).
- Landed on main today, post-v3.2.0 (so **a release cut is owed** when the
  dust settles): `476967f` map.DOMAIN uNmINeD static shell + BlueMap
  removal; `6c7e90f`+`93ebb18` the support-matrix + precision-placement
  docs; `a21fff4` Tier 1 environment fields; a Dockerfile fix (Linux
  uNmINeD downloads are tar.gz, NOT zip; build checks are structural to
  avoid QEMU). Confirm the latest publish.yml run is green — the
  unmined-render image build was in flight when this was written.
- **elfydd bump owed**: local consumer sits on v3.1.x-era stack; bump via
  `./dev update` once nobody else is using the local server, ideally after
  the next release so map.DOMAIN ships in one hop. NEVER touch the local
  elfydd docker stack without checking whether another agent is using it.
- **Tier 1 live boot oracle owed** (mods/AGENTS.md verification loop):
  boot a fixture dim with `"environment": {"effects": "minecraft:the_nether",
  "monsterSpawnLightLevel": 15}` on local elfydd, assert the registered
  type + spawn behaviour, then remove the fixture. Unit tests exist
  (DimensionTypeBuilderTest) and pass; the live pass has NOT run.
- Other agent's recent work you must not clobber: `db8d838`
  (world-type-faithful rendering, mod-exact biome mixing, cave
  conversions, 3 pocket dimensions), `c89e1e1` (seed profiles honour
  borders.player + v4 structures block), `59976af` (critters-and-companions
  hold). Read those diffs before touching scripts/seed/.

## Approved implementation work

### 1. `biomePatches` (config array, per dimension)

    "biomePatches": [
      { "biome": "minecraft:cherry_grove", "x": 0, "z": 0, "radius": 96 },
      { "biome": "terralith:moonlight_grove", "x": 1500, "z": -800, "radius": 200 }
    ]

Wrap the dimension's biome source with an override layer: delegate
everywhere except inside patches. Implementation home:
`DimensionManager.createDimensionOptions` (mods/custom-dimensions/.../dimension/)
builds every biome source — wrap the result there. Write a
`PatchedBiomeSource extends BiomeSource` holding (delegate, patches);
`getBiomes()` = delegate ∪ patch biomes (registry entries resolved at
construction); `getBiomeForNoiseGen(x,y,z)` = patch hit else delegate
(remember biome coords are QUARTS: block>>2 — radius in blocks / 4).
CODEC: required abstract in 1.21.1 — a codec that can round-trip
(delegate codec + patch list); it only needs to survive server-side use.
Blend the edge with 1–2 chunks of position-hash jitter so patches don't
look stamped. The killer feature: a patch at (0,0) guarantees spawn biome
→ the roller's spawn-filter lottery disappears for patched dims.
**Roller parity (mandatory):** `biome_sampler.py` must apply the same
patch override before scoring; spawn filter passes trivially when a patch
covers (0,0). See scripts/seed/ + the fast_roller pipeline.

### 2. Structure control: `customdimensions:fixed` placement + filters

Approved shape (bikeshed field names as needed):

    "structures": {
      "mode": "allow" | "reject" | "none",      // filter direction
      "list": ["minecraft:ancient_city", ...],   // with allow/reject
      "force": [
        { "structure": "minecraft:end_city", "x": 780, "z": -120 }
      ]
    }

- "none" = drop every structure set (structureDensity machinery already
  supports whole-set drops — extend, don't duplicate).
- allow/reject lists filter which structure SETS survive the per-dimension
  placement rebuild. The machinery lives in
  `ServerChunkLoadingManagerMixin` → `DimensionStructures`: it already
  rebuilds each world's StructurePlacementCalculator with UNREGISTERED
  rescaled placement copies (global registry never mutated — keep that
  invariant). `StructurePlacementAccessor` +
  `StructurePlacementCalculatorInvoker` (private ctor — the public
  Stream create() zeroes concentric-ring seeds) are the access points.
- `force`: register a `customdimensions:fixed` StructurePlacementType
  (StructurePlacementType registry, register at mod init) whose
  `shouldGenerate`/position logic returns exactly the configured chunk
  positions. Inject synthetic (structure-set → fixed placement) pairs into
  the rebuilt calculator. This gets generation-time placement with terrain
  adaptation, /locate support, and map markers.
- NOTE `structures` key already exists in DimensionConfig (a `Structures`
  class, used by seedRoll banding) — read it first and either extend it or
  nest carefully; c89e1e1 just touched how profiles read it.
- Interactions: structureDensity rescaling composes BEFORE filters;
  peaceful overlay already drops sets — unify the paths.
- **Roller parity:** `structure_placement.py` computes vanilla placements
  from seed — teach it (a) filtered sets vanish, (b) forced structures are
  constants (distance known without measurement). Wants/shuns scoring then
  treats forced structures as guaranteed hits.
- Datapack reference material: mods/.ideas/customising-structures.md +
  customising-structures.csv + structure-sets-extracted.csv (kept for
  exactly this work).

### 3. Client-side mod

Approved: ship custom-dimensions to clients via the mrpack (players
already auto-update through packwiz — transparent to them). What it
unlocks: custom dimension EFFECTS beyond the three vanilla skyboxes
(skyColor/fogColor from config — currently ignored server-side with a log
note), proper sky rendering per dimension, and later GUI hooks. Mechanics:
the jar must become environment-safe (`fabric.mod.json` environment "*",
client entrypoint guarded; server-only mixins gated). Client list lives in
`modpack/adventure.mrpack.json` `_clientMods` — but in-house jars are NOT
on Modrinth (delivery is bundle → data/mods for servers), so client
delivery needs the mirror route: pack-web already serves `/mods/` — add
the local-mod jar to the pack build + packwiz index (build-modpack.sh).
Check mods/AGENTS.md delivery-pipeline section first.

### 4. Dimension designer GUI (end goal, design later)

Direction, not spec: a local web GUI unifying config editing + seed
rolling + rendering. Foundations already exist — `viewer-server.py` HTTP
server with POST endpoints (/pick /reroll /edit-config /create-dimension
/shortlist /hide-dimension /remove-dimension), the viewer SPA, the
pure-Python renderer (biome_renderer.py), fast_roller (1578 seeds/sec
tier-1), and mods/.ideas/fork-dimension-config-gui.md (kept — read it).
The map shell (docker/unmined-render/webshell/) shows the house style.
Don't start this before 1+2 land; the GUI's value depends on patches and
placements being configurable.

## Key files (mod)

    mods/custom-dimensions/src/main/java/com/customdimensions/
      config/DimensionConfig.java        one file per dim; Environment/Borders/Difficulty/Structures/Portal/SeedRoll
      config/DimensionConfigLoader.java  directory loader + overlay merge (replace / "overrides" / {})
      dimension/DimensionManager.java    generator construction (10 type cases), world lifecycle, pending-load queue
      dimension/DimensionTypeBuilder.java environment → DimensionType (Tier 1 fields live here)
      dimension/DimensionStructures.java per-dim placement rebuild (extend for #2)
      dimension/WorldBorderManager.java  borders.player at boot; 0 = borderless
      mixin/ServerChunkLoadingManagerMixin.java  calculator swap hook

Build: `cd mods/custom-dimensions && mise exec -- ./gradlew build` (global
Java 25 breaks Gradle with a misleading error; mise pins temurin-21).
Verify the REMAPPED jar (build/libs/, never devlibs): class count +
refmap, per mods/AGENTS.md. Full local verification loop is MANDATORY
before any release: install jar into elfydd data/mods, restart mc, RCON
oracles, soak timed paths. Never `git stash` (shared stack — use a WIP
commit to park work instead); no worktrees (see directives above);
`./scripts/test-scripts.sh --quick` before pushing.

## Sharp edges you will hit

- c2me density-function compiler must stay disabled (deploy.sh 8c /
  dev-up.sh re-patch it; a bare `docker restart mc` boots WITHOUT it —
  re-run ./dev up before trusting seed results).
- Custom placements: only whole-set drops apply to non-random-spread
  placement types today; concentric-rings placements need the private
  calculator ctor (already invoked — see comments in DimensionStructures).
- Biome coords are quarts; world border centre is (0,0); borders.generation
  is tooling metadata (uNmINeD --area clamp + future chunky per-dim pregen).
- Seed changes/heights only apply to NEW chunks; production wipe planned
  anyway (server intentionally down — one final wipe before relaunch).
- The roller measures REALITY: any mod change that alters worldgen without
  a matching scripts/seed/ change silently invalidates every score. Rescore
  is cheap (`./dev seed-rescore`), re-rolling is not.

## Cleared today (deleted from .ideas — content actioned; git history has them)

catalyst-maw-redesign (config applied), custom-dimensions-v4-plan (v4
shipped), mod-owned-dimension-lifecycle + profile-driven-seed-rolling +
fast-spawn-lookup (all implemented eras), seed-viewer-audit-reports +
seed-viewer-improvement-prompt (viewer a11y/progress landed today),
unmined-rendering (implemented as map.DOMAIN), session-resume-prompt
(superseded by this file). Still-open scraps folded in here: wire
biome_survey into scoring (display-only today); shoreline/water/texture
polish in biome_renderer; structure-count-within-border scoring idea.

## Kept in .ideas

vanilla-custom-world-settings.md (the support matrix + precision-placement
plan this handoff executes), customising-structures.md/.csv,
structure-sets-extracted.csv, customising-terrain.md (reference data),
optional-mods-hardening.md (unactioned), fork-dimension-config-gui.md
(GUI seed material).

## Suggested order

1. Confirm publish.yml green; cut the release that ships map.DOMAIN;
   bump elfydd (when the local server is free); run the Tier 1 oracle.
2. biomePatches (mod + biome_sampler parity + one fixture-dim oracle:
   patch cherry_grove at 0,0, assert `locate biome` = 0 distance).
3. Structure controls (filters first, then customdimensions:fixed;
   structure_placement.py parity; oracle: force an end_city near spawn in
   a fixture dim, `/locate structure` must return it).
4. Client-side mod enablement; then GUI discovery.
