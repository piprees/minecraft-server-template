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
 * The config panel: what a dimension is, beside the seed being judged.
 *
 * <p>Two things it must never do. It must not print a field the config
 * leaves out — an empty row reads as a setting. And it must not imply that
 * editing a field would change an existing world when it would not: the
 * timing marker on every block is the panel's main claim, and it is the one
 * a reader would act on.
 */
class ConfigPanelTest {

    private static final Gson GSON = new Gson();

    /** The real dimension configs, relative to this Gradle project. */
    private static final Path SHIPPED_DIMENSIONS =
            Path.of("../../config/custom-dimensions/dimensions");

    private static String render(String slug, String json) {
        DimensionConfig config = GSON.fromJson(json, DimensionConfig.class);
        config.setName(slug);
        return ViewerPage.config(config);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }


    /**
     * A placeholder in a VALUE position, not merely the letters. Real ids
     * carry the substring — {@code nullscape:void_barrens} is a shipped
     * biome — so a bare contains("null") fails on a correct render.
     */
    private static boolean leaksToken(String html, String token) {
        return java.util.regex.Pattern
                .compile("(?<![A-Za-z0-9_:.\\-])" + token + "(?![A-Za-z0-9_:.\\-])")
                .matcher(html).find();
    }

    // --- absence --------------------------------------------------------------

    @Test
    void everyBlockIsAbsentUntilItsConfigIs() {
        String html = render("the_test", "{}");
        for (String block : List.of(">terrain<", ">biomes<", ">environment<",
                ">exits<", ">structures<", ">roll intent<")) {
            assertFalse(html.contains(block), block + " has nothing to show: " + html);
        }
        assertFalse(leaksToken(html, "null"), "a null leaked in: " + html);
        assertFalse(leaksToken(html, "undefined"), "an undefined leaked in: " + html);
    }

    @Test
    void theBoundsBlockIsAlwaysThereBecauseABorderAlwaysHasAValue() {
        String html = render("the_test", "{}");
        assertTrue(html.contains(">bounds<"), "no bounds block: " + html);
        assertTrue(html.contains("8192"), "the default border radius is missing: " + html);
    }

    // --- world ----------------------------------------------------------------

    @Test
    void theWorldBlockNamesTypeNoiseSettingsAndSpawn() {
        String html = render("the_test", """
                {"type":"multi_biome","noiseSettings":"adventure:compressed",
                 "seed":98765,"spawn":[120,64,-40]}
                """);
        assertTrue(html.contains(">world<"), "no world block: " + html);
        assertTrue(html.contains("multi_biome"), "type missing: " + html);
        assertTrue(html.contains("adventure:compressed"), "noiseSettings missing: " + html);
        assertTrue(html.contains("98765"), "seed missing: " + html);
        assertTrue(html.contains("120, 64, -40"), "spawn missing: " + html);
    }

    @Test
    void anEnvSeedSaysEnvRatherThanResolvingIt() {
        String html = render("overworld", "{\"seed\":\"env\"}");
        assertTrue(html.contains("env"), "the env sentinel is missing: " + html);
        assertTrue(html.contains("SEED"),
                "the panel should say where an env seed comes from: " + html);
    }

    // --- terrain --------------------------------------------------------------

    @Test
    void settingsOverridesShowTheFluidAndBlockSwaps() {
        String html = render("the_crimson_nexus", """
                {"settingsOverrides":{"defaultFluid":"minecraft:lava","defaultBlock":"minecraft:blackstone",
                 "seaLevel":31,"disableMobGeneration":true,"endIsland":false}}
                """);
        assertTrue(html.contains(">terrain<"), "no terrain block: " + html);
        assertTrue(html.contains("minecraft:lava"), "the fluid override is missing: " + html);
        assertTrue(html.contains("minecraft:blackstone"), "the block override is missing: " + html);
        assertTrue(html.contains("31"), "the sea level is missing: " + html);
        assertTrue(html.contains("no mobs generate"), "disableMobGeneration is not spelled out: " + html);
        assertTrue(html.contains("no origin island"), "endIsland false is not spelled out: " + html);
    }

