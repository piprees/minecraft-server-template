# WORKSPACE

In-flight: the seed-roll rewrite, plan at
`~/Projects/elfydd/reports/seed-roll-rewrite.md`. Branch
`feature/seed-roll-fixes` in this repo (platform); consumer under test is
`~/Projects/elfydd` with its local Docker stack.

Read the plan's "Read me first" before touching anything. The rules that
matter most: never trust a report of success, every claim needs a file you can
open, every phase ends with an adversarial pass.

## Current phase

**Phase 4 — facts engine.** Partially landed; gate 1 is 3 of 4 and the residual
is named in the plan. The immediate next step is small and specific: dump the
facts engine's structure-set list and the live calculator's side by side for
`the_boneyard` and find the entry that differs (`friendsandfoes:citadel` is in
one and not the other). Positions already match exactly, so only the set list
is in question.

Then: gate 3 (a reader, for round-trip serialisation), a JUnit test per fact
computation, and the five facts not yet built (spawn buildability, contact
perimeter, traversability, landmark line-of-sight, progression reachability) —
all of which need block-level column probes rather than the heightmap.

**Phase 3 — make `wants` real.** Changes 1 and 2 of 3 landed. Phases 1 and
2 are done; their gates, and the conditions that were missed, are recorded in
the plan's own phase sections with the artefact paths.

Change 1 (`wants` implies pool membership) took `want_not_in_pool` from 135 to
10, put positions in the census for wants that had none on any seed, and
`/customdim occupant` confirms `structory_towers:wizard_tower` physically
occupies chunk (-63, -32) of `the_gauntlet`. Change 2 (`clearSpawnRadius` as a
placement exclusion) puts zero dungeons or endgame inside the disc across four
dimensions, with `the_gilded_pit`'s settlements at 2.2 chunks as the control.

Change 3 is **deferred, with reasons written into the plan**: a radial curve is
per-GROUP, so bending it to satisfy one want moves every structure in that
group. The mechanism with the right granularity is `StructurePick`, not the
field — and that is a mirror change, so it is cheaper after Phase 4 deletes the
mirror. Read the plan's Phase 3 section before picking it up.

Both adversarial passes are done and their findings are fixed and re-verified
(pass-through wants, flat generators, probe coverage). One item needs a HUMAN
decision, not code: `the_frozen_strait` and `the_dustbowl` have config seeds
that do not match their live worlds — wipe or revert, see the plan's Phase 1
adversarial section.

### Phase 1 and 2 artefacts

| Thing | Where |
| --- | --- |
| `/customdim lint` | `.../command/DimensionLint.java`, `dimension/StructureWants.java`, `dimension/StructureAliases.java` |
| Lint checker | `scripts/check-dimension-lint.py` |
| Dead-want oracle (142) + its script | `~/Projects/elfydd/reports/dead-wants-oracle.json`, `dead-wants-audit.py` |
| Lint report at the gate | `~/Projects/elfydd/reports/lint-report-phase2.json` |

### Phase 1 artefacts

| Thing | Where |
| --- | --- |

| Thing | Where |
| --- | --- |
| `SpikeSampler` (headless rig, live rig, sampling, probe coords) | `mods/custom-dimensions/src/main/java/com/customdimensions/command/SpikeSampler.java` |
| `spike-sample` / `spike-compare` / `spike-bench` commands | `.../command/SpikeCommands.java` |
| `DimensionManager.buildOptionsHeadless` | `.../dimension/DimensionManager.java` |
| Offline checker | `scripts/check-spike-parity.py` |
| Artefacts | `~/Projects/elfydd/data/config/custom-dimensions/spike/` |

### How to reproduce the gate run

```bash
cd mods/custom-dimensions && mise exec -- ./gradlew build
cp build/libs/customdimensions-0.0.0-local.jar ~/Projects/elfydd/data/mods/customdimensions.jar
docker restart mc            # never ./dev up — it reinstates the bundle jar
docker exec mc sh -c 'touch /data/.skip-pause'
for d in the_gauntlet the_boneyard the_abyssal_shrine; do
  docker exec -i mc rcon-cli "customdim load $d"; done
# three seeds each, one of them the dimension's own so the live NoiseConfig answers
docker exec -i mc rcon-cli "customdim spike-compare the_gauntlet 12345 20 4000"
docker exec -i mc rcon-cli "customdim spike-bench the_gauntlet 1000"
./scripts/check-spike-parity.py --spike ~/Projects/elfydd/data/config/custom-dimensions/spike \
  --expect-dimensions 3 --expect-seeds 3 --expect-columns 20
```

### Gate status

Recorded against the artefacts, not against a memory of them. See the phase
section of the plan for the amendments.

## Test dimensions

| Dimension | Type | Why |
| --- | --- | --- |
| `the_gauntlet` | `multi_biome` | the overworld router — Tectonic + Terralith, the expensive case |
| `the_boneyard` | `nether` | ceilinged, a different router |
| `the_abyssal_shrine` | `paradise_lost:paradise_lost` | a third-party mod's dimension cloned by id |
