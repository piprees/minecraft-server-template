package com.customdimensions.immersive;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.ImmersiveSettings;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.config.PortalDefinition;
import com.customdimensions.dimension.DifficultyManager;
import com.customdimensions.portal.PortalHelper;
import com.customdimensions.portal.PortalShape;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.Leashable;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.vehicle.VehicleEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immersive portal entity pass-through (Phase 3): items, projectiles,
 * experience orbs, falling blocks and — since Phase 3b — living entities
 * (mobs, villagers, animals) cross an immersive portal the way a player does,
 * keeping their velocity, so the frame reads as an open doorway rather than a
 * player-only turnstile.
 *
 * <h2>Living entities</h2>
 * They were excluded when this phase first shipped, on the grounds that
 * pathfinding targets, AI memories, leashes and spawn tracking are all
 * world-scoped and break on a cross-dimension recreate. That exclusion has
 * been lifted deliberately: vanilla nether portals carry mobs, so a portal
 * that refuses them does not read as a portal, and players want to bring
 * villagers and livestock to their dimensions. Vanilla's own behaviour is the
 * reference for what "correct" means here, and where this diverges from it —
 * leashes (detached near-side rather than dropped far-side) and per-dimension
 * difficulty (re-applied on arrival) — the divergence is documented at the
 * method that causes it.
 *
 * <p>What the cross-dimension recreate does to a mob, and why none of it needs
 * code here: navigation and the current attack target are not serialised, so
 * the arrival gets a fresh {@code EntityNavigation} bound to its new world;
 * brain memories are {@code GlobalPos}-qualified, so a villager's old home and
 * job site simply stop matching, exactly as they do when vanilla carries one
 * into the Nether; and animals and villagers already refuse to despawn, so
 * only hostile mobs are subject to the destination's despawn rules — again as
 * vanilla.
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
 * The arrival is resolved by {@link ArrivalResolver}, which reads an
 * already-loaded chunk ({@code getChunkManager().getWorldChunk(cx, cz,
 * false)}) and reports {@link ArrivalResolver#NO_ARRIVAL} for a null one —
 * "skip, try again on a later tick". {@link PortalHelper#findSurfaceY} is
 * deliberately NOT used even though the player path uses it: it calls
 * {@code world.getChunk(...)}, which FORCE-GENERATES, and sync-generating a
 * chunk from the world tick is a documented server-wedge trigger on this
 * server (mods/AGENTS.md "Known issues"). A thrown item is not worth a wedged
 * tick loop.
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
 * <h2>Rule 3: land where the player lands, not where the ground is</h2>
 * This used to hold its own copy of the heightmap surface calculation, as did
 * the projector, and both diverged from the player path for the same reason:
 * once an arrival portal exists the player is teleported INTO it, while the
 * heightmap answers with the top of the solid frame we built around it — four
 * or more blocks higher. {@link ArrivalResolver} is now the single answer for
 * all three of them; do not reintroduce a local copy.
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

    /**
     * Arrival column's chunk isn't loaded yet — no crossing this pass.
     * Aliased from {@link ArrivalResolver} rather than re-declared.
     */
    private static final int NO_ARRIVAL = ArrivalResolver.NO_ARRIVAL;

    private EntityPassthrough() {
    }

    // ------------------------------------------------------------------
    // Pure decision logic (no world, no registries — unit-testable)
    // ------------------------------------------------------------------

    /**
     * Whether an entity of this class may pass through an immersive portal.
     *
     * <p>Class-based rather than instance-based so the whole eligibility
     * policy is testable without a live world; the live-state half lives in
     * {@link #isEligible}. Still an allow-list, not a deny-list — item frames
     * and paintings are attached to blocks, end crystals are structural, and
     * an unknown modded non-living entity is not our business.
     *
     * <p>The exclusions are deliberate and each one is load-bearing:
     *
     * <ul>
     *   <li>{@link PlayerEntity} — a player has their own teleport path in
     *       {@code ServerWorldMixin} (origin tracking, portal sounds,
     *       single-use countdown, arrival portal creation). Redundant here at
     *       best, double-teleporting at worst, so this is checked before
     *       anything else. Written against {@code PlayerEntity} rather than
     *       {@link ServerPlayerEntity} so it holds for every player subclass,
     *       fake players included.</li>
     *   <li>{@link ArmorStandEntity} — living, but placed rather than
     *       wandering. A stand a player has stood inside the frame as
     *       decoration would silently teleport itself away on the next tick.
     *       This is the one living type that is decor, so it is the one
     *       living type excluded.</li>
     *   <li>{@link VehicleEntity} (boats, minecarts) — a boat carrying a
     *       passenger across a dimension boundary is a bigger piece of work
     *       than this (see the phase doc); a boat crossing WITHOUT its
     *       passenger is worse than not crossing at all.</li>
     * </ul>
     *
     * <p>Everything else living is in, which is the point of this phase: mobs,
     * villagers, animals and anything a mod adds on top of
     * {@link LivingEntity}. Vanilla's own per-entity veto
     * ({@code canUsePortals}) is applied in {@link #isEligible}, so the ender
     * dragon and the wither still stay where they are.
     */
    public static boolean isPassthroughType(Class<?> type) {
        if (type == null) {
            return false;
        }
        if (PlayerEntity.class.isAssignableFrom(type)) {
            return false;
        }
        if (ArmorStandEntity.class.isAssignableFrom(type)) {
            return false;
        }
        if (VehicleEntity.class.isAssignableFrom(type)) {
            return false;
        }
        return LivingEntity.class.isAssignableFrom(type)
                || ItemEntity.class.isAssignableFrom(type)
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
                    // load: nothing short of a player should be able to spin
                    // up a dimension — least of all a mob that has wandered
                    // into an unattended frame. Phase 0's pre-loader has
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

    /**
     * Instance-level eligibility: the type policy plus live entity state.
     *
     * <p>Ordered cheapest-and-most-selective first, because the arrival-side
     * caller runs this on every non-player entity in the game every tick. The
     * class check is what rejects the overwhelming majority.
     *
     * <p>Two gates matter more now that living entities are in:
     *
     * <ul>
     *   <li><b>Passengers, not just vehicles.</b> A rider was already skipped
     *       (the vehicle should carry it or nothing should move), but
     *       {@code Entity.teleportTo} carries passengers with it — it
     *       detaches them, teleports each recursively and re-mounts on the far
     *       side. A ridden horse would therefore drag its PLAYER across on
     *       this path, bypassing origin tracking entirely and racing the
     *       player's own teleport. So a mount with anyone aboard stays put;
     *       the player dismounts and walks through, which is what the player
     *       loop expects anyway (it skips mounted players too).</li>
     *   <li><b>{@code canUsePortals} is vanilla's own veto</b> and is honoured
     *       verbatim. It rules out the ender dragon and the wither (both
     *       return false outright), sleeping villagers, and dead entities —
     *       and, incidentally, fishing bobbers, which were eligible before
     *       this phase purely because they are projectiles. A bobber crossing
     *       while the angler holding it stays behind is exactly the dangling
     *       cross-world reference this class is careful to avoid, and vanilla
     *       forbids it for the same reason. {@code false} is passed for
     *       {@code allowVehicles} so it agrees with the explicit
     *       {@code hasVehicle} gate rather than overriding it.</li>
     * </ul>
     */
    public static boolean isEligible(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (!isPassthroughType(entity.getClass())) {
            return false;
        }
        return isPassthroughState(entity.isRemoved(), entity.hasVehicle(), entity.hasPassengers(),
                entity.canUsePortals(false));
    }

    /**
     * The live-state half of {@link #isEligible}, as a pure function so the
     * policy is pinned by unit test rather than only by reading the call site
     * — the same reason {@link #isPassthroughType} takes a {@code Class}
     * instead of an {@code Entity}.
     *
     * <p>Note what is NOT a gate here: <b>being on a lead</b>. A leashed mob
     * crosses, and its lead is broken and dropped first — see
     * {@code detachLeashBeforeCrossing} for why refusing to move it would be
     * the worse of the two options.
     *
     * @param removed             the entity has already been taken this tick
     * @param ridden              the entity is a passenger; its vehicle should
     *                            carry it, or nothing should move
     * @param carrying            the entity has passengers of its own;
     *                            {@code teleportTo} would drag them across
     *                            with it, players included
     * @param vanillaAllowsPortal {@code Entity.canUsePortals(false)} — vanilla's
     *                            own per-entity veto, honoured verbatim
     */
    public static boolean isPassthroughState(boolean removed, boolean ridden, boolean carrying,
            boolean vanillaAllowsPortal) {
        return !removed && !ridden && !carrying && vanillaAllowsPortal;
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
        int arrivalY = ArrivalResolver.arrivalY(
                targetWorld, mapping.arrivalX(), mapping.arrivalZ(), zone.axis);
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

        detachLeashBeforeCrossing(entity);

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
        applyArrivalDifficulty(arrived);

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
     * ordered cheapest-and-most-selective first: the class check rejects
     * players and everything off the allow-list before anything touches the
     * world, the portal cooldown is a plain field read, and only then does the
     * block state get looked at — and that comes from
     * {@code getBlockStateAtPos()}, which vanilla caches per tick. Living
     * entities now clear the class gate, so the added per-tick cost of this
     * phase is one integer comparison and one cached state read per mob in a
     * loaded world; the map lookup and the config read stay behind the
     * portal-block test, which almost nothing passes.
     *
     * <p>Unlike the player path this checks only the entity's own block, not
     * also the blocks above and below it. That is exact for the sub-block
     * entities (items, orbs, arrows, falling blocks) and for anything standing
     * in a vertical doorway, whose portal blocks reach the floor. The gap it
     * leaves is an entity resting ON a horizontal (END_PORTAL) arrival, whose
     * block position is the air above the portal — see the phase doc's known
     * limits. Two extra block lookups per entity per tick is not the price to
     * pay for it.
     *
     * <p>Exit modes are honoured only where they mean something without a
     * player: {@code "worldSpawn"} resolves, a plain link or {@code "origin"}
     * falls back to the recorded source world and Y (a thrown diamond and a
     * wandering cow alike have no bed and no tracked origin), and
     * {@code "bed"} / {@code "dim!..."} are skipped rather than guessed at.
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

        detachLeashBeforeCrossing(entity);

        Vec3d velocity = entity.getVelocity();
        Entity arrived = entity.teleportTo(new TeleportTarget(
                destination, new Vec3d(tx, ty, tz), velocity,
                entity.getYaw(), entity.getPitch(), TeleportTarget.ADD_PORTAL_CHUNK_TICKET));
        if (arrived == null) {
            return false;
        }
        arrived.velocityModified = true;
        arrived.setPortalCooldown(target.cooldown);
        applyArrivalDifficulty(arrived);
        // The return often lands inside the very source zone that sent the
        // entity here. Seeding the destination's "was inside" set makes that
        // read as already-inside rather than as a fresh entry, so it settles
        // instead of bouncing back the moment its cooldown expires.
        markInside(destination.getRegistryKey(), arrived.getUuid());

        // Single-use countdowns are deliberately NOT armed from here, and
        // startSingleUseCountdown is not called on the source side either: no
        // thrown item and no wandering mob may burn a one-shot portal the
        // player is saving. Only a player traversal arms the countdown.
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

    /**
     * Break the lead before a leashed mob crosses, dropping it the way any
     * other broken leash does.
     *
     * <p>{@code detachLeash(true, true)} is vanilla's own break call — the one
     * {@code Leashable.tickLeash} makes when the holder dies or the mob is
     * dragged past the maximum leash length. It drops a lead item and sends
     * the detach packet, so the holder's client stops drawing a rope to
     * something that is no longer there.
     *
     * <p><b>Why detach rather than refuse to move.</b> Refusing leaves the mob
     * behind, still leashed to a holder who has just walked into another
     * dimension — and vanilla NEVER breaks that leash: {@code tickLeash} only
     * detaches on death, and skips its distance check entirely when the two
     * are in different worlds. Refusing would therefore manufacture the exact
     * permanent cross-world leash this is meant to avoid, and it would make
     * "lead your cow through the portal" impossible, which is most of the
     * reason living entities are allowed through at all.
     *
     * <p><b>Why not simply let it cross leashed.</b> That is what vanilla
     * does, and it self-heals — but badly, and only eventually. The teleport
     * recreates the mob from NBT, so the arrival carries an UNRESOLVED holder
     * (a UUID, or a block position for a fence knot) rather than a live
     * reference. {@code resolveLeashData} then either drops a lead in the
     * destination once {@code age > 100} — a phantom leash on any mob younger
     * than five seconds — or, for a fence-tied mob, calls
     * {@code LeashKnotEntity.getOrCreate} and <b>spawns a stray leash knot in
     * the destination world</b> at the mapped position, tied to nothing.
     * Detaching first is deterministic, immediate, and cannot leave litter on
     * the far side. The one cost is that the lead lands on the near side, so
     * a player leading an animal through has to step back for it.
     *
     * <p>Mobs leashed TO the crossing entity need nothing here: the teleport
     * removes the original, and their next {@code tickLeash} sees a holder
     * that is no longer alive and drops their own leads.
     */
    private static void detachLeashBeforeCrossing(Entity entity) {
        if (!(entity instanceof Leashable leashable) || !leashable.isLeashed()) {
            return;
        }
        leashable.detachLeash(true, true);
        MultiverseServer.LOGGER.debug("immersive: entity {} leash detached before crossing",
                entity.getType().getTranslationKey());
    }

    /**
     * Re-apply the DESTINATION dimension's mob scaling to an arriving mob.
     *
     * <p>{@code MobAttributeMixin} applies this mod's per-dimension multipliers
     * at {@code MobEntity.initialize}, which runs at natural spawn and never
     * for an entity that walked in from somewhere else. Without this a zombie
     * led into a hard dimension would keep its home dimension's stats
     * forever. {@link DifficultyManager#applyMobModifiers} is the same public
     * entry point the mixin uses and is idempotent by modifier id
     * (remove-then-add), so calling it on arrival replaces the origin's
     * modifiers with the destination's rather than stacking them.
     *
     * <p>Two behaviours inherited from that method are worth knowing:
     * scaling only ever applies to MONSTER-spawn-group mobs (villagers and
     * livestock are untouched, which is the mod's existing policy), and a mob
     * whose health scales is set back to full — an arrival in a scaled
     * dimension is treated exactly like a spawn there.
     *
     * <p><b>Known gap:</b> a destination with no scaling (multiplier 1.0, or
     * no dimension config at all — the plain overworld, say) returns early
     * WITHOUT stripping the origin's modifiers, so a mob walked out of a hard
     * dimension keeps its boost. Closing that needs a strip-only path inside
     * {@code DifficultyManager}, which owns the modifier id; it is not
     * something to reach around from here.
     */
    private static void applyArrivalDifficulty(Entity arrived) {
        if (arrived instanceof MobEntity mob) {
            DifficultyManager.applyMobModifiers(mob);
        }
    }

    /** The source -&gt; target transform this zone's player teleport would use. */
    private static ProjectionVolume.TargetMapping mappingFor(PortalHelper.PortalZone zone, PortalDefinition def) {
        if (def.hasAnchor()) {
            int[] anchor = def.getAnchorPos();
            return ProjectionVolume.anchorMapping(zone.interior, anchor[0], anchor[2]);
        }
        return ProjectionVolume.scaledMapping(zone.interior, def.getScale());
    }

    private static void markInside(RegistryKey<World> worldKey, UUID entityId) {
        INSIDE.computeIfAbsent(worldKey, k -> new HashSet<>()).add(entityId);
    }

    /** Resets all session state (server shutdown, tests). */
    public static void clear() {
        INSIDE.clear();
    }
}