    @Test
    void superflatShowsItsLayerStackBottomUp() {
        String html = render("the_test", """
                {"type":"superflat","flatBiome":"minecraft:desert",
                 "layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:sand","height":3}]}
                """);
        assertTrue(html.contains("minecraft:bedrock"), "layer 1 missing: " + html);
        assertTrue(html.contains("minecraft:sand"), "layer 2 missing: " + html);
        assertTrue(html.contains("minecraft:desert"), "the flat biome is missing: " + html);
        assertTrue(html.contains("bottom up"), "the stack order must be stated: " + html);
    }

    @Test
    void checkerboardShowsTheCellSizeItsScaleMeans() {
        // 2^(scale+4): scale 2 is 64 blocks, and the number people care about
        // is the block size, not the exponent.
        String html = render("the_test",
                "{\"type\":\"checkerboard\",\"checkerboardScale\":2}");
        assertTrue(html.contains("64"), "the cell size in blocks is missing: " + html);
    }

    // --- biomes ---------------------------------------------------------------

    @Test
    void everyListedBiomeIsShown() {
        String html = render("the_test",
                "{\"biomes\":[\"minecraft:jungle\",\"minecraft:bamboo_jungle\",\"terralith:cloud_forest\"]}");
        assertTrue(html.contains(">biomes<"), "no biomes block: " + html);
        assertTrue(html.contains("minecraft:jungle"), "biome 1 missing");
        assertTrue(html.contains("minecraft:bamboo_jungle"), "biome 2 missing");
        assertTrue(html.contains("terralith:cloud_forest"), "biome 3 missing");
        assertTrue(html.contains("3"), "the count is missing: " + html);
    }

    @Test
    void aBandedBiomeEntryShowsTheAxisAndIntervalItClaims() {
        String html = render("the_test", """
                {"biomes":["minecraft:jungle",
                  {"id":"natures_spirit:cypress_fields","parameters":{"weirdness":[-0.5,0.2]}}]}
                """);
        assertTrue(html.contains("natures_spirit:cypress_fields"), "the banded biome is missing");
        assertTrue(html.contains("weirdness"), "the banded axis is missing: " + html);
        assertTrue(html.contains("-0.5"), "the interval is missing: " + html);
        assertTrue(html.contains("banded"), "a banded entry must be marked as one: " + html);
    }

    @Test
    void biomePatchesReadAsPlacementsNotARawList() {
        String html = render("the_test", """
                {"biomePatches":[{"biome":"minecraft:desert","x":100,"z":-200,"radius":48,
                  "shape":"circle","blend":8}]}
                """);
        assertTrue(html.contains("minecraft:desert"), "the patch biome is missing");
        assertTrue(html.contains("100, -200"), "the patch centre is missing: " + html);
        assertTrue(html.contains("48"), "the patch radius is missing: " + html);
        assertFalse(html.contains("BiomePatch"), "a raw object must never be printed: " + html);
    }

    // --- bounds ---------------------------------------------------------------

    @Test
    void theGenerationBorderIsMarkedAsToolingThatNoWorldEverSees() {
        String html = render("the_test",
                "{\"borders\":{\"player\":2048,\"generation\":4096}}");
        assertTrue(html.contains("2048"), "the player border is missing");
        assertTrue(html.contains("4096"), "the generation border is missing");
        // The legend names every marker, so contains("tooling") passes even
        // with the row's own marker gone. Count the class instead: one from
        // the legend, one from the row.
        assertTrue(count(html, "ptime-tooling") >= 2,
                "the generation row itself must carry the tooling marker: " + html);
        assertTrue(html.contains("never applied to the world"),
                "the surprising half of borders.generation must be spelled out: " + html);
    }

    @Test
    void theBorderStatesHowMuchOfTheSourceWorldItReaches() {
        // radius x scale is the source-world reach — the arithmetic behind
        // "borders.player must be overworldBorder / scale", stated without
        // asserting what the source world's border is.
        String html = render("the_test", """
                {"borders":{"player":2048,"generation":2048},
                 "portal":{"frameBlock":"minecraft:stone","scale":4.0}}
                """);
        assertTrue(html.contains("8192"), "2048 x 4.0 = 8192 is not stated: " + html);
        assertTrue(html.contains("source world"), "the reach needs naming: " + html);
    }

    // --- difficulty -----------------------------------------------------------

