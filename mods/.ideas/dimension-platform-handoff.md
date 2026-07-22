# Dimension platform — where we are and what's next

Welcome aboard! This is a handoff note from the agents who worked on this
today (22 July 2026), written so you can pick things up with fresh context
and none of our baggage. It's been a genuinely good run — the map stack
got replaced, the release pipeline got healthier, and the mod grew a whole
tier of new config — and the next stretch is honestly the fun part.

Good first reads alongside this file: `AGENTS.md` (repo root),
`mods/AGENTS.md` (the mod contract + verification loop), and
`mods/.ideas/vanilla-custom-world-settings.md` (the support matrix this
plan grew out of). Elfydd, the consumer repo we test against, lives at
`~/Projects/elfydd`.

## The big picture

We're building custom-dimensions into a complete authoring platform:
dimensions designed entirely in config (biomes, structures, environment,
terrain), verified by the pure-Python seed pipeline in `scripts/seed/`,
rendered to the static map site, and eventually edited through a friendly
GUI. Two example dimensions capture the ambition nicely — a nasty little
mob-heavy pocket world with an endgame structure right by spawn, and a
vast empty scenic one with a single crash-landed end ship and nothing
else. If the config can express both of those, we've built the right
thing.

## How we work (small team, light process)

It's a three-party shop — Pip and a couple of agents — so process stays
minimal:

- Commit straight to main; we don't use PRs or git worktrees. Work in
  `~/Projects/minecraft-server-template` directly, and if you and another
  agent need the repo at the same time, a quick word with Pip beats any
  isolation machinery.
- Notes and plans like this one live in `mods/.ideas/`.
- A couple of timing courtesies that save real pain: hold main pushes
  while a release.yml run is in flight (`gh run list --limit 3` to
  check), and after any release refresh the major tag with
  `git fetch origin '+refs/tags/v3:refs/tags/v3'` — releases force-move
  it, and a stale local copy makes fetch complain forever.
- Park unfinished work as a WIP commit rather than `git stash` (the stash
  stack is shared across sessions).
- The local elfydd server is shared — worth checking nobody's mid-test
  before restarting its containers.

## Where things stand tonight

- Latest release is **v3.2.0**. Since it was cut, main has gained the new
  map site (map.DOMAIN — a static uNmINeD-based shell replacing the
  BlueMap sidecar, commit `476967f`), the Tier 1 environment fields
  (`a21fff4`), and a Dockerfile fix for the new `unmined-render` image
  (the Linux uNmINeD downloads turned out to be tar.gz, not zip). All
  seven GHCR images build green, multi-arch.
- Release image tagging is trustworthy again as of v3.1.1 — publish.yml
  now takes the version as an explicit input (the old ref-derived semver
  tags had silently never fired since a refactor).
- Two warm-up tasks are queued and make a nice on-ramp:
  1. **Cut the next release** (it ships the map site), then bring elfydd
     up to it with `./dev update`.
  2. **Run the Tier 1 live oracle** — the new environment fields have
     passing unit tests but haven't had their live boot check yet. Add a
     fixture dim with `"environment": {"effects": "minecraft:the_nether",
     "monsterSpawnLightLevel": 15}`, restart local mc, look for
     `Registered dimension type: adventure:<slug>_type` in the log, probe
     via RCON, then remove the fixture. The loop is documented in
     mods/AGENTS.md.
- Recent seed-pipeline commits worth reading before touching
  `scripts/seed/`: `db8d838` (world-type-faithful rendering, mod-exact
  biome mixing, three pocket dimensions), `c89e1e1` (profiles honour
  borders.player and the v4 structures block).

## Workstream 1 — biomePatches

The idea: exact biomes at exact coordinates, layered over any generator.
The flagship use is a patch at (0,0) that guarantees the spawn biome —
which turns the roller's spawn-filter lottery (historically a 99.5%+
rejection rate on narrow filters) into a constant pass.

