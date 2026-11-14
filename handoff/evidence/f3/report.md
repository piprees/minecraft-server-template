F3b is done: `/customdim fastrender <dimension> wave [threads]` and `/customdim fastrender all wave [threads]` work, the full build is green and the quick gate exits 0. All timings below come from the headless bench while the server lane's game server was using about 1.3 of the 4 cores. Identical runs differed by up to 2×, so the numbers are ranges, not points.

**Commits:** 41423c15 (the feature) and 2c907816 (a bench label fix), on branch `worktree-agent-a9aa46c12d0a3b106` in `/home/user/minecraft-server-template/.claude/worktrees/agent-a9aa46c12d0a3b106`. Not pushed. Changes to `ColumnRuns`, `LatticeScanner`, `TileSampler` and `ColumnModel` only add methods, so they should merge cleanly with F4. `RunDress` and `LatticeInterpolation` are untouched.

**Commands**
- `/customdim fastrender <dimension> wave [threads]` covers the larger of the player and generation borders (default radius 1024). It writes to `renders/<dim>/wave-<seed>/` and replies after each pass.
- `/customdim fastrender all wave [threads]` does every dimension one at a time in one job. A dimension that fails is reported and the rest carry on.
- Bench: `./gradlew --max-workers=1 --offline :server:runFastBench -PfastBench=the_underdark:2070195577671787983:4096:wave:4,the_aviary:7826077224469180302:4096:wave:4,overworld:4705305050088502262:8192:wave:4 -PfastBenchOut=<dir>`

**Where the code is**
- Pass maths: `core/.../render/WavePlan.java:31` (radius) and `:43` (passes).
- Slice heights: `core/.../render/SliceChoice.java:69` (choice) and `:174` (sky bands).
- Contact sheet: `ContactSheet.java`, drawn with `TinyFont.java`, a built-in 5×7 font because a headless server may have no system fonts.
- Wave runner: `server/.../render/fast/FastWave.java:71`.
- Slice pixels: `FastSlices.java` and `FastRender.java:435` (`slice`).
- Render options: `FastRender.java:147`.
- Floor-first scan: `FastRender.java:372`, its self-check `:595`, and the window mode in `ScanWindow.java:146`.
- One-row density sample: `LatticeScanner.densityRow` and `TileSampler.solidRow`.
- Command: `FastRenderCommand.java:71` to `:89`. Bench: `FastBench.java:186`.
- Five new messages in `config/messages.json`, the cutover copy and `Messages.java`.

**The slice set**
- Every world: `sea-level`, at the settings' sea level − 1. At sea level itself every ocean read as open sky, because the sea fills up to sea level − 1.
- Surface worlds: `caves`, halfway between the median surface and deep caves, and `deep-caves`, at the noise floor + 16. Both only when the median surface stands 48 blocks above the noise floor.
- Roofed worlds: `ceiling`, at the roof's median underside, plus `caves` between the floor and that underside, and `deep-caves`.
- Sky worlds: `sky-N`, the median of each band of run middles from the pre-scan, top first, at most four.
- Every height comes from one pre-scan, never from the dimension's name.
- A slice draws solid ground by block colour, a fluid only where nothing solid is over it or the level has no aquifers, open space under ground in black and open sky in slate.
- Each slice also gets a biome image where the biome at that height differs from the surface biome.

**Final stride: two passes, ending at 1024 px across** (stride 16 for the overworld, 8 for the r4096 dimensions). With a third pass to 2048 px, the whole wave took 203 s for the overworld, 158 s for the_aviary and 102 s for the_underdark, so it broke the 2-minute limit.

| Dimension | Time to the first whole-world wave (pass 1 with slices, plus pre-scan) | Whole wave |
| --- | --- | --- |
| the_underdark | 3.6–8.2 s | 16.6–33 s |
| the_aviary | 7.6–11.3 s | 40–59 s |
| overworld | 9.1–15.9 s | 41–68 s |

