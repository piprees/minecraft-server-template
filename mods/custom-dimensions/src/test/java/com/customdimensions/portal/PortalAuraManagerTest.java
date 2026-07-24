package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class PortalAuraManagerTest {

    @Test
    void topNIsCountDescendingThenIdDeterministic() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("minecraft:dirt", 5);
        counts.put("minecraft:stone", 9);
        counts.put("minecraft:grass_block", 5);
        counts.put("minecraft:sand", 1);
        assertEquals(List.of("minecraft:stone", "minecraft:dirt", "minecraft:grass_block"),
                PortalAuraManager.topN(counts, 3));
        assertEquals(List.of("minecraft:stone"), PortalAuraManager.topN(counts, 1));
        assertTrue(PortalAuraManager.topN(Map.of(), 5).isEmpty());
    }

    @Test
    void depressionNeedsFloorAndThreeWalls() {
        BlockPos pos = new BlockPos(0, 64, 0);
        Set<BlockPos> solid = new java.util.HashSet<>(Set.of(
                pos.down(), pos.north(), pos.south(), pos.east()));
        Predicate<BlockPos> isSolid = solid::contains;
        assertTrue(PortalAuraManager.isDepression(isSolid, pos));   // floor + 3 walls
        solid.remove(pos.east());
        assertFalse(PortalAuraManager.isDepression(isSolid, pos));  // only 2 walls
        solid.add(pos.east());
        solid.remove(pos.down());
        assertFalse(PortalAuraManager.isDepression(isSolid, pos));  // no floor
    }

    @Test
    void auraSettingsDefaultsAndClamps() {
        PortalDefinition.AuraSettings s = new PortalDefinition.AuraSettings();
        assertEquals(8, s.getRadius());
        assertEquals(40, s.getInterval());
        assertEquals(2, s.getBlocksPerPass());
        assertEquals(300, s.getBudget());
        assertEquals("both", s.getSides());
        assertTrue(s.affectsSource() && s.affectsTarget());
        assertEquals(0.0, s.getFireChance());
        s.radius = 9999;
        s.interval = 1;
        s.blocksPerPass = 999;
        s.budget = -5;
        s.fireChance = 3.0;
        s.sides = "sideways";
        assertEquals(32, s.getRadius());
        assertEquals(10, s.getInterval());
        assertEquals(16, s.getBlocksPerPass());
        assertEquals(-1, s.getBudget());
        assertEquals(1.0, s.getFireChance());
        assertEquals("both", s.getSides());   // unknown = both (validator warns)
        s.sides = "source";
        assertTrue(s.affectsSource());
        assertFalse(s.affectsTarget());
    }

    @Test
    void definitionAuraDefaultsToEnabledDerived() {
        PortalDefinition def = new PortalDefinition("p", "b", "i", "t", "AA00FF", 0);
        assertTrue(def.isAuraEnabled());
        assertEquals(300, def.getAura().getBudget());
        PortalDefinition.AuraSettings off = new PortalDefinition.AuraSettings();
        off.enabled = false;
        def.setAura(off);
        assertFalse(def.isAuraEnabled());
    }

    @Test
    void minCornerIsDeterministic() {
        Set<BlockPos> interior = Set.of(
                new BlockPos(3, 64, 3), new BlockPos(2, 64, 3), new BlockPos(2, 63, 4));
        assertEquals(new BlockPos(2, 63, 4), PortalAuraManager.minCorner(interior));
    }

    @Test
    void exclusionCoversInteriorAndRing() {
        Set<BlockPos> interior = Set.of(new BlockPos(0, 64, 0), new BlockPos(0, 65, 0));
        Set<BlockPos> exclusion = PortalAuraManager.exclusionFor(
                interior, net.minecraft.util.math.Direction.Axis.X);
        assertTrue(exclusion.contains(new BlockPos(0, 63, 0)));  // below
        assertTrue(exclusion.contains(new BlockPos(0, 66, 0)));  // above
        assertTrue(exclusion.contains(new BlockPos(1, 64, 0)));  // east
        assertTrue(exclusion.contains(new BlockPos(-1, 65, 0))); // west
        assertFalse(exclusion.contains(new BlockPos(0, 64, 1))); // off-plane
    }
}
