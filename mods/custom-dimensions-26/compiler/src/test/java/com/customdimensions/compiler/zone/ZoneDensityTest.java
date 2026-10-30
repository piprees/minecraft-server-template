package com.customdimensions.compiler.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.zone.ZoneMask;
import com.customdimensions.core.zone.ZoneShape;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ZoneDensityTest {
    /** Vanilla 26.3's density-function types, from the server's registry report. */
    private static final Set<String> VANILLA_TYPES = Set.of("abs", "add", "beardifier", "blend_alpha", "blend_density",
            "blend_offset", "cache", "ceil", "clamp", "constant", "cube", "distance_to_point", "div", "end_outer_islands",
            "find_top_surface", "floor", "gradient", "half_negative", "interpolated", "interval_select", "lerp", "log", "max",
            "min", "mul", "negate", "noise", "old_blended_noise", "pow", "quarter_negative", "range_choice", "reciprocal",
            "round", "shift", "shift_a", "shift_b", "sign", "slice", "spline", "sqrt", "square", "squeeze", "sub", "truncate");

    @Test
    void ringJsonAgreesWithTheReferenceMask() {
        ZoneMask zone = new ZoneMask(37, -90, new ZoneShape.Ring(600, 1200), 120);
        assertAgrees(zone, ZoneDensity.mask(zone), 3000);
    }

    @Test
    void circleAndHolelessRingAgreeToo() {
        ZoneMask circle = new ZoneMask(-5000, 2000, new ZoneShape.Circle(800), 64);
        assertAgrees(circle, ZoneDensity.mask(circle), 2000);
        ZoneMask solid = new ZoneMask(0, 0, new ZoneShape.Ring(0, 300), 50);
        assertAgrees(solid, ZoneDensity.mask(solid), 600);
    }

    @Test
    void blendIsALerpBetweenBaseAndZone() {
        ZoneMask zone = new ZoneMask(0, 0, new ZoneShape.Ring(600, 1200), 120);
        JsonObject blend = ZoneDensity.blend(zone, new JsonPrimitive("minecraft:overworld/final_density"),
                new JsonPrimitive("minecraft:overworld_amplified/final_density"));
        assertEquals("minecraft:lerp", blend.get("type").getAsString());
        assertEquals("minecraft:overworld/final_density", blend.get("first").getAsString());
        assertEquals("minecraft:overworld_amplified/final_density", blend.get("second").getAsString());
        assertAgrees(zone, blend.getAsJsonObject("alpha"), 2000);
    }

    @Test
    void usesOnlyVanillaFunctionTypes() {
        Set<String> used = new HashSet<>();
        collectTypes(ZoneDensity.mask(new ZoneMask(0, 0, new ZoneShape.Ring(600, 1200), 120)), used);
        assertTrue(VANILLA_TYPES.containsAll(used), "non-vanilla types: " + used);
    }

    @Test
    void refusesZonesBeyondExactReach() {
        assertThrows(IllegalArgumentException.class,
                () -> ZoneDensity.mask(new ZoneMask(0, 0, new ZoneShape.Circle(ZoneDensity.REACH), 10)));
    }

    private static void assertAgrees(ZoneMask zone, JsonObject json, int radius) {
        Random random = new Random(7);
        for (int i = 0; i < 2000; i++) {
            int x = (int) zone.centreX() + random.nextInt(2 * radius + 1) - radius;
            int z = (int) zone.centreZ() + random.nextInt(2 * radius + 1) - radius;
            assertEquals(zone.at(x, z), evaluate(json, x, z), 2e-3, "at " + x + "," + z);
        }
    }

    private static void collectTypes(JsonElement element, Set<String> into) {
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        into.add(object.get("type").getAsString().replace("minecraft:", ""));
        object.entrySet().forEach(e -> collectTypes(e.getValue(), into));
    }

    /** Evaluates the subset of density functions the zone compiler emits, in single precision as 26.3 does. */
    private static float evaluate(JsonElement element, int x, int z) {
        if (element.isJsonPrimitive()) return element.getAsFloat();
        JsonObject f = element.getAsJsonObject();
        return switch (f.get("type").getAsString()) {
            case "minecraft:gradient" -> {
                int coordinate = f.get("axis").getAsString().equals("x") ? x : z;
                int from = f.get("from_coordinate").getAsInt(), to = f.get("to_coordinate").getAsInt();
                float t = Math.max(0f, Math.min(1f, (float) (coordinate - from) / (float) (to - from)));
                float a = f.get("from_value").getAsFloat(), b = f.get("to_value").getAsFloat();
                yield a + t * (b - a);
            }
            case "minecraft:add" -> evaluate(f.get("left"), x, z) + evaluate(f.get("right"), x, z);
            case "minecraft:mul" -> evaluate(f.get("left"), x, z) * evaluate(f.get("right"), x, z);
            case "minecraft:square" -> {
                float v = evaluate(f.get("input"), x, z);
                yield v * v;
            }
            case "minecraft:sqrt" -> (float) Math.sqrt(evaluate(f.get("input"), x, z));
            case "minecraft:max" -> Math.max(evaluate(f.get("left"), x, z), evaluate(f.get("right"), x, z));
            case "minecraft:abs" -> Math.abs(evaluate(f.get("input"), x, z));
            case "minecraft:negate" -> -evaluate(f.get("input"), x, z);
            case "minecraft:clamp" -> Math.max(f.get("min").getAsFloat(),
                    Math.min(f.get("max").getAsFloat(), evaluate(f.get("input"), x, z)));
            default -> throw new IllegalArgumentException("unexpected " + f.get("type"));
        };
    }
}
