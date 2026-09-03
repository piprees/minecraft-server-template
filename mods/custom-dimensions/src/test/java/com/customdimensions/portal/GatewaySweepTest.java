package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The ignition gate itself, over a stronghold-shaped world. An ender eye
 * right-clicked on an {@code end_portal_frame} runs {@link IgnitionScan#sweep}
 * for every gateway definition sharing that igniter — six neighbours, then a
 * 7x7x7 box — and every one of them must decline, or the eye never reaches
 * vanilla and the twelve-eye ritual is impossible.
 *
 * <p>Declining is only meaningful next to igniting, so the same fixture shape
 * lights a real mud-brick ring: a sweep that always returned null would pass
 * the stronghold cases for no reason at all.
 */
class GatewaySweepTest {

    /**
     * A block-id map standing in for the world. Everything unnamed is
     * stone_bricks — a stronghold is solid rock with rooms cut out of it.
     */
    private static final class Fixture implements FrameView {
        private static final String SOLID = "minecraft:stone_bricks";
        private static final String AIR = "minecraft:air";

        private final Map<BlockPos, String> blocks = new HashMap<>();

        Fixture set(BlockPos pos, String blockId) {
            this.blocks.put(pos, blockId);
            return this;
        }

        Fixture box(int x0, int x1, int y0, int y1, int z0, int z1, String blockId) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        this.blocks.put(new BlockPos(x, y, z), blockId);
                    }
                }
            }
            return this;
        }

        /** An air cell ringed on one axis-X plane by the given block. */
        Fixture ring(BlockPos interior, String frameBlock) {
            this.set(interior, AIR);
            this.set(interior.west(), frameBlock);
            this.set(interior.east(), frameBlock);
            this.set(interior.up(), frameBlock);
            this.set(interior.down(), frameBlock);
            return this;
        }

        String at(BlockPos pos) {
            return this.blocks.getOrDefault(pos, SOLID);
        }

        @Override
        public boolean isFillable(BlockPos pos) {
            return AIR.equals(this.at(pos));
        }

        @Override
        public boolean matches(BlockPos pos, FrameMatcher matcher) {
            return matcher.acceptsBlockId(this.at(pos));
        }
    }

    // The clicked end_portal_frame: the middle of the ring's north side.
    private static final BlockPos CLICK = new BlockPos(1, 63, -1);

    /**
     * A stronghold portal room: twelve end_portal_frame blocks around an
     * unlit 3x3 at y=63, lava beneath it, and the chamber's air above.
     */
    private static Fixture strongholdRoom() {
        Fixture fixture = new Fixture();
        fixture.box(-3, 5, 64, 67, -3, 5, "minecraft:air");
        fixture.box(0, 2, 63, 63, 0, 2, "minecraft:air");
        fixture.box(0, 2, 62, 62, 0, 2, "minecraft:lava");
        for (int i = 0; i <= 2; i++) {
            fixture.set(new BlockPos(i, 63, -1), "minecraft:end_portal_frame");
            fixture.set(new BlockPos(i, 63, 3), "minecraft:end_portal_frame");
            fixture.set(new BlockPos(-1, 63, i), "minecraft:end_portal_frame");
            fixture.set(new BlockPos(3, 63, i), "minecraft:end_portal_frame");
        }
        return fixture;
    }

    private static PortalDefinition gateway(String id, String frameBlock) {
        PortalDefinition def = new PortalDefinition(
                id, frameBlock, "minecraft:ender_eye", "adventure:" + id, "8B7050", 11);
        def.setShape("end_gateway");
        return def;
    }

    private static PortalDefinition crumblingReaches() {
        return gateway("the_crumbling_reaches", "minecraft:mud_bricks");
    }

    private static PortalDefinition pillaredVoid() {
        return gateway("the_pillared_void", "minecraft:polished_basalt");
    }

    private static IgnitionScan.Site sweep(Fixture fixture, BlockPos clicked, PortalDefinition def) {
        return IgnitionScan.sweep(fixture, clicked, def.resolveFrameMatcher(), def);
    }

    // === the ritual =====================================================

    @Test
    void noShippedGatewayIgnitesInAStrongholdPortalRoom() {
        Fixture room = strongholdRoom();
        assertNull(sweep(room, CLICK, crumblingReaches()),
                "an eye on a stronghold frame must fall through to vanilla and socket");
        assertNull(sweep(room, CLICK, pillaredVoid()));
    }

    @Test
    void mudBeneathTheRoomIsNotAFrame() {
        // The 343-cell box sweeps the floor in: a gateway's own block being
        // NEARBY is not a gateway, only a ring around one cell is.
        Fixture room = strongholdRoom().box(-3, 5, 62, 62, -3, 5, "minecraft:mud_bricks");
        assertNull(sweep(room, CLICK, crumblingReaches()));
    }

    @Test
    void threeSidesOfMudIsNotARing() {
        BlockPos pocket = new BlockPos(3, 65, -1);
        Fixture room = strongholdRoom()
                .ring(pocket, "minecraft:mud_bricks")
                .set(pocket.down(), "minecraft:stone_bricks");
        assertNull(sweep(room, CLICK, crumblingReaches()));
    }

    @Test
    void aStrongholdNextToAnotherDimensionsFrameStillDeclines() {
        // polished_basalt rings a cell in reach of the click; the mud-brick
        // gateway must not claim someone else's frame.
        Fixture room = strongholdRoom().ring(new BlockPos(3, 65, -1), "minecraft:polished_basalt");
        assertNull(sweep(room, CLICK, crumblingReaches()));
    }

    // === and the same sweep does light a real one ========================

    @Test
    void aMudBrickRingInsideTheBoxIgnites() {
        BlockPos cell = new BlockPos(3, 65, -1);
        Fixture room = strongholdRoom().ring(cell, "minecraft:mud_bricks");

        IgnitionScan.Site site = sweep(room, CLICK, crumblingReaches());

        assertNotNull(site, "the box reaches it, and one empty cell in a mud ring is a gateway");
        assertEquals(Set.of(cell), site.fill());
        assertEquals(Direction.Axis.X, site.axis());
        assertEquals(cell, site.soundPos(), "the box pass sounds at the candidate, not the click");
    }

    @Test
    void aMudBrickRingFourBlocksOutIsBeyondTheBox() {
        // The click is at x=1 and the box reaches three: a ring at x=5 is
        // some other portal in the room, not the one the player clicked.
        BlockPos cell = new BlockPos(5, 65, -1);
        Fixture room = strongholdRoom().ring(cell, "minecraft:mud_bricks");
        assertNull(sweep(room, CLICK, crumblingReaches()));
    }

    @Test
    void theClickedBlocksOwnNeighbourIsTriedBeforeTheBox() {
        BlockPos cell = new BlockPos(1, 65, 0);
        Fixture solid = new Fixture().ring(cell, "minecraft:mud_bricks");
        BlockPos clickedFrame = cell.west();

        IgnitionScan.Site site = sweep(solid, clickedFrame, crumblingReaches());

        assertNotNull(site);
        assertEquals(Set.of(cell), site.fill());
        assertEquals(Direction.Axis.X, site.axis());
        assertEquals(clickedFrame, site.soundPos(), "the neighbour pass sounds at the clicked block");
    }

    // === the gates the sweep runs each candidate through =================

    @Test
    void twoStackedCellsAreNoGatewayButAreAStandardPortal() {
        BlockPos lower = new BlockPos(3, 65, -1);
        BlockPos upper = lower.up();
        Fixture room = strongholdRoom()
                .set(lower, "minecraft:air")
                .set(upper, "minecraft:air")
                .set(lower.west(), "minecraft:mud_bricks")
                .set(lower.east(), "minecraft:mud_bricks")
                .set(upper.west(), "minecraft:mud_bricks")
                .set(upper.east(), "minecraft:mud_bricks")
                .set(lower.down(), "minecraft:mud_bricks")
                .set(upper.up(), "minecraft:mud_bricks");

        assertNull(sweep(room, CLICK, crumblingReaches()),
                "end_gateway is a frame around ONE cell");

        PortalDefinition standard = new PortalDefinition("the_crumbling_reaches",
                "minecraft:mud_bricks", "minecraft:ender_eye",
                "adventure:the_crumbling_reaches", "8B7050", 11);
        IgnitionScan.Site site = sweep(room, CLICK, standard);

        assertNotNull(site, "the same opening under the default shape is a portal");
        assertEquals(Set.of(lower, upper), site.fill());
        assertEquals(lower, site.soundPos(), "the box sweeps -x,-y,-z first");
    }

    @Test
    void aGatewayDeclaringNoFrameSweepsNothing() {
        Fixture room = strongholdRoom().ring(new BlockPos(3, 65, -1), "minecraft:mud_bricks");
        PortalDefinition def = gateway("the_open_door", null);
        def.setFrameAccepts(List.of());
        assertNull(sweep(room, CLICK, def));
    }
}
