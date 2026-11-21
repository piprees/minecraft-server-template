package com.customdimensions.compiler.datapack;

import com.customdimensions.core.definition.Atmosphere;
import com.customdimensions.core.definition.DefinitionReader;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code atmosphere.dayLength}: vanilla's day timelines warped so the day half spans the day minutes and the night half the night. */
class DayLengthTest {

    private static final DayLength BETTER_DAYS = new DayLength(45, 15);
    private static final String CLOCK = "adventure:day_length/54000_18000";

    @Test
    void theWarpStretchesTheDayAndTheNightSeparately() {
        assertEquals(72000, BETTER_DAYS.period());
        assertEquals(0, BETTER_DAYS.warp(0));
        assertEquals(27000, BETTER_DAYS.warp(6000), "noon halfway through 45 minutes");
        assertEquals(54000, BETTER_DAYS.warp(12000), "dusk after 45 minutes");
        assertEquals(63000, BETTER_DAYS.warp(18000), "midnight halfway through the 15-minute night");
        assertEquals(72000 + 27000, BETTER_DAYS.warp(24000 + 6000), "the next day");
    }

    @Test
    void everyTrackKeepsItsValueAtTheWarpedTick() {
        JsonObject vanilla = Fixtures.vanilla().timeline("minecraft:day");
        JsonObject warped = BETTER_DAYS.apply(vanilla, CLOCK);
        assertEquals(CLOCK, warped.get("clock").getAsString());
        assertEquals(72000, warped.get("period_ticks").getAsInt());
        JsonObject markers = warped.getAsJsonObject("time_markers");
        assertEquals(27000, markers.getAsJsonObject("minecraft:noon").get("ticks").getAsInt());
        assertEquals(63000, markers.getAsJsonObject("minecraft:midnight").get("ticks").getAsInt());
        assertEquals(4500, markers.getAsJsonObject("minecraft:day").get("ticks").getAsInt());
        assertEquals(63000, markers.get("minecraft:roll_village_siege").getAsInt());
        for (Map.Entry<String, JsonElement> e : vanilla.getAsJsonObject("tracks").entrySet()) {
            JsonObject before = e.getValue().getAsJsonObject();
            JsonObject after = warped.getAsJsonObject("tracks").getAsJsonObject(e.getKey());
            assertEquals(before.has("modifier"), after.has("modifier"), e.getKey());
            for (int t = 0; t < 24000; t += 125) {
                JsonElement want = FixedTime.sample(before, 24000, t);
                JsonElement got = FixedTime.sample(after, 72000, BETTER_DAYS.warp(t));
                if (want.isJsonPrimitive() && want.getAsJsonPrimitive().isNumber()) {
                    double tolerance = e.getKey().endsWith("_angle") ? 1.0 : 0.02;
                    assertEquals(want.getAsDouble(), got.getAsDouble(), tolerance, e.getKey() + " at " + t);
                } else if (want.isJsonPrimitive() && want.getAsJsonPrimitive().isBoolean()) {
                    assertEquals(want, got, e.getKey() + " at " + t);
                }
            }
        }
        double sunAtNoon = FixedTime.sample(warped.getAsJsonObject("tracks").getAsJsonObject("minecraft:visual/sun_angle"), 72000, 27000).getAsDouble();
        assertEquals(FixedTime.sample(vanilla.getAsJsonObject("tracks").getAsJsonObject("minecraft:visual/sun_angle"), 24000, 6000).getAsDouble(),
                sunAtNoon, 1e-3);
    }

    @Test
    void aTimelineOfSeveralDaysWarpsEachDay() {
        JsonObject moon = BETTER_DAYS.apply(Fixtures.vanilla().timeline("minecraft:moon"), CLOCK);
        assertEquals(8 * 72000, moon.get("period_ticks").getAsInt());
        assertEquals(72000, moon.getAsJsonObject("tracks").getAsJsonObject("minecraft:visual/moon_phase").getAsJsonArray("keyframes")
                .get(1).getAsJsonObject().get("ticks").getAsInt());
        assertFalse(DayLength.applies(Fixtures.vanilla().timeline("minecraft:early_game")));
    }

    private static JsonObject type(DatapackCompiler.Output out, String slug) {
        return JsonParser.parseString(out.files().get("data/adventure/dimension_type/" + slug + ".json")).getAsJsonObject();
    }

