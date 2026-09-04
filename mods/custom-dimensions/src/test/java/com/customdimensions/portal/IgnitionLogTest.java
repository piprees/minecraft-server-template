package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the refusal line says. Two audiences share one line: a person who
 * needs a sentence they can act on, and a grep that needs a stable prefix,
 * the reason's name and the coordinates. Both are pinned here — a line that
 * loses either half stops being usable for the job it exists to do.
 */
class IgnitionLogTest {

    private static final String COPPER = "minecraft:copper_block";
    private static final BlockPos CLICKED = new BlockPos(3260, 85, 2883);

    private static PortalDefinition crucible() {
        return new PortalDefinition("the_crucible", COPPER, "minecraft:diamond",
                "adventure:the_crucible", "8BAF5B", 11);
    }

    private static IgnitionScan.Refusal frameGap() {
        return new IgnitionScan.Refusal(IgnitionRefusal.FRAME_INCOMPLETE,
                new BlockPos(3260, 85, 2884), Direction.Axis.X, 6);
    }

    @Test
    void theLineNamesThePortalTheReasonAndTheSpot() {
        String line = IgnitionLog.line("minecraft:overworld", CLICKED, COPPER, "the_crucible", frameGap());

        assertTrue(line.startsWith(IgnitionLog.PREFIX),
                "the prefix is the grep contract: " + line);
        assertTrue(line.contains("the_crucible"), line);
        assertTrue(line.contains("3260, 85, 2883"), "the clicked block: " + line);
        assertTrue(line.contains("minecraft:overworld"), line);
        assertTrue(line.contains(COPPER), "what was clicked, so a wrong-frame click is obvious: " + line);
        assertTrue(line.contains(IgnitionRefusal.FRAME_INCOMPLETE.sentence()),
                "the sentence is the half a person reads: " + line);
        assertTrue(line.contains("FRAME_INCOMPLETE"),
                "the constant is the half a grep reads: " + line);
        assertTrue(line.contains("3260, 85, 2884"), "the cell it judged, not just the click: " + line);
        assertTrue(line.contains("axis=X"), line);
        assertTrue(line.contains("cells=6"), line);
    }

    @Test
    void theLineNamesTheBlockThatStoppedTheFill() {
        // The Crucible's live failure, rendered: one weathered block out of
        // fourteen, and the line has to say which and where or the reader is
        // back to checking a ring by hand.
        IgnitionScan.Refusal leak = new IgnitionScan.Refusal(IgnitionRefusal.OPENING_NOT_ENCLOSED,
                new BlockPos(3261, 85, 2883), Direction.Axis.X, 4, new BlockPos(3260, 84, 2883));

        String line = IgnitionLog.line("minecraft:overworld", CLICKED, COPPER, "the_crucible",
                leak, "minecraft:exposed_copper");

        assertTrue(line.contains("minecraft:exposed_copper"), "which block: " + line);
        assertTrue(line.contains("3260, 84, 2883"), "and where: " + line);
    }

    @Test
    void anUnnamedBlockerStillGetsItsPosition() {
        IgnitionScan.Refusal leak = new IgnitionScan.Refusal(IgnitionRefusal.OPENING_NOT_ENCLOSED,
                CLICKED, Direction.Axis.X, 4, new BlockPos(3260, 84, 2883));

        String line = IgnitionLog.line("minecraft:overworld", CLICKED, COPPER, "the_crucible", leak);

        assertTrue(line.contains("3260, 84, 2883"), line);
        assertFalse(line.contains("null"), "an unresolved block id must not print as null: " + line);
    }

    @Test
    void aRefusalWithNoCellStillReads() {
        // NO_CANDIDATE_CELL has no axis and no fill to report; the line must
        // not print "axis=null" at somebody.
        IgnitionScan.Refusal bare =
                new IgnitionScan.Refusal(IgnitionRefusal.NO_CANDIDATE_CELL, CLICKED, null, 0);

        String line = IgnitionLog.line("minecraft:overworld", CLICKED, COPPER, "the_crucible", bare);

        assertTrue(line.contains("NO_CANDIDATE_CELL"), line);
        assertFalse(line.contains("null"), "no null reached the reader: " + line);
    }

    @Test
    void theEarlyLineNamesTheItemThatLitNothing() {
        String line = IgnitionLog.earlyLine(IgnitionRefusal.NO_IGNITER_MATCH,
                "minecraft:overworld", CLICKED, COPPER, "minecraft:stick");

        assertTrue(line.startsWith(IgnitionLog.PREFIX), line);
        assertTrue(line.contains("NO_IGNITER_MATCH"), line);
        assertTrue(line.contains(IgnitionRefusal.NO_IGNITER_MATCH.sentence()), line);
        assertTrue(line.contains("minecraft:stick"), "which item was tried: " + line);
        assertTrue(line.contains("3260, 85, 2883"), line);
    }

    // === which refusals reach a default log ==============================

    @Test
    void clickingAPortalsOwnFrameIsADeliberateAttempt() {
        assertTrue(IgnitionLog.isDeliberate(crucible(), COPPER),
                "a diamond on a copper block is somebody trying to light the Crucible");
    }

    @Test
    void clickingAnythingElseIsAPassingClick() {
        assertFalse(IgnitionLog.isDeliberate(crucible(), "minecraft:dirt"),
                "a diamond on dirt must not put a line in a default log");
    }

    @Test
    void anAcceptFormBesideTheFrameBlockStillCounts() {
        PortalDefinition def = crucible();
        def.setFrameAccepts(java.util.List.of(COPPER, "minecraft:exposed_copper"));

        assertTrue(IgnitionLog.isDeliberate(def, "minecraft:exposed_copper"),
                "the accept forms are the frame, not just frameBlock");
    }

    @Test
    void aDefinitionWithNoFrameIsNeverDeliberate() {
        PortalDefinition def = crucible();
        def.setFrameBlock(null);
        def.setFrameAccepts(java.util.List.of());

        assertFalse(IgnitionLog.isDeliberate(def, COPPER));
    }

    @Test
    void everySentenceEndsWithoutAFullStop() {
        // The sentence is embedded mid-line before the bracketed fields, so a
        // trailing stop reads as the end of the line and is not.
        for (IgnitionRefusal reason : IgnitionRefusal.values()) {
            assertFalse(reason.sentence().endsWith("."), reason + ": " + reason.sentence());
            assertEquals(reason.sentence().trim(), reason.sentence(), reason + " has loose whitespace");
        }
    }
}
