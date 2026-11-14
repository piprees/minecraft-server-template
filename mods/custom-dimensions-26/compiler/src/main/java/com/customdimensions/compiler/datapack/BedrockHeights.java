package com.customdimensions.compiler.datapack;

import com.google.gson.JsonObject;

/**
 * The Bedrock dimension Geyser shows a compiled dimension type as, and whether
 * the type's height fits it. Geyser maps a dimension type outside the
 * {@code minecraft} namespace by its {@code skybox}: {@code none} is the Nether,
 * {@code end} the End, anything else the Overworld
 * (https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/level/JavaDimension.java).
 * Bedrock's ranges, and which top Geyser warns about, are Geyser's
 * (https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/level/BedrockDimension.java):
 * Overworld -64 to 320, Nether 0 to 128 (the bottom only: vanilla's Nether is
 * 256 tall above a roof Bedrock players never see past), End 0 to 256.
 */
public final class BedrockHeights {

    /** A Bedrock dimension's buildable range; {@code checkTop} as Geyser's {@code doUpperHeightWarn}. */
    public record Range(String name, int minY, int height, boolean checkTop) {
        public int maxY() {
            return minY + height;
        }
    }

    public static final Range OVERWORLD = new Range("overworld", -64, 384, true);
    public static final Range NETHER = new Range("nether", 0, 128, false);
    public static final Range END = new Range("end", 0, 256, true);

    private BedrockHeights() {
    }

    /** The Bedrock dimension a non-{@code minecraft} dimension type maps to. */
    public static Range rangeOf(JsonObject type) {
        String skybox = type.has("skybox") ? type.get("skybox").getAsString() : "overworld";
        return switch (skybox) {
            case "none" -> NETHER;
            case "end" -> END;
            default -> OVERWORLD;
        };
    }

    /** Why the type's {@code min_y}/{@code height} fall outside its Bedrock range, or null when they fit. */
    public static String check(JsonObject type) {
        Range range = rangeOf(type);
        int minY = type.get("min_y").getAsInt();
        int maxY = minY + type.get("height").getAsInt();
        boolean low = minY < range.minY();
        boolean high = range.checkTop() && maxY > range.maxY();
        if (!low && !high) {
            return null;
        }
        return "y " + minY + " to " + maxY + " shows as the Bedrock " + range.name() + " (y " + range.minY() + " to "
                + range.maxY() + "); " + (low ? "blocks below y " + range.minY() : "")
                + (low && high ? " and " : "") + (high ? "blocks from y " + range.maxY() : "")
                + " are invisible to Bedrock players";
    }
}
