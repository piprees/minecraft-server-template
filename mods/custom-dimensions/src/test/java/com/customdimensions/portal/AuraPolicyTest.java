package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuraPolicyTest {

    @Test
    void unknownAbsentAndBlankValuesAllMeanNatural() {
        assertEquals(AuraPolicy.NATURAL, AuraPolicy.normalise(null));
        assertEquals(AuraPolicy.NATURAL, AuraPolicy.normalise(""));
        assertEquals(AuraPolicy.NATURAL, AuraPolicy.normalise("   "));
        assertEquals(AuraPolicy.NATURAL, AuraPolicy.normalise("some_future_value"));
        // A typo must fail to the SAFE value, never to "everything".
        assertEquals(AuraPolicy.NATURAL, AuraPolicy.normalise("everthing"));
    }

    @Test
    void knownValuesSurviveCaseAndPadding() {
        assertEquals(AuraPolicy.NONE, AuraPolicy.normalise("NONE"));
        assertEquals(AuraPolicy.NONE, AuraPolicy.normalise(" none "));
        assertEquals(AuraPolicy.NATURAL, AuraPolicy.normalise("Natural"));
        assertEquals(AuraPolicy.EVERYTHING, AuraPolicy.normalise("Everything"));
    }

    @Test
    void naturalConvertsTerrainButNeverCraftedBlocks() {
        assertTrue(AuraPolicy.allowsReplacement(AuraPolicy.NATURAL, false));
        assertFalse(AuraPolicy.allowsReplacement(AuraPolicy.NATURAL, true));
        // The default is natural, so an absent value behaves identically.
        assertTrue(AuraPolicy.allowsReplacement(null, false));
        assertFalse(AuraPolicy.allowsReplacement(null, true));
    }

    @Test
    void noneReplacesNothingAndEverythingReplacesAnything() {
        assertFalse(AuraPolicy.allowsReplacement(AuraPolicy.NONE, false));
        assertFalse(AuraPolicy.allowsReplacement(AuraPolicy.NONE, true));
        assertTrue(AuraPolicy.allowsReplacement(AuraPolicy.EVERYTHING, false));
        assertTrue(AuraPolicy.allowsReplacement(AuraPolicy.EVERYTHING, true));
    }

    @Test
    void onlyNoneWithholdsFireAndFluids() {
        assertFalse(AuraPolicy.allowsHazardousAdditions(AuraPolicy.NONE));
        assertTrue(AuraPolicy.allowsHazardousAdditions(AuraPolicy.NATURAL));
        assertTrue(AuraPolicy.allowsHazardousAdditions(AuraPolicy.EVERYTHING));
        assertTrue(AuraPolicy.allowsHazardousAdditions(null));
    }

    @Test
    void auraSettingsDefaultToNaturalAndNormaliseTheirInput() {
        assertEquals(AuraPolicy.NATURAL, PortalDefinition.AuraSettings.DEFAULTS.getSubsume());

        PortalDefinition.AuraSettings settings = new PortalDefinition.AuraSettings();
        settings.subsume = "EVERYTHING";
        assertEquals(AuraPolicy.EVERYTHING, settings.getSubsume());

        settings.subsume = "nonsense";
        assertEquals(AuraPolicy.NATURAL, settings.getSubsume());
    }

    @Test
    void aDefinitionWithNoAuraBlockStillAnswersNatural() {
        // A dimension that never mentions auras must land on the default
        // without touching the shared DEFAULTS instance.
        PortalDefinition definition = new PortalDefinition();
        assertEquals(AuraPolicy.NATURAL, definition.getAura().getSubsume());
    }
}
