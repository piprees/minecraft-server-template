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

    @Test
    void aMissingFingerprintOnAFreshWorldWithNoChunksYetStillAdoptsTheCurrentConfig() {
        // "Fresh server" case: this dimension is registered but nobody has
        // ever visited it, so there is nothing on disk to lose. The boot
        // still records a baseline so drift can be caught from here on.
        DimensionConfig def = config("the_untouched_expanse", "nether", "adventure:wide", "minecraft:crimson_forest");
        DimensionFingerprints.checkExisting(def, false);

        assertEquals(List.of(),
                DimensionFingerprints.driftedFields(
                        DimensionFingerprints.storedFieldsFor(def.getName()),
                        fingerprint("nether", "adventure:wide", "minecraft:crimson_forest")));
    }

    @Test
    void aMissingFingerprintOnAWorldWithGeneratedChunksStillAdoptsTheCurrentConfig() {
        // "File deleted with worlds present" case — the bug this fixes. The
        // store was lost, not the dimension: this boot cannot verify drift
        // before now, but it still must not leave the guard permanently
        // blind — a baseline is recorded so the NEXT boot can catch drift.
        DimensionConfig def = config("the_boneyard_recovered", "nether", "adventure:wide", "minecraft:crimson_forest");
        DimensionFingerprints.checkExisting(def, true);

        assertEquals(List.of(),
                DimensionFingerprints.driftedFields(
                        DimensionFingerprints.storedFieldsFor(def.getName()),
                        fingerprint("nether", "adventure:wide", "minecraft:crimson_forest")));
    }

    @Test
    void anExistingFingerprintIsNeverOverwrittenByADriftedCurrentConfig() {
        // "Normal boot, config changed" — the policy is warn and keep the
        // world as generated. Overwriting the stored baseline with the
        // drifted config would erase the drift on the very next boot,
        // defeating the check permanently instead of just once.
        DimensionConfig def = config("the_settled_reach", "nether", "adventure:wide", "minecraft:crimson_forest");
        DimensionFingerprints.checkExisting(def, false); // establishes the baseline

        DimensionConfig changed = config("the_settled_reach", "overworld", "adventure:wide", "minecraft:crimson_forest");
        DimensionFingerprints.checkExisting(changed, false);

        assertEquals(List.of(),
                DimensionFingerprints.driftedFields(
                        DimensionFingerprints.storedFieldsFor(def.getName()),
                        fingerprint("nether", "adventure:wide", "minecraft:crimson_forest")),
                "the original baseline must survive a drifted boot unchanged");
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
