package com.customdimensions.immersive;

import com.customdimensions.config.PortalDefinition;
import com.customdimensions.portal.PortalHelper;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The far side's colour and light, applied to a portal's own.
 *
 * <p>The sampling itself needs a world; the arithmetic that turns a sample
 * into what a player sees does not, and that is what decides whether an
 * opening reads as a window onto somewhere or as a coloured pane.
 */
class DestinationGlowTest {

    private static final int PORTAL_VIOLET = 0x8844FF;
    private static final int NETHER_FOG = 0x330303;

    @Test
    void blendMovesTowardsTheDestination() {
        assertEquals(PORTAL_VIOLET, DestinationGlow.blend(PORTAL_VIOLET, NETHER_FOG, 0.0));
        assertEquals(NETHER_FOG, DestinationGlow.blend(PORTAL_VIOLET, NETHER_FOG, 1.0));
        int half = DestinationGlow.blend(0x000000, 0xFFFFFF, 0.5);
        assertEquals(0x808080, half, "an even blend is the midpoint per channel");
    }

    @Test
    void blendClampsRatherThanOvershooting() {
        assertEquals(PORTAL_VIOLET, DestinationGlow.blend(PORTAL_VIOLET, NETHER_FOG, -3.0));
        assertEquals(NETHER_FOG, DestinationGlow.blend(PORTAL_VIOLET, NETHER_FOG, 9.0));
        assertEquals(PORTAL_VIOLET, DestinationGlow.blend(PORTAL_VIOLET, NETHER_FOG, Double.NaN));
    }

    @Test
    void brightnessTracksTheDestinationsLight() {
        assertEquals(1.0f, DestinationGlow.brightness(15), 1e-6);
        assertTrue(DestinationGlow.brightness(0) < DestinationGlow.brightness(7));
        assertTrue(DestinationGlow.brightness(7) < DestinationGlow.brightness(15));
    }

    @Test
    void aLitDestinationShowsBrighterThanADarkOne() {
        DestinationGlow lit = new DestinationGlow(15, 0xFFFFFF);
        DestinationGlow dark = new DestinationGlow(0, 0xFFFFFF);
        int litColour = lit.applyTo(PORTAL_VIOLET, 1.0, true);
        int darkColour = dark.applyTo(PORTAL_VIOLET, 1.0, true);
        assertTrue((litColour & 0xFF) > (darkColour & 0xFF),
                "daylight through the opening must outshine a cave");
    }

    @Test
    void lightCanBeTurnedOffWithoutLosingTheColour() {
        DestinationGlow dark = new DestinationGlow(0, NETHER_FOG);
        assertEquals(NETHER_FOG, dark.applyTo(PORTAL_VIOLET, 1.0, false));
    }

    @Test
    void shadeScalesEveryChannel() {
        assertEquals(0x804020, DestinationGlow.shade(0xFF8040, 0.5f));
        assertEquals(0xFF8040, DestinationGlow.shade(0xFF8040, 1.0f));
    }

    // ------------------------------------------------------------------
    // Negative: the cases that must never happen
    // ------------------------------------------------------------------

    @Test
    void anUnsampledDestinationChangesNothing() {
        assertFalse(DestinationGlow.NONE.isPresent());
        assertEquals(PORTAL_VIOLET, DestinationGlow.NONE.applyTo(PORTAL_VIOLET, 1.0, true),
                "no sample must leave the configured colour exactly as configured");
    }

    @Test
    void aPitchDarkDestinationStillDrawsAnEdge() {
        assertTrue(DestinationGlow.brightness(0) >= DestinationGlow.MIN_BRIGHTNESS,
                "a portal onto a dark world must read as dark, not as absent");
        int colour = new DestinationGlow(0, 0xFFFFFF).applyTo(0xFFFFFF, 1.0, true);
        assertTrue((colour & 0xFF) > 0);
    }

    @Test
    void samplingWithoutAWorldIsSafeAndEmpty() {
        assertEquals(DestinationGlow.NONE, DestinationGlow.sample(null, BlockPos.ORIGIN));
        assertEquals(DestinationGlow.NONE, DestinationGlow.sample(null, null));
    }

    @Test
    void aZoneNobodyIsNearHasNoGlowAndCachesNothing() {
        ImmersiveProjector.clear();
        PortalDefinition definition = new PortalDefinition(
                "test", "minecraft:obsidian", "minecraft:flint_and_steel",
                "adventure:the_trap", "#8844FF", 0);
        PortalHelper.PortalZone unseen = new PortalHelper.PortalZone(
                Set.of(new BlockPos(0, 64, 0)),
                definition,
                Direction.Axis.X,
                RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld")),
                RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure", "the_trap")));
        assertEquals(DestinationGlow.NONE, ImmersiveProjector.glowFor(unseen));
        assertEquals(DestinationGlow.NONE, ImmersiveProjector.glowFor(null));
        assertEquals(DestinationGlow.NONE, ImmersiveProjector.glowForArrival(
                RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld")),
                BlockPos.ORIGIN));
        assertTrue(ImmersiveProjector.immersiveArrivals(
                RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"))).isEmpty());
    }
}
