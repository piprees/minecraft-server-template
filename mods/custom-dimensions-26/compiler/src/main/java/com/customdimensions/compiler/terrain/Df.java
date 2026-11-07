package com.customdimensions.compiler.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Builders for 26.3 density-function JSON, using vanilla function types only.
 * A function is a {@link JsonElement}: a number, an id, or an object.
 */
final class Df {

    private Df() {
    }

    /** A constant, rounded to nine significant digits so arithmetic on parameters writes as people would. */
    static JsonElement c(double value) {
        return new JsonPrimitive(new BigDecimal(value).round(new MathContext(9)).doubleValue());
    }

    static JsonElement ref(String id) {
        return new JsonPrimitive(id);
    }

    static JsonObject type(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "minecraft:" + name);
        return o;
    }

    static JsonObject unary(String name, JsonElement input) {
        JsonObject o = type(name);
        o.add("input", input);
        return o;
    }

    static JsonObject binary(String name, JsonElement left, JsonElement right) {
        JsonObject o = type(name);
        o.add("left", left);
        o.add("right", right);
        return o;
    }

    static JsonObject add(JsonElement a, JsonElement b) {
        return binary("add", a, b);
    }

    static JsonObject add(JsonElement a, double b) {
        return binary("add", a, c(b));
    }

    static JsonObject sub(JsonElement a, JsonElement b) {
        return binary("sub", a, b);
    }

    static JsonObject mul(JsonElement a, JsonElement b) {
        return binary("mul", a, b);
    }

    static JsonObject mul(JsonElement a, double b) {
        return binary("mul", a, c(b));
    }

    static JsonObject min(JsonElement a, JsonElement b) {
        return binary("min", a, b);
    }

    static JsonObject max(JsonElement a, JsonElement b) {
        return binary("max", a, b);
    }

    static JsonObject abs(JsonElement input) {
        return unary("abs", input);
    }

    static JsonObject square(JsonElement input) {
        return unary("square", input);
    }

    static JsonObject sqrt(JsonElement input) {
        return unary("sqrt", input);
    }

    static JsonObject floor(JsonElement input) {
        return unary("floor", input);
    }

    static JsonObject cache(JsonElement input) {
        return unary("cache", input);
    }

    static JsonObject clamp(JsonElement input, double min, double max) {
        JsonObject o = unary("clamp", input);
        o.add("min", c(min));
        o.add("max", c(max));
        return o;
    }

    /** {@code clamp((input - from) / (to - from), 0, 1)}: 0 at {@code from}, 1 at {@code to}, either order. */
    static JsonObject ramp(JsonElement input, double from, double to) {
        return clamp(mul(add(input, -from), 1 / (to - from)), 0, 1);
    }

    static JsonObject lerp(JsonElement alpha, JsonElement first, JsonElement second) {
        JsonObject o = type("lerp");
        o.add("alpha", alpha);
        o.add("first", first);
        o.add("second", second);
        return o;
    }

    /** A 2D or 3D noise sample; {@code shifted} warps it by vanilla's {@code shift_x}/{@code shift_z}. */
    static JsonObject noise(String id, double xzScale, double yScale, boolean shifted) {
        JsonObject o = type("noise");
        o.addProperty("noise", id);
        if (shifted) {
            o.addProperty("shift_x", "minecraft:shift_x");
            o.addProperty("shift_z", "minecraft:shift_z");
        }
        o.addProperty("xz_scale", xzScale);
        o.addProperty("y_scale", yScale);
        return o;
    }

    /** {@code -y}, exact across {@code [minY, maxY]} and clamped beyond. */
    static JsonObject negY(int minY, int maxY) {
        JsonObject o = type("gradient");
        o.addProperty("axis", "y");
        o.addProperty("from_coordinate", minY);
        o.addProperty("from_value", (double) -minY);
        o.addProperty("to_coordinate", maxY);
        o.addProperty("to_value", (double) -maxY);
        return o;
    }

    /** A cubic spline over {@code coordinate}; {@code points} is location, value pairs, each with derivative 0. */
    static JsonObject spline(JsonElement coordinate, double... points) {
        JsonArray array = new JsonArray();
        for (int i = 0; i + 1 < points.length; i += 2) {
            if (i > 0 && points[i] <= points[i - 2]) {
                throw new IllegalArgumentException("spline locations must increase: " + points[i - 2] + " then " + points[i]);
            }
            JsonObject p = new JsonObject();
            p.addProperty("derivative", 0.0);
            p.addProperty("location", (float) points[i]);
            p.addProperty("value", (float) points[i + 1]);
            array.add(p);
        }
        JsonObject spline = new JsonObject();
        spline.add("coordinate", coordinate);
        spline.add("points", array);
        JsonObject o = type("spline");
        o.add("spline", spline);
        return o;
    }
}
