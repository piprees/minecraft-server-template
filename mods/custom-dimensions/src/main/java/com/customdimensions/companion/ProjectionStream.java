package com.customdimensions.companion;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.ImmersiveSettings;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.immersive.DestinationGlow;
import com.customdimensions.immersive.ProjectionVolume;
import com.customdimensions.portal.PortalHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the destination description a companion client renders for itself.
 *
 * <p>This is the same projection the fake-block path computes, described
 * instead of painted: the same zone geometry and the same {@code toTarget}
 * mapping, sampled into a dense grid. Two things change because nothing is
 * being written into the source world any more — there is no per-player
 * sightline mask (the client clips against the real aperture, per fragment),
 * and the slab can be deeper and wider than a projection a player could walk
 * into.
 *
 * <p>Chunks are read through {@link PortalHelper#residentChunk} throughout. An
 * unloaded target chunk contributes air, which reads as a hole rather than as
 * a stall.
 */
public final class ProjectionStream {

    /** Multiplier on the configured depth, and the range it is held to. */
    private static final int DEPTH_MULTIPLIER = 2;
    private static final int MIN_DEPTH = 16;
    private static final int MAX_DEPTH = 32;

    /** Extra lateral padding over {@code previewRadius}, and its ceiling. */
    private static final int EXTRA_RADIUS = 2;
    private static final int MAX_RADIUS = 12;

    private ProjectionStream() {}

    public static int depthFor(ImmersiveSettings settings) {
        return Math.max(MIN_DEPTH, Math.min(MAX_DEPTH, settings.previewDepth() * DEPTH_MULTIPLIER));
    }

    public static int radiusFor(ImmersiveSettings settings) {
        return Math.min(MAX_RADIUS, settings.previewRadius() + EXTRA_RADIUS);
    }

    /**
     * Sample one zone's destination into a payload, or null when the zone has
     * no geometry to describe.
     */
    public static CompanionPayloads.Projection build(PortalHelper.PortalZone zone, Direction normal,
            ServerWorld targetWorld, ImmersiveSettings settings,
            ProjectionVolume.TargetMapping mapping, int arrivalY) {
        if (zone == null || zone.interior.isEmpty() || normal == null || targetWorld == null) {
            return null;
        }
        List<BlockPos> cells = ProjectionVolume.computeSourcePositions(
                zone.interior, zone.axis, normal, depthFor(settings), radiusFor(settings));
        if (cells.isEmpty()) {
            return null;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : cells) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;

        int[] states = new int[sizeX * sizeY * sizeZ];
        byte[] light = new byte[states.length];
        int airId = Block.getRawIdFromState(Blocks.AIR.getDefaultState());
        java.util.Arrays.fill(states, airId);

        Map<Long, WorldChunk> chunks = new HashMap<>();
        int bottomY = targetWorld.getBottomY();
        int topY = targetWorld.getTopY();
        BlockPos.Mutable source = new BlockPos.Mutable();
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int y = 0; y < sizeY; y++) {
                    source.set(minX + x, minY + y, minZ + z);
                    BlockPos target = ProjectionVolume.toTarget(source, mapping, arrivalY);
                    if (target.getY() < bottomY || target.getY() >= topY) {
                        continue;
                    }
                    WorldChunk chunk = chunkAt(targetWorld, target, chunks);
                    if (chunk == null) {
                        continue;
                    }
                    int index = ((x * sizeZ) + z) * sizeY + y;
                    states[index] = Block.getRawIdFromState(chunk.getBlockState(target));
                    light[index] = (byte) packLight(targetWorld, target);
                }
            }
        }

        CompanionPayloads.Projection.TintGrid tints =
                new CompanionPayloads.Projection.TintGrid(sizeX, sizeZ);
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                int top = CompanionPayloads.Projection.topSolid(states, sizeY, sizeZ, x, z, airId);
                if (top < 0) {
                    continue;
                }
                source.set(minX + x, minY + top, minZ + z);
                BlockPos target = ProjectionVolume.toTarget(source, mapping, arrivalY);
                Biome biome = biomeAt(targetWorld, target, chunks);
                if (biome != null) {
                    tints.set(x, z,
                            biome.getGrassColorAt(target.getX(), target.getZ()),
                            biome.getFoliageColor(),
                            biome.getWaterColor());
                }
            }
        }

        BlockPos arrival = new BlockPos(mapping.arrivalX(), arrivalY, mapping.arrivalZ());
        Biome biome = biomeAt(targetWorld, arrival, chunks);
        int configuredSky = configuredColour(targetWorld, true);
        int configuredFog = configuredColour(targetWorld, false);
        return new CompanionPayloads.Projection(
                targetWorld.getRegistryKey().getValue(),
                ProjectionVolume.minCorner(zone.interior),
                new ArrayList<>(zone.interior),
                zone.axis.ordinal(),
                normal.ordinal(),
                new BlockPos(minX, minY, minZ),
                sizeX, sizeY, sizeZ,
                states, light,
                DestinationGlow.preferConfigured(configuredSky, biome == null ? -1 : biome.getSkyColor()),
                DestinationGlow.preferConfigured(configuredFog, biome == null ? -1 : biome.getFogColor()),
                tints.palette(), tints.columns(),
                targetWorld.getDimension().ambientLight());
    }


    /**
     * The geometry-only description a client rendering the destination itself
     * gets in place of {@link #build}: the same zone and the same mapping,
     * with no blocks sampled at all.
     *
     * <p>Null when the destination's {@code DimensionType} is not a registered
     * entry. A client cannot stand a world up for one it has no type for, so
     * that portal keeps its block slab however the player set the toggle.
     */
    public static CompanionPayloads.PortalFrame frame(PortalHelper.PortalZone zone,
            Direction normal, ServerWorld targetWorld,
            ProjectionVolume.TargetMapping mapping, int arrivalY) {
        if (zone == null || zone.interior.isEmpty() || normal == null || targetWorld == null) {
            return null;
        }
        Identifier dimensionType = targetWorld.getDimensionEntry().getKey()
                .map(key -> key.getValue()).orElse(null);
        if (dimensionType == null) {
            return null;
        }
        Map<Long, WorldChunk> chunks = new HashMap<>();
        BlockPos arrival = new BlockPos(mapping.arrivalX(), arrivalY, mapping.arrivalZ());
        Biome biome = biomeAt(targetWorld, arrival, chunks);
        int configuredSky = configuredColour(targetWorld, true);
        int configuredFog = configuredColour(targetWorld, false);
        return new CompanionPayloads.PortalFrame(
                targetWorld.getRegistryKey().getValue(),
                dimensionType,
                ProjectionVolume.minCorner(zone.interior),
                new ArrayList<>(zone.interior),
                zone.axis.ordinal(),
                normal.ordinal(),
                mapping.dx(),
                arrivalY - mapping.interiorMinY(),
                mapping.dz(),
                DestinationGlow.preferConfigured(configuredSky, biome == null ? -1 : biome.getSkyColor()),
                DestinationGlow.preferConfigured(configuredFog, biome == null ? -1 : biome.getFogColor()));
    }

    /** True when two payloads describe the same view; skips a redundant send. */
    public static boolean sameContent(CompanionPayloads.Projection a, CompanionPayloads.Projection b) {
        if (a == null || b == null) {
            return false;
        }
        return a.origin().equals(b.origin())
                && a.sizeX() == b.sizeX() && a.sizeY() == b.sizeY() && a.sizeZ() == b.sizeZ()
                && a.normal() == b.normal()
                && java.util.Arrays.equals(a.states(), b.states())
                && java.util.Arrays.equals(a.light(), b.light());
    }

    private static int packLight(ServerWorld world, BlockPos pos) {
        // Reads the lighting provider, which never generates: an unlit or
        // unloaded position answers 0 rather than loading the chunk.
        int sky = world.getLightingProvider().get(LightType.SKY).getLightLevel(pos);
        int block = world.getLightingProvider().get(LightType.BLOCK).getLightLevel(pos);
        return (Math.min(15, sky) << 4) | Math.min(15, block);
    }

    private static WorldChunk chunkAt(ServerWorld world, BlockPos pos, Map<Long, WorldChunk> cache) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        WorldChunk chunk = PortalHelper.residentChunk(world, cx, cz);
        cache.put(key, chunk);
        return chunk;
    }

    /**
     * The destination biome at one position, null when its chunk is not
     * resident. Reads the cache the block sweep already filled, so a column
     * that contributed blocks costs no lookup of its own.
     */
    private static Biome biomeAt(ServerWorld world, BlockPos at, Map<Long, WorldChunk> chunks) {
        WorldChunk chunk = chunkAt(world, at, chunks);
        if (chunk == null) {
            return null;
        }
        RegistryEntry<Biome> entry = chunk.getBiomeForNoiseGen(
                at.getX() >> 2, at.getY() >> 2, at.getZ() >> 2);
        return entry == null ? null : entry.value();
    }

    /**
     * The dimension config's authored sky or fog colour, or -1. These are the
     * two fields {@code DimensionTypeBuilder} parses and drops because no
     * vanilla {@code DimensionType} component can carry them.
     */
    public static int configuredColour(ServerWorld world, boolean sky) {
        DimensionConfig config = MultiverseConfig.getInstance()
                .getCustomDimension(world.getRegistryKey().getValue().getPath());
        if (config == null || config.getEnvironment() == null) {
            return -1;
        }
        String raw = sky ? config.getEnvironment().skyColor : config.getEnvironment().fogColor;
        return parseHex(raw);
    }

    static int parseHex(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        String hex = raw.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.length() != 6) {
            return -1;
        }
        try {
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