Proposed config, per dimension file:

    "biomePatches": [
      { "biome": "minecraft:cherry_grove", "x": 0, "z": 0, "radius": 96 },
      { "biome": "terralith:moonlight_grove", "x": 1500, "z": -800, "radius": 200 }
    ]

Implementation sketch:
- A `PatchedBiomeSource extends BiomeSource` wrapping (delegate, patches):
  `getBiomes()` is the union, `getBiomeForNoiseGen(x,y,z)` answers from a
  patch when inside one, else delegates. Two things worth knowing going
  in: biome-source coordinates are quarts (block >> 2), so convert the
  radius; and CODEC is a required abstract in 1.21.1 — a codec that
  round-trips delegate + patch list server-side is all it needs.
- The natural home is `DimensionManager.createDimensionOptions` — every
  generator case builds its biome source there, so wrap the result when
  patches are configured.
- A couple of chunks of position-hash jitter on the patch edge keeps the
  boundary from looking stamped on.
- One honest limitation to design around: since 1.18, terrain shape is
  mostly biome-independent. Patches change surface blocks, features,
  spawns and tints — not the landforms. A desert patch on a mountain is a
  sandy mountain, which can still look great if the site is chosen well.

Pipeline parity (part of the same change, not a follow-up):
`scripts/seed/biome_sampler.py` should apply the same override before
scoring, so the Python pipeline and the real server agree about what's at
any coordinate.

A good oracle: fixture dim, cherry_grove patch at (0,0) —
`execute in <dim> run locate biome minecraft:cherry_grove` returns ~0;
a probe outside the radius returns the base source's biome.

## Workstream 2 — structure control

The idea: per-dimension structure policy — everything, nothing, an
allowlist or rejectlist — plus exact "put THIS structure THERE"
placements.

Proposed config:

    "structures": {
      "mode": "allow" | "reject" | "none",
      "list": ["minecraft:ancient_city", ...],
      "force": [
        { "structure": "minecraft:end_city", "x": 780, "z": -120 }
      ]
    }

Heads-up before editing: `DimensionConfig` already has a `Structures`
class used by seed-roll banding, and `c89e1e1` recently adjusted how
profiles read it — the new fields need to coexist with that shape, so
it's worth reading first.

Implementation sketch:
- Filtering builds on machinery that already exists:
  `DimensionStructures` (driven by `ServerChunkLoadingManagerMixin`)
  rebuilds each world's StructurePlacementCalculator from unregistered
  placement copies — the global registry is never touched, and keeping
  that invariant keeps every other dimension safe. "none" is a whole-set
  drop (structureDensity already does these); allow/reject filter which
  sets survive the rebuild. The peaceful overlay drops sets through a
  parallel path today — unifying them while you're in there would leave
  things tidier than we found them.
- Fixed placement is the exciting bit: register a
  `customdimensions:fixed` StructurePlacementType at mod init that
  returns exactly the configured chunk positions, and inject synthetic
  (structure set → fixed placement) pairs during the rebuild. That buys
  generation-time placement with terrain adaptation, `/locate` support,
  and visibility on the map. The access points are
  `StructurePlacementAccessor` and `StructurePlacementCalculatorInvoker`
  — the latter exists because the public Stream create() zeroes
  concentric-ring seeds, so the private ctor is the one to use.
- Reference material is already in this folder:
  `customising-structures.md`/`.csv` and `structure-sets-extracted.csv`.

Pipeline parity: `scripts/seed/structure_placement.py` treats filtered
sets as absent and forced structures as constants — known distance, no
measurement, guaranteed scoring hits. Rolls then only hunt for the
organic remainder, which makes them cheaper, not obsolete.

Oracle: fixture dim forcing an end_city near spawn — `locate structure`
returns the configured spot; with `"mode": "none"`, every locate comes
back "Could not find".

## Workstream 3 — client-side mod

