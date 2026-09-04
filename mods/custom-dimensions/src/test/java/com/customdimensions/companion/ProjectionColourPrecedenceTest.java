package com.customdimensions.companion;

import com.customdimensions.immersive.DestinationGlow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The projection's colour precedence through the path it actually takes:
 * {@code parseHex} reads the authored string, {@code preferConfigured} decides
 * between it and the biome.
 *
 * <p>The two halves have to agree about what "absent" means. {@code parseHex}
 * returns {@code -1} for absent and {@code 0} for {@code #000000}, and
 * {@code preferConfigured} treats only {@code -1} as absent — conflating them
 * anywhere would silently drop an authored black.
 */
class ProjectionColourPrecedenceTest {

    private static final int BIOME_FOG = 0x3A0E0E;

    @Test
    void anAuthoredColourSurvivesTheParsePath() {
        assertEquals(0x8B0000,
                DestinationGlow.preferConfigured(ProjectionStream.parseHex("#8B0000"), BIOME_FOG));
    }

    @Test
    void anAuthoredBlackSurvivesTheParsePath() {
        assertEquals(0x000000,
                DestinationGlow.preferConfigured(ProjectionStream.parseHex("#000000"), BIOME_FOG),
                "parseHex returns 0 for black, and 0 is a colour");
    }

    @Test
    void anUnauthoredColourFallsThroughToTheBiome() {
        assertEquals(BIOME_FOG,
                DestinationGlow.preferConfigured(ProjectionStream.parseHex(null), BIOME_FOG));
        assertEquals(BIOME_FOG,
                DestinationGlow.preferConfigured(ProjectionStream.parseHex("  "), BIOME_FOG));
    }

    @Test
    void anUnparseableColourFallsThroughToTheBiome() {
        assertEquals(BIOME_FOG,
                DestinationGlow.preferConfigured(ProjectionStream.parseHex("#gggggg"), BIOME_FOG));
        assertEquals(BIOME_FOG,
                DestinationGlow.preferConfigured(ProjectionStream.parseHex("#ABC"), BIOME_FOG));
    }
}
