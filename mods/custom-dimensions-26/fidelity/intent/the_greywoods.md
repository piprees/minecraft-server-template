# the_greywoods — noise:overworld; place kind: place (one gloomy wood)
> "Gloomy cedar woodland where the fog sits low. The witches' territory — graveyards, chapels, and a manor that should probably be left alone. They came from the darkpine depths and brought their knowledge with them."

- **First:** a graveyard in cedar and fir under low grey fog (wants graveyard near_spawn).
- **Mostly:** cedar thicket and fir forest, dark birch, swamp. **Rarely:** moonlight grove, fungal fen; the manor near the border.
- **Layers:** surface woodland; infested caves and deep dark below.
- **Atmosphere:** fogColor #8B968B, ambientLight 0.1 (kept).
- **Place kind:** one place: one gloomy wood.
- **Baseline wrong (W9g b1 full):** an ocean takes the largest share (`surface.biomesPercent.minecraft:ocean` 39.5; water tops 46.7%); cedar is missing (no cedar-like trees: birch 27.5/ha, pale oak 15.2 lead; sugi_forest 10.7% stands in); dying_forest listed but unplaced; flagged "snowing climate but snow cover under 5%".
- **v2 responsible:** overworld preset with a coast; no ocean listed, so the sea takes vanilla ocean (W9g roles); weights birch_forest 19.4 highest.
