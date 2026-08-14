package com.customdimensions.dimension;

import com.customdimensions.dimension.StructurePoolRecord.Entry;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pool record is what lets the seed roller tell a Village from any-old
 * settlement. Its contract is narrow and almost entirely about the SHAPE of
 * the JSON it produces.
 *
 * Pure logic, no Minecraft Bootstrap: the registry lookup that turns a
 * RegistryEntry into an id lives at the DimensionStructures call site, which
 * already holds a registry, precisely so this stays testable.
 */
class StructurePoolRecordTest {

    private static final Gson GSON = new Gson();
    private static final String HEADER = "{\n \"schemaVersion\": 1,\n";

    @BeforeEach
    void reset() {
        StructurePoolRecord.reset();
    }

    private static Map<String, List<Entry>> pool(String group, Entry... entries) {
        Map<String, List<Entry>> byGroup = new LinkedHashMap<>();
        byGroup.put(group, List.of(entries));
        return byGroup;
    }

    private static JsonObject dump() {
        return GSON.fromJson(StructurePoolRecord.toJson(HEADER), JsonObject.class);
    }

    @Test
    void anEmptyRecordIsStillValidJson() {
        assertEquals(0, StructurePoolRecord.size());
        JsonObject doc = dump();
        assertEquals(1, doc.get("schemaVersion").getAsInt());
        assertEquals(0, doc.getAsJsonObject("dimensions").size());
    }

    @Test
    void weightsSurviveTheRoundTrip() {
        StructurePoolRecord.record("the_test", pool("settlements",
                new Entry("minecraft:village_plains", 8),
                new Entry("explorify:farmstead", 3)));

        JsonObject doc = dump();
        JsonObject weights = doc.getAsJsonObject("dimensions")
                .getAsJsonObject("the_test").getAsJsonObject("settlements");
        assertEquals(8, weights.get("minecraft:village_plains").getAsInt());
        assertEquals(3, weights.get("explorify:farmstead").getAsInt());
        // The header is preserved verbatim, so the artefact stays versioned.
        assertEquals(1, doc.get("schemaVersion").getAsInt());
    }

    @Test
    void aStructureInTwoSetsHasItsWeightsSummed() {
        // Vanilla's weighted draw sees the total, so the record has to as well —
        // recording them separately would under-count the structure's share.
        StructurePoolRecord.record("the_test", pool("loot",
                new Entry("minecraft:igloo", 5),
                new Entry("minecraft:igloo", 4)));

        assertEquals(9, dump().getAsJsonObject("dimensions").getAsJsonObject("the_test")
                .getAsJsonObject("loot").get("minecraft:igloo").getAsInt());
    }

    @Test
    void anEmptyGroupIsRecordedRatherThanOmitted() {
        // "this dimension has no maritime structures" gives every maritime want
        // a share of 0, which is a real answer. Omitting it would be
        // indistinguishable from "not measured yet", which means share 1.0 —
        // the opposite conclusion.
        StructurePoolRecord.record("the_test", pool("maritime"));

        JsonObject dim = dump().getAsJsonObject("dimensions").getAsJsonObject("the_test");
        assertTrue(dim.has("maritime"));
        assertEquals(0, dim.getAsJsonObject("maritime").size());
    }

    @Test
    void reloadingADimensionReplacesItsRecord() {
        StructurePoolRecord.record("the_test", pool("loot",
                new Entry("minecraft:igloo", 5)));
        StructurePoolRecord.record("the_test", pool("loot",
                new Entry("minecraft:desert_pyramid", 2)));

        JsonObject weights = dump().getAsJsonObject("dimensions")
                .getAsJsonObject("the_test").getAsJsonObject("loot");
        assertEquals(1, StructurePoolRecord.size());
        assertFalse(weights.has("minecraft:igloo"), "the stale pool must not linger");
        assertEquals(2, weights.get("minecraft:desert_pyramid").getAsInt());
    }

    @Test
    void severalDimensionsAndGroupsDumpAsValidJson() {
        for (int i = 0; i < 5; i++) {
            Map<String, List<Entry>> groups = new LinkedHashMap<>();
            groups.put("deco", List.of(new Entry("structory:ruin_grassy", i + 1)));
            groups.put("loot", List.of(new Entry("minecraft:igloo", 1)));
            StructurePoolRecord.record("dim_" + i, groups);
        }
        JsonObject dims = dump().getAsJsonObject("dimensions");
        assertEquals(5, StructurePoolRecord.size());
        assertEquals(5, dims.size());
        assertEquals(3, dims.getAsJsonObject("dim_2").getAsJsonObject("deco")
                .get("structory:ruin_grassy").getAsInt());
        assertEquals(1, dims.getAsJsonObject("dim_4").getAsJsonObject("loot")
                .get("minecraft:igloo").getAsInt());
    }

    @Test
    void nullsAreIgnoredRatherThanRecordedAsHoles() {
        StructurePoolRecord.record(null, pool("deco", new Entry("a:b", 1)));
        StructurePoolRecord.record("the_test", null);
        assertEquals(0, StructurePoolRecord.size());
    }
}
