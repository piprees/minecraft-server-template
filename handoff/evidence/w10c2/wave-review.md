# Whole-world waves of all 82 (fastrender all wave 4, seed 20261022, 1706 s, 0 failed)

Full output (481 MB, every pass's map, biome, hillshade, tiers, slices and structures): /tmp/claude-0/f3/lane/lane1/waves/<dimension>/wave-<seed>/.
Final-pass contact sheets, 1100 px: handoff/evidence/w10c2/wave-sheets/<dimension>.jpg. Montages of every map: /tmp/claude-0/w10c2/wave-sheets/all-maps-{1,2}.jpg.

Looked at in full: overworld, the_aviary, the_underdark (bench), the_blackstone_keep (roofed), the_end (End family), the_slatemouth (void); every other dimension on the two montages.

What looks wrong:
- overworld: the tier1 and tier2 panels are speckle (a surface world's lower runs are cave roofs and overhangs); they show nothing a reader can use.
- the_underdark: the withered forest place has a long straight edge on its east side.
- the_blackstone_keep: the basin place is an inverted triangle with straight sides; its warp (38 blocks at 256) is too small to bend edges over a 1024 radius.
- the_end: the structures panel shows no End city markers.
- the_slatemouth, the_icebound_rift, the_red_monument (void generators): the map and structures panels are black. Empty ground is right for a void world, but the forced builds that make each place do not show, so the sheet shows nothing of it.
- the_teal_corruption, the_twisted_groves, the_starwell, the_crimson_nexus: perfect circles on the map (zone discs without edge jitter).
- Roofed sheets lead with the roof-off floor; their plain map (the roof) is grey and carries nothing.
