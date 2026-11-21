package com.customdimensions.server.portal;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Conversions between core's game-free positions and the game's. */
final class Pos {
    private Pos() {
    }

    static BlockPos mc(com.customdimensions.core.BlockPos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    static com.customdimensions.core.BlockPos core(BlockPos p) {
        return new com.customdimensions.core.BlockPos(p.getX(), p.getY(), p.getZ());
    }

    static Set<BlockPos> mc(Set<com.customdimensions.core.BlockPos> cells) {
        Set<BlockPos> out = new HashSet<>();
        cells.forEach(c -> out.add(mc(c)));
        return out;
    }

    static Direction.Axis mc(com.customdimensions.core.Direction.Axis axis) {
        return Direction.Axis.valueOf(axis.name());
    }

    static com.customdimensions.core.Direction.Axis core(Direction.Axis axis) {
        return com.customdimensions.core.Direction.Axis.valueOf(axis.name());
    }
}
