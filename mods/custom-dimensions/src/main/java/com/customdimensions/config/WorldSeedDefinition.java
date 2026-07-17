package com.customdimensions.config;

/**
 * One entry of the config's top-level "worlds" array — the dimensions that
 * exist without this mod creating them (vanilla overworld/nether/end plus
 * static mod dimensions like paradise_lost). The seed roller writes a
 * per-world "seed" here; ServerWorldSeedMixin applies it to every world
 * EXCEPT minecraft:overworld (the overworld seed IS the save seed and must
 * come from the .env SEED at world creation).
 */
public class WorldSeedDefinition {
    private String name;
    private String dimensionId;
    private Long seed;

    public String getName() {
        return this.name;
    }

    public String getDimensionId() {
        return this.dimensionId;
    }

    public Long getSeed() {
        return this.seed;
    }
}
