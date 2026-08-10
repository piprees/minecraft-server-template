# WORKSPACE

In-flight: the seed-roll rewrite, plan at
`~/Projects/elfydd/reports/seed-roll-rewrite.md`. Branch
`feature/seed-roll-fixes` in this repo (platform); consumer under test is
`~/Projects/elfydd` with its local Docker stack.

Read the plan's "Read me first" before touching anything. The rules that
matter most: never trust a report of success, every claim needs a file you can
open, every phase ends with an adversarial pass.

## Current phase

**Phase 1 — spike: headless seeded sampling.** In progress.

### What exists

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