    @Test
    void difficultyShowsTheMultiplierLuckAndWhichAttributesItTouches() {
        String html = render("the_test", """
                {"difficulty":{"hostileSpawning":true,"mobMultiplier":2.5,"playerLuck":2.0,
                  "attributes":{"health":true,"damage":true,"armor":false,"speed":false,"knockback":false}}}
                """);
        assertTrue(html.contains(">difficulty<"), "no difficulty block: " + html);
        assertTrue(html.contains("2.5"), "the multiplier is missing");
        assertTrue(html.contains("2.0"), "player luck is missing");
        assertTrue(html.contains("health"), "an enabled attribute is missing: " + html);
        assertTrue(html.contains("damage"), "an enabled attribute is missing: " + html);
        assertFalse(html.contains("armor"), "a disabled attribute is not touched: " + html);
    }

    @Test
    void depthScalingIsShownAsAFactorRangeNotAnAbsoluteOne() {
        // DifficultyManager.effectiveMultiplier is mobMultiplier * depthFactor,
        // so 2.5 with a 1.5-3.5 ramp really runs 3.75 to 8.75. Writing the
        // ramp alone is how a dimension silently doubles.
        String html = render("the_forged_depths", """
                {"difficulty":{"mobMultiplier":2.5,
                  "depthScaling":{"enabled":true,"startY":0,"endY":-64,
                    "minMultiplier":1.5,"maxMultiplier":3.5}}}
                """);
        assertTrue(html.contains("3.75"), "the effective floor is missing: " + html);
        assertTrue(html.contains("8.75"), "the effective ceiling is missing: " + html);
        assertTrue(html.contains("-64"), "the depth range is missing: " + html);
    }

    // --- structures -----------------------------------------------------------

    @Test
    void structuresShowDensityWantsShunsAndForcedPlacements() {
        String html = render("the_test", """
                {"structureDensity":"sparse",
                 "structures":{"wants":{"igloo":{"min":256,"max":1200}},
                  "shuns":{"village":{}},
                  "force":[{"structure":"minecraft:ancient_city","x":1200,"z":-800}],
                  "mode":"allow","list":["minecraft:villages"],
                  "clearSpawnRadius":64}}
                """);
        assertTrue(html.contains(">structures<"), "no structures block: " + html);
        assertTrue(html.contains("sparse"), "the density is missing");
        assertTrue(html.contains("igloo"), "the want is missing");
        assertTrue(html.contains("256"), "the want band is missing");
        assertTrue(html.contains("village"), "the shun is missing");
        assertTrue(html.contains("minecraft:ancient_city"), "the forced structure is missing");
        assertTrue(html.contains("1200, -800"), "the forced position is missing: " + html);
        assertTrue(html.contains("64"), "clearSpawnRadius is missing");
    }

    @Test
    void theNoisePlanShowsPerGroupProfilesAndRadialCurves() {
        String html = render("the_test", """
                {"structures":{"noise":{"dungeons":"sparse","settlements":"none"},
                  "radial":{"settlements":[1.5,1.2,1.0,0.7,0.4,0.2,0.0,0.0,0.0,0.0]},
                  "rarity":{"minecraft:trial_chambers":"common"},
                  "exclude":["minecraft:villages"],"include":["mes:phantom_citadel"]}}
                """);
        assertTrue(html.contains("dungeons"), "a per-group profile is missing: " + html);
        assertTrue(html.contains("settlements"), "a per-group profile is missing");
        assertTrue(html.contains("minecraft:trial_chambers"), "a rarity entry is missing");
        assertTrue(html.contains("minecraft:villages"), "an exclude is missing");
        assertTrue(html.contains("mes:phantom_citadel"), "an include is missing");
        assertTrue(html.contains("spawn"), "a radial curve runs spawn to border: " + html);
    }

    @Test
    void aStructureNamedInBothWantsAndShunsIsFlagged() {
        String html = render("the_test",
                "{\"structures\":{\"wants\":{\"village\":{\"min\":0,\"max\":256}},"
                        + "\"shuns\":{\"village\":{}}}}");
        assertTrue(html.contains("cancel"),
                "a want and a shun on the same structure cancel, and the panel must say so: " + html);
    }

    // --- timing and inertness -------------------------------------------------

