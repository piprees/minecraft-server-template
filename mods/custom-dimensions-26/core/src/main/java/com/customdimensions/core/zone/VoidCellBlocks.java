package com.customdimensions.core.zone;

import java.util.Set;

/**
 * What a block in a void cell of a checkerboard is: terrain the generator laid (its stone, the fluids of its sea and
 * aquifers, the bedrock floor) or a feature or structure (an End spike's obsidian and the bedrock under its crystal, a
 * village's planks reaching over from the next cell).
 */
public final class VoidCellBlocks {
    /** Blocks the noise and its material rule lay; bedrock only as the floor, within {@link #FLOOR} blocks of the bottom. */
    static final Set<String> TERRAIN = Set.of("minecraft:stone", "minecraft:deepslate", "minecraft:water", "minecraft:lava");
    static final String BEDROCK = "minecraft:bedrock";
    /** Height of the bedrock floor the overworld's material rule lays. */
    static final int FLOOR = 5;

    private VoidCellBlocks() {
    }

    /** Whether block {@code id} at {@code y} in a world starting at {@code minY} is terrain rather than a feature. */
    public static boolean terrain(String id, int y, int minY) {
        return TERRAIN.contains(id) || BEDROCK.equals(id) && y < minY + FLOOR;
    }
}
