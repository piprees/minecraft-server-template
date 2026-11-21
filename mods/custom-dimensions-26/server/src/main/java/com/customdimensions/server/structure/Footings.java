package com.customdimensions.server.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The terrain adaptations beyond vanilla's (lore/placement.md G9), for ours and
 * the mods' structures alike, run after a start's pieces are placed in a
 * chunk: {@code footing} fills under every piece's lowest blocks down to the
 * ground in the dimension's own stone, so nothing stands on stilts of air;
 * {@code terrace} does the same and cuts the ground round each piece into a
 * stepped bank, one block per block out for {@value #MARGIN} blocks, filling
 * where it falls away. Declining a footprint that spreads past its slope limit
 * is the site filter's work ({@code structures.sites}, a set piece's
 * {@code slope}).
 */
public final class Footings {

    static final int MAX = 32;
    static final int MARGIN = 6;
    private static final AtomicLong FILLED = new AtomicLong();
    private static final AtomicLong CUT = new AtomicLong();

    private Footings() {
    }

    /** Blocks filled under footprints and blocks cut or raised for terraces since the server started. */
    public static long[] counts() {
        return new long[] {FILLED.get(), CUT.get()};
    }

    public static void apply(StructureStart start, WorldGenLevel level, ChunkGenerator generator, BoundingBox chunkBox) {
        if (!start.isValid()) return;
        String kind = StructureRuntime.footing(level.getLevel().dimension(), start.getStructure());
        if (kind == null) return;
        BlockState stone = generator instanceof NoiseBasedChunkGenerator n ? n.generatorSettings().value().defaultBlock() : Blocks.STONE.defaultBlockState();
        if (stone.isAir() || !stone.getFluidState().isEmpty()) stone = Blocks.STONE.defaultBlockState();
        List<BoundingBox> boxes = new ArrayList<>();
        for (StructurePiece p : start.getPieces()) {
            if (p instanceof SetPiecePart part && part.drawsShape()) continue;
            boxes.add(p.getBoundingBox());
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (BoundingBox box : boxes) fill(level, box, chunkBox, stone, pos);
        if ("terrace".equals(kind)) terrace(level, boxes, chunkBox, stone, pos);
    }

    private static boolean open(BlockState s) {
        return s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced();
    }

    private static void fill(WorldGenLevel level, BoundingBox box, BoundingBox chunkBox, BlockState stone, BlockPos.MutableBlockPos pos) {
        for (int x = Math.max(box.minX(), chunkBox.minX()); x <= Math.min(box.maxX(), chunkBox.maxX()); x++) {
            for (int z = Math.max(box.minZ(), chunkBox.minZ()); z <= Math.min(box.maxZ(), chunkBox.maxZ()); z++) {
                int bottom = Integer.MIN_VALUE;
                for (int y = box.minY(); y <= Math.min(box.minY() + 3, box.maxY()); y++) {
                    if (!open(level.getBlockState(pos.set(x, y, z)))) {
                        bottom = y;
                        break;
                    }
                }
                if (bottom == Integer.MIN_VALUE) continue;
                for (int y = bottom - 1; y >= Math.max(level.getMinY() + 1, bottom - MAX); y--) {
                    if (!open(level.getBlockState(pos.set(x, y, z)))) break;
                    level.setBlock(pos, stone, 2);
                    FILLED.incrementAndGet();
                }
            }
        }
    }

    private static void terrace(WorldGenLevel level, List<BoundingBox> boxes, BoundingBox chunkBox, BlockState stone, BlockPos.MutableBlockPos pos) {
        for (int x = chunkBox.minX(); x <= chunkBox.maxX(); x++) {
            for (int z = chunkBox.minZ(); z <= chunkBox.maxZ(); z++) {
                BoundingBox near = null;
                int best = MARGIN + 1;
                for (BoundingBox b : boxes) {
                    int d = Math.max(Math.max(b.minX() - x, x - b.maxX()), Math.max(b.minZ() - z, z - b.maxZ()));
                    if (d <= 0) {
                        near = null;
                        best = 0;
                        break;
                    }
                    if (d < best) {
                        best = d;
                        near = b;
                    }
                }
                if (near == null) continue;
                int floor = near.minY();
                int g = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                while (g > level.getMinY() && open(level.getBlockState(pos.set(x, g - 1, z)))) g--;
                int top = g - 1;
                if (top <= level.getMinY() + SetPieceStructure.VOID_BAND) continue;
                BlockState skin = level.getBlockState(pos.set(x, top, z));
                int highest = floor - 1 + best;
                int lowest = floor - 1 - best;
                if (top > highest) {
                    for (int y = highest + 1; y <= top + 1; y++) {
                        level.setBlock(pos.set(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                        CUT.incrementAndGet();
                    }
                    level.setBlock(pos.set(x, highest, z), skin, 2);
                } else if (top < lowest) {
                    for (int y = top; y < lowest; y++) {
                        level.setBlock(pos.set(x, y, z), stone, 2);
                        CUT.incrementAndGet();
                    }
                    level.setBlock(pos.set(x, lowest, z), skin, 2);
                }
            }
        }
    }
}
