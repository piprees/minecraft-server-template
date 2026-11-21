package com.customdimensions.compiler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A fixed time of day as 26.3 data. Time of day is a set of timelines
 * ({@code minecraft:day}, {@code minecraft:moon}, …) sampled from a world
 * clock; a dimension type with {@code has_fixed_time} and timelines holding each
 * track's value at one tick looks, lights and behaves as that time for good.
 * Sampling follows vanilla's {@code KeyframeTrackSampler}: periodic tracks
 * wrap, the track's ease shapes each segment, numbers and colours interpolate
 * and every other value steps.
 */
public final class FixedTime {

    private FixedTime() {
    }

    /** True when the timeline repeats ({@code period_ticks}), so a time of day applies to it. */
    public static boolean isPeriodic(JsonObject timeline) {
        return timeline.has("period_ticks");
    }

    /** The timeline frozen at {@code tick}: each track holds one keyframe, its modifier kept. */
    public static JsonObject freeze(JsonObject timeline, long tick) {
        Integer period = timeline.has("period_ticks") ? timeline.get("period_ticks").getAsInt() : null;
        JsonObject tracks = new JsonObject();
        for (Map.Entry<String, JsonElement> e : timeline.getAsJsonObject("tracks").entrySet()) {
            JsonObject track = e.getValue().getAsJsonObject();
            JsonObject keyframe = new JsonObject();
            keyframe.addProperty("ticks", 0);
            keyframe.add("value", sample(track, period, tick));
            JsonArray keyframes = new JsonArray();
            keyframes.add(keyframe);
            JsonObject frozen = new JsonObject();
            frozen.add("keyframes", keyframes);
            if (track.has("modifier")) {
                frozen.add("modifier", track.get("modifier").deepCopy());
            }
            tracks.add(e.getKey(), frozen);
        }
        JsonObject out = new JsonObject();
        out.add("clock", timeline.get("clock").deepCopy());
        out.add("tracks", tracks);
        return out;
    }

    /** One track's value at {@code tick}. */
    public static JsonElement sample(JsonObject track, Integer period, long tick) {
        List<Frame> frames = new ArrayList<>();
        for (JsonElement k : track.getAsJsonArray("keyframes")) {
            JsonObject o = k.getAsJsonObject();
            frames.add(new Frame(o.get("ticks").getAsInt(), o.get("value")));
        }
        if (frames.size() == 1) {
            return frames.getFirst().value.deepCopy();
        }
        List<Segment> segments = new ArrayList<>();
        Frame first = frames.getFirst();
        Frame last = frames.getLast();
        if (period != null) {
            segments.add(new Segment(last.ticks - period, last.value, first.ticks, first.value));
        }
        for (int i = 0; i + 1 < frames.size(); i++) {
            segments.add(new Segment(frames.get(i).ticks, frames.get(i).value, frames.get(i + 1).ticks, frames.get(i + 1).value));
        }
        if (period != null) {
            segments.add(new Segment(last.ticks, last.value, first.ticks + period, first.value));
        }
        long t = period != null ? Math.floorMod(tick, (long) period) : tick;
        Segment at = segments.getLast();
        for (Segment s : segments) {
            if (t < s.toTicks) {
                at = s;
                break;
            }
        }
        if (t <= at.fromTicks) {
            return at.from.deepCopy();
        }
        if (t >= at.toTicks) {
            return at.to.deepCopy();
        }
        float fraction = (float) (t - at.fromTicks) / (float) (at.toTicks - at.fromTicks);
        return lerp(ease(track.get("ease"), fraction), at.from, at.to);
    }

    /** Vanilla's easing: absent or {@code linear}, {@code constant}, or {@code {"cubic_bezier": [x1, y1, x2, y2]}}. */
    public static float ease(JsonElement ease, float t) {
        if (ease == null || ease.isJsonNull()) {
            return t;
        }
        if (ease.isJsonPrimitive()) {
            return switch (ease.getAsString()) {
                case "linear" -> t;
                case "constant" -> 0f;
                default -> throw new IllegalArgumentException("unsupported ease " + ease);
            };
        }
        JsonArray c = ease.getAsJsonObject().getAsJsonArray("cubic_bezier");
        if (c == null || c.size() != 4) {
            throw new IllegalArgumentException("unsupported ease " + ease);
        }
        return cubicBezier(c.get(0).getAsFloat(), c.get(1).getAsFloat(), c.get(2).getAsFloat(), c.get(3).getAsFloat(), t);
    }

    static float cubicBezier(float x1, float y1, float x2, float y2, float x) {
        float lo = 0f;
        float hi = 1f;
        float t = x;
        for (int i = 0; i < 8; i++) {
            float error = bezier(x1, x2, t) - x;
            if (Math.abs(error) < 1e-5f) {
                return bezier(y1, y2, t);
            }
            float slope = bezierSlope(x1, x2, t);
            if (Math.abs(slope) < 1e-5f) {
                break;
            }
            t -= Math.max(-0.25f, Math.min(0.25f, error / slope));
        }
        t = x;
        for (int i = 0; i < 64; i++) {
            float value = bezier(x1, x2, t);
            if (Math.abs(value - x) < 1e-5f) {
                break;
            }
            if (value < x) {
                lo = t;
            } else {
                hi = t;
            }
            t = (lo + hi) / 2f;
        }
        return bezier(y1, y2, t);
    }

    private static float bezier(float p1, float p2, float t) {
        float u = 1f - t;
        return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t;
    }

    private static float bezierSlope(float p1, float p2, float t) {
        float u = 1f - t;
        return 3f * u * u * p1 + 6f * u * t * (p2 - p1) + 3f * t * t * (1f - p2);
    }

    /** Numbers interpolate linearly, {@code #rrggbb}/{@code #aarrggbb} colours per channel; other values step. */
    static JsonElement lerp(float alpha, JsonElement from, JsonElement to) {
        if (isNumber(from) && isNumber(to)) {
            float a = from.getAsFloat();
            float b = to.getAsFloat();
            return new JsonPrimitive(a + alpha * (b - a));
        }
        if (isColour(from) && isColour(to) && from.getAsString().length() == to.getAsString().length()) {
            String a = from.getAsString();
            String b = to.getAsString();
            StringBuilder out = new StringBuilder("#");
            for (int i = 1; i < a.length(); i += 2) {
                int ca = Integer.parseInt(a.substring(i, i + 2), 16);
                int cb = Integer.parseInt(b.substring(i, i + 2), 16);
                out.append(String.format(Locale.ROOT, "%02x", ca + (int) Math.floor(alpha * (cb - ca))));
            }
            return new JsonPrimitive(out.toString());
        }
        return (alpha >= 1f ? to : from).deepCopy();
    }

    private static boolean isNumber(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    private static boolean isColour(JsonElement e) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            return false;
        }
        String s = e.getAsString();
        return (s.length() == 7 || s.length() == 9) && s.startsWith("#") && s.substring(1).matches("[0-9a-fA-F]+");
    }

    private record Frame(int ticks, JsonElement value) {
    }

    private record Segment(int fromTicks, JsonElement from, int toTicks, JsonElement to) {
    }
}
