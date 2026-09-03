package com.customdimensions.portal;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code findRegisteredPortalNear} — the in-memory half of {@code
 * ArrivalResolver}, and the reason the immersive preview stopped previewing
 * the sky four blocks above the destination.
 *
 * <p>Building an arrival portal raises the target column's
 * MOTION_BLOCKING_NO_LEAVES heightmap (the frame we place above the top
 * interior row is solid), so from the first traversal onwards the heightmap
 * no longer describes where a player lands — the player is teleported into
 * the EXISTING portal instead. This lookup is what lets the preview and
 * entity pass-through find that portal without reading a single block.
 *
 * <p>The ordering assertions are not pedantry: this lookup is the ONLY way
 * an arrival is found — a portal has no blocks in it to scan for — and the
 * player path, the preview and entity pass-through must all land on the same
 * one when several are in the box. The order is the nested dx/dz/dy walk the
 * block scan used, kept so those three agree.
 */
class RegisteredPortalLookupTest {

    private static final RegistryKey<World> TARGET =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure", "the_blossom_gardens"));
    private static final RegistryKey<World> OTHER =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure", "the_canvas"));
    private static final RegistryKey<World> SOURCE =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));

    @BeforeEach
    @AfterEach
    void reset() {
        // Clears every in-memory portal map. With no run directory set the
        // load is a pure reset — it never touches the filesystem.
        PortalHelper.loadPortalLinks();
    }

    private static void register(RegistryKey<World> world, int x, int y, int z) {
        PortalHelper.registerPortal(world, new BlockPos(x, y, z), SOURCE, 64, 0x8844FF, 40, null);
    }

    @Test
    void findsTheArrivalPortalRegisteredAtTheColumn() {
        // The measured fixture: interior y=63..65 at the arrival column,
        // frame rows at 62 and 66 — so the heightmap says 67 while the
        // player lands at 63.
        for (int y = 63; y <= 65; y++) {
            register(TARGET, 3000, y, 3000);
            register(TARGET, 3001, y, 3000);
        }
        BlockPos found = PortalHelper.findRegisteredPortalNear(TARGET, 3000, 67, 3000, 5, 16);
        assertNotNull(found, "an arrival portal 4 blocks below the polluted surface must still be found");
        assertEquals(63, found.getY(), "the portal's bottom row is where the player lands");
        assertEquals(new BlockPos(3000, 63, 3000), found);
    }

    @Test
    void nothingRegisteredMeansNoAnswer() {
        assertNull(PortalHelper.findRegisteredPortalNear(TARGET, 3000, 67, 3000, 5, 16));
        register(TARGET, 3000, 63, 3000);
        // Right column, wrong world.
        assertNull(PortalHelper.findRegisteredPortalNear(OTHER, 3000, 67, 3000, 5, 16));
    }

    @Test
    void searchBoxIsRespectedOnBothAxes() {
        register(TARGET, 3006, 63, 3000);   // 6 out on X: outside radiusH 5
        register(TARGET, 3000, 63, 3006);   // 6 out on Z
        register(TARGET, 3000, 46, 3000);   // 21 down: outside radiusV 16
        assertNull(PortalHelper.findRegisteredPortalNear(TARGET, 3000, 67, 3000, 5, 16));

        register(TARGET, 3005, 51, 3005);   // exactly on all three bounds
        assertEquals(new BlockPos(3005, 51, 3005),
                PortalHelper.findRegisteredPortalNear(TARGET, 3000, 67, 3000, 5, 16));
    }

    @Test
    void scanOrderIsDxThenDzThenDy() {
        // dx, then dz, then dy — all ascending — first hit wins. So the
        // winner is the lexicographic
        // minimum by (x, z, y), NOT the lowest block and NOT BlockPos's own
        // (y, z, x) ordering.
        register(TARGET, 3002, 60, 3000);   // lowest Y, but a larger X
        register(TARGET, 2998, 70, 3002);   // smallest X, larger Z
        register(TARGET, 2998, 68, 3001);   // smallest X, smaller Z -> wins
        register(TARGET, 2998, 69, 3001);   // same column, higher

        assertEquals(new BlockPos(2998, 68, 3001),
                PortalHelper.findRegisteredPortalNear(TARGET, 3000, 67, 3000, 5, 16));
    }

    @Test
    void lowestRowWinsWithinTheSameColumn() {
        // The normal case: one arrival portal, several interior rows
        // registered. The bottom row is the landing row.
        register(TARGET, 3000, 65, 3000);
        register(TARGET, 3000, 63, 3000);
        register(TARGET, 3000, 64, 3000);
        assertEquals(63, PortalHelper.findRegisteredPortalNear(TARGET, 3000, 67, 3000, 5, 16).getY());
    }

    @Test
    void lookupIsSideEffectFree() {
        register(TARGET, 3000, 63, 3000);
        PortalHelper.findRegisteredPortalNear(TARGET, 3000, 67, 3000, 5, 16);
        // The registration must survive the lookup: unlike getPortalTarget,
        // this must never claim, migrate or persist anything — it runs from
        // the world tick for every immersive zone.
        assertTrue(PortalHelper.isRegisteredPortalPosition(TARGET, new BlockPos(3000, 63, 3000)));
        assertEquals(new BlockPos(3000, 63, 3000),
                PortalHelper.findRegisteredPortalNear(TARGET, 3000, 67, 3000, 5, 16));
    }
}
