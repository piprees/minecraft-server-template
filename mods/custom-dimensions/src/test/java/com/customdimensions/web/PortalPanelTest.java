package com.customdimensions.web;

import com.customdimensions.config.DimensionConfig;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Portals panel: every portal a dimension declares, stated in full.
 *
 * <p>The panel is read to answer "what do I build, what do I light it with,
 * and what happens then", so an absent field must read as absent — a row
 * saying "null" is worse than no row, because it looks like a value.
 */
class PortalPanelTest {

    private static final Gson GSON = new Gson();

    /** The real dimension configs, relative to this Gradle project. */
    private static final Path SHIPPED_DIMENSIONS =
            Path.of("../../config/custom-dimensions/dimensions");

    private static String render(String slug, String json) {
        DimensionConfig config = GSON.fromJson(json, DimensionConfig.class);
        config.setName(slug);
        return ViewerPage.portals(config);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    // --- nothing to show ------------------------------------------------------

    @Test
    void aDimensionWithNoPortalRendersNothingAtAll() {
        for (String json : List.of("{}", "{\"portal\":null}", "{\"portal\":[]}")) {
            assertEquals("", render("the_test", json), json);
        }
    }

    // --- the frame ------------------------------------------------------------

    @Test
    void aSinglePortalStatesItsFrameIgniterColourAndTravel() {
        String html = render("the_crucible", """
                {"portal":{"frameBlock":"minecraft:copper_block","igniterItem":"minecraft:diamond",
                 "color":"8BAF5B","lightLevel":11,"scale":4.0,"cooldown":40}}
                """);
        assertTrue(html.contains("class='portals'"), "no portals panel: " + html);
        assertTrue(html.contains("the_crucible"), "the portal is not named");
        assertTrue(html.contains("minecraft:copper_block"), "no frame block");
        assertTrue(html.contains("minecraft:diamond"), "no igniter");
        assertTrue(html.contains("8BAF5B"), "no colour");
        assertTrue(html.contains("11"), "no light level");
        assertTrue(html.contains("4"), "no scale");
        assertTrue(html.contains("40"), "no cooldown");
    }

    @Test
    void oneAcceptedFormSaysItIsTheOnlyOne() {
        String html = render("the_test",
                "{\"portal\":{\"frameBlock\":\"minecraft:copper_block\"}}");
        assertEquals(1, count(html, "class='pblock'"),
                "a single-form frame should render exactly one block chip: " + html);
        assertTrue(html.contains("the only form it accepts"),
                "a single-form frame must say so: " + html);
        assertFalse(html.contains("any of "), "a single form is not a choice: " + html);
    }

    @Test
    void everyAcceptedFormIsListedAndCounted() {
        String html = render("the_test", """
                {"portal":{"frameBlock":["minecraft:copper_block","minecraft:exposed_copper",
                 "#minecraft:logs"]}}
                """);
        assertTrue(html.contains("minecraft:copper_block"), "form 1 missing");
        assertTrue(html.contains("minecraft:exposed_copper"), "form 2 missing");
        assertTrue(html.contains("#minecraft:logs"), "the tag form missing");
        assertTrue(html.contains("any of 3"), "the count of accepted forms is not stated: " + html);
        assertFalse(html.contains("the only form it accepts"), "three forms are not one");
    }

    @Test
    void aColourGroupNamesTheColourAsWellAsTheTagItResolvesTo() {
        String html = render("the_test",
                "{\"portal\":{\"frameBlock\":{\"colorGroup\":\"red\"}}}");
        assertTrue(html.contains("#adventure:red_blocks"), "the resolved tag is missing: " + html);
        assertTrue(html.contains("colour group red"), "the colour group is not named: " + html);
    }

    @Test
    void perPartMaterialsReadAsTopSidesAndBottom() {
        String html = render("the_test", """
                {"portal":{"frameMaterials":{"top":"minecraft:oak_planks",
                 "sides":"#minecraft:logs","bottom":"minecraft:stone"},
                 "framePlaceBlock":"minecraft:oak_log"}}
                """);
        assertTrue(html.contains(">top<"), "no top part row: " + html);
        assertTrue(html.contains(">sides<"), "no sides part row: " + html);
        assertTrue(html.contains(">bottom<"), "no bottom part row: " + html);
        assertTrue(html.contains("minecraft:oak_planks"), "top material missing");
        assertTrue(html.contains("#minecraft:logs"), "sides material missing");
        assertTrue(html.contains("minecraft:stone"), "bottom material missing");
        assertFalse(html.contains("{top="), "the raw map must never be printed: " + html);
        assertFalse(html.contains("[minecraft:"), "a raw list must never be printed: " + html);
    }

    @Test
    void aPartLeftOutSaysItAcceptsTheUnion() {
        String html = render("the_test",
                "{\"portal\":{\"frameMaterials\":{\"top\":\"minecraft:oak_planks\"}}}");
        assertTrue(html.contains(">sides<") && html.contains(">bottom<"),
                "unspecified parts still need naming: " + html);
        assertTrue(html.contains("any accepted form"),
                "an unspecified part accepts the union and must say so: " + html);
    }

    @Test
    void whatTheModPlacesIsStatedWhenItDiffersFromWhatIsAccepted() {
        String html = render("the_test",
                "{\"portal\":{\"frameBlock\":\"#minecraft:logs\",\"framePlaceBlock\":\"minecraft:oak_log\"}}");
        assertTrue(html.contains("minecraft:oak_log"), "the place block is missing: " + html);
        assertTrue(html.contains("builds with"), "the place block needs its own label: " + html);
    }

    // --- shape ----------------------------------------------------------------

    @Test
    void aPatternShapeIsDrawnAsAGridOfCells() {
        String html = render("the_test", """
                {"portal":{"frameBlock":"minecraft:stone","shape":{"type":"pattern",
                 "template":["FFFFF","FF.FF","F...F","FF.FF","FFFFF"],
                 "legend":{"F":"frame",".":"interior"}}}}
                """);
        assertEquals(5, count(html, "class='pshape-row'"), "five template rows expected: " + html);
        assertEquals(25, count(html, "class='pcell"), "25 cells expected: " + html);
        assertEquals(5, count(html, "pcell-interior"), "5 interior cells expected: " + html);
        assertEquals(20, count(html, "pcell-frame"), "20 frame cells expected: " + html);
        assertFalse(html.contains("FFFFF"), "the raw template must not be dumped: " + html);
    }

    @Test
    void aNamedShapeIsDrawnAsTheInteriorItDemands() {
        String door = render("the_test",
                "{\"portal\":{\"frameBlock\":\"minecraft:stone\",\"shape\":\"door\"}}");
        assertTrue(door.contains("door"), "the shape is not named: " + door);
        assertEquals(2, count(door, "pcell-interior"), "a door is a 1x2 interior: " + door);

        String doorway = render("the_test",
                "{\"portal\":{\"frameBlock\":\"minecraft:stone\",\"shape\":\"doorway\"}}");
        assertEquals(6, count(doorway, "pcell-interior"), "a doorway is a 2x3 interior: " + doorway);

        String gateway = render("the_test",
                "{\"portal\":{\"frameBlock\":\"minecraft:stone\",\"shape\":\"end_gateway\"}}");
        assertEquals(1, count(gateway, "pcell-interior"), "a gateway is one cell: " + gateway);
    }

    @Test
    void aFreeFormShapeIsNotDrawnAsAFixedFootprint() {
        String standard = render("the_test", "{\"portal\":{\"frameBlock\":\"minecraft:stone\"}}");
        assertEquals(0, count(standard, "class='pshape-row'"),
                "free-form flood-fill has no fixed shape to draw: " + standard);
        String endExit = render("the_test",
                "{\"portal\":{\"frameBlock\":\"minecraft:stone\",\"shape\":\"end_exit\"}}");
        assertEquals(0, count(endExit, "class='pshape-row'"),
                "end_exit accepts any footprint, so no grid: " + endExit);
        assertTrue(endExit.contains("end_exit"), "the shape is still named: " + endExit);
    }

    @Test
    void orientationIsTheEffectiveOneNotJustTheWrittenField() {
        String implied = render("the_test",
                "{\"portal\":{\"frameBlock\":\"minecraft:stone\",\"shape\":\"end_exit\"}}");
        assertTrue(implied.contains("horizontal"),
                "end_exit implies a horizontal orientation: " + implied);
        String explicit = render("the_test",
                "{\"portal\":{\"frameBlock\":\"minecraft:stone\",\"orientation\":\"vertical_x\"}}");
        assertTrue(explicit.contains("vertical_x"), "the explicit orientation is missing: " + explicit);
    }

    // --- absence --------------------------------------------------------------

    @Test
    void absentFieldsAreOmittedRatherThanPrintedAsNothing() {
        String html = render("the_test", "{\"portal\":{\"frameBlock\":\"minecraft:stone\"}}");
        assertFalse(html.contains("null"), "a null leaked into the panel: " + html);
        assertFalse(html.contains("undefined"), "an undefined leaked into the panel: " + html);
        assertFalse(html.contains("anchor"), "no anchor is configured: " + html);
        assertFalse(html.contains("single use"), "no single-use is configured: " + html);
        assertFalse(html.contains("particles"), "no particle type is configured: " + html);
        assertFalse(html.contains("centre"), "no centre block is configured: " + html);
    }

    @Test
    void aPortalWithNoFrameIsShownAndFlaggedAsUnlightable() {
        String html = render("the_test",
                "{\"portal\":[{\"igniterItem\":\"minecraft:diamond\"},"
                        + "{\"frameBlock\":\"minecraft:mud_bricks\"}]}");
        assertTrue(html.contains("cannot be lit"),
                "a frameless portal must be flagged, not hidden: " + html);
        assertTrue(html.contains("minecraft:mud_bricks"), "the second portal is missing");
    }

    // --- several portals ------------------------------------------------------

    @Test
    void portalsAreNumberedByConfigPositionWithTheFirstMarkedPrimary() {
        String html = render("the_test", """
                {"portal":[
                  {"frameBlock":"minecraft:copper_block"},
                  {"frameBlock":"minecraft:mud_bricks"},
                  {"frameBlock":"minecraft:bone_block"}
                ]}
                """);
        assertEquals(3, count(html, "class='portal '") + count(html, "class='portal'"),
                "one block per portal: " + html);
        assertTrue(html.contains(">the_test<"), "the primary keeps the bare slug: " + html);
        assertTrue(html.contains("the_test#2"), "the second portal id is missing: " + html);
        assertTrue(html.contains("the_test#3"), "the third portal id is missing: " + html);
        assertEquals(1, count(html, "primary"), "exactly one entry is the primary: " + html);
    }

    @Test
    void aVanillaManagedPortalIsFlaggedAsSuch() {
        String html = render("the_nether",
                "{\"portal\":{\"frameBlock\":\"minecraft:obsidian\","
                        + "\"igniterItem\":\"minecraft:flint_and_steel\",\"vanillaManaged\":true}}");
        assertTrue(html.contains("vanilla-managed"), "the flag is missing: " + html);
    }

    // --- anchor, single use, aura, immersive ----------------------------------

    @Test
    void anExplicitAnchorShowsItsCoordinatesAndItsExit() {
        String html = render("the_pale_reach", """
                {"portal":{"frameBlock":"minecraft:end_stone",
                 "anchor":{"pos":[520,37,-480],"exit":"origin"}}}
                """);
        assertTrue(html.contains("anchor"), "no anchor row: " + html);
        assertTrue(html.contains("520, 37, -480"), "the anchor position is missing: " + html);
        assertTrue(html.contains("origin"), "the anchor exit is missing: " + html);
    }

    @Test
    void aSpawnAnchorSaysSpawnRatherThanAResolvedCoordinate() {
        String html = render("the_test", """
                {"portal":{"frameBlock":"minecraft:stone","anchor":{"pos":"spawn","exit":"bed"}}}
                """);
        assertTrue(html.contains("spawn"), "a spawn anchor should say spawn: " + html);
        assertFalse(html.contains("0, 64, 0"),
                "a spawn anchor is not a coordinate the config wrote: " + html);
    }

    @Test
    void singleUseStatesItsDelayAndHowTheFrameBreaks() {
        String html = render("the_lost_outpost", """
                {"portal":{"frameBlock":"minecraft:stone",
                 "singleUse":{"enabled":true,"delaySeconds":15,"breakMode":"partial"}}}
                """);
        assertTrue(html.contains("single use"), "no single-use row: " + html);
        assertTrue(html.contains("15"), "the delay is missing: " + html);
        assertTrue(html.contains("partial"), "the break mode is missing: " + html);
    }

    @Test
    void anAuraShowsItsPolicyAndTheMaterialsItEmits() {
        String html = render("the_crucible", """
                {"portal":{"frameBlock":"minecraft:copper_block",
                 "aura":{"subsume":"everything",
                   "palette":["minecraft:moss_block","minecraft:mud"],
                   "flora":["minecraft:fern"]}}}
                """);
        assertTrue(html.contains("everything"), "the subsume policy is missing: " + html);
        assertTrue(html.contains("minecraft:moss_block"), "a palette entry is missing: " + html);
        assertTrue(html.contains("minecraft:mud"), "a palette entry is missing: " + html);
        assertTrue(html.contains("minecraft:fern"), "a flora entry is missing: " + html);
    }

    @Test
    void anAuraSwitchedOffSaysOffAndListsNoMaterials() {
        String html = render("the_test",
                "{\"portal\":{\"frameBlock\":\"minecraft:stone\",\"aura\":{\"enabled\":false}}}");
        assertTrue(html.contains("aura"), "no aura row: " + html);
        assertTrue(html.contains("off"), "an aura that is off must say so: " + html);
    }

    @Test
    void anUnconfiguredAuraIsDescribedAsTheDerivedLeakItIs() {
        // Specific to the derived branch: the configured branch also says
        // "sampled" (in "aura emits"), so a looser assertion passes either way.
        String html = render("the_test", "{\"portal\":{\"frameBlock\":\"minecraft:stone\"}}");
        assertTrue(html.contains("leaks the far side's sampled terrain"),
                "an aura with no config samples the far side and must say so: " + html);
        assertFalse(html.contains("aura emits"),
                "an unconfigured aura has no configured emission list: " + html);
    }

    @Test
    void immersiveIsOnUnlessTheConfigTurnsItOff() {
        String on = render("the_test", "{\"portal\":{\"frameBlock\":\"minecraft:stone\"}}");
        assertTrue(on.contains("immersive"), "no immersive row: " + on);
        assertFalse(on.contains("not immersive"), "absent means on: " + on);

        String off = render("the_test",
                "{\"portal\":{\"frameBlock\":\"minecraft:stone\",\"immersive\":false}}");
        assertTrue(off.contains("not immersive"), "an explicit false means off: " + off);
    }

    @Test
    void tunedImmersiveSettingsAreShownAsNumbers() {
        String html = render("the_test", """
                {"portal":{"frameBlock":"minecraft:stone",
                 "immersive":{"previewDepth":4,"activationRange":32,"audio":false}}}
                """);
        assertTrue(html.contains("4"), "the preview depth is missing: " + html);
        assertTrue(html.contains("32"), "the activation range is missing: " + html);
        assertTrue(html.contains("no audio"), "audio off must be stated: " + html);
    }

    // --- ways out -------------------------------------------------------------

    @Test
    void anAnchoredDimensionWithNoExitPortalSaysSo() {
        String html = render("the_pale_reach", """
                {"portal":{"frameBlock":"minecraft:end_stone",
                 "anchor":{"pos":[520,37,-480],"exit":"origin"}}}
                """);
        assertTrue(html.contains("nothing here builds a way back"),
                "an anchored dimension with no way home must say so: " + html);
    }

    @Test
    void anExitPortalStatesWhereItIsAndWhereItLeads() {
        String html = render("the_test", """
                {"portal":{"frameBlock":"minecraft:stone"},
                 "exitPortal":{"enabled":true,"pos":"spawn","target":"bed"},
                 "exitShrines":{"enabled":true,"target":"worldSpawn"}}
                """);
        assertTrue(html.contains("exit portal"), "no exit-portal row: " + html);
        assertTrue(html.contains("bed"), "the exit-portal target is missing: " + html);
        assertTrue(html.contains("exit shrines"), "no exit-shrine row: " + html);
        assertTrue(html.contains("worldSpawn"), "the shrine target is missing: " + html);
    }

    // --- escaping and the shipped set -----------------------------------------

    @Test
    void everyValueIsHtmlEscaped() {
        String html = render("the_test",
                "{\"portal\":{\"frameBlock\":\"mod:a<b\",\"igniterItem\":\"mod:c&d\"}}");
        assertTrue(html.contains("mod:a&lt;b"), "the frame id is not escaped: " + html);
        assertTrue(html.contains("mod:c&amp;d"), "the igniter id is not escaped: " + html);
        assertFalse(html.contains("a<b"), "raw markup reached the page: " + html);
    }

    @Test
    void everyShippedPortalConfigRendersWithoutLeakingAPlaceholder() throws IOException {
        assertTrue(java.nio.file.Files.isDirectory(SHIPPED_DIMENSIONS),
                "shipped configs missing: " + SHIPPED_DIMENSIONS.toAbsolutePath());
        int rendered = 0;
        try (var files = Files.list(SHIPPED_DIMENSIONS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String slug = file.getFileName().toString().replace(".json", "");
                if (slug.endsWith("_thumb")) {
                    continue;
                }
                String html = render(slug, Files.readString(file));
                if (html.isEmpty()) {
                    continue;
                }
                rendered++;
                assertFalse(html.contains("null"), slug + " leaked a null: " + html);
                assertFalse(html.contains("undefined"), slug + " leaked an undefined: " + html);
                assertFalse(html.contains("NaN"), slug + " leaked a NaN: " + html);
                assertTrue(html.contains("class='portals'"), slug + " rendered no panel");
                assertTrue(html.contains(">" + slug + "<"), slug + " does not name its portal");
            }
        }
        assertEquals(82, rendered, "every shipped dimension with a portal must render one");
    }

    /**
     * The crucible's frameAccepts is not a schema field, so the panel shows
     * the one block the mod really accepts. This pins the panel to the
     * mod's behaviour rather than to the config's intent — the whole point
     * of showing accept forms at all.
     */
    @Test
    void theShippedCrucibleAcceptsEveryCopperFormItCanWeatherInto() throws IOException {
        Path file = SHIPPED_DIMENSIONS.resolve("the_crucible.json");
        String html = render("the_crucible", Files.readString(file));
        // Unwaxed copper oxidises, so a frame that accepts only copper_block
        // breaks itself (TROUBLESHOOTING.md#t85). The accept list is the
        // frameBlock LIST; there is no frameAccepts config key.
        // oxidized_copper is deliberately absent: it is the_gauntlet's frame,
        // and two dimensions may not share one.
        for (String form : new String[] {
                "minecraft:copper_block", "minecraft:exposed_copper",
                "minecraft:weathered_copper",
                "minecraft:waxed_copper_block", "minecraft:waxed_exposed_copper",
                "minecraft:waxed_weathered_copper", "minecraft:waxed_oxidized_copper" }) {
            // At least once: copper_block is also the framePlaceBlock, so it
            // legitimately renders twice.
            assertTrue(count(html, "class='pblock'>" + form + "<") >= 1,
                    form + " must be an accepted frame form: " + html);
        }
    }
}
