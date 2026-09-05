package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
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
 * {@code findSourceZoneNear} — the lookup that stops the mod raising a second
 * frame beside the one the player built.
 *
 * <p>Ignition writes a source zone and nothing else, so
 * {@code findRegisteredPortalNear} — which reads the return-target map alone —
 * cannot see a player-lit frame. Before this lookup existed the traversal took
 * that silence for "no portal here" and built its own, then
 * {@code PortalSite.clearArrival} carved the footprint it landed on.
 *
 * <p>The fixture is the measured one: two 2x3 frames in the same dimension at
 * the same x and y, five blocks apart in z, which is exactly the box edge.
 */
class SourceZoneCounterpartTest {

    private static final RegistryKey<World> TARGET =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("elfydd", "e2e_two"));
    private static final RegistryKey<World> RETURN =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("elfydd", "e2e_one"));
    private static final RegistryKey<World> ELSEWHERE =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure", "the_canvas"));

    @BeforeEach
    @AfterEach
    void reset() {
        PortalHelper.loadPortalLinks();
    }

    /** A 2x3 vertical frame on the X axis, its plane at {@code z}. */
    private static PortalHelper.PortalZone lightFrame(RegistryKey<World> world,
            RegistryKey<World> target, int x, int y, int z) {
        Set<BlockPos> interior = new HashSet<>();
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                interior.add(new BlockPos(x + dx, y + dy, z));
            }
        }
        PortalHelper.PortalZone zone = new PortalHelper.PortalZone(
                interior, (PortalDefinition) null, Direction.Axis.X, world, target);
        PortalHelper.registerZone(zone);
        return zone;
    }

    @Test
    void findsThePlayerLitFrameTheRegistryCannotSee() {
        PortalHelper.PortalZone lit = lightFrame(TARGET, RETURN, 200, -60, 200);

        // The registry is empty of it — that silence is the whole defect.
        assertNull(PortalHelper.findRegisteredPortalNear(TARGET, 200, -60, 205, 5, 16),
                "a lit frame is never written into the return-target map");

        PortalHelper.ZoneCell found =
                PortalHelper.findSourceZoneNear(TARGET, RETURN, 200, -60, 205, 5, 16);
        assertNotNull(found, "the frame the player lit five blocks away must be found");
        assertSame(lit, found.zone());
        assertEquals(new BlockPos(200, -60, 200), found.cell(),
                "the bottom row of the nearest column is where the player lands");
    }

    @Test
    void aZoneLeadingSomewhereElseIsNotACounterpart() {
        lightFrame(TARGET, ELSEWHERE, 200, -60, 200);

        assertNull(PortalHelper.findSourceZoneNear(TARGET, RETURN, 200, -60, 205, 5, 16),
                "a doorway to a third dimension is not the way back");
        assertNull(PortalHelper.findSourceZoneNear(TARGET, null, 200, -60, 205, 5, 16));
    }

    @Test
    void zonesInAnotherWorldAreNeverAnswered() {
        lightFrame(RETURN, TARGET, 200, -60, 200);

        assertNull(PortalHelper.findSourceZoneNear(TARGET, RETURN, 200, -60, 200, 5, 16));
    }

    @Test
    void searchBoxIsRespectedOnBothAxes() {
        lightFrame(TARGET, RETURN, 206, -60, 200);   // 6 out on X
        lightFrame(TARGET, RETURN, 200, -60, 206);   // 6 out on Z
        lightFrame(TARGET, RETURN, 200, -83, 200);   // 23 down: outside radiusV 16

        assertNull(PortalHelper.findSourceZoneNear(TARGET, RETURN, 200, -60, 200, 5, 16));

        lightFrame(TARGET, RETURN, 205, -76, 205);   // exactly on all three bounds
        PortalHelper.ZoneCell edge =
                PortalHelper.findSourceZoneNear(TARGET, RETURN, 200, -60, 200, 5, 16);
        assertNotNull(edge);
        assertEquals(new BlockPos(205, -76, 205), edge.cell());
    }

    @Test
    void scanOrderMatchesTheRegistryLookup() {
        // (x, z, y) ascending, first hit wins — the same order an arrival is
        // picked in, so a counterpart and an arrival cannot land a player on
        // different cells of the same box.
        lightFrame(TARGET, RETURN, 202, -62, 200);   // lowest y, larger x
        lightFrame(TARGET, RETURN, 198, -60, 202);   // smallest x, larger z
        lightFrame(TARGET, RETURN, 198, -58, 201);   // smallest x, smaller z -> wins

        PortalHelper.ZoneCell found =
                PortalHelper.findSourceZoneNear(TARGET, RETURN, 200, -60, 200, 5, 16);
        assertNotNull(found);
        assertEquals(new BlockPos(198, -58, 201), found.cell());
    }

    @Test
    void anArrivalIsNotACounterpart() {
        // Arrival cells belong to findRegisteredPortalNear. Answering them
        // here too would hand the traversal a zone that does not exist.
        PortalHelper.registerPortal(TARGET, new BlockPos(200, -60, 200), RETURN, -60, 0x8844FF, 40, null);

        assertNull(PortalHelper.findSourceZoneNear(TARGET, RETURN, 200, -60, 200, 5, 16));
        assertNotNull(PortalHelper.findRegisteredPortalNear(TARGET, 200, -60, 200, 5, 16));
    }
}
