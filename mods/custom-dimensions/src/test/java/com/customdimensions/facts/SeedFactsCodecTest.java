package com.customdimensions.facts;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 gate 3: facts serialise and deserialise round-trip identically.
 *
 * <p>Two separate claims, and the weaker one is not enough on its own. Byte
 * equality of the re-emitted JSON proves the writer is reproducible; record
 * equality proves the reader actually recovered the values rather than
 * producing something that happens to print the same.
 */
class SeedFactsCodecTest {

    private static SeedFacts fullyMeasured() {
        return new SeedFacts("5.4.1", "adventure:the_boneyard", -8414350508334623889L,
                "2026-08-10T09:00:00Z", "fp-abc", 4096,
                new SeedFacts.SpawnFacts(
                        Measured.of(new SeedFacts.Column(64, -192, true)),
                        Measured.of("minecraft:snowy_plains"),
                        Measured.of(72),
                        Measured.of(11.5),
                        Measured.of(true)),
                new SeedFacts.BiomeFacts(
                        Measured.of(Map.of("minecraft:plains", 0.6,
                                "minecraft:desert", 0.4)),
                        Measured.of(2),
                        Measured.of(0.6),
                        Measured.of(0.2178)),
                new SeedFacts.TerrainFacts(
                        Measured.of(83.0),
                        Measured.of(2.3333333333333335),
                        Measured.of(0.125),
                        Measured.of(41),
                        Measured.of(124)),
                new SeedFacts.StructureFacts(
                        Measured.of(Map.of("minecraft:village_plains", 12)),
                        Measured.of(Map.of("dungeons", 7, "endgame", 1)),
                        Measured.of(Map.of("minecraft:village_plains", 3)),
                        Measured.of(Map.of("minecraft:village_plains", 912.5)),
                        Measured.of(Map.of("dungeons", 0.7412, "endgame", 1.0891)),
                        Measured.of(0.8123456789),
                        Measured.of(129.0),
                        Measured.of(8)));
    }

    /** A flat generator: real values where they exist, stated absences elsewhere. */
    private static SeedFacts partlyAbsent() {
        SeedFacts f = fullyMeasured();
        return new SeedFacts(f.stackVersion(), f.dimension(), f.seed(), f.measuredAt(),
                f.configFingerprint(), f.playableRadius(),
                f.spawn(),
                f.biomes(),
                new SeedFacts.TerrainFacts(
                        f.terrain().relief(),
                        f.terrain().grain(),
                        Measured.absent("a flat generator has no sea level, so a "
                                + "water fraction has no meaning"),
                        f.terrain().minHeight(),
                        f.terrain().maxHeight()),
                new SeedFacts.StructureFacts(
                        f.structures().pool(),
                        f.structures().byGroup(),
                        f.structures().byStructure(),
                        f.structures().nearestByStructure(),
                        f.structures().clusteringByGroup(),
                        Measured.absent("fewer than two positions, so nearest-neighbour "
                                + "distance is undefined"),
                        Measured.absent("no hostile placement in this dimension"),
                        f.structures().totalPositions()));
    }

    @Test
    void aFullyMeasuredRecordSurvivesTheRoundTripUnchanged() {
        SeedFacts original = fullyMeasured();
        String json = original.toJson();
        SeedFacts back = SeedFactsCodec.read(json);

        assertEquals(json, back.toJson(), "re-emitted JSON differs from the original");
        assertEquals(original, back, "the parsed record is not the record written");
    }

    @Test
    void anAbsenceComesBackAnAbsenceWithItsReasonIntact() {
        // The one that matters. D4 is enforced by the type in memory; at the
        // file boundary it is enforced here. A round trip that turned
        // {"absent": "..."} into 0.0 would defeat the whole layer silently.
        SeedFacts original = partlyAbsent();
        SeedFacts back = SeedFactsCodec.read(original.toJson());

        assertEquals(original.toJson(), back.toJson());
        assertEquals(original, back);
        assertFalse(back.terrain().waterFraction().isPresent());
        assertEquals("a flat generator has no sea level, so a water fraction has no meaning",
                back.terrain().waterFraction().reason());
        assertEquals(3, back.absences().size(), back.absences().toString());
    }

