package com.customdimensions.immersive;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.ImmersiveSettings;
import com.customdimensions.config.PortalDefinition;
import com.customdimensions.portal.PortalHelper;
import com.customdimensions.portal.PortalShape;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immersive portal preview (Phase 1) and cross-portal audio (Phase 2): the
 * per-tick driver that activates, refreshes and tears down each player's
 * fake-block projection of the dimension on the other side of an immersive
 * portal, and leaks the target dimension's biome ambience back through the
 * portal plane.
 *
 * Presentation only. It never touches traversal, ignition, zone validation
 * or portal links — the blocks it sends are client-side illusions that are
 * never placed in the world, and the sounds it plays are ordinary
 * {@link World#playSound} packets aimed at the source-side portal position.
 *
 * <h2>Rule 1: never sync-load a chunk from here</h2>
 * Every read of the target world goes through a non-loading accessor
 * ({@code getChunkManager().getWorldChunk(cx, cz, false)}), and a null
 * result means "skip" — the position keeps its real source block and the
 * zone waits for a later tick. Sync-loading an ungenerated chunk from the
 * world tick is a known server-wedge trigger on this server
 * (mods/AGENTS.md "Known issues" — Epic Dungeons loot ids + c2me), and a
 * cosmetic preview must never be able to hang the tick loop. That is also
 * why {@link PortalHelper#findSurfaceY} is NOT used: it calls
 * {@code world.getChunk(...)}, which force-generates. {@link
 * #arrivalSurfaceY} reproduces its maths on an already-loaded chunk.
 *
 * <h2>Rule 2: hold a chunk ticket, do not hope someone else did</h2>
 * <b>Regression guard — do not remove the ticket as "redundant with Phase
 * 0's pre-loader".</b> Without it the preview works exactly once and then
 * dies forever, which is what shipped in the first cut of this phase:
 * <ul>
 *   <li>Phase 0's {@code ImmersivePreloader} generates a 5x5 arrival grid,
 *       but takes no ticket. With no player in the target world those
 *       chunks unload within seconds.</li>
 *   <li>{@link #arrivalSurfaceY} then returns {@link #NO_ARRIVAL} forever
 *       and the zone is skipped silently — no log, no error.</li>
 *   <li>The pre-loader never re-runs, because its dedupe is only
 *       invalidated by {@code ServerWorldEvents.UNLOAD} and the WORLD never
 *       unloaded — only its chunks did.</li>
 * </ul>
 * The two mechanisms guarded each other into a permanent dead state. So the
 * projector now owns its own chunk lifetime: while any player is in range of
 * an immersive zone it holds a {@link #PREVIEW_TICKET} on exactly the chunk
 * columns {@link ProjectionVolume#targetChunks} says it can sample, and
 * releases it on every teardown path. Tickets load asynchronously, so this
 * does not reintroduce the sync-load risk Rule 1 exists to prevent — the
 * {@code NO_ARRIVAL} guard simply covers the tick or two while the chunks
 * are still on their way.
 *
 * The ticket type also carries an expiry ({@link #TICKET_EXPIRY_TICKS}) and
 * is refreshed on a cadence while wanted: a leaked ticket pins chunks loaded
 * forever, which would be a worse bug than the one it fixes, so it
 * self-heals even if a release path is ever missed.
 */
public final class ImmersiveProjector {

    /**
     * player uuid -&gt; zone -&gt; projection. Zones are keyed by identity: a
     * {@code PortalZone} instance is stable for as long as it is registered,
     * and re-ignition produces a fresh instance, so a stale key can never
     * shadow a rebuilt portal.
     */
    private static final Map<UUID, Map<PortalHelper.PortalZone, PlayerProjectionState>> ACTIVE =
            new ConcurrentHashMap<>();

    /** Zones currently holding arrival-chunk tickets. */
    private static final Map<PortalHelper.PortalZone, HeldChunks> HELD = new ConcurrentHashMap<>();

    /**
     * Captured from the world tick so the zone-removal and world-unload
     * hooks (which have no world in hand) can resolve worlds and players.
     * Null before the first tick and after shutdown — callers degrade to
     * forgetting.
     */
    private static volatile MinecraftServer server;

    /**
     * Keeps a projection's arrival chunks loaded. Expiring by design: see
     * "Rule 2" above — an unreleased ticket must not pin chunks forever.
     */
    private static final int TICKET_EXPIRY_TICKS = 100;

    /** Re-add cadence while wanted; well inside the expiry window. */
    private static final int TICKET_REFRESH_TICKS = 20;

    /**
     * {@code ServerChunkManager.addTicket}'s int is a RADIUS, converted
     * internally to {@code ChunkLevels.getLevelFromType(FULL) - radius}
     * (verified by disassembling the Yarn-mapped 1.21.1 jar). Radius 0 is
     * therefore level 33 = exactly the listed chunk at FULL status, nothing
     * around it — the chunk is readable but not ticking, which is all a
     * block-state preview needs. The same radius must be passed to
     * {@code removeTicket} or the ticket will not match.
     */
    private static final int TICKET_RADIUS = 0;

    private static final ChunkTicketType<ChunkPos> PREVIEW_TICKET = ChunkTicketType.create(
            "customdimensions_preview", Comparator.comparingLong(ChunkPos::toLong), TICKET_EXPIRY_TICKS);

    /**
     * Deactivation happens this many blocks beyond activationRange so a
     * player standing on the boundary can't flap the projection (and the
     * log) on and off every tick.
     */
    private static final int DEACTIVATE_MARGIN = 2;

    /**
     * The chunk ticket uses a wider band than the projection, with its own
     * hysteresis: held from {@code activationRange + 6}, dropped only past
     * {@code activationRange + 10}. It is therefore always held while a
     * projection could be active, and a player loitering on the projection
     * boundary cannot flap ticket add/remove (or its log lines).
     */
    private static final int TICKET_HOLD_MARGIN = 6;
    private static final int TICKET_DROP_MARGIN = 10;

    /** Arrival column's chunk isn't loaded yet — no projection data this pass. */
    private static final int NO_ARRIVAL = Integer.MIN_VALUE;

    private ImmersiveProjector() {
    }

    /** One zone's ticketed chunk columns in one target world. */
    private static final class HeldChunks {
        private final RegistryKey<World> targetWorld;
        private final List<ChunkPos> chunks;
        private long lastRefreshTick;

        private HeldChunks(RegistryKey<World> targetWorld, List<ChunkPos> chunks, long tick) {
            this.targetWorld = targetWorld;
            this.chunks = chunks;
            this.lastRefreshTick = tick;
        }
    }

    /**
     * Per-world tick. Called from {@code ServerWorldMixin.onTick} after
     * {@code PortalAuraManager.tick} and before {@code ExitConditions.tick}
     * (PLAN.md Gotcha #12): after the teleport loop so a player who stepped
     * through this tick is already gone from this world, and after the aura
     * pass so the projection sees post-aura blocks.
     */
    public static void tick(ServerWorld world) {
        MinecraftServer running = world.getServer();
        server = running;

        List<PortalHelper.PortalZone> sourceZones = PortalHelper.getSourceZones(world.getRegistryKey());
        if (sourceZones.isEmpty()) {
            return;
        }
        // NOTE: no early return on an empty player list. This loop is also
        // the release path for tickets held by a zone whose last nearby
        // player disconnected or changed world.
        List<ServerPlayerEntity> players = world.getPlayers();

        long tick = running.getTicks();
        for (PortalHelper.PortalZone zone : new ArrayList<>(sourceZones)) {
            PortalDefinition def = zone.definition;
            ImmersiveSettings immersive = def.getImmersive();
            if (immersive == null) {
                // Not an immersive portal: zero work, zero behavioural change.
                continue;
            }
            if (PortalShape.END_GATEWAY.equals(def.getShape())) {
                // Frameless single-block teleporter — there is no portal
                // plane to project through. Silently excluded.
                continue;
            }
            BlockPos centre = PortalShape.centreOf(zone.interior);
            if (centre == null) {
                continue;
            }

            ServerWorld targetWorld = running.getWorld(zone.targetWorld);
            int range = immersive.activationRange();
            double activateSq = (double) range * range;
            double deactivateSq = (double) (range + DEACTIVATE_MARGIN) * (range + DEACTIVATE_MARGIN);

            // The ticket follows PROXIMITY, not successful activation: the
            // arrival surface can only be resolved once the chunks are
            // loaded, so waiting for activation to take the ticket would
            // never take it at all.
            int ticketMargin = HELD.containsKey(zone) ? TICKET_DROP_MARGIN : TICKET_HOLD_MARGIN;
            double ticketSq = (double) (range + ticketMargin) * (range + ticketMargin);
            boolean anyoneNear = false;
            for (ServerPlayerEntity player : players) {
                if (centre.getSquaredDistance(player.getBlockPos()) <= ticketSq) {
                    anyoneNear = true;
                    break;
                }
            }

            ProjectionVolume.TargetMapping mapping = null;
            int arrivalY = NO_ARRIVAL;
            if (anyoneNear && targetWorld != null) {
                mapping = mappingFor(zone, def);
                holdChunks(targetWorld, zone, mapping, immersive, tick);
                arrivalY = arrivalSurfaceY(targetWorld, mapping.arrivalX(), mapping.arrivalZ());
            } else {
                releaseChunks(zone, running);
            }

            for (ServerPlayerEntity player : players) {
                Map<PortalHelper.PortalZone, PlayerProjectionState> states = ACTIVE.get(player.getUuid());
                PlayerProjectionState state = states != null ? states.get(zone) : null;
                double distanceSq = centre.getSquaredDistance(player.getBlockPos());
                boolean inRange = distanceSq <= (state == null ? activateSq : deactivateSq);

                // Target world unloaded mid-projection (the idle unloader
                // closes pre-loaded-but-unvisited worlds) is treated exactly
                // like walking away: restore the real blocks (Gotcha #11).
                if (!inRange || targetWorld == null) {
                    if (state != null) {
                        state.cleanup(player, world);
                        states.remove(zone);
                        ACTIVE.computeIfPresent(player.getUuid(), (k, v) -> v.isEmpty() ? null : v);
                        MultiverseServer.LOGGER.info(
                                "immersive: projection cleared for {} at zone {} {} ({})",
                                player.getName().getString(), world.getRegistryKey().getValue(),
                                centre.toShortString(),
                                targetWorld == null ? "target world unloaded" : "out of range");
                    }
                    continue;
                }

                if (arrivalY == NO_ARRIVAL) {
                    // Ticketed chunks are still loading in. An existing
                    // projection is left as-is rather than flickering; a new
                    // one activates on a later tick, within a second or two.
                    continue;
                }

                if (state == null) {
                    state = new PlayerProjectionState(player, zone);
                    state.sendFull(player, world, targetWorld, immersive, mapping, arrivalY);
                    ACTIVE.computeIfAbsent(player.getUuid(), k -> new ConcurrentHashMap<>()).put(zone, state);
                    MultiverseServer.LOGGER.info(
                            "immersive: projection activated for {} at zone {} {} -> {} ({} blocks)",
                            player.getName().getString(), world.getRegistryKey().getValue(),
                            centre.toShortString(), zone.targetWorld.getValue(), state.projectedCount());
                } else if (tick % immersive.refreshInterval() == 0) {
                    state.sendDelta(player, world, targetWorld, immersive, mapping, arrivalY);
                }
            }

            // Audio pass (Phase 2): same audience gate as the chunk ticket
            // (anyoneNear) and the same arrival column as the block
            // projection (mapping/arrivalY), so it never resolves a second,
            // divergent notion of "where the other side is". Skipped
            // whenever the projection itself would be skipped this tick —
            // no ticket-holder, no target world, or the arrival chunk isn't
            // loaded yet (NO_ARRIVAL) — which also means never loading a
            // chunk just to sample a biome.
            if (immersive.audio() && anyoneNear && targetWorld != null && arrivalY != NO_ARRIVAL) {
                BlockPos arrivalPos = new BlockPos(mapping.arrivalX(), arrivalY, mapping.arrivalZ());
                tickAudio(world, targetWorld, centre, arrivalPos, tick);
            }
        }
    }

    /**
     * Zone removed (frame broken, single-use expiry): restore every player's
     * projection of it and drop its chunk ticket. Hooked into {@link
     * PortalHelper#removeZone} so it runs BEFORE the zone leaves the backing
     * list, and so any future removal path gets cleanup for free.
     */
    public static void cleanupZone(PortalHelper.PortalZone zone) {
        if (zone == null) {
            return;
        }
        MinecraftServer running = server;
        releaseChunks(zone, running);
        if (ACTIVE.isEmpty()) {
            return;
        }
        ServerWorld sourceWorld = running != null ? running.getWorld(zone.sourceWorld) : null;
        Iterator<Map.Entry<UUID, Map<PortalHelper.PortalZone, PlayerProjectionState>>> it =
                ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Map<PortalHelper.PortalZone, PlayerProjectionState>> entry = it.next();
            PlayerProjectionState state = entry.getValue().remove(zone);
            if (state != null) {
                ServerPlayerEntity player = running != null
                        ? running.getPlayerManager().getPlayer(entry.getKey()) : null;
                state.cleanup(player, sourceWorld);
                MultiverseServer.LOGGER.info(
                        "immersive: projection cleared for {} at zone {} (zone removed)",
                        state.playerName(), zone.sourceWorld.getValue());
            }
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
    }

    /**
     * Drop a player's tracking without sending anything. Wired to BOTH
     * connection edges:
     *
     * <ul>
     *   <li><b>DISCONNECT</b> — the packets would go nowhere, and vanilla's
     *       chunk resend on the next login is the defence-in-depth layer
     *       that corrects any leftover fake blocks.</li>
     *   <li><b>JOIN</b> — a fresh client has just been sent REAL chunk data
     *       for everything, so any surviving {@code lastSent} baseline is a
     *       lie. Without this, a player who relogs while still inside
     *       activationRange keeps a non-null state, the tick loop takes the
     *       {@code sendDelta} branch, every position compares equal to the
     *       stale baseline, and NOTHING is sent — no projection at all until
     *       they walk out of range and back. Belt-and-braces too: it covers
     *       any future path where DISCONNECT is missed.</li>
     * </ul>
     *
     * {@code forget()} rather than {@code cleanup()} in both cases: there is
     * nothing to restore, because the client's block data is already the
     * real thing. Chunk tickets are untouched — they follow zone proximity,
     * not players, so the tick loop already has them right.
     */
    public static void forgetPlayer(UUID playerId, String playerName, String reason) {
        Map<PortalHelper.PortalZone, PlayerProjectionState> states = ACTIVE.remove(playerId);
        if (states != null && !states.isEmpty()) {
            MultiverseServer.LOGGER.info(
                    "immersive: projection cleared for {} ({} zones, {})",
                    playerName, states.size(), reason);
        }
    }

    /**
     * Player changed world — including the common case of stepping THROUGH
     * the portal. Their projections in the world they LEFT are dropped
     * WITHOUT restore packets: the client is in another dimension now, so a
     * block update at those coordinates would paint source-world blocks into
     * the destination. The dimension change resends every chunk anyway.
     */
    public static void forgetInWorld(UUID playerId, String playerName, RegistryKey<World> worldKey) {
        Map<PortalHelper.PortalZone, PlayerProjectionState> states = ACTIVE.get(playerId);
        if (states == null || states.isEmpty()) {
            return;
        }
        int dropped = 0;
        Iterator<Map.Entry<PortalHelper.PortalZone, PlayerProjectionState>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            PlayerProjectionState state = it.next().getValue();
            if (state.sourceWorldKey().equals(worldKey)) {
                state.forget();
                it.remove();
                dropped++;
            }
        }
        if (states.isEmpty()) {
            ACTIVE.remove(playerId);
        }
        if (dropped > 0) {
            MultiverseServer.LOGGER.info(
                    "immersive: projection cleared for {} ({} zones in {}, player changed world)",
                    playerName, dropped, worldKey.getValue());
        }
    }

    /**
     * A world is closing. Zones TARGETING it lose their tickets with its
     * chunk manager, so those records are simply dropped; zones SOURCED in
     * it may hold tickets in a still-live target world, so those are
     * released properly.
     */
    public static void onWorldUnload(ServerWorld world) {
        RegistryKey<World> worldKey = world.getRegistryKey();
        MinecraftServer running = server;
        List<PortalHelper.PortalZone> sourced = new ArrayList<>();
        Iterator<Map.Entry<PortalHelper.PortalZone, HeldChunks>> it = HELD.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<PortalHelper.PortalZone, HeldChunks> entry = it.next();
            if (entry.getValue().targetWorld.equals(worldKey)) {
                it.remove();
            } else if (entry.getKey().sourceWorld.equals(worldKey)) {
                sourced.add(entry.getKey());
            }
        }
        for (PortalHelper.PortalZone zone : sourced) {
            releaseChunks(zone, running);
        }
    }

    /** Resets all session state (server shutdown). */
    public static void clear() {
        MinecraftServer running = server;
        for (PortalHelper.PortalZone zone : new ArrayList<>(HELD.keySet())) {
            releaseChunks(zone, running);
        }
        HELD.clear();
        ACTIVE.clear();
        server = null;
    }

    /**
     * Take (or refresh) the ticket on this zone's arrival chunks. Cheap on
     * the common path: the chunk set is only recomputed on the refresh
     * cadence, not every tick.
     */
    private static void holdChunks(ServerWorld targetWorld, PortalHelper.PortalZone zone,
            ProjectionVolume.TargetMapping mapping, ImmersiveSettings settings, long tick) {
        RegistryKey<World> targetKey = targetWorld.getRegistryKey();
        HeldChunks held = HELD.get(zone);
        if (held != null && !held.targetWorld.equals(targetKey)) {
            // Defensive: a zone's target is final, but never leak a ticket
            // in a world we are no longer pointing at.
            releaseChunks(zone, targetWorld.getServer());
            held = null;
        }
        if (held != null && tick - held.lastRefreshTick < TICKET_REFRESH_TICKS) {
            return;
        }
        List<ChunkPos> chunks = held != null ? held.chunks
                : ProjectionVolume.targetChunks(zone.interior, zone.axis, mapping,
                        settings.previewDepth(), settings.previewRadius());
        if (chunks.isEmpty()) {
            return;
        }
        for (ChunkPos pos : chunks) {
            // Re-adding an identical ticket resets its expiry without
            // touching the chunk level, so this is idempotent and cheap.
            targetWorld.getChunkManager().addTicket(PREVIEW_TICKET, pos, TICKET_RADIUS, pos);
        }
        if (held == null) {
            HELD.put(zone, new HeldChunks(targetKey, chunks, tick));
            MultiverseServer.LOGGER.info(
                    "immersive: holding {} arrival chunks in {} for zone {} {}",
                    chunks.size(), targetKey.getValue(), zone.sourceWorld.getValue(),
                    chunks.get(0));
        } else {
            held.lastRefreshTick = tick;
        }
    }

    /** Drop this zone's ticket, keeping chunks another zone still wants. */
    private static void releaseChunks(PortalHelper.PortalZone zone, MinecraftServer running) {
        HeldChunks held = HELD.remove(zone);
        if (held == null) {
            return;
        }
        // Resolved AFTER the remove, so the releasing zone can't count
        // itself as a reason to keep its own chunks.
        ServerWorld targetWorld = running != null ? running.getWorld(held.targetWorld) : null;
        if (targetWorld == null) {
            // World gone: its tickets went with its chunk manager.
            return;
        }
        for (ChunkPos pos : held.chunks) {
            if (wantedByAnotherZone(held.targetWorld, pos)) {
                continue;
            }
            targetWorld.getChunkManager().removeTicket(PREVIEW_TICKET, pos, TICKET_RADIUS, pos);
        }
        MultiverseServer.LOGGER.info(
                "immersive: released {} arrival chunks in {} for zone {}",
                held.chunks.size(), held.targetWorld.getValue(), zone.sourceWorld.getValue());
    }

    /**
     * Anchor dimensions share one arrival between many source portals, so
     * overlapping chunk sets are the norm rather than an edge case — and a
     * ticket is a single entry keyed on (type, level, argument), so the
     * first release would drop it for every holder without this check.
     */
    private static boolean wantedByAnotherZone(RegistryKey<World> targetWorld, ChunkPos pos) {
        for (HeldChunks other : HELD.values()) {
            if (other.targetWorld.equals(targetWorld) && other.chunks.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /** The source -&gt; target transform this zone's teleport would use. */
    private static ProjectionVolume.TargetMapping mappingFor(PortalHelper.PortalZone zone, PortalDefinition def) {
        if (def.hasAnchor()) {
            int[] anchor = def.getAnchorPos();
            return ProjectionVolume.anchorMapping(zone.interior, anchor[0], anchor[2]);
        }
        return ProjectionVolume.scaledMapping(zone.interior, def.getScale());
    }

    /**
     * The arrival surface Y, or {@link #NO_ARRIVAL} when the arrival
     * column's chunk isn't loaded yet. Same maths as
     * {@link PortalHelper#findSurfaceY} (so the preview lines up with where
     * the player actually lands), read off an already-loaded chunk instead
     * of force-generating one.
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

    /**
     * Cross-portal audio (Phase 2): biome ambience and mood sound bleeding
     * through from the target dimension, played at the SOURCE-side portal
     * centre — never in the target world, and never as a game event.
     * {@code GameEventSuppressionMixin} drops every game event in a managed
     * world with no players, which the target world usually is here (the
     * approaching player is still on this side); {@link World#playSound}
     * sends packets straight to nearby players and bypasses that entirely
     * (PLAN.md Gotcha #7).
     *
     * {@code arrivalPos} is the same column {@link #arrivalSurfaceY} already
     * resolved for the block projection — its chunk is guaranteed loaded (the
     * caller only reaches here once {@code arrivalY != NO_ARRIVAL}), so
     * sampling its biome never touches the chunk system and can never
     * force-load anything (Rule 1).
     *
     * No weather relay: originally planned (PHASE-2 §2c) but per-dimension
     * weather does not exist in vanilla 1.21.1. Every {@code ServerWorld}
     * except the overworld is built over an {@link
     * net.minecraft.world.level.UnmodifiableLevelProperties} wrapping
     * {@code saveProperties.getMainWorldProperties()} — the SAME instance
     * the overworld itself was constructed with — so {@code isRaining()}/
     * {@code isThundering()} always read one shared, save-wide flag no
     * matter which dimension asks. Confirmed both in vanilla
     * ({@code MinecraftServer.createWorlds}) and in this mod's own
     * {@code DimensionManager.getOrCreateDimension}/{@code
     * getOrCreateDimensionDirect}, which construct every runtime dimension
     * the identical way. {@code targetWorld.isRaining() != world.isRaining()}
     * can therefore never be true — do not re-add a rain/thunder relay
     * without first giving dimensions independent weather state, which
     * nothing in this codebase (or vanilla) currently does.
     *
     * Each leak has its own cadence, checked independently so biome loop and
     * mood can each land on their own tick within one call rather than
     * sharing a single gate. Volumes stay low and vanilla's distance falloff
     * (~16 blocks) does the rest — this must read as atmospheric background,
     * not a noise machine.
     */
    private static void tickAudio(ServerWorld world, ServerWorld targetWorld, BlockPos portalCentre,
            BlockPos arrivalPos, long tick) {
        if (tick % 40 == 0) {
            // Nether-family biomes carry a distinctive loop; most overworld
            // biomes have none, so overworld-to-overworld portals are
            // correctly silent here (PHASE-2 research notes).
            targetWorld.getBiome(arrivalPos).value().getLoopSound().ifPresent(sound -> {
                world.playSound(null, portalCentre, sound.value(), SoundCategory.AMBIENT, 0.3f, 1.0f);
                MultiverseServer.LOGGER.debug("immersive: biome loop sound at {} from {}",
                        portalCentre.toShortString(), targetWorld.getRegistryKey().getValue());
            });
        }
        if (tick % 60 == 0) {
            // Mood sound (cave ambience) stands in for per-mob sounds:
            // LivingEntity.getAmbientSound() is protected and not worth
            // reaching for (PHASE-2 doc). Rolled at 15% so it doesn't loop
            // constantly even when the arrival column qualifies every pass.
            targetWorld.getBiome(arrivalPos).value().getMoodSound().ifPresent(mood -> {
                if (world.random.nextFloat() < 0.15f) {
                    world.playSound(null, portalCentre, mood.getSound().value(), SoundCategory.AMBIENT, 0.2f, 1.0f);
                    MultiverseServer.LOGGER.debug("immersive: mood sound at {} from {}",
                            portalCentre.toShortString(), targetWorld.getRegistryKey().getValue());
                }
            });
        }
    }
}
