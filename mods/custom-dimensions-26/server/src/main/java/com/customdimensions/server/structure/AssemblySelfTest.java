package com.customdimensions.server.structure;

import com.customdimensions.core.structure.Assemblies;
import com.customdimensions.core.structure.SetPiecePlan;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.portal.PortalRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * The {@code setpieces} section's assemblies (places/set-pieces.md § SP4):
 * {@code selftest_assemblies} forces each piece on the End's central island,
 * generates its chunks and reads them back: the crashed ship's tilt and its
 * elytra frame at integrity 1, the enderman held in a minecart in the same
 * block after 200 ticks, the named and buffed golem once, the marker gate's
 * exit zone, the court's things, the burned camp, the wart farm, and the
 * igloo re-skinned through the three re-skin mixins.
 */
final class AssemblySelfTest {

    static final String SLUG = "selftest_assemblies";
    static final int HOLD_TICKS = 200;
    static final Set<Block> HULL = Set.of(Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR, Blocks.PURPUR_STAIRS, Blocks.PURPUR_SLAB, Blocks.END_STONE_BRICKS);

    private final List<String> lines = new ArrayList<>();
    private final List<java.util.function.BooleanSupplier> pending = new ArrayList<>();
    private final Map<String, StructureStart> starts = new HashMap<>();
    private ServerLevel level;