    @Test
    void anExactDoubleIsNotRoundedOnTheWayThroughAFile() {
        // Two measurements that differ in the last place must still differ
        // after a round trip. A %.6f rendering would make them compare equal,
        // which is the one thing a facts layer must never do.
        SeedFacts a = fullyMeasured();
        SeedFacts b = new SeedFacts(a.stackVersion(), a.dimension(), a.seed(), a.measuredAt(),
                a.configFingerprint(), a.playableRadius(), a.spawn(), a.biomes(),
                a.terrain(),
                new SeedFacts.StructureFacts(
                        a.structures().pool(), a.structures().byGroup(),
                        a.structures().byStructure(), a.structures().nearestByStructure(),
                        a.structures().clusteringByGroup(),
                        Measured.of(0.8123456789 + 1e-12),
                        a.structures().nearestHostile(), a.structures().totalPositions()));

        assertTrue(a.structures().clustering().orThrow()
                != b.structures().clustering().orThrow());
        assertEquals(a.structures().clustering().orThrow(),
                SeedFactsCodec.read(a.toJson()).structures().clustering().orThrow());
        assertEquals(b.structures().clustering().orThrow(),
                SeedFactsCodec.read(b.toJson()).structures().clustering().orThrow());
        assertTrue(SeedFactsCodec.read(a.toJson()).structures().clustering().orThrow()
                != SeedFactsCodec.read(b.toJson()).structures().clustering().orThrow(),
                "two distinct measurements collapsed to the same value through a file");
    }

    @Test
    void theSpawnColumnSurvivesWithWhetherTheConfigDeclaredIt() {
        // The measurement site is only derivable from the merged config, so the
        // record carries it. The flag has to survive the file too: the same
        // coordinates with declared=false say the config named no spawn and the
        // origin stood in, which is a different fact.
        SeedFacts declared = fullyMeasured();
        SeedFacts fellBack = new SeedFacts(declared.stackVersion(), declared.dimension(),
                declared.seed(), declared.measuredAt(), declared.configFingerprint(),
                declared.playableRadius(),
                new SeedFacts.SpawnFacts(
                        Measured.of(new SeedFacts.Column(0, 0, false)),
                        declared.spawn().biome(), declared.spawn().surfaceHeight(),
                        declared.spawn().localRelief(), declared.spawn().aboveSeaLevel()),
                declared.biomes(), declared.terrain(), declared.structures());

        SeedFacts backDeclared = SeedFactsCodec.read(declared.toJson());
        SeedFacts backFellBack = SeedFactsCodec.read(fellBack.toJson());

        assertEquals(new SeedFacts.Column(64, -192, true),
                backDeclared.spawn().column().orThrow());
        assertEquals(new SeedFacts.Column(0, 0, false),
                backFellBack.spawn().column().orThrow());
        assertTrue(backDeclared.spawn().column().orThrow().declared());
        assertFalse(backFellBack.spawn().column().orThrow().declared());
    }

    @Test
    void perGroupClusteringIsAMapAndNotCollapsedIntoOneNumber() {
        // The pooled figure cannot answer whether any group forms pockets, so
        // losing the per-group breakdown through a file would put the reader
        // back to the statistic that never fell below 1.
        SeedFacts back = SeedFactsCodec.read(fullyMeasured().toJson());
        Map<String, Double> byGroup = back.structures().clusteringByGroup().orThrow();

        assertEquals(2, byGroup.size(), byGroup.toString());
        assertEquals(0.7412, byGroup.get("dungeons"));
        assertEquals(1.0891, byGroup.get("endgame"));
        assertTrue(byGroup.get("dungeons") < 1.0 && byGroup.get("endgame") > 1.0,
                "the fixture must hold one pocketed and one dispersed group, or "
                + "it cannot show the two being told apart");
    }

    @Test
    void theReleaseThatMeasuredARecordSurvivesSoACallerCanRefuseIt() {
        // The record names the release that wrote it, and that is the only
        // version there is. A caller reusing a banked record compares it with
        // the running release and deletes anything else — reading another
        // release's facts under this build's meanings is what the stamp exists
        // to prevent, so the stamp has to survive the file.
        SeedFacts other = SeedFactsCodec.read(
                fullyMeasured().toJson().replace("\"5.4.1\"", "\"5.3.0\""));
        assertEquals("5.3.0", other.stackVersion());
        assertEquals("5.4.1", SeedFactsCodec.read(fullyMeasured().toJson()).stackVersion());
    }

    @Test
    void aRecordMissingItsReleaseStampIsRefusedRatherThanGuessedAt() {
        String json = fullyMeasured().toJson()
                .replace("\"stackVersion\"", "\"notTheStamp\"");
        assertThrows(RuntimeException.class, () -> SeedFactsCodec.read(json));
    }
}
