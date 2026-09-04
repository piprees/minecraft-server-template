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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A persisted arrival zone waits in the pending map until its own world first
 * ticks, and most worlds never tick — nobody has travelled there this boot.
 * Every lookup that decides whether an arrival already exists has to see it
 * there, or the same arrival is registered twice and breaking its source
 * portal leaves it standing.
 */
class PendingArrivalZoneTest {

    private static final Gson GSON = new Gson();

    private static final RegistryKey<World> ARRIVAL_WORLD =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure", "the_crimson_nexus"));
    private static final RegistryKey<World> RETURN_WORLD =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));

    @AfterEach
    void reset() {
        PortalHelper.setPortalLinksPath(null);
        PortalHelper.loadPortalLinks();
    }

    private static PortalDefinition definition() {
        return new PortalDefinition("the_crimson_nexus", "minecraft:nether_bricks",
                "minecraft:flint_and_steel", "adventure:the_crimson_nexus", "AF2B2B", 11);
    }

    /** A 2x3 vertical opening on the X axis, the nexus arrival's shape. */
    private static Set<BlockPos> doorway() {
        Set<BlockPos> interior = new HashSet<>();
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                interior.add(new BlockPos(750 + dx, 57 + dy, 750));
            }
        }
        return interior;
    }

    private static PortalHelper.PortalZone arrivalZone() {
        return new PortalHelper.PortalZone(
                doorway(), definition(), Direction.Axis.X, ARRIVAL_WORLD, RETURN_WORLD);
    }

    /** Persists one arrival record and reads it back into the pending map. */
    private void persistAndLoad(Path runDir) throws IOException {
        Path links = runDir.resolve("portal_links.json");
        Files.writeString(links,
                GSON.toJson(List.of(PortalHelper.StoredPortalZone.fromArrival(arrivalZone()))));
        PortalHelper.setPortalLinksPath(links);
        PortalHelper.loadPortalLinks();
    }

    @Test
    void aPendingArrivalZoneIsStillFoundByItsCell(@TempDir Path runDir) throws IOException {
        persistAndLoad(runDir);

        // The condition under test, asserted rather than assumed: the zone is
        // persisted but not promoted, because its world has not ticked.
        assertTrue(PortalHelper.getArrivalZones(ARRIVAL_WORLD).isEmpty(),
                "precondition: the restored zone should still be pending");

        assertNotNull(PortalHelper.arrivalZoneAt(ARRIVAL_WORLD, new BlockPos(750, 58, 750)),
                "a pending arrival is invisible to the point lookup, so breaking its source "
                + "portal cannot remove it and it is left standing");
    }

    @Test
    void removingAPendingArrivalZoneActuallyRemovesIt(@TempDir Path runDir) throws IOException {
        // Finding the zone is only half of it. Both break paths look it up and
        // then drop it, and dropping it from the live map alone leaves a zone
        // that every later lookup still finds.
        persistAndLoad(runDir);
        BlockPos cell = new BlockPos(750, 58, 750);
        PortalHelper.PortalZone zone = PortalHelper.arrivalZoneAt(ARRIVAL_WORLD, cell);
        assertNotNull(zone, "precondition: the pending zone is findable");

        PortalHelper.removeArrivalZone(ARRIVAL_WORLD, zone);

        assertNull(PortalHelper.arrivalZoneAt(ARRIVAL_WORLD, cell),
                "a removed arrival must be gone from the pending map too, or breaking its "
                + "source portal deregisters the cells and leaves the zone behind");
    }

    @Test
    void aPendingArrivalZoneIsNotRegisteredTwice(@TempDir Path runDir) throws IOException {
        persistAndLoad(runDir);

        PortalHelper.PortalZone zone = PortalHelper.ensureArrivalZone(
                ARRIVAL_WORLD, doorway(), Direction.Axis.X, definition(), RETURN_WORLD);

        assertNotNull(zone);
        assertEquals(doorway(), zone.interior, "the pending zone is the one that already exists");
        assertTrue(PortalHelper.getArrivalZones(ARRIVAL_WORLD).isEmpty(),
                "an arrival that already exists must not be added a second time; the live map "
                + "should still be empty because the pending zone was reused");
    }
}
