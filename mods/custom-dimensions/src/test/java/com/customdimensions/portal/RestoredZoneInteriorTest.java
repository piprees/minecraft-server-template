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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A route that fails validation on restore is dropped, and dropping it must
 * take its interior with it. A legacy gateway zone is one cell holding a real
 * {@code minecraft:end_gateway} with no ring, so under the frame-only rule
 * {@link PortalHelper#isZoneValid} fails it on every boot; the block outlives
 * the zone and the PORTAL_TARGETS entry, and
 * {@code EndGatewaySuppressionMixin} gates on exactly those two — so vanilla
 * End-island travel comes back on an orphan nobody can see is a portal.
 *
 * <p>The arrival side has always done this: {@code closeArrival} calls
 * {@code clearInteriorPortals} before dropping the zone.
 */
class RestoredZoneInteriorTest {

    private static final Gson GSON = new Gson();

    private static final RegistryKey<World> END =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "the_end"));
    private static final RegistryKey<World> REACHES =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure", "the_crumbling_reaches"));

    private static final BlockPos GATEWAY_CELL = new BlockPos(96, 75, -32);

    @AfterEach
    void reset() {
        PortalHelper.setPortalLinksPath(null);
        PortalHelper.loadPortalLinks();
    }

    private static PortalHelper.PortalZone legacyGateway() {
        PortalDefinition def = new PortalDefinition("the_crumbling_reaches", "minecraft:mud_bricks",
                "minecraft:ender_eye", "adventure:the_crumbling_reaches", "8B7050", 11);
        def.setShape("end_gateway");
        return new PortalHelper.PortalZone(
                Set.of(GATEWAY_CELL), def, Direction.Axis.X, END, REACHES);
    }

    /** Persists one source-zone record and reads it back into PENDING_ZONES. */
    private void persistAndLoad(Path runDir, PortalHelper.PortalZone zone) throws IOException {
        Path links = runDir.resolve("portal_links.json");
        Files.writeString(links, GSON.toJson(List.of(PortalHelper.StoredPortalZone.from(zone))));
        PortalHelper.setPortalLinksPath(links);
        PortalHelper.loadPortalLinks();
    }

    @Test
    void aDroppedRouteHasItsInteriorCleared(@TempDir Path runDir) throws IOException {
        persistAndLoad(runDir, legacyGateway());

        List<PortalHelper.PortalZone> cleared = new ArrayList<>();
        // The frame-only rule: a ringless gateway cell is never bounded by
        // its declared frame block, so validation declines it every boot.
        PortalHelper.restorePendingZones(END, zone -> false, cleared::add);

        assertEquals(1, cleared.size(),
                "the dropped route's interior was left standing in the world");
        assertEquals(Set.of(GATEWAY_CELL), cleared.get(0).interior,
                "the interior must be read off the record before it is discarded");
        assertTrue(PortalHelper.getSourceZones(END).isEmpty(), "the route is still dropped");
    }

    @Test
    void aRouteThatStillStandsIsRestoredAndNothingIsCleared(@TempDir Path runDir) throws IOException {
        persistAndLoad(runDir, legacyGateway());

        List<PortalHelper.PortalZone> cleared = new ArrayList<>();
        PortalHelper.restorePendingZones(END, zone -> true, cleared::add);

        assertTrue(cleared.isEmpty(), "a live portal's interior is never touched");
        assertEquals(1, PortalHelper.getSourceZones(END).size());
        assertEquals(Set.of(GATEWAY_CELL), PortalHelper.getSourceZones(END).get(0).interior);
    }

    @Test
    void aWorldWithNoPendingRoutesClearsNothing(@TempDir Path runDir) throws IOException {
        persistAndLoad(runDir, legacyGateway());

        List<PortalHelper.PortalZone> cleared = new ArrayList<>();
        PortalHelper.restorePendingZones(REACHES, zone -> false, cleared::add);

        assertTrue(cleared.isEmpty(), "another world's routes are not this world's to clear");
        assertTrue(PortalHelper.getSourceZones(REACHES).isEmpty());
    }
}
