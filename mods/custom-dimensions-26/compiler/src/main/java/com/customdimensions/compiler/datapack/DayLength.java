package com.customdimensions.compiler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code atmosphere.dayLength} as 26.3 data. The world clock ticks once a game
 * tick, and a periodic timeline reads it modulo its period; a timeline of a
 * vanilla day ({@link #VANILLA_DAY} ticks) is rewritten so its day half
 * (ticks 0 to 12000) spans {@code dayMinutes} and its night half
 * {@code nightMinutes}: each keyframe, time marker and the period move to their
 * warped tick, and a numeric or colour track gains a sample every {@link #STEP} vanilla
 * ticks in between, so its eased shape (the sun's arc, the sky's fade) follows
 * the warp linearly. A timeline of several days warps each day the same way.
 * Random ticks and weather keep vanilla's pacing; they read no timeline.
 */
public final class DayLength {
    /** Ticks in a vanilla day, and the tick its night half starts at. */
    public static final int VANILLA_DAY = 24000, VANILLA_DUSK = 12000;
    /** Game ticks in a real minute. */
    public static final int TICKS_PER_MINUTE = 1200;
    /** Vanilla ticks between the samples added to a numeric track. */
    static final int STEP = 500;

    private final long day;
    private final long night;

    public DayLength(double dayMinutes, double nightMinutes) {
        this.day = Math.round(dayMinutes * TICKS_PER_MINUTE);
        this.night = Math.round(nightMinutes * TICKS_PER_MINUTE);
    }

    /** Ticks in one warped day. */
    public long period() {
        return day + night;
    }

    /** Whether {@code timeline} is periodic in whole vanilla days, so a day length applies to it. */
    public static boolean applies(JsonObject timeline) {
        return timeline.has("period_ticks") && timeline.get("period_ticks").getAsLong() % VANILLA_DAY == 0;
    }

    /** The warped tick of vanilla tick {@code t}. */
    public long warp(long t) {
        long d = Math.floorDiv(t, VANILLA_DAY), w = Math.floorMod(t, VANILLA_DAY);
        long within = w < VANILLA_DUSK ? Math.round((double) w * day / VANILLA_DUSK)
                : day + Math.round((double) (w - VANILLA_DUSK) * night / (VANILLA_DAY - VANILLA_DUSK));
        return d * period() + within;
    }

    /** {@code timeline} on this day length, read from {@code clock}. */
    public JsonObject apply(JsonObject timeline, String clock) {
        int period = timeline.get("period_ticks").getAsInt();
        JsonObject out = new JsonObject();
        out.addProperty("clock", clock);
        out.addProperty("period_ticks", warp(period));
        if (timeline.has("time_markers")) {
            JsonObject markers = new JsonObject();
            for (Map.Entry<String, JsonElement> m : timeline.getAsJsonObject("time_markers").entrySet()) {
                JsonElement v = m.getValue();
                if (v.isJsonObject()) {
                    JsonObject o = v.getAsJsonObject().deepCopy();
                    o.addProperty("ticks", warp(o.get("ticks").getAsLong()));
                    markers.add(m.getKey(), o);
                } else {
                    markers.addProperty(m.getKey(), warp(v.getAsLong()));
                }
            }
            out.add("time_markers", markers);
        }
        JsonObject tracks = new JsonObject();
        for (Map.Entry<String, JsonElement> e : timeline.getAsJsonObject("tracks").entrySet()) {
            tracks.add(e.getKey(), track(e.getValue().getAsJsonObject(), period));
        }
        out.add("tracks", tracks);
        return out;
    }

    private JsonObject track(JsonObject track, int period) {
        JsonArray frames = track.getAsJsonArray("keyframes");
        JsonElement ease = track.get("ease");
        boolean constant = ease != null && ease.isJsonPrimitive() && "constant".equals(ease.getAsString());
        JsonElement v0 = frames.get(0).getAsJsonObject().get("value");
        boolean blends = v0.isJsonPrimitive() && (v0.getAsJsonPrimitive().isNumber()
                || v0.getAsJsonPrimitive().isString() && v0.getAsString().startsWith("#"));
        boolean sampled = blends && !constant && frames.size() > 1;
        List<long[]> ticks = new ArrayList<>();
        List<JsonElement> values = new ArrayList<>();
        int first = frames.get(0).getAsJsonObject().get("ticks").getAsInt();
        int last = frames.get(frames.size() - 1).getAsJsonObject().get("ticks").getAsInt();
        if (sampled) {
            for (int t = 0; t < first; t += STEP) add(ticks, values, t, FixedTime.sample(track, period, t));
        }
        for (int i = 0; i < frames.size(); i++) {
            JsonObject f = frames.get(i).getAsJsonObject();
            int t = f.get("ticks").getAsInt();
            add(ticks, values, t, f.get("value").deepCopy());
            if (sampled && i + 1 < frames.size()) {
                int next = frames.get(i + 1).getAsJsonObject().get("ticks").getAsInt();
                for (int g = (t / STEP + 1) * STEP; g < next; g += STEP) add(ticks, values, g, FixedTime.sample(track, period, g));
            }
        }
        if (sampled) {
            for (int g = (last / STEP + 1) * STEP; g < period; g += STEP) add(ticks, values, g, FixedTime.sample(track, period, g));
        }
        JsonArray keyframes = new JsonArray();
        for (int i = 0; i < ticks.size(); i++) {
            JsonObject k = new JsonObject();
            k.addProperty("ticks", warp(ticks.get(i)[0]));
            k.add("value", values.get(i));
            keyframes.add(k);
        }
        JsonObject out = new JsonObject();
        out.add("keyframes", keyframes);
        if (!sampled && ease != null) out.add("ease", ease.deepCopy());
        if (track.has("modifier")) out.add("modifier", track.get("modifier").deepCopy());
        return out;
    }

    private static void add(List<long[]> ticks, List<JsonElement> values, long t, JsonElement value) {
        ticks.add(new long[]{t});
        values.add(value);
    }

    /**
     * The world clock of this day length: {@code <namespace>:day_length/<day>_<night>} in ticks. A clock's time markers
     * are unique across every timeline on it, so the warped day, which carries vanilla's markers, runs on a clock of its own.
     */
    public String clockId(String namespace) {
        return namespace + ":day_length/" + day + "_" + night;
    }

    /** The id of a vanilla timeline on this day length: {@code <namespace>:day_length/<path>_<day>_<night>} in ticks. */
    public String id(String namespace, String vanillaId) {
        return namespace + ":day_length/" + vanillaId.substring(vanillaId.indexOf(':') + 1) + "_" + day + "_" + night;
    }
}
