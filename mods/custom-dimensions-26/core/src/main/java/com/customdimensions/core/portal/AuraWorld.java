package com.customdimensions.core.portal;

import com.customdimensions.core.BlockPos;

/**
 * The block reads and writes an aura needs, answered by the server over one
 * level. Every read of a cell outside a resident chunk answers as air and
 * every write there is refused, so nothing behind this interface loads a chunk.
 */
public interface AuraWorld {

    /** The cell's chunk is already in memory. */
    boolean resident(BlockPos pos);

    boolean isAir(BlockPos pos);

    /** A block id such as {@code minecraft:stone}. */
    String blockId(BlockPos pos);

    /** An opaque full cube. */
    boolean isSolid(BlockPos pos);

    boolean hasBlockEntity(BlockPos pos);

    /** Bedrock, a portal block or a lit portal's cell: never replaced by anything. */
    boolean isFixed(BlockPos pos);

    /** In {@code #adventure:aura_protected}. */
    boolean isProtected(BlockPos pos);

    /** In the block tag {@code tag} (an id without the {@code #}). */
    boolean inTag(BlockPos pos, String tag);

    boolean isLog(BlockPos pos);

    /** A flower, sapling or small plant. */
    boolean isFlora(BlockPos pos);

    /** Holds any fluid, still or flowing. */
    boolean hasFluid(BlockPos pos);

    /** {@code minecraft:water} or {@code minecraft:lava} for a still fluid, else null. */
    String stillFluid(BlockPos pos);

    /** Inside a chunk an Open Parties and Claims player claimed. */
    boolean claimed(BlockPos pos);

    /** {@code blockId}'s default state would survive at the cell. */
    boolean canPlace(BlockPos pos, String blockId);

    /** Sets the cell to {@code blockId}'s default state; false for an unknown id or a refused write. */
    boolean set(BlockPos pos, String blockId);

    /** Places the {@code worldgen/feature} entry {@code featureId}; false when it is unknown or does not fit. */
    boolean growFeature(BlockPos pos, String featureId);
}
