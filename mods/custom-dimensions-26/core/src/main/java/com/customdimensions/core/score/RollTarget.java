package com.customdimensions.core.score;

import com.customdimensions.core.definition.Definition;

/**
 * What one dimension asks of a seed: its {@link RollSpec}, its playable
 * radius and how many biomes it declares.
 *
 * @param dimension the definition's slug
 */
public record RollTarget(String dimension, RollSpec spec, int playableRadius, int declaredBiomes) {

    /** The playable radius when a definition sets no player border. */
    public static final int DEFAULT_RADIUS = 1024;

    public RollTarget {
        spec = spec == null ? RollSpec.NONE : spec;
    }

    public static RollTarget of(String dimension, Definition def) {
        Integer border = def == null || def.borders() == null ? null : def.borders().player();
        return new RollTarget(dimension, RollSpec.of(def), border == null || border <= 0 ? DEFAULT_RADIUS : border,
                def == null ? 0 : def.biomes().size());
    }
}
