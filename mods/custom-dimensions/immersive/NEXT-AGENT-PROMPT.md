# Kickoff prompt — Structure Noise, phase 2 (F2 / F5 / G1 / G2 / E1)

Copy everything below the line into a fresh agent.

---

Read this spike in full (`~/Projects/minecraft-server-template/mods/custom-dimensions/immersive/SPIKE-STRUCTURE-NOISE-IMPLEMENTATION.md`) **and its implementation log (`NOISE-IMPL-LOG.md`, same directory — it records six real bugs, every deviation from the spike, and two decisions I have already made that you must not relitigate)**, then `~/Projects/minecraft-server-template/AGENTS.md`, `~/Projects/minecraft-server-template/README.md`, `~/Projects/minecraft-server-template/TROUBLESHOOTING.md`, and `~/Projects/minecraft-server-template/mods/AGENTS.md` (the architecture tree plus the new "Noise structure placement (2026-07-26)" section — those bullets are invariants that are easy to break silently).

Noise-based structure placement is **already built and shipped into the mod**. Phases A–D are done, E2/F1/F3/F4/G3 are done, all gates are green: 472 Java tests, 269 Python tests, `./scripts/test-scripts.sh --quick`. The Java↔Python parity gate (F4) passes exactly — 5 dimensions, 17 groups, 2383 positions, zero divergence.

**Your job is the seed roller half plus the live regression suite: F2, F5, G1, G2, and finishing E1.** Work them in that order; F2 unblocks F5 and G1, and G2/E1 share a prerequisite.

## The one thing to understand before you start

Structure positions are no longer a vanilla grid the roller re-derives from spacing/separation/salt. Every managed dimension now gets a **complete census**: `scripts/seed/noise_placement.py` mirrors the mod's placement maths bit-for-bit, and `noise_census(world_seed, dim_name, dim_config, type_defaults)` returns `{group: [(chunk_x, chunk_z), ...]}` for the whole playable radius.

That is what F2 is for. The roller still scores structures with `want_score(nearest_dist)` in `score-dimensions.py`, which is the formula the concept spike calls broken — it penalises a structure for being at its natural distance. Replace it with distribution matching against the dimension's radial curves, per the spike's F2 section.

## Order of work

1. **F2** — `distribution_match()` replacing `want_score()`. Bin census positions by radial decile, compare to the desired curve (cosine similarity), add the count-satisfaction bonus. Keep `seedRoll.wants`/`shuns` as existence/absence checks. Tests in `scripts/seed/test_score_dimensions.py`.
2. **Chunky pre-generation** of the G2 test dimensions. This is the prerequisite for both G2 and the rest of E1 — see "RCON" below.
3. **E1 (finish)** — the `/locate` batteries, against pre-generated chunks.
4. **G2** — the 10-dimension regression suite.
5. **F5** — `./dev seed-rescore`, before/after comparison.
6. **G1** — full `./dev seed-roll --count 50` pass.
7. Update `.claude/skills/seed-rolling/SKILL.md` with the census **scoring** model. It currently documents the census and the fingerprint change but still describes nearest-distance scoring, because F2 did not exist when it was written.

## Two decisions already made — do not revisit

**1. `the_end_citadel` taking ~2.5 s to build its placements is ACCEPTED.** 8192 border × `dense` × 5 groups = 62,556 positions, against the spike's 200 ms target. It logs a warning, runs once per world load, and is off the tick loop. Do **not** cap positions, shrink `MAX_RADIUS_CHUNKS`, or raise exclusion for large dimensions — all three change worldgen to fix a number nobody is paying for. The counts are already conservative: 39,570 `deco` placements replace 144 structure sets vanilla would each place every 20–30 chunks, so noise `deco` is *sparser* than the grid it replaces.

**2. RCON slowness is pre-existing and out of scope.** A bare synchronous `/locate` into an ungenerated custom dimension does not return on this hardware and can wedge RCON while the container stays healthy (no crash — the game log just stops advancing; recover with `docker stop -t 90 mc && docker start mc`). This is not a placement bug: `the_dustbowl` runs the untouched `FixedStructurePlacement` path with exactly one placement in the whole world and is equally slow. Don't investigate it. Chunky pre-generation is the fix, which is why it is step 2 above.

## Things that will bite you

- **Expect a DRIFTED wave.** 73 shipped dimensions gained a `noisePlacement` fingerprint key and will report DRIFTED on `./dev seed-status`. That is correct — noise genuinely changed their worlds, and only a re-roll produces valid measurements. 5 suppressed dimensions and the 4 base worlds keep byte-identical fingerprints. Do not "fix" the drift.
- **`borders.player` and `difficulty.mobMultiplier` are now generation-affecting.** They used to be scoring/runtime only. The border sets both the scanned radius and the noise frequency scale; the multiplier drives the peaceful/hostile group shifts.
- **The Python mirror must stay bit-exact.** If you touch `StructureNoise.java`, `NoiseFieldIndex.java` or `noise_placement.py`, change both and re-run `python3 -B -m unittest discover -s scripts/seed -p 'test_noise_parity.py'`. The translation rules that actually bite (unsigned longs, unsigned rank comparison, `Math.round` vs banker's rounding, doubles never floats) are in that module's docstring.
- **Not every structure is noise-placed.** 155 of ~280 sets have a custom placement type (YUNG's, and everything Cristel Lib rewrites at runtime — explorify, towns_and_towers) and keep grid placement. A `groups=4/5` boot line is also normal: a group whose pool is empty after biome filtering is skipped.
- **`/customdim structure-audit` and `/customdim structure-census <dim>` write files** and return only a summary — RCON concatenates feedback lines with no separator and truncates at a few KB.

## Testing

Use `~/Projects/elfydd` as the local consumer for verification. **Do not push to elfydd** — it triggers a production deploy. Work locally and patiently: boots take ~2–3 minutes, so batch your RCON checks into one pass rather than restarting per assertion.

Follow the local loop in `mods/AGENTS.md` § Verification loop. The two traps that will waste your time: never use `./dev up` to test a local mod build (it overwrites your jar with the bundle's), and re-patch c2me's `useDensityFunctionCompiler` before **every** `docker stop`/`start`.

Review all of the mentioned documentation, then say "Ready to start" when you are ready.