Shipping custom-dimensions in the client pack unlocks the config's
`skyColor`/`fogColor` fields (currently ignored server-side, with a log
note saying so) and richer per-dimension atmosphere than the three
vanilla effects. Players won't notice the mechanics — they already
auto-update through the mrpack/packwiz flow.

Notes for whoever takes it: the jar needs to become environment-safe
(`fabric.mod.json` environment `*`, guarded client entrypoint,
server-gated mixins), and distribution needs a small extension — in-house
jars aren't on Modrinth, servers get them via the stack bundle, so
clients will need the jar added to the pack build and packwiz index in
`build-modpack.sh` (pack-web already serves `/mods/`). The
delivery-pipeline section of mods/AGENTS.md explains the
hash-verification pieces that make auto-update work.

## Workstream 4 — dimension designer GUI

The end goal: a local web UI that makes designing, rolling, rendering and
reviewing dimensions genuinely fun. It should come after workstreams 1–2
(its value depends on placements being configurable), but the foundations
are further along than you might expect: `viewer-server.py` already has
POST endpoints (/pick /reroll /edit-config /create-dimension /shortlist
/hide-dimension /remove-dimension), `biome_renderer.py` draws pure-Python
maps, fast_roller screens ~1578 seeds/sec, the map shell in
`docker/unmined-render/webshell/` shows the house style, and
`fork-dimension-config-gui.md` in this folder has earlier thinking.

## Map of the mod (where to find things)

    mods/custom-dimensions/src/main/java/com/customdimensions/
      config/DimensionConfig.java         per-dim schema (Environment/Borders/Difficulty/Structures/Portal/SeedRoll)
      config/DimensionConfigLoader.java   directory loader + overlay merge (replace / "overrides" / {})
      dimension/DimensionManager.java     generator construction (10 type cases), world lifecycle
      dimension/DimensionTypeBuilder.java environment block → DimensionType (incl. the new Tier 1 fields)
      dimension/DimensionStructures.java  per-dim placement rebuild — workstream 2 lives here
      dimension/WorldBorderManager.java   borders.player applied at boot; 0 means borderless
      mixin/ServerChunkLoadingManagerMixin.java  where the calculator swap hooks in

Building: `cd mods/custom-dimensions && mise exec -- ./gradlew build` —
the mise prefix matters (a newer global Java fails with a misleading
Gradle error; mise pins temurin-21). Ship the remapped jar from
`build/libs/` and give it the class-count + refmap once-over from
mods/AGENTS.md. `./scripts/test-scripts.sh --quick` before pushing keeps
CI happy.

## Things that will save you time

- c2me's density-function compiler stays disabled — deploy.sh (step 8c)
  and dev-up.sh re-patch it every boot, but a bare `docker restart mc`
  boots without the patch, so re-run `./dev up` before trusting seed
  results after a manual restart.
- Worldgen changes and seed-pipeline changes travel together. The roller
  measures reality; if the mod changes generation and `scripts/seed/`
  doesn't know, every candidate score quietly goes stale. Rescoring is
  cheap (`./dev seed-rescore`), re-rolling isn't.
- Seed and height changes only affect newly generated chunks. There's a
  final world wipe planned before the production relaunch, so
  creation-time settings are still freely changeable for now.

## Smaller items worth picking up along the way

- Wire `biome_survey` into scoring (it's collected and displayed today,
  but not scored).
- biome_renderer polish: shoreline brightening, depth-aware water colour,
  per-block surface texture variation.
- Consider scoring structure count within the border, not just nearest
  distance.
- `optional-mods-hardening.md` in this folder is still open (unrelated to
  this programme, but real).

## A sensible order

1. Release cut → elfydd `./dev update` → Tier 1 oracle. Good warm-up,
   ships today's work.
2. Workstream 1 (biomePatches + sampler parity + oracle).
3. Workstream 2 (filters first, then fixed placement + parity + oracle).
4. Workstream 3, then GUI discovery.

Have fun with it — the crash-landed end ship is waiting.
