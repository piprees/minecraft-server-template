package com.customdimensions.dimension;

import com.customdimensions.config.DimensionConfig;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure comparison behind the boot-time drift warning: creation-time
 * fingerprint plus current config fields in, drifted field names out.
 */
class DimensionFingerprintsTest {

    @Test
    void biomePatchesAddedToAWorldThatAlreadyExistsReadsAsDrift() {
        // A dimension created before its patches records the string "null", so
        // the key is present and compared rather than backfilled. Adding
        // biomePatches later is creation-time worldgen the world cannot take.
        Map<String, String> stored = fingerprint("multi_biome", "null", "minecraft:plains");
        Map<String, String> current = new HashMap<>(stored);
        current.put("biomePatches", "minecraft:warped_forest@0,0,420~64");

        List<String> drifted = DimensionFingerprints.driftedFields(stored, current);

        assertEquals(List.of("biomePatches"), drifted);
        assertTrue(DimensionFingerprints.needsWipe(drifted),
                "the generator is baked into level.dat, so this needs a wipe");
    }

    @Test
    void aPatchListThatHasNotChangedIsNotDrift() {
        Map<String, String> stored = fingerprint("multi_biome", "null", "minecraft:plains");
        stored.put("biomePatches", "minecraft:warped_forest@0,0,420~64");
        Map<String, String> current = new HashMap<>(stored);

        assertEquals(List.of(), DimensionFingerprints.driftedFields(stored, current));
    }

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
        // Resolved through the alias table, so a config naming no wants still
        // records its family's inherited list — the one the pool weights.
        DimensionConfig bare = config("the_bare", type, noiseSettings, biomes);
        f.put("structureWants",
                String.valueOf(new java.util.TreeSet<>(NoisePoolBuilder.wantedStructureIds(bare))));
        f.put("structureShuns",
                String.valueOf(new java.util.TreeSet<>(NoisePoolBuilder.shunnedStructureIds(bare))));
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
        // structureWants comes along: a config naming no wants inherits its
        // FAMILY's default list, and the family follows from the type, so the
        // pool this dimension draws from really did change.
        assertEquals(List.of("type", "structureWants"),
                DimensionFingerprints.driftedFields(stored, current));
    }

    @Test
    void severalFieldsDrifted() {
        Map<String, String> stored = fingerprint("nether", "adventure:wide", "minecraft:crimson_forest");
        Map<String, String> current = fingerprint("overworld", "adventure:compressed", "minecraft:plains");
        assertEquals(List.of("type", "noiseSettings", "biomes", "structureWants"),
                DimensionFingerprints.driftedFields(stored, current));
    }

    // ------------------------------------ canonical(): a measurement's identity

    private static DimensionConfig parseConfig(String json) {
        DimensionConfig config = new com.google.gson.Gson().fromJson(json, DimensionConfig.class);
        config.setName("d");
        config.setNamespace("adventure");
        return config;
    }

    private static String bandedAt(String weirdness) {
        return "{\"type\":\"multi_biome\",\"biomes\":[\"minecraft:plains\","
                + "{\"id\":\"minecraft:taiga\",\"parameters\":{\"weirdness\":[" + weirdness + "]}}]}";
    }

    @Test
    void twoConfigsDifferingOnlyInTheirBandsFingerprintDifferently() {
        assertNotEquals(
                DimensionFingerprints.canonical(parseConfig(bandedAt("-2.0,-1.0"))),
                DimensionFingerprints.canonical(parseConfig(bandedAt("-1.0,0.0"))));
    }

    @Test
    void theSameConfigFingerprintsIdenticallyEveryTime() {
        // Stability is the other half. A value that moved across a restart
        // would hand a corroboration gate a false-MISMATCH arm in place of the
        // false-match one this replaces.
        DimensionConfig a = parseConfig(bandedAt("-2.0,-1.0"));
        assertEquals(DimensionFingerprints.canonical(a), DimensionFingerprints.canonical(a));
        assertEquals(DimensionFingerprints.canonical(a),
                DimensionFingerprints.canonical(parseConfig(bandedAt("-2.0,-1.0"))));
    }

    @Test
    void theCanonicalCarriesTheBandDefaultTerm() {
        // The sweep's per-point proof: BAND_OFFSET_BASE lives inside
        // biomeParameters, so a build at a different default writes a
        // different value into every affected record. Asserted on the TERM,
        // not its value — the value is pinned by BandOffsetDefaultTest.
        assertTrue(DimensionFingerprints.canonical(parseConfig(bandedAt("-2.0,-1.0")))
                .contains("|defaultOffset="));
    }

    @Test
    void twoConfigsWithNoBiomePatchesStillFingerprintDistinctly() {
        // The exact shape of the defect this replaces: the field carried the
        // biomePatches fingerprint, which is the literal "null" for every
        // dimension without a patch, so a gate keyed on it compared nothing.
        DimensionConfig a = parseConfig("{\"type\":\"multi_biome\",\"biomes\":[\"minecraft:plains\"]}");
        DimensionConfig b = parseConfig("{\"type\":\"multi_biome\",\"biomes\":[\"minecraft:swamp\"]}");

        assertEquals("null", String.valueOf(a.getBiomePatchesFingerprint()));
        assertEquals("null", String.valueOf(b.getBiomePatchesFingerprint()));
        assertNotEquals(DimensionFingerprints.canonical(a), DimensionFingerprints.canonical(b));
    }

    @Test
    void canonicalSurvivesEveryDegenerateConfigShapeFactsCanBeCalledOn() {
        // canonical() runs fields(), which resolves structure ids through the
        // alias table — code the field it replaces never touched. `facts` is
        // called on RESERVED dimensions too, and a reserved config has never
        // been through fields(): registerDimensions iterates
        // getCustomDimensions(), which excludes them. A throw here would break
        // a call that used to succeed, so the shapes are pinned rather than
        // assumed.
        assertEquals("", DimensionFingerprints.canonical(null));
        for (String json : List.of(
                "{}",
                "{\"type\":\"overworld\"}",
                "{\"type\":\"nether\",\"biomes\":[]}",
                "{\"type\":\"superflat\",\"layers\":[{\"block\":\"minecraft:stone\",\"height\":1}]}",
                "{\"type\":\"checkerboard\",\"biomes\":[\"minecraft:plains\"],\"checkerboardScale\":2}")) {
            String c = DimensionFingerprints.canonical(parseConfig(json));
            assertFalse(c.isEmpty(), "canonical must answer for " + json);
            assertTrue(c.contains("biomeParameters="),
                    "every field is present even where its value is null: " + json);
        }
    }

    // ------------------------------------------ the band-offset default term

    /**
     * A real band string from a server record. The default's term is a SUFFIX
     * inside the {@code biomeParameters} VALUE, not a field of its own, and
     * the three tests below turn on that distinction.
     */
    private static final String BANDS = "regions_unexplored:inferno={\"temperature\":[0.375,2.0]}";

    @Test
    void changingTheDefaultOffsetTermDriftsBiomeParameters() {
        Map<String, String> stored = fingerprint("nether", "null", "minecraft:crimson_forest");
        stored.put("biomeParameters", BANDS + "|defaultOffset=0.175");
        Map<String, String> current = new HashMap<>(stored);
        current.put("biomeParameters", BANDS + "|defaultOffset=0.3");

        List<String> drifted = DimensionFingerprints.driftedFields(stored, current);

        assertEquals(List.of("biomeParameters"), drifted);
        assertTrue(DimensionFingerprints.needsWipe(drifted),
                "the biome source is baked into level.dat, so a moved default needs a wipe");
    }

    @Test
    void droppingTheDefaultOffsetTermStillDriftsBecauseTheKeyRemains() {
        // driftedFields skips a field only where the KEY is absent. Removing
        // the suffix leaves the key present with a changed value, so it drifts
        // like any other edit. The vacuous case is the next test, not this one.
        Map<String, String> stored = fingerprint("nether", "null", "minecraft:crimson_forest");
        stored.put("biomeParameters", BANDS + "|defaultOffset=0.175");
        Map<String, String> current = new HashMap<>(stored);
        current.put("biomeParameters", BANDS);

        assertEquals(List.of("biomeParameters"),
                DimensionFingerprints.driftedFields(stored, current));
    }

    @Test
    void aRecordCarryingNoBiomeParametersKeyAtAllCannotDrift() {
        // The one edit that cannot warn: a record predating the field is
        // backfilled rather than compared.
        Map<String, String> stored = fingerprint("nether", "null", "minecraft:crimson_forest");
        stored.remove("biomeParameters");
        Map<String, String> current = new HashMap<>(stored);
        current.put("biomeParameters", BANDS + "|defaultOffset=0.175");

        assertEquals(List.of(), DimensionFingerprints.driftedFields(stored, current));
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

    private static DimensionConfig config(String name, String type, String noiseSettings, String biome) {
        DimensionConfig def = new DimensionConfig();
        def.setName(name);
        def.setNamespace("adventure");
        def.setType(type);
        def.setNoiseSettings(noiseSettings);
        def.setBiome(biome);
        return def;
    }

    // -------------------------------------------------- checkExisting: the missing-fingerprint split

    /**
     * The recorded baseline, asserted as PRESENT and EQUAL. Asking {@link
     * DimensionFingerprints#driftedFields} instead cannot answer this: it skips
     * every field a record does not carry, so it reports no drift for a
     * baseline that was never written — the one outcome these tests exclude.
     */
    private static void assertBaselineRecorded(String dimension, Map<String, String> expected) {
        Map<String, String> stored = DimensionFingerprints.storedFieldsFor(dimension);
        assertNotNull(stored, "checkExisting recorded no baseline for " + dimension
                + " — drift detection is blind from this boot on");
        assertFalse(stored.isEmpty(), "checkExisting recorded an EMPTY baseline for " + dimension
                + " — every field is absent, so driftedFields skips all of them and calls it clean");
        for (Map.Entry<String, String> field : expected.entrySet()) {
            assertEquals(field.getValue(), stored.get(field.getKey()),
                    dimension + " baseline field " + field.getKey());
        }
    }

    @Test
    void aMissingFingerprintOnAFreshWorldWithNoChunksYetStillAdoptsTheCurrentConfig() {
        // "Fresh server" case: this dimension is registered but nobody has
        // ever visited it, so there is nothing on disk to lose. The boot
        // still records a baseline so drift can be caught from here on.
        DimensionConfig def = config("the_untouched_expanse", "nether", "adventure:wide", "minecraft:crimson_forest");
        DimensionFingerprints.checkExisting(def, false);

        assertBaselineRecorded(def.getName(),
                fingerprint("nether", "adventure:wide", "minecraft:crimson_forest"));
    }

    @Test
    void aMissingFingerprintOnAWorldWithGeneratedChunksStillAdoptsTheCurrentConfig() {
        // "File deleted with worlds present" case — the bug this fixes. The
        // store was lost, not the dimension: this boot cannot verify drift
        // before now, but it still must not leave the guard permanently
        // blind — a baseline is recorded so the NEXT boot can catch drift.
        DimensionConfig def = config("the_boneyard_recovered", "nether", "adventure:wide", "minecraft:crimson_forest");
        DimensionFingerprints.checkExisting(def, true);

        assertBaselineRecorded(def.getName(),
                fingerprint("nether", "adventure:wide", "minecraft:crimson_forest"));
    }

    @Test
    void anExistingFingerprintIsNeverOverwrittenByADriftedCurrentConfig() {
        // "Normal boot, config changed" — the policy is warn and keep the
        // world as generated. Overwriting the stored baseline with the
        // drifted config would erase the drift on the very next boot,
        // defeating the check permanently instead of just once. The baseline
        // asserted here is the ORIGINAL nether one, not the drifted overworld.
        DimensionConfig def = config("the_settled_reach", "nether", "adventure:wide", "minecraft:crimson_forest");
        DimensionFingerprints.checkExisting(def, false); // establishes the baseline

        DimensionConfig changed = config("the_settled_reach", "overworld", "adventure:wide", "minecraft:crimson_forest");
        DimensionFingerprints.checkExisting(changed, false);

        assertBaselineRecorded(def.getName(),
                fingerprint("nether", "adventure:wide", "minecraft:crimson_forest"));
    }

    // ------------------------------------------- record(): the re-registration split

    private static DimensionConfig seeded(String name, String type, String biome, long seed) {
        DimensionConfig def = config(name, type, "adventure:wide", biome);
        def.setSeed(seed);
        return def;
    }

    @Test
    void aDimensionWithNoStoredRecordAdoptsTheCurrentConfig() {
        DimensionConfig def = seeded("the_first_light", "nether", "minecraft:crimson_forest", 11L);
        DimensionFingerprints.record(def, false);

        assertEquals("nether", DimensionFingerprints.storedFieldsFor(def.getName()).get("type"));
        assertEquals("11", DimensionFingerprints.storedFieldsFor(def.getName()).get("seed"));
    }

    @Test
    void aRerecordedDimensionWithNoChunksAdoptsTheNewConfig() {
        // Nothing was ever baked, so the stored record is a forecast and the
        // current config is the better one. Refreshing here is correct.
        DimensionFingerprints.record(
                seeded("the_unvisited_span", "nether", "minecraft:crimson_forest", 1L), false);
        DimensionFingerprints.record(
                seeded("the_unvisited_span", "overworld", "minecraft:plains", 2L), false);

        Map<String, String> stored = DimensionFingerprints.storedFieldsFor("the_unvisited_span");
        assertEquals("overworld", stored.get("type"));
        assertEquals("2", stored.get("seed"));
    }

    @Test
    void aRerecordedDimensionWithChunksKeepsItsStoredBaseline() {
        // THE FIX. A dimension whose level.dat entry did not decode falls out
        // of the registry and takes this path, and its stored fingerprint is
        // the only record of what the chunks on disk were built from.
        DimensionFingerprints.record(
                seeded("the_settled_span", "nether", "minecraft:crimson_forest", 1L), false);
        DimensionFingerprints.record(
                seeded("the_settled_span", "overworld", "minecraft:plains", 2L), true);

        assertEquals("nether", DimensionFingerprints.storedFieldsFor("the_settled_span").get("type"),
                "a re-record over generated chunks must not adopt the drifted config");
    }

    @Test
    void aRerecordedDimensionWithChunksKeepsItsStoredSeedToo() {
        // seed is written by fields() and is NOT in WORLDGEN_FIELDS, so
        // driftedFields never sees it. It follows the world like every other
        // field rather than getting a rule of its own.
        DimensionFingerprints.record(
                seeded("the_settled_seed", "nether", "minecraft:crimson_forest", 1L), false);
        DimensionFingerprints.record(
                seeded("the_settled_seed", "nether", "minecraft:crimson_forest", 999L), true);

        assertEquals("1", DimensionFingerprints.storedFieldsFor("the_settled_seed").get("seed"));
    }

    @Test
    void aRerecordedDimensionWithNoChunksRefreshesTheStoredSeed() {
        // The other half of the same rule, and the one a "skip the write when
        // no worldgen field drifted" implementation gets wrong: seed is not a
        // worldgen field, so nothing would have drifted and the store would
        // keep a creation seed the world will never be created from.
        DimensionFingerprints.record(
                seeded("the_rerolled_span", "nether", "minecraft:crimson_forest", 1L), false);
        DimensionFingerprints.record(
                seeded("the_rerolled_span", "nether", "minecraft:crimson_forest", 42L), false);

        assertEquals("42", DimensionFingerprints.storedFieldsFor("the_rerolled_span").get("seed"));
    }

    @Test
    void anUnchangedRerecordDoesNotRewriteTheStore() {
        // 65 of 82 dimensions re-register on every boot of a fresh world, each
        // rewriting an identical record into a 118 KB file. fields() builds a
        // new map every call, so the stored instance surviving IS the proof
        // that no put happened.
        DimensionFingerprints.record(
                seeded("the_unchanged_span", "nether", "minecraft:crimson_forest", 7L), false);
        Map<String, String> first = DimensionFingerprints.storedFieldsFor("the_unchanged_span");

        DimensionFingerprints.record(
                seeded("the_unchanged_span", "nether", "minecraft:crimson_forest", 7L), false);

        assertSame(first, DimensionFingerprints.storedFieldsFor("the_unchanged_span"),
                "an identical re-record must not write");
    }

    @Test
    void aRecordPredatingAFieldIsBackfilledByARerecordOverChunks() {
        // driftedFields skips a field the record does not carry, so without the
        // backfill "no drift" and "never compared" stay indistinguishable for
        // the life of the store.
        DimensionFingerprints.record(
                seeded("the_elder_span", "nether", "minecraft:crimson_forest", 1L), false);
        DimensionFingerprints.storedFieldsFor("the_elder_span").remove("biomeParameters");

        DimensionFingerprints.record(
                seeded("the_elder_span", "nether", "minecraft:crimson_forest", 1L), true);

        assertTrue(DimensionFingerprints.storedFieldsFor("the_elder_span")
                        .containsKey("biomeParameters"),
                "a field the record predates must be adopted, not skipped forever");
    }

    @Test
    void aRebuiltGeneratorIsNeverToldItsChangeWillNotApply() {
        // The two worldgen warnings are opposites and the branch between them
        // is the whole reason record() does not simply delegate: a generator
        // rebuilt from config DOES reach new chunks.
        String baked = DimensionFingerprints.driftWarning(List.of("type"), false);
        String rebuilt = DimensionFingerprints.driftWarning(List.of("type"), true);

        assertNotEquals(baked, rebuilt);
        assertTrue(baked.contains("never apply to existing"));
        assertFalse(rebuilt.contains("never apply to existing"),
                "the rebuilt-generator case must not claim the change will not apply");
        assertTrue(rebuilt.contains("will not match across the boundary"));
        // wants/shuns are not baked either way, so the path does not change it
        assertEquals(DimensionFingerprints.driftWarning(List.of("structureWants"), false),
                DimensionFingerprints.driftWarning(List.of("structureWants"), true));
    }

    // -------------------------------------------------------------- regionDirFor

    @Test
    void regionDirForPointsAtTheDimensionsOwnNamespaceAndPath(@TempDir Path saveRoot) throws Exception {
        Identifier id = Identifier.of("adventure", "the_boneyard");
        Path expected = saveRoot.resolve("dimensions").resolve("adventure")
                .resolve("the_boneyard").resolve("region");

        assertEquals(expected, DimensionFingerprints.regionDirFor(saveRoot, id));
        assertFalse(Files.isDirectory(expected), "nothing generated yet — the directory must not exist");

        Files.createDirectories(expected);
        assertTrue(Files.isDirectory(DimensionFingerprints.regionDirFor(saveRoot, id)),
                "a chunk has saved — the region directory now exists");
    }
}
