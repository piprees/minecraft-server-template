package com.customdimensions.immersive;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.ImmersiveSettings;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.config.PortalDefinition;
import com.customdimensions.portal.PortalHelper;
import com.customdimensions.portal.PortalShape;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.vehicle.VehicleEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immersive portal entity pass-through (Phase 3): items, projectiles,
 * experience orbs and falling blocks cross an immersive portal the way a
 * player does, keeping their velocity, so the frame reads as an open
 * doorway rather than a player-only turnstile.
 *
 * <p>Gated entirely on {@link ImmersiveSettings#entityPassthrough()}. A zone
 * whose definition has no immersive block does zero work here and behaves
 * exactly as it did before this phase — including the arrival-side return in
 * {@code EntityTickPortalMixin}, which resolves its gate from the dimension
 * config of the world the portal block lives in.
 *
 * <h2>Why the scan lives in the world tick</h2>
 * Source-side portal zones have NO portal blocks (mods/AGENTS.md; PLAN.md
 * Gotcha #5) — they are invisible volumes. {@code Entity.tickPortalTeleportation}
 * only fires for entities standing in a real portal block, so it can only ever
 * serve the ARRIVAL side. The source side has to be a position scan from the
 * world tick, which is what {@link #tick} is.
 *
 * <h2>Rule 1: never sync-load a chunk from here</h2>
 * The arrival surface is read off an already-loaded chunk
 * ({@code getChunkManager().getWorldChunk(cx, cz, false)}); a null chunk means
 * "skip, try again on a later tick". {@link PortalHelper#findSurfaceY} is
 * deliberately NOT used even though the player path uses it: it calls
 * {@code world.getChunk(...)}, which FORCE-GENERATES, and sync-generating a
 * chunk from the world tick is a documented server-wedge trigger on this
 * server (mods/AGENTS.md "Known issues"). A thrown item is not worth a wedged
 * tick loop. {@link #arrivalSurfaceY} reproduces {@code findSurfaceY}'s maths
 * (including its void fallback and build-limit headroom) on a loaded chunk, so
 * when it does resolve it agrees with the player exactly.
 *
 * <h2>Rule 2: the transform is the player's transform</h2>
 * The source -&gt; target mapping comes from {@link ProjectionVolume}, the same
 * pure functions Phase 1's preview uses — {@code scaledMapping} reproduces
 * {@code ServerWorldMixin}'s integer-averaged-centre-times-scale (truncation
 * and all) and {@code anchorMapping} reproduces {@code teleportToAnchor}'s
 * min-corner translation. An entity sitting in the interior's centre column
 * therefore lands in exactly the column a player would; entities off-centre
 * keep their offset through the doorway, which is the same translation applied
 * to a different starting point rather than a second, divergent transform.
 *
 * <h2>Edge triggering</h2>
 * The player loop teleports on the ENTRY EDGE only (it tracks "was inside" per
 * player per world in {@code PortalHelper.PLAYER_IN_ZONE}). This does the same
 * with {@link #INSIDE}, and it is not cosmetic: level triggering makes an
 * entity that RETURNS from an arrival portal into its own source zone bounce
 * straight back the moment its cooldown expires. The set is rebuilt from the
 * scan every tick, so it is self-pruning — it only ever holds entities
 * currently standing in an immersive zone interior.
 */
public final class EntityPassthrough {

    /**
     * Per-world set of eligible entities that were inside an immersive zone
     * interior at the end of the previous scan. Rebuilt every tick from the
     * scan itself (so it cannot leak), and seeded by
     * {@link #tryReturnFromArrivalPortal} for an entity it drops into a world,
     * so a return trip does not read as a fresh entry on the next tick.
     */
    private static final Map<RegistryKey<World>, Set<UUID>> INSIDE = new ConcurrentHashMap<>();

    /**
     * How far past the interior's bounding box the broad-phase query reaches,
     * in blocks. It exists purely so that fast movers are CANDIDATES — a bow
     * arrow travels about 3 blocks a tick and would otherwise be past a
     * one-block-thick portal before any scan sees it. Nothing is teleported on
     * the strength of this margin: {@link #crossedInterior} is an exact swept
     * test over the tick's movement and rejects everything that merely flew
     * nearby.
     */
    private static final double SCAN_MARGIN = 3.0;

    /** Swept-path sampling resolution, in blocks, and its hard cap. */
    private static final double SWEEP_STEP = 0.5;
    private static final int MAX_SWEEP_SAMPLES = 32;

    /** Arrival column's chunk isn't loaded yet — no crossing this pass. */
    private static final int NO_ARRIVAL = Integer.MIN_VALUE;

    private EntityPassthrough() {
    }

    // ------------------------------------------------------------------
    // Pure decision logic (no world, no registries — unit-testable)
    // ------------------------------------------------------------------

    /**
     * Whether an entity of this class may pass through an immersive portal.
     *
     * <p>Class-based rather than instance-based so the whole eligibility
     * policy is testable without a live world. The exclusions are deliberate
     * and each one is load-bearing:
     *
     * <ul>
     *   <li>{@link ServerPlayerEntity} — has its own teleport path in
     *       {@code ServerWorldMixin} (origin tracking, portal sounds,
     *       single-use countdown, arrival portal creation). Redundant here at
     *       best, double-teleporting at worst.</li>
     *   <li>ALL {@link LivingEntity} (mobs, villagers, armour stands) —
     *       pathfinding targets, AI memories, leashes and spawn tracking are
     *       all world-scoped and break on a cross-dimension recreate. A
     *       future feature, not this one. This also covers players, since
     *       {@code PlayerEntity} is a {@code LivingEntity}; the explicit
     *       player check above it is documentation.</li>
     *   <li>{@link VehicleEntity} (boats, minecarts) — rider state.</li>
     * </ul>
     *
     * Everything else must be on the allow-list explicitly: item frames and
     * paintings are attached to blocks, end crystals are structural, and an
     * unknown modded entity is not our business.
     */
    public static boolean isPassthroughType(Class<?> type) {
        if (type == null) {
            return false;
        }
        if (ServerPlayerEntity.class.isAssignableFrom(type)) {
            return false;
        }
        if (LivingEntity.class.isAssignableFrom(type)) {
            return false;
        }
        if (VehicleEntity.class.isAssignableFrom(type)) {
            return false;
        }
        return ItemEntity.class.isAssignableFrom(type)
                || ProjectileEntity.class.isAssignableFrom(type)
                || ExperienceOrbEntity.class.isAssignableFrom(type)
                || FallingBlockEntity.class.isAssignableFrom(type);
    }

    /**
     * The axis-aligned box enclosing a zone interior, in world coordinates:
     * each interior block contributes its full cube, so a single block at
     * (0, 64, 0) yields the box (0, 64, 0) -&gt; (1, 65, 1).
     *
     * <p>Returns null for an empty or absent interior — a zone with no
     * interior has nothing to pass through and the caller skips it.
     */
    public static Box boundsOf(Set<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos p : positions) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        return new Box(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
    }

    /**
     * Whether the straight path an entity travelled this tick (previous
     * position -&gt; current position) passed through any block of the zone
     * interior.
     *
     * <p>The interior is checked as a SET of block positions rather than as
     * its bounding box, so irregular flood-filled shapes don't teleport
     * things that flew past a concave corner. A stationary entity (previous
     * == current) samples once, at its own block — which is exactly the
     * {@code PortalHelper.isInsideZone} test the player loop uses.
     */
    public static boolean crossedInterior(Set<BlockPos> interior,
            double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        if (interior == null || interior.isEmpty()) {
            return false;
        }
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.min(MAX_SWEEP_SAMPLES, (int) Math.ceil(length / SWEEP_STEP));
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 1.0 : (double) i / steps;
            if (interior.contains(BlockPos.ofFloored(fromX + dx * t, fromY + dy * t, fromZ + dz * t))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // World tick: source-side crossing
    // ------------------------------------------------------------------

    /**
     * Per-world tick. Called from {@code ServerWorldMixin.onTick} after
     * {@code ImmersiveProjector.tick} and before {@code ExitConditions.tick}
     * (PLAN.md Gotcha #12): after the player teleport loop, so a player who
     * stepped through this tick is already gone, and after the projector, so
     * entities cross the same zone state the projection was built from.
     */
    public static void tick(ServerWorld world) {
        RegistryKey<World> worldKey = world.getRegistryKey();
        List<PortalHelper.PortalZone> sourceZones = PortalHelper.getSourceZones(worldKey);
        if (sourceZones.isEmpty()) {
            INSIDE.remove(worldKey);
            return;
        }

        Set<UUID> previous = INSIDE.getOrDefault(worldKey, Set.of());
        Set<UUID> nowInside = null;

        // Snapshot: a teleport removes the entity from this world, and the
        // zone list itself is the live backing list (see ServerWorldMixin).
        for (PortalHelper.PortalZone zone : new ArrayList<>(sourceZones)) {
            PortalDefinition def = zone.definition;
            ImmersiveSettings immersive = def.getImmersive();
            if (immersive == null || !immersive.entityPassthrough()) {
                // Not immersive, or pass-through switched off: zero work,
                // zero behavioural change.
                continue;
            }
            if (PortalShape.END_GATEWAY.equals(def.getShape())) {
                // Gateway source zones DO contain a real block, so vanilla's
                // own gateway travel already handles entities standing in
                // them. Excluded here so the two never race.
                continue;
            }
            if (zone.targetWorld.equals(worldKey)) {
                continue;
            }
            Box scanBox = boundsOf(zone.interior);
            if (scanBox == null) {
                continue;
            }

            // getOtherEntities returns a fresh snapshot list, so teleporting
            // inside the loop cannot CME the world's entity index; isRemoved
            // re-checks defensively for anything already taken this tick.
            List<Entity> candidates = world.getOtherEntities(
                    null, scanBox.expand(SCAN_MARGIN), EntityPassthrough::isEligible);
            if (candidates.isEmpty()) {
                continue;
            }
            ServerWorld targetWorld = world.getServer().getWorld(zone.targetWorld);

            for (Entity entity : candidates) {
                if (entity.isRemoved()) {
                    continue;
                }
                if (!crossedInterior(zone.interior, entity.prevX, entity.prevY, entity.prevZ,
                        entity.getX(), entity.getY(), entity.getZ())) {
                    continue;
                }
                if (nowInside == null) {
                    nowInside = new HashSet<>();
                }
                // Recorded BEFORE the cooldown gate on purpose: an entity
                // that is sitting in the zone waiting out a cooldown must
                // not read as a fresh entry the tick that cooldown reaches
                // zero. That is the ping-pong.
                nowInside.add(entity.getUuid());
                if (previous.contains(entity.getUuid())) {
                    continue;
                }
                if (entity.getPortalCooldown() != 0) {
                    continue;
                }
                if (targetWorld == null) {
                    // Target world unloaded. Deliberately NOT queued for
                    // load: a thrown item should not be able to spin up a
                    // dimension. Phase 0's proximity pre-loader already has
                    // it loaded whenever a player is near enough to throw
                    // anything at the portal.
                    continue;
                }
                passThrough(world, targetWorld, entity, zone, def);
            }
        }

        if (nowInside == null) {
            INSIDE.remove(worldKey);
        } else {
            INSIDE.put(worldKey, nowInside);
        }
    }

    /** Instance-level eligibility: the type policy plus live entity state. */
    public static boolean isEligible(Entity entity) {
        return entity != null
                && !entity.isRemoved()
                && !entity.hasVehicle()
                && isPassthroughType(entity.getClass());
    }

    /**
     * Move one entity to the far side, position and velocity together.
     *
     * <p>{@code teleportTo(TeleportTarget)} is used rather than the
     * {@code teleport(world, x, y, z, flags, yaw, pitch)} overload the player
     * path uses, because a cross-dimension move RECREATES a non-player entity
     * in the destination world: the original reference is left removed and
     * anything set on it afterwards is set on a corpse (PLAN.md Gotcha #10).
     * {@code teleportTo} carries the velocity as a first-class field of the
     * target — so it is applied to the entity that actually arrives, in the
     * same operation as the position — and RETURNS the live arrival, which
     * removes the need for the plan's teleport-then-look-up-by-UUID dance.
     *
     * <p>{@code ADD_PORTAL_CHUNK_TICKET} rather than {@code NO_OP}: the
     * destination frequently has no player in it (that is the whole point of
     * throwing something through first), and an arrival with no ticket lands
     * in a chunk that can unload from under it. The ticket is vanilla's own
     * portal ticket, added asynchronously, so it does not reintroduce the
     * sync-load risk Rule 1 exists to prevent.
     * {@code SEND_TRAVEL_THROUGH_PORTAL_PACKET} is player-only and would do
     * nothing here.
     */
    private static void passThrough(ServerWorld world, ServerWorld targetWorld, Entity entity,
            PortalHelper.PortalZone zone, PortalDefinition def) {
        ProjectionVolume.TargetMapping mapping = mappingFor(zone, def);
        int arrivalY = arrivalSurfaceY(targetWorld, mapping.arrivalX(), mapping.arrivalZ());
        if (arrivalY == NO_ARRIVAL) {
            // Arrival column not loaded. The entity keeps its position and
            // its zero cooldown, so it simply crosses on a later tick once
            // the chunk is in — never a sync-load from the tick loop.
            return;
        }

        // Horizontal portals land a player one block ABOVE the surface; keep
        // entities on the same rule so both arrive on top of the ground
        // rather than inside it.
        double floorY = arrivalY + (zone.axis == Direction.Axis.Y ? 1 : 0);
        double tx = entity.getX() + mapping.dx();
        double ty = floorY + (entity.getY() - mapping.interiorMinY());
        double tz = entity.getZ() + mapping.dz();

        Vec3d velocity = entity.getVelocity();
        Entity arrived = entity.teleportTo(new TeleportTarget(
                targetWorld, new Vec3d(tx, ty, tz), velocity,
                entity.getYaw(), entity.getPitch(), TeleportTarget.ADD_PORTAL_CHUNK_TICKET));
        if (arrived == null) {
            return;
        }
        // Without this the client predicts zero motion and the entity appears
        // to stop dead at the far side for a tick.
        arrived.velocityModified = true;
        arrived.setPortalCooldown(def.getCooldown());

        MultiverseServer.LOGGER.debug(
                "immersive: entity {} crossed {} -> {} at ({}, {}, {}) velocity ({}, {}, {})",
                arrived.getType().getTranslationKey(), world.getRegistryKey().getValue(),
                targetWorld.getRegistryKey().getValue(),
                String.format("%.2f", tx), String.format("%.2f", ty), String.format("%.2f", tz),
                String.format("%.3f", velocity.x), String.format("%.3f", velocity.y),
                String.format("%.3f", velocity.z));
    }

    // ------------------------------------------------------------------
    // Arrival side: return trip out of a real portal block
    // ------------------------------------------------------------------

    /**
     * Arrival-side return (Phase 3d), called from
     * {@code EntityTickPortalMixin} for every non-player entity.
     *
     * <p><b>Returns true only when it actually teleported</b> — the caller
     * cancels the vanilla callback on true and on true alone, because
     * cancelling otherwise would break vanilla portal handling for every
     * entity in the game.
     *
     * <p>This runs on every non-player entity every tick, so the gates are
     * ordered cheapest-and-most-selective first: the class check rejects all
     * mobs and players before anything touches the world, and the block state
     * comes from {@code getBlockStateAtPos()}, which vanilla caches per tick.
     * Unlike the player path this checks only the entity's own block, not
     * also the blocks above and below it: items, orbs, arrows and falling
     * blocks are all sub-block entities whose block position is where they
     * visually are, and two extra block lookups per entity per tick is not a
     * price worth paying for a case that cannot arise.
     *
     * <p>Exit modes are honoured only where they mean something without a
     * player: {@code "worldSpawn"} resolves, a plain link or {@code "origin"}
     * falls back to the recorded source world and Y (an item has no bed and
     * no tracked origin), and {@code "bed"} / {@code "dim!..."} are skipped
     * rather than guessed at.
     */
    public static boolean tryReturnFromArrivalPortal(Entity entity, World rawWorld) {
        if (!(rawWorld instanceof ServerWorld world)) {
            return false;
        }
        if (!isEligible(entity)) {
            return false;
        }
        if (entity.getPortalCooldown() != 0) {
            return false;
        }
        if (!PortalHelper.isPortalBlock(entity.getBlockStateAtPos())) {
            return false;
        }

        // The gate for the arrival side is the dimension config of the world
        // the portal block is IN — the same ImmersiveSettings that every
        // source zone targeting this world carries. Without it, arrival
        // portals of NON-immersive dimensions would gain new behaviour.
        RegistryKey<World> worldKey = world.getRegistryKey();
        MultiverseConfig config = MultiverseConfig.getInstance();
        ImmersiveSettings immersive = config != null ? config.getImmersiveFor(worldKey) : null;
        if (immersive == null || !immersive.entityPassthrough()) {
            return false;
        }

        // Direct lookup rather than collectPortalArea: every interior block
        // of an arrival portal is registered individually, so the flood fill
        // the player path runs would buy nothing and cost up to 128 block
        // reads per entity per tick. A null target means this is not one of
        // our portals (a player-built vanilla one, say) — leave it alone.
        BlockPos pos = entity.getBlockPos();
        PortalHelper.PortalReturnTarget target = PortalHelper.getPortalTarget(worldKey, pos);
        if (target == null) {
            return false;
        }

        ServerWorld destination;
        double tx;
        double ty;
        double tz;
        if ("worldSpawn".equals(target.exitMode)) {
            destination = world.getServer().getOverworld();
            BlockPos spawn = destination.getSpawnPos();
            tx = spawn.getX() + 0.5;
            ty = spawn.getY();
            tz = spawn.getZ() + 0.5;
        } else if (target.exitMode == null || "origin".equals(target.exitMode)) {
            destination = world.getServer().getWorld(target.sourceWorld);
            tx = pos.getX() + 0.5;
            ty = target.sourceY;
            tz = pos.getZ() + 0.5;
        } else {
            // "bed" and "dim!ns:slug!arrival" resolve against a player.
            return false;
        }
        if (destination == null || destination.getRegistryKey().equals(worldKey)) {
            return false;
        }

        Vec3d velocity = entity.getVelocity();
        Entity arrived = entity.teleportTo(new TeleportTarget(
                destination, new Vec3d(tx, ty, tz), velocity,
                entity.getYaw(), entity.getPitch(), TeleportTarget.ADD_PORTAL_CHUNK_TICKET));
        if (arrived == null) {
            return false;
        }
        arrived.velocityModified = true;
        arrived.setPortalCooldown(target.cooldown);
        // The return often lands inside the very source zone that sent the
        // entity here. Seeding the destination's "was inside" set makes that
        // read as already-inside rather than as a fresh entry, so it settles
        // instead of bouncing back the moment its cooldown expires.
        markInside(destination.getRegistryKey(), arrived.getUuid());

        // Single-use countdowns are deliberately NOT armed from here: a
        // thrown item must not burn a one-shot portal the player is saving.
        MultiverseServer.LOGGER.debug(
                "immersive: entity {} returned {} -> {} at ({}, {}, {})",
                arrived.getType().getTranslationKey(), worldKey.getValue(),
                destination.getRegistryKey().getValue(),
                String.format("%.2f", tx), String.format("%.2f", ty), String.format("%.2f", tz));
        return true;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** The source -&gt; target transform this zone's player teleport would use. */
    private static ProjectionVolume.TargetMapping mappingFor(PortalHelper.PortalZone zone, PortalDefinition def) {
        if (def.hasAnchor()) {
            int[] anchor = def.getAnchorPos();
            return ProjectionVolume.anchorMapping(zone.interior, anchor[0], anchor[2]);
        }
        return ProjectionVolume.scaledMapping(zone.interior, def.getScale());
    }

    /**
     * The arrival surface Y, or {@link #NO_ARRIVAL} when the arrival column's
     * chunk isn't loaded. Same maths as {@link PortalHelper#findSurfaceY} — so
     * an entity lands where a player would — read off an already-loaded chunk
     * instead of force-generating one (see "Rule 1" above).
     */
    private static int arrivalSurfaceY(ServerWorld targetWorld, int x, int z) {
        WorldChunk chunk = targetWorld.getChunkManager().getWorldChunk(x >> 4, z >> 4, false);
        if (chunk == null) {
            return NO_ARRIVAL;
        }
        int surfaceY = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        if (surfaceY <= targetWorld.getBottomY() + 1) {
            return PortalHelper.VOID_FALLBACK_Y;
        }
        return Math.min(surfaceY, targetWorld.getTopY() - 8);
    }

    private static void markInside(RegistryKey<World> worldKey, UUID entityId) {
        INSIDE.computeIfAbsent(worldKey, k -> new HashSet<>()).add(entityId);
    }

    /** Resets all session state (server shutdown, tests). */
    public static void clear() {
        INSIDE.clear();
    }
}