    @Test
    void aDimensionTakesItsOwnLengthOrTheDefaultAndAFixedTimeKeepsItsOwn() {
        String overworld = "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}";
        DatapackCompiler.Output own = Fixtures.compile("d", overworld + ", \"atmosphere\": {\"dayLength\": {\"dayMinutes\": 45, \"nightMinutes\": 15}}");
        String timelines = type(own, "d").get("timelines").toString();
        assertTrue(timelines.contains("adventure:day_length/day_54000_18000") && timelines.contains("adventure:day_length/moon_54000_18000")
                && timelines.contains("adventure:day_length/villager_schedule_54000_18000") && timelines.contains("minecraft:early_game"), timelines);
        assertTrue(own.files().containsKey("data/adventure/timeline/day_length/day_54000_18000.json"));
        assertTrue(own.reportText().contains("Day length 45 min day, 15 min night"), own.reportText());
        assertEquals(CLOCK, type(own, "d").get("default_clock").getAsString());
        assertEquals("{}", own.files().get("data/adventure/world_clock/day_length/54000_18000.json").strip());

        Atmosphere.DayLength fallback = new Atmosphere.DayLength(30, 10);
        Map<String, com.customdimensions.core.definition.Definition> defs = Map.of(
                "plain", Fixtures.definition(overworld),
                "frozen", Fixtures.definition(overworld + ", \"atmosphere\": {\"fixedTime\": 6000}"),
                "nether", Fixtures.definition("\"generator\": {\"type\": \"noise\", \"preset\": \"nether\"}"));
        DatapackCompiler.Output out = DatapackCompiler.compileAll("adventure", defs, Fixtures.vanilla(), VersionTable.V26_3, fallback);
        assertTrue(type(out, "plain").get("timelines").toString().contains("adventure:day_length/day_36000_12000"));
        assertFalse(type(out, "frozen").get("timelines").toString().contains("day_length"));
        assertFalse(type(out, "nether").toString().contains("day_length"), "a dimension without vanilla's day keeps its timelines");

        DatapackCompiler.Output none = Fixtures.compile("d", overworld);
        assertFalse(none.files().keySet().stream().anyMatch(p -> p.contains("day_length")));
    }

    /** The game refuses a pack in which two timelines on one clock define the same time marker. */
    @Test
    void noClockCarriesATimeMarkerTwice() {
        String overworld = "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}";
        DatapackCompiler.Output out = DatapackCompiler.compileAll("adventure", Map.of(
                "a", Fixtures.definition(overworld + ", \"atmosphere\": {\"dayLength\": {\"dayMinutes\": 45, \"nightMinutes\": 15}}"),
                "b", Fixtures.definition(overworld + ", \"atmosphere\": {\"dayLength\": {\"dayMinutes\": 30, \"nightMinutes\": 10}}"),
                "c", Fixtures.definition(overworld + ", \"atmosphere\": {\"fixedTime\": 6000}")), Fixtures.vanilla(), VersionTable.V26_3);
        Map<String, JsonObject> timelines = new java.util.TreeMap<>();
        for (String id : java.util.List.of("minecraft:day", "minecraft:moon", "minecraft:villager_schedule", "minecraft:early_game")) {
            timelines.put(id, Fixtures.vanilla().timeline(id));
        }
        out.files().forEach((path, text) -> {
            if (path.contains("/timeline/")) timelines.put(path, JsonParser.parseString(text).getAsJsonObject());
        });
        java.util.Set<String> seen = new java.util.HashSet<>();
        timelines.forEach((id, t) -> {
            if (!t.has("time_markers")) return;
            for (String marker : t.getAsJsonObject("time_markers").keySet()) {
                assertTrue(seen.add(t.get("clock").getAsString() + " " + marker), id + " repeats " + marker + " on " + t.get("clock"));
            }
        });
        assertTrue(seen.contains("adventure:day_length/54000_18000 minecraft:wake_up_from_sleep"), seen.toString());
    }

    @Test
    void theReaderRefusesAFixedTimeWithADayLengthAndOutOfRangeMinutes() {
        String head = "{\"schemaVersion\": 2, \"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, \"atmosphere\": ";
        assertThrows(RuntimeException.class, () -> DefinitionReader.read(head
                + "{\"fixedTime\": 6000, \"dayLength\": {\"dayMinutes\": 45, \"nightMinutes\": 15}}}", "x"));
        assertThrows(RuntimeException.class, () -> DefinitionReader.read(head + "{\"dayLength\": {\"dayMinutes\": 0, \"nightMinutes\": 15}}}", "x"));
        assertThrows(RuntimeException.class, () -> DefinitionReader.read(head + "{\"dayLength\": {\"dayMinutes\": 45}}}", "x"));
        assertEquals(new Atmosphere.DayLength(45, 15), DefinitionReader.read(head
                + "{\"dayLength\": {\"dayMinutes\": 45, \"nightMinutes\": 15}}}", "x").atmosphere().dayLength());
    }
}
