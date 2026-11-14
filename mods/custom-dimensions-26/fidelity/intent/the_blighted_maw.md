# the_blighted_maw — noise:end (1.21.1 nether_islands); place kind: place (one tight island cluster)
> "A toxic little hell that came apart. Piglin scientists built labs and reactors here to study the sculk infection from the boneyard; the blight won, and took the ground with it. What is left is a tight cluster of floating islands with a long fall between them. Fall off and the blight rejects you rather than killing you. Loot escalates with the danger. The blight is not finished; a build beside the portal is just ground it has not taken yet."

- **First:** a ruined lab on a netherrack island (wants ruined_lab near_spawn) in red fog.
- **Mostly:** a tight cluster of toxic Nether islands (1.21.1 toxic_heap, soul_sand_valley, withered_forest, ash_barrens, nether_wastes) with long falls between. **Rarely:** reactors, bastions.
- **Layers:** islands over void; lava default fluid, sea at -60.
- **Atmosphere:** fogColor #3B1B1B, ambientLight 0.08 (kept).
- **Place kind:** one place: a cluster round spawn, emptiness beyond.
- **Baseline wrong (W9g b1 full):** ground is End stone, not Nether: end_stone 11.5% of ground with netherrack absent from the top eight despite `materials.defaultBlock` netherrack; every biome "placed, not expressed" (`biomeExpression.expressed` 0), ash_barrens and nether_wastes 100% over void; void 72.0% with islands spread evenly, not clustered (`landforms.sharesPercent.islands` 1.4).
- **v2 responsible:** `generator` end (with the central island, vanilla's outer-island field), regions over the climate not the islands; the islands stay end stone despite the default block (measured; cause unverified).
