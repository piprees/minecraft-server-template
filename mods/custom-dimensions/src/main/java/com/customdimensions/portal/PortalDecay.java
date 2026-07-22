package com.customdimensions.portal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Pure resolution logic for single-use portal frame decay (portal.singleUse
 * in the dimension config). No Minecraft classes so the whole table is
 * exercisable from plain JUnit; PortalHelper owns the world-side application.
 */
public final class PortalDecay {

    /** Built-in decay pairs; a config decayMap entry for the same id wins. */
    public static final Map<String, String> DEFAULT_DECAY = Map.of(
            "minecraft:obsidian", "minecraft:crying_obsidian",
            "minecraft:stone_bricks", "minecraft:cracked_stone_bricks",
            "minecraft:nether_bricks", "minecraft:cracked_nether_bricks",
            "minecraft:polished_blackstone_bricks", "minecraft:cracked_polished_blackstone_bricks",
            "minecraft:deepslate_bricks", "minecraft:cracked_deepslate_bricks",
            "minecraft:deepslate_tiles", "minecraft:cracked_deepslate_tiles");

    private PortalDecay() {
    }

    /**
     * Decayed replacement for a frame block id, or null to leave the block
     * untouched. Order: explicit config decayMap, built-in pairs, then the
     * pattern rules (any *_log strips, any *_planks burns out to air).
     */
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

    /**
     * Deterministic pick of 1-2 frame indices for "partial" break mode. The
     * seed comes from the zone's position so the same frame always crumbles
     * the same way (stable across restarts and re-checks).
     */
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
}
