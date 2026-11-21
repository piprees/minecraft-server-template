package com.customdimensions.server.structure;

import com.customdimensions.core.structure.Age;
import com.customdimensions.core.structure.SetPiecePlan;
import com.customdimensions.core.structure.SetPieceSpec;
import com.customdimensions.server.CustomDimensions;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockRotProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Our piece type {@code customdimensions:set_piece_part}: one vanilla template
 * of a set piece, placed whole with its rotation, mirror, integrity,
 * processors and age; or the set piece's blocks, chests and spawners at their
 * resolved positions. Each writes only inside the chunk being generated, and
 * everything it writes follows from its saved data, so a piece split across
 * chunks is continuous.
 */
public final class SetPiecePart extends StructurePiece {

    public static final StructurePieceType TYPE = SetPiecePart::new;

    /** One block, chest or spawner at its position, its block already weathered. */
    record Item(String kind, int x, int y, int z, String block, String loot, String entity, int delay, int range, int count, long seed) {
        static Item of(SetPiecePlan.Placement p, int x, int y, int z) {
            SetPieceSpec.Element e = p.source();
            return new Item(p.kind(), x, y, z, SetPiecePlan.blockAt(p, x, y, z), e.string("loot", null), e.string("entity", null),
                    e.integer("delay", 0), e.integer("range", 0), "spawner".equals(p.kind()) ? e.integer("count", 0) : 0, p.seed());
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("kind", kind);
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            o.addProperty("block", block);
            if (loot != null) o.addProperty("loot", loot);
            if (entity != null) o.addProperty("entity", entity);
            if (delay > 0) o.addProperty("delay", delay);
            if (range > 0) o.addProperty("range", range);
            if (count > 0) o.addProperty("count", count);
            o.addProperty("seed", seed);
            return o;
        }

        static Item fromJson(JsonObject o) {
            return new Item(o.get("kind").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                    o.get("block").getAsString(), o.has("loot") ? o.get("loot").getAsString() : null,
                    o.has("entity") ? o.get("entity").getAsString() : null, o.has("delay") ? o.get("delay").getAsInt() : 0,
                    o.has("range") ? o.get("range").getAsInt() : 0, o.has("count") ? o.get("count").getAsInt() : 0, o.get("seed").getAsLong());
        }
    }

    private final JsonObject data;
    private final StructureTemplateManager templates;

    private SetPiecePart(JsonObject data, BoundingBox box, StructureTemplateManager templates) {
        super(TYPE, 0, box);
        this.data = data;
        this.templates = templates;
    }

    private SetPiecePart(StructurePieceSerializationContext context, CompoundTag tag) {
        super(TYPE, tag);
        this.data = JsonParser.parseString(tag.getStringOr("SetPiece", "{}")).getAsJsonObject();
        this.templates = context.structureTemplateManager();
    }

    /** What this piece places, as saved: {@code kind} {@code template} or {@code blocks}. */
    public JsonObject data() {
        return data.deepCopy();
    }

    /** A template element centred on ({@code x}, {@code z}), its floor at {@code y} less its embedding; empty when vanilla lacks it. */
    static Optional<SetPiecePart> template(StructureTemplateManager templates, SetPieceSpec spec, BlockPos origin, SetPiecePlan.Placement p,
                                           int x, int y, int z) {
        String id = p.source().string("id", "");
        Optional<StructureTemplate> found = templates.get(Identifier.parse(id));
        if (found.isEmpty()) {
            CustomDimensions.LOG.warn("set piece {}: template {} is not in the game's data; the element is not placed", spec.key(), id);
            return Optional.empty();
        }
        StructureTemplate template = found.get();
        Age age = p.age();
        int embed = p.source().params().has("embed") ? p.source().integer("embed", 0)
                : age == null ? 0 : age.embed(template.getSize().getY(), p.roll());
        Double fixed = p.source().number("integrity");
        double integrity = fixed != null ? fixed : age == null ? 1.0 : age.integrity(SetPiecePlan.roll(p.seed(), 0, 0, 0, 7));
        StructurePlaceSettings settings = settings(p.rotation(), p.mirror());
        BoundingBox at0 = template.getBoundingBox(settings, BlockPos.ZERO);
        BlockPos corner = new BlockPos(x - (at0.minX() + at0.maxX()) / 2, y - embed - at0.minY(), z - (at0.minZ() + at0.maxZ()) / 2);
        BoundingBox box = template.getBoundingBox(settings, corner);
        JsonObject data = new JsonObject();
        data.addProperty("kind", "template");
        data.addProperty("set", spec.key());
        data.addProperty("origin", origin.getY());
        data.addProperty("id", id);
        data.addProperty("rotation", p.rotation());
        data.addProperty("mirror", p.mirror());
        data.addProperty("integrity", integrity);
        if (p.source().params().has("processors")) data.addProperty("processors", p.source().string("processors", ""));
        if (age != null) data.addProperty("age", age.id());
        data.addProperty("seed", p.seed());
        JsonArray c = new JsonArray();
        c.add(corner.getX());
        c.add(corner.getY());
        c.add(corner.getZ());
        data.add("corner", c);
        return Optional.of(new SetPiecePart(data, box, templates));
    }

