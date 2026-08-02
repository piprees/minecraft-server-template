package com.customdimensions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pure TOML patch behind the preLaunch c2me override — must mirror the
 * dev-up.sh/deploy.sh patch exactly: rewrite an existing key, insert under an
 * existing section, append both when absent, change nothing when already off.
 */
class C2meConfigPatchTest {

    @Test
    void rewritesAnEnabledKeyToFalse() {
        String toml = "[vanillaWorldGenOptimizations]\n\tuseDensityFunctionCompiler = true\n";
        String patched = C2meConfigPatch.patch(toml);
        assertTrue(patched.contains("useDensityFunctionCompiler = false"));
        assertFalse(patched.contains("= true"));
    }

    @Test
    void insertsUnderAnExistingSection() {
        String toml = "[generalOptimizations]\n\tenabled = true\n"
                + "[vanillaWorldGenOptimizations]\n\tother = 3\n";
        String patched = C2meConfigPatch.patch(toml);
        assertTrue(patched.contains(
                "[vanillaWorldGenOptimizations]\n\tuseDensityFunctionCompiler = false"));
        assertTrue(patched.contains("\tother = 3"), "sibling keys survive");
        assertTrue(patched.contains("enabled = true"), "other sections survive");
    }

    @Test
    void appendsSectionAndKeyWhenAbsent() {
        assertEquals("\n[vanillaWorldGenOptimizations]\n\tuseDensityFunctionCompiler = false\n",
                C2meConfigPatch.patch(""));
        String patched = C2meConfigPatch.patch("[ioSystem]\n\tenabled = true");
        assertTrue(patched.contains("[ioSystem]\n\tenabled = true"));
        assertTrue(patched.endsWith(
                "[vanillaWorldGenOptimizations]\n\tuseDensityFunctionCompiler = false\n"));
    }

    @Test
    void alreadyPatchedIsAFixedPoint() {
        String once = C2meConfigPatch.patch(
                "[vanillaWorldGenOptimizations]\n\tuseDensityFunctionCompiler = true\n");
        assertEquals(once, C2meConfigPatch.patch(once));
    }
}
