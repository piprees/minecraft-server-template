# overworld — noise:overworld (base world); place kind: noise (base world, many biomes)
> "The world where it all begins. Villagers settled the plains and humans arrived through the stone gate. Safe at spawn, but the ancient cities near the border predate everyone — and something stirs in them."

- **First:** settled plains round spawn with a village in sight (1.21.1 seedRoll spawnFilter plains, meadow, forest, sunflower_plains, cherry_grove, prairie, lush_valley; wants village/tavern/campsite near_spawn).
- **Mostly:** ordinary overworld land, forests and plains, with seas at the edges. **Rarely:** ancient cities and mansions near the border.
- **Layers:** surface (land, seas), underground (vanilla caves, deep dark under the border cities). No sky, no roof.
- **Atmosphere:** vanilla day cycle; no 1.21.1 `environment`.
- **Signature structures:** village, tavern near spawn; ancient_city, mansion, arena near border (v2 `roll.wants`).
- **Place kind:** a base world: a varied overworld read as broad regions, not a scatter (AGENTS § Dimensions: rolled and scored like the others).
- **Baseline wrong (W9b, overworld/dossier.json):** water is the largest cover (`surface.fluidCoverPercent.water` 43.3, `landforms.sharesPercent.water` 46.4; top3 biomes are all oceans: lukewarm 11.8, ocean 9.4, cold 8.4); plains are placed but carry no trees and the flag reads it (`biomeExpression.biomes.minecraft:plains` "features name trees, none on 0.5 ha"), which is vanilla plains and not a defect; 16 of 24 placed biomes expressed, the 8 others being oceans and river (`biomeExpression.expressed` 16).
- **v2 responsible:** `terrain.scales` {continents 1.3, erosion 2.08, ridges 1.39, climate 1.67} is the 1.21.1 wide tune (placeness.md) and stretches oceans as much as land; there is no `layout` to keep spawn's plains a continent. No placeness figures yet (W9b predates them).
- **Conflict:** none; the 1.21.1 overworld ran Terralith and Tectonic, which v2 does not have (clean-room), so terrain character comes from vanilla routing only.