    /**
     * One shape or approach ({@link com.customdimensions.core.structure.SetPieceShapes}), drawn column by column in each chunk it
     * crosses; {@code scan} finds each column's ground near {@code originY} rather than at the surface.
     */
    static SetPiecePart shape(String key, int originY, com.customdimensions.core.structure.SetPieceShapes.Shape shape, boolean scan) {
        JsonObject data = new JsonObject();
        data.addProperty("kind", "shape");
        data.addProperty("set", key);
        data.addProperty("origin", originY);
        data.addProperty("scan", scan);
        data.add("shape", shape.toJson());
        int[] b = com.customdimensions.core.structure.SetPieceShapes.box(shape);
        return new SetPiecePart(data, new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]), null);
    }

    /** Whether this part draws a shape or an approach, which the terrain round a structure never adapts to. */
    public boolean drawsShape() {
        return "shape".equals(data.get("kind").getAsString());
    }

    /** The set piece's blocks, chests and spawners in one piece. */
    static SetPiecePart blocks(SetPieceSpec spec, BlockPos origin, List<Item> items) {
        JsonObject data = new JsonObject();
        data.addProperty("kind", "blocks");
        data.addProperty("set", spec.key());
        data.addProperty("origin", origin.getY());
        JsonArray a = new JsonArray();
        List<BlockPos> positions = new ArrayList<>();
        for (Item i : items) {
            a.add(i.toJson());
            positions.add(new BlockPos(i.x(), i.y(), i.z()));
        }
        data.add("items", a);
        return new SetPiecePart(data, BoundingBox.encapsulatingPositions(positions).orElseThrow(), null);
    }

    private static StructurePlaceSettings settings(int rotation, int mirror) {
        return new StructurePlaceSettings().setRotation(Rotation.values()[rotation & 3])
                .setMirror(mirror == 1 ? Mirror.LEFT_RIGHT : mirror == 2 ? Mirror.FRONT_BACK : Mirror.NONE);
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putString("SetPiece", data.toString());
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random,
                            BoundingBox chunkBox, ChunkPos chunk, BlockPos pivot) {
        if ("template".equals(data.get("kind").getAsString())) {
            placeTemplate(level, chunkBox);
        } else if (drawsShape()) {
            ShapeWorld world = new ShapeWorld(level, chunkBox, generator, data.get("scan").getAsBoolean(), data.get("origin").getAsInt());
            com.customdimensions.core.structure.SetPieceShapes.draw(com.customdimensions.core.structure.SetPieceShapes.Shape.fromJson(
                    data.getAsJsonObject("shape")), chunkBox.minX(), chunkBox.minZ(), chunkBox.maxX(), chunkBox.maxZ(), world);
        } else {
            for (var e : data.getAsJsonArray("items")) placeItem(level, chunkBox, Item.fromJson(e.getAsJsonObject()));
        }
    }

    private void placeTemplate(WorldGenLevel level, BoundingBox chunkBox) {
        StructureTemplateManager manager = templates != null ? templates : level.getLevel().getServer().getStructureTemplateManager();
        String id = data.get("id").getAsString();
        Optional<StructureTemplate> found = manager.get(Identifier.parse(id));
        if (found.isEmpty()) return;
        JsonArray c = data.getAsJsonArray("corner");
        BlockPos corner = new BlockPos(c.get(0).getAsInt(), c.get(1).getAsInt(), c.get(2).getAsInt());
        long seed = data.get("seed").getAsLong();
        Age age = data.has("age") ? Age.parse(data.get("age").getAsString()) : null;
        StructurePlaceSettings settings = settings(data.get("rotation").getAsInt(), data.get("mirror").getAsInt()).setBoundingBox(chunkBox);
        double integrity = data.get("integrity").getAsDouble();
        if (integrity < 1) settings.addProcessor(new BlockRotProcessor((float) integrity));
        if (data.has("processors")) {
            level.registryAccess().lookupOrThrow(Registries.PROCESSOR_LIST)
                    .get(ResourceKey.create(Registries.PROCESSOR_LIST, Identifier.parse(data.get("processors").getAsString())))
                    .ifPresent(list -> list.value().list().forEach(settings::addProcessor));
        }
        if (age != null) settings.addProcessor(new AgeProcessor(age, seed));
        found.get().placeInWorld(level, corner, corner, settings, RandomSource.create(seed), 2);
        if (age != null && (age.cobweb > 0 || age.vine > 0)) grow(level, chunkBox, age, seed);
    }

    /** Cobwebs under ceilings and vines on walls, in the air of the template's box, at the age's shares. */
    private void grow(WorldGenLevel level, BoundingBox chunkBox, Age age, long seed) {
        BoundingBox box = getBoundingBox();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = Math.max(box.minY(), chunkBox.minY()); y <= Math.min(box.maxY(), chunkBox.maxY()); y++) {
            for (int z = Math.max(box.minZ(), chunkBox.minZ()); z <= Math.min(box.maxZ(), chunkBox.maxZ()); z++) {
                for (int x = Math.max(box.minX(), chunkBox.minX()); x <= Math.min(box.maxX(), chunkBox.maxX()); x++) {
                    pos.set(x, y, z);
                    if (!level.getBlockState(pos).isAir()) continue;
                    double roll = SetPiecePlan.roll(seed, x, y, z, 11);
                    if (roll < age.cobweb && level.getBlockState(pos.above()).isSolidRender()) {
                        level.setBlock(pos, Blocks.COBWEB.defaultBlockState(), 2);
                        continue;
                    }
                    if (SetPiecePlan.roll(seed, x, y, z, 12) >= age.vine) continue;
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos wall = pos.relative(d);
                        if (chunkBox.isInside(wall) && level.getBlockState(wall).isFaceSturdy(level, wall, d.getOpposite())) {
                            level.setBlock(pos, Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(d), true), 2);
                            break;
                        }
                    }
                }
            }
        }
    }

    private static void placeItem(WorldGenLevel level, BoundingBox chunkBox, Item item) {
        BlockPos pos = new BlockPos(item.x(), item.y(), item.z());
        if (!chunkBox.isInside(pos)) return;
        BlockState state;
        try {
            state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, item.block(), false).blockState();
        } catch (Exception e) {
            CustomDimensions.LOG.warn("set piece block {} does not parse: {}", item.block(), e.getMessage());
            return;
        }
        level.setBlock(pos, state, 2);
        BlockEntity be = level.getBlockEntity(pos);
        if ("chest".equals(item.kind()) && be instanceof RandomizableContainer container && item.loot() != null) {
            container.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(item.loot())), item.seed());
        } else if ("spawner".equals(item.kind()) && be instanceof SpawnerBlockEntity spawner && item.entity() != null) {
            BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(item.entity()))
                    .ifPresent(type -> spawner.setEntityId(type, RandomSource.create(item.seed())));
            var timing = (com.customdimensions.server.mixin.BaseSpawnerAccessor) spawner.getSpawner();
            if (item.delay() > 0) {
                timing.customdimensions$setSpawnDelay(item.delay());
                timing.customdimensions$setMinSpawnDelay(item.delay());
                timing.customdimensions$setMaxSpawnDelay(Math.max(item.delay(), item.delay() * 4));
            }
            if (item.range() > 0) timing.customdimensions$setSpawnRange(item.range());
            if (item.count() > 0) timing.customdimensions$setSpawnCount(item.count());
        }
    }
}
