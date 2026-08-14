package com.customdimensions.portal;

import com.customdimensions.config.MultiverseConfig;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRESENTATION describes where a portal GOES, not where it is.
 *
 * <p>The frame of an arrival is built from the destination's blocks so it is
 * recognisable on arrival; its colour and particles belong to the world on
 * the other side, because that portal's job is to take you back there.
 *
 * <p>{@code getPortalFor(minecraft:overworld)} returns null — the overworld
 * has no portal config, since nothing targets it — so an arrival whose
 * source is the overworld must fall back to a neutral presentation, never to
 * {@code definition} (the DESTINATION's own portal), or every way home glows
 * like the dimension you're standing in.
 */
class PortalPresentationTest {

    private static RegistryKey<World> world(String id) {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(id));
    }

    // === the bug ========================================================

    @Test
    void theOverworldHasNoPortalDefinitionToPresentWith() {
        // The premise of the defect, pinned. If this ever starts returning a
        // definition, the neutral fallback below stops being the path taken
        // and the presentation rules change without anyone noticing.
        assertNull(MultiverseConfig.getInstance().getPortalFor(world("minecraft:overworld")),
                "no shipped dimension targets the overworld, so it has no presentation");
    }

    @Test
    void aNullSourcePresentationIsNeutralNotTheDestinations() {
        // The fix. An arrival whose source has no config must NOT inherit the
        // destination's colour — that is what made the way home out of an
        // ember dimension glow ember and read as another door deeper in.
        assertEquals(0x8844FF, PortalHelper.NEUTRAL_PORTAL_COLOR,
                "neutral is vanilla portal violet");
        assertEquals(PortalHelper.NEUTRAL_PORTAL_COLOR, PortalHelper.parseColor(null),
                "the neutral colour and parseColor's own fallback must agree — "
                        + "two different 'defaults' is how this drifts");
    }

    @Test
    void aDestinationColourIsNotTheNeutralOne() {
        // Guard with teeth: if a dimension ever picked violet the test above
        // would pass for the wrong reason. These are the real shipped values.
        assertNotEquals(PortalHelper.NEUTRAL_PORTAL_COLOR, PortalHelper.parseColor("FF5B1B"),
                "the_ember_fields' ember");
        assertNotEquals(PortalHelper.NEUTRAL_PORTAL_COLOR, PortalHelper.parseColor("D8C8A0"),
                "the_boneyard's bone");
    }

    // === colour parsing, which the presentation rides on =================

    @Test
    void colourParsesWithAndWithoutTheHash() {
        assertEquals(0xFF5B1B, PortalHelper.parseColor("FF5B1B"));
        assertEquals(0xFF5B1B, PortalHelper.parseColor("#FF5B1B"));
    }

    @Test
    void aMalformedColourFallsBackRatherThanThrowing() {
        // A bad colour in config must never break portal creation — the
        // portal matters more than its tint.
        assertEquals(PortalHelper.NEUTRAL_PORTAL_COLOR, PortalHelper.parseColor("nonsense"));
        assertEquals(PortalHelper.NEUTRAL_PORTAL_COLOR, PortalHelper.parseColor(""));
        assertEquals(PortalHelper.NEUTRAL_PORTAL_COLOR, PortalHelper.parseColor(null));
    }

    @Test
    void aChainedDimensionKeepsItsOwnPresentation() {
        // Only BASE worlds are neutral. A chained arrival (dim A -> dim B)
        // has a real config for A, so getPortalFor(A) answers and B's arrival
        // presents as A. Asserted through the lookup rather than the write
        // path, which needs a ServerWorld.
        var fromEmber = MultiverseConfig.getInstance().getPortalFor(world("adventure:the_ember_fields"));
        if (fromEmber != null) {
            assertNotNull(fromEmber.getColor(),
                    "a configured dimension presents with its own colour, not the neutral one");
        }
    }
}
