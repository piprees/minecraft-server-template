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
    void deathOnlyExitsWarn() {
        DimensionConfig config = parse("the_oubliette", """
                {"exits":{"death":{"target":"bed"},"death:lava":{"target":"worldSpawn"}}}
                """);
        List<String> warnings = PortalSafetyValidator.validate(List.of(config));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("death triggers"));
        // A non-death exit, a portal, or an exitPortal silences it.
        DimensionConfig withVoid = parse("d", """
                {"exits":{"death":{"target":"bed"},"void":{"target":"bed"}}}
                """);
        assertTrue(PortalSafetyValidator.validate(List.of(withVoid)).isEmpty());
        DimensionConfig withExitPortal = parse("d", """
                {"exits":{"death":{"target":"bed"}},"exitPortal":{"enabled":true}}
                """);
        assertTrue(PortalSafetyValidator.validate(List.of(withExitPortal)).isEmpty());
    }

    @Test
    void danglingDimensionLinksWarn() {
        DimensionConfig linked = parse("the_gate", """
                {"exitPortal":{"enabled":true,
                  "target":{"dimension":"adventure:nowhere","arrival":"spawn"}}}
                """);
        List<String> warnings = PortalSafetyValidator.validate(List.of(linked));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("adventure:nowhere"));
        assertTrue(warnings.get(0).contains("exitPortal.target"));
        // A link to a CONFIGURED dimension (cyclic included) is fine.
        DimensionConfig hub = parse("the_hub", """
                {"exitPortal":{"enabled":true,
                  "target":{"dimension":"adventure:the_spoke"}}}
                """);
        DimensionConfig spoke = parse("the_spoke", """
                {"exits":{"enderPearl":{"target":{"dimension":"adventure:the_hub"}}}}
                """);
        hub.setNamespace("adventure");
        spoke.setNamespace("adventure");
        assertTrue(PortalSafetyValidator.validate(List.of(hub, spoke)).isEmpty());
        // Base-world links are always known.
        DimensionConfig toNether = parse("d", """
                {"exits":{"void":{"target":{"dimension":"minecraft:the_nether"}}}}
                """);
        assertTrue(PortalSafetyValidator.validate(List.of(toNether)).isEmpty());
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
