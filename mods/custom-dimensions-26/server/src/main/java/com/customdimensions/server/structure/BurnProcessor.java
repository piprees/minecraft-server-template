package com.customdimensions.server.structure;

import com.customdimensions.core.structure.Age;
import com.customdimensions.core.structure.Assemblies;
import com.customdimensions.core.structure.SetPiecePlan;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * A template of a {@code burned} camp or hovel: its wood swapped for the
 * burned palette and its cloth and thin wood gone, by {@link Assemblies#burnBlock}
 * at a roll for each world position. Built in code only, never decoded.
 */
final class BurnProcessor implements StructureProcessor {

    private static final MapCodec<BurnProcessor> CODEC = MapCodec.unit(() -> {
        throw new UnsupportedOperationException("a burn processor is built in code only");
    });

    private final long seed;

    BurnProcessor(long seed) {
        this.seed = seed;
    }

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos origin, BlockPos pivot, BlockPos local,
                                                             StructureTemplate.StructureBlockInfo current, StructurePlaceSettings settings) {
        if (current == null || current.state().isAir()) return current;
        BlockPos p = current.pos();
        String id = BuiltInRegistries.BLOCK.getKey(current.state().getBlock()).toString();
        String to = Assemblies.burnBlock(id, SetPiecePlan.roll(seed, p.getX(), p.getY(), p.getZ(), 4));
        if (to.equals(id)) return current;
        if (to.equals(Age.AIR)) return new StructureTemplate.StructureBlockInfo(p, Blocks.AIR.defaultBlockState(), null);
        String base = to.contains("[") ? to.substring(0, to.indexOf('[')) : to;
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(base)).orElse(null);
        if (block == null) return current;
        return new StructureTemplate.StructureBlockInfo(p, AgeProcessor.copyProperties(current.state(), block.defaultBlockState()), null);
    }

    @Override
    public MapCodec<? extends StructureProcessor> codec() {
        return CODEC;
    }
}