- Pass 1 is about 10 s for each dimension; it misses that target on a contended run for the overworld.
- Slices cost 0.2–0.6 s of pass 1 on the two sky and cave worlds, where they are read from runs already scanned. On the overworld they cost about 1.5–3 s, because two heights lie outside the scan window and need their own density row; slice biome lookups are about half of that.
- To reach that, the pre-scan spacing became four first-pass strides, and batches shrank to 8×8 columns at strides of 16 or more (the overworld's mean window went from 138 to 115 blocks).

**The owner's cheap height guess for roofed worlds (floor-first), measured on the_underdark against whole columns at stride 16**

| Start height | Floors within one block | Notes |
| --- | --- | --- |
| 26 (roof thickness at the 0.9 quantile) | 23 % | Floors reaching the start were taken as roof |
| 94–96 (median roof thickness − 8) | 86.5–87 % | |
| 110 | 99.93 % | |
| 126 (rule now in the code) | 100 % | Agreement on the pre-scan's own columns: 1.0 |

- Where it goes wrong:
  - **Stalactites:** roof bulges below the start never broke it; they are taken as roof and dropped.
  - **Floors reaching the roof:** this is what failed. A floor reaching the start was lost; 1.3 % of columns were solid all the way and lost their floor until the fix below.
- The rule now in the code starts the scan at the higher of (noise top − median roof thickness − 8) and (the floors' 0.99 quantile + 8).
- A column solid from the start down to the noise floor is now read as solid to the noise top.
- Floor-first runs on pass 1 only when the pre-scan's own columns keep their floor within one block in at least 95 % of cases. It does on the_underdark; `-Dcustomdimensions.fastrender.floorfirst=off` turns it off.
- **It saves little here:** 1.06–1.3× (window 187 against 192 blocks), within noise. This roof is thin and the floors reach close to it. It does give the floor map directly.
- Sky worlds already scan from 16 blocks over the highest run near each tile, which is at or under the top tier, so there was nothing further to cut.

**Contact sheets (I looked at each)**
- `/tmp/claude-0/f3/bench/b4/overworld/wave/pass2-s16-sheet.png`: the map, biomes and hillshade read clearly. The sea-level slice shows land against the sea. Caves at Y 7 are mostly stone with dripstone patches. Deep caves at Y −48 show deep_dark patches in their biome image.
- `/tmp/claude-0/f3/bench/b4/the_aviary/wave/pass1-s16-sheet.png`: islands, tier 1 and tier 2. Sky slices at 205 and 131 show two distinct island tiers, matching the definition's 2 tiers 64 apart. Hanging gardens dominate the biome at 131.
- `/tmp/claude-0/f3/bench/b3/the_underdark/wave/pass1-s16-sheet.png`: floor-first, so the floor replaces the uniform roof. Slices at the ceiling (105), caves (84), sea level and Y −48 (nearly solid with lava specks). Biomes do not vary with height.

**Tests:** core 719 → 733 (14 new: radius and passes at odd borders, slice choice for surface, roofed, sky and void worlds, band splitting, the sheet). Server 160 → 163 (floor window, the roof rule, the new `ColumnRuns` helpers). Compiler 559, unchanged.

**Other things I changed**
- `config/messages.json` and the cutover copy were re-recorded with `mkshas.sh`, and `./cutover/apply.sh --no-network` ran clean from a fresh clone.
- `mods/custom-dimensions-26/README.md` had a stale second copy of the Sampler and fast-renderer sections; I deleted it. The new Waves section sits under the current fast-renderer section.
- `docs/design/rebuild-26/roller.md` has a new row for the wave commands.

**One-boot server lane script:** `handoff/evidence/f3/wave.sh` (ShellCheck clean). Run it as `P=<checkout> wave.sh <tag> [threads]`. It boots with seed 20261022, runs `customdim fastrender all wave`, waits up to 3 h and copies the waves to `/tmp/claude-0/f3/lane/<tag>/`. Footprints only appear in a real server; the bench has no structure templates.

**Trap drafts (T-F3)**
- **T-F3a:** a floor-first scan that starts under the roof's thickness cuts into tall floors (y 26 under a median floor of 64 kept 23 %). Start over the floors' 0.99 quantile, read a column solid down to the noise floor as solid to the noise top, and gate it on the pre-scan's own columns agreeing at least 95 %.
- **T-F3b:** a slice at `sea_level` shows open sky over every ocean, because the sea's top block is sea level − 1. Slice at sea level − 1.
- **T-F3c:** solid coverage per height smears an island world's tiers into one band. Count run middles and split at a dip to 0.85 of the peaks either side. That 0.85 is tuned on one sky world (the_aviary), so it may not hold for others.
- **T-F3d:** with aquifers, the global fluid picker floods every cave below sea level. Under ground, draw open space as a cave and use the picker only under open sky or in a world without aquifers.
- **T-F3e:** headless bench timings swing 1.5–2× between identical runs while the server lane runs (load about 7 on 4 cores). Compare only within one run.

Benches are in `/tmp/claude-0/f3/bench/` (b1–b4; b4 is the final code); the log is `/tmp/claude-0/f3/progress.log`.
