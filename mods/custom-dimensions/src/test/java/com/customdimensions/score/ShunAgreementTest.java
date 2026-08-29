package com.customdimensions.score;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.dimension.NoisePoolBuilder;
import com.customdimensions.dimension.StructureWants;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scorer marks a seed down for a shunned structure and the pool builder
 * lowers that structure's draw weight. Both must read the same field, or the
 * roller spends seeds searching for something the world is no less likely to
 * make — the defect shape of T40.
 */
class ShunAgreementTest {

    private static final Gson GSON = new Gson();

    private static DimensionConfig config(String json) {
        DimensionConfig config = GSON.fromJson(json, DimensionConfig.class);
        config.setName("the_test");
        return config;
    }

    private static Set<String> scoredShunIds(DimensionConfig def) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Criterion c : Criteria.forConfig(def)) {
            String key = c.id();
            if (key.startsWith("shuns:")) {
                String id = com.customdimensions.dimension.StructureAliases
                        .resolve(key.substring("shuns:".length()));
                if (id != null && !id.startsWith("#")) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    @Test
    void theScorerAndThePoolBuilderShunTheSameStructuresViaSeedRoll() {
        DimensionConfig def = config(
                "{\"type\": \"nether\", \"seedRoll\": "
                + "{\"shuns\": [\"monument\", \"mansion\", \"village\"]}}");
        assertEquals(scoredShunIds(def), NoisePoolBuilder.shunnedStructureIds(def));
        assertTrue(NoisePoolBuilder.shunnedStructureIds(def).contains("minecraft:monument"));
    }

    @Test
    void theScorerAndThePoolBuilderShunTheSameStructuresViaTheStructuresBlock() {
        DimensionConfig def = config(
                "{\"type\": \"multi_biome\", \"structures\": "
                + "{\"shuns\": {\"monument\": {}, \"mansion\": {}}}}");
        assertEquals(List.of("monument", "mansion"), StructureWants.shunNames(def));
        assertEquals(scoredShunIds(def), NoisePoolBuilder.shunnedStructureIds(def));
        assertEquals(Set.of("minecraft:monument", "minecraft:mansion"),
                NoisePoolBuilder.shunnedStructureIds(def));
    }

    @Test
    void aStructuresShunsBlockIsScoredRatherThanIgnored() {
        // the_tidepools is the shipped config that shuns through structures.shuns.
        DimensionConfig def = config(
                "{\"type\": \"multi_biome\", \"structures\": "
                + "{\"shuns\": {\"village\": {}, \"monument\": {}}}}");
        assertTrue(scoredShunIds(def).contains("minecraft:monument"),
                "structures.shuns produced no shun criterion");
    }
}
