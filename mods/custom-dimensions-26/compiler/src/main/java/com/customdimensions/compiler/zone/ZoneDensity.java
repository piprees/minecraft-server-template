package com.customdimensions.compiler.zone;

import com.customdimensions.core.zone.ZoneMask;
import com.customdimensions.core.zone.ZoneShape;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Writes a {@link ZoneMask} as 26.3 density-function JSON, and blends a zone's
 * function into a base one with it. The mask is
 * {@code clamp(1 - (outside + jitter * noise) / blend, 0, 1)}, where
 * {@code outside} is the shape's distance beyond its edge
 * ({@link ZoneShape#outside}).
 *
 * <p>Circles, rings, boxes and the whole dimension are vanilla functions:
 * horizontal distance is {@code sqrt(gx^2 + gz^2)}, where each {@code g} is a
 * {@code gradient} equal to the coordinate minus the centre. Density functions
 * evaluate in single precision, so the gradient spans {@link #REACH} blocks
 * either side of the centre (about 0.1 block of error at the edge of that span)
 * and clamps beyond it, where every mask is already zero. Sectors and polygons,
 * which vanilla functions cannot draw, use {@link #DISTANCE_TYPE}, which
 * evaluates {@link ZoneShape#outside} itself.
 */
public final class ZoneDensity {
    /** Blocks either side of a zone's centre over which distance stays exact. */
    public static final int REACH = 1_000_000;
    /** Our density-function type: a sector's or polygon's distance beyond its edge. */
    public static final String DISTANCE_TYPE = "customdimensions:zone_distance";
    /** Standard deviation of the jitter noise's value, so {@code edgeJitter} is two of them in blocks. */
    public static final double JITTER_SIGMA = 0.25;

    private ZoneDensity() {}

    /** The mask with a still edge: 1 inside the zone, 0 outside, ramping across the blend. */
    public static JsonObject mask(ZoneMask zone) {
        return mask(zone, null);
    }

    /**
     * The mask, its edge moved by {@code jitterNoise} (a noise id, sampled in 2D) when both it and the zone's
     * jitter are set: two standard deviations of the noise are {@code edgeJitter} blocks.
     */
    public static JsonObject mask(ZoneMask zone, String jitterNoise) {
        requireWithinReach(zone.shape().extent() + zone.rampWidth() + zone.jitter());
        JsonElement outside = outside(zone);
        if (jitterNoise != null && zone.jitter() > 0) {
            JsonObject noise = type("noise");
            noise.addProperty("noise", jitterNoise);
            noise.addProperty("xz_scale", 1.0);
            noise.addProperty("y_scale", 0.0);
            outside = binary("add", outside, binary("mul", noise, new JsonPrimitive(zone.jitter() / (2 * JITTER_SIGMA))));
        }
        JsonObject clamp = type("clamp");
        clamp.add("input", binary("add", new JsonPrimitive(1.0),
                binary("mul", outside, new JsonPrimitive(-1 / zone.rampWidth()))));
        clamp.addProperty("min", 0.0);
        clamp.addProperty("max", 1.0);
        return clamp;
    }

    /** The shape's distance beyond its edge, in blocks. */
    static JsonElement outside(ZoneMask zone) {
        long cx = Math.round(zone.centreX());
        long cz = Math.round(zone.centreZ());
        return switch (zone.shape()) {
            case ZoneShape.Circle circle -> binary("add", distance(cx, cz), new JsonPrimitive(-circle.radius()));
            case ZoneShape.Ring ring -> {
                JsonObject beyond = binary("add", distance(cx, cz), new JsonPrimitive(-ring.outer()));
                yield ring.inner() == 0 ? beyond : binary("max",
                        binary("add", unary("negate", distance(cx, cz)), new JsonPrimitive(ring.inner())), beyond);
            }
            case ZoneShape.Box box -> binary("max",
                    binary("add", unary("abs", gradient("x", cx)), new JsonPrimitive(-box.halfX())),
                    binary("add", unary("abs", gradient("z", cz)), new JsonPrimitive(-box.halfZ())));
            case ZoneShape.All all -> new JsonPrimitive(-1.0);
            case ZoneShape.Sector sector -> distanceType(zone, cx, cz);
            case ZoneShape.Polygon polygon -> distanceType(zone, cx, cz);
        };
    }

    /** {@code base} outside the zone, {@code inZone} inside, blended by {@code mask} (a mask's JSON or id). */
    public static JsonObject blend(JsonElement mask, JsonElement base, JsonElement inZone) {
        JsonObject lerp = type("lerp");
        lerp.add("alpha", mask);
        lerp.add("first", base);
        lerp.add("second", inZone);
        return lerp;
    }

    public static JsonObject blend(ZoneMask zone, JsonElement base, JsonElement inZone) {
        return blend(mask(zone), base, inZone);
    }

    /** {@link #DISTANCE_TYPE} for a sector or polygon about a centre. */
    static JsonObject distanceType(ZoneMask zone, long cx, long cz) {
        JsonObject f = new JsonObject();
        f.addProperty("type", DISTANCE_TYPE);
        JsonArray centre = new JsonArray();
        centre.add(cx);
        centre.add(cz);
        f.add("centre", centre);
        switch (zone.shape()) {
            case ZoneShape.Sector s -> {
                f.addProperty("shape", "sector");
                f.addProperty("inner", s.inner());
                f.addProperty("outer", s.outer());
                JsonArray angles = new JsonArray();
                angles.add(s.from());
                angles.add(s.to());
                f.add("angles", angles);
            }
            case ZoneShape.Polygon p -> {
                f.addProperty("shape", "polygon");
                JsonArray points = new JsonArray();
                for (double[] xz : p.points()) {
                    JsonArray point = new JsonArray();
                    point.add(xz[0]);
                    point.add(xz[1]);
                    points.add(point);
                }
                f.add("points", points);
            }
            default -> throw new IllegalArgumentException("vanilla functions draw " + zone.shape());
        }
        return f;
    }

    private static JsonObject distance(long cx, long cz) {
        return unary("sqrt", binary("add", unary("square", gradient("x", cx)), unary("square", gradient("z", cz))));
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
