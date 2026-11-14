# M12 state

Branch `worktree-agent-a8eafc2c6921aa92d`, worktree `.claude/worktrees/agent-a8eafc2c6921aa92d`.

Done and committed: core map package with tests; `/customdim map export`; explored layer; site manifest and viewer
shell (in the mod jar); messages; idle-tasks map stage (cutover copy); bench `map` mode with `-PfastBenchRegions`;
lane script `map-lane.sh`; retirement step 7b in the kit (clean-clone apply.sh rc 0); README section.

Measured (bench b2, 3 threads, server lane busy): the_underdark r4096 65 s, 595 tiles, 11.5 MB; the_aviary r4096 136 s,
510 tiles, 10.8 MB; second run skips by fingerprint in 1 ms.

Overworld r8192 513 s, 1705 tiles, 40.8 MB. Explored on real 26.3 chunks (copied from the server lane's world): fixed
the string-palette read (T295). Screenshots in shots/. Full build green (core 748, compiler 578, server 183); quick gate
exit 0; clean-clone apply.sh --no-network exit 0. Finished: report delivered.

Trap drafts (from T290):
- T290 Biome.ClimateSettings is private in 26.3; an accessor mixin returning it does not compile. Read temperature and
  downfall through Biome.NETWORK_CODEC.
- T291 On a server, grass and foliage colours from Biome are not usable: the colormaps are filled by the client's
  resource reload. The map carries its own colormap samples.
- T292 A model's tintindex does not say what the client tints (cherry and pale oak leaves carry one, are untinted);
  fluids have no elements. Tint only the listed blocks.
- T293 26.x model textures may be objects ({"sprite": ...}), not strings.
- T294 A roofed world seen from above is its roof: open the map on the floor (Roofless).
- T295 26.3 saves a section's block-state palette as state strings ("minecraft:oak_stairs[waterlogged=true]"), not
  compounds; a compound-only reader sees every block as air.