    @Test
    void everyBlockCarriesTheTimingOfTheFieldsInIt() {
        String html = render("the_test", """
                {"type":"multi_biome","biomes":["minecraft:jungle"],
                 "settingsOverrides":{"seaLevel":31},
                 "difficulty":{"mobMultiplier":2.0},
                 "structures":{"noise":"sparse"},
                 "environment":{"fixedTime":18000},
                 "exits":{"void":{"target":"origin","action":"teleport"}},
                 "seedRoll":{"mood":"hard"}}
                """);
        // Twice each: the legend explains the marker, a block head carries it.
        assertTrue(count(html, "ptime-frozen") >= 2, "creation-time blocks must be marked: " + html);
        assertTrue(count(html, "ptime-new-chunks") >= 2, "structure timing must be marked: " + html);
        assertTrue(count(html, "ptime-live") >= 2, "boot-re-read blocks must be marked: " + html);
        assertTrue(html.contains("scoring"), "seedRoll affects nothing at runtime: " + html);
        assertTrue(html.contains("world wipe"),
                "the legend must say what frozen costs: " + html);
    }

    @Test
    void environmentHeightsAreFrozenWhileTheRestOfEnvironmentIsLive() {
        String html = render("the_test",
                "{\"environment\":{\"minY\":-64,\"height\":512,\"logicalHeight\":512,\"fixedTime\":18000}}");
        assertTrue(html.contains(">environment<"), "no environment block: " + html);
        assertTrue(html.contains("512"), "the height is missing");
        assertTrue(html.contains("18000"), "fixedTime is missing");
        // The three storage-shape fields are the exception inside a live block.
        assertTrue(count(html, "ptime-frozen") >= 2,
                "the height row itself must carry a frozen marker, not just the legend: " + html);
    }

    @Test
    void parsedButInertFieldsAreMarkedInert() {
        String html = render("the_test", """
                {"structures":{"endgame":{"allow":true,"safeRadius":1500},
                   "shuns":{"village":{"minDistance":512}}},
                 "seedRoll":{"spawnRadius":128,"locateCap":5000,"allowEndgameNearSpawn":true}}
                """);
        // Exactly six: the legend's own marker, plus one for each of
        // endgame, the shun distance, spawnRadius, locateCap and
        // allowEndgameNearSpawn. A threshold would let one row lose its
        // marker unnoticed.
        assertEquals(6, count(html, "ptime-inert"),
                "every field the mod parses and never reads must say so: " + html);
        assertTrue(html.contains("endgame"), "the inert endgame block is missing: " + html);
    }

    // --- environment, exits, roll intent --------------------------------------

    @Test
    void environmentShowsTheFlagsItSetsAndNotTheOnesItDoesNot() {
        String html = render("the_test", """
                {"environment":{"ambientLight":0.3,"fixedTime":18000,"hasCeiling":true,
                  "natural":false,"bedWorks":false,"effects":"minecraft:the_end",
                  "infiniburn":"#minecraft:infiniburn_overworld","monsterSpawnLightLevel":7}}
                """);
        assertTrue(html.contains("0.3"), "ambient light is missing");
        assertTrue(html.contains("minecraft:the_end"), "the sky effect is missing");
        assertTrue(html.contains("#minecraft:infiniburn_overworld"), "infiniburn is missing");
        assertTrue(html.contains("7"), "the spawn light level is missing");
        assertFalse(html.contains("piglinSafe"), "an unset flag must not appear: " + html);
        assertFalse(html.contains("hasRaids"), "an unset flag must not appear: " + html);
    }

    @Test
    void skyAndFogColoursAreShownAsClientOnly() {
        String html = render("the_test",
                "{\"environment\":{\"skyColor\":\"#4A2C6B\",\"fogColor\":\"#2A1A3E\"}}");
        assertTrue(html.contains("4A2C6B"), "the sky colour is missing");
        assertTrue(html.contains("client"),
                "sky and fog colours do nothing server-side and must say so: " + html);
    }

