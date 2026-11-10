package com.customdimensions.core.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Schema v2 {@code atmosphere}: sky, fog and ambient light, a fixed time of day or a day length, and a vanilla sky to borrow. */
public record Atmosphere(String skyColor, String fogColor, Double ambientLight, Long fixedTime, String effects, DayLength dayLength) {

    public Atmosphere(String skyColor, String fogColor, Double ambientLight, Long fixedTime, String effects) {
        this(skyColor, fogColor, ambientLight, fixedTime, effects, null);
    }

    /** {@code dayLength}: real minutes from sunrise to sunset ({@code dayMinutes}) and from sunset to sunrise ({@code nightMinutes}). */
    public record DayLength(double dayMinutes, double nightMinutes) {
        public static final double MIN_MINUTES = 1, MAX_MINUTES = 600;

        public DayLength {
            for (double m : new double[]{dayMinutes, nightMinutes}) {
                if (!(m >= MIN_MINUTES && m <= MAX_MINUTES)) {
                    throw new IllegalArgumentException("atmosphere.dayLength minutes must lie in [" + (int) MIN_MINUTES + ", "
                            + (int) MAX_MINUTES + "], got " + m);
                }
            }
        }

        /** The day length an {@code atmosphere} object of {@code settings.json}'s defaults gives, or null. */
        public static DayLength fromDefaults(JsonObject defaults) {
            if (defaults == null || !defaults.has("atmosphere") || !defaults.get("atmosphere").isJsonObject()) return null;
            JsonElement d = defaults.getAsJsonObject("atmosphere").get("dayLength");
            if (d == null || !d.isJsonObject()) return null;
            JsonObject o = d.getAsJsonObject();
            return new DayLength(o.get("dayMinutes").getAsDouble(), o.get("nightMinutes").getAsDouble());
        }
    }
}
