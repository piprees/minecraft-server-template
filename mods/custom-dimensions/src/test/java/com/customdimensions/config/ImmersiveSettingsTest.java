package com.customdimensions.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImmersiveSettingsTest {
    private static final Gson GSON = new Gson();

    private PortalDefinition parsePortal(String json) {
        DimensionConfig config = GSON.fromJson(json, DimensionConfig.class);
        config.setName("d");
        return config.toPortalDefinition();
    }

    @Test
    void testBooleanTrue() {
        ImmersiveSettings imm = parsePortal("{\"portal\":{\"frameBlock\":\"b\",\"immersive\":true}}").getImmersive();
        assertNotNull(imm);
        assertTrue(imm.enabled());
        assertEquals(ImmersiveSettings.DEFAULT_PREVIEW_DEPTH, imm.previewDepth());
        assertEquals(ImmersiveSettings.DEFAULT_PREVIEW_RADIUS, imm.previewRadius());
        assertEquals(ImmersiveSettings.DEFAULT_REFRESH_INTERVAL, imm.refreshInterval());
        assertEquals(ImmersiveSettings.DEFAULT_ACTIVATION_RANGE, imm.activationRange());
        assertTrue(imm.audio());
        assertTrue(imm.entityPassthrough());
    }

    @Test
    void testBooleanFalse() {
        assertNull(parsePortal("{\"portal\":{\"frameBlock\":\"b\",\"immersive\":false}}").getImmersive());
    }

    @Test
    void testAbsentIsOnByDefault() {
        // Immersive is the house style for every portal this mod builds, so
        // saying nothing means yes. Opting out is an explicit false.
        ImmersiveSettings imm = parsePortal("{\"portal\":{\"frameBlock\":\"b\"}}").getImmersive();
        assertNotNull(imm);
        assertTrue(imm.enabled());
        assertEquals(ImmersiveSettings.DEFAULT_PREVIEW_DEPTH, imm.previewDepth());
    }

    @Test
    void testExplicitFalseIsTheOptOut() {
        assertNull(parsePortal("{\"portal\":{\"frameBlock\":\"b\",\"immersive\":false}}").getImmersive());
        assertNull(parsePortal(
                "{\"portal\":{\"frameBlock\":\"b\",\"immersive\":{\"enabled\":false}}}").getImmersive());
    }

    @Test
    void testObjectWithDefaults() {
        ImmersiveSettings imm = parsePortal("{\"portal\":{\"frameBlock\":\"b\",\"immersive\":{}}}").getImmersive();
        assertNotNull(imm);
        assertTrue(imm.enabled());
        assertEquals(ImmersiveSettings.DEFAULT_PREVIEW_DEPTH, imm.previewDepth());
        assertEquals(ImmersiveSettings.DEFAULT_PREVIEW_RADIUS, imm.previewRadius());
        assertEquals(ImmersiveSettings.DEFAULT_REFRESH_INTERVAL, imm.refreshInterval());
        assertEquals(ImmersiveSettings.DEFAULT_ACTIVATION_RANGE, imm.activationRange());
        assertTrue(imm.audio());
        assertTrue(imm.entityPassthrough());
    }

    @Test
    void testObjectWithOverrides() {
        ImmersiveSettings imm = parsePortal("""
                {"portal":{"frameBlock":"b","immersive":{"previewDepth":4,"audio":false}}}
                """).getImmersive();
        assertNotNull(imm);
        assertEquals(4, imm.previewDepth());
        assertFalse(imm.audio());
        // Untouched fields keep their defaults.
        assertEquals(ImmersiveSettings.DEFAULT_PREVIEW_RADIUS, imm.previewRadius());
        assertEquals(ImmersiveSettings.DEFAULT_REFRESH_INTERVAL, imm.refreshInterval());
        assertEquals(ImmersiveSettings.DEFAULT_ACTIVATION_RANGE, imm.activationRange());
        assertTrue(imm.entityPassthrough());
    }

    @Test
    void testClampedValues() {
        ImmersiveSettings imm = parsePortal("""
                {"portal":{"frameBlock":"b","immersive":{"previewDepth":100,"previewRadius":-5,
                 "refreshInterval":1,"activationRange":9999}}}
                """).getImmersive();
        assertNotNull(imm);
        assertEquals(ImmersiveSettings.MAX_PREVIEW_DEPTH, imm.previewDepth());
        assertEquals(ImmersiveSettings.MIN_PREVIEW_RADIUS, imm.previewRadius());
        assertEquals(ImmersiveSettings.MIN_REFRESH_INTERVAL, imm.refreshInterval());
        assertEquals(ImmersiveSettings.MAX_ACTIVATION_RANGE, imm.activationRange());
    }

    @Test
    void enabledFalseInsideObjectMeansNotImmersive() {
        // An explicit "enabled": false inside the object wins over any
        // other fields set alongside it.
        assertNull(parsePortal("""
                {"portal":{"frameBlock":"b","immersive":{"enabled":false,"previewDepth":4}}}
                """).getImmersive());
    }

    @Test
    void immersiveSettingsAreNotSerialisedIntoPortalDefinitionJson() {
        PortalDefinition def = parsePortal("{\"portal\":{\"frameBlock\":\"b\",\"immersive\":true}}");
        assertNotNull(def.getImmersive());
        String json = GSON.toJson(def);
        assertFalse(json.contains("immersive"), "immersive settings must not leak into persisted zone JSON: " + json);
    }

    /**
     * ImmersiveSettings is transient on PortalDefinition (correctly — see
     * the test above), so a plain Gson round-trip — exactly what
     * portal_links.json does to every restored PortalZone.definition — can
     * never resurrect it. PortalHelper.restoreZones() must re-stamp
     * immersive settings from the live MultiverseConfig after
     * deserialising a zone, or an already-ignited immersive portal
     * silently stops being immersive after a restart. Do not "fix" this
     * test by removing the transient modifier — that would leak immersive
     * settings into portal_links.json.
     */
    @Test
    void immersiveIsLostAcrossGsonRoundTripAndMustBeReStampedOnRestore() {
        PortalDefinition def = new PortalDefinition("p", "minecraft:amethyst_block",
                "minecraft:amethyst_shard", "minecraft:the_end", "9B59B6", 8);
        def.setImmersive(ImmersiveSettings.DEFAULTS);
        assertNotNull(def.getImmersive());

        String json = GSON.toJson(def);
        assertFalse(json.contains("immersive"), "immersive settings must not be serialised: " + json);

        PortalDefinition restored = GSON.fromJson(json, PortalDefinition.class);
        assertNull(restored.getImmersive(),
                "a bare Gson round-trip must NOT resurrect immersive settings — "
                        + "PortalHelper.restoreZones() is responsible for re-stamping them from live config");
    }
}