    @Test
    void exitsReadAsATriggerAndWhatItDoes() {
        String html = render("the_test", """
                {"exits":{"void":{"target":"origin","action":"teleport"},
                  "fallFrom":{"minHeight":100,"target":"origin","action":"teleport"},
                  "death:lava":{"action":"respawnAt","target":"worldSpawn"}}}
                """);
        assertTrue(html.contains(">exits<"), "no exits block: " + html);
        assertTrue(html.contains("void"), "the void trigger is missing");
        assertTrue(html.contains("death:lava"), "a qualified death trigger is missing: " + html);
        assertTrue(html.contains("teleport"), "the action is missing");
        assertTrue(html.contains("100"), "the fall height is missing");
        assertTrue(html.contains("worldSpawn"), "the target is missing");
    }

    @Test
    void rollIntentShowsWhatAGoodSeedMeansHere() {
        String html = render("the_test", """
                {"seedRoll":{"mood":"adventurous","family":"overworld","water":"none",
                  "terrain":"islands","heightRange":[-60,440],"allowHazardousSpawn":true,
                  "spawnFilter":["minecraft:desert","minecraft:savanna"],
                  "wants":{"village":"near_spawn"},"shuns":["tavern"]}}
                """);
        assertTrue(html.contains(">roll intent<"), "no roll-intent block: " + html);
        assertTrue(html.contains("adventurous"), "the mood is missing");
        assertTrue(html.contains("minecraft:desert"), "the spawn filter is missing");
        assertTrue(html.contains("islands"), "the terrain word is missing");
        assertTrue(html.contains("-60"), "the height envelope is missing");
        assertTrue(html.contains("near_spawn"), "a roll want band is missing");
        assertTrue(html.contains("tavern"), "a roll shun is missing");
        assertTrue(html.contains("spawn safety"),
                "allowHazardousSpawn withdraws the spawn-safety criteria and must say so: " + html);
    }

    @Test
    void aSkippedDimensionSaysItIsNotRolled() {
        String html = render("the_test", "{\"seedRoll\":{\"skip\":true}}");
        assertTrue(html.contains("not rolled"), "seedRoll.skip must be visible: " + html);
    }

    // --- escaping and the shipped set -----------------------------------------

    @Test
    void everyValueIsHtmlEscaped() {
        String html = render("the_test",
                "{\"type\":\"mod:a<b\",\"noiseSettings\":\"mod:c&d\"}");
        assertTrue(html.contains("mod:a&lt;b"), "the type is not escaped: " + html);
        assertTrue(html.contains("mod:c&amp;d"), "the noise settings are not escaped: " + html);
        assertFalse(html.contains("a<b"), "raw markup reached the page: " + html);
    }

    @Test
    void everyShippedConfigRendersWithoutLeakingAPlaceholder() throws IOException {
        assertTrue(Files.isDirectory(SHIPPED_DIMENSIONS),
                "shipped configs missing: " + SHIPPED_DIMENSIONS.toAbsolutePath());
        int rendered = 0;
        try (var files = Files.list(SHIPPED_DIMENSIONS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String slug = file.getFileName().toString().replace(".json", "");
                if (slug.endsWith("_thumb")) {
                    continue;
                }
                String html = render(slug, Files.readString(file));
                rendered++;
                assertFalse(leaksToken(html, "null"), slug + " leaked a null: " + html);
                assertFalse(leaksToken(html, "undefined"), slug + " leaked an undefined: " + html);
                assertFalse(leaksToken(html, "NaN"), slug + " leaked a NaN: " + html);
                assertFalse(html.contains("[L"), slug + " printed a raw array: " + html);
                assertFalse(html.contains("@"), slug + " printed an object identity: " + html);
                assertTrue(html.contains(">bounds<"), slug + " rendered no bounds block");
            }
        }
        assertEquals(82, rendered, "every shipped dimension must render a config panel");
    }

    /**
     * The maintainer's named example: the lava override is the kind of field
     * that was invisible before this panel.
     */
    @Test
    void theShippedCrimsonNexusShowsItsLavaOverride() throws IOException {
        Path file = SHIPPED_DIMENSIONS.resolve("the_crimson_nexus.json");
        assertTrue(Files.exists(file), "shipped config missing: " + file.toAbsolutePath());
        String html = render("the_crimson_nexus", Files.readString(file));
        assertTrue(html.contains("minecraft:lava"),
                "the crimson nexus's defaultFluid override is not shown: " + html);
    }

}
