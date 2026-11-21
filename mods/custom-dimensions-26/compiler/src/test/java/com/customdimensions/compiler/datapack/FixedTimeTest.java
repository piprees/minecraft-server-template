package com.customdimensions.compiler.datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedTimeTest {

    private static JsonObject track(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void singleKeyframeIsConstant() {
        JsonObject t = track("{\"keyframes\": [{\"ticks\": 100, \"value\": 0.5}]}");
        assertEquals(0.5, FixedTime.sample(t, 24000, 9999).getAsDouble());
    }

    @Test
    void numbersInterpolateLinearlyBetweenKeyframes() {
        JsonObject t = track("{\"keyframes\": [{\"ticks\": 0, \"value\": 0.0}, {\"ticks\": 100, \"value\": 1.0}]}");
        assertEquals(0.25, FixedTime.sample(t, null, 25).getAsDouble(), 1e-6);
        assertEquals(0.0, FixedTime.sample(t, null, -50).getAsDouble());
        assertEquals(1.0, FixedTime.sample(t, null, 500).getAsDouble());
    }

    @Test
    void periodicTracksWrapFromTheLastKeyframeToTheFirst() {
        JsonObject t = track("{\"keyframes\": [{\"ticks\": 100, \"value\": 0.0}, {\"ticks\": 200, \"value\": 1.0}]}");
        // Period 400: from 200 (1.0) back to 500 (0.0); 350 is halfway.
        assertEquals(0.5, FixedTime.sample(t, 400, 350).getAsDouble(), 1e-6);
        assertEquals(0.5, FixedTime.sample(t, 400, 350 + 4 * 400).getAsDouble(), 1e-6);
        // 50 lies on the wrapped segment from -200 (1.0) to 100 (0.0).
        assertEquals(1.0f / 6.0f, FixedTime.sample(t, 400, 50).getAsFloat(), 1e-6);
    }

    @Test
    void constantEaseHoldsTheEarlierValue() {
        JsonObject t = track("{\"ease\": \"constant\", \"keyframes\": [{\"ticks\": 0, \"value\": 3.0}, {\"ticks\": 10, \"value\": 7.0}]}");
        assertEquals(3.0, FixedTime.sample(t, null, 9).getAsDouble());
        assertEquals(7.0, FixedTime.sample(t, null, 10).getAsDouble());
    }

    @Test
    void coloursInterpolatePerChannel() {
        JsonElement mid = FixedTime.lerp(0.5f, new JsonPrimitive("#000000"), new JsonPrimitive("#ff8040"));
        assertEquals("#7f4020", mid.getAsString());
        JsonElement argb = FixedTime.lerp(0.5f, new JsonPrimitive("#ffffffff"), new JsonPrimitive("#ff191926"));
        assertEquals("#ff8c8c92", argb.getAsString());
    }

    @Test
    void otherValuesStep() {
        assertEquals(new JsonPrimitive(true), FixedTime.lerp(0.9f, new JsonPrimitive(true), new JsonPrimitive(false)));
        assertEquals(new JsonPrimitive("new_moon"), FixedTime.lerp(1f, new JsonPrimitive("full_moon"), new JsonPrimitive("new_moon")));
    }

    @Test
    void symmetricBezierPassesThroughItsMidpoint() {
        assertEquals(0.5f, FixedTime.cubicBezier(0.362f, 0.241f, 0.638f, 0.759f, 0.5f), 1e-4);
        assertEquals(0f, FixedTime.cubicBezier(0.362f, 0.241f, 0.638f, 0.759f, 0f), 1e-4);
        assertEquals(1f, FixedTime.cubicBezier(0.362f, 0.241f, 0.638f, 0.759f, 1f), 1e-4);
        float early = FixedTime.cubicBezier(0.362f, 0.241f, 0.638f, 0.759f, 0.25f);
        assertTrue(early > 0f && early < 0.25f, "an ease-in-out curve starts slower than linear: " + early);
    }

    @Test
    void unknownEaseIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> FixedTime.ease(new JsonPrimitive("in_out_wobble"), 0.5f));
    }

    @Test
    void vanillaDayAtMidnightIsNight() {
        JsonObject day = Fixtures.vanilla().timeline("minecraft:day");
        assertTrue(FixedTime.isPeriodic(day));
        JsonObject frozen = FixedTime.freeze(day, 18000);
        JsonObject tracks = frozen.getAsJsonObject("tracks");
        assertEquals(180f, value(tracks, "minecraft:visual/sun_angle").getAsFloat(), 1e-3);
        assertEquals(0.26666668f, value(tracks, "minecraft:gameplay/sky_light_level").getAsFloat(), 1e-6);
        assertEquals("#000000", value(tracks, "minecraft:visual/sky_color").getAsString());
        assertFalse(value(tracks, "minecraft:gameplay/monsters_burn").getAsBoolean());
        assertEquals("multiply", tracks.getAsJsonObject("minecraft:visual/sky_color").get("modifier").getAsString());
        assertEquals(day.get("clock"), frozen.get("clock"));
        assertFalse(frozen.has("period_ticks"));
    }

    @Test
    void vanillaDayAtNoonIsDay() {
        JsonObject tracks = FixedTime.freeze(Fixtures.vanilla().timeline("minecraft:day"), 6000).getAsJsonObject("tracks");
        assertEquals(1f, value(tracks, "minecraft:gameplay/sky_light_level").getAsFloat());
        assertTrue(value(tracks, "minecraft:gameplay/monsters_burn").getAsBoolean());
        assertEquals(0f, value(tracks, "minecraft:visual/star_brightness").getAsFloat());
    }

    private static JsonElement value(JsonObject tracks, String id) {
        return tracks.getAsJsonObject(id).getAsJsonArray("keyframes").get(0).getAsJsonObject().get("value");
    }
}
