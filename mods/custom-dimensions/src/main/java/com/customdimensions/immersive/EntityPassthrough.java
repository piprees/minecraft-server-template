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
 * Immersive portal entity pass-through: items, projectiles, experience orbs,
 * falling blocks, and living entities (mobs, villagers, animals) cross an
 * immersive portal the way a player does, keeping their velocity.
 *
 * <p>Living entities diverge from vanilla nether-portal behaviour in two
 * ways, each documented at the method that causes it: leashes are detached
 * near-side rather than dropped far-side, and per-dimension difficulty is
 * re-applied on arrival. Navigation and brain memories need no code here: a
 * cross-dimension teleport recreates the entity with fresh, world-bound
 * navigation, and {@code GlobalPos}-qualified brain memories simply stop
 * matching, exactly as vanilla behaves carrying a mob into the Nether.
 *
 * <p>Gated entirely on {@link ImmersiveSettings#entityPassthrough()} — a zone
 * with no immersive block does zero work here.
 *
 * <h2>Why the scan lives in the world tick</h2>
 * Source-side portal zones have NO portal blocks — they are invisible
 * volumes. {@code Entity.tickPortalTeleportation} only fires for entities
 * standing in a real portal block, so it can only ever serve the ARRIVAL
 * side; the source side needs a position scan from the world tick, which is
 * {@link #tick}.
 *
 * <h2>Rule 1: never sync-load a chunk from here</h2>
 * The arrival is resolved by {@link ArrivalResolver}, which reads an
 * already-loaded chunk and reports {@link ArrivalResolver#NO_ARRIVAL} for a
 * null one. {@link PortalHelper#findSurfaceY} is deliberately NOT used here
 * even though the player path uses it: it force-generates chunks, and
 * sync-generating from the world tick is a documented server-wedge trigger
 * on this server (mods/AGENTS.md "Known issues").
 *
 * <h2>Rule 2: the transform is the player's transform</h2>
 * The source -&gt; target mapping comes from {@link ProjectionVolume}, the
 * same pure functions the preview uses, so an entity sitting in the
 * interior's centre column lands in exactly the column a player would.
 *
 * <h2>Rule 3: land where the player lands, not where the ground is</h2>
 * Once an arrival portal exists, the player teleports INTO it, while a plain
 * heightmap read answers with the top of the solid frame built around it.
 * {@link ArrivalResolver} is the single answer for the player path, the
 * projector and this class — never reintroduce a local heightmap copy.
 *
 * <h2>Edge triggering</h2>
 * Teleport happens on the ENTRY EDGE only, tracked per world in {@link
 * #INSIDE} (mirrored on the arrival side by {@code
 * PortalHelper.enteredArrivalPortal}). Level triggering would make an entity
 * that returns from an arrival portal into its own source zone bounce
 * straight back the moment its cooldown expires. A cooldown gate alone
 * cannot substitute for this: vanilla re-pins the cooldown every tick an
 * entity stands in a portal block, so an entity that arrived in a portal
 * would never see it reach zero.
 */
public final class EntityPassthrough {

    /**
     * Per-world set of eligible entities inside an immersive zone interior at
     * the end of the previous scan. Rebuilt every tick from the scan itself
     * (so it cannot leak), and seeded by {@link #tryReturnFromArrivalPortal}
     * so a return trip does not read as a fresh entry.
     */
    private static final Map<RegistryKey<World>, Set<UUID>> INSIDE = new ConcurrentHashMap<>();

    /**
     * How far past the interior's bounding box the broad-phase query reaches,
     * in blocks — so fast movers are CANDIDATES (an arrow travels ~3
     * blocks/tick). {@link #crossedInterior} is the exact swept test that
     * decides what actually crosses.
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
     * <p>Class-based rather than instance-based so the eligibility policy is
     * testable without a live world; live state is checked separately in
     * {@link #isEligible}. An allow-list, not a deny-list — an unknown
     * modded non-living entity stays put.
     *
     * <p>Exclusions:
     * <ul>
     *   <li>{@link PlayerEntity} — has its own teleport path in
     *       {@code ServerWorldMixin}; letting a player through here would
     *       double-teleport them. Checked against {@code PlayerEntity}
     *       rather than {@link ServerPlayerEntity} so it holds for every
     *       subclass, fake players included.</li>
     *   <li>{@link ArmorStandEntity} — living, but placed as decoration; it
     *       would otherwise teleport itself away on the next tick.</li>
     *   <li>{@link VehicleEntity} (boats, minecarts) — carrying a passenger
     *       across a dimension boundary is out of scope, and crossing
     *       WITHOUT the passenger is worse than not crossing at all.</li>
     * </ul>
     *
     * <p>Everything else living is in: mobs, villagers, animals, and anything
     * a mod adds on top of {@link LivingEntity}. Vanilla's own per-entity
     * veto ({@code canUsePortals}) is applied in {@link #isEligible}.
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
     * Per-world tick. Called from {@code ServerWorldMixin.onTick} after the
     * player teleport loop (so a player who stepped through this tick is
     * already gone) and after {@code ImmersiveProjector.tick} (so entities
     * cross the same zone state the projection was built from).
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
                // Recorded BEFORE the cooldown gate: an entity waiting out a
                // cooldown in the zone must not read as a fresh entry once
                // the cooldown reaches zero.
                nowInside.add(entity.getUuid());
                if (previous.contains(entity.getUuid())) {
                    continue;
                }
                if (entity.getPortalCooldown() != 0) {
                    continue;
                }
                if (targetWorld == null) {
                    // Target world unloaded — deliberately not queued for
                    // load; only a player nearby should spin up a dimension.
                    // ImmersivePreloader keeps it loaded whenever a player is
                    // near enough to throw something through.
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
     * <p>Ordered cheapest-and-most-selective first — this runs on every
     * non-player entity in the game every tick, and the class check rejects
     * the overwhelming majority.
     *
     * <p>Two gates matter for living entities:
     * <ul>
     *   <li><b>Passengers, not just vehicles.</b> {@code Entity.teleportTo}
     *       carries passengers with it, so a ridden mount would drag its
     *       rider across, bypassing the player's own teleport path entirely.
     *       A mount with anyone aboard stays put; the rider dismounts and
     *       walks through.</li>
     *   <li><b>{@code canUsePortals} is vanilla's own veto</b>, honoured
     *       verbatim: it rules out the ender dragon, the wither, sleeping
     *       villagers, and fishing bobbers whose angler stays behind.
     *       {@code allowVehicles} is passed {@code false} to agree with the
     *       explicit {@code hasVehicle} gate.</li>
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
     * The live-state half of {@link #isEligible}, pure so the policy is
     * pinned by unit test.
     *
     * <p>Not a gate here: being on a lead. A leashed mob crosses and its lead
     * is broken and dropped first — see {@code detachLeashBeforeCrossing}.
     *
     * @param removed             already taken this tick
     * @param ridden              is a passenger; its vehicle should carry it, or nothing moves
     * @param carrying            has passengers of its own, which {@code teleportTo} would drag across
     * @param vanillaAllowsPortal {@code Entity.canUsePortals(false)}, vanilla's own per-entity veto
     */
    public static boolean isPassthroughState(boolean removed, boolean ridden, boolean carrying,
            boolean vanillaAllowsPortal) {
        return !removed && !ridden && !carrying && vanillaAllowsPortal;
    }

    /**
     * Move one entity to the far side, position and velocity together.
     *
     * <p>{@code teleportTo(TeleportTarget)} is used rather than the
     * {@code teleport(...)} overload the player path uses, because a
     * cross-dimension move RECREATES a non-player entity in the destination
     * world — the original reference is left removed. {@code teleportTo}
     * carries velocity as a field of the target and RETURNS the live
     * arrival.
     *
     * <p>{@code ADD_PORTAL_CHUNK_TICKET} rather than {@code NO_OP}: the
     * destination often has no player in it, and an arrival with no ticket
     * lands in a chunk that can unload from under it. The ticket is
     * vanilla's own async portal ticket, so it does not reintroduce the
     * sync-load risk Rule 1 exists to prevent.
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
        // The far side of a source zone IS an arrival portal. Record it as
        // already standing there, or the arrival-side return reads the next
        // tick as an entry edge and throws it straight back.
        PortalHelper.markArrivedInPortal(
                targetWorld.getRegistryKey(), arrived.getUuid(), world.getServer().getTicks());

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
     * Arrival-side return, called from {@code EntityTickPortalMixin} for
     * every non-player entity.
     *
     * <p><b>Returns true only when it actually teleported</b> — the caller
     * cancels the vanilla callback on true alone, since cancelling otherwise
     * would break vanilla portal handling for every entity in the game.
     *
     * <p>Runs on every non-player entity every tick, so gates are ordered
     * cheapest-and-most-selective first: the class check rejects players and
     * everything off the allow-list before anything touches the world, and
     * only then is the block state checked — vanilla caches that per tick.
     *
     * <p>Checks only the entity's own block, not also above and below it
     * (unlike the player path). That is exact for sub-block entities and
     * vertical doorways; the gap is an entity resting ON a horizontal
     * (END_PORTAL) arrival, whose block position is the air above the
     * portal.
     *
     * <p>Exit modes are honoured only where they mean something without a
     * player: {@code "worldSpawn"} resolves, a plain link or {@code "origin"}
     * falls back to the recorded source world and Y, and {@code "bed"} /
     * {@code "dim!..."} are skipped rather than guessed at.
     */
    public static boolean tryReturnFromArrivalPortal(Entity entity, World rawWorld) {
        if (!(rawWorld instanceof ServerWorld world)) {
            return false;
        }
        if (!isEligible(entity)) {
            return false;
        }
        if (!PortalHelper.isPortalBlock(entity.getBlockStateAtPos())) {
            return false;
        }

        // The gate is the dimension config of the world the portal block is
        // IN, the same ImmersiveSettings every source zone targeting this
        // world carries — otherwise non-immersive dimensions' arrival
        // portals would gain new behaviour.
        RegistryKey<World> worldKey = world.getRegistryKey();
        MultiverseConfig config = MultiverseConfig.getInstance();
        ImmersiveSettings immersive = config != null ? config.getImmersiveFor(worldKey) : null;
        if (immersive == null || !immersive.entityPassthrough()) {
            return false;
        }

        // Direct lookup rather than a flood fill: every interior block of an
        // arrival portal is registered individually. A null target means
        // this is not one of our portals — leave it alone.
        BlockPos pos = entity.getBlockPos();
        PortalHelper.PortalReturnTarget target = PortalHelper.getPortalTarget(worldKey, pos);
        if (target == null) {
            return false;
        }

        // The entry edge, shared with the player path (see "Edge triggering"
        // in the class doc). Sampled only once the entity is confirmed
        // standing in one of OUR portals; "it stepped out" is inferred from
        // a gap in sightings, which needs the entity away for a full tick.
        int now = world.getServer().getTicks();
        if (!PortalHelper.enteredArrivalPortal(worldKey, entity.getUuid(), true,
                entity.getPortalCooldown() != 0, now)) {
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
            // "bed" and "dim!ns:slug!arrival" resolve against a player and
            // can never succeed for an entity; the entry edge stays CONSUMED
            // so re-testing every tick buys nothing.
            return false;
        }
        if (destination == null || destination.getRegistryKey().equals(worldKey)) {
            // Target world idle-unloaded: transient, so hand the edge back
            // and let a later tick carry the entity out (see
            // PortalHelper.rearmArrivalPortalEntry).
            PortalHelper.rearmArrivalPortalEntry(worldKey, entity.getUuid(), now);
            return false;
        }

        detachLeashBeforeCrossing(entity);

        Vec3d velocity = entity.getVelocity();
        Entity arrived = entity.teleportTo(new TeleportTarget(
                destination, new Vec3d(tx, ty, tz), velocity,
                entity.getYaw(), entity.getPitch(), TeleportTarget.ADD_PORTAL_CHUNK_TICKET));
        if (arrived == null) {
            PortalHelper.rearmArrivalPortalEntry(worldKey, entity.getUuid(), now);
            return false;
        }
        arrived.velocityModified = true;
        arrived.setPortalCooldown(target.cooldown);
        applyArrivalDifficulty(arrived);
        // The return often lands inside the source zone that sent the entity
        // here; seeding the destination's "was inside" set makes that read
        // as already-inside rather than a fresh entry.
        markInside(destination.getRegistryKey(), arrived.getUuid());
        // Same idea one layer up, for ARRIVAL portals rather than source
        // zones: whatever we just dropped the entity into counts as somewhere
        // it was already standing.
        PortalHelper.markArrivedInPortal(destination.getRegistryKey(), arrived.getUuid(), now);

        // Single-use countdowns are deliberately NOT armed from here or on
        // the source side: only a player traversal may burn a one-shot portal.
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
     * Break the lead before a leashed mob crosses, dropping it as any other
     * broken leash does.
     *
     * <p>{@code detachLeash(true, true)} is vanilla's own break call.
     * Refusing to move a leashed mob would strand it leashed to a holder in
     * another dimension — vanilla never breaks a leash across worlds, so
     * that would be permanent. Letting it cross leashed is also wrong: the
     * teleport recreates the mob from NBT with an UNRESOLVED holder, and
     * {@code resolveLeashData} either drops a phantom lead after
     * {@code age > 100} or spawns a stray {@code LeashKnotEntity} in the
     * destination tied to nothing. Detaching first is deterministic and
     * leaves no litter; the lead simply lands on the near side.
     *
     * <p>Mobs leashed TO the crossing entity need nothing here: the teleport
     * removes the original, and their next {@code tickLeash} sees a dead
     * holder and drops their own leads.
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
     * <p>{@code MobAttributeMixin} applies this mod's per-dimension
     * multipliers at {@code MobEntity.initialize}, which never runs for an
     * entity that walked in from elsewhere. {@link
     * DifficultyManager#applyMobModifiers} is idempotent by modifier id
     * (remove-then-add), so calling it here replaces the origin's modifiers
     * with the destination's rather than stacking them. Scaling only applies
     * to MONSTER-spawn-group mobs, and a mob whose health scales is reset to
     * full — an arrival is treated exactly like a spawn.
     *
     * <p><b>Known gap:</b> a destination with no scaling returns early
     * WITHOUT stripping the origin's modifiers, so a mob walked out of a hard
     * dimension keeps its boost. Fixing this needs a strip-only path inside
     * {@code DifficultyManager}.
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

    /**
     * Resets all session state (server shutdown, tests).
     *
     * <p>Includes the arrival-portal presence map, which lives in
     * {@link PortalHelper} because the player path shares it — the two are one
     * mechanism and resetting half of it would leave the other half lying.
     */
    public static void clear() {
        INSIDE.clear();
        PortalHelper.clearArrivalPresence();
    }
}
