package com.customdimensions.server.survey;

import com.customdimensions.core.survey.SurveyMetrics;
import com.customdimensions.server.structure.StructureRuntime;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.ArrayList;
import java.util.List;

/**
 * The structure sites over a level's border, read from its structure placement
 * (the structure-control state the level generates with): every chunk a set's
 * placement picks, given to the set's first structure whose biome lies at the
 * chunk's centre (or that structure control lets past its biome). No chunk is
 * generated, so terrain checks a structure makes are not applied.
 */
final class StructureCensus {
    private StructureCensus() {
    }

    static List<SurveyMetrics.Site> sites(ServerLevel level, int radius) {
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        RandomState random = state.randomState();
        BiomeSource biomes = level.getChunkSource().getGenerator().getBiomeSource();
        net.minecraft.world.level.biome.Climate.Sampler climate = random.createClimateSampler(
                net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED);
        net.minecraft.world.level.biome.BiomeResolver resolver = biomes.createResolver(climate);
        int y = QuartPos.fromBlock(level.getSeaLevel());
        int min = Math.floorDiv(-radius, 16), max = Math.floorDiv(radius, 16);
        List<SurveyMetrics.Site> out = new ArrayList<>();
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            StructureSet s = set.value();
            for (int cx = min; cx <= max; cx++) {
                for (int cz = min; cz <= max; cz++) {
                    if (!s.placement().isStructureChunk(state, cx, cz)) continue;
                    ChunkPos chunk = new ChunkPos(cx, cz);
                    Holder<Biome> biome = resolver.getNoiseBiome(QuartPos.fromBlock(chunk.getMiddleBlockX()), y,
                            QuartPos.fromBlock(chunk.getMiddleBlockZ()));
                    for (StructureSet.StructureSelectionEntry e : s.structures()) {
                        Holder<Structure> structure = e.structure();
                        if (StructureRuntime.zoneShunned(level.dimension(), structure, chunk)) continue;
                        if (!structure.value().biomes().contains(biome)
                                && !StructureRuntime.bypassesBiome(level.dimension(), structure.value(), chunk)) continue;
                        out.add(new SurveyMetrics.Site(structure.unwrapKey().map(k -> k.identifier().toString()).orElse("?"),
                                chunk.getMiddleBlockX(), chunk.getMiddleBlockZ()));
                        break;
                    }
                }
            }
        }
        return out;
    }
}
