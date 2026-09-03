package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import com.google.gson.Gson;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An arrival is a frame with an empty interior, so nothing in the world says
 * one is there. The registry says where its cells are and the arrival zone
 * says what shape its frame is — between them they replace every block read
 * the arrival side used to do.
 */
class ArrivalZoneTest {
    private static final Gson GSON = new Gson();

    private static final RegistryKey<World> ARRIVAL_WORLD =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure", "the_blossom_gardens"));
    private static final RegistryKey<World> RETURN_WORLD =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));
    private static final RegistryKey<World> OTHER_WORLD =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure", "the_canvas"));

    @BeforeEach
    @AfterEach
    void reset() {
        // Clears every in-memory portal map. With no run directory set the
        // load is a pure reset — it never touches the filesystem.
        PortalHelper.loadPortalLinks();
    }

    private static PortalDefinition definition() {
        return new PortalDefinition("the_blossom_gardens", "minecraft:pink_concrete",
                "minecraft:flint_and_steel", "adventure:the_blossom_gardens", "FFAACC", 0);
    }

    /** A 2x3 vertical opening on the X axis at (100, 64..66, 200). */
    private static Set<BlockPos> doorway() {
        Set<BlockPos> interior = new HashSet<>();
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                interior.add(new BlockPos(100 + dx, 64 + dy, 200));
            }
        }
        return interior;
    }

    private static void registerDoorway() {
        for (BlockPos p : doorway()) {
            PortalHelper.registerPortal(ARRIVAL_WORLD, p, RETURN_WORLD, 64, 0xFFAACC, 40, null);
        }
    }

    @Test
    void standingInAnArrivalIsARegistryQuestion() {
        registerDoorway();
        // Feet in the bottom row.
        assertEquals(new BlockPos(100, 64, 200),
                PortalHelper.arrivalCellNear(ARRIVAL_WORLD, new BlockPos(100, 64, 200)));
        // Feet one below it — the reach the block probe had.
        assertEquals(new BlockPos(100, 64, 200),
                PortalHelper.arrivalCellNear(ARRIVAL_WORLD, new BlockPos(100, 63, 200)));
        // And one above the top row.
        assertEquals(new BlockPos(100, 66, 200),
                PortalHelper.arrivalCellNear(ARRIVAL_WORLD, new BlockPos(100, 67, 200)));
    }

    @Test
    void nothingRegisteredIsNotAnArrival() {
        registerDoorway();
        assertNull(PortalHelper.arrivalCellNear(ARRIVAL_WORLD, new BlockPos(100, 70, 200)));
        assertNull(PortalHelper.arrivalCellNear(OTHER_WORLD, new BlockPos(100, 64, 200)));
    }

    @Test
    void theApertureGrowsOverTheRegistryNotTheWorld() {
        registerDoorway();
        Set<BlockPos> aperture = PortalHelper.registeredAperture(
                ARRIVAL_WORLD, new BlockPos(101, 66, 200), Direction.Axis.X);
        assertEquals(doorway(), aperture, "every cell of the opening, from any seed in it");

        // A cell nothing registered has no aperture at all.
        assertTrue(PortalHelper.registeredAperture(
                ARRIVAL_WORLD, new BlockPos(100, 80, 200), Direction.Axis.X).isEmpty());
    }

    @Test
    void anArrivalZoneIsRegisteredOnceAndFoundFromAnyOfItsCells() {
        registerDoorway();
        PortalHelper.PortalZone zone = PortalHelper.ensureArrivalZone(
                ARRIVAL_WORLD, doorway(), Direction.Axis.X, definition(), RETURN_WORLD);
        assertNotNull(zone);
        assertEquals(1, PortalHelper.getArrivalZones(ARRIVAL_WORLD).size());
        assertEquals(RETURN_WORLD, zone.targetWorld, "an arrival leads back where it came from");

        // Idempotent: the reuse path calls this on every traversal.
        PortalHelper.ensureArrivalZone(
                ARRIVAL_WORLD, doorway(), Direction.Axis.X, definition(), RETURN_WORLD);
        assertEquals(1, PortalHelper.getArrivalZones(ARRIVAL_WORLD).size());

        assertSame(zone, PortalHelper.arrivalZoneAt(ARRIVAL_WORLD, new BlockPos(101, 65, 200)));
        assertNull(PortalHelper.arrivalZoneAt(ARRIVAL_WORLD, new BlockPos(100, 80, 200)));
        assertNull(PortalHelper.arrivalZoneAt(OTHER_WORLD, new BlockPos(100, 64, 200)));
    }

    @Test
    void arrivalZonesAreNotSourceZones() {
        registerDoorway();
        PortalHelper.ensureArrivalZone(
                ARRIVAL_WORLD, doorway(), Direction.Axis.X, definition(), RETURN_WORLD);
        // The source-zone list drives the outbound teleport loop. An arrival
        // in it would send anyone standing in it straight back out.
        assertTrue(PortalHelper.getSourceZones(ARRIVAL_WORLD).isEmpty());
    }

    @Test
    void anArrivalRecordCarriesItsOwnRecordType() {
        registerDoorway();
        PortalHelper.PortalZone zone = PortalHelper.ensureArrivalZone(
                ARRIVAL_WORLD, doorway(), Direction.Axis.X, definition(), RETURN_WORLD);

        String arrival = GSON.toJson(PortalHelper.StoredPortalZone.fromArrival(zone));
        assertTrue(arrival.contains("\"recordType\":\"arrival-zone-v1\""));
        // A jar that predates arrival zones must not read one as a source
        // zone and claim the traversal; it logs an unknown record and drops it.
        assertFalse(arrival.contains(PortalHelper.StoredPortalZone.SOURCE_RECORD));

        String source = GSON.toJson(PortalHelper.StoredPortalZone.from(zone));
        assertTrue(source.contains("\"recordType\":\"source-zone-v1\""));
    }

    @Test
    void anArrivalRecordRoundTripsItsGeometry() {
        registerDoorway();
        PortalHelper.PortalZone zone = PortalHelper.ensureArrivalZone(
                ARRIVAL_WORLD, doorway(), Direction.Axis.X, definition(), RETURN_WORLD);
        PortalHelper.PortalZone restored = GSON.fromJson(
                GSON.toJson(PortalHelper.StoredPortalZone.fromArrival(zone)),
                PortalHelper.StoredPortalZone.class).toPortalZone();

        assertEquals(doorway(), restored.interior);
        assertEquals(Direction.Axis.X, restored.axis);
        assertEquals(ARRIVAL_WORLD, restored.sourceWorld, "an arrival stands in the world it is in");
        assertEquals(RETURN_WORLD, restored.targetWorld);
        assertEquals("minecraft:pink_concrete", restored.definition.getFrameBlock());
    }
}
