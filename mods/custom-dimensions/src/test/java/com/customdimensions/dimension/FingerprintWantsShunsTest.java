package com.customdimensions.dimension;

import com.customdimensions.config.DimensionConfig;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wants and shuns in the creation-time fingerprint.
 *
 * <p>They weight the noise pool, so a world generated before an edit no longer
 * matches the config that describes it. The record holds the RESOLVED ids: an
 * alias is not identity, and the band words and min/max values around a want
 * never reach the pool, so neither may register as a change.
 */
class FingerprintWantsShunsTest {

    private static final Gson GSON = new Gson();

    private static DimensionConfig config(String json) {
        DimensionConfig def = GSON.fromJson(json, DimensionConfig.class);
        def.setName("the_test");
        return def;
    }

    /** The fingerprint fields, reached the way checkExisting reaches them. */
    private static Map<String, String> fields(DimensionConfig def) {
        Map<String, String> out = new HashMap<>();
        out.put("structureWants",
                String.valueOf(new java.util.TreeSet<>(NoisePoolBuilder.wantedStructureIds(def))));
        out.put("structureShuns",
                String.valueOf(new java.util.TreeSet<>(NoisePoolBuilder.shunnedStructureIds(def))));
        out.put("type", String.valueOf(def.getType()));
        return out;
    }

    private static List<String> drift(DimensionConfig a, DimensionConfig b) {
        return DimensionFingerprints.driftedFields(fields(a), fields(b));
    }

    @Test
    void twoConfigsDifferingOnlyInAShunDriftApart() {
        assertEquals(List.of("structureShuns"), drift(
                config("{\"type\": \"multi_biome\", \"seedRoll\": {\"shuns\": []}}"),
                config("{\"type\": \"multi_biome\", \"seedRoll\": {\"shuns\": [\"monument\"]}}")));
    }

    @Test
    void twoConfigsDifferingOnlyInAStructuresShunDriftApart() {
        assertEquals(List.of("structureShuns"), drift(
                config("{\"type\": \"multi_biome\", \"structures\": {\"shuns\": {}}}"),
                config("{\"type\": \"multi_biome\", \"structures\": "
                        + "{\"shuns\": {\"monument\": {}}}}")));
    }

    @Test
    void twoConfigsDifferingOnlyInAWantDriftApart() {
        assertEquals(List.of("structureWants"), drift(
                config("{\"type\": \"multi_biome\", \"structures\": {\"wants\": {}}}"),
                config("{\"type\": \"multi_biome\", \"structures\": "
                        + "{\"wants\": {\"monument\": {\"min\": 0, \"max\": 256}}}}")));
    }

    @Test
    void twoConfigsDifferingOnlyInADescriptionDoNotDrift() {
        assertEquals(List.of(), drift(
                config("{\"type\": \"multi_biome\", \"description\": \"a\", "
                        + "\"structures\": {\"shuns\": {\"monument\": {}}}}"),
                config("{\"type\": \"multi_biome\", \"description\": \"b\", "
                        + "\"structures\": {\"shuns\": {\"monument\": {}}}}")));
    }

    @Test
    void namingOneStructureByTwoAliasesIsNotAChange() {
        assertEquals(List.of(), drift(
                config("{\"type\": \"nether\", \"seedRoll\": {\"shuns\": [\"monument\"]}}"),
                config("{\"type\": \"nether\", \"seedRoll\": "
                        + "{\"shuns\": [\"minecraft:monument\"]}}")));
    }

    @Test
    void aBandWordIsNotAChangeBecauseThePoolNeverReadsIt() {
        assertEquals(List.of(), drift(
                config("{\"type\": \"multi_biome\", \"seedRoll\": "
                        + "{\"wants\": {\"monument\": \"near_spawn\"}}}"),
                config("{\"type\": \"multi_biome\", \"seedRoll\": "
                        + "{\"wants\": {\"monument\": \"near_border\"}}}")));
    }

    @Test
    void aWantsMinMaxIsNotAChangeEither() {
        assertEquals(List.of(), drift(
                config("{\"type\": \"multi_biome\", \"structures\": "
                        + "{\"wants\": {\"monument\": {\"min\": 0, \"max\": 256}}}}"),
                config("{\"type\": \"multi_biome\", \"structures\": "
                        + "{\"wants\": {\"monument\": {\"min\": 900, \"max\": 4000}}}}")));
    }

    @Test
    void shunOrderIsNotAChange() {
        assertEquals(List.of(), drift(
                config("{\"type\": \"nether\", \"seedRoll\": "
                        + "{\"shuns\": [\"monument\", \"mansion\"]}}"),
                config("{\"type\": \"nether\", \"seedRoll\": "
                        + "{\"shuns\": [\"mansion\", \"monument\"]}}")));
    }

    // ------------------------------------------------------- record migration

    @Test
    void aRecordWrittenBeforeAFieldExistedDoesNotReportDriftOnIt() {
        // Without this every one of the 82 shipped dimensions would WARN the
        // first time it booted on a jar that fingerprints wants and shuns.
        Map<String, String> old = new HashMap<>();
        old.put("type", "multi_biome");
        Map<String, String> current = fields(config(
                "{\"type\": \"multi_biome\", \"structures\": "
                + "{\"shuns\": {\"monument\": {}}}}"));
        assertEquals(List.of(), DimensionFingerprints.driftedFields(old, current));
    }

    @Test
    void aPresentFieldStillReportsDrift() {
        Map<String, String> stored = new HashMap<>();
        stored.put("type", "multi_biome");
        stored.put("structureShuns", "[]");
        Map<String, String> current = fields(config(
                "{\"type\": \"multi_biome\", \"structures\": "
                + "{\"shuns\": {\"monument\": {}}}}"));
        assertEquals(List.of("structureShuns"),
                DimensionFingerprints.driftedFields(stored, current));
    }

    // ----------------------------------------------------------- wipe or not

    @Test
    void wantsAndShunsDriftDoesNotDemandAWipe() {
        // The pool is rebuilt from config every boot. Telling an operator to
        // reset-seed for a shun edit would destroy a world for nothing.
        assertFalse(DimensionFingerprints.needsWipe(List.of("structureShuns")));
        assertFalse(DimensionFingerprints.needsWipe(List.of("structureWants",
                "structureShuns")));
    }

    @Test
    void abakedFieldDoesDemandAWipe() {
        assertTrue(DimensionFingerprints.needsWipe(List.of("biomes")));
        assertTrue(DimensionFingerprints.needsWipe(List.of("structureShuns", "type")));
        assertFalse(DimensionFingerprints.needsWipe(List.of()));
    }

    @Test
    void theResolvedIdSetIsWhatIsRecordedNotTheRawBlock() {
        Map<String, String> f = fields(config(
                "{\"type\": \"nether\", \"seedRoll\": {\"shuns\": [\"monument\", \"village\"]}}"));
        // village is #minecraft:village, a tag, which the pool drops.
        assertEquals("[minecraft:monument]", f.get("structureShuns"));
        assertNotEquals("[monument, village]", f.get("structureShuns"));
    }
}
