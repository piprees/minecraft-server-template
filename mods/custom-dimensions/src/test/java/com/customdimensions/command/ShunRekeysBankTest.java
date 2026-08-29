package com.customdimensions.command;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.dimension.NoisePoolBuilder;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wants and shuns changed a world's generation, so a banked measurement taken
 * before the edit must not be reused after it.
 *
 * <p>{@link InputHash#hashOf} is the seed bank's key and it canonicalises the
 * whole config minus {@code seed}, so the wants and shuns blocks are already
 * inside it — these tests pin that rather than assume it. The other half is
 * the jar: an alias table edit changes which structure a name resolves to
 * without touching any config, and {@link InputHash#affectsMeasurement} is
 * what decides whether that is noticed.
 */
class ShunRekeysBankTest {

    private static final Gson GSON = new Gson();
    private static final String ARTEFACT = "artefact-hash-abc123";
    private static final List<String> MODS = List.of("fabric-api=0.100.0");

    private static DimensionConfig config(String json) {
        DimensionConfig def = GSON.fromJson(json, DimensionConfig.class);
        def.setName("the_test");
        return def;
    }

    private static String hash(DimensionConfig def) {
        return InputHash.hashOf(def, "v5.24.0", MODS, ARTEFACT);
    }

    @Test
    void twoConfigsDifferingOnlyInAShunKeyDifferently() {
        assertNotEquals(
                hash(config("{\"type\": \"multi_biome\", \"seedRoll\": {\"shuns\": []}}")),
                hash(config("{\"type\": \"multi_biome\", \"seedRoll\": "
                        + "{\"shuns\": [\"monument\"]}}")));
    }

    @Test
    void twoConfigsDifferingOnlyInAStructuresShunKeyDifferently() {
        assertNotEquals(
                hash(config("{\"type\": \"multi_biome\", \"structures\": {\"shuns\": {}}}")),
                hash(config("{\"type\": \"multi_biome\", \"structures\": "
                        + "{\"shuns\": {\"monument\": {}}}}")));
    }

    @Test
    void twoConfigsDifferingOnlyInAWantKeyDifferently() {
        assertNotEquals(
                hash(config("{\"type\": \"multi_biome\", \"structures\": {\"wants\": {}}}")),
                hash(config("{\"type\": \"multi_biome\", \"structures\": "
                        + "{\"wants\": {\"shipwreck\": {\"min\": 0, \"max\": 256}}}}")));
    }

    @Test
    void anyConfigFieldRekeysTheBankIncludingProseOnes() {
        // Deliberately pinned the way it BEHAVES, not the way an author would
        // want it to. measurementConfig() canonicalises the whole config minus
        // seed, so a description edit mints a fresh bank for that dimension.
        // Harmless (a new cache entry, not a wrong measurement) and the
        // deliberate direction — under-inclusion reuses a stale number — but it
        // means "differs only in prose" is not a shared-bank case today.
        assertNotEquals(
                hash(config("{\"type\": \"multi_biome\", \"description\": \"a\"}")),
                hash(config("{\"type\": \"multi_biome\", \"description\": \"b\"}")));
    }

    @Test
    void identicalConfigsKeyTheSame() {
        String json = "{\"type\": \"multi_biome\", \"structures\": "
                + "{\"shuns\": {\"monument\": {}}}}";
        assertEquals(hash(config(json)), hash(config(json)));
    }

    @Test
    void theAliasTableIsPartOfTheMeasurementIdentity() {
        // A want or shun NAME becomes an id through structure_aliases.json, so
        // an alias edit moves the pool with no config change at all. Omitting
        // it let a banked measurement outlive the pool it measured.
        assertTrue(InputHash.affectsMeasurement("structure_aliases.json"));
    }

    @Test
    void theFootprintTableIsPartOfTheMeasurementIdentity() {
        // NoiseStructurePlacement asks structure_sizes.json how much ground
        // each site claims, so it decides how many sites a pool yields.
        assertTrue(InputHash.affectsMeasurement("structure_sizes.json"));
    }

    @Test
    void aViewerOnlyResourceIsNotPartOfTheMeasurementIdentity() {
        // The counterweight: over-inclusion mints harmless cache entries, but
        // adding everything would throw a bank away on a colour change.
        assertTrue(!InputHash.affectsMeasurement("biome_surface_colours.json"));
        assertTrue(!InputHash.affectsMeasurement("web/app.built.css"));
    }

    // ------------------------------------------------- band names are scoring

    @Test
    void aSeedRollBandNameDoesNotChangeWhatThePoolWeights() {
        // seedRoll.wants values are band words the scorecard reads. Only the
        // KEYS reach StructureWants.resolve, so a band edit cannot move a
        // weight — which is why the resolved id set, not the raw block, is the
        // thing that describes generation.
        DimensionConfig nearSpawn = config("{\"type\": \"multi_biome\", \"seedRoll\": "
                + "{\"wants\": {\"monument\": \"near_spawn\"}}}");
        DimensionConfig nearBorder = config("{\"type\": \"multi_biome\", \"seedRoll\": "
                + "{\"wants\": {\"monument\": \"near_border\"}}}");
        assertEquals(NoisePoolBuilder.wantedStructureIds(nearSpawn),
                NoisePoolBuilder.wantedStructureIds(nearBorder));
        assertEquals(java.util.Set.of("minecraft:monument"),
                NoisePoolBuilder.wantedStructureIds(nearSpawn));
    }

    @Test
    void aStructuresWantsBandDoesNotChangeWhatThePoolWeights() {
        assertEquals(
                NoisePoolBuilder.wantedStructureIds(config(
                        "{\"type\": \"multi_biome\", \"structures\": "
                        + "{\"wants\": {\"monument\": {\"min\": 0, \"max\": 256}}}}")),
                NoisePoolBuilder.wantedStructureIds(config(
                        "{\"type\": \"multi_biome\", \"structures\": "
                        + "{\"wants\": {\"monument\": {\"min\": 900, \"max\": 4000}}}}")));
    }

    @Test
    void twoAliasesForTheSameStructureResolveToTheSameId() {
        // Aliases are not identity: the fingerprint has to be the resolved id,
        // or naming one structure two ways reads as two different pools.
        assertEquals(
                NoisePoolBuilder.shunnedStructureIds(config(
                        "{\"type\": \"nether\", \"seedRoll\": {\"shuns\": [\"monument\"]}}")),
                NoisePoolBuilder.shunnedStructureIds(config(
                        "{\"type\": \"nether\", \"seedRoll\": "
                        + "{\"shuns\": [\"minecraft:monument\"]}}")));
    }
}