    static CompletableFuture<List<String>> run(MinecraftServer server) {
        LevelManager manager = LevelManager.get().orElse(null);
        if (manager == null) return CompletableFuture.completedFuture(List.of("FAIL setpieces assemblies: the level manager did not start"));
        AssemblySelfTest test = new AssemblySelfTest();
        ServerTickEvents.END_SERVER_TICK.register(s -> test.tick());
        return manager.activate(SLUG).thenApply(LevelManager.Activation::level).thenCompose(level -> {
            test.level = level;
            StructureRuntime.Applied applied = StructureRuntime.applied(level.dimension().identifier().toString()).orElse(null);
            if (applied == null) {
                test.lines.add("FAIL setpieces assemblies: no structure control was applied");
                return CompletableFuture.completedFuture(null);
            }
            List<ChunkPos> chunks = new ArrayList<>();
            for (StructureRuntime.Placed p : applied.placed()) {
                int r = p.structure().endsWith("crash_site") ? 3 : 1;
                for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                    ChunkPos c = new ChunkPos(p.chunkX() + dx, p.chunkZ() + dz);
                    if (!chunks.contains(c)) chunks.add(c);
                }
            }
            chunks.forEach(c -> level.setChunkForced(c.x(), c.z(), true));
            return test.loaded(chunks).thenCompose(v -> test.after(40)).thenCompose(v -> {
                for (StructureRuntime.Placed p : applied.placed()) {
                    Structure structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                            .getValue(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(p.structure())));
                    LevelChunk chunk = level.getChunkSource().getChunkNow(p.chunkX(), p.chunkZ());
                    StructureStart start = chunk == null || structure == null ? null : chunk.getStartForStructure(structure);
                    String name = p.structure().substring(Math.max(p.structure().lastIndexOf('/'), p.structure().indexOf(':')) + 1);
                    if (start == null || !start.isValid()) test.lines.add("FAIL setpieces assemblies " + name + ": no start at chunk " + p.chunkX() + ", " + p.chunkZ());
                    else test.starts.put(name, start);
                }
                test.check("wreck", test::wreck);
                test.check("mob", test::golem);
                test.check("things", test::court);
                test.check("camp burned", test::camp);
                test.check("farm", test::farm);
                test.check("mixin StructureReskinMixin TemplateReskinMixin PieceReskinMixin reskin", test::reskin);
                Map<String, BlockPos> held = test.captives();
                return test.after(HOLD_TICKS).thenRun(() -> {
                    test.check("cell", () -> test.cell(held));
                    test.check("gate marker", test::gate);
                    test.check("frame map", test::frameMap);
                    chunks.forEach(c -> level.setChunkForced(c.x(), c.z(), false));
                });
            });
        }).handle((v, error) -> {
            if (error != null) test.lines.add("FAIL setpieces assemblies: the check chain broke: " + error);
            return List.copyOf(test.lines);
        });
    }

    private void check(String name, java.util.concurrent.Callable<String> c) {
        try {
            lines.add(c.call().replaceFirst("^(PASS|FAIL) ", "$1 setpieces assemblies " + name + ": "));
        } catch (Exception e) {
            lines.add("FAIL setpieces assemblies " + name + ": " + e);
        }
    }

    // ---- the checks -------------------------------------------------------------------------

    private String wreck() {
        StructureStart start = starts.get("crash_site");
        if (start == null) return "FAIL no crash site start";
        for (StructurePiece sp : start.getPieces()) {
            if (!(sp instanceof SetPiecePart part) || !part.data().get("kind").getAsString().equals("template")) continue;
            JsonObject d = part.data();
            BoundingBox box = sp.getBoundingBox();
            int rotation = d.get("rotation").getAsInt();
            int[] axis = (rotation & 1) == 1 ? new int[] {1, 0} : new int[] {0, 1};
            List<double[]> hull = new ArrayList<>();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = box.minY(); y <= box.maxY(); y++) for (int z = box.minZ(); z <= box.maxZ(); z++) for (int x = box.minX(); x <= box.maxX(); x++) {
                if (!HULL.contains(level.getBlockState(pos.set(x, y, z)).getBlock())) continue;
                hull.add(new double[] {x * axis[0] + z * axis[1], y});
            }
            if (hull.size() < 100) return "FAIL the ship placed " + hull.size() + " hull blocks";
            double ms = hull.stream().mapToDouble(h -> h[0]).average().orElse(0);
            double my = hull.stream().mapToDouble(h -> h[1]).average().orElse(0);
            double cov = hull.stream().mapToDouble(h -> (h[0] - ms) * (h[1] - my)).sum();
            double var = hull.stream().mapToDouble(h -> (h[0] - ms) * (h[0] - ms)).sum();
            double slope = cov / var;
            List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, AABB.of(box).inflate(2), f -> f.getItem().is(Items.ELYTRA));
            int anyFrames = level.getEntitiesOfClass(ItemFrame.class, AABB.of(box).inflate(2), f -> true).size();
            int loose = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, AABB.of(box).inflate(4), i -> i.getItem().is(Items.ELYTRA)).size();
            if (frames.isEmpty()) lines.add("INFO setpieces assemblies wreck: " + anyFrames + " frame(s) of any item, " + loose + " loose elytra in the box");
            boolean ok = d.has("tilt") && slope < -0.2 && frames.size() == 1;
            return (ok ? "PASS " : "FAIL ") + "tilt " + (d.has("tilt") ? d.get("tilt").getAsString() + " " + d.get("angle").getAsDouble() : "none")
                    + ": the hull's height falls " + String.format("%.2f", -slope) + " a block toward its bow over " + hull.size()
                    + " hull blocks (the template measures 0.38 tilted 35 degrees, 0.01 level); " + frames.size() + " elytra frame(s) at integrity "
                    + d.get("integrity").getAsDouble()
                    + (frames.isEmpty() ? "" : " at " + frames.getFirst().blockPosition().toShortString());
        }
        return "FAIL the crash site has no template part";
    }

    private Map<String, BlockPos> captives() {
        Map<String, BlockPos> out = new HashMap<>();
        for (JsonObject t : things("prison")) {
            if (!t.get("kind").getAsString().equals("mob")) continue;
            List<Entity> found = entities(t, 3, e -> e.getType() == EntityTypes.ENDERMAN);
            if (!found.isEmpty()) out.put(at(t).toShortString(), found.getFirst().blockPosition());
        }
        return out;
    }

    private String cell(Map<String, BlockPos> held) {
        List<String> parts = new ArrayList<>();
        boolean ok = !held.isEmpty();
        for (JsonObject t : things("prison")) {
            if (!t.get("kind").getAsString().equals("mob")) continue;
            List<Entity> found = entities(t, 3, e -> e.getType() == EntityTypes.ENDERMAN);
            BlockPos before = held.get(at(t).toShortString());
            if (found.isEmpty() || before == null) {
                ok = false;
                parts.add("no enderman at " + at(t).toShortString() + (before == null ? " when first read" : " after " + HOLD_TICKS + " ticks"));
                continue;
            }
            Entity e = found.getFirst();
            boolean riding = e.getVehicle() != null && e.getVehicle().getType() == EntityTypes.MINECART;
            boolean same = e.blockPosition().equals(before);
            ok &= riding && same && e instanceof Mob m && m.isPersistenceRequired();
            parts.add("enderman at " + before.toShortString() + " then " + e.blockPosition().toShortString() + " after " + HOLD_TICKS + " ticks, "
                    + (riding ? "riding a minecart" : "not riding") + (e instanceof Mob m && m.isPersistenceRequired() ? ", persistent" : ", not persistent"));
        }
        return (ok ? "PASS " : "FAIL ") + String.join("; ", parts);
    }

    private String golem() {
        List<String> parts = new ArrayList<>();
        boolean ok = true;
        for (JsonObject t : things("golem")) {
            switch (t.get("kind").getAsString()) {
                case "mob" -> {
                    List<Entity> found = entities(t, 8, e -> e.getType() == EntityTypes.IRON_GOLEM);
                    LivingEntity g = found.isEmpty() ? null : (LivingEntity) found.getFirst();
                    String name = g == null || g.getCustomName() == null ? "none" : g.getCustomName().getString();
                    boolean persistent = g instanceof Mob m && m.isPersistenceRequired();
                    double health = g == null ? 0 : g.getMaxHealth();
                    double scale = g == null ? 0 : g.getAttributeValue(Attributes.SCALE);
                    ok &= found.size() == 1 && name.equals("Old Iron") && persistent && health == 200 && scale == 1.5;
                    parts.add(found.size() + " iron golem(s), named " + name + (persistent ? ", persistent" : ", not persistent") + ", max health "
                            + health + ", scale " + scale + ", effects " + (g == null ? 0 : g.getActiveEffects().size()));
                }
                case "chest" -> {
                    var be = level.getBlockEntity(at(t));
                    ItemStack slot = be instanceof Container c ? c.getItem(13) : ItemStack.EMPTY;
                    int rolled = filled(be) - (slot.isEmpty() ? 0 : 1);
                    ok &= slot.is(Items.IRON_BLOCK) && slot.getCount() == 4 && rolled > 0;
                    parts.add("chest beside it: slot 13 " + slot + ", " + rolled + " other slot(s) of rolled loot");
                }
                default -> {
                }
            }
        }
        return (ok && !parts.isEmpty() ? "PASS " : "FAIL ") + String.join("; ", parts);
    }

    private String gate() {
        StructureStart start = starts.get("gateway");
        if (start == null) return "FAIL no gateway start";
        BlockPos beacon = null;
        for (StructurePiece sp : start.getPieces()) {
            if (!(sp instanceof SetPiecePart part) || !part.data().get("kind").getAsString().equals("blocks")) continue;
            for (JsonElement e : part.data().getAsJsonArray("items")) {
                JsonObject i = e.getAsJsonObject();
                if (i.get("block").getAsString().equals("minecraft:beacon")) beacon = new BlockPos(i.get("x").getAsInt(), i.get("y").getAsInt(), i.get("z").getAsInt());
            }
        }
        if (beacon == null) return "FAIL the gate placed no beacon";
        var zone = PortalRegistry.get().zoneAt(level.dimension(), beacon.above(2));
        String frame = BuiltInRegistries.BLOCK.getKey(level.getBlockState(beacon.above()).getBlock()).toString();
        return (zone != null ? "PASS " : "FAIL ") + "beacon at " + beacon.toShortString() + " (" + BuiltInRegistries.BLOCK.getKey(level.getBlockState(beacon).getBlock())
                + "), frame " + frame + ", exit zone " + (zone == null ? "not registered" : "registered");
    }

    private String court() {
        List<String> parts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (JsonObject t : things("court")) {
            BlockPos p = at(t);
            switch (t.get("kind").getAsString()) {
                case "banner" -> {
                    var layers = level.getBlockEntity(p) instanceof BannerBlockEntity b ? b.getPatterns().layers() : List.<net.minecraft.world.level.block.entity.BannerPatternLayers.Layer>of();
                    boolean has = layers.stream().anyMatch(l -> l.pattern().is(Identifier.parse("minecraft:piglin")) && l.color().getName().equals("yellow"));
                    parts.add("banner " + level.getBlockState(p).getBlock().getName().getString() + " with " + layers.size() + " layer(s)");
                    if (!has) problems.add("the banner lacks its yellow piglin layer");
                }
                case "stand" -> {
                    List<Entity> found = entities(t, 2, e -> e instanceof ArmorStand);
                    ItemStack head = found.isEmpty() ? ItemStack.EMPTY : ((ArmorStand) found.getFirst()).getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
                    ItemStack chest = found.isEmpty() ? ItemStack.EMPTY : ((ArmorStand) found.getFirst()).getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
                    var trim = head.get(DataComponents.TRIM);
                    var dye = chest.get(DataComponents.DYED_COLOR);
                    parts.add("stand with " + head.getItem() + " trimmed " + (trim == null ? "none" : trim.pattern().getRegisteredName() + " in " + trim.material().getRegisteredName())
                            + ", " + chest.getItem() + " dyed " + (dye == null ? "none" : Integer.toHexString(dye.rgb())));
                    if (trim == null || !trim.pattern().is(Identifier.parse("minecraft:snout"))) problems.add("the stand's helmet lacks its snout trim");
                    if (t.has("equipment") && t.getAsJsonObject("equipment").has("chest") && (dye == null || dye.rgb() != 0x3355AA)) {
                        problems.add("the stand's coat is not dyed 3355aa");
                    }
                }
                case "lectern" -> {
                    ItemStack book = level.getBlockEntity(p) instanceof LecternBlockEntity l ? l.getBook() : ItemStack.EMPTY;
                    var content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
                    String text = content == null ? "" : content.pages().getFirst().raw().getString();
                    parts.add("lectern with " + (content == null ? "no book" : content.pages().size() + " page(s): " + text.replace("\n\n", " / ")));
                    if (!text.contains("will not wake")) problems.add("the lectern's book lacks its lines");
                }
                case "pot" -> {
                    if (!(level.getBlockEntity(p) instanceof DecoratedPotBlockEntity pot)) {
                        problems.add("no pot at " + p.toShortString());
                        continue;
                    }
                    parts.add("pot with front " + pot.getDecorations().front().map(Object::toString).orElse("none") + ", holding " + pot.getTheItem());
                    if (pot.getDecorations().front().isEmpty() || !pot.getTheItem().is(Items.ENDER_PEARL) || pot.getTheItem().getCount() != 3) {
                        problems.add("the pot lacks its sherd or its pearls");
                    }
                }
                case "chest" -> {
                    var be = level.getBlockEntity(p);
                    ItemStack first = be instanceof Container c ? c.getItem(0) : ItemStack.EMPTY;
                    ItemStack fifth = be instanceof Container c ? c.getItem(4) : ItemStack.EMPTY;
                    int rolled = filled(be) - 3;
                    parts.add("chest laid out: slot 0 " + first + ", slot 4 " + fifth + ", " + rolled + " slot(s) of rolled loot");
                    if (!first.is(Items.IRON_PICKAXE) || !fifth.is(Items.CANDLE) || rolled <= 0) problems.add("the chest's arrangement or loot is missing");
                }
                case "archaeology" -> {
                    int found = 0;
                    int withLoot = 0;
                    for (BlockPos q : BlockPos.betweenClosed(p.offset(-3, -4, -3), p.offset(3, 1, 3))) {
                        if (!level.getBlockState(q).is(Blocks.SUSPICIOUS_GRAVEL)) continue;
                        found++;
                        if (level.getBlockEntity(q) instanceof BrushableBlockEntity) withLoot++;
                    }
                    parts.add(found + " suspicious gravel (" + withLoot + " with a loot table)");
                    if (found == 0 || withLoot != found) problems.add("archaeology laid no suspicious gravel with loot");
                }
                default -> {
                }
            }
        }
        StructureStart start = starts.get("court");
        int bones = 0;
        if (start != null) for (StructurePiece sp : start.getPieces()) bones += count(sp.getBoundingBox(), s -> s == Blocks.BONE_BLOCK || s == Blocks.SKELETON_SKULL);
        parts.add("remains: " + bones + " bone and skull block(s)");
        if (bones < 2) problems.add("the remains placed no bones and skull");
        return (problems.isEmpty() ? "PASS " : "FAIL ") + String.join("; ", parts) + (problems.isEmpty() ? "" : "; problems: " + problems);
    }

    private String camp() {
        StructureStart start = starts.get("burned_camp");
        if (start == null) return "FAIL no burned camp start";
        int wood = 0;
        int burned = 0;
        int tents = 0;
        int cloth = 0;
        for (StructurePiece sp : start.getPieces()) {
            if (!(sp instanceof SetPiecePart part)) continue;
            JsonObject d = part.data();
            if (d.get("kind").getAsString().equals("template")) {
                tents++;
                cloth += count(sp.getBoundingBox(), b -> BuiltInRegistries.BLOCK.getKey(b).getPath().endsWith("_wool"));
                wood += count(sp.getBoundingBox(), b -> Assemblies.wooden(BuiltInRegistries.BLOCK.getKey(b).toString()));
            } else if (d.get("kind").getAsString().equals("blocks")) {
                for (JsonElement e : d.getAsJsonArray("items")) {
                    String b = e.getAsJsonObject().get("block").getAsString();
                    String id = b.contains("[") ? b.substring(0, b.indexOf('[')) : b;
                    if (Assemblies.wooden(id)) wood++;
                    if (Assemblies.BURNED.contains(id)) burned++;
                }
            }
        }
        boolean ok = wood == 0 && cloth == 0 && burned > 0 && tents > 0;
        return (ok ? "PASS " : "FAIL ") + tents + " tent(s), " + burned + " burned block(s), " + wood + " wooden and " + cloth + " wool block(s) left";
    }

    private String farm() {
        StructureStart start = starts.get("wart_farm");
        if (start == null) return "FAIL no farm start";
        int crops = 0;
        int held = 0;
        for (StructurePiece sp : start.getPieces()) {
            BoundingBox box = sp.getBoundingBox();
            for (BlockPos q : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
                if (!level.getBlockState(q).is(Blocks.NETHER_WART)) continue;
                crops++;
                if (level.getBlockState(q.below()).is(Blocks.SOUL_SAND)) held++;
            }
        }
        return (crops > 0 && held == crops ? "PASS " : "FAIL ") + crops + " nether wart, " + held + " on soul sand";
    }

    private String reskin() {
        StructureStart start = starts.get("igloo");
        if (start == null) return "FAIL no igloo start";
        int snow = 0;
        int bricks = 0;
        int wool = 0;
        for (StructurePiece sp : start.getPieces()) {
            snow += count(sp.getBoundingBox(), b -> b == Blocks.SNOW_BLOCK);
            bricks += count(sp.getBoundingBox(), b -> b == Blocks.END_STONE_BRICKS);
            wool += count(sp.getBoundingBox(), b -> BuiltInRegistries.BLOCK.getKey(b).getPath().endsWith("_wool"));
        }
        int rules = StructureReskin.rules(level.dimension().identifier().toString()).size();
        boolean ok = snow == 0 && wool == 0 && bricks > 0 && rules == 1;
        return (ok ? "PASS " : "FAIL ") + rules + " rule(s); igloo has " + bricks + " end stone bricks, " + snow + " snow blocks and " + wool + " wool left";
    }

    private String frameMap() {
        for (JsonObject t : things("court")) {
            if (!t.get("kind").getAsString().equals("frame")) continue;
            List<Entity> found = entities(t, 2, e -> e instanceof ItemFrame);
            ItemStack item = found.isEmpty() ? ItemStack.EMPTY : ((ItemFrame) found.getFirst()).getItem();
            var id = item.get(DataComponents.MAP_ID);
            var decorations = item.get(DataComponents.MAP_DECORATIONS);
            boolean ok = item.is(Items.FILLED_MAP) && id != null && decorations != null && !decorations.decorations().isEmpty();
            return (ok ? "PASS " : "FAIL ") + found.size() + " frame(s) holding " + item + ", map id " + id + ", "
                    + (decorations == null ? 0 : decorations.decorations().size()) + " decoration(s)";
        }
        return "FAIL the court has no frame";
    }

    // ---- helpers ----------------------------------------------------------------------------

    private List<JsonObject> things(String piece) {
        List<JsonObject> out = new ArrayList<>();
        StructureStart start = starts.get(piece);
        if (start == null) return out;
        for (StructurePiece sp : start.getPieces()) {
            if (sp instanceof SetPiecePart part && part.data().get("kind").getAsString().equals("things")) {
                part.data().getAsJsonArray("things").forEach(e -> out.add(e.getAsJsonObject()));
            }
        }
        return out;
    }

    private static int filled(net.minecraft.world.level.block.entity.BlockEntity be) {
        if (!(be instanceof Container c)) return 0;
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) n++;
        return n;
    }

    private static BlockPos at(JsonObject t) {
        return new BlockPos(t.get("x").getAsInt(), t.get("y").getAsInt(), t.get("z").getAsInt());
    }

    private List<Entity> entities(JsonObject t, int r, Predicate<Entity> which) {
        return level.getEntities((Entity) null, new AABB(at(t)).inflate(r), which);
    }

    private int count(BoundingBox box, Predicate<Block> which) {
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            if (level.getChunkSource().getChunkNow(q.getX() >> 4, q.getZ() >> 4) == null) continue;
            if (which.test(level.getBlockState(q).getBlock())) n++;
        }
        return n;
    }

    private CompletableFuture<Void> loaded(List<ChunkPos> chunks) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        int[] ticks = {0};
        pending.add(() -> {
            int got = 0;
            for (ChunkPos c : chunks) if (level.getChunkSource().getChunkNow(c.x(), c.z()) != null) got++;
            if (got == chunks.size()) {
                done.complete(null);
                return true;
            }
            if (++ticks[0] >= SetPieceSelfTest.LOAD_CAP_TICKS) {
                done.completeExceptionally(new IllegalStateException(got + "/" + chunks.size() + " chunks loaded after " + SetPieceSelfTest.LOAD_CAP_TICKS + " ticks"));
                return true;
            }
            return false;
        });
        return done;
    }

    private CompletableFuture<Void> after(int ticks) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        int[] left = {ticks};
        pending.add(() -> {
            if (--left[0] > 0) return false;
            done.complete(null);
            return true;
        });
        return done;
    }

    private void tick() {
        for (java.util.function.BooleanSupplier p : List.copyOf(pending)) {
            if (p.getAsBoolean()) pending.remove(p);
        }
    }
}
