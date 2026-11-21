package com.customdimensions.server.structure;

import com.customdimensions.core.structure.Age;
import com.customdimensions.core.structure.SetPiecePlan;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * A template's blocks weathered to an {@link Age}: each block's swap is
 * decided by a roll for its world position and the piece's seed, so the
 * same block weathers the same whichever chunk places it. A swapped block
 * keeps every property the new block shares (a stair's facing, a slab's
 * half); a swap to a block the game lacks keeps the original. Built in code
 * only, never decoded, so its codec refuses.
 */
final class AgeProcessor implements StructureProcessor {

    private static final MapCodec<AgeProcessor> CODEC = MapCodec.unit(() -> {
        throw new UnsupportedOperationException("an age processor is built in code only");
    });

    private final Age age;
    private final long seed;

    AgeProcessor(Age age, long seed) {
        this.age = age;
        this.seed = seed;
    }

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos origin, BlockPos pivot,
                                                             BlockPos local,
                                                             StructureTemplate.StructureBlockInfo current, StructurePlaceSettings settings) {
        if (current == null || current.state().isAir()) return current;
        BlockPos p = current.pos();
        String id = BuiltInRegistries.BLOCK.getKey(current.state().getBlock()).toString();
        String to = age.weather(id, SetPiecePlan.roll(seed, p.getX(), p.getY(), p.getZ(), 1), SetPiecePlan.roll(seed, p.getX(), p.getY(), p.getZ(), 2));
        if (to.equals(id)) return current;
        if (to.equals(Age.AIR)) return new StructureTemplate.StructureBlockInfo(p, Blocks.AIR.defaultBlockState(), null);
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(to)).orElse(null);
        if (block == null) return current;
        return new StructureTemplate.StructureBlockInfo(p, copyProperties(current.state(), block.defaultBlockState()), current.nbt());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static BlockState copyProperties(BlockState from, BlockState to) {
        BlockState out = to;
        for (Property property : from.getProperties()) {
            if (out.hasProperty(property)) out = out.setValue(property, from.getValue(property));
        }
        return out;
    }

    @Override
    public MapCodec<? extends StructureProcessor> codec() {
        return CODEC;
    }
}
