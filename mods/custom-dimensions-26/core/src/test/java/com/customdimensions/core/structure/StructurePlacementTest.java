package com.customdimensions.core.structure;

import com.customdimensions.core.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A forced start's or a want's chunk: it must pass the site filter, lie inside the border and stand when generated. */
class StructurePlacementTest {

    @Test
    void aChunkItsOwnRulesRefuseIsPassedOver() {
        List<ChunkPos> tried = new ArrayList<>();
        StructureSites.Placement p = StructureSites.place(11L, 0, 0, 100, 600, 0, (x, z) -> true, (x, z) -> true, (x, z) -> {
            tried.add(new ChunkPos(x, z));
            return tried.size() < 3 ? "its own rules start none there" : null;
        });
        assertTrue(p.stands());
        assertTrue(p.matched());
        assertEquals(3, p.tried());
        assertEquals(tried.get(2), p.chunk());
        assertEquals("its own rules start none there", p.refusal());
    }

    @Test
    void theSiteFilterIsNeverBypassed() {
        // choosePlaced falls back to its first candidate whatever the predicate; place finds none and says why.
        assertNotNull(StructureSites.choosePlaced(3L, 0, 0, 0, 300, (x, z) -> false).chunk());
        StructureSites.Placement p = StructureSites.place(3L, 0, 0, 0, 300, 0, (x, z) -> false, (x, z) -> true, (x, z) -> null);
        assertFalse(p.stands());
        assertNull(p.chunk());
        assertEquals(0, p.sited());
        assertTrue(p.tested() > 0 && p.inside() == p.tested());
        assertTrue(p.account().contains("0 pass its site filter"), p.account());
    }

    @Test
    void withoutItsBiomeAnywhereItStandsWhereItCanBiomeBypassed() {
        StructureSites.Placement p = StructureSites.place(5L, 0, 0, 0, 300, 0, (x, z) -> true, (x, z) -> false,
                (x, z) -> x > 0 ? null : "no ground under it");
        assertTrue(p.stands());
        assertFalse(p.matched());
        assertTrue(p.chunk().x() > 0);
        assertEquals(0, p.fitting());
    }

    @Test
    void nothingStandsWhenEveryGenerationIsRefused() {
        StructureSites.Placement p = StructureSites.place(5L, 0, 0, 0, 2000, 0, (x, z) -> true, (x, z) -> (x + z) % 2 == 0,
                (x, z) -> "no ground under it");
        assertFalse(p.stands());
        assertEquals(2 * StructureSites.TRIALS, p.tried());
        assertTrue(p.account().contains("no ground under it"), p.account());
    }

    @Test
    void candidatesOutsideTheBorderAreNotChosen() {
        StructureSites.Placement p = StructureSites.place(8L, 0, -192, 150, 900, 512, (x, z) -> true, (x, z) -> true, (x, z) -> null);
        assertTrue(p.stands());
        assertTrue(Math.abs(p.chunk().x() * 16 + 8) <= 512 && Math.abs(p.chunk().z() * 16 + 8) <= 512, p.chunk().toString());
        StructureSites.Placement out = StructureSites.place(8L, 5000, 5000, 0, 100, 512, (x, z) -> true, (x, z) -> true, (x, z) -> null);
        assertFalse(out.stands());
        assertEquals(0, out.inside());
    }

    @Test
    void radiusZeroIsTheOwnChunkOnly() {
        StructureSites.Placement p = StructureSites.place(1L, -17, 33, 0, 0, 0, (x, z) -> true, (x, z) -> true, (x, z) -> null);
        assertEquals(new ChunkPos(-2, 2), p.chunk());
        assertEquals(1, p.tested());
    }
}
