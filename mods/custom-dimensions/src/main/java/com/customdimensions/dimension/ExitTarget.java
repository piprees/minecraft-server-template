package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.portal.PortalHelper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;

/**
 * A parsed exit target: where "leaving" takes you. Understands the legacy
 * shorthands ("bed" | "worldSpawn" | "origin") AND dimension-link
 * descriptors ({"dimension": "ns:slug", "arrival": "anchor" | "spawn" |
 * [x, y, z]}), making dimensions composable into chains and hubs.
 *
 * Canonical string form (for PortalReturnTarget.exitMode and
 * portal_links.json, which persist strings): shorthands pass through
 * unchanged; descriptors serialise as "dim!ns:slug!arrival" where arrival
 * is "anchor", "spawn", or "x,y,z". parse() inverts canonical strings, so
 * the persistence layer needs no schema change and legacy records keep
 * working.
 *
 * Resolution is never-strand by construction: every branch falls back to
 * the overworld spawn rather than failing, and dimension arrivals surface-
 * resolve through the same PortalHelper machinery as anchors. A resolution
 * against an UNLOADED runtime dimension queues the world load and returns
 * null — callers skip the action this tick and retry (the world loads
 * within a few ticks via the END_SERVER_TICK drain).
 */
public final class ExitTarget {

    public enum Kind { BED, WORLD_SPAWN, ORIGIN, DIMENSION }

    private final Kind kind;
    private final String dimensionId;   // DIMENSION only
    private final String arrival;       // "anchor" | "spawn" | null (explicit pos)
    private final int[] arrivalPos;     // explicit [x, y, z] or null

    private ExitTarget(Kind kind, String dimensionId, String arrival, int[] arrivalPos) {
        this.kind = kind;
        this.dimensionId = dimensionId;
        this.arrival = arrival;
        this.arrivalPos = arrivalPos;
    }

    public Kind getKind() {
        return this.kind;
    }

    public String getDimensionId() {
        return this.dimensionId;
    }

