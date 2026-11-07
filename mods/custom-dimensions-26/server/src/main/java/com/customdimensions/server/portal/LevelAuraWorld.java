package com.customdimensions.server.portal;

import com.customdimensions.core.portal.AuraWorld;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.material.FluidState;

/**
 * {@link AuraWorld} over one level. Every read goes through
 * {@code getChunkNow}, so a cold chunk reads as air and is never loaded;
 * a plant needs its neighbours' chunks resident and a feature the 3x3 chunks
 * around it, since both read or write past their own cell.
 */
final class LevelAuraWorld implements AuraWorld {
    static final TagKey<Block> AURA_PROTECTED = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("adventure", "aura_protected"));

    private final ServerLevel level;

    LevelAuraWorld(ServerLevel level) {
        this.level = level;
    }

    private static BlockPos mc(com.customdimensions.core.BlockPos p) {
        return Pos.mc(p);
    }

    private LevelChunk chunk(int x, int z) {
        return level.getChunkSource().getChunkNow(x >> 4, z >> 4);
    }

    private BlockState state(com.customdimensions.core.BlockPos p) {
        LevelChunk chunk = chunk(p.getX(), p.getZ());
        return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(mc(p));
    }

    private boolean residentAround(com.customdimensions.core.BlockPos p, int chunks) {
        int cx = p.getX() >> 4;
        int cz = p.getZ() >> 4;
        int reach = chunks * 16;
        return PortalWorld.resident(level, (cx << 4) - reach, (cz << 4) - reach, (cx << 4) + reach, (cz << 4) + reach);
    }

    @Override
    public boolean resident(com.customdimensions.core.BlockPos pos) {
        return chunk(pos.getX(), pos.getZ()) != null;
    }

    @Override
    public boolean isAir(com.customdimensions.core.BlockPos pos) {
        return state(pos).isAir();
    }

    @Override
    public String blockId(com.customdimensions.core.BlockPos pos) {
        return PortalWorld.blockId(state(pos));
    }

    @Override
    public boolean isSolid(com.customdimensions.core.BlockPos pos) {
        return state(pos).isSolidRender();
    }

    @Override
    public boolean hasBlockEntity(com.customdimensions.core.BlockPos pos) {
        return state(pos).hasBlockEntity();
    }

    @Override
    public boolean isFixed(com.customdimensions.core.BlockPos pos) {
        BlockState state = state(pos);
        return state.is(Blocks.BEDROCK) || PortalWorld.isPortal(state) || state.is(Blocks.NETHER_PORTAL)
                || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY)
                || PortalRegistry.get().zoneAt(level.dimension(), mc(pos)) != null;
    }

    @Override
    public boolean isProtected(com.customdimensions.core.BlockPos pos) {
        return state(pos).is(AURA_PROTECTED);
    }

    @Override
    public boolean inTag(com.customdimensions.core.BlockPos pos, String tag) {
        Identifier id = Identifier.tryParse(tag);
        return id != null && state(pos).is(TagKey.create(Registries.BLOCK, id));
    }

    @Override
    public boolean isLog(com.customdimensions.core.BlockPos pos) {
        return state(pos).is(BlockTags.LOGS);
    }

    @Override
    public boolean isFlora(com.customdimensions.core.BlockPos pos) {
        BlockState state = state(pos);
        return state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS);
    }

    @Override
    public boolean hasFluid(com.customdimensions.core.BlockPos pos) {
        return !state(pos).getFluidState().isEmpty();
    }

    @Override
    public String stillFluid(com.customdimensions.core.BlockPos pos) {
        FluidState fluid = state(pos).getFluidState();
        if (fluid.isEmpty() || !fluid.isSource()) {
            return null;
        }
        return fluid.is(FluidTags.LAVA) ? "minecraft:lava" : "minecraft:water";
    }

    @Override
    public boolean claimed(com.customdimensions.core.BlockPos pos) {
        return ClaimsCompat.isClaimed(level, mc(pos));
    }

    @Override
    public boolean canPlace(com.customdimensions.core.BlockPos pos, String blockId) {
        Block block = PortalWorld.block(blockId);
        return block != null && block != Blocks.AIR && residentAround(pos, 1)
                && block.defaultBlockState().canSurvive(level, mc(pos));
    }

    @Override
    public boolean set(com.customdimensions.core.BlockPos pos, String blockId) {
        Block block = PortalWorld.block(blockId);
        if (block == null || block == Blocks.AIR || !resident(pos)) {
            return false;
        }
        return level.setBlock(mc(pos), block.defaultBlockState(), PortalWorld.QUIET);
    }

    @Override
    public boolean growFeature(com.customdimensions.core.BlockPos pos, String featureId) {
        Identifier id = Identifier.tryParse(featureId);
        if (id == null || !residentAround(pos, 1)) {
            return false;
        }
        Optional<Feature> feature = level.registryAccess().lookupOrThrow(Registries.FEATURE).getOptional(id);
        if (feature.isEmpty()) {
            return false;
        }
        try {
            return feature.get().place(level, level.getChunkSource().getGenerator(), level.getRandom(), mc(pos));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
