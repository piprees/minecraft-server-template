package com.customdimensions.core.firewall;

import java.util.List;
import java.util.Set;

/**
 * One mod's bridge ({@code config/custom-dimensions/bridges/<mod>.json}):
 * what of that mod's worldgen this pack adopts. Only a {@code reviewed}
 * bridge counts; a generated one is written unreviewed for a human to tag.
 */
public record Bridge(String mod, boolean reviewed, List<String> structureSets, List<Structure> structures,
                     List<String> features, List<String> spawns, List<String> mixins, List<String> registries) {

    public static final Set<String> MOODS = Set.of("hard", "adventurous", "dramatic", "scenic", "pastoral", "serene", "desolate", "standard");
    public static final Set<String> CLIMATES = Set.of("frozen", "cold", "temperate", "warm", "hot", "arid", "wet");
    public static final Set<String> DEPTHS = Set.of("sky", "surface", "underground", "deep", "underwater");

    /** An adopted structure: its set, its measured footprint (null until measured) and our tags. */
    public record Structure(String id, String set, Size size, List<String> mood, List<String> climate, List<String> depth) {}

    /** A measured footprint in blocks; every slot is null until the structure is measured (T54). */
    public record Size(Integer x, Integer y, Integer z) {
        public static final Size UNMEASURED = new Size(null, null, null);
    }
}
