package com.customdimensions.server.structure;

import com.customdimensions.core.dimension.NoiseFieldIndex;
import com.customdimensions.core.dimension.NoiseProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The plan's sites as the structure search walks them: nearest first, within the caller's radius in the placement's sectors. */
class PlanLocateTest {

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    static Map<StructurePlacement, Set<Holder<Structure>>> scans(StructurePlacement... placements) {
        Map<StructurePlacement, Set<Holder<Structure>>> out = new IdentityHashMap<>();
        for (StructurePlacement p : placements) out.put(p, Set.of());
        return out;
    }

    static List<ChunkPos> chunks(List<PlanLocate.Candidate> candidates) {
        return candidates.stream().map(PlanLocate.Candidate::chunk).toList();
    }

    @Test
    void theSearchSeesThePlansPlacementsAndOnlyThose() {
        assertNotNull(PlanLocate.sites(new StructurePlacements.Fixed(1, 2)));
        assertEquals(List.of(new com.customdimensions.core.ChunkPos(1, 2)), PlanLocate.sites(new StructurePlacements.Fixed(1, 2)));
        StructurePlacement vanilla = new StructurePlacement() {
            @Override
            public boolean isStructureChunk(net.minecraft.world.level.chunk.ChunkGeneratorStructureState state, int x, int z) {
                return false;
            }

            @Override
            public com.mojang.serialization.MapCodec<? extends StructurePlacement> codec() {
                throw new UnsupportedOperationException();
            }
        };
        assertNull(PlanLocate.sites(vanilla), "a placement of vanilla's is vanilla's search's");
    }

    @Test
    void fixedSitesComeNearestFirst() {
        var far = new StructurePlacements.Fixed(10, 0);
        var near = new StructurePlacements.Fixed(3, 4);
        var behind = new StructurePlacements.Fixed(-6, -6);
        List<PlanLocate.Candidate> out = PlanLocate.candidates(scans(far, near, behind), new BlockPos(8, 64, 8), 100, Double.MAX_VALUE);
        assertEquals(List.of(new ChunkPos(3, 4), new ChunkPos(-6, -6), new ChunkPos(10, 0)), chunks(out));
        assertTrue(out.get(0).placement() == near);
    }

    @Test
    void theRadiusCountsInThePlacementsSectors() {
        var edge = new StructurePlacements.Fixed(PlanLocate.FIXED_SPACING, 0);
        var past = new StructurePlacements.Fixed(PlanLocate.FIXED_SPACING + 1, 0);
        assertEquals(List.of(new ChunkPos(PlanLocate.FIXED_SPACING, 0)),
                chunks(PlanLocate.candidates(scans(edge, past), BlockPos.ZERO, 1, Double.MAX_VALUE)));
    }

    @Test
    void sitesNoNearerThanVanillasAnswerAreNotChecked() {
        var a = new StructurePlacements.Fixed(2, 0);
        var b = new StructurePlacements.Fixed(5, 0);
        double vanilla = BlockPos.ZERO.distSqr(new BlockPos(64, 0, 0));
        assertEquals(List.of(new ChunkPos(2, 0)), chunks(PlanLocate.candidates(scans(a, b), BlockPos.ZERO, 100, vanilla)));
    }

    @Test
    void aNoiseGroupsSitesWithinTheRadiusComeNearestFirst() {
        NoiseFieldIndex index = new NoiseFieldIndex(20261022L, NoiseProfile.NATURAL, 3, new double[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, 64, 0, 0);
        var noise = new StructurePlacements.Noise("dungeons", index);
        assertTrue(index.size() > 20, "the fixture has sites: " + index.size());
        BlockPos origin = new BlockPos(200, 64, -150);
        int radius = 4;
        long bound = (long) radius * index.spacing();
        List<PlanLocate.Candidate> out = PlanLocate.candidates(scans(noise), origin, radius, Double.MAX_VALUE);
        long inside = index.positions().stream().filter(p -> Math.max(Math.abs(p.x() - (origin.getX() >> 4)),
                Math.abs(p.z() - (origin.getZ() >> 4))) <= bound).count();
        assertEquals(inside, out.size());
        assertTrue(out.size() < index.size(), "the radius bounds the walk");
        for (int i = 1; i < out.size(); i++) assertTrue(out.get(i - 1).distSqr() <= out.get(i).distSqr());
        double nearest = index.positions().stream().mapToDouble(p -> origin.distSqr(new BlockPos(p.x() * 16, 0, p.z() * 16))).min().orElseThrow();
        assertEquals(nearest, out.getFirst().distSqr());
    }
}
