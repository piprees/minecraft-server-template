package com.customdimensions.command;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The portal-light receipt, and the invariant that keeps "dark" apart from
 * "nobody could tell".
 */
class PortalLightCommandTest {

    private static final PortalLightCommand.Settings SETTINGS =
            new PortalLightCommand.Settings(true, true, 0.6, 12, 32);

    private static final PortalLightCommand.Glow UNSAMPLED =
            new PortalLightCommand.Glow(-1L, -1, -1, 1.0, 0xAF2B2B, 0xAF2B2B);

    private static final PortalLightCommand.Glow SAMPLED =
            new PortalLightCommand.Glow(4211L, 4, 0x330707, 0.5233, 0xAF2B2B, 0x4C1414);

    @Test
    void theArtefactHasOneFixedName() {
        assertEquals(Path.of("/tmp/.seed-rolling/portal-light.json"),
                PortalLightCommand.artefactPath(Path.of("/tmp/.seed-rolling")));
    }

    /** T63: no zones is not "no portals painting" — the denominator says which. */
    @Test
    void anEmptyReportNamesItsEmptiness() {
        String summary = PortalLightCommand.summary(
                new PortalLightCommand.Report(List.of()), Path.of("/tmp/portal-light.json"));
        assertEquals("portal-light: 0 zone(s), 0 painting light (levels none), 0 cold, "
                + "0 with a destination sample, 0 with a particleType that bypasses it, "
                + "0 viewer hold(s) -> /tmp/portal-light.json", summary);
    }

    @Test
    void theSummaryCountsPaintedZonesAndTheirLevelRange() {
        String summary = PortalLightCommand.summary(new PortalLightCommand.Report(List.of(
                zone("source", 11, null, true, List.of(), SAMPLED),
                zone("source", 15, null, true, List.of(viewer("Pip")), SAMPLED),
                zone("arrival", 0, null, true, List.of(), UNSAMPLED))),
                Path.of("/tmp/portal-light.json"));
        assertTrue(summary.startsWith("portal-light: 3 zone(s), 2 painting light (levels 11..15), "
                + "0 cold, 2 with a destination sample, 0 with a particleType that bypasses it, "
                + "1 viewer hold(s) -> "), summary);
    }

    /** A configured particleType makes the destination glow inert; the receipt says so. */
    @Test
    void theSummaryCountsPortalsWhoseParticleBypassesTheGlow() {
        String summary = PortalLightCommand.summary(new PortalLightCommand.Report(List.of(
                zone("source", 11, "minecraft:end_rod", true, List.of(), SAMPLED),
                zone("source", 11, null, true, List.of(), SAMPLED))),
                Path.of("/tmp/portal-light.json"));
        assertTrue(summary.contains("1 with a particleType that bypasses it"), summary);
    }

    @Test
    void theSummaryCountsColdZonesSeparately() {
        String summary = PortalLightCommand.summary(new PortalLightCommand.Report(List.of(
                zone("source", 11, null, false, List.of(), UNSAMPLED))),
                Path.of("/tmp/portal-light.json"));
        assertTrue(summary.contains("1 cold,"), summary);
        assertTrue(summary.contains("0 with a destination sample"), summary);
    }

    /** A cold zone read its opening anyway, or a resident one did not: both are bugs. */
    @Test
    void aZoneReadsItsOpeningExactlyWhenItIsResident() {
        assertThrows(IllegalArgumentException.class,
                () -> zone("source", 11, null, true, List.of(), UNSAMPLED, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PortalLightCommand.ZoneLight("source", "minecraft:overworld",
                        "adventure:the_crimson_nexus", "X", 11, true, null, false,
                        List.of(cell(1500, 101, 1500)), List.of(), UNSAMPLED, SETTINGS));
    }

    /** The record is the answer: every field a reader needs survives rendering. */
    @Test
    void theDocumentCarriesTheOpeningAndTheGlow() {
        String json = PortalLightCommand.render("{\n",
                new PortalLightCommand.Report(List.of(
                        zone("source", 11, null, true, List.of(viewer("Pip")), SAMPLED))));
        assertTrue(json.contains("\"configuredLightLevel\": 11"), json);
        assertTrue(json.contains("\"painted\": true"), json);
        assertTrue(json.contains("\"particleTypeOverride\": null"), json);
        assertTrue(json.contains("\"block\": \"minecraft:air\""), json);
        assertTrue(json.contains("\"blockLight\": 0"), json);
        assertTrue(json.contains("\"sampledAtTick\": 4211"), json);
        assertTrue(json.contains("\"projectedCells\": 336"), json);
    }

    /** A cold zone's opening is null, never an empty list that reads as "no cells". */
    @Test
    void aColdZoneRendersANullOpening() {
        String json = PortalLightCommand.render("{\n",
                new PortalLightCommand.Report(List.of(
                        zone("source", 11, null, false, List.of(), UNSAMPLED))));
        assertTrue(json.contains("\"resident\": false"), json);
        assertTrue(json.contains("\"aperture\": null"), json);
    }

    private static PortalLightCommand.ZoneLight zone(String kind, int level, String particleType,
            boolean resident, List<PortalLightCommand.Viewer> viewers,
            PortalLightCommand.Glow glow) {
        return zone(kind, level, particleType, resident, viewers, glow,
                resident ? List.of(cell(1500, 101, 1500), cell(1501, 101, 1500)) : List.of());
    }

    private static PortalLightCommand.ZoneLight zone(String kind, int level, String particleType,
            boolean resident, List<PortalLightCommand.Viewer> viewers,
            PortalLightCommand.Glow glow, List<PortalLightCommand.Cell> aperture) {
        return new PortalLightCommand.ZoneLight(kind, "minecraft:overworld",
                "adventure:the_crimson_nexus", "X", level, level > 0, particleType, resident,
                aperture, viewers, glow, SETTINGS);
    }

    private static PortalLightCommand.Cell cell(int x, int y, int z) {
        return new PortalLightCommand.Cell(new PortalLightCommand.Pos(x, y, z),
                "minecraft:air", 0, 0, 15);
    }

    private static PortalLightCommand.Viewer viewer(String name) {
        return new PortalLightCommand.Viewer(name, 336, 0);
    }
}
