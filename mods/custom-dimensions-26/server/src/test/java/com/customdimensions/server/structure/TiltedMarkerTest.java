package com.customdimensions.server.structure;

import com.customdimensions.core.structure.Tilt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A template's data markers as the chunks of a set piece place them: an
 * elytra frame is hung by the chunk holding the block it hangs on, so its
 * support is written in the same pass whichever chunk generates first, and a
 * tilted template places each marker exactly once.
 */
class TiltedMarkerTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** A template {@code sx} by 1 by {@code sz} of air with one data marker at ({@code mx}, 0, {@code mz}). */
    static TiltedTemplate.Turned template(int sx, int sz, int mx, int mz, String metadata) {
        BlockState[] states = new BlockState[sx * sz];
        CompoundTag[] nbt = new CompoundTag[states.length];
        TiltedTemplate.Turned t = new TiltedTemplate.Turned(sx, 1, sz, states, nbt, new ArrayList<>(), new ArrayList<>());
        for (int i = 0; i < states.length; i++) states[i] = Blocks.AIR.defaultBlockState();
        int k = t.index(mx, 0, mz);
        states[k] = Blocks.STRUCTURE_BLOCK.defaultBlockState();
        CompoundTag tag = new CompoundTag();
        tag.putString("mode", "DATA");
        tag.putString("metadata", metadata);
        nbt[k] = tag;
        return t;
    }

    static BoundingBox chunk(int cx, int cz) {
        return new BoundingBox(cx * 16, -64, cz * 16, cx * 16 + 15, 319, cz * 16 + 15);
    }

    @Test
    void anElytraFrameIsHungByTheChunkHoldingItsSupport() {
        // The frame stands at z 16 (chunk 0, 1) facing south; the block it hangs on is at z 15, in chunk 0, 0.
        TiltedTemplate.Turned t = template(1, 1, 0, 0, "Elytra");
        Tilt level = Tilt.of("none", null, 1, 1, 1);
        BlockPos corner = new BlockPos(0, 64, 16);
        List<TiltedTemplate.Marker> own = TiltedTemplate.markers(t, level, corner, Direction.SOUTH, chunk(0, 1));
        List<TiltedTemplate.Marker> support = TiltedTemplate.markers(t, level, corner, Direction.SOUTH, chunk(0, 0));
        assertEquals(0, own.size(), "the frame's own chunk leaves it to the chunk that writes its support");
        assertEquals(1, support.size());
        assertEquals(new BlockPos(0, 64, 16), support.getFirst().pos());
        assertEquals(new BlockPos(0, 64, 15), support.getFirst().owner());
    }

    @Test
    void anyOtherMarkerIsPlacedByItsOwnChunk() {
        TiltedTemplate.Turned t = template(1, 1, 0, 0, "Chest");
        Tilt level = Tilt.of("none", null, 1, 1, 1);
        BlockPos corner = new BlockPos(0, 64, 16);
        assertEquals(1, TiltedTemplate.markers(t, level, corner, Direction.SOUTH, chunk(0, 1)).size());
        assertEquals(0, TiltedTemplate.markers(t, level, corner, Direction.SOUTH, chunk(0, 0)).size());
    }

    @Test
    void aTiltedTemplatePlacesEachMarkerOnce() {
        for (int mz = 0; mz < 24; mz++) {
            TiltedTemplate.Turned t = template(3, 24, 1, mz, "Elytra");
            Tilt nose = Tilt.of("nose", null, 3, 1, 24);
            BlockPos corner = new BlockPos(5, 64, 4);
            BoundingBox box = TiltedTemplate.box(nose, corner);
            int n = 0;
            for (int cx = Math.floorDiv(box.minX() - 1, 16); cx <= Math.floorDiv(box.maxX() + 1, 16); cx++) {
                for (int cz = Math.floorDiv(box.minZ() - 1, 16); cz <= Math.floorDiv(box.maxZ() + 1, 16); cz++) {
                    n += TiltedTemplate.markers(t, nose, corner, Direction.SOUTH, chunk(cx, cz)).size();
                }
            }
            assertEquals(1, n, "marker at z " + mz);
        }
    }
}
