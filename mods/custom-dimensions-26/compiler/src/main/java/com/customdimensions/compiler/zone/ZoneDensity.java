package com.customdimensions.compiler.zone;

import com.customdimensions.core.zone.ZoneMask;
import com.customdimensions.core.zone.ZoneShape;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Writes a {@link ZoneMask} as 26.3 density-function JSON built only from
 * vanilla function types, and blends a zone's function into a base one with it.
 *
 * <p>Horizontal distance is {@code sqrt(gx^2 + gz^2)}, where each {@code g} is a
 * {@code gradient} equal to the coordinate minus the centre. Density functions
 * evaluate in single precision, so the gradient spans {@link #REACH} blocks
 * either side of the centre (about 0.1 block of error at the edge of that span)
 * and clamps beyond it, where every mask is already zero.
 */
public final class ZoneDensity {
    /** Blocks either side of a zone's centre over which distance stays exact. */
    public static final int REACH = 1_000_000;

    private ZoneDensity() {}

    /** The mask as JSON: 1 inside the zone, 0 outside, ramping across the blend. */
    public static JsonObject mask(ZoneMask zone) {
        long centreX = Math.round(zone.centreX());
        long centreZ = Math.round(zone.centreZ());
        JsonElement distance = unary("sqrt", binary("add",
                unary("square", gradient("x", centreX)),
                unary("square", gradient("z", centreZ))));
        return switch (zone.shape()) {
            case ZoneShape.Circle circle -> {
                requireWithinReach(circle.radius() + zone.blend());
                yield fallOff(distance, circle.radius(), zone.blend());
            }
            case ZoneShape.Ring ring -> {
                requireWithinReach(ring.outer() + zone.blend());
                JsonObject outer = fallOff(distance, ring.outer(), zone.blend());
                yield ring.inner() == 0 ? outer
                        : binary("mul", riseIn(distance, ring.inner(), zone.blend()), outer);
            }
        };
    }

    /** {@code base} outside the zone, {@code inZone} inside, blended by the mask. */
    public static JsonObject blend(ZoneMask zone, JsonElement base, JsonElement inZone) {
        JsonObject lerp = type("lerp");
        lerp.add("alpha", mask(zone));
        lerp.add("first", base);
        lerp.add("second", inZone);
        return lerp;
    }

    private static JsonObject fallOff(JsonElement distance, double edge, double blend) {
        return ramp(distance, -(edge + blend), -1 / blend);
    }

    private static JsonObject riseIn(JsonElement distance, double edge, double blend) {
        return ramp(distance, -(edge - blend), 1 / blend);
    }

    /** clamp((distance + offset) * scale, 0, 1). */
    private static JsonObject ramp(JsonElement distance, double offset, double scale) {
        JsonObject clamp = type("clamp");
        clamp.add("input", binary("mul", binary("add", distance, new JsonPrimitive(offset)), new JsonPrimitive(scale)));
        clamp.addProperty("min", 0.0);
        clamp.addProperty("max", 1.0);
        return clamp;
    }

    private static JsonObject gradient(String axis, long centre) {
        JsonObject gradient = type("gradient");
        gradient.addProperty("axis", axis);
        gradient.addProperty("tiling", "clamp_to_edge");
        gradient.addProperty("from_coordinate", centre - REACH);
        gradient.addProperty("to_coordinate", centre + REACH);
        gradient.addProperty("from_value", (double) -REACH);
        gradient.addProperty("to_value", (double) REACH);
        return gradient;
    }

    private static JsonObject unary(String name, JsonElement input) {
        JsonObject function = type(name);
        function.add("input", input);
        return function;
    }

    private static JsonObject binary(String name, JsonElement left, JsonElement right) {
        JsonObject function = type(name);
        function.add("left", left);
        function.add("right", right);
        return function;
    }

    private static JsonObject type(String name) {
        JsonObject function = new JsonObject();
        function.addProperty("type", "minecraft:" + name);
        return function;
    }

    private static void requireWithinReach(double extent) {
        if (extent >= REACH) {
            throw new IllegalArgumentException("zone reaches " + extent + " blocks from its centre; the limit is " + REACH);
        }
    }
}
