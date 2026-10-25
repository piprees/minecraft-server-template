package com.customdimensions.core.zone;

import com.customdimensions.core.definition.Definition;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of a definition's zones cover a column, game-free, for structure
 * control and anything else that scopes itself to a zone. The weight is the
 * mask with a still edge ({@link ZoneMask#at}); the compiled mask's jittered
 * edge lies mostly within {@link ZoneMask#jitter()} blocks of it. Biomes
 * switch to a zone where its compiled mask reaches {@link #BIOME_THRESHOLD}.
 */
public final class ZoneQuery {
    /** The mask at which a zone's biomes take over: the middle of its blend. */
    public static final double BIOME_THRESHOLD = 0.5;

    /** A zone covering a column, by its index in {@code zones}, with its weight there. */
    public record Hit(int index, ZoneSpec zone, double weight) {
        /** Whether the zone's biomes hold at the column, ignoring jitter. */
        public boolean biomesHold() {
            return weight >= BIOME_THRESHOLD;
        }
    }

    private final List<ZoneSpec> zones;

    public ZoneQuery(List<ZoneSpec> zones) {
        this.zones = List.copyOf(zones);
    }

    public static ZoneQuery of(String slug, Definition def) {
        return new ZoneQuery(ZoneSpec.all(slug, def));
    }

    public List<ZoneSpec> zones() {
        return zones;
    }

    /** Every zone whose weight at {@code (x, z)} is above zero, in zone order. */
    public List<Hit> containing(double x, double z) {
        List<Hit> out = new ArrayList<>();
        for (ZoneSpec zone : zones) {
            double w = zone.mask().at(x, z);
            if (w > 0) out.add(new Hit(zone.index(), zone, w));
        }
        return out;
    }

    /** The last zone in order whose biomes hold at {@code (x, z)}, or null: the one whose biomes a column takes. */
    public Hit biomeZone(double x, double z) {
        Hit top = null;
        for (Hit hit : containing(x, z)) {
            if (hit.zone().changesBiomes() && !hit.zone().globalSwap() && hit.biomesHold()) top = hit;
        }
        return top;
    }
}
