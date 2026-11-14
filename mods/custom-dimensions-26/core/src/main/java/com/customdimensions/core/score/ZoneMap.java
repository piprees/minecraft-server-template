package com.customdimensions.core.score;

import java.util.List;
import java.util.Set;

/** Which zone a column belongs to, for scoring how much of each zone reads as intended. */
public interface ZoneMap {

    /** A zone that sets biomes; a zone that sets none is not scored. */
    record Zone(String id, Set<String> biomes) {}

    List<Zone> zones();

    /** The index in {@link #zones()} of the zone holding the column at full strength (inside its blend), or -1. */
    int zoneAt(int x, int z);

    ZoneMap NONE = new ZoneMap() {
        @Override
        public List<Zone> zones() {
            return List.of();
        }

        @Override
        public int zoneAt(int x, int z) {
            return -1;
        }
    };
}
