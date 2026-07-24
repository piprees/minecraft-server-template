package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import com.google.gson.Gson;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The single-use countdown must survive restarts: the remaining ticks ride
 * in the persisted zone record (portal_links.json), written at countdown
 * start and at shutdown. This pins the JSON round-trip shape.
 */
class StoredPortalZonePersistenceTest {
    private static final Gson GSON = new Gson();

    private PortalHelper.PortalZone zone(int ticksLeft) {
        PortalDefinition def = new PortalDefinition(
                "the_trap", "minecraft:obsidian", "minecraft:flint_and_steel",
                "adventure:the_trap", "FF0000", 10);
        def.setSingleUse(true);
        def.setSingleUseDelayTicks(600);
        def.setSingleUseBreakMode("partial");
        PortalHelper.PortalZone zone = new PortalHelper.PortalZone(
                Set.of(new BlockPos(1, 64, 1), new BlockPos(1, 65, 1)),
                def,
                Direction.Axis.X,
                RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld")),
                RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure", "the_trap")));
        zone.singleUseTicksLeft = ticksLeft;
        return zone;
    }

    @Test
    void countdownRoundTripsThroughJson() {
        PortalHelper.StoredPortalZone stored = PortalHelper.StoredPortalZone.from(zone(77));
        String json = GSON.toJson(stored);
        assertTrue(json.contains("\"singleUseTicksLeft\":77"));
        PortalHelper.PortalZone restored =
                GSON.fromJson(json, PortalHelper.StoredPortalZone.class).toPortalZone();
        assertEquals(77, restored.singleUseTicksLeft);
        assertTrue(restored.definition.isSingleUse());
        assertEquals("partial", restored.definition.getSingleUseBreakMode());
        assertEquals(600, restored.definition.getSingleUseDelayTicks());
    }

    @Test
    void untraversedZoneOmitsCountdownAndRestoresUnarmed() {
        PortalHelper.StoredPortalZone stored = PortalHelper.StoredPortalZone.from(zone(-1));
        String json = GSON.toJson(stored);
        assertFalse(json.contains("singleUseTicksLeft"));
        PortalHelper.PortalZone restored =
                GSON.fromJson(json, PortalHelper.StoredPortalZone.class).toPortalZone();
        assertEquals(-1, restored.singleUseTicksLeft);
    }

    @Test
    void shapeAndCentreBlockRoundTripAndDefaultToStandard() {
        PortalHelper.PortalZone source = zone(-1);
        source.definition.setShape("end_exit");
        source.definition.setCentreBlock("minecraft:dragon_egg");
        String json = GSON.toJson(PortalHelper.StoredPortalZone.from(source));
        // Plain strings only in the record (downgrade-parseability rule);
        // older jars simply ignore the unknown fields.
        assertTrue(json.contains("\"shape\":\"end_exit\""));
        assertTrue(json.contains("\"centreBlock\":\"minecraft:dragon_egg\""));
        PortalHelper.PortalZone restored =
                GSON.fromJson(json, PortalHelper.StoredPortalZone.class).toPortalZone();
        assertEquals("end_exit", restored.definition.getShape());
        assertEquals("minecraft:dragon_egg", restored.definition.getCentreBlock());
        // Records without shape (every pre-Tier-2 zone) restore as standard.
        PortalHelper.PortalZone legacyShaped = zone(-1);
        String legacyJson = GSON.toJson(PortalHelper.StoredPortalZone.from(legacyShaped));
        assertFalse(legacyJson.contains("shape"));
        assertEquals("standard",
                GSON.fromJson(legacyJson, PortalHelper.StoredPortalZone.class)
                        .toPortalZone().definition.getShape());
    }

    @Test
    void framePartAcceptsRoundTripAndLegacyRecordsStayUniform() {
        PortalHelper.PortalZone source = zone(-1);
        source.definition.setFramePartAccepts(java.util.Map.of(
                "sides", java.util.List.of("#minecraft:logs"),
                "bottom", java.util.List.of("minecraft:stone")));
        String json = GSON.toJson(PortalHelper.StoredPortalZone.from(source));
        assertTrue(json.contains("framePartAccepts"));
        PortalHelper.PortalZone restored =
                GSON.fromJson(json, PortalHelper.StoredPortalZone.class).toPortalZone();
        assertTrue(restored.definition.hasPartMaterials());
        assertEquals(java.util.List.of("#minecraft:logs"),
                restored.definition.getFramePartAccepts().get("sides"));
        // pre-2b records have no part accepts and stay uniform
        String legacyJson = GSON.toJson(PortalHelper.StoredPortalZone.from(zone(-1)));
        assertFalse(legacyJson.contains("framePartAccepts"));
        assertFalse(GSON.fromJson(legacyJson, PortalHelper.StoredPortalZone.class)
                .toPortalZone().definition.hasPartMaterials());
    }

    @Test
    void auraPalettesAndBudgetRoundTrip() {
        PortalHelper.PortalZone source = zone(-1);
        source.auraPalette = java.util.List.of("minecraft:end_stone", "minecraft:obsidian");
        source.auraFlora = java.util.List.of("minecraft:chorus_flower");
        source.auraTrees = java.util.List.of();
        source.auraFluids = java.util.List.of();
        source.auraBudgetSpent = 42;
        String json = GSON.toJson(PortalHelper.StoredPortalZone.from(source));
        assertTrue(json.contains("\"auraBudgetSpent\":42"));
        PortalHelper.PortalZone restored =
                GSON.fromJson(json, PortalHelper.StoredPortalZone.class).toPortalZone();
        assertEquals(java.util.List.of("minecraft:end_stone", "minecraft:obsidian"), restored.auraPalette);
        assertEquals(42, restored.auraBudgetSpent);
        // unlinked zones carry no aura fields at all
        String plain = GSON.toJson(PortalHelper.StoredPortalZone.from(zone(-1)));
        assertFalse(plain.contains("aura"));
        assertNull(GSON.fromJson(plain, PortalHelper.StoredPortalZone.class).toPortalZone().auraPalette);
    }

    @Test
    void auraSiteRecordRoundTrips() {
        PortalHelper.AuraSite site = new PortalHelper.AuraSite();
        site.setInterior(java.util.List.of(
                new net.minecraft.util.math.BlockPos(10, 64, 10),
                new net.minecraft.util.math.BlockPos(10, 65, 10)));
        site.palette = java.util.List.of("minecraft:moss_block");
        site.settings = new com.customdimensions.config.PortalDefinition.AuraSettings();
        site.settings.budget = 100;
        site.budgetSpent = 7;
        String json = GSON.toJson(site);
        assertTrue(json.contains("\"recordType\":\"aura-site-v1\""));
        PortalHelper.AuraSite restored = GSON.fromJson(json, PortalHelper.AuraSite.class);
        assertEquals(2, restored.interiorPositions().size());
        assertEquals(java.util.List.of("minecraft:moss_block"), restored.palette);
        assertEquals(100, restored.settings.getBudget());
        assertEquals(7, restored.budgetSpent);
    }

    @Test
    void legacyRecordWithoutCountdownFieldRestoresUnarmed() {
        String legacy = """
                {"recordType":"source-zone-v1","sourceWorld":"minecraft:overworld",
                 "targetWorld":"adventure:the_trap","axis":"X",
                 "definition":{"id":"the_trap","frameBlock":"minecraft:obsidian",
                   "igniterItem":"i","targetDimension":"adventure:the_trap"},
                 "interior":[{"x":1,"y":64,"z":1}]}
                """;
        PortalHelper.PortalZone restored =
                GSON.fromJson(legacy, PortalHelper.StoredPortalZone.class).toPortalZone();
        assertEquals(-1, restored.singleUseTicksLeft);
        assertFalse(restored.definition.isSingleUse());
    }
}
