package com.customdimensions.core.portal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What a single-use portal's frame becomes when it expires: the decay map
 * (the definition's {@code decayMap} over a default table and two naming
 * rules), and which ring positions a {@code partial} break takes.
 */
public final class PortalDecay {

    public static final Map<String, String> DEFAULT_DECAY = Map.of(
            "minecraft:obsidian", "minecraft:crying_obsidian",
            "minecraft:stone_bricks", "minecraft:cracked_stone_bricks",
            "minecraft:nether_bricks", "minecraft:cracked_nether_bricks",
            "minecraft:polished_blackstone_bricks", "minecraft:cracked_polished_blackstone_bricks",
            "minecraft:deepslate_bricks", "minecraft:cracked_deepslate_bricks",
            "minecraft:deepslate_tiles", "minecraft:cracked_deepslate_tiles");

    private PortalDecay() {
    }

    /** The decayed form of {@code blockId}, or null when it has none. */
    public static String resolve(String blockId, Map<String, String> overrides) {
        if (blockId == null) {
            return null;
        }
        if (overrides != null && overrides.containsKey(blockId)) {
            return overrides.get(blockId);
        }
        String mapped = DEFAULT_DECAY.get(blockId);
        if (mapped != null) {
            return mapped;
        }
        int colon = blockId.indexOf(':');
        String namespace = colon >= 0 ? blockId.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? blockId.substring(colon + 1) : blockId;
        if (path.endsWith("_planks")) {
            return "minecraft:air";
        }
        if (path.endsWith("_log") && !path.startsWith("stripped_")) {
            return namespace + ":stripped_" + path;
        }
        return null;
    }

    /** One or two distinct ring indices, the same for the same seed. */
    public static List<Integer> pickPartialIndices(int frameSize, long seed) {
        List<Integer> picked = new ArrayList<>();
        if (frameSize <= 0) {
            return picked;
        }
        Random random = new Random(seed);
        int count = frameSize == 1 ? 1 : 1 + random.nextInt(2);
        while (picked.size() < count) {
            int index = random.nextInt(frameSize);
            if (!picked.contains(index)) {
                picked.add(index);
            }
        }
        return picked;
    }

    /** The partial-break seed for a ring whose first position is ({@code x}, {@code y}, {@code z}). */
    public static long partialSeed(int x, int y, int z) {
        return x * 341873128712L + y * 132897987541L + z;
    }
}
