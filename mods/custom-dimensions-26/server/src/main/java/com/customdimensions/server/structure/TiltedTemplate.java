package com.customdimensions.server.structure;

import com.customdimensions.core.structure.Age;
import com.customdimensions.core.structure.Assemblies;
import com.customdimensions.core.structure.SetPiecePlan;
import com.customdimensions.core.structure.Tilt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * A vanilla template placed tilted ({@link Tilt}): its blocks after its
 * rotation and mirror, each tilted position taking the nearest block of the
 * level template, kept at the piece's integrity and weathered to its age;
 * its entities moved with the hull, a hanging entity turned to the nearest
 * face and given a solid block behind it so it stays. Writes only inside the
 * chunk being generated.
 */
final class TiltedTemplate {

    /** One template's blocks and entities after its rotation and mirror, relative to its box corner. */
    record Turned(int sx, int sy, int sz, BlockState[] states, CompoundTag[] nbt, List<CompoundTag> entities, List<Vec3> entityPos) {
        int index(int x, int y, int z) {
            return (y * sz + z) * sx + x;
        }
    }

    private TiltedTemplate() {
    }

    static Turned turned(WorldGenLevel level, StructureTemplate template, Rotation rotation, Mirror mirror) {
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation).setMirror(mirror);
        BoundingBox at0 = template.getBoundingBox(settings, BlockPos.ZERO);
        int sx = at0.getXSpan();
        int sy = at0.getYSpan();
        int sz = at0.getZSpan();
        BlockState[] states = new BlockState[sx * sy * sz];
        CompoundTag[] nbt = new CompoundTag[states.length];
        CompoundTag tag = template.save(new CompoundTag());
        ListTag palette = tag.contains("palette") ? tag.getListOrEmpty("palette") : tag.getListOrEmpty("palettes").getListOrEmpty(0);
        List<BlockState> pal = new ArrayList<>();
        var blocks = level.holderLookup(Registries.BLOCK);
        for (int i = 0; i < palette.size(); i++) pal.add(NbtUtils.readBlockState(blocks, palette.getCompoundOrEmpty(i)));
        ListTag list = tag.getListOrEmpty("blocks");
        Turned t = new Turned(sx, sy, sz, states, nbt, new ArrayList<>(), new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag b = list.getCompoundOrEmpty(i);
            ListTag p = b.getListOrEmpty("pos");
            BlockPos local = StructureTemplate.transform(new BlockPos(p.getIntOr(0, 0), p.getIntOr(1, 0), p.getIntOr(2, 0)), mirror, rotation, BlockPos.ZERO)
                    .offset(-at0.minX(), -at0.minY(), -at0.minZ());
            if (local.getX() < 0 || local.getY() < 0 || local.getZ() < 0 || local.getX() >= sx || local.getY() >= sy || local.getZ() >= sz) continue;
            int k = t.index(local.getX(), local.getY(), local.getZ());
            states[k] = pal.get(b.getIntOr("state", 0)).mirror(mirror).rotate(rotation);
            if (b.contains("nbt")) nbt[k] = b.getCompoundOrEmpty("nbt");
        }
        ListTag entities = tag.getListOrEmpty("entities");
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag e = entities.getCompoundOrEmpty(i);
            ListTag p = e.getListOrEmpty("pos");
            Vec3 v = StructureTemplate.transform(new Vec3(p.getDoubleOr(0, 0), p.getDoubleOr(1, 0), p.getDoubleOr(2, 0)), mirror, rotation, BlockPos.ZERO)
                    .add(-at0.minX(), -at0.minY(), -at0.minZ());
            CompoundTag n = e.getCompoundOrEmpty("nbt").copy();
            n.putByte("customdimensions_rotation", (byte) rotation.ordinal());
            n.putByte("customdimensions_mirror", (byte) mirror.ordinal());
            t.entities().add(n);
            t.entityPos().add(v);
        }
        return t;
    }

    /** The tilt's box in the world for a template whose level box corner is {@code corner}. */
    static BoundingBox box(Tilt tilt, BlockPos corner) {
        int[] b = tilt.bounds();
        return new BoundingBox(corner.getX() + b[0], corner.getY() + b[1], corner.getZ() + b[2], corner.getX() + b[3], corner.getY() + b[4],
                corner.getZ() + b[5]);
    }

    static void place(WorldGenLevel level, BoundingBox chunkBox, StructureTemplate template, Rotation rotation, Mirror mirror, BlockPos corner,
                      Tilt tilt, double integrity, Age age, boolean burned, long seed) {
        Turned t = turned(level, template, rotation, mirror);
        BoundingBox box = box(tilt, corner);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = Math.max(box.minY(), chunkBox.minY()); y <= Math.min(box.maxY(), chunkBox.maxY()); y++) {
            for (int z = Math.max(box.minZ(), chunkBox.minZ()); z <= Math.min(box.maxZ(), chunkBox.maxZ()); z++) {
                for (int x = Math.max(box.minX(), chunkBox.minX()); x <= Math.min(box.maxX(), chunkBox.maxX()); x++) {
                    int[] src = tilt.source(x - corner.getX(), y - corner.getY(), z - corner.getZ());
                    if (src == null) continue;
                    int k = t.index(src[0], src[1], src[2]);
                    BlockState state = t.states()[k];
                    if (state == null || state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.JIGSAW)) continue;
                    if (state.is(Blocks.STRUCTURE_BLOCK)) continue;
                    if (integrity < 1 && SetPiecePlan.roll(seed, src[0], src[1], src[2], 3) >= integrity) continue;
                    state = weather(state, age, burned, seed, x, y, z);
                    if (state == null) continue;
                    level.setBlock(pos.set(x, y, z), state, 2);
                    CompoundTag nbt = t.nbt()[k];
                    if (nbt != null) {
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be != null) be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), nbt));
                    }
                }
            }
        }
        for (Marker m : markers(t, tilt, corner, rotation.rotate(Direction.SOUTH), chunkBox)) {
            marker(level, chunkBox, m.metadata(), m.pos(), m.below(), m.facing(), seed);
        }
        for (int i = 0; i < t.entities().size(); i++) entity(level, chunkBox, t.entities().get(i), t.entityPos().get(i), tilt, corner);
    }

    /** A data marker as placed: its metadata, its tilted position, the block below it, and the way a frame there faces. */
    record Marker(String metadata, BlockPos pos, BlockPos below, Direction facing) {
        /** The block an elytra frame hangs on (the one behind it), else the marker's own position. */
        BlockPos owner() {
            return metadata.startsWith("Elytra") ? pos.relative(facing.getOpposite()) : pos;
        }
    }

    /**
     * The data markers this chunk places, after its block pass: each at its
     * template position turned by the tilt, so once; and in the chunk that
     * holds its {@link Marker#owner}, so an elytra frame is hung by the chunk
     * that writes the block it hangs on, whichever chunk generates first.
     */
    static List<Marker> markers(Turned t, Tilt tilt, BlockPos corner, Direction south, BoundingBox chunkBox) {
        List<Marker> out = new ArrayList<>();
        Direction facing = facing(tilt, south);
        for (int k = 0; k < t.states().length; k++) {
            BlockState state = t.states()[k];
            CompoundTag nbt = t.nbt()[k];
            if (state == null || !state.is(Blocks.STRUCTURE_BLOCK) || nbt == null || !"DATA".equals(nbt.getStringOr("mode", ""))) continue;
            int x = k % t.sx();
            int z = (k / t.sx()) % t.sz();
            int y = k / (t.sx() * t.sz());
            int[] at = tilt.target(x, y, z);
            int[] below = tilt.target(x, y - 1, z);
            Marker m = new Marker(nbt.getStringOr("metadata", ""), corner.offset(at[0], at[1], at[2]), corner.offset(below[0], below[1], below[2]),
                    facing);
            if (chunkBox.isInside(m.owner())) out.add(m);
        }
        return out;
    }

    /** {@code d} turned by the tilt, to the nearest face. */
    static Direction facing(Tilt tilt, Direction d) {
        double[] o = tilt.turn(0, 0, 0, false);
        double[] f = tilt.turn(d.getStepX(), d.getStepY(), d.getStepZ(), false);
        return Direction.getApproximateNearest(f[0] - o[0], f[1] - o[1], f[2] - o[2]);
    }

    /**
     * Vanilla's data markers in the templates we reuse, as the End city's own
     * pieces read them: {@code Elytra} an item frame holding an elytra facing
     * {@code facing} with a solid block behind it, {@code Chest} the chest
     * below it given the End city's treasure, {@code Sentry} a shulker. The
     * marker itself is air; others are left as air.
     */
    static void marker(WorldGenLevel level, BoundingBox chunkBox, String metadata, BlockPos pos, BlockPos below, Direction facing, long seed) {
        if (chunkBox.isInside(pos)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        if (metadata.startsWith("Elytra")) {
            BlockPos behind = pos.relative(facing.getOpposite());
            if (!level.getBlockState(behind).isSolid()) level.setBlock(behind, Blocks.PURPUR_BLOCK.defaultBlockState(), 2);
            var frame = new net.minecraft.world.entity.decoration.ItemFrame(level.getLevel(), pos, facing);
            frame.setSilent(true);
            frame.setItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ELYTRA), false);
            frame.setSilent(false);
            level.addFreshEntityWithPassengers(frame);
        } else if (metadata.startsWith("Chest")) {
            if (chunkBox.isInside(below) && level.getBlockEntity(below) instanceof net.minecraft.world.RandomizableContainer chest) {
                chest.setLootTable(net.minecraft.resources.ResourceKey.create(Registries.LOOT_TABLE,
                        Identifier.withDefaultNamespace("chests/end_city_treasure")), seed ^ below.asLong());
            }
        } else if (metadata.startsWith("Sentry")) {
            Entity shulker = net.minecraft.world.entity.EntityTypes.SHULKER.create(level.getLevel(), EntitySpawnReason.STRUCTURE);
            if (shulker != null) {
                shulker.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
                level.addFreshEntityWithPassengers(shulker);
            }
        }
    }

    /** A block weathered to its age and burned when the piece is: null when it is gone. */
    static BlockState weather(BlockState state, Age age, boolean burned, long seed, int x, int y, int z) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String to = id;
        if (burned) to = Assemblies.burnBlock(to, SetPiecePlan.roll(seed, x, y, z, 4));
        if (age != null && !to.equals(Age.AIR)) to = age.weather(to, SetPiecePlan.roll(seed, x, y, z, 1), SetPiecePlan.roll(seed, x, y, z, 2));
        if (to.equals(id)) return state;
        if (to.equals(Age.AIR)) return null;
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(to.contains("[") ? to.substring(0, to.indexOf('[')) : to)).orElse(null);
        return block == null ? state : AgeProcessor.copyProperties(state, block.defaultBlockState());
    }

    private static void entity(WorldGenLevel level, BoundingBox chunkBox, CompoundTag source, Vec3 local, Tilt tilt, BlockPos corner) {
        double[] p = tilt.turn(local.x - 0.5, local.y, local.z - 0.5, false);
        Vec3 world = new Vec3(corner.getX() + p[0] + 0.5, corner.getY() + p[1], corner.getZ() + p[2] + 0.5);
        BlockPos block = BlockPos.containing(world);
        if (!chunkBox.isInside(block)) return;
        CompoundTag tag = source.copy();
        Rotation rotation = Rotation.values()[tag.getByteOr("customdimensions_rotation", (byte) 0)];
        Mirror mirror = Mirror.values()[tag.getByteOr("customdimensions_mirror", (byte) 0)];
        tag.remove("customdimensions_rotation");
        tag.remove("customdimensions_mirror");
        tag.remove("UUID");
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(world.x));
        pos.add(DoubleTag.valueOf(world.y));
        pos.add(DoubleTag.valueOf(world.z));
        tag.put("Pos", pos);
        if (tag.contains("Facing")) {
            Direction facing = tag.read("Facing", Direction.LEGACY_ID_CODEC).orElse(Direction.NORTH);
            facing = rotation.rotate(mirror.mirror(facing));
            double[] o = tilt.turn(0, 0, 0, false);
            double[] f = tilt.turn(facing.getStepX(), facing.getStepY(), facing.getStepZ(), false);
            Direction tilted = Direction.getApproximateNearest(f[0] - o[0], f[1] - o[1], f[2] - o[2]);
            tag.store("Facing", Direction.LEGACY_ID_CODEC, tilted);
            BlockPos behind = block.relative(tilted.getOpposite());
            if (chunkBox.isInside(behind) && !level.getBlockState(behind).isSolid()) level.setBlock(behind, Blocks.PURPUR_BLOCK.defaultBlockState(), 2);
            if (!level.getBlockState(block).isAir()) level.setBlock(block, Blocks.AIR.defaultBlockState(), 2);
        }
        if (tag.contains("block_pos")) tag.store("block_pos", BlockPos.CODEC, block);
        var created = EntityType.create(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), level.getLevel(),
                new EntitySpawnRequest(EntitySpawnReason.STRUCTURE, false));
        if (created.isEmpty()) {
            com.customdimensions.server.CustomDimensions.LOG.warn("tilted template entity {} at {} was not created", tag.getStringOr("id", "?"), block.toShortString());
            return;
        }
        Entity entity = created.get();
        if (!(entity instanceof net.minecraft.world.entity.decoration.HangingEntity)) {
            entity.snapTo(world.x, world.y, world.z, entity.rotate(rotation) + entity.mirror(mirror) - entity.getYRot(), entity.getXRot());
        }
        level.addFreshEntityWithPassengers(entity);
    }
}
