package com.customdimensions.compiler.datapack;

import com.customdimensions.compiler.terrain.TerrainFamily;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Each library terrain at its default setting, compiled as the only
 * definition, against {@code src/test/resources/golden/26.3/terrain_<name>/}
 * in {@link GoldenForm}, and the variants in {@link #variantsMatchTheCommittedOutput}. Regenerate with {@code -PupdateGolden=true}.
 */
class TerrainGoldenTest {

    @ParameterizedTest
    @EnumSource(TerrainFamily.class)
    void matchesTheCommittedOutput(TerrainFamily family) throws IOException {
        if (family.roofed()) {
            matchesOn("terrain_" + family.id(), "nether", "{\"base\": \"" + family.id() + "\"}");
        } else if (family == TerrainFamily.END) {
            matchesOn("terrain_" + family.id(), "end", "{\"base\": \"" + family.id() + "\"}");
        } else {
            matches("terrain_" + family.id(), "{\"base\": \"" + family.id() + "\"}");
        }
    }

    /** Parameter variants that change a terrain's shape beyond its settings. */
    @Test
    void variantsMatchTheCommittedOutput() throws IOException {
        matches("terrain_ridges_thin", "{\"base\": \"ridges\", \"params\": {\"coast\": -0.6, \"seaFloor\": 8, \"landHeight\": 16, "
                + "\"peakHeight\": 240, \"rangeWidth\": 0.1, \"crest\": 0.5, \"erosionFalloff\": 0, \"web\": 1}}");
        matches("terrain_continents_shaped", "{\"base\": \"continents\", \"scales\": {\"continents\": 1.3, \"erosion\": 2, "
                + "\"ridges\": 1.4, \"climate\": 1.5}, \"layout\": {\"type\": \"radial\", \"radius\": 400, \"falloff\": 500, \"strength\": 0.6}}");
        matches("terrain_overworld_shaped", "{\"scales\": {\"continents\": 1.3, \"erosion\": 2.08, \"ridges\": 1.39, \"climate\": 1.67}}");
    }

    /** Stacked tiers and flat, terraced tops on the terrains over a void. */
    @Test
    void skyVariantsMatchTheCommittedOutput() throws IOException {
        matchesOn("terrain_floating_islands_tiers", "floating_islands", "{\"base\": \"floating_islands\", \"params\": {\"altitude\": 110, \"tiers\": 3, "
                + "\"tierSpacing\": 60, \"tierGap\": 24, \"tierShrink\": 0.7}}");
        matchesOn("terrain_floating_islands_plateaus", "floating_islands", "{\"base\": \"floating_islands\", \"params\": {\"flatness\": 1, \"terraces\": 3, "
                + "\"terraceStep\": 8, \"size\": 2}}");
        matchesOn("terrain_basin_islands_tiers", "floating_islands", "{\"base\": \"basin_islands\", \"params\": {\"altitude\": 120, \"tiers\": 2, \"tierSpacing\": 70}}");
    }

    /** Spires on shared floating islands, a ring's hollow held as one lake, and a shifted, warped climate. */
    @Test
    void spiresHollowAndClimateVariantsMatchTheCommittedOutput() throws IOException {
        matchesOn("terrain_floating_islands_spires", "floating_islands", "{\"base\": \"floating_islands\", \"params\": {\"altitude\": 120, \"size\": 2.5, "
                + "\"coverage\": 0.35, \"flatness\": 0.6, \"spires\": 0.08, \"spireHeight\": 90, \"spireThickness\": 1.5, \"spireTaper\": 0.3}}");
        matchesOn("terrain_basin_islands_hollow", "floating_islands", "{\"base\": \"basin_islands\", \"params\": {\"hollowDepth\": 28, "
                + "\"ringLakes\": 0, \"gaps\": 0.1}, \"layout\": {\"type\": \"ring\", \"radius\": 300, \"falloff\": 160, \"strength\": 0.9}}");
        matches("terrain_continents_climate", "{\"base\": \"continents\", \"scales\": {\"climate\": 3}, \"climateShift\": {\"temperature\": 0.25}, "
                + "\"climateWarp\": {\"blocks\": 160, \"wavelength\": 768}}");
    }

    /** A roofed terrain per feature term, on the caves preset, and the climate-only shaping of the presets under a roof. */
    @Test
    void roofedVariantsMatchTheCommittedOutput() throws IOException {
        matchesOn("terrain_roofed_flats", "nether", "{\"base\": \"roofed\", \"params\": {\"floorHeight\": 34, \"floorRelief\": 8, \"flats\": 0.7}}");
        matchesOn("terrain_roofed_pit", "nether", "{\"base\": \"roofed\", \"params\": {\"floorHeight\": 70, \"pit\": 0.03, \"pitDepth\": 44, "
                + "\"ledges\": 0.6}, \"layout\": {\"type\": \"radial\", \"centre\": [96, 0], \"radius\": 80, \"falloff\": 64, \"strength\": 1}}");
        matchesOn("terrain_roofed_chasms", "nether", "{\"base\": \"roofed\", \"params\": {\"floorHeight\": 70, \"chasms\": 0.08, \"chasmDepth\": 56}}");
        matchesOn("terrain_roofed_corridors", "nether", "{\"base\": \"roofed\", \"params\": {\"corridors\": 0.06, \"corridorDepth\": 20}}");
        matchesOn("terrain_roofed_ledges", "nether", "{\"base\": \"roofed\", \"params\": {\"floorRelief\": 24, \"ceilingGap\": 60, \"ledges\": 0.8, "
                + "\"ledgeStep\": 6}}");
        matchesOn("terrain_roofed_pillars", "nether", "{\"base\": \"roofed\", \"params\": {\"pillars\": 0.04, \"pillarSize\": 1.5}, "
                + "\"scales\": {\"climate\": 2}}");
        matchesOn("terrain_roofed_caves", "caves", "{\"base\": \"roofed\", \"setting\": \"extreme\"}");
        matchesOn("terrain_nether_climate", "nether", "{\"scales\": {\"climate\": 2}}");
    }

    /**
     * The sky and End kit: the End's islands shaped and laid out without the central island; layouts gathering
     * floating islands at a point and basin masses round a ring; spires over a void, out of a sea and on islands
     * of their own; biome regions ranked over land with underside and basin roles; End regions over its islands;
     * an End-preset default block.
     */
    @Test
    void skyAndEndVariantsMatchTheCommittedOutput() throws IOException {
        matchesBody("terrain_end_shaped", "\"generator\": {\"type\": \"noise\", \"preset\": \"end\", \"endIsland\": false}, \"terrain\": "
                + "{\"base\": \"end\", \"params\": {\"coverage\": 0.1, \"islandHeight\": 24, \"peaks\": 0.5}, "
                + "\"layout\": {\"type\": \"radial\", \"radius\": 300, \"falloff\": 400, \"strength\": 0.8}}");
        matchesOn("terrain_floating_islands_radial", "floating_islands", "{\"base\": \"floating_islands\", \"params\": {\"coverage\": 0.5}, "
                + "\"layout\": {\"type\": \"radial\", \"radius\": 200, \"falloff\": 300, \"strength\": 1}}");
        matchesOn("terrain_basin_islands_ring", "floating_islands", "{\"base\": \"basin_islands\", \"setting\": \"extreme\", "
                + "\"layout\": {\"type\": \"ring\", \"radius\": 400, \"falloff\": 200, \"strength\": 0.8}}");
        matchesOn("terrain_spires_void", "overworld", "{\"base\": \"spires\", \"floor\": \"void\", \"params\": {\"coverage\": 0.06}}");
        matchesOn("terrain_spires_sea", "overworld", "{\"base\": \"spires\", \"floor\": \"sea\", \"params\": {\"coverage\": 0.06}}");
        matchesOn("terrain_spires_islands", "floating_islands", "{\"base\": \"spires\", \"floor\": \"islands\", \"params\": {\"landHeight\": 120}}");
        matchesBody("terrain_floating_places", "\"generator\": {\"type\": \"noise\", \"preset\": \"floating_islands\"}, \"terrain\": "
                + "{\"base\": \"floating_islands\"}, \"biomes\": [\"minecraft:meadow\", \"minecraft:cherry_grove\", \"minecraft:lush_caves\"], "
                + "\"biomeLayout\": {\"mode\": \"regions\", \"over\": \"land\", \"roles\": {\"minecraft:lush_caves\": \"underside\"}}");
        matchesBody("terrain_basin_places", "\"generator\": {\"type\": \"noise\", \"preset\": \"floating_islands\"}, \"terrain\": "
                + "{\"base\": \"basin_islands\"}, \"liquids\": {\"basins\": {}}, \"biomes\": [\"minecraft:jungle\", \"minecraft:meadow\", "
                + "\"minecraft:warm_ocean\"], \"biomeLayout\": {\"mode\": \"regions\", \"over\": \"land\"}");
        matchesBody("end_regions_land", "\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}, \"biomes\": [\"minecraft:end_highlands\", "
                + "\"minecraft:end_midlands\", \"minecraft:end_barrens\"], \"biomeLayout\": {\"mode\": \"regions\", \"over\": \"land\"}");
        matchesBody("end_default_block", "\"generator\": {\"type\": \"noise\", \"preset\": \"end\"}, \"materials\": {\"defaultBlock\": "
                + "\"minecraft:netherrack\"}");
    }

    /**
     * Land shares on a land terrain with a layout and on the overworld preset's own terrain; a sea under the land
     * with biome regions; a void placing its biomes; checkerboard void cells; a day length.
     */
    @Test
    void landShareVoidsAndDayLengthMatchTheCommittedOutput() throws IOException {
        matchesBody("terrain_continents_land_share", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, \"terrain\": "
                + "{\"base\": \"continents\", \"landShare\": 0.8, \"layout\": {\"type\": \"radial\", \"radius\": 200, \"falloff\": 300, "
                + "\"strength\": 0.4}}, \"borders\": {\"player\": 512}");
        matchesBody("terrain_overworld_land_share", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, \"terrain\": "
                + "{\"landShare\": 0.25}, \"borders\": {\"player\": 512}");
        matchesBody("regions_dry_sea", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, \"liquids\": {\"seaLevel\": -55}, "
                + "\"biomes\": [\"minecraft:desert\", \"minecraft:savanna\", \"minecraft:badlands\"], \"biomeLayout\": {\"mode\": \"regions\"}");
        matchesBody("void_biomes", "\"generator\": {\"type\": \"void\"}, \"biomes\": [{\"id\": \"minecraft:snowy_plains\", \"climate\": "
                + "{\"weirdness\": [-2.0, 0.0]}}, {\"id\": \"minecraft:ice_spikes\", \"climate\": {\"weirdness\": [0.0, 2.0]}}]");
        matchesBody("checkerboard_void_cells", "\"generator\": {\"type\": \"checkerboard\", \"cellScale\": 2, \"voidBiomes\": "
                + "[\"minecraft:the_end\"]}, \"biomes\": [\"minecraft:plains\", \"minecraft:the_end\", \"minecraft:desert\"]");
        matchesBody("day_length", "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, \"atmosphere\": {\"dayLength\": "
                + "{\"dayMinutes\": 45, \"nightMinutes\": 15}}");
    }

    /** Every terrain gallery fixture the self-test opens compiles, and each family has its three settings there. */
    @Test
    void everyGalleryFixtureCompiles() throws IOException {
        Path dir = Path.of("../server/src/selftest/gallery");
        Set<String> slugs = new TreeSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                String slug = p.getFileName().toString().replace(".json", "");
                DatapackCompiler.compileOne(Fixtures.NAMESPACE, slug,
                        com.customdimensions.core.definition.DefinitionReader.read(Files.readString(p), p.getFileName().toString()),
                        Fixtures.vanilla(), VersionTable.V26_3);
                slugs.add(slug);
            }
        }
        for (TerrainFamily family : TerrainFamily.values()) {
            for (String setting : TerrainFamily.SETTINGS) {
                assertEquals(true, slugs.contains("terrain_" + family.id() + "_" + setting), family.id() + " " + setting);
            }
        }
    }

    private static void matches(String slug, String terrain) throws IOException {
        matchesOn(slug, "overworld", terrain);
    }

    private static void matchesOn(String slug, String preset, String terrain) throws IOException {
        matchesBody(slug, "\"generator\": {\"type\": \"noise\", \"preset\": \"" + preset + "\"}, \"terrain\": " + terrain);
    }

    /** The definition with JSON body {@code body} against {@code golden/26.3/<slug>/}. */
    static void matchesBody(String slug, String body) throws IOException {
        DatapackCompiler.Output out = Fixtures.compile(slug, body);
        Set<String> emitted = new TreeSet<>(out.report().entry(slug).emitted());
        Path dir = Path.of(System.getProperty("customdimensions.golden")).resolve("26.3").resolve(slug);
        if (Boolean.getBoolean("customdimensions.updateGolden")) {
            if (Files.exists(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
                }
            }
            for (String path : emitted) {
                Path target = dir.resolve(path);
                Files.createDirectories(target.getParent());
                Files.writeString(target, GoldenForm.of(path, out.files().get(path), out.compiled().get(slug), Fixtures.vanilla()));
            }
        }
        Set<String> golden = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(p -> golden.add(dir.relativize(p).toString().replace('\\', '/')));
        }
        assertEquals(golden, emitted, "files emitted for " + slug);
        for (String path : emitted) {
            assertEquals(Files.readString(dir.resolve(path)),
                    GoldenForm.of(path, out.files().get(path), out.compiled().get(slug), Fixtures.vanilla()), path);
        }
    }
}
