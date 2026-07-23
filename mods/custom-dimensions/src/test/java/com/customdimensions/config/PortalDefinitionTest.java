package com.customdimensions.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortalDefinitionTest {

    @Test
    void constructorSetsFields() {
        PortalDefinition def = new PortalDefinition("test_portal", "minecraft:obsidian", "minecraft:flint_and_steel", "minecraft:the_nether", "FF0000", 10);
        assertEquals("test_portal", def.getId());
        assertEquals("minecraft:obsidian", def.getFrameBlock());
        assertEquals("minecraft:flint_and_steel", def.getIgniterItem());
        assertEquals("minecraft:the_nether", def.getTargetDimension());
        assertEquals("FF0000", def.getColor());
        assertEquals(10, def.getLightLevel());
    }

    @Test
    void defaultCooldownIs40() {
        PortalDefinition def = new PortalDefinition("p", "b", "i", "t", "AA00FF", 0);
        assertEquals(40, def.getCooldown());
    }

    @Test
    void cooldownSetterWorks() {
        PortalDefinition def = new PortalDefinition("p", "b", "i", "t", "AA00FF", 0);
        def.setCooldown(100);
        assertEquals(100, def.getCooldown());

        def.setCooldown(0);
        assertEquals(0, def.getCooldown());
    }

    @Test
    void defaultScaleIs1() {
        PortalDefinition def = new PortalDefinition("p", "b", "i", "t", "AA00FF", 0);
        assertEquals(1.0, def.getScale());
    }

    @Test
    void defaultSoundsAreVanillaPortalSounds() {
        PortalDefinition def = new PortalDefinition("p", "b", "i", "t", "AA00FF", 0);
        assertEquals("block.portal.trigger", def.getIgniteSound());
        assertEquals("block.portal.travel", def.getEnterSound());
        assertEquals("block.portal.travel", def.getExitSound());
    }

    @Test
    void soundGettersHandleNullWithDefaults() {
        PortalDefinition def = new PortalDefinition();
        assertEquals("block.portal.trigger", def.getIgniteSound());
        assertEquals("block.portal.travel", def.getEnterSound());
        assertEquals("block.portal.travel", def.getExitSound());
    }

    @Test
    void noArgConstructorDefaults() {
        PortalDefinition def = new PortalDefinition();
        assertNull(def.getId());
        assertNull(def.getFrameBlock());
        assertEquals(1.0, def.getScale());
        assertEquals(40, def.getCooldown());
    }

    @Test
    void frameAcceptsFallsBackToFrameBlock() {
        PortalDefinition def = new PortalDefinition("p", "minecraft:clay", "i", "t", "AA00FF", 0);
        assertEquals(java.util.List.of("minecraft:clay"), def.getFrameAccepts());
        def.setFrameAccepts(java.util.List.of("#minecraft:logs", "minecraft:stone"));
        assertEquals(java.util.List.of("#minecraft:logs", "minecraft:stone"), def.getFrameAccepts());
        // empty list normalises back to the frameBlock fallback
        def.setFrameAccepts(java.util.List.of());
        assertEquals(java.util.List.of("minecraft:clay"), def.getFrameAccepts());
    }

    @Test
    void framePlaceBlockFallsBackToPlainFrameBlockOnly() {
        PortalDefinition plain = new PortalDefinition("p", "minecraft:clay", "i", "t", "AA00FF", 0);
        assertEquals("minecraft:clay", plain.getFramePlaceBlock());
        PortalDefinition tagged = new PortalDefinition("p", "#minecraft:logs", "i", "t", "AA00FF", 0);
        assertNull(tagged.getFramePlaceBlock());          // accepting is not placing
        tagged.setFramePlaceBlock("minecraft:oak_log");
        assertEquals("minecraft:oak_log", tagged.getFramePlaceBlock());
    }

    @Test
    void orientationDefaultsToAnyAndGatesAxes() {
        net.minecraft.util.math.Direction.Axis x = net.minecraft.util.math.Direction.Axis.X;
        net.minecraft.util.math.Direction.Axis y = net.minecraft.util.math.Direction.Axis.Y;
        net.minecraft.util.math.Direction.Axis z = net.minecraft.util.math.Direction.Axis.Z;
        PortalDefinition def = new PortalDefinition("p", "b", "i", "t", "AA00FF", 0);
        assertEquals("any", def.getOrientation());        // today's behaviour
        assertTrue(def.allowsAxis(x) && def.allowsAxis(y) && def.allowsAxis(z));
        def.setOrientation("vertical");
        assertTrue(def.allowsAxis(x) && def.allowsAxis(z));
        assertFalse(def.allowsAxis(y));
        def.setOrientation("horizontal");
        assertTrue(def.allowsAxis(y));
        assertFalse(def.allowsAxis(x) || def.allowsAxis(z));
        def.setOrientation("vertical_x");
        assertTrue(def.allowsAxis(x));
        assertFalse(def.allowsAxis(z) || def.allowsAxis(y));
        def.setOrientation("vertical_z");
        assertTrue(def.allowsAxis(z));
        assertFalse(def.allowsAxis(x) || def.allowsAxis(y));
        // unknown values behave as "any" (validator warns, never crashes)
        def.setOrientation("sideways");
        assertTrue(def.allowsAxis(x) && def.allowsAxis(y) && def.allowsAxis(z));
    }
}
