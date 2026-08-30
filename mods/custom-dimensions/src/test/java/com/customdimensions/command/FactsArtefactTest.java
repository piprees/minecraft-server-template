package com.customdimensions.command;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where {@code /customdim facts} writes its record, and that a re-measure
 * replaces it.
 *
 * <p>RCON cannot carry per-biome shares ([T17]), so the file IS the answer. A
 * stale one read as fresh is the failure this exists to prevent.
 */
class FactsArtefactTest {

    private static final Path ROLLING = Path.of("/srv/.seed-rolling");

    private static Path pathFor(String id, long seed) {
        return FactsCommands.artefactPath(ROLLING, Identifier.of(id), seed);
    }

    @Test
    void theNameCarriesTheDimensionAndTheSeed() {
        assertEquals("facts__adventure_the_lantern_pools__-2792686122266062874.json",
                pathFor("adventure:the_lantern_pools", -2792686122266062874L)
                        .getFileName().toString());
    }

    @Test
    void theNamespaceSeparatorIsNotAColon() {
        // A colon is legal on ext4 and not on every filesystem a consumer runs,
        // and it is the separator every sibling artefact already replaces.
        assertFalse(pathFor("adventure:the_gauntlet", 1L).getFileName().toString().contains(":"));
    }

    @Test
    void twoSeedsOfOneDimensionDoNotShareAFile() {
        assertNotEquals(pathFor("adventure:the_gauntlet", 1L),
                pathFor("adventure:the_gauntlet", 2L));
    }

    @Test
    void twoDimensionsOnOneSeedDoNotShareAFile() {
        assertNotEquals(pathFor("adventure:the_gauntlet", 1L),
                pathFor("adventure:the_crucible", 1L));
    }

    @Test
    void theRecordDoesNotLandAmongTheRollersCandidates() {
        // A roller candidate is a snapshot of the config it was rolled
        // against; overwriting one with a fresh measurement would destroy the
        // bank this is meant to be comparable with.
        assertNotEquals("candidates",
                pathFor("adventure:the_gauntlet", 1L).getParent().getFileName().toString());
    }

    @Test
    void aRecordRoundTrips(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("facts__adventure_x__1.json");
        String body = "{\n \"dimension\": \"adventure:x\",\n \"seed\": 1\n}\n";

        Artefacts.write(target, body);

        assertEquals(body, Files.readString(target));
    }

    @Test
    void aSecondMeasurementReplacesTheFirstRatherThanKeepingIt(@TempDir Path dir)
            throws IOException {
        Path target = dir.resolve("facts__adventure_x__1.json");
        Artefacts.write(target, "{\"measuredAt\": \"first\"}");

        Artefacts.write(target, "{\"measuredAt\": \"second\"}");

        assertEquals("{\"measuredAt\": \"second\"}", Files.readString(target));
    }

    @Test
    void aWriteLeavesNoTempFileBehind(@TempDir Path dir) throws IOException {
        Artefacts.write(dir.resolve("facts__adventure_x__1.json"), "{}");

        try (var entries = Files.list(dir)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }
}
