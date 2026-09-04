package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A refused ignition must say why. Every gate on the path names its own
 * reason, and one click reaches 349 candidate cells across three axes, so the
 * sweep reports the attempt that came CLOSEST to a portal rather than the
 * first cell it happened to try — the first refusal is almost never the one
 * the player needs to hear.
 */
class IgnitionRefusalTest {

    private static final String COPPER = "minecraft:copper_block";
    private static final String GOLD = "minecraft:gold_block";
    private static final String AIR = "minecraft:air";
    private static final String STONE = "minecraft:stone";

    /** A block-id map standing in for the world; everything unnamed is stone. */
    private static final class Fixture implements FrameView {
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

        /** One air cell ringed on the X plane (west/east/up/down). */
        Fixture ring(BlockPos interior, String frameBlock) {
            this.set(interior, AIR);
            this.set(interior.west(), frameBlock);
            this.set(interior.east(), frameBlock);
            this.set(interior.up(), frameBlock);
            this.set(interior.down(), frameBlock);
            return this;
        }

        String at(BlockPos pos) {
            return this.blocks.getOrDefault(pos, STONE);
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

    private static final BlockPos CELL = new BlockPos(0, 64, 0);
    private static final BlockPos CLICK = CELL.west();

    private static PortalDefinition crucible() {
        return new PortalDefinition("the_crucible", COPPER, "minecraft:diamond",
                "adventure:the_crucible", "8BAF5B", 11);
    }

    private static IgnitionScan.Attempt attempt(Fixture fixture, BlockPos clicked, PortalDefinition def) {
        return IgnitionScan.sweepDetailed(fixture, clicked, def.resolveFrameMatcher(), def);
    }

    private static IgnitionScan.Refusal refusal(Fixture fixture, BlockPos clicked, PortalDefinition def) {
        IgnitionScan.Attempt outcome = attempt(fixture, clicked, def);
        assertNull(outcome.site(), "expected a refusal, but the sweep lit a portal");
        assertNotNull(outcome.refusal(), "a refused ignition must carry a reason");
        return outcome.refusal();
    }

    private static IgnitionRefusal reason(Fixture fixture, BlockPos clicked, PortalDefinition def) {
        return refusal(fixture, clicked, def).reason();
    }

    // === the positive control ============================================

    @Test
    void aFrameThatLightsCarriesNoRefusal() {
        Fixture world = new Fixture().ring(CELL, COPPER);

        IgnitionScan.Attempt outcome = attempt(world, CLICK, crucible());

        assertNotNull(outcome.site(), "a copper ring clicked with its own igniter is a portal");
        assertEquals(Direction.Axis.X, outcome.site().axis());
        assertNull(outcome.refusal(), "a portal that lit has nothing to refuse");
    }

    // === one reason per gate =============================================

    @Test
    void aDefinitionWithNoFrameMaterialSaysSo() {
        Fixture world = new Fixture().ring(CELL, COPPER);
        PortalDefinition def = crucible();
        def.setFrameBlock(null);
        def.setFrameAccepts(List.of());

        IgnitionScan.Refusal refused = refusal(world, CLICK, def);

        assertEquals(IgnitionRefusal.NO_FRAME_MATERIAL, refused.reason());
        assertEquals(CLICK, refused.at(), "there is no candidate cell to blame, so the click is");
    }

    @Test
    void solidRockNamesTheMissingOpening() {
        Fixture world = new Fixture();

        IgnitionScan.Refusal refused = refusal(world, CLICK, crucible());

        assertEquals(IgnitionRefusal.NO_CANDIDATE_CELL, refused.reason());
        assertEquals(CLICK, refused.at());
    }

    @Test
    void anOpeningThatLeaksSaysItLeaks() {
        // One air cell in solid stone: the fill escapes on its first
        // neighbour, and no axis gets any further.
        Fixture world = new Fixture().set(CELL, AIR);

        IgnitionScan.Refusal refused = refusal(world, CLICK, crucible());

        assertEquals(IgnitionRefusal.OPENING_NOT_ENCLOSED, refused.reason());
        assertEquals(CELL, refused.at(), "the cell the fill started from is the one to name");
        assertEquals(CELL.west(), refused.blockedBy(),
                "the block the fill ran into IS the answer — the west neighbour is tried first");
    }

    @Test
    void aWeatheredFrameBlockIsNamedAsTheBlocker() {
        // The Crucible's live failure: fourteen copper blocks and one that
        // oxidised, which the matcher no longer accepts.
        BlockPos bottom = CELL.down();
        Fixture world = new Fixture().ring(CELL, COPPER).set(bottom, "minecraft:exposed_copper");

        IgnitionScan.Refusal refused = refusal(world, CLICK, crucible());

        assertEquals(IgnitionRefusal.OPENING_NOT_ENCLOSED, refused.reason());
        assertEquals(bottom, refused.blockedBy(),
                "one block out of a whole ring, named outright");
    }

    @Test
    void aRefusalWithNoSingleBlockToBlameNamesNone() {
        Fixture world = new Fixture().ring(CELL, COPPER);
        PortalDefinition def = crucible();
        def.setShape(PortalShape.DOOR);

        assertNull(refusal(world, CLICK, def).blockedBy(),
                "a shape is not one block's fault, and must not point at one");
    }

    @Test
    void anOpeningOverTheCellCapSaysItIsTooLarge() {
        // A 12x12x12 room inside a copper shell: every plane through it is
        // 144 cells, past the 128 the fill will carry.
        Fixture world = new Fixture()
                .box(-7, 6, 57, 70, -7, 6, COPPER)
                .box(-6, 5, 58, 69, -6, 5, AIR);

        IgnitionScan.Refusal refused = refusal(world, new BlockPos(-7, 64, 0), crucible());

        assertEquals(IgnitionRefusal.OPENING_TOO_LARGE, refused.reason());
        assertTrue(refused.cells() > PortalHelper.MAX_PORTAL_BLOCKS,
                "the count on the line must be the count that broke the cap, not zero");
    }

    @Test
    void anOpeningOfTheWrongShapeNamesTheShape() {
        Fixture world = new Fixture().ring(CELL, COPPER);
        PortalDefinition def = crucible();
        def.setShape(PortalShape.DOOR);

        IgnitionScan.Refusal refused = refusal(world, CLICK, def);

        assertEquals(IgnitionRefusal.SHAPE_MISMATCH, refused.reason());
        assertEquals(Direction.Axis.X, refused.axis(), "the axis that got furthest is the one to name");
        assertEquals(1, refused.cells());
    }

    @Test
    void anOpeningOffTheTemplateNamesThePattern() {
        Fixture world = new Fixture().ring(CELL, COPPER);
        PortalDefinition def = crucible();
        def.setShape(PortalShape.PATTERN);
        def.setShapeTemplate(List.of("FFF", "FIF", "FIF", "FFF"));
        def.setShapeLegend(Map.of("F", "frame", "I", "interior"));

        assertEquals(IgnitionRefusal.PATTERN_MISMATCH, reason(world, CLICK, def));
    }

    @Test
    void aFramePartOfTheWrongMaterialNamesThePart() {
        Fixture world = new Fixture().ring(CELL, COPPER);
        PortalDefinition def = crucible();
        def.setFramePartAccepts(Map.of("top", List.of(GOLD)));

        assertEquals(IgnitionRefusal.FRAME_PART_MISMATCH, reason(world, CLICK, def));
    }

    @Test
    void aFrameStandingTheWrongWayNamesTheOrientation() {
        Fixture world = new Fixture().ring(CELL, COPPER);
        PortalDefinition def = crucible();
        def.setOrientation("horizontal");

        IgnitionScan.Refusal refused = refusal(world, CLICK, def);

        assertEquals(IgnitionRefusal.AXIS_NOT_ALLOWED, refused.reason());
        assertEquals(Direction.Axis.X, refused.axis(),
                "the axis the player actually built on is the one to name");
        assertEquals(1, refused.cells(),
                "orientation is judged after the geometry, so the opening was measured first");
    }

    // === the sweep reports the closest miss, not the first ================

    @Test
    void theClosestMissWinsOverAnEarlierOne() {
        // A leaking cell sits nearer the click than the ringed one, and the
        // box reaches both. A gateway wants exactly one cell; the ringed
        // opening has two, so it refuses on SHAPE — which is the interesting
        // answer, and the one a first-refusal-wins report would lose.
        BlockPos lower = new BlockPos(2, 65, 0);
        BlockPos upper = lower.up();
        Fixture world = new Fixture()
                .set(CELL, AIR)
                .set(lower, AIR)
                .set(upper, AIR)
                .set(lower.west(), COPPER)
                .set(lower.east(), COPPER)
                .set(upper.west(), COPPER)
                .set(upper.east(), COPPER)
                .set(lower.down(), COPPER)
                .set(upper.up(), COPPER);
        PortalDefinition def = crucible();
        def.setShape(PortalShape.END_GATEWAY);

        IgnitionScan.Refusal refused = refusal(world, CLICK, def);

        assertEquals(IgnitionRefusal.SHAPE_MISMATCH, refused.reason());
        assertEquals(2, refused.cells(), "the opening it judged is the two-cell one");
    }

    @Test
    void progressOrderRanksEveryReason() {
        // The ranking is what makes "closest miss" mean anything: an early
        // gate must never outrank a late one.
        IgnitionRefusal[] byProgress = IgnitionRefusal.values();
        for (int i = 1; i < byProgress.length; i++) {
            assertTrue(byProgress[i - 1].progress() < byProgress[i].progress(),
                    byProgress[i - 1] + " must rank below " + byProgress[i]);
        }
        assertEquals(IgnitionRefusal.SHAPE_MISMATCH,
                IgnitionRefusal.furthest(IgnitionRefusal.OPENING_NOT_ENCLOSED, IgnitionRefusal.SHAPE_MISMATCH));
        assertEquals(IgnitionRefusal.SHAPE_MISMATCH,
                IgnitionRefusal.furthest(IgnitionRefusal.SHAPE_MISMATCH, IgnitionRefusal.OPENING_NOT_ENCLOSED));
        assertEquals(IgnitionRefusal.NO_CANDIDATE_CELL,
                IgnitionRefusal.furthest(null, IgnitionRefusal.NO_CANDIDATE_CELL));
        assertEquals(IgnitionRefusal.NO_CANDIDATE_CELL,
                IgnitionRefusal.furthest(IgnitionRefusal.NO_CANDIDATE_CELL, null));
    }

    @Test
    void everyReasonCarriesASentence() {
        for (IgnitionRefusal reason : IgnitionRefusal.values()) {
            assertNotNull(reason.sentence(), reason + " has no sentence");
            assertTrue(reason.sentence().length() > 10,
                    reason + "'s sentence must read as an explanation, not a label");
        }
    }

    // === the old entry point keeps its contract ==========================

    @Test
    void sweepStillAnswersNullForARefusal() {
        Fixture world = new Fixture();
        assertNull(IgnitionScan.sweep(world, CLICK, crucible().resolveFrameMatcher(), crucible()));
    }
}
