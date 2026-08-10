package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pure comparison behind the boot-time drift warning: creation-time
 * fingerprint plus current config fields in, drifted field names out.
 */
class DimensionFingerprintsTest {

    private static Map<String, String> fingerprint(String type, String noiseSettings, String biomes) {
        Map<String, String> f = new HashMap<>();
        f.put("type", type);
        f.put("noiseSettings", noiseSettings);
        f.put("biomes", biomes);
        f.put("checkerboardScale", "null");
        f.put("layers", "null");
        f.put("flatBiome", "null");
        f.put("settingsOverrides", "null");
        f.put("biomeParameters", "null");
        f.put("biomePatches", "null");
        return f;
    }

    @Test
    void noDriftWhenEveryWorldgenFieldMatches() {
        Map<String, String> stored = fingerprint("nether", "adventure:wide", "minecraft:crimson_forest");
        Map<String, String> current = fingerprint("nether", "adventure:wide", "minecraft:crimson_forest");
        assertEquals(List.of(), DimensionFingerprints.driftedFields(stored, current));
    }

    @Test
    void oneFieldDrifted() {
        Map<String, String> stored = fingerprint("nether", "adventure:wide", "minecraft:crimson_forest");
        Map<String, String> current = fingerprint("overworld", "adventure:wide", "minecraft:crimson_forest");
        assertEquals(List.of("type"), DimensionFingerprints.driftedFields(stored, current));
    }

    @Test
    void severalFieldsDrifted() {
        Map<String, String> stored = fingerprint("nether", "adventure:wide", "minecraft:crimson_forest");
        Map<String, String> current = fingerprint("overworld", "adventure:compressed", "minecraft:plains");
        assertEquals(List.of("type", "noiseSettings", "biomes"),
                DimensionFingerprints.driftedFields(stored, current));
    }

    @Test
    void anOrphanIsAFingerprintWithNoMatchingConfigEntry() {
        assertEquals(List.of("the_ghost"),
                DimensionFingerprints.orphans(List.of("the_ghost", "the_glasswood"), List.of("the_glasswood")));
    }

    @Test
    void noOrphansWhenEveryFingerprintHasAConfigEntry() {
        assertEquals(List.of(),
                DimensionFingerprints.orphans(List.of("the_glasswood"), List.of("the_glasswood", "the_other")));
    }
}
