package com.customdimensions.server.structure;

import com.customdimensions.core.structure.ItemSpec;
import com.customdimensions.core.structure.SetPiecePlan;
import com.customdimensions.server.CustomDimensions;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * The things inside a set piece (places/set-pieces.md § Things inside,
 * lore/placement.md G10–G12), each placed whole by the chunk that holds its
 * position: a mob (named, persistent, with its attributes, gear and effects,
 * on foot or held in a minecart or boat), a banner, an armour stand, an item
 * frame, a lectern with its book, a decorated pot, a chest laid out slot by
 * slot beside its loot table, and archaeology (suspicious sand or gravel with
 * a loot table). A thing at one position is placed once, as vanilla's swamp
 * hut places its witch. A filled map in a frame is drawn when the frame
 * first loads, on the server thread.
 */
public final class SetPieceThings {

    static final String MAP_MARK = "customdimensions_map";
    static final String LOOT_MARK = "customdimensions_loot";
    private static final Map<ResourceKey<net.minecraft.world.level.Level>, java.util.Set<BlockPos>> ARRANGED = new java.util.concurrent.ConcurrentHashMap<>();
    static final Map<String, EquipmentSlot> SLOTS = Map.of("head", EquipmentSlot.HEAD, "chest", EquipmentSlot.CHEST, "legs", EquipmentSlot.LEGS,
            "feet", EquipmentSlot.FEET, "mainhand", EquipmentSlot.MAINHAND, "offhand", EquipmentSlot.OFFHAND, "body", EquipmentSlot.BODY);

