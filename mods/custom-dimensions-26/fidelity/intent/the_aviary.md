# the_aviary — noise:floating_islands (base world, 1.21.1 paradise_lost); place kind: meeting (plateaus and groves)
> "The oldest living world — skylands where towers and vaults grew like coral long before anyone named them. Wisteria drifts on the updrafts between plateaus. Whatever built this place wasn't human, wasn't piglin, wasn't enderman. It just grew."

- **First:** high grassy plateaus with towers (1.21.1 spawnFilter paradise_lost highlands, highlands_forest, wisteria_woods, tradewinds; wants para_remains, birdcage near_spawn).
- **Mostly:** skyland plateaus and groves separated by open sky; wisteria groves. **Rarely:** palaces near the border (v2 `mss:castle_ruin` near_border).
- **Layers:** sky (stacked islands), surface (plateau tops), undersides hung over the void (v2 lists `adventure:hanging_gardens`). No roof.
- **Atmosphere:** bright, pale (library sky_highlands "white sky", golden_skygrove "gold light"); no 1.21.1 `environment`.
- **Place kind:** a meeting of a few skyland regions; "between plateaus" needs open sky between masses.
- **Baseline wrong:** no dossier: W9b's 81 surveys omit the_aviary (`/tmp/claude-0/w9b/dossiers/` has no the_aviary). The four clones that copy its generator (violet_spire, sunken_temple, abyssal_shrine, snowbound_isle; W9g b1 full) read 89–96% of columns as land (`vertical.layerColumnsPercent.surface` 89.4–95.5, `surface.voidPercent` 4.5–10.6): skylands without much sky between them.
- **v2 responsible:** `generator` floating_islands with no `terrain`; 14 biomes each bound by `climate` (no `biomeLayout`), so it is banded, not regional.
- **Conflict:** Paradise Lost's own dimension (1.21.1) is a mod we cannot read (clean-room); "grew like coral" towers rely on structure mods the firewall keeps.
