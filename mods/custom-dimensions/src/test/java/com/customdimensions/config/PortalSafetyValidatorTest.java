package com.customdimensions.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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
    void aPortalWithNoFrameBlockWarns() {
        DimensionConfig config = parse("the_open_door", """
                {"portal":{"shape":"end_gateway"}}
                """);
        List<String> warnings = PortalSafetyValidator.validate(List.of(config));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("no frameBlock"));
        assertTrue(warnings.get(0).contains("never auto-fixed"));

        // Nothing about the shape: a frame is what every portal is made of.
        DimensionConfig plain = parse("the_other_open_door", """
                {"portal":{"igniterItem":"minecraft:flint_and_steel"}}
                """);
        assertEquals(1, PortalSafetyValidator.validate(List.of(plain)).size());
    }

    @Test
    void aGatewayWithAFrameBlockIsSilent() {
        DimensionConfig config = parse("the_crumbling_reaches", """
                {"portal":{"frameBlock":"minecraft:mud_bricks","shape":"end_gateway"}}
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
    void reservedDimensionsAreCheckedLikeAnyOther() {
        // Reserved-dimension portals are real portals — same registry, same rules —
        // so the same authoring traps apply to them.
        DimensionConfig overworld = parse("overworld", """
                {"portal":{"frameBlock":"b","singleUse":{"enabled":true}}}
                """);
        List<String> warnings = PortalSafetyValidator.validate(List.of(overworld));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("singleUse"));
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
        // Reserved-dimension links are always known.
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

    @Test
    void vanillaManagedNetherAtTheWrongScaleWarns() {
        DimensionConfig config = parse("the_nether", """
                {"portal":{"frameBlock":"minecraft:obsidian",
                 "vanillaManaged":true,"scale":1.0}}
                """);
        List<String> warnings = PortalSafetyValidator.validate(List.of(config));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("vanillaManaged"));
        assertTrue(warnings.get(0).contains("1:8"));
        assertTrue(warnings.get(0).contains("never auto-fixed"));
    }

    @Test
    void vanillaManagedNetherAtEightIsSilent() {
        DimensionConfig config = parse("the_nether", """
                {"portal":{"frameBlock":"minecraft:obsidian",
                 "vanillaManaged":true,"scale":8.0}}
                """);
        assertTrue(PortalSafetyValidator.validate(List.of(config)).isEmpty());
    }

    @Test
    void anOrdinaryNetherPortalKeepsWhateverScaleItStates() {
        // The rule encodes what VANILLA does. A mod-owned route into the same
        // dimension performs its own traversal and may state its own ratio.
        DimensionConfig config = parse("the_nether", """
                {"portal":{"frameBlock":"minecraft:obsidian","scale":1.0}}
                """);
        assertTrue(PortalSafetyValidator.validate(List.of(config)).isEmpty());
    }

    @Test
    void auraOnAVanillaManagedPortalWarns() {
        DimensionConfig config = parse("the_nether", """
                {"portal":{"frameBlock":"minecraft:obsidian","vanillaManaged":true,
                 "scale":8.0,"aura":{"enabled":true,"palette":["minecraft:netherrack"]}}}
                """);
        List<String> warnings = PortalSafetyValidator.validate(List.of(config));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("portal.aura"));
        assertTrue(warnings.get(0).contains("nothing will ever leak"));
    }

    @Test
    void aScaleTooLargeForItsBorderStrandsArrivalsAndSaysSo() {
        // The shipped set trips this nowhere, so nothing else exercises the
        // check id the two build gates in ShippedDimensionReachabilityTest
        // now key on. Source radius falls back to the 8192 default, so a
        // 1:8 portal needs 1024 of player border and this config has 64.
        DimensionConfig config = parse("the_pinhole", """
                {"portal":{"frameBlock":"minecraft:obsidian","scale":8.0},
                 "borders":{"player":64}}
                """);

        List<PortalSafetyValidator.SafetyFinding> findings =
                PortalSafetyValidator.findings(List.of(config));

        assertEquals(List.of("arrival_unreachable"),
                findings.stream().map(PortalSafetyValidator.SafetyFinding::check).toList());
        assertEquals("the_pinhole", findings.get(0).dimension());
        assertTrue(findings.get(0).message().contains("arrive inside this dimension's border"),
                findings.get(0).message());
        assertTrue(findings.get(0).fix().contains("borders.player"), findings.get(0).fix());
    }

    @Test
    void aScaleThatFitsItsBorderIsSilent() {
        DimensionConfig config = parse("the_wide_place", """
                {"portal":{"frameBlock":"minecraft:obsidian","scale":8.0},
                 "borders":{"player":1024}}
                """);
        assertTrue(PortalSafetyValidator.validate(List.of(config)).isEmpty());
    }

    @Test
    void theBootLogRendersEveryFindingWithItsMessageAndItsFix() {
        // The two build gates in ShippedDimensionReachabilityTest key on
        // check ids, and DimensionLint reads the fields directly, so nothing
        // else would notice if this renderer stopped carrying half of what a
        // finding says. The boot log is the only place it is read as prose.
        DimensionConfig config = parse("the_well", """
                {"portal":{"frameBlock":"minecraft:obsidian","anchor":{"exit":"bed"}}}
                """);

        List<PortalSafetyValidator.SafetyFinding> findings =
                PortalSafetyValidator.findings(List.of(config));
        List<String> rendered = PortalSafetyValidator.validate(List.of(config));

        assertEquals(findings.size(), rendered.size(), "one line per finding");
        assertEquals(1, rendered.size());
        PortalSafetyValidator.SafetyFinding finding = findings.get(0);
        assertTrue(rendered.get(0).startsWith("Dimension the_well: "), rendered.get(0));
        assertTrue(rendered.get(0).contains(finding.message()), rendered.get(0));
        assertTrue(rendered.get(0).contains(finding.fix()), rendered.get(0));
        assertTrue(rendered.get(0).endsWith("(never auto-fixed)."), rendered.get(0));
    }

    @Test
    void immersiveOnAVanillaManagedPortalIsSilent() {
        // The preview is drawn through a presentation zone, so it is honoured
        // rather than dropped — nothing to warn about.
        DimensionConfig config = parse("the_nether", """
                {"portal":{"frameBlock":"minecraft:obsidian","vanillaManaged":true,
                 "scale":8.0,"immersive":true}}
                """);
        assertTrue(PortalSafetyValidator.validate(List.of(config)).isEmpty());
    }

    // ------------------------------------------------- unignitable check ids

    /** A portal that lights, so a check id must not fire on everything. */
    private static final String LIGHTABLE = "{\"portal\":{\"frameBlock\":\"minecraft:obsidian\"}}";

    /**
     * One config per check id whose verdict is "this portal can never be lit".
     * {@code ShippedDimensionReachabilityTest} gates the build by filtering the
     * shipped set through those ids, and the tests above pin the PROSE of these
     * same checks rather than the id, so nothing else notices a rename: the
     * filter matches nothing, the gate compares two empty lists, and eight
     * ignition checks stop guarding anything.
     */
    private static Stream<Arguments> unignitableConfigs() {
        return Stream.of(
                arguments("portal_no_frame_block", """
                        {"portal":{"igniterItem":"minecraft:flint_and_steel"}}"""),
                arguments("frame_block_unusable", """
                        {"portal":{"frameBlock":{"block":"minecraft:stone"}}}"""),
                arguments("frame_color_group_unknown", """
                        {"portal":{"frameBlock":{"colorGroup":"puce"},
                         "framePlaceBlock":"minecraft:pink_wool"}}"""),
                arguments("frame_materials_empty", """
                        {"portal":{"frameBlock":"minecraft:obsidian",
                         "frameMaterials":{"middle":"minecraft:stone"}}}"""),
                arguments("portal_shape_unknown", """
                        {"portal":{"frameBlock":"minecraft:obsidian","shape":"hexagon"}}"""),
                arguments("portal_shape_not_a_pattern", """
                        {"portal":{"frameBlock":"minecraft:obsidian","shape":{"type":"blob"}}}"""),
                arguments("portal_shape_no_interior", """
                        {"portal":{"frameBlock":"minecraft:obsidian","shape":{"type":"pattern",
                         "template":["FFF","FFF"],"legend":{"F":"frame"}}}}"""),
                arguments("portal_shape_orientation_conflict", """
                        {"portal":{"frameBlock":"minecraft:obsidian","shape":"door",
                         "orientation":"horizontal"}}"""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unignitableConfigs")
    void everyUnignitableCheckIsReportedUnderTheIdTheBuildGatesFilterOn(String check, String json) {
        List<String> tripped = checkIds(parse("the_unlightable", json));
        assertTrue(tripped.contains(check),
                "no finding carries check id '" + check + "' — the build gates in "
                + "ShippedDimensionReachabilityTest filter on that id, so it now matches nothing "
                + "and they pass having asserted nothing. Findings were: " + tripped);

        assertFalse(checkIds(parse("the_lightable", LIGHTABLE)).contains(check),
                "'" + check + "' also fires on a portal that lights, so it says nothing about "
                + "the config it is reported against");
    }

    private static List<String> checkIds(DimensionConfig config) {
        return PortalSafetyValidator.findings(List.of(config)).stream()
                .map(PortalSafetyValidator.SafetyFinding::check).toList();
    }
}
