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
    void unknownShapeWarns() {
        DimensionConfig config = parse("d", """
                {"portal":{"frameBlock":"b","shape":"hexagon"}}
                """);
        List<String> warnings = PortalSafetyValidator.validate(List.of(config));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("hexagon"));
        assertTrue(warnings.get(0).contains("never ignite"));
    }

    @Test
    void knownShapesAreSilent() {
        DimensionConfig door = parse("d1", "{\"portal\":{\"frameBlock\":\"b\",\"shape\":\"door\"}}");
        DimensionConfig doorway = parse("d2", "{\"portal\":{\"frameBlock\":\"b\",\"shape\":\"doorway\"}}");
        DimensionConfig endExit = parse("d3", """
                {"portal":{"frameBlock":"b","shape":"end_exit","centreBlock":"minecraft:dragon_egg"}}
                """);
        assertTrue(PortalSafetyValidator.validate(List.of(door, doorway, endExit)).isEmpty());
    }

    @Test
    void contradictoryShapeOrientationWarns() {
        DimensionConfig sideways = parse("d1", """
                {"portal":{"frameBlock":"b","shape":"door","orientation":"horizontal"}}
                """);
        DimensionConfig upright = parse("d2", """
                {"portal":{"frameBlock":"b","shape":"end_exit","orientation":"vertical_x"}}
                """);
        List<String> warnings = PortalSafetyValidator.validate(List.of(sideways, upright));
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("can never ignite"));
        assertTrue(warnings.get(1).contains("can never ignite"));
        // compatible explicit orientation stays silent
        DimensionConfig fine = parse("d3", """
                {"portal":{"frameBlock":"b","shape":"door","orientation":"vertical_z"}}
                """);
        assertTrue(PortalSafetyValidator.validate(List.of(fine)).isEmpty());
    }

    @Test
    void centreBlockOutsideEndExitWarns() {
        DimensionConfig stray = parse("d1", """
                {"portal":{"frameBlock":"b","centreBlock":"minecraft:dragon_egg"}}
                """);
        List<String> warnings = PortalSafetyValidator.validate(List.of(stray));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("centreBlock"));
        DimensionConfig badId = parse("d2", """
                {"portal":{"frameBlock":"b","shape":"end_exit","centreBlock":"NOT AN ID"}}
                """);
        List<String> idWarnings = PortalSafetyValidator.validate(List.of(badId));
        assertEquals(1, idWarnings.size());
        assertTrue(idWarnings.get(0).contains("not a valid identifier"));
    }

    @Test
    void frameMaterialsHygieneWarnings() {
        // both frameBlock and frameMaterials -> exclusivity warning
        DimensionConfig both = parse("d1", """
                {"portal":{"frameBlock":"minecraft:stone",
                 "frameMaterials":{"sides":"minecraft:oak_log"}}}
                """);
        List<String> w1 = PortalSafetyValidator.validate(List.of(both));
        assertEquals(1, w1.size());
        assertTrue(w1.get(0).contains("mutually exclusive"));
        // unknown part key -> ignored + warned
        DimensionConfig badKey = parse("d2", """
                {"portal":{"frameMaterials":{"sides":"minecraft:oak_log","lintel":"minecraft:stone"}}}
                """);
        List<String> w2 = PortalSafetyValidator.validate(List.of(badKey));
        assertEquals(1, w2.size());
        assertTrue(w2.get(0).contains("lintel"));
        // horizontal orientation -> per-part has no effect
        DimensionConfig flat = parse("d3", """
                {"portal":{"orientation":"horizontal",
                 "frameMaterials":{"sides":"minecraft:oak_log"}}}
                """);
        List<String> w3 = PortalSafetyValidator.validate(List.of(flat));
        assertEquals(1, w3.size());
        assertTrue(w3.get(0).contains("no effect on horizontal"));
        // clean vertical per-part config is silent
        DimensionConfig fine = parse("d4", """
                {"portal":{"frameMaterials":{"top":"minecraft:oak_planks",
                 "sides":"#minecraft:logs","bottom":"minecraft:stone"}}}
                """);
        assertTrue(PortalSafetyValidator.validate(List.of(fine)).isEmpty());
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
    void tagFrameWithoutPlaceBlockWarns() {
        DimensionConfig config = parse("the_grove", """
                {"portal":{"frameBlock":"#minecraft:logs"}}
                """);
        List<String> warnings = PortalSafetyValidator.validate(List.of(config));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("framePlaceBlock"));
        // an explicit place block (or a plain id in a list) silences it
        DimensionConfig placed = parse("d", """
                {"portal":{"frameBlock":"#minecraft:logs","framePlaceBlock":"minecraft:oak_log"}}
                """);
        assertTrue(PortalSafetyValidator.validate(List.of(placed)).isEmpty());
        DimensionConfig listed = parse("d", """
                {"portal":{"frameBlock":["#minecraft:logs","minecraft:oak_planks"]}}
                """);
        assertTrue(PortalSafetyValidator.validate(List.of(listed)).isEmpty());
    }

    @Test
    void unknownColourGroupWarns() {
        DimensionConfig config = parse("the_puce_palace", """
                {"portal":{"frameBlock":{"colorGroup":"puce"}}}
                """);
        List<String> warnings = PortalSafetyValidator.validate(List.of(config));
        // colour warning + (puce has no wool, so no place block) place warning
        assertTrue(warnings.stream().anyMatch(w -> w.contains("puce")));
        // a real colour is silent (wool default place block)
        DimensionConfig red = parse("d", """
                {"portal":{"frameBlock":{"colorGroup":"red"}}}
                """);
        assertTrue(PortalSafetyValidator.validate(List.of(red)).isEmpty());
    }

    @Test
    void invalidOrientationAndMalformedFormsWarn() {
        DimensionConfig sideways = parse("the_tilted", """
                {"portal":{"frameBlock":"minecraft:clay","orientation":"sideways"}}
                """);
        List<String> warnings = PortalSafetyValidator.validate(List.of(sideways));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("sideways"));
        DimensionConfig ok = parse("d", """
                {"portal":{"frameBlock":"minecraft:clay","orientation":"vertical_x"}}
                """);
        assertTrue(PortalSafetyValidator.validate(List.of(ok)).isEmpty());
        DimensionConfig malformed = parse("the_glitch", """
                {"portal":{"frameBlock":["minecraft:clay","Not An Id"]}}
                """);
        List<String> malformedWarnings = PortalSafetyValidator.validate(List.of(malformed));
        assertEquals(1, malformedWarnings.size());
        assertTrue(malformedWarnings.get(0).contains("Not An Id"));
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
