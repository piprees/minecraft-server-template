# M12 state

Branch `worktree-agent-a8eafc2c6921aa92d`, worktree `.claude/worktrees/agent-a8eafc2c6921aa92d`.

Done and committed: core map package with tests; `/customdim map export`; explored layer; site manifest and viewer
shell (in the mod jar); messages; idle-tasks map stage (cutover copy); bench `map` mode with `-PfastBenchRegions`;
lane script `map-lane.sh`; retirement step 7b in the kit (clean-clone apply.sh rc 0); README section.

Measured (bench b2, 3 threads, server lane busy): the_underdark r4096 65 s, 595 tiles, 11.5 MB; the_aviary r4096 136 s,
510 tiles, 10.8 MB; second run skips by fingerprint in 1 ms.

Next: overworld r8192 (b2), b3 with the copied overworld regions (seed 20261022) for the explored layer, final
screenshots, full build, quick gate, report.

Trap drafts (from T290):
- T290 Biome.ClimateSettings is private in 26.3; an accessor mixin returning it does not compile. Read temperature and
  downfall through Biome.NETWORK_CODEC.
- T291 On a server, grass and foliage colours from Biome are not usable: the colormaps are filled by the client's
  resource reload. The map carries its own colormap samples.
- T292 A model's tintindex does not say what the client tints (cherry and pale oak leaves carry one, are untinted);
  fluids have no elements. Tint only the listed blocks.
- T293 26.x model textures may be objects ({"sprite": ...}), not strings.
- T294 A roofed world seen from above is its roof: open the map on the floor (Roofless).
