package com.customdimensions.core.render.map;

/**
 * A biome's tints as the client draws them: grass, foliage and dry foliage
 * from its override or else the colormap at its temperature and downfall,
 * grass then passed through its modifier, and its water colour. The swamp
 * modifier's two colours alternate by noise in the game; the map takes their
 * mean.
 */
public record BiomeTint(int grass, int foliage, int dryFoliage, int water) {
    public enum GrassModifier { NONE, DARK_FOREST, SWAMP }

    /** The swamp modifier's mean colour (its noise picks 0x4C763C or 0x6A7039). */
    public static final int SWAMP_GRASS = 0x5B733A;
    /** Plains: temperature 0.8, downfall 0.4, the default water colour. */
    public static final BiomeTint PLAINS = of(MapPalette.vanilla(), 0.8f, 0.4f, null, null, null, 0x3F76E4, GrassModifier.NONE);

    public static BiomeTint of(MapPalette palette, float temperature, float downfall, Integer grassOverride, Integer foliageOverride,
                               Integer dryFoliageOverride, int water, GrassModifier modifier) {
        int grass = grassOverride != null ? grassOverride : palette.colormap("grass", temperature, downfall);
        grass = switch (modifier) {
            case NONE -> grass;
            case DARK_FOREST -> ((grass & 0xFEFEFE) + 0x28340A) >> 1;
            case SWAMP -> SWAMP_GRASS;
        };
        int foliage = foliageOverride != null ? foliageOverride : palette.colormap("foliage", temperature, downfall);
        int dry = dryFoliageOverride != null ? dryFoliageOverride : palette.colormap("dry_foliage", temperature, downfall);
        return new BiomeTint(grass & 0xFFFFFF, foliage & 0xFFFFFF, dry & 0xFFFFFF, water & 0xFFFFFF);
    }
}