    /** Parse a raw config value: shorthand string, canonical string, or descriptor object. */
    public static ExitTarget parse(JsonElement raw) {
        if (raw == null || raw.isJsonNull()) {
            return null;
        }
        if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
            return parse(raw.getAsString());
        }
        if (!raw.isJsonObject()) {
            return null;
        }
        JsonObject obj = raw.getAsJsonObject();
        JsonElement dim = obj.get("dimension");
        if (dim == null || !dim.isJsonPrimitive() || !dim.getAsJsonPrimitive().isString()) {
            return null;
        }
        String dimId = dim.getAsString().trim().toLowerCase();
        if (Identifier.tryParse(dimId) == null) {
            return null;
        }
        JsonElement arrival = obj.get("arrival");
        if (arrival == null || arrival.isJsonNull()) {
            return new ExitTarget(Kind.DIMENSION, dimId, "spawn", null);
        }
        if (arrival.isJsonPrimitive() && arrival.getAsJsonPrimitive().isString()) {
            String a = arrival.getAsString().trim();
            if ("anchor".equals(a) || "spawn".equals(a)) {
                return new ExitTarget(Kind.DIMENSION, dimId, a, null);
            }
            return null;
        }
        if (arrival.isJsonArray() && arrival.getAsJsonArray().size() == 3) {
            JsonArray arr = arrival.getAsJsonArray();
            try {
                return new ExitTarget(Kind.DIMENSION, dimId, null,
                        new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt()});
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** Parse a shorthand or canonical string ("bed", "dim!ns:slug!spawn", ...). */
    public static ExitTarget parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        switch (s) {
            case "bed":
                return new ExitTarget(Kind.BED, null, null, null);
            case "worldSpawn":
                return new ExitTarget(Kind.WORLD_SPAWN, null, null, null);
            case "origin":
                return new ExitTarget(Kind.ORIGIN, null, null, null);
            default:
                break;
        }
        if (!s.startsWith("dim!")) {
            return null;
        }
        String[] parts = s.split("!", 3);
        if (parts.length != 3 || Identifier.tryParse(parts[1]) == null) {
            return null;
        }
        String arrival = parts[2];
        if ("anchor".equals(arrival) || "spawn".equals(arrival)) {
            return new ExitTarget(Kind.DIMENSION, parts[1], arrival, null);
        }
        String[] coords = arrival.split(",");
        if (coords.length == 3) {
            try {
                return new ExitTarget(Kind.DIMENSION, parts[1], null, new int[]{
                        Integer.parseInt(coords[0].trim()),
                        Integer.parseInt(coords[1].trim()),
                        Integer.parseInt(coords[2].trim())});
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** The canonical string form (round-trips through parse(String)). */
    public String canonical() {
        switch (this.kind) {
            case BED: return "bed";
            case WORLD_SPAWN: return "worldSpawn";
            case ORIGIN: return "origin";
            default: break;
        }
        if (this.arrivalPos != null) {
            return "dim!" + this.dimensionId + "!"
                    + this.arrivalPos[0] + "," + this.arrivalPos[1] + "," + this.arrivalPos[2];
        }
        return "dim!" + this.dimensionId + "!" + this.arrival;
    }

    /**
     * Canonicalise a raw config value to the string the persistence layer
     * stores; falls back to the given default shorthand on invalid input
     * (warn is the caller's job — this is used from getters).
     */
    public static String canonicalise(JsonElement raw, String fallback) {
        ExitTarget parsed = parse(raw);
        return parsed != null ? parsed.canonical() : fallback;
    }

    /**
     * Resolve to a concrete destination for this player. Null means "not
     * ready" (an unloaded runtime dimension — its load has been queued;
     * retry next tick) — every other path resolves, falling back to the
     * overworld spawn rather than stranding.
     */
    public Destination resolve(ServerPlayerEntity player, ServerWorld currentWorld) {
        MinecraftServer server = currentWorld.getServer();
        switch (this.kind) {
            case BED: {
                TeleportTarget respawn = player.getRespawnTarget(true, TeleportTarget.NO_OP);
                return new Destination(respawn.world(), respawn.pos());
            }
            case WORLD_SPAWN:
                return overworldSpawn(server);
            case ORIGIN: {
                PortalHelper.PlayerOrigin origin = PortalHelper.getPlayerOrigin(player.getUuid());
                if (origin != null) {
                    ServerWorld world = server.getWorld(origin.world);
                    if (world != null) {
                        return new Destination(world,
                                new Vec3d(origin.pos.getX() + 0.5, origin.pos.getY(), origin.pos.getZ() + 0.5));
                    }
                }
                return overworldSpawn(server);
            }
            case DIMENSION:
            default:
                return resolveDimension(player, server);
        }
    }

    private Destination resolveDimension(ServerPlayerEntity player, MinecraftServer server) {
        Identifier id = Identifier.tryParse(this.dimensionId);
        if (id == null) {
            return overworldSpawn(server);
        }
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, id);
        ServerWorld world = server.getWorld(key);
        if (world == null) {
            // Runtime dimensions load lazily; never create a world from a
            // tick/mixin context (main-thread deadlock) — queue and retry.
            DimensionManager.getInstance().requestWorldLoadDirect(id.getPath());
            return null;
        }
        DimensionConfig def = DimensionManager.getInstance().resolveDefinition(id.getPath());
        int[] pos = this.arrivalPos;
        if (pos == null && "anchor".equals(this.arrival) && def != null
                && def.getPortal() != null && def.getPortal().anchor != null) {
            pos = def.getPortal().anchor.resolvePos(def.getSpawn());
        }
        if (pos == null && def != null) {
            pos = def.getSpawn();
        }
        if (pos == null) {
            BlockPos spawn = world.getSpawnPos();
            pos = new int[]{spawn.getX(), spawn.getY(), spawn.getZ()};
        }
        int surfaceY = PortalHelper.findSurfaceY(world, pos[0], pos[2]);
        return new Destination(world, new Vec3d(pos[0] + 0.5, surfaceY, pos[2] + 0.5));
    }

    private static Destination overworldSpawn(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        BlockPos spawn = overworld.getSpawnPos();
        return new Destination(overworld,
                new Vec3d(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
    }

    /** A resolved destination: world + position. */
    public record Destination(ServerWorld world, Vec3d pos) {
    }
}
