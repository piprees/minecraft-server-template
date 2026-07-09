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
}
