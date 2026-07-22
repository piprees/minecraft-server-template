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