    private SetPieceThings() {
    }

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof ItemFrame frame) drawMap(frame, level);
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                BlockEntity be = chunk.getBlockEntity(pos);
                CustomData data = be == null ? null : be.components().get(DataComponents.CUSTOM_DATA);
                if (data != null && data.copyTag().contains(LOOT_MARK)) {
                    ARRANGED.computeIfAbsent(level.dimension(), k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(pos.immutable());
                }
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(level -> {
            var pending = ARRANGED.remove(level.dimension());
            if (pending != null) pending.forEach(pos -> rollArranged(level, pos));
        });
    }

    /** How far a thing reaches from its position, for its piece's box. */
    static int extent(JsonObject thing) {
        return "archaeology".equals(thing.get("kind").getAsString()) ? (thing.has("radius") ? thing.get("radius").getAsInt() : 3) : 1;
    }

    /** Places {@code thing} if its position is inside {@code chunkBox}; archaeology places the part of its area inside it. */
    static void place(WorldGenLevel level, BoundingBox chunkBox, JsonObject thing) {
        BlockPos pos = new BlockPos(thing.get("x").getAsInt(), thing.get("y").getAsInt(), thing.get("z").getAsInt());
        String kind = thing.get("kind").getAsString();
        if ("archaeology".equals(kind)) {
            archaeology(level, chunkBox, thing, pos);
            return;
        }
        if (!chunkBox.isInside(pos)) return;
        try {
            switch (kind) {
                case "mob" -> mob(level, thing, pos);
                case "stand" -> stand(level, thing, pos);
                case "frame" -> frame(level, thing, pos);
                case "banner" -> banner(level, thing, pos);
                case "lectern" -> lectern(level, thing, pos);
                case "pot" -> pot(level, thing, pos);
                case "chest" -> chest(level, thing, pos);
                default -> CustomDimensions.LOG.warn("set piece thing {} is not placed by this build", kind);
            }
        } catch (RuntimeException e) {
            CustomDimensions.LOG.warn("set piece {} at {} was not placed: {}", kind, pos.toShortString(), e.toString());
        }
    }

    // ---- items ------------------------------------------------------------------------------

    /** The stack an {@link ItemSpec} names, with its components. */
    static ItemStack stack(RegistryAccess access, JsonElement json) {
        ItemSpec spec = ItemSpec.fromJson(json);
        Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(spec.id())).orElse(Items.AIR);
        ItemStack stack = new ItemStack(item, spec.count());
        if (spec.trimPattern() != null) {
            var material = holder(access, Registries.TRIM_MATERIAL, spec.trimMaterial());
            var pattern = holder(access, Registries.TRIM_PATTERN, spec.trimPattern());
            if (material.isPresent() && pattern.isPresent()) stack.set(DataComponents.TRIM, new ArmorTrim(material.get(), pattern.get()));
        }
        if (spec.dye() != null) stack.set(DataComponents.DYED_COLOR, new DyedItemColor(spec.dye()));
        if (!spec.banner().isEmpty()) stack.set(DataComponents.BANNER_PATTERNS, layers(access, spec.banner()));
        if (spec.mapScale() != null) {
            Map<String, MapDecorations.Entry> map = new HashMap<>();
            int i = 0;
            for (ItemSpec.Decoration d : spec.decorations()) {
                var type = BuiltInRegistries.MAP_DECORATION_TYPE.get(Identifier.parse(d.type()));
                if (type.isPresent()) map.put("customdimensions_" + i++, new MapDecorations.Entry(type.get(), d.x(), d.z(), d.rotation()));
            }
            stack.set(DataComponents.MAP_DECORATIONS, new MapDecorations(map));
            CompoundTag mark = new CompoundTag();
            mark.putInt(MAP_MARK, spec.mapScale());
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(mark));
        }
        return stack;
    }

    static BannerPatternLayers layers(RegistryAccess access, List<ItemSpec.Layer> layers) {
        List<BannerPatternLayers.Layer> out = new ArrayList<>();
        for (ItemSpec.Layer l : layers) {
            holder(access, Registries.BANNER_PATTERN, l.pattern()).ifPresent(h -> out.add(new BannerPatternLayers.Layer(h, DyeColor.byName(l.color(), DyeColor.WHITE))));
        }
        return new BannerPatternLayers(out);
    }

    private static <T> Optional<? extends Holder<T>> holder(RegistryAccess access, ResourceKey<? extends net.minecraft.core.Registry<T>> registry, String id) {
        HolderLookup.RegistryLookup<T> lookup = access.lookupOrThrow(registry);
        return lookup.get(ResourceKey.create(registry, Identifier.parse(id)));
    }

    /** A frame's filled map, drawn once on the server thread: centred on the frame, its decorations kept. */
    private static void drawMap(ItemFrame frame, ServerLevel level) {
        ItemStack stack = frame.getItem();
        if (!stack.is(Items.FILLED_MAP) || stack.get(DataComponents.MAP_ID) != null) return;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.copyTag().contains(MAP_MARK)) return;
        int scale = data.copyTag().getIntOr(MAP_MARK, 2);
        ItemStack map = MapItem.create(level, frame.getBlockX(), frame.getBlockZ(), (byte) scale, true, true);
        MapDecorations decorations = stack.get(DataComponents.MAP_DECORATIONS);
        if (decorations != null) map.set(DataComponents.MAP_DECORATIONS, decorations);
        frame.setItem(map, false);
    }

    // ---- the things -------------------------------------------------------------------------

    private static void mob(WorldGenLevel level, JsonObject t, BlockPos pos) {
        Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(qualified(t.get("entity").getAsString())));
        if (type.isEmpty()) return;
        int count = t.has("count") ? Math.max(1, t.get("count").getAsInt()) : 1;
        for (int i = 0; i < count; i++) {
            Entity entity = type.get().create(level.getLevel(), EntitySpawnReason.STRUCTURE);
            if (entity == null) return;
            entity.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
            if (entity instanceof Mob mob) {
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.STRUCTURE, null);
                if (!t.has("persistent") || t.get("persistent").getAsBoolean()) mob.setPersistenceRequired();
            }
            if (entity instanceof LivingEntity living) {
                equip(level.registryAccess(), living, t.has("equipment") ? t.getAsJsonObject("equipment") : null,
                        t.has("drops") && t.get("drops").getAsBoolean());
                attributes(living, t);
                effects(living, t);
            }
            if (t.has("name")) {
                entity.setCustomName(Component.literal(t.get("name").getAsString()));
                entity.setCustomNameVisible(false);
            }
            String holder = t.has("holder") ? t.get("holder").getAsString() : t.has("ride") ? t.get("ride").getAsString() : "floor";
            Entity vehicle = switch (holder) {
                case "minecart" -> net.minecraft.world.entity.EntityTypes.MINECART.create(level.getLevel(), EntitySpawnReason.STRUCTURE);
                case "boat" -> net.minecraft.world.entity.EntityTypes.OAK_BOAT.create(level.getLevel(), EntitySpawnReason.STRUCTURE);
                default -> null;
            };
            if (vehicle != null) {
                vehicle.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
                entity.startRiding(vehicle, true, false);
                level.addFreshEntityWithPassengers(vehicle);
            } else {
                level.addFreshEntityWithPassengers(entity);
            }
        }
    }

    private static void equip(RegistryAccess access, LivingEntity living, JsonObject equipment, boolean drops) {
        if (equipment == null) return;
        boolean silent = living.isSilent();
        living.setSilent(true);
        for (Map.Entry<String, JsonElement> e : equipment.entrySet()) {
            EquipmentSlot slot = SLOTS.get(e.getKey());
            if (slot == null) continue;
            living.setItemSlot(slot, stack(access, e.getValue()));
            if (living instanceof Mob mob) {
                if (drops) mob.setGuaranteedDrop(slot);
            }
        }
        living.setSilent(silent);
    }

    private static void attributes(LivingEntity living, JsonObject t) {
        if (!t.has("attributes")) return;
        for (Map.Entry<String, JsonElement> e : t.getAsJsonObject("attributes").entrySet()) {
            var attribute = BuiltInRegistries.ATTRIBUTE.get(Identifier.parse(qualified(e.getKey())));
            if (attribute.isEmpty()) continue;
            AttributeInstance instance = living.getAttribute(attribute.get());
            if (instance != null) instance.setBaseValue(e.getValue().getAsDouble());
        }
        living.setHealth(living.getMaxHealth());
    }

    private static void effects(LivingEntity living, JsonObject t) {
        if (!t.has("effects")) return;
        for (JsonElement e : t.getAsJsonArray("effects")) {
            JsonObject o = e.getAsJsonObject();
            BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(qualified(o.get("id").getAsString()))).ifPresent(effect -> living.addEffect(
                    new MobEffectInstance(effect, o.has("duration") ? o.get("duration").getAsInt() : MobEffectInstance.INFINITE_DURATION,
                            o.has("amplifier") ? o.get("amplifier").getAsInt() : 0)));
        }
    }

    private static void stand(WorldGenLevel level, JsonObject t, BlockPos pos) {
        ArmorStand stand = new ArmorStand(level.getLevel(), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        stand.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw(t), 0);
        stand.setShowArms(!t.has("arms") || t.get("arms").getAsBoolean());
        stand.setNoBasePlate(t.has("plate") && !t.get("plate").getAsBoolean());
        equip(level.registryAccess(), stand, t.has("equipment") ? t.getAsJsonObject("equipment") : null, false);
        level.addFreshEntityWithPassengers(stand);
    }

    private static void frame(WorldGenLevel level, JsonObject t, BlockPos pos) {
        Direction facing = Direction.byName(t.has("facing") ? t.get("facing").getAsString() : "north");
        if (facing == null) facing = Direction.NORTH;
        BlockPos behind = pos.relative(facing.getOpposite());
        if (!level.getBlockState(behind).isSolid()) {
            state(t.has("behind") ? t.get("behind").getAsString() : "minecraft:cobblestone").ifPresent(s -> level.setBlock(behind, s, 2));
        }
        boolean glow = t.has("glow") && t.get("glow").getAsBoolean();
        ItemFrame frame = glow ? new GlowItemFrame(level.getLevel(), pos, facing) : new ItemFrame(level.getLevel(), pos, facing);
        frame.setSilent(true);
        frame.setItem(stack(level.registryAccess(), t.get("item")), false);
        frame.setSilent(false);
        level.addFreshEntityWithPassengers(frame);
    }

    private static void banner(WorldGenLevel level, JsonObject t, BlockPos pos) {
        String color = t.get("color").getAsString();
        String block = t.has("facing") ? "minecraft:" + color + "_wall_banner[facing=" + t.get("facing").getAsString() + "]"
                : "minecraft:" + color + "_banner[rotation=" + (t.has("turn") ? t.get("turn").getAsInt() : 0) + "]";
        if (!set(level, pos, block)) return;
        if (t.has("patterns") && level.getBlockEntity(pos) instanceof BannerBlockEntity banner) {
            ItemStack item = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("minecraft:" + color + "_banner")));
            item.set(DataComponents.BANNER_PATTERNS, layers(level.registryAccess(),
                    com.customdimensions.core.structure.ItemSpec.layers("banner", t.get("patterns"), (r, i) -> true)));
            banner.applyComponentsFromItemStack(item);
        }
    }

    private static void lectern(WorldGenLevel level, JsonObject t, BlockPos pos) {
        String facing = t.has("facing") ? t.get("facing").getAsString() : "north";
        if (!set(level, pos, "minecraft:lectern[facing=" + facing + ",has_book=true]")) return;
        if (!(level.getBlockEntity(pos) instanceof LecternBlockEntity lectern)) return;
        List<String> lines = lines(t);
        List<Filterable<Component>> pages = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += 4) {
            pages.add(Filterable.passThrough(Component.literal(String.join("\n\n", lines.subList(i, Math.min(lines.size(), i + 4))))));
        }
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        String title = t.has("title") ? t.get("title").getAsString() : "Notes";
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title), "", 0, pages, true));
        lectern.setBook(book);
    }

    /** A book's lines: fixed, or {@code pick} drawn from a pool by the thing's seed, in pool order. */
    static List<String> lines(JsonObject t) {
        List<String> out = new ArrayList<>();
        if (t.has("lines")) {
            t.getAsJsonArray("lines").forEach(e -> out.add(e.getAsString()));
            return out;
        }
        JsonArray pool = t.getAsJsonArray("pool");
        int pick = t.has("pick") ? t.get("pick").getAsInt() : Math.min(2, pool.size());
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) order.add(i);
        SplittableRandom r = new SplittableRandom(t.get("seed").getAsLong());
        for (int i = order.size() - 1; i > 0; i--) java.util.Collections.swap(order, i, r.nextInt(i + 1));
        List<Integer> chosen = new ArrayList<>(order.subList(0, Math.min(pick, order.size())));
        chosen.sort(Integer::compare);
        chosen.forEach(i -> out.add(pool.get(i).getAsString()));
        return out;
    }

    private static void pot(WorldGenLevel level, JsonObject t, BlockPos pos) {
        String facing = t.has("facing") ? t.get("facing").getAsString() : "north";
        if (!set(level, pos, "minecraft:decorated_pot[facing=" + facing + "]")) return;
        if (!(level.getBlockEntity(pos) instanceof DecoratedPotBlockEntity pot)) return;
        if (t.has("sherds")) {
            JsonObject s = t.getAsJsonObject("sherds");
            ItemStack item = new ItemStack(Items.DECORATED_POT);
            item.set(DataComponents.POT_DECORATIONS, new PotDecorations(sherd(s, "back"), sherd(s, "left"), sherd(s, "right"), sherd(s, "front")));
            pot.applyComponentsFromItemStack(item);
        }
        if (t.has("item")) pot.setTheItem(stack(level.registryAccess(), t.get("item")));
        if (t.has("loot")) pot.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(t.get("loot").getAsString())), t.get("seed").getAsLong());
    }

    private static Optional<ItemStackTemplate> sherd(JsonObject s, String side) {
        if (!s.has(side)) return Optional.empty();
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(qualified(s.get(side).getAsString()))).map(ItemStackTemplate::new);
    }

    private static void chest(WorldGenLevel level, JsonObject t, BlockPos pos) {
        String block = "minecraft:" + (t.has("block") ? t.get("block").getAsString() : "chest");
        if (!set(level, pos, block)) return;
        BlockEntity be = level.getBlockEntity(pos);
        boolean loot = t.has("loot") && !t.get("loot").getAsString().isEmpty();
        if (be instanceof Container container && t.has("arrangement")) {
            for (JsonElement e : t.getAsJsonArray("arrangement")) {
                JsonObject s = e.getAsJsonObject();
                int slot = s.get("slot").getAsInt();
                if (slot < container.getContainerSize()) container.setItem(slot, stack(level.registryAccess(), s.get("item")));
            }
            if (loot) {
                CompoundTag mark = new CompoundTag();
                mark.putString(LOOT_MARK, t.get("loot").getAsString());
                mark.putLong(LOOT_MARK + "_seed", t.get("seed").getAsLong());
                be.setComponents(DataComponentMap.builder().set(DataComponents.CUSTOM_DATA, CustomData.of(mark)).build());
            }
        } else if (be instanceof RandomizableContainer container && loot) {
            container.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(t.get("loot").getAsString())), t.get("seed").getAsLong());
        }
    }

    /**
     * An arranged chest's loot, rolled into the slots its arrangement leaves on
     * the server thread once its chunk is loaded: a container saves either its
     * items or its loot table, never both, so the table waits on the block
     * entity as a mark.
     */
    private static void rollArranged(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !(be instanceof Container container)) return;
        CustomData data = be.components().get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.copyTag().contains(LOOT_MARK)) return;
        CompoundTag mark = data.copyTag();
        be.setComponents(DataComponentMap.EMPTY);
        var key = ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(mark.getStringOr(LOOT_MARK, "")));
        net.minecraft.world.level.storage.loot.LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
        table.fill(container, new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, net.minecraft.world.phys.Vec3.atCenterOf(pos))
                .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.CHEST), mark.getLongOr(LOOT_MARK + "_seed", 0L));
        be.setChanged();
    }

    private static void archaeology(WorldGenLevel level, BoundingBox chunkBox, JsonObject t, BlockPos at) {
        int radius = t.has("radius") ? t.get("radius").getAsInt() : 3;
        int depth = t.has("depth") ? t.get("depth").getAsInt() : 2;
        double share = t.has("share") ? t.get("share").getAsDouble() : 0.3;
        String block = t.has("block") ? t.get("block").getAsString() : "auto";
        long seed = t.get("seed").getAsLong();
        ResourceKey<net.minecraft.world.level.storage.loot.LootTable> loot = ResourceKey.create(Registries.LOOT_TABLE,
                Identifier.parse(t.get("loot").getAsString()));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int x = at.getX() + dx;
                int z = at.getZ() + dz;
                int top = at.getY();
                for (int k = 0; k < 6 && level.getBlockState(pos.set(x, top - 1, z)).isAir(); k++) top--;
                for (int d = 1; d <= depth; d++) {
                    pos.set(x, top - d, z);
                    if (!chunkBox.isInside(pos) || SetPiecePlan.roll(seed, x, top - d, z, 21) >= share) continue;
                    BlockState here = level.getBlockState(pos);
                    BlockState to = switch (block) {
                        case "sand" -> here.isAir() ? null : Blocks.SUSPICIOUS_SAND.defaultBlockState();
                        case "gravel" -> here.isAir() ? null : Blocks.SUSPICIOUS_GRAVEL.defaultBlockState();
                        default -> here.is(Blocks.SAND) ? Blocks.SUSPICIOUS_SAND.defaultBlockState()
                                : here.is(Blocks.GRAVEL) ? Blocks.SUSPICIOUS_GRAVEL.defaultBlockState() : null;
                    };
                    if (to == null) continue;
                    level.setBlock(pos, to, 2);
                    if (level.getBlockEntity(pos) instanceof BrushableBlockEntity brush) {
                        brush.setLootTable(loot, seed ^ pos.asLong());
                    }
                }
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static float yaw(JsonObject t) {
        String facing = t.has("facing") ? t.get("facing").getAsString() : "south";
        return switch (facing) {
            case "west" -> 90;
            case "north" -> 180;
            case "east" -> -90;
            default -> 0;
        };
    }

    private static boolean set(WorldGenLevel level, BlockPos pos, String block) {
        Optional<BlockState> s = state(block);
        s.ifPresent(state -> level.setBlock(pos, state, 2));
        return s.isPresent();
    }

    static Optional<BlockState> state(String block) {
        try {
            return Optional.of(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, block, false).blockState());
        } catch (Exception e) {
            CustomDimensions.LOG.warn("set piece block {} does not parse: {}", block, e.getMessage());
            return Optional.empty();
        }
    }

    private static String qualified(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }


}
