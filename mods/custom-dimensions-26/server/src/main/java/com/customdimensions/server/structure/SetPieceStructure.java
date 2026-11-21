package com.customdimensions.server.structure;

import com.customdimensions.core.structure.SetPiecePlan;
import com.customdimensions.core.structure.SetPieceSpec;
import com.customdimensions.server.CustomDimensions;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Our structure type {@code customdimensions:set_piece} (places/set-pieces.md):
 * a set piece's spec carried in the structure, its origin found by its seat
 * from the level's base columns as vanilla's {@code nether_fossil} and
 * {@code ruined_portal} sample theirs, and its elements drawn by
 * {@link SetPiecePart}s. A start whose seat finds no ground declines and the
 * log names why, once per structure and chunk.
 */
public final class SetPieceStructure extends Structure {

    public static final MapCodec<SetPieceStructure> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            settingsCodec(i),
            Codec.PASSTHROUGH.fieldOf("set_piece").forGetter(s -> new Dynamic<>(JsonOps.INSTANCE, s.json))
    ).apply(i, (settings, dynamic) -> new SetPieceStructure(settings, dynamic.convert(JsonOps.INSTANCE).getValue().getAsJsonObject())));

    public static final StructureType<SetPieceStructure> TYPE = () -> CODEC;
    /** The lowest blocks of the world that count as void, not ground. */
    static final int VOID_BAND = 8;

    /** Why each declined start declined, by structure key and chunk, for the self-test and the log. */
    private static final Map<String, String> DECLINED = new ConcurrentHashMap<>();

    private final JsonObject json;
    private final SetPieceSpec spec;

    public SetPieceStructure(StructureSettings settings, JsonObject json) {
        super(settings);
        this.json = json;
        this.spec = SetPieceSpec.fromJson(json);
    }

    public static void register() {
        Registry.register(BuiltInRegistries.STRUCTURE_TYPE, Identifier.fromNamespaceAndPath(CustomDimensions.ID, "set_piece"), TYPE);
        Registry.register(BuiltInRegistries.STRUCTURE_PIECE, Identifier.fromNamespaceAndPath(CustomDimensions.ID, "set_piece_part"),
                SetPiecePart.TYPE);
    }

    public SetPieceSpec spec() {
        return spec;
    }

    /** Whether the spec names no biomes, so it stands in any biome of its level. */
    public boolean anyBiome() {
        return spec.site().biomes().isEmpty();
    }

    /** The reasons starts declined, by {@code <key> <chunkX>,<chunkZ>}. */
    public static Map<String, String> declined() {
        return Map.copyOf(DECLINED);
    }

    @Override
    public StructureType<?> type() {
        return TYPE;
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        ChunkPos chunk = context.chunkPos();
        int x = chunk.getMiddleBlockX();
        int z = chunk.getMiddleBlockZ();
        Seat seat = seat(spec, context.chunkGenerator(), context.heightAccessor(), context.randomState(), x, z);
        if (seat.y() == null) {
            if (DECLINED.putIfAbsent(spec.key() + " " + chunk.x() + "," + chunk.z(), seat.why()) == null) {
                CustomDimensions.LOG.info("set piece {} at chunk {}, {} declined: {}", spec.key(), chunk.x(), chunk.z(), seat.why());
            }
            return Optional.empty();
        }
        int rotation = context.random().nextInt(4);
        long seed = context.random().nextLong();
        BlockPos origin = new BlockPos(x, seat.y(), z);
        return Optional.of(new GenerationStub(origin, builder -> pieces(builder, context, origin, seed, rotation)));
    }

    private void pieces(StructurePiecesBuilder builder, GenerationContext context, BlockPos origin, long seed, int rotation) {
        List<SetPiecePlan.Placement> plan = SetPiecePlan.plan(spec, seed, rotation);
        List<SetPiecePart.Item> items = new ArrayList<>();
        for (SetPiecePlan.Placement p : plan) {
            int x = origin.getX() + p.dx();
            int z = origin.getZ() + p.dz();
            Integer ground = groundAt(spec, context.chunkGenerator(), context.heightAccessor(), context.randomState(), x, z, origin.getY());
            if (ground == null) continue;
            int y = ground + p.dy();
            if ("template".equals(p.kind())) {
                SetPiecePart.template(context.structureTemplateManager(), spec, origin, p, x, y, z).ifPresent(builder::addPiece);
            } else {
                items.add(SetPiecePart.Item.of(p, x, y, z));
            }
        }
        if (!items.isEmpty()) builder.addPiece(SetPiecePart.blocks(spec, origin, items));
    }

    /** An origin height, or null and why not. */
    record Seat(Integer y, String why) {}

    /** The origin's height under {@code spec}'s seat at ({@code x}, {@code z}), from the nine footprint samples. */
    static Seat seat(SetPieceSpec spec, ChunkGenerator generator, LevelHeightAccessor height, RandomState random, int x, int z) {
        if ("pinned".equals(spec.seat())) {
            return new Seat(generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random), null);
        }
        int r = Math.min(spec.size(), 32);
        int found = 0;
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        Integer centre = null;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Integer y = "floor".equals(spec.seat()) ? floor(spec, generator, height, random, x + dx * r, z + dz * r, null)
                        : surface(generator, height, random, x + dx * r, z + dz * r);
                if (y == null) continue;
                found++;
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
                if (dx == 0 && dz == 0) centre = y;
            }
        }
        String what = "floor".equals(spec.seat()) ? "a floor with " + spec.clear() + " blocks of headroom in its band" : "ground above the void";
        if (centre == null) return new Seat(null, "no " + what + " under its origin (" + found + " of 9 samples found one)");
        if (found < Math.ceil(spec.support() * 9 - 1e-9)) {
            return new Seat(null, found + " of 9 samples found " + what + ", support " + spec.support() + " needs "
                    + (int) Math.ceil(spec.support() * 9 - 1e-9));
        }
        if (spec.site().slope() != null && hi - lo > spec.site().slope()) {
            return new Seat(null, "its footprint spreads " + (hi - lo) + " blocks, more than its slope " + spec.site().slope());
        }
        return new Seat(centre, null);
    }

    /** The seat height an element finds at a column: the surface, the floor nearest the origin, or the pinned height. */
    static Integer groundAt(SetPieceSpec spec, ChunkGenerator generator, LevelHeightAccessor height, RandomState random, int x, int z,
                            int originY) {
        return switch (spec.seat()) {
            case "pinned" -> originY;
            case "floor" -> floor(spec, generator, height, random, x, z, originY);
            default -> surface(generator, height, random, x, z);
        };
    }

    /** The surface height, or null over the void. */
    static Integer surface(ChunkGenerator generator, LevelHeightAccessor height, RandomState random, int x, int z) {
        int y = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random);
        return y <= height.getMinY() + VOID_BAND ? null : y;
    }

    /**
     * The first air above solid ground with {@code clear} blocks of headroom in
     * the spec's band (default the generator's sea level to the top less 8);
     * the one nearest {@code near} when it is given, else the lowest.
     */
    static Integer floor(SetPieceSpec spec, ChunkGenerator generator, LevelHeightAccessor height, RandomState random, int x, int z,
                         Integer near) {
        int bottom = Math.max(height.getMinY() + 1, spec.bandMin() != null ? spec.bandMin() : generator.getSeaLevel());
        int top = Math.min(height.getMaxY() - spec.clear(), spec.bandMax() != null ? spec.bandMax() : height.getMaxY() - 8);
        NoiseColumn column = generator.getBaseColumn(x, z, height, random);
        Integer best = null;
        for (int y = bottom; y <= top; y++) {
            if (column.getBlock(y - 1).isAir() || !column.getBlock(y - 1).getFluidState().isEmpty()) continue;
            boolean clear = true;
            for (int k = 0; k < spec.clear() && clear; k++) clear = column.getBlock(y + k).isAir();
            if (!clear) continue;
            if (near == null) return y;
            if (best == null || Math.abs(y - near) < Math.abs(best - near)) best = y;
        }
        return best;
    }
}
