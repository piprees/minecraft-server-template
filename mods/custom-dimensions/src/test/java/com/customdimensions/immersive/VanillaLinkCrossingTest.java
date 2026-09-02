package com.customdimensions.immersive;

import com.customdimensions.config.PortalDefinition;
import com.customdimensions.portal.PortalHelper;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A crossing the mod watched vanilla make names both ends for certain, so the
 * preview needs no search and no resident far side. Recorded for both
 * directions, because a zone only ever asks about its own target.
 *
 * <p>The geometry is one measured pair of linked vanilla portals: overworld
 * interior x-3855, z3260-3262, y-22..-17 and the Nether portal vanilla linked
 * it to at x-493, z401-402, y73-75.
 *
 * <p>A portal nobody has crossed this session still has no link, and that is
 * the intended behaviour rather than a gap — no preview before first use.
 */
class VanillaLinkCrossingTest {

    private static final RegistryKey<World> OVERWORLD =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));
    private static final RegistryKey<World> THE_NETHER =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "the_nether"));

    private static Set<BlockPos> interior(int x, int y, int z, int wide, int tall) {
        Set<BlockPos> out = new HashSet<>();
        for (int dz = 0; dz < wide; dz++) {
            for (int dy = 0; dy < tall; dy++) {
                out.add(new BlockPos(x, y + dy, z + dz));
            }
        }
        return out;
    }

    private static Set<BlockPos> overworldPortal() {
        return interior(-3855, -22, 3260, 3, 6);
    }

    private static Set<BlockPos> netherPortal() {
        return interior(-493, 73, 401, 2, 3);
    }

    private static PortalHelper.PortalZone zone(Set<BlockPos> interior,
            RegistryKey<World> source, RegistryKey<World> target) {
        PortalDefinition def = new PortalDefinition("the_nether", "minecraft:obsidian",
                "minecraft:flint_and_steel", "minecraft:the_nether", "#8844FF", 0);
        def.setScale(8.0);
        return new PortalHelper.PortalZone(interior, def, Direction.Axis.Z, source, target);
    }

    /** The zone standing in the overworld, previewing into the Nether. */
    private static PortalHelper.PortalZone overworldZone() {
        return zone(overworldPortal(), OVERWORLD, THE_NETHER);
    }

    /** The zone standing in the Nether. Its target normalises to the overworld. */
    private static PortalHelper.PortalZone netherZone() {
        return zone(netherPortal(), THE_NETHER, THE_NETHER);
    }

    private static void recordTheMeasuredCrossing() {
        VanillaLinkResolver.recordCrossing(
                OVERWORLD, overworldPortal(), THE_NETHER, netherPortal(), 100L);
    }

    @BeforeEach
    void reset() {
        VanillaLinkResolver.clear();
    }

    // === the negative control ============================================

    @Test
    void aPortalNobodyHasCrossedHasNoLink() {
        assertNull(VanillaLinkResolver.cachedLink(THE_NETHER, overworldZone()));
        assertNull(VanillaLinkResolver.cachedLink(World.OVERWORLD, netherZone()));
    }

    // === both directions =================================================

    @Test
    void theSideThatEnteredFindsTheFarPortal() {
        recordTheMeasuredCrossing();
        assertEquals(new BlockPos(-493, 73, 401),
                VanillaLinkResolver.cachedLink(THE_NETHER, overworldZone()));
    }

    @Test
    void theFarSideFindsTheWayBack() {
        // The leg that a search cannot answer without the overworld resident.
        recordTheMeasuredCrossing();
        assertEquals(new BlockPos(-3855, -22, 3261),
                VanillaLinkResolver.cachedLink(World.OVERWORLD, netherZone()));
    }

    @Test
    void aReturnCrossingRecordsTheSamePair() {
        // Whichever way the player walked first, the pair is the same.
        VanillaLinkResolver.recordCrossing(
                THE_NETHER, netherPortal(), OVERWORLD, overworldPortal(), 100L);

        assertEquals(new BlockPos(-493, 73, 401),
                VanillaLinkResolver.cachedLink(THE_NETHER, overworldZone()));
        assertEquals(new BlockPos(-3855, -22, 3261),
                VanillaLinkResolver.cachedLink(World.OVERWORLD, netherZone()));
    }

    // === what the link has to be =========================================

    @Test
    void theLinkIsTheFloorRowNotTheCentreRow() {
        // The projection lands the source interior's floor on this Y, and
        // search answers the lowest row for the same reason. The Nether
        // portal's centre row is y74; its floor is y73.
        recordTheMeasuredCrossing();
        assertEquals(73, VanillaLinkResolver.cachedLink(THE_NETHER, overworldZone()).getY());
        assertEquals(-22, VanillaLinkResolver.cachedLink(World.OVERWORLD, netherZone()).getY());
    }

    // === the key has to be the one resolve reads =========================

    @Test
    void anotherPortalInTheSameWorldIsNotThisLink() {
        recordTheMeasuredCrossing();
        Set<BlockPos> elsewhere = interior(-3855, -22, 3300, 3, 6);
        assertNull(VanillaLinkResolver.cachedLink(THE_NETHER, zone(elsewhere, OVERWORLD, THE_NETHER)));
    }

    @Test
    void theSameGeometryInAnotherWorldIsNotThisLink() {
        recordTheMeasuredCrossing();
        RegistryKey<World> elsewhere =
                RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure", "the_crucible"));
        assertNull(VanillaLinkResolver.cachedLink(
                THE_NETHER, zone(overworldPortal(), elsewhere, THE_NETHER)));
    }

    @Test
    void aLinkIsNotVisibleFromAWorldItDoesNotLeadTo() {
        recordTheMeasuredCrossing();
        RegistryKey<World> elsewhere =
                RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure", "the_crucible"));
        assertNull(VanillaLinkResolver.cachedLink(elsewhere, overworldZone()));
    }

    // === lifecycle =======================================================

    @Test
    void unloadingAWorldDropsTheLinksIntoIt() {
        recordTheMeasuredCrossing();
        VanillaLinkResolver.invalidate(THE_NETHER);

        assertNull(VanillaLinkResolver.cachedLink(THE_NETHER, overworldZone()),
                "links INTO the unloaded world go");
        assertEquals(new BlockPos(-3855, -22, 3261),
                VanillaLinkResolver.cachedLink(World.OVERWORLD, netherZone()),
                "links into a world still loaded stay");
    }

    @Test
    void anEmptyInteriorRecordsNothing() {
        VanillaLinkResolver.recordCrossing(OVERWORLD, Set.of(), THE_NETHER, netherPortal(), 100L);
        assertNull(VanillaLinkResolver.cachedLink(World.OVERWORLD, netherZone()));
    }
}
