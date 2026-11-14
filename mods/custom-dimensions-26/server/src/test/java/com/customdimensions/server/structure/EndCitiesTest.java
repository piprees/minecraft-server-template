package com.customdimensions.server.structure;

import com.customdimensions.core.ChunkPos;
import com.customdimensions.core.dimension.NoiseFieldIndex;
import com.customdimensions.core.structure.StructureSpec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The End's structure plan read headlessly through the runtime's own index
 * and settling, on vanilla's End generator and biome source with the_end's
 * definition seed: how many End cities start inside its player border, and
 * how a forced start's {@code y} meets a structure's own height rule.
 */
class EndCitiesTest {

    static final LevelHeightAccessor END_HEIGHT = LevelHeightAccessor.create(0, 256);

    static HolderLookup.Provider lookup;
    static BiomeSource biomes;
    static NoiseBasedChunkGenerator generator;
    static RandomState random;
    static Structure endCity;
    static long seed;
    static int border;

    @BeforeAll
    static void bootstrap() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        JsonObject def = JsonParser.parseString(Files.readString(
                Path.of(System.getProperty("customdimensions.definitions"), "the_end.json"))).getAsJsonObject();
        seed = def.get("seed").getAsLong();
        border = def.getAsJsonObject("borders").get("player").getAsInt();
        lookup = VanillaRegistries.createWorldLookup();
        biomes = TheEndBiomeSource.create(lookup.lookupOrThrow(Registries.BIOME));
        Holder<NoiseGeneratorSettings> settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.END);
        generator = new NoiseBasedChunkGenerator(biomes, settings);
        random = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), seed, settings.value());
        endCity = lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.END_CITY).value();
    }

    /** The End city's biomes, read from vanilla's own tag file. */
    static Predicate<Holder<Biome>> endCityBiomes() throws Exception {
        JsonObject tag = JsonParser.parseString(Files.readString(Path.of(System.getProperty("customdimensions.vanilla"),
                "data/minecraft/tags/worldgen/biome/has_structure/end_city.json"))).getAsJsonObject();
        Set<String> ids = new HashSet<>();
        tag.getAsJsonArray("values").forEach(v -> ids.add(v.getAsString()));
        return h -> h.unwrapKey().map(k -> ids.contains(k.identifier().toString())).orElse(false);
    }

    static Optional<Structure.GenerationStub> start(ChunkGenerator on, ChunkPos chunk, Predicate<Holder<Biome>> valid) {
        Structure.GenerationContext context = new Structure.GenerationContext(null, on, biomes,
                random.createClimateSampler(SamplerContext.EMPTY_UNCACHED), random, null, seed,
                new net.minecraft.world.level.ChunkPos(chunk.x(), chunk.z()), END_HEIGHT, valid);
        return endCity.findValidGenerationPoint(context);
    }

    @Test
    void theEndStartsAboutSixtyCitiesInsideItsPlayerBorder() throws Exception {
        StructureSpec spec = StructureSpec.resolve(new StructureSpec.Context("the_end", "minecraft:the_end", "end", 1.5, border, 0, 0),
                null).spec();
        NoiseFieldIndex index = StructureRuntime.index(seed, "minecraft:the_end", "dungeons", spec.groups().get("dungeons"), spec, 1);
        Predicate<Holder<Biome>> fits = endCityBiomes();
        BiomeResolver sampler = biomes.createUncachedResolver(random);
        List<ChunkPos> sites = StructureRuntime.settled(index, seed, "dungeons", fits, sampler, generator.getSeaLevel());
        int inside = 0;
        int starts = 0;
        for (ChunkPos site : sites) {
            if (Math.hypot(site.x() * 16 + 8, site.z() * 16 + 8) > border) continue;
            inside++;
            if (start(generator, site, fits).isPresent()) starts++;
        }
        System.out.printf("the_end dungeons: %d index sites (exclusion %d), %d settled, %d inside the %d-block border, %d End city starts%n",
                index.size(), index.exclusion(), sites.size(), inside, border, starts);
        assertTrue(starts >= 50 && starts <= 80, starts + " End city starts");
    }

    @Test
    void aForcedStartOverVoidStandsAtItsYAndAnEndCityRefusesBelowSixty() {
        ChunkPos voidChunk = new ChunkPos(0, 40);
        assertEquals(END_HEIGHT.getMinY(), generator.getBaseHeight(8, 648, Heightmap.Types.WORLD_SURFACE_WG, END_HEIGHT, random),
                "a chunk in the void ring");
        Predicate<Holder<Biome>> any = b -> true;
        assertFalse(start(generator, voidChunk, any).isPresent(), "no ground, no city");
        Optional<Structure.GenerationStub> pinned = start(StructureRuntime.ground(generator, 96), voidChunk, any);
        assertTrue(pinned.isPresent());
        assertEquals(95, pinned.get().position().getY(), "an End city stands on the ground's top block, one under the first free height");
        assertFalse(start(StructureRuntime.ground(generator, 40), voidChunk, any).isPresent(), "an End city starts at 60 or above");
    }

    @Test
    void pinnedGroundIsSolidBelowItsYAndAirFromIt() {
        ChunkGenerator pinned = StructureRuntime.ground(generator, 96);
        assertEquals(96, pinned.getFirstFreeHeight(5, 5, Heightmap.Types.OCEAN_FLOOR_WG, END_HEIGHT, random));
        assertEquals(95, pinned.getFirstOccupiedHeight(5, 5, Heightmap.Types.WORLD_SURFACE_WG, END_HEIGHT, random));
        var column = pinned.getBaseColumn(5, 5, END_HEIGHT, random);
        assertTrue(column.getBlock(95).is(Blocks.STONE));
        assertTrue(column.getBlock(96).isAir());
        assertEquals(generator.getSeaLevel(), pinned.getSeaLevel());
        assertEquals(255 + 1, StructureRuntime.ground(generator, 900).getBaseHeight(0, 0, Heightmap.Types.WORLD_SURFACE_WG, END_HEIGHT, random));
        assertTrue(StructureRuntime.ground(generator, null) == generator);
    }
}
