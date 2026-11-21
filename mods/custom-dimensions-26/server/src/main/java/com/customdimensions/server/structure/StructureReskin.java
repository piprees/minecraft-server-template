package com.customdimensions.server.structure;

import com.customdimensions.compiler.datapack.DatapackCompiler;
import com.customdimensions.compiler.datapack.StructuresCompiler;
import com.customdimensions.core.structure.Age;
import com.customdimensions.core.structure.Reskin;
import com.customdimensions.core.structure.SetPiecePlan;
import com.customdimensions.server.CustomDimensions;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A level's {@code structures.reskin} (lore/placement.md G13) applied to the
 * structures it names as they place: every template block (through
 * {@code StructureTemplate.processBlockInfos}) and every block a hard-coded
 * piece places (through {@code StructurePiece.placeBlock}) inside a start of
 * a matching structure is swapped and weathered. The start being placed is
 * held per thread from {@code StructureStart.placeInChunk}.
 */
public final class StructureReskin {

    private static volatile Map<String, List<Reskin>> rules = Map.of();
    private static final ThreadLocal<List<Reskin>> CURRENT = new ThreadLocal<>();
    private static final Map<String, TagKey<Block>> TAGS = new ConcurrentHashMap<>();

    private StructureReskin() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> rules = load(server));
    }

    static Map<String, List<Reskin>> load(MinecraftServer server) {
        Map<String, List<Reskin>> out = new HashMap<>();
        Map<Identifier, Resource> files = server.getResourceManager().listResources(StructuresCompiler.FOLDER,
                id -> id.getNamespace().equals(DatapackCompiler.RUNTIME_NAMESPACE) && id.getPath().endsWith(".json"));
        for (Map.Entry<Identifier, Resource> file : files.entrySet()) {
            try (Reader reader = file.getValue().openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                if (!json.has("reskin") || !json.has("level")) continue;
                List<Reskin> list = new ArrayList<>();
                for (JsonElement e : json.getAsJsonArray("reskin")) list.add(Reskin.fromJson(e.getAsJsonObject()));
                out.put(json.get("level").getAsString(), List.copyOf(list));
            } catch (Exception e) {
                CustomDimensions.LOG.warn("structure control file {}: its reskin is unreadable: {}", file.getKey(), e.toString());
            }
        }
        if (!out.isEmpty()) CustomDimensions.LOG.info("structure reskin: {} level(s), {} rule(s)", out.size(),
                out.values().stream().mapToInt(List::size).sum());
        return Map.copyOf(out);
    }

    /** The rules of {@code level} for one level id, for the self-test. */
    public static List<Reskin> rules(String level) {
        return rules.getOrDefault(level, List.of());
    }

    /** Holds the rules for {@code structure} in {@code level} while its start places. */
    public static void begin(WorldGenLevel level, Structure structure) {
        List<Reskin> all = rules.get(level.getLevel().dimension().identifier().toString());
        if (all == null || all.isEmpty()) {
            CURRENT.remove();
            return;
        }
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Holder<Structure> holder = registry.wrapAsHolder(structure);
        String id = holder.unwrapKey().map(k -> k.identifier().toString()).orElse("");
        String group = StructureRuntime.groupOf(level.getLevel().dimension(), structure);
        List<Reskin> matched = new ArrayList<>();
        for (Reskin r : all) {
            String t = r.target();
            boolean hit = t.startsWith("#") ? holder.is(TagKey.create(Registries.STRUCTURE, Identifier.parse(t.substring(1))))
                    : t.contains(":") ? t.equals(id) : t.equals(group);
            if (hit) matched.add(r);
        }
        if (matched.isEmpty()) CURRENT.remove();
        else CURRENT.set(List.copyOf(matched));
    }

    public static void end() {
        CURRENT.remove();
    }

    /** Whether a start with re-skin rules is placing on this thread. */
    public static boolean active() {
        return CURRENT.get() != null;
    }

    /** {@code state} at {@code pos} re-skinned by the held rules, in order; {@code state} itself when none apply. */
    public static BlockState apply(BlockState state, BlockPos pos) {
        List<Reskin> held = CURRENT.get();
        if (held == null || state.isAir()) return state;
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String to = id;
        for (Reskin r : held) {
            to = r.apply(to, (tag, block) -> state.is(TAGS.computeIfAbsent(tag, t -> TagKey.create(Registries.BLOCK, Identifier.parse(t)))),
                    SetPiecePlan.roll(31, pos.getX(), pos.getY(), pos.getZ(), 1), SetPiecePlan.roll(31, pos.getX(), pos.getY(), pos.getZ(), 2));
        }
        if (to.equals(id)) return state;
        if (to.equals(Age.AIR)) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(to)).orElse(null);
        return block == null ? state : AgeProcessor.copyProperties(state, block.defaultBlockState());
    }
}
