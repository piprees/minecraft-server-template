package com.customdimensions.immersive;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.ImmersiveSettings;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.config.PortalDefinition;
import com.customdimensions.portal.PortalHelper;
import com.customdimensions.portal.PortalShape;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immersive portal preview (Phase 1), cross-portal audio (Phase 2) and the
 * Phase 4 particle passes: the per-tick driver that activates, refreshes and
 * tears down each player's fake-block projection of the dimension on the
 * other side of an immersive portal, leaks the target dimension's biome
 * ambience back through the portal plane, and dresses the result — a
 * coloured border on the frame of an active preview (4b), and a denser cloud
 * for gateway zones that have no frame to project through at all (4d).
 *
 * Presentation only. It never touches traversal, ignition, zone validation
 * or portal links — the blocks it sends are client-side illusions that are
 * never placed in the world, and the sounds it plays are ordinary
 * {@link World#playSound} packets aimed at the source-side portal position.
 *
 * <h2>Both directions</h2>
 * The tick runs two passes over two different kinds of thing:
 * <ul>
 *   <li>{@link #tickSourceZones} — source portal ZONES in this world (the
 *       flood-filled openings players ignite), previewing where they lead;</li>
 *   <li>{@link #tickArrivalPortals} — arrival PORTALS standing in this world
 *       (real portal blocks with a registered return target, and no zone at
 *       all), previewing the world you would go back to.</li>
 * </ul>
 * They differ only in how the aperture, the settings and the destination
 * mapping are found. Everything downstream of that — the sightline mask, the
 * chunk ticket, the delta refresh, the stationary throttle and every teardown
 * path — is {@link #projectToPlayers} and {@link PlayerProjectionState},
 * shared verbatim, so the two cannot drift apart in behaviour.
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
 * ArrivalResolver} answers the same question without loading anything —
 * and answers it BETTER, by preferring the registered arrival portal the
 * player actually lands in over a heightmap our own frame has raised.
 *
 * <h2>Rule 2: hold a chunk ticket, do not hope someone else did</h2>
 * <b>Regression guard — do not remove the ticket as "redundant with Phase
 * 0's pre-loader".</b> Without it the preview works exactly once and then
 * dies forever, which is what shipped in the first cut of this phase:
 * <ul>
 *   <li>Phase 0's {@code ImmersivePreloader} generates a 5x5 arrival grid,
 *       but takes no ticket. With no player in the target world those
 *       chunks unload within seconds.</li>
 *   <li>{@link ArrivalResolver#arrivalY} then returns {@link #NO_ARRIVAL}
 *       forever and the zone is skipped silently — no log, no error.</li>
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
 *
 * <h2>Rule 3: an ORDERLY shutdown is a teardown; a crash is not</h2>
 * {@link #clear()} restores every live projection before dropping its state.
 * It has to, and the reason is worth reading before anyone "simplifies" it
 * back into a set of {@code clear()} calls:
 *
 * <p>A fake block only ever becomes real again because the server tells the
 * client so. If the process stops while projections are live, nobody tells
 * it — and after a restart the server has no record that those positions were
 * ever faked, so it will never correct them. The client goes on rendering
 * destination terrain that, server-side, is plain air. This cost most of a
 * debugging session in July 2026: a tester's screenshots of foliage floating
 * outside a portal frame looked exactly like a sightline-mask failure, but the
 * server's last projection activity was an hour old and a restart sat between
 * the two. Every jar install during local iteration was minting fresh ghosts.
 *
 * <p>The hook is sound for this: {@code WorldLoaderMixin.onShutdown} injects
 * at {@code MinecraftServer.shutdown} HEAD, which runs before {@code
 * getNetworkIo().stop()} and before {@code
 * PlayerManager.disconnectAllPlayers()}, so the player list is live and every
 * channel is open. {@code ClientConnection.send} writes with {@code
 * flush = true}, and the later {@code disconnect()} does {@code
 * channel.close().awaitUninterruptibly()} — a close queued behind writes that
 * have already been flushed. The corrections make it onto the wire.
 *
 * <p><b>What this cannot fix, honestly:</b> a hard crash, an OOM kill, {@code
 * kill -9}, or the container being torn out from under the process. None of
 * them run {@code shutdown()}, so none of them restore anything, and there is
 * no server-side mechanism that could — the knowledge of what was faked dies
 * with the process. That residue is only correctable by the CLIENT reloading
 * the affected chunks. If a player reports blocks from another dimension
 * hanging around a portal after a crash, the remedy is to make their client
 * re-request chunks: relog, press F3+A (reload chunks), or walk out past
 * render distance and back. Check the server log for a recent
 * "restored ... before shutdown" line before treating any such report as a
 * projection bug — its absence, plus a restart in the window, is the
 * signature of stale ghosts rather than a live defect.
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
     * world -&gt; aperture min corner -&gt; the arrival portal standing there.
     * Rebuilt on a cadence rather than every tick, and entries are reused
     * across rebuilds so their synthetic zone (the key into {@link #ACTIVE}
     * and {@link #HELD}) stays the same instance.
     */
    private static final Map<RegistryKey<World>, Map<BlockPos, ArrivalPortal>> ARRIVALS =
            new ConcurrentHashMap<>();

    /** Last tick each world's arrival index (and immersive setting) was asked for. */
    private static final Map<RegistryKey<World>, Long> ARRIVAL_INDEX_TICK = new ConcurrentHashMap<>();

    /**
     * Cached {@code getImmersiveFor} answer per world. Only present for a
     * world that IS immersive; absent means "not immersive, or not asked yet".
     */
    private static final Map<RegistryKey<World>, ImmersiveSettings> ARRIVAL_SETTINGS =
            new ConcurrentHashMap<>();

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

    /**
     * Arrival column's chunk isn't loaded yet — no projection data this pass.
     * Aliased from {@link ArrivalResolver} rather than re-declared: the two
     * are compared against each other on every tick.
     */
    private static final int NO_ARRIVAL = ArrivalResolver.NO_ARRIVAL;

    /**
     * Gateway cloud audience gate (4d). {@code ServerWorld.spawnParticles}
     * only forwards to players within 32 blocks unless forced (verified by
     * disassembling {@code sendToPlayerIfNearby} in the Yarn-mapped 1.21.1
     * jar: {@code isWithinDistance(..., force ? 512 : 32)}), so this is the
     * exact radius at which the packets stop being visible — not a tuning
     * knob, and deliberately NOT activationRange: a gateway has no
     * projection to activate, and its particles should not appear or vanish
     * on a config value that means something else.
     */
    private static final double GATEWAY_PARTICLE_RANGE_SQ = 32.0 * 32.0;

    /** 4d: cloud size, and the slow scale pulse that stops it reading as static. */
    private static final int GATEWAY_PARTICLE_COUNT = 2;
    private static final int GATEWAY_PULSE_PERIOD = 40;

    /**
     * How often the Phase 4 particle passes emit, in ticks.
     *
     * <h2>Why these are not 1</h2>
     * Both passes originally ran EVERY tick, on top of {@code
     * PortalHelper.spawnParticles}' pre-existing 2-per-interior-block-per-tick
     * — reported in-game as "the particle effects are very strong". A dust
     * particle lives roughly 20-30 ticks, so an every-tick spawn keeps ~20+
     * particles alive per position at all times: a cloud, not a border.
     *
     * At a 10-tick cadence the frame carries about two live particles per
     * block, which reads as a steady coloured outline of the opening rather
     * than smoke pouring out of it — a tenth of the particles and a tenth of
     * the {@code ParticleS2CPacket}s (one per ring block per emission). The
     * gateway cloud emits twice as often but at half the count, because for a
     * gateway zone the cloud is the ENTIRE immersive treatment (no frame, no
     * projection, nothing else to look at) — net, also about a tenth of what
     * it was.
     *
     * Both divide {@link #PARTICLE_LOG_INTERVAL} exactly, which is what keeps
     * the heartbeat below landing on an emitting tick. Deliberately constants
     * and not config: this is taste, and a knob for it would be one more
     * thing to get wrong.
     */
    private static final int EDGE_PARTICLE_INTERVAL = 10;
    private static final int GATEWAY_PARTICLE_INTERVAL = 5;

    /**
     * Heartbeat cadence (10s) for the 4b/4d particle DEBUG lines. Particles
     * are pure client-side output, so a periodic line is the only way to
     * confirm either pass is running without a human in the game.
     */
    private static final int PARTICLE_LOG_INTERVAL = 200;

    /**
     * How often the arrival index is re-derived, in ticks (1s).
     *
     * A rebuild scans every world's source zones, so it is not a per-tick
     * cost — but it is what makes a newly-built arrival portal start
     * previewing, so it cannot be slow either. It only ever runs for a world
     * that is both immersive and occupied.
     */
    private static final int ARRIVAL_INDEX_INTERVAL = 20;

    /**
     * The search box for an arrival portal around its zone's mapped column.
     * Same numbers as {@link ArrivalResolver}, which is the same question
     * asked from the other side — a portal one of them finds and the other
     * does not would preview one direction and not the other.
     */
    private static final int ARRIVAL_SEARCH_H = 5;
    private static final int ARRIVAL_SEARCH_V = 16;

    /**
     * Aperture size cap, mirroring {@code PortalHelper}'s private
     * {@code MAX_PORTAL_BLOCKS}. A registry that has somehow grown a
     * connected run of stale positions must not be able to walk the aperture
     * fill into a long loop from the world tick.
     */
    private static final int MAX_APERTURE = 128;

    private ImmersiveProjector() {
    }

    /**
     * One arrival portal being projected: its canonical key (the aperture's
     * min corner), the block the liveness check probes, the synthetic zone
     * that carries it through the shared machinery, its return mapping, and
     * the destination Y that mapping's floor row lands on.
     */
    private static final class ArrivalPortal {
        private final BlockPos key;
        private final BlockPos seed;
        private final PortalHelper.PortalZone zone;
        private final ProjectionVolume.TargetMapping mapping;
        private final int destinationY;

        private ArrivalPortal(BlockPos key, BlockPos seed, PortalHelper.PortalZone zone,
                ProjectionVolume.TargetMapping mapping, int destinationY) {
            this.key = key;
            this.seed = seed;
            this.zone = zone;
            this.mapping = mapping;
            this.destinationY = destinationY;
        }

        /**
         * Is a freshly derived record describing the same projection as this
         * one? Only then may the existing instance be kept — anything else
         * (a block broken out of the aperture, a re-registered destination)
         * means the old projection is describing somewhere it no longer is.
         */
        private boolean matches(ArrivalPortal other) {
            return this.destinationY == other.destinationY
                    && this.zone.axis == other.zone.axis
                    && this.zone.targetWorld.equals(other.zone.targetWorld)
                    && this.zone.interior.equals(other.zone.interior);
        }
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
        long tick = running.getTicks();
        tickSourceZones(world, running, tick);
        tickArrivalPortals(world, running, tick);
    }

    /**
     * The outbound direction: source portal zones in this world, previewing
     * the dimension they lead TO.
     */
    private static void tickSourceZones(ServerWorld world, MinecraftServer running, long tick) {
        List<PortalHelper.PortalZone> sourceZones = PortalHelper.getSourceZones(world.getRegistryKey());
        if (sourceZones.isEmpty()) {
            return;
        }
        // NOTE: no early return on an empty player list. This loop is also
        // the release path for tickets held by a zone whose last nearby
        // player disconnected or changed world.
        List<ServerPlayerEntity> players = world.getPlayers();

        for (PortalHelper.PortalZone zone : new ArrayList<>(sourceZones)) {
            PortalDefinition def = zone.definition;
            ImmersiveSettings immersive = def.getImmersive();
            if (immersive == null) {
                // Not an immersive portal: zero work, zero behavioural change.
                continue;
            }
            if (PortalShape.END_GATEWAY.equals(def.getShape())) {
                // Frameless single-block teleporter — there is no portal
                // plane to project blocks through, and none is ever built
                // for it. Phase 4d gives it the one immersive treatment it
                // can carry instead: a denser particle cloud, in place of
                // (not on top of) the standard zone particles that
                // PortalHelper.spawnParticles skips for exactly these zones.
                tickGatewayCloud(world, zone, def, players, tick);
                continue;
            }
            BlockPos centre = PortalShape.centreOf(zone.interior);
            if (centre == null) {
                continue;
            }

            ServerWorld targetWorld = running.getWorld(zone.targetWorld);
            int range = immersive.activationRange();

            // The ticket follows PROXIMITY, not successful activation: the
            // arrival surface can only be resolved once the chunks are
            // loaded, so waiting for activation to take the ticket would
            // never take it at all.
            boolean anyoneNear = anyoneWithinTicketRange(players, centre, zone, range);

            ProjectionVolume.TargetMapping mapping = null;
            int arrivalY = NO_ARRIVAL;
            if (anyoneNear && targetWorld != null) {
                mapping = mappingFor(zone, def);
                holdChunks(targetWorld, zone, mapping, immersive, tick);
                arrivalY = ArrivalResolver.arrivalY(
                        targetWorld, mapping.arrivalX(), mapping.arrivalZ(), zone.axis);
            } else {
                releaseChunks(zone, running);
            }

            // 4b: whether at least one player is actually being shown this
            // zone's preview right now. Edge particles frame a projection;
            // with nobody projecting there is nothing to frame, and the
            // packets would be spent on an ordinary-looking portal.
            boolean projecting = projectToPlayers(world, zone, targetWorld, mapping, arrivalY,
                    immersive, players, centre, tick, "source");

            if (projecting) {
                spawnEdgeParticles(world, zone, def, centre, tick);
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
     * The RETURN direction: arrival portals standing in this world, previewing
     * the world you would go back to.
     *
     * <h2>Why this is not just another zone loop</h2>
     * An arrival portal is not a {@code PortalZone} and has no
     * {@code PortalDefinition}. What it has is an entry per portal BLOCK in
     * {@code PortalHelper}'s registered return targets. So the three things a
     * projection needs are each sourced differently:
     * <ul>
     *   <li><b>Settings</b> come from the dimension the portal is IN
     *       ({@code getImmersiveFor}), not from a zone — checked first, so a
     *       non-immersive dimension takes no further code path at all.</li>
     *   <li><b>The aperture</b> is found by growing over REGISTERED positions
     *       rather than block states ({@link ProjectionVolume#collectAperture})
     *       — no block reads, so Rule 1 cannot be violated even by an aperture
     *       that straddles an unloaded chunk border.</li>
     *   <li><b>The mapping</b> is {@link ProjectionVolume#returnMapping}, which
     *       mirrors {@code EntityTickPortalMixin}'s registered fallback.</li>
     * </ul>
     * Everything after that — mask, ticket, delta, throttle, teardown — is
     * {@link #projectToPlayers}, the same code the source direction runs.
     *
     * <h2>The portal blocks themselves are never faked</h2>
     * Unlike a source zone (invisible, no blocks), an arrival aperture is
     * full of real {@code NETHER_PORTAL}/{@code END_PORTAL}, and those blocks
     * are load-bearing for vanilla's in-portal detection and for the return
     * trip itself. Nothing here touches them: the synthetic zone's interior IS
     * the aperture, and {@link ProjectionVolume#computeSourcePositions} starts
     * its slab one block PAST the plane, so the projection sits behind the
     * portal exactly as it does on the source side.
     *
     * <h2>Deliberately not done here</h2>
     * No edge particles (the aperture already carries its own from
     * {@code spawnTargetPortalParticles}) and no audio relay (it would double
     * the sound budget for a destination the player just came from). The
     * arrival direction is block projection only.
     */
    private static void tickArrivalPortals(ServerWorld world, MinecraftServer running, long tick) {
        RegistryKey<World> worldKey = world.getRegistryKey();
        List<ServerPlayerEntity> players = world.getPlayers();
        if (players.isEmpty()) {
            // Nobody here to project to. Checked before anything else because
            // it is a field read: an empty world costs one comparison. Any
            // ticket held for a player who has just left still goes; the
            // INDEX is kept, since rebuilding it means scanning every world's
            // zones and the portals have not moved.
            Map<BlockPos, ArrivalPortal> held = ARRIVALS.get(worldKey);
            if (held != null) {
                for (ArrivalPortal arrival : held.values()) {
                    releaseChunks(arrival.zone, running);
                }
            }
            return;
        }

        // getImmersiveFor walks the portal list parsing each target id, so it
        // allocates — asking it every tick for every world would be pure
        // garbage on the tick loop. It rides the index cadence instead, and
        // the answer is cached alongside. A dimension that is not immersive
        // therefore costs three map lookups per tick and nothing else.
        Long asked = ARRIVAL_INDEX_TICK.get(worldKey);
        if (asked == null || tick - asked >= ARRIVAL_INDEX_INTERVAL) {
            ImmersiveSettings resolved = MultiverseConfig.getInstance().getImmersiveFor(worldKey);
            if (resolved == null) {
                // Not an immersive dimension — and if it USED to be one
                // (the setting is boot-re-read), give back what was projected.
                dropArrivals(worldKey);
                ARRIVAL_INDEX_TICK.put(worldKey, tick);
                return;
            }
            ARRIVAL_SETTINGS.put(worldKey, resolved);
            rebuildArrivalIndex(world, running, tick);
        }

        ImmersiveSettings settings = ARRIVAL_SETTINGS.get(worldKey);
        Map<BlockPos, ArrivalPortal> index = ARRIVALS.get(worldKey);
        if (settings == null || index == null || index.isEmpty()) {
            return;
        }

        for (ArrivalPortal arrival : new ArrayList<>(index.values())) {
            PortalHelper.PortalZone zone = arrival.zone;
            BlockPos centre = PortalShape.centreOf(zone.interior);
            if (centre == null) {
                continue;
            }
            boolean anyoneNear = anyoneWithinTicketRange(players, centre, zone,
                    settings.activationRange());

            // Portal-destruction teardown. The registry is never pruned when
            // a portal block is broken (see ArrivalResolver.stillAPortal), so
            // there is no event to hook — this IS the detection: one
            // loaded-chunk block read per arrival per tick, checked only
            // while someone is close enough for the chunk to be loaded and
            // for a leak to matter. cleanupZone restores every viewer's real
            // blocks and drops the ticket, exactly as a broken source frame
            // does.
            if (anyoneNear && !stillAPortalBlock(world, arrival.seed)) {
                cleanupZone(zone);
                index.remove(arrival.key);
                MultiverseServer.LOGGER.info(
                        "immersive: arrival projection dropped at {} {} (portal destroyed)",
                        worldKey.getValue(), arrival.key.toShortString());
                continue;
            }

            ServerWorld destination = running.getWorld(zone.targetWorld);
            if (anyoneNear && destination != null) {
                holdChunks(destination, zone, arrival.mapping, settings, tick);
            } else {
                releaseChunks(zone, running);
            }
            projectToPlayers(world, zone, destination, arrival.mapping, arrival.destinationY,
                    settings, players, centre, tick, "arrival");
        }
    }

    /**
     * Re-derive which arrival portals stand in this world.
     *
     * <p>Arrival portals are discovered through the SOURCE ZONES that built
     * them: a zone whose {@code targetWorld} is this world has its arrival at
     * the column its own outbound mapping names, which is the same lookup
     * {@link ArrivalResolver} uses to preview it from the other side. That
     * keeps discovery to in-memory reads plus one heightmap sample off an
     * already-loaded chunk.
     *
     * <p>Consequence worth knowing: arrivals with no source zone pointing at
     * them — {@code exitPortal} frames and exit shrines — are not found this
     * way and get no preview. Covering them needs a read accessor over
     * {@code PortalHelper}'s registered targets, which is not this session's
     * file to change.
     *
     * <p>Existing {@link ArrivalPortal} instances are REUSED whenever the
     * aperture is unchanged. That is load-bearing rather than an optimisation:
     * {@code ACTIVE} and {@code HELD} are keyed on the synthetic zone
     * instance, so handing out a fresh one each rebuild would orphan every
     * projection and every chunk ticket once a second.
     */
    private static Map<BlockPos, ArrivalPortal> rebuildArrivalIndex(ServerWorld world,
            MinecraftServer running, long tick) {
        RegistryKey<World> worldKey = world.getRegistryKey();
        Map<BlockPos, ArrivalPortal> previous = ARRIVALS.get(worldKey);
        Map<BlockPos, ArrivalPortal> next = new HashMap<>();

        for (ServerWorld other : running.getWorlds()) {
            for (PortalHelper.PortalZone zone
                    : new ArrayList<>(PortalHelper.getSourceZones(other.getRegistryKey()))) {
                if (!worldKey.equals(zone.targetWorld) || zone.definition == null) {
                    continue;
                }
                if (PortalShape.END_GATEWAY.equals(zone.definition.getShape())) {
                    // A gateway arrival is one frameless block: no plane, so
                    // nothing to project through (same rule as the source side).
                    continue;
                }
                ProjectionVolume.TargetMapping outbound = mappingFor(zone, zone.definition);
                int surfaceY = ArrivalResolver.heightmapSurfaceY(
                        world, outbound.arrivalX(), outbound.arrivalZ());
                if (surfaceY == NO_ARRIVAL) {
                    continue;
                }
                BlockPos seed = PortalHelper.findRegisteredPortalNear(worldKey,
                        outbound.arrivalX(), surfaceY, outbound.arrivalZ(),
                        ARRIVAL_SEARCH_H, ARRIVAL_SEARCH_V);
                if (seed == null) {
                    // The zone has never been traversed, so its arrival does
                    // not exist yet. It will on a later rebuild.
                    continue;
                }
                ArrivalPortal built = buildArrival(world, worldKey, seed);
                if (built == null || next.containsKey(built.key)) {
                    // Already found via another zone — anchor dimensions
                    // share one arrival between every source portal.
                    continue;
                }
                ArrivalPortal prior = previous != null ? previous.get(built.key) : null;
                if (prior != null && prior.matches(built)) {
                    next.put(built.key, prior);
                } else {
                    if (prior != null) {
                        // The aperture or its destination changed under us:
                        // the old projection describes somewhere else now.
                        cleanupZone(prior.zone);
                    }
                    next.put(built.key, built);
                }
            }
        }

        if (previous != null) {
            for (Map.Entry<BlockPos, ArrivalPortal> gone : previous.entrySet()) {
                if (!next.containsKey(gone.getKey())) {
                    cleanupZone(gone.getValue().zone);
                }
            }
        }
        ARRIVALS.put(worldKey, next);
        ARRIVAL_INDEX_TICK.put(worldKey, tick);
        return next;
    }

    /**
     * Build one arrival's projection record from a registered portal block,
     * or null when it cannot or should not be projected.
     *
     * <p>The axis comes from the real block state rather than from the source
     * zone that led here, because several zones with different axes can share
     * one arrival and only the block knows which way its plane actually
     * faces. That same read doubles as the liveness check: air at a
     * registered position means a stale registration, not a portal.
     */
    private static ArrivalPortal buildArrival(ServerWorld world, RegistryKey<World> worldKey, BlockPos seed) {
        WorldChunk chunk = world.getChunkManager().getWorldChunk(seed.getX() >> 4, seed.getZ() >> 4, false);
        if (chunk == null) {
            return null;
        }
        BlockState state = chunk.getBlockState(seed);
        Direction.Axis axis;
        if (state.isOf(Blocks.END_PORTAL)) {
            axis = Direction.Axis.Y;
        } else if (state.isOf(Blocks.NETHER_PORTAL) && state.contains(NetherPortalBlock.AXIS)) {
            axis = state.get(NetherPortalBlock.AXIS);
        } else {
            // Gateway (no plane), air (stale registration), or a block some
            // other mod put there.
            return null;
        }
        PortalHelper.PortalReturnTarget target = PortalHelper.getPortalTarget(worldKey, seed);
        if (target == null || target.sourceWorld == null || worldKey.equals(target.sourceWorld)) {
            return null;
        }
        if (target.exitMode != null) {
            // "bed" is per-player, and "worldSpawn"/"dim!..." land somewhere
            // that is not this portal's column at all — see returnMapping.
            // Previewing a place the player will not arrive at is worse than
            // previewing nothing, so anchor and exit-portal arrivals keep
            // their plain vanilla look.
            return null;
        }
        Set<BlockPos> aperture = ProjectionVolume.collectAperture(seed,
                PortalHelper.planeDirections(axis),
                pos -> PortalHelper.isRegisteredPortalPosition(worldKey, pos),
                MAX_APERTURE);
        if (aperture.isEmpty()) {
            return null;
        }
        BlockPos key = ProjectionVolume.minCorner(aperture);
        // A synthetic zone is what lets the arrival direction reuse every
        // teardown path unchanged: ACTIVE, HELD, cleanupZone, forgetPlayer,
        // forgetInWorld and onWorldUnload all key on a PortalZone and read
        // only interior/axis/sourceWorld/targetWorld. sourceWorld is where
        // the fake blocks are painted (this world) and targetWorld is where
        // they are sampled from, which is exactly the meaning those paths
        // already give the two fields.
        PortalDefinition definition = new PortalDefinition(
                "arrival:" + key.toShortString(), null, null,
                target.sourceWorld.getValue().toString(), null, 0);
        PortalHelper.PortalZone zone = new PortalHelper.PortalZone(
                aperture, definition, axis, worldKey, target.sourceWorld);
        return new ArrivalPortal(key, seed, zone,
                ProjectionVolume.returnMapping(aperture), target.sourceY);
    }

    /**
     * Is there still a portal block here? Null chunk means "no evidence" and
     * keeps the projection — an unloaded chunk must never be read as a
     * destroyed portal, and must never be loaded to find out (Rule 1). Same
     * discipline as {@code ArrivalResolver.stillAPortal}.
     */
    private static boolean stillAPortalBlock(ServerWorld world, BlockPos pos) {
        WorldChunk chunk = world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4, false);
        if (chunk == null) {
            return true;
        }
        return PortalHelper.isPortalBlock(chunk.getBlockState(pos));
    }

    /** Tear down and forget every arrival projection in one world. */
    private static void dropArrivals(RegistryKey<World> worldKey) {
        Map<BlockPos, ArrivalPortal> index = ARRIVALS.remove(worldKey);
        ARRIVAL_SETTINGS.remove(worldKey);
        ARRIVAL_INDEX_TICK.remove(worldKey);
        if (index == null) {
            return;
        }
        for (ArrivalPortal arrival : index.values()) {
            // Restores every viewer's real blocks and drops the ticket.
            cleanupZone(arrival.zone);
        }
    }

    /**
     * The per-player half of a projection, shared by BOTH directions:
     * activate, refresh, or tear down each nearby player's fake-block view of
     * one aperture. Returns whether anyone is being shown it right now.
     *
     * Factoring this out is what makes the arrival direction cost so little:
     * the sightline mask, the delta pass, the activation hysteresis, the
     * stationary throttle and — the part that matters — every teardown path
     * are the SAME CODE for both, not a parallel implementation that can
     * drift out of agreement with this one.
     *
     * {@code destination} null (world unloaded) and {@code destinationY}
     * {@link #NO_ARRIVAL} (chunks still loading) are both handled here, so
     * neither caller has to.
     */
    private static boolean projectToPlayers(ServerWorld world, PortalHelper.PortalZone zone,
            ServerWorld destination, ProjectionVolume.TargetMapping mapping, int destinationY,
            ImmersiveSettings settings, List<ServerPlayerEntity> players, BlockPos centre,
            long tick, String direction) {
        int range = settings.activationRange();
        double activateSq = (double) range * range;
        double deactivateSq = (double) (range + DEACTIVATE_MARGIN) * (range + DEACTIVATE_MARGIN);
        boolean projecting = false;

        for (ServerPlayerEntity player : players) {
            Map<PortalHelper.PortalZone, PlayerProjectionState> states = ACTIVE.get(player.getUuid());
            PlayerProjectionState state = states != null ? states.get(zone) : null;
            double distanceSq = centre.getSquaredDistance(player.getBlockPos());
            boolean inRange = distanceSq <= (state == null ? activateSq : deactivateSq);

            // Destination world unloaded mid-projection (the idle unloader
            // closes pre-loaded-but-unvisited worlds) is treated exactly
            // like walking away: restore the real blocks (Gotcha #11).
            if (!inRange || destination == null) {
                if (state != null) {
                    state.cleanup(player, world);
                    states.remove(zone);
                    ACTIVE.computeIfPresent(player.getUuid(), (k, v) -> v.isEmpty() ? null : v);
                    MultiverseServer.LOGGER.info(
                            "immersive: {} projection cleared for {} at {} {} ({})",
                            direction, player.getName().getString(),
                            world.getRegistryKey().getValue(), centre.toShortString(),
                            destination == null ? "destination world unloaded" : "out of range");
                }
                continue;
            }

            if (destinationY == NO_ARRIVAL) {
                // Ticketed chunks are still loading in. An existing
                // projection is left as-is rather than flickering; a new
                // one activates on a later tick, within a second or two.
                projecting |= state != null;
                continue;
            }

            if (state == null) {
                state = new PlayerProjectionState(player, zone);
                state.sendFull(player, world, destination, settings, mapping, destinationY, tick);
                ACTIVE.computeIfAbsent(player.getUuid(), k -> new ConcurrentHashMap<>()).put(zone, state);
                MultiverseServer.LOGGER.info(
                        "immersive: {} projection activated for {} at {} {} -> {} ({} blocks)",
                        direction, player.getName().getString(), world.getRegistryKey().getValue(),
                        centre.toShortString(), zone.targetWorld.getValue(), state.projectedCount());
            } else if (state.needsRefresh(player, tick, settings)) {
                // 4c: the cadence is the projection's own, not the
                // world's — a stationary viewer is refreshed a quarter
                // as often. Nothing is dropped by waiting: the delta
                // baseline is untouched and the next pass still sends
                // everything that changed in the meantime.
                state.sendDelta(player, world, destination, settings, mapping, destinationY, tick);
            }
            projecting = true;
        }
        return projecting;
    }

    /**
     * Is anyone close enough that this aperture should be holding its chunk
     * ticket? Wider band than the projection, with its own hysteresis (see
     * {@link #TICKET_HOLD_MARGIN}).
     */
    private static boolean anyoneWithinTicketRange(List<ServerPlayerEntity> players, BlockPos centre,
            PortalHelper.PortalZone zone, int range) {
        int margin = HELD.containsKey(zone) ? TICKET_DROP_MARGIN : TICKET_HOLD_MARGIN;
        double ticketSq = (double) (range + margin) * (range + margin);
        for (ServerPlayerEntity player : players) {
            if (centre.getSquaredDistance(player.getBlockPos()) <= ticketSq) {
                return true;
            }
        }
        return false;
    }

    /**
     * Does the projector own this zone's particles (4d)? True only for an
     * immersive gateway zone, which gets its denser cloud from {@link
     * #tickGatewayCloud} instead of the standard interior spawn.
     *
     * Asked by {@code PortalHelper.spawnParticles} so the two never double
     * up. The decision lives here rather than there because it is entirely
     * an immersive concern (PLAN.md Gotcha #12) — and it is false for every
     * non-immersive portal, which keeps its particles exactly as they were.
     */
    /**
     * Is this registered portal position part of an immersive ARRIVAL — one
     * whose blocks the projector fakes away so the aperture reads as a
     * window rather than a nether portal?
     *
     * <p>Asked by {@code PortalHelper.spawnTargetPortalParticles} so the
     * mod's own dust is thinned to match, instead of replacing the haze the
     * fake blocks just removed. Reads the arrival index only — no world
     * access, no allocation — and is false for every non-immersive portal,
     * which keeps their particles exactly as they were.
     */
    public static boolean isImmersiveArrival(RegistryKey<World> world, BlockPos pos) {
        Map<BlockPos, ArrivalPortal> index = ARRIVALS.get(world);
        if (index == null || index.isEmpty()) {
            return false;
        }
        for (ArrivalPortal arrival : index.values()) {
            if (arrival.zone.interior.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    public static boolean suppliesParticlesFor(PortalHelper.PortalZone zone) {
        return zone != null && zone.definition != null
                && zone.definition.getImmersive() != null
                && PortalShape.END_GATEWAY.equals(zone.definition.getShape());
    }

    /**
     * 4b: a coloured border on the frame blocks around an active preview,
     * marking where the real world stops and the projection starts. Spawned
     * on the FRAME ring (positions adjacent to the interior, in-plane),
     * which is exactly where the existing interior particles are not — the
     * two are complementary.
     *
     * Called only when a projection is live for someone, from the projector
     * tick rather than a second injection point.
     *
     * Emits on {@link #EDGE_PARTICLE_INTERVAL}, not every tick — see that
     * constant for why. The gate is the first thing here so the nine ticks in
     * ten that emit nothing also build no ring set.
     */
    private static void spawnEdgeParticles(ServerWorld world, PortalHelper.PortalZone zone,
            PortalDefinition def, BlockPos centre, long tick) {
        long phased = tick + particlePhase(centre, EDGE_PARTICLE_INTERVAL);
        if (phased % EDGE_PARTICLE_INTERVAL != 0) {
            return;
        }
        DustParticleEffect effect = new DustParticleEffect(
                dustColour(PortalHelper.parseColor(def.getColor())), 1.0f);
        Direction[] planeDirs = PortalHelper.planeDirections(zone.axis);
        Set<BlockPos> ring = new HashSet<>();
        for (BlockPos p : zone.interior) {
            for (Direction dir : planeDirs) {
                BlockPos edge = p.offset(dir);
                // Deduplicated: an irregular interior can present the same
                // frame block to two of its cells, and a doubled spawn there
                // would read as a bright spot on an otherwise even border.
                if (!zone.interior.contains(edge)) {
                    ring.add(edge);
                }
            }
        }
        for (BlockPos edge : ring) {
            world.spawnParticles(effect,
                    edge.getX() + 0.5, edge.getY() + 0.5, edge.getZ() + 0.5,
                    1, 0.1, 0.1, 0.1, 0.0);
        }
        if (phased % PARTICLE_LOG_INTERVAL == 0) {
            // Particles leave no server-side trace, so this heartbeat is the
            // only headless evidence that 4b is running. Phased with the
            // emission for the same reason it is a multiple of the interval:
            // an unphased check would never coincide with an emitting tick
            // for any zone whose stagger offset is non-zero, and 4b would
            // look dead in the log while working perfectly.
            MultiverseServer.LOGGER.debug("immersive: edge particles on {} frame blocks at zone {} {}",
                    ring.size(), world.getRegistryKey().getValue(), centre.toShortString());
        }
    }

    /**
     * Per-zone emission offset, so a hub with several immersive portals
     * spreads its particle packets across the interval instead of spiking
     * them all onto the same tick. Deterministic (position-derived), so a
     * zone keeps its phase for as long as it exists.
     */
    private static int particlePhase(BlockPos anchor, int interval) {
        return Math.floorMod(anchor.hashCode(), interval);
    }

    /**
     * 4d: the immersive treatment for gateway zones, which have no frame,
     * no plane and therefore no block projection — a denser, gently pulsing
     * cloud of the portal's own colour around the gateway block.
     *
     * No target-world sampling at all: a gateway zone never resolves a
     * mapping and never takes an arrival chunk ticket, so there is nothing
     * loaded to read a biome (or anything else) from, and reading one would
     * mean force-loading a chunk from the world tick — the one thing this
     * whole feature is built not to do. The pulse is therefore local: a
     * slow scale cycle rather than the biome-fog blend the phase doc
     * sketched.
     *
     * "Denser" is relative to the standard interior particles it replaces,
     * not to the every-tick firehose this originally was: it emits on {@link
     * #GATEWAY_PARTICLE_INTERVAL}, which still samples both halves of the
     * pulse four times each per cycle, so the breathing survives the cut.
     */
    private static void tickGatewayCloud(ServerWorld world, PortalHelper.PortalZone zone,
            PortalDefinition def, List<ServerPlayerEntity> players, long tick) {
        if (zone.interior.isEmpty()) {
            return;
        }
        BlockPos gatewayPos = zone.interior.iterator().next();
        long phased = tick + particlePhase(gatewayPos, GATEWAY_PARTICLE_INTERVAL);
        if (phased % GATEWAY_PARTICLE_INTERVAL != 0) {
            return;
        }
        boolean visible = false;
        for (ServerPlayerEntity player : players) {
            if (gatewayPos.getSquaredDistance(player.getBlockPos()) <= GATEWAY_PARTICLE_RANGE_SQ) {
                visible = true;
                break;
            }
        }
        if (!visible) {
            return;
        }
        float scale = tick % GATEWAY_PULSE_PERIOD < GATEWAY_PULSE_PERIOD / 2 ? 1.2f : 1.8f;
        world.spawnParticles(
                new DustParticleEffect(dustColour(PortalHelper.parseColor(def.getColor())), scale),
                gatewayPos.getX() + 0.5, gatewayPos.getY() + 0.5, gatewayPos.getZ() + 0.5,
                GATEWAY_PARTICLE_COUNT, 0.3, 0.3, 0.3, 0.02);
        if (phased % PARTICLE_LOG_INTERVAL == 0) {
            // As with the edge particles: a gateway zone has no projection to
            // log, so without this there is no headless signal at all.
            MultiverseServer.LOGGER.debug("immersive: gateway cloud at zone {} {}",
                    world.getRegistryKey().getValue(), gatewayPos.toShortString());
        }
    }

    /**
     * Packed RGB to the 0..1 vector {@link DustParticleEffect} wants.
     *
     * A three-line duplicate of {@code PortalHelper}'s private helper on
     * purpose: widening the portal package's API for a cosmetic feature buys
     * a permanent coupling to save three lines.
     */
    private static Vector3f dustColour(int colour) {
        return new Vector3f(
                ((colour >> 16) & 0xFF) / 255.0f,
                ((colour >> 8) & 0xFF) / 255.0f,
                (colour & 0xFF) / 255.0f);
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
        // Arrival portals standing in this world go with it. Done first, so
        // the ticket sweep below sees their zones already released rather
        // than stranding entries keyed on a world that is closing.
        dropArrivals(worldKey);
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

    /**
     * Server is stopping: give every connected player their real blocks back,
     * then drop all session state.
     *
     * <b>The restore is the point.</b> This used to release chunk tickets and
     * clear {@link #ACTIVE} without sending anything, which orphaned every
     * projected position on every still-connected client — see "Rule 3" in
     * the class comment for why that was invisible for a whole session.
     *
     * Called from {@code WorldLoaderMixin.onShutdown}, which injects at
     * {@code MinecraftServer.shutdown} HEAD — before {@code
     * getNetworkIo().stop()} and well before {@code
     * PlayerManager.disconnectAllPlayers()}, so the player list is live and
     * the channels are open.
     */
    public static void clear() {
        MinecraftServer running = server;
        restoreEverything(running);
        for (PortalHelper.PortalZone zone : new ArrayList<>(HELD.keySet())) {
            releaseChunks(zone, running);
        }
        HELD.clear();
        ACTIVE.clear();
        ARRIVALS.clear();
        ARRIVAL_SETTINGS.clear();
        ARRIVAL_INDEX_TICK.clear();
        server = null;
    }

    /**
     * Restore every live projection, in both directions, through the ordinary
     * {@link PlayerProjectionState#cleanup} path — not a parallel one, so
     * shutdown gets the same real-block-not-AIR restore (Gotcha #8) and the
     * same loaded-chunk guard as walking out of range does.
     *
     * <h2>Nothing here may prevent the server stopping</h2>
     * {@code shutdown()} goes on to save every world AFTER this returns, so an
     * exception escaping this method would cost world data — a far worse
     * outcome than the cosmetic residue it is trying to clean up. Each
     * projection is therefore isolated, and the whole pass is wrapped:
     * {@code Throwable}, deliberately, because at this point continuing to
     * shut down is the correct response to ANY failure, including one we have
     * not thought of.
     */
    private static void restoreEverything(MinecraftServer running) {
        if (running == null || ACTIVE.isEmpty()) {
            return;
        }
        try {
            int restoredPositions = 0;
            int restoredPlayers = 0;
            for (Map.Entry<UUID, Map<PortalHelper.PortalZone, PlayerProjectionState>> entry
                    : ACTIVE.entrySet()) {
                ServerPlayerEntity player = running.getPlayerManager().getPlayer(entry.getKey());
                if (player == null) {
                    // Already gone; their client has no fake blocks to fix,
                    // and the next join sends real chunk data anyway.
                    continue;
                }
                boolean restoredAny = false;
                for (PlayerProjectionState state : entry.getValue().values()) {
                    try {
                        // Read the count BEFORE cleanup empties the baseline.
                        int count = state.projectedCount();
                        state.cleanup(player, running.getWorld(state.sourceWorldKey()));
                        restoredPositions += count;
                        restoredAny = true;
                    } catch (RuntimeException e) {
                        // One bad projection must not cost the others theirs.
                        MultiverseServer.LOGGER.warn(
                                "immersive: could not restore a projection for {} at shutdown: {}",
                                state.playerName(), e.toString());
                    }
                }
                if (restoredAny) {
                    restoredPlayers++;
                }
            }
            if (restoredPositions > 0) {
                // Counts, not events: this line is the only evidence that a
                // restart handed the blocks back rather than orphaning them.
                MultiverseServer.LOGGER.info(
                        "immersive: restored {} projected positions for {} player(s) before shutdown",
                        restoredPositions, restoredPlayers);
            }
        } catch (Throwable t) {
            MultiverseServer.LOGGER.warn(
                    "immersive: shutdown restore pass failed, continuing to stop the server", t);
        }
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
     * Cross-portal audio (Phase 2): biome ambience and mood sound bleeding
     * through from the target dimension, played at the SOURCE-side portal
     * centre — never in the target world, and never as a game event.
     * {@code GameEventSuppressionMixin} drops every game event in a managed
     * world with no players, which the target world usually is here (the
     * approaching player is still on this side); {@link World#playSound}
     * sends packets straight to nearby players and bypasses that entirely
     * (PLAN.md Gotcha #7).
     *
     * {@code arrivalPos} is the same column {@link ArrivalResolver} already
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
