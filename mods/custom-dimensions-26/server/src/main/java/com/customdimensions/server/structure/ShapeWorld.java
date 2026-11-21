package com.customdimensions.server.structure;

import com.customdimensions.core.structure.SetPieceShapes;
import com.customdimensions.server.CustomDimensions;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The chunk being generated as a {@link SetPieceShapes.World}: the ground at
 * a column read from the chunk itself (the surface less plants, snow and other
 * blocks a player walks through, or with {@code scan} the floor nearest the
 * piece's origin), writes kept inside the chunk, and every liquid written
 * given a fluid tick, so a fall flows from the first moment the chunk ticks.
 */
final class ShapeWorld implements SetPieceShapes.World {

    private static final Map<String, BlockState> STATES = new ConcurrentHashMap<>();
    static final int SCAN_UP = 12;
    static final int SCAN_DOWN = 40;

    private final WorldGenLevel level;
    private final BoundingBox chunkBox;
    private final boolean scan;
    private final int originY;
    private final String stone;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    int written;
    int ticks;

    ShapeWorld(WorldGenLevel level, BoundingBox chunkBox, ChunkGenerator generator, boolean scan, int originY) {
        this.level = level;
        this.chunkBox = chunkBox;
        this.scan = scan;
        this.originY = originY;
        BlockState s = generator instanceof NoiseBasedChunkGenerator n ? n.generatorSettings().value().defaultBlock() : Blocks.STONE.defaultBlockState();
        this.stone = BuiltInRegistries.BLOCK.getKey((s.isAir() ? Blocks.STONE.defaultBlockState() : s).getBlock()).toString();
    }

    private BlockState state(int x, int y, int z) {
        if (y < level.getMinY() || y > level.getMaxY()) return Blocks.VOID_AIR.defaultBlockState();
        return level.getBlockState(pos.set(x, y, z));
    }

    private static boolean ground(BlockState s) {
        return s.isSolid() || !s.getFluidState().isEmpty();
    }

    @Override
    public int ground(int x, int z) {
        if (scan) {
            for (int y = Math.min(level.getMaxY(), originY + SCAN_UP); y >= Math.max(level.getMinY() + 1, originY - SCAN_DOWN); y--) {
                if (state(x, y, z).isAir() && ground(state(x, y - 1, z))) return y;
            }
            return SetPieceShapes.NO_GROUND;
        }
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        while (y > level.getMinY() && !ground(state(x, y - 1, z))) y--;
        return y <= level.getMinY() + SetPieceStructure.VOID_BAND ? SetPieceShapes.NO_GROUND : y;
    }

    @Override
    public String block(int x, int y, int z) {
        return BuiltInRegistries.BLOCK.getKey(state(x, y, z).getBlock()).toString();
    }

    @Override
    public void set(int x, int y, int z, String state) {
        if (y < level.getMinY() || y > level.getMaxY() || !chunkBox.isInside(pos.set(x, y, z))) return;
        BlockState s = parse(state);
        if (s == null) return;
        level.setBlock(pos, s, 2);
        written++;
        if (!s.getFluidState().isEmpty()) {
            level.scheduleTick(pos.immutable(), s.getFluidState().getType(), 0);
            ticks++;
        }
    }

    @Override
    public String stone() {
        return stone;
    }

    /** A block state from its string, or null (logged) when it does not parse. */
    static BlockState parse(String state) {
        BlockState cached = STATES.get(state);
        if (cached != null) return cached;
        try {
            BlockState s = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false).blockState();
            STATES.put(state, s);
            return s;
        } catch (Exception e) {
            CustomDimensions.LOG.warn("set piece block {} does not parse: {}", state, e.getMessage());
            return null;
        }
    }
}
