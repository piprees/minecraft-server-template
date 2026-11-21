package com.customdimensions.core.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.BlockPos;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WinTest {

    private static Win win(Win.Trigger t) {
        return new Win("w", t, "W", null, null, null, null, null);
    }

    @Test
    void anIdOrATagMatches() {
        Win.Trigger id = new Win.Trigger(Win.Type.KILL, null, 0, "minecraft:zombie", 1, 0);
        assertTrue(id.matches("minecraft:zombie", Set.of()));
        assertFalse(id.matches("minecraft:husk", Set.of("minecraft:zombies")));
        Win.Trigger tag = new Win.Trigger(Win.Type.KILL, null, 0, "#minecraft:zombies", 1, 0);
        assertTrue(tag.matches("minecraft:husk", Set.of("minecraft:zombies")));
        assertFalse(tag.matches("minecraft:zombies", Set.of()));
    }

    @Test
    void reachIsASphereAroundTheBlockCentre() {
        Win.Trigger t = new Win.Trigger(Win.Type.REACH, new BlockPos(10, 64, 10), 3, null, 1, 0);
        assertTrue(t.reached(10.5, 64, 10.5));
        assertTrue(t.reached(13.5, 64, 10.5));
        assertFalse(t.reached(13.6, 64, 10.5));
        assertFalse(t.reached(10.5, 68, 10.5));
    }

    @Test
    void aCountedKillHasOneCriterionPerKill() {
        assertEquals(List.of("kill_1", "kill_2", "kill_3"),
                win(new Win.Trigger(Win.Type.KILL, null, 0, "minecraft:zombie", 3, 0)).criteria());
        assertEquals(List.of("win"), win(new Win.Trigger(Win.Type.KILL, null, 0, "minecraft:zombie", 1, 0)).criteria());
        assertEquals(List.of("win"), win(new Win.Trigger(Win.Type.OBTAIN, null, 0, "minecraft:diamond", 5, 0)).criteria());
    }

    @Test
    void pathAndMessageDefaults() {
        Win w = win(new Win.Trigger(Win.Type.ENTER, null, 0, null, 1, 0));
        assertEquals("wins/the_canvas/w", w.advancementPath("the_canvas"));
        assertEquals("win.earned", w.messageKey());
    }

    @Test
    void anAbsentDeathRuleLeavesItToTheGameRule() {
        assertTrue(Deaths.keeps(null, true));
        assertFalse(Deaths.keeps(null, false));
        assertTrue(Deaths.keeps(Deaths.Rule.KEEP, false));
        assertFalse(Deaths.keeps(Deaths.Rule.DROP, true));
    }
}
