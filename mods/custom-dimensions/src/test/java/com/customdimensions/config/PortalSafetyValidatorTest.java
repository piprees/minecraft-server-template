package com.customdimensions.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PortalSafetyValidatorTest {
    private static final Gson GSON = new Gson();

    private DimensionConfig parse(String slug, String json) {
        DimensionConfig config = GSON.fromJson(json, DimensionConfig.class);
        config.setName(slug);
        return config;
    }

    @Test
    void singleUseWithoutExitPortalWarns() {
        DimensionConfig config = parse("the_trap", """
                {"portal":{"frameBlock":"minecraft:obsidian","singleUse":{"enabled":true}}}
                """);
        List<String> warnings = PortalSafetyValidator.validate(List.of(config));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("the_trap"));
        assertTrue(warnings.get(0).contains("singleUse"));
        assertTrue(warnings.get(0).contains("never auto-fixed"));
    }

    @Test
    void anchorWithoutExitPortalWarns() {
        DimensionConfig config = parse("the_well", """
                {"portal":{"frameBlock":"minecraft:obsidian","anchor":{"exit":"bed"}}}
                """);
        List<String> warnings = PortalSafetyValidator.validate(List.of(config));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("anchor"));
    }

    @Test
    void exitPortalSilencesBothWarnings() {
        DimensionConfig config = parse("the_safe", """
                {"portal":{"frameBlock":"minecraft:obsidian",
                  "anchor":{"exit":"bed"},"singleUse":{"enabled":true}},
                 "exitPortal":{"enabled":true}}
                """);
        assertTrue(PortalSafetyValidator.validate(List.of(config)).isEmpty());
    }

    @Test
    void disabledSingleUseAndPlainPortalsAreSilent() {
        DimensionConfig disabled = parse("d1", """
                {"portal":{"frameBlock":"b","singleUse":{"enabled":false}}}
                """);
        DimensionConfig plain = parse("d2", "{\"portal\":{\"frameBlock\":\"b\"}}");
        DimensionConfig noPortal = parse("d3", "{}");
        assertTrue(PortalSafetyValidator.validate(List.of(disabled, plain, noPortal)).isEmpty());
    }

    @Test
    void baseWorldsAreNeverChecked() {
        DimensionConfig overworld = parse("overworld", """
                {"portal":{"frameBlock":"b","singleUse":{"enabled":true}}}
                """);
        assertTrue(PortalSafetyValidator.validate(List.of(overworld)).isEmpty());
    }

    @Test
    void explicitlyDisabledExitPortalStillWarns() {
        DimensionConfig config = parse("the_trap", """
                {"portal":{"frameBlock":"b","singleUse":{"enabled":true}},
                 "exitPortal":{"enabled":false}}
                """);
        assertEquals(1, PortalSafetyValidator.validate(List.of(config)).size());
    }
}
