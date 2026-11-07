package com.customdimensions.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class CloneSettingsTest {
    private static final String PALETTE = """
            {"type":"minecraft:condition","if_true":{"type":"minecraft:biome","biome_is":["adventure:ice_starfield"]},
             "then_run":{"type":"minecraft:block","result_state":{"Name":"minecraft:snow_block"}}}""";
    private static final String SOURCE = """
            {"sea_level":-64,"noise_router":{"temperature":"minecraft:t","final_density":"minecraft:f"},
             "material_rule":{"type":"minecraft:sequence","sequence":["minecraft:overworld/copper_ore_vein",
               "minecraft:overworld/surface","minecraft:overworld/underground"]}}""";
    private static final String CLONE = """
            {"sea_level":-64,"noise_router":{"temperature":"adventure:zones/x/temperature","final_density":"minecraft:f"},
             "material_rule":{"type":"minecraft:sequence","sequence":["minecraft:overworld/copper_ore_vein",
               %s,"minecraft:overworld/surface","minecraft:overworld/underground"]}}""".formatted(PALETTE);

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static List<JsonElement> zoneRules() {
        return List.of(json(PALETTE));
    }

    @Test
    void theZonesPaletteAndTemperatureAreTheOnlyDifferences() {
        assertEquals(List.of(), CloneSettings.differences(json(CLONE), json(SOURCE), zoneRules(), List.of()));
    }

    @Test
    void aPaletteNoZoneBringsDiffers() {
        assertEquals(List.of("material_rule"), CloneSettings.differences(json(CLONE), json(SOURCE), List.of(), List.of()));
    }

    @Test
    void aMissingZonePaletteDiffers() {
        assertEquals(List.of("material_rule (no entry for [\"adventure:ice_starfield\"])"),
                CloneSettings.differences(json(SOURCE), json(SOURCE), zoneRules(), List.of()));
    }

    @Test
    void aZonePaletteWithOtherBlocksDiffers() {
        String other = CLONE.replace("minecraft:snow_block", "minecraft:packed_ice");
        assertEquals(List.of("material_rule (no entry for [\"adventure:ice_starfield\"])", "material_rule"),
                CloneSettings.differences(json(other), json(SOURCE), zoneRules(), List.of()));
    }

    @Test
    void anyOtherRuleEntryDiffers() {
        String dropped = CLONE.replace(",\"minecraft:overworld/underground\"", "");
        assertEquals(List.of("material_rule"), CloneSettings.differences(json(dropped), json(SOURCE), zoneRules(), List.of()));
        String reordered = CLONE.replace("\"minecraft:overworld/surface\",\"minecraft:overworld/underground\"",
                "\"minecraft:overworld/underground\",\"minecraft:overworld/surface\"");
        assertEquals(List.of("material_rule"), CloneSettings.differences(json(reordered), json(SOURCE), zoneRules(), List.of()));
    }

    @Test
    void anyOtherRouterFieldOrSettingDiffers() {
        String router = CLONE.replace("\"final_density\":\"minecraft:f\"", "\"final_density\":\"minecraft:g\"");
        assertEquals(List.of("noise_router.final_density"), CloneSettings.differences(json(router), json(SOURCE), zoneRules(), List.of()));
        String sea = CLONE.replace("\"sea_level\":-64", "\"sea_level\":63");
        assertEquals(List.of("sea_level"), CloneSettings.differences(json(sea), json(SOURCE), zoneRules(), List.of()));
    }

    @Test
    void aSourceThatIsNotASequenceComparesAsOneEntry() {
        String block = "{\"type\":\"minecraft:block\",\"result_state\":{\"Name\":\"minecraft:end_stone\"}}";
        JsonObject source = json("{\"material_rule\":" + block + "}");
        JsonObject clone = json("{\"material_rule\":{\"type\":\"minecraft:sequence\",\"sequence\":[" + PALETTE + "," + block + "]}}");
        assertEquals(List.of(), CloneSettings.differences(clone, source, zoneRules(), List.of()));
    }

    @Test
    void eachSidesListedPalettesCancel() {
        String other = PALETTE.replace("ice_starfield", "sky_highlands").replace("snow_block", "calcite");
        String source = SOURCE.replace("\"minecraft:overworld/surface\"", other + ",\"minecraft:overworld/surface\"");
        assertEquals(List.of(), CloneSettings.differences(json(CLONE), json(source), zoneRules(), List.of(json(other))));
        String both = CLONE.replace(PALETTE + ",", PALETTE + "," + other + ",");
        assertEquals(List.of(), CloneSettings.differences(json(both), json(source), List.of(json(PALETTE), json(other)),
                List.of(json(other))));
        assertEquals(List.of("material_rule"), CloneSettings.differences(json(both), json(source), zoneRules(), List.of(json(other))));
    }

    @Test
    void aSourcePaletteMissingFromTheSourceDiffers() {
        String other = PALETTE.replace("ice_starfield", "sky_highlands");
        assertEquals(List.of("the source's material_rule (no entry for [\"adventure:sky_highlands\"])"),
                CloneSettings.differences(json(CLONE), json(SOURCE), zoneRules(), List.of(json(other))));
    }
}
