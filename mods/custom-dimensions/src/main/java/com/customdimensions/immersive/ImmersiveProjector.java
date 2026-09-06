package com.customdimensions.immersive;

import com.customdimensions.MultiverseServer;
import com.customdimensions.companion.DestinationFeed;
import com.customdimensions.config.ImmersiveSettings;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.config.PortalDefinition;
import com.customdimensions.portal.PortalAperture;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immersive portal preview, cross-portal audio, and particle passes: the
 * per-tick driver that activates, refreshes and tears down each player's
 * fake-block projection of the dimension on the other side of an immersive
 * portal, leaks the target dimension's biome ambience back through the
 * portal plane, and dresses the result with a coloured border on an active
 * preview's frame.
 *
 * <p>Presentation only. It never touches traversal, ignition, zone
 * validation or portal links — the blocks it sends are client-side illusions
 * never placed in the world, and the sounds it plays are ordinary
 * {@link World#playSound} packets aimed at the source-side portal position.
 *
 * <h2>Both directions</h2>
 * The tick runs two passes:
 * <ul>
 *   <li>{@link #tickSourceZones} — source portal ZONES in this world,
 *       previewing where they lead;</li>
 *   <li>{@link #tickArrivalPortals} — arrival PORTALS standing in this world
 *       (real portal blocks with a registered return target and no zone),
 *       previewing the world you would go back to.</li>
 * </ul>
 * They differ only in how the aperture, settings and destination mapping are
 * found. Everything downstream — the sightline mask, the chunk ticket, the
 * delta refresh, the stationary throttle and every teardown path — is
 * {@link #projectToPlayers} and {@link PlayerProjectionState}, shared
 * verbatim, so the two cannot drift apart in behaviour.
 *
 * <h2>Rule 1: never sync-load a chunk from here</h2>
 * Every read of the target world goes through a non-loading accessor, and a
 * null result means "skip" — the position keeps its real source block and
 * the zone waits for a later tick. {@link PortalHelper#findSurfaceY} is NOT
 * used because it force-generates; {@link ArrivalResolver} answers the same
 * question without loading anything, preferring the registered arrival
 * portal the player actually lands in over a heightmap our own frame has
 * raised.
 *
 * <h2>Rule 2: hold a chunk ticket, do not hope someone else did</h2>
 * <b>Regression guard — do not remove the ticket as "redundant with {@code
 * ImmersivePreloader}".</b> The pre-loader takes no ticket, so its
 * pre-generated chunks unload within seconds with no player in the target
 * world. Without a ticket of its own here, {@link ArrivalResolver#arrivalY}
 * would return {@link #NO_ARRIVAL} forever and the zone would be skipped
 * silently, and the pre-loader would never re-run because its dedupe is only
 * invalidated by a world unload — which never happens, only the chunks do.
 * The two mechanisms would guard each other into a permanent dead state. So
 * the projector holds its own {@link #PREVIEW_TICKET} on the columns
 * {@link #holdSet} names — {@link ProjectionVolume#targetChunks} for the block
 * slab, plus the core running forward from the arrival for a viewer drawing
 * the far side itself — for as long as any player is in range, and releases it
 * on every teardown path. The ticket also carries an expiry
 * ({@link #TICKET_EXPIRY_TICKS}), refreshed on a cadence while wanted, so a
 * missed release path self-heals instead of pinning chunks forever.
 *
 * <h2>Rule 3: an orderly shutdown is a teardown; a crash is not</h2>
 * {@link #clear()} restores every live projection before dropping its state.
 * A fake block only becomes real again because the server tells the client
 * so — if the process stops without that, the client keeps rendering
 * destination terrain the server has no record of, indistinguishable from a
 * live sightline-mask bug. {@code WorldLoaderMixin.onShutdown} injects at
 * {@code MinecraftServer.shutdown} HEAD, before the network stops and before
 * players are disconnected, so the corrections reach the client before the
 * connection closes.
 *
 * <p>A hard crash, OOM kill, or {@code kill -9} runs no shutdown hook and
 * restores nothing — the client is the only side that can then self-correct
 * (relog, F3+A, or walk out of render distance and back). Check the log for
 * a "restored ... before shutdown" line before treating a stray-block report
 * as a live projection bug.
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
     * Last {@link DestinationGlow} sampled for a zone, and the tick it was
     * taken on. Read by {@code PortalHelper.spawnParticles} so the dust
     * drifting out of an opening carries the colour and brightness of the
     * world behind it — the visual half of what {@link #tickAudio} does with
     * biome ambience.
     *
     * <p>Only ever written from a pass that has already resolved the arrival
     * column and holds its chunk ticket, so sampling never loads a chunk.
     */
    private static final Map<PortalHelper.PortalZone, GlowSample> GLOW = new ConcurrentHashMap<>();

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
     * How often the frame ring emits, in ticks. An every-tick spawn would be
     * too strong on top of {@code PortalHelper.spawnParticles}'s own rate — a
     * dust particle lives ~20-30 ticks, so it would read as a cloud rather
     * than a border. It divides {@link #PARTICLE_LOG_INTERVAL} exactly, so
     * the heartbeat never lands on an emitting tick.
     */
    private static final int EDGE_PARTICLE_INTERVAL = 10;

    /**
     * Dust scale for the frame ring. Below 1.0 on purpose: {@code
     * AbstractDustParticle} sizes its billboard at {@code 0.75 * scale} and
     * scales the lifetime with it too, so a smaller mote covers less of what
     * is behind it and clears sooner — a glow along the frame rather than a
     * row of tiles.
     */
    private static final float EDGE_PARTICLE_SCALE = 0.7f;

    /**
     * Heartbeat cadence (10s) for the edge-particle DEBUG line. Particles are
     * pure client-side output, so a periodic line is the only way to confirm
     * the pass is running without a human in the game.
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
     * How often the destination's light and biome colour are re-read (1s).
     * Both change on a day/night or a walk, never per tick, and the sample
     * costs a light lookup plus a biome lookup in an already-resident chunk.
     */
    private static final int GLOW_SAMPLE_INTERVAL = 20;

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

    /** A destination sample and the tick it was taken on. */
    private record GlowSample(DestinationGlow glow, long tick) {
    }

    /**
     * The far side's light and colour for a zone, or {@link
     * DestinationGlow#NONE} when none has been sampled — a destination whose
     * arrival chunk has not arrived, or a portal nobody is near. Callers apply it to their own colour; NONE leaves them unchanged.
     */
    public static DestinationGlow glowFor(PortalHelper.PortalZone zone) {
        GlowSample sample = zone == null ? null : GLOW.get(zone);
        return sample == null ? DestinationGlow.NONE : sample.glow();
    }

    /**
     * The tick a zone's glow was last sampled, or -1 for never. A glow of
     * {@code NONE} and one nobody has sampled read alike without it.
     */
    public static long glowSampledAt(PortalHelper.PortalZone zone) {
        GlowSample sample = zone == null ? null : GLOW.get(zone);
        return sample == null ? -1L : sample.tick();
    }

    /**
     * Who is currently holding a projection of this zone, and how many
     * positions each client is showing. Read-only, for a diagnostic: the
     * aperture's light is a fake block sent per viewer, so "is anything being
     * painted at all" is a question about this map.
     */
    public static List<Viewer> viewersOf(PortalHelper.PortalZone zone) {
        List<Viewer> out = new ArrayList<>();
        if (zone == null) {
            return out;
        }
        for (Map<PortalHelper.PortalZone, PlayerProjectionState> states : ACTIVE.values()) {
            PlayerProjectionState state = states.get(zone);
            if (state != null) {
                out.add(new Viewer(state.playerName(), state.projectedCount(),
                        state.pendingCarryOverCount()));
            }
        }
        return out;
    }

    /** One client's hold on a zone's projection. */
    public record Viewer(String playerName, int projectedCells, int carryOver) {
    }

    /**
     * The far side's light and colour for a registered ARRIVAL position —
     * the world a player would go back to, seen through the portal they came
     * out of. Asked by {@code PortalHelper.spawnTargetPortalParticles}, which
     * holds a position rather than a zone.
     */
    public static DestinationGlow glowForArrival(RegistryKey<World> world, BlockPos pos) {
        Map<BlockPos, ArrivalPortal> index = ARRIVALS.get(world);
        if (index == null || index.isEmpty()) {
            return DestinationGlow.NONE;
        }
        for (ArrivalPortal arrival : index.values()) {
            if (arrival.zone.interior.contains(pos)) {
                return glowFor(arrival.zone);
            }
        }
        return DestinationGlow.NONE;
    }

    /**
     * The immersive arrival apertures standing in this world, as the
     * synthetic zones everything here is already keyed on. Asked by {@code
     * PortalHelper.spawnTargetPortalParticles} so an arrival's opening is
     * planned as one aperture rather than one loose position at a time.
     */
    public static List<PortalHelper.PortalZone> immersiveArrivals(RegistryKey<World> world) {
        Map<BlockPos, ArrivalPortal> index = ARRIVALS.get(world);
        if (index == null || index.isEmpty()) {
            return List.of();
        }
        List<PortalHelper.PortalZone> zones = new ArrayList<>(index.size());
        for (ArrivalPortal arrival : index.values()) {
            zones.add(arrival.zone);
        }
        return zones;
    }

    /** Settings arrivals in this world are projected with, or null if it isn't immersive. */
    public static ImmersiveSettings arrivalSettings(RegistryKey<World> world) {
        return ARRIVAL_SETTINGS.get(world);
    }

    /**
     * Re-read the destination's light and biome colour on {@link
     * #GLOW_SAMPLE_INTERVAL}. Callers must already hold the zone's chunk
     * ticket and have a resolved arrival column, so this never loads a chunk.
     */
    private static void sampleGlow(PortalHelper.PortalZone zone, ServerWorld targetWorld,
            BlockPos arrivalPos, long tick) {
        GlowSample existing = GLOW.get(zone);
        if (existing != null && tick - existing.tick() < GLOW_SAMPLE_INTERVAL) {
            return;
        }
        DestinationGlow glow = DestinationGlow.sample(targetWorld, arrivalPos);
        GLOW.put(zone, new GlowSample(glow, tick));
        if (existing == null || !existing.glow().equals(glow)) {
            // Only on a change, so a portal onto a stable column says this
            // once: the values, not the event, are what tell you whether the
            // far side is actually reaching the opening.
            MultiverseServer.LOGGER.debug(
                    "immersive: destination glow light={} tint=#{} from {} at {} for zone {}",
                    glow.light(), String.format("%06X", Math.max(0, glow.tint())),
                    targetWorld.getRegistryKey().getValue(), arrivalPos.toShortString(),
                    zone.sourceWorld.getValue());
        }
    }

    /**
     * One zone's ticketed chunk columns in one target world. {@code chunks} is
     * what carries a ticket and is what {@link #releaseChunks} removes;
     * {@code previewBox} is the geometric half of it, cached because it does
     * not move.
     */
    private static final class HeldChunks {
        private final RegistryKey<World> targetWorld;
        private final List<ChunkPos> previewBox;
        private final List<ChunkPos> chunks;
        private final long lastRefreshTick;

        private HeldChunks(RegistryKey<World> targetWorld, List<ChunkPos> previewBox,
                List<ChunkPos> chunks, long tick) {
            this.targetWorld = targetWorld;
            this.previewBox = previewBox;
            this.chunks = chunks;
            this.lastRefreshTick = tick;
        }
    }

    /**
     * Per-world tick. Called from {@code ServerWorldMixin.onTick} after the
     * teleport loop (so a player who stepped through this tick is already
     * gone) and after {@code PortalAuraManager.tick} (so the projection sees
     * post-aura blocks).
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
        // Projection zones, not source zones: a vanillaManaged portal's
        // presentation zone is drawn through here and nowhere else.
        List<PortalHelper.PortalZone> sourceZones =
                PortalHelper.getProjectionZones(world.getRegistryKey());
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
            Set<Direction> localFarSides = localDrawerFarSides(players, centre, zone, range);

            ProjectionVolume.TargetMapping mapping = null;
            int arrivalY = NO_ARRIVAL;
            boolean unresolvedLink = false;
            if (anyoneNear && targetWorld != null) {
                ProjectionVolume.TargetMapping scaled = mappingFor(zone, def);
                if (def.isVanillaManaged() && !def.hasAnchor()) {
                    // Vanilla builds its far portal wherever PortalForcer finds
                    // room, never at the scaled column. No link, no preview.
                    BlockPos link = VanillaLinkResolver.resolve(targetWorld, zone,
                            new BlockPos(scaled.arrivalX(), scaled.interiorMinY(), scaled.arrivalZ()),
                            tick);
                    unresolvedLink = link == null;
                    if (link != null) {
                        mapping = ProjectionVolume.anchorMapping(zone.interior, link.getX(), link.getZ());
                        holdChunks(targetWorld, zone, mapping, immersive, tick, localFarSides);
                        arrivalY = link.getY();
                    }
                } else {
                    mapping = scaled;
                    holdChunks(targetWorld, zone, mapping, immersive, tick, localFarSides);
                    arrivalY = ArrivalResolver.arrivalY(
                            targetWorld, mapping.arrivalX(), mapping.arrivalZ(), zone.axis);
                }
            } else {
                releaseChunks(zone, running);
            }

            if (unresolvedLink) {
                // Torn down, not held: a preview left standing over a portal
                // vanilla no longer links to is the defect being fixed.
                cleanupZone(zone);
                continue;
            }

            // Whether at least one player is actually being shown this
            // zone's preview right now. Edge particles frame a projection;
            // with nobody projecting there is nothing to frame, and the
            // packets would be spent on an ordinary-looking portal.
            boolean projecting = projectToPlayers(world, zone, targetWorld, mapping, arrivalY,
                    immersive, players, centre, tick, "source");

            // The far side's leak, audible and visible, on one gate: the same
            // audience test as the chunk ticket (anyoneNear) and the same
            // arrival column as the block projection (mapping/arrivalY), so
            // neither ever resolves a second, divergent notion of "where the
            // other side is". Skipped whenever the projection itself would be
            // — no ticket-holder, no target world, or the arrival chunk isn't
            // loaded yet (NO_ARRIVAL) — which also means never loading a
            // chunk to sample a biome or a light level.
            if (anyoneNear && targetWorld != null && arrivalY != NO_ARRIVAL) {
                BlockPos arrivalPos = new BlockPos(mapping.arrivalX(), arrivalY, mapping.arrivalZ());
                sampleGlow(zone, targetWorld, arrivalPos, tick);
                if (immersive.audio()) {
                    tickAudio(world, targetWorld, centre, arrivalPos, tick);
                }
            }

            if (projecting) {
                spawnEdgeParticles(world, zone, def, immersive, centre, tick);
            }
        }
    }

    /**
     * The RETURN direction: arrival portals standing in this world, previewing
     * the world you would go back to.
     *
     * <p>An arrival portal is not a {@code PortalZone} and has no
     * {@code PortalDefinition} — it is an entry per portal BLOCK in {@code
     * PortalHelper}'s registered return targets, so the three things a
     * projection needs are each sourced differently: settings come from the
     * dimension the portal is IN ({@code getImmersiveFor}), the aperture is
     * found by growing over REGISTERED positions rather than block states
     * ({@link ProjectionVolume#collectAperture}, so Rule 1 cannot be violated
     * even by an aperture straddling an unloaded chunk border), and the
     * mapping is {@link ProjectionVolume#returnMapping}, mirroring {@code
     * EntityTickPortalMixin}'s registered fallback. Everything after that —
     * mask, ticket, delta, throttle, teardown — is {@link #projectToPlayers},
     * the same code the source direction runs.
     *
     * <p>The portal blocks themselves are never faked: unlike a source zone
     * (invisible, no blocks), an arrival aperture is full of real portal
     * blocks that are load-bearing for vanilla's in-portal detection and the
     * return trip itself. The synthetic zone's interior IS the aperture, and
     * {@link ProjectionVolume#computeSourcePositions} starts its slab one
     * block past the plane, so the projection sits behind the portal exactly
     * as on the source side.
     *
     * <p>Deliberately not done here: edge particles (the aperture already
     * carries its own from {@code spawnTargetPortalParticles}) or an audio
     * relay (it would double the sound budget for a destination the player
     * just came from).
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
            Set<Direction> localFarSides = localDrawerFarSides(players, centre, zone,
                    settings.activationRange());

            // Portal-destruction teardown. Closing an arrival deregisters
            // every cell of it (PortalHelper.closeArrival), so the registry
            // IS the liveness signal — no block read, no chunk access.
            // cleanupZone restores every viewer's real blocks and drops the
            // ticket, exactly as a broken source frame does.
            if (!PortalHelper.isRegisteredPortalPosition(worldKey, arrival.seed)) {
                cleanupZone(zone);
                index.remove(arrival.key);
                MultiverseServer.LOGGER.info(
                        "immersive: arrival projection dropped at {} {} (portal destroyed)",
                        worldKey.getValue(), arrival.key.toShortString());
                continue;
            }

            ServerWorld destination = running.getWorld(zone.targetWorld);
            if (anyoneNear && destination != null) {
                holdChunks(destination, zone, arrival.mapping, settings, tick, localFarSides);
                if (arrival.destinationY != NO_ARRIVAL) {
                    sampleGlow(zone, destination, new BlockPos(arrival.mapping.arrivalX(),
                            arrival.destinationY, arrival.mapping.arrivalZ()), tick);
                }
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
     * the column its own outbound mapping names, the same lookup {@link
     * ArrivalResolver} uses from the other side. Arrivals with no source zone
     * pointing at them ({@code exitPortal} frames, exit shrines) are not
     * found this way and get no preview.
     *
     * <p>Existing {@link ArrivalPortal} instances are REUSED whenever the
     * aperture is unchanged — load-bearing rather than an optimisation, since
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
     * Build one arrival's projection record from its arrival zone, or null
     * when it cannot or should not be projected.
     *
     * <p>The plane comes from the zone rather than from a block, because an
     * arrival has no blocks in it: several source zones with different axes
     * can share one arrival, and the zone is the record of which way the
     * frame that was actually built faces.
     */
    private static ArrivalPortal buildArrival(ServerWorld world, RegistryKey<World> worldKey, BlockPos seed) {
        PortalHelper.PortalZone arrivalZone = PortalHelper.arrivalZoneAt(worldKey, seed);
        if (arrivalZone == null) {
            // Nothing has recorded this arrival's geometry yet — the next
            // traversal to it does, on the reuse path.
            return null;
        }
        Direction.Axis axis = arrivalZone.axis;
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
        Set<BlockPos> aperture = new HashSet<>(arrivalZone.interior);
        if (aperture.isEmpty() || aperture.size() > MAX_APERTURE) {
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
                ProjectionVolume.returnMapping(aperture, target.sourceX, target.sourceZ), target.sourceY);
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
     * activate, refresh, hold or tear down each nearby player's fake-block view
     * of one aperture. Returns whether anyone is being shown it right now — a
     * projection held through {@link ProjectionPresence} counts, since the
     * client is still drawing it and the frame ring still frames it.
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
        boolean projecting = false;

        for (ServerPlayerEntity player : players) {
            Map<PortalHelper.PortalZone, PlayerProjectionState> states = ACTIVE.get(player.getUuid());
            PlayerProjectionState state = states != null ? states.get(zone) : null;
            double distanceSq = centre.getSquaredDistance(player.getBlockPos());
            // Held only inside the ticket's own band, so grace never outlives
            // the chunks that would refresh it.
            ProjectionPresence.Presence presence = destination == null
                    ? ProjectionPresence.Presence.CLEAR
                    : ProjectionPresence.of(distanceSq, range, range + DEACTIVATE_MARGIN,
                            range + TICKET_DROP_MARGIN, state != null, tick,
                            state != null ? state.outOfRangeSince() : ProjectionPresence.NOT_LEFT);

            // Destination world unloaded mid-projection (the idle unloader
            // closes pre-loaded-but-unvisited worlds) is treated exactly
            // like walking away: restore the real blocks.
            if (presence == ProjectionPresence.Presence.CLEAR) {
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

            if (presence == ProjectionPresence.Presence.HOLD) {
                // Nothing is sent and nothing is sampled: the client keeps
                // drawing what it has until the window expires or they return.
                if (state.outOfRangeSince() == ProjectionPresence.NOT_LEFT) {
                    state.setOutOfRangeSince(tick);
                    MultiverseServer.LOGGER.debug(
                            "immersive: {} projection held for {} at {} {} ({} ticks)",
                            direction, player.getName().getString(),
                            world.getRegistryKey().getValue(), centre.toShortString(),
                            ProjectionPresence.GRACE_TICKS);
                }
                projecting = true;
                continue;
            }
            if (state != null) {
                state.setOutOfRangeSince(ProjectionPresence.NOT_LEFT);
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
                // The cadence is the projection's own, not the world's — a
                // stationary viewer is refreshed a quarter as often. Nothing
                // is dropped by waiting: the delta baseline is untouched and
                // the next pass still sends everything that changed in the
                // meantime.
                state.sendDelta(player, world, destination, settings, mapping, destinationY, tick);
            }
            // Outside the refresh gate on purpose: a stationary viewer is
            // throttled to a quarter rate, and the far side moves while they
            // stand still. The feed holds its own cadence.
            state.feedEntities(player, destination, mapping, destinationY, tick);
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
     * Which sides of this zone are being drawn locally, as the far side each
     * viewer's own box reads. Empty when nobody in ticket range draws the far
     * side themselves.
     *
     * <p>Asked of the players in range rather than of {@code ACTIVE}: the
     * ticket is taken before the projection pass rebuilds a viewer's state, so
     * on the first pass after a world change {@code ACTIVE} names nobody — and
     * one refresh is long enough for a destination to drain.
     *
     * <p>A side rather than a flag because the two mouths read two different
     * volumes: {@link #holdSet} runs the core forward from the arrival, and
     * forward is only defined once you know which side the eye is on.
     */
    private static Set<Direction> localDrawerFarSides(List<ServerPlayerEntity> players,
            BlockPos centre, PortalHelper.PortalZone zone, int range) {
        int margin = HELD.containsKey(zone) ? TICKET_DROP_MARGIN : TICKET_HOLD_MARGIN;
        double ticketSq = (double) (range + margin) * (range + margin);
        Set<Direction> sides = EnumSet.noneOf(Direction.class);
        for (ServerPlayerEntity player : players) {
            if (centre.getSquaredDistance(player.getBlockPos()) <= ticketSq
                    && !com.customdimensions.companion.CompanionNetwork.streamsSlab(player.getUuid())) {
                sides.add(ProjectionVolume.viewerFarSide(
                        zone.interior, zone.axis, player.getBlockPos(), null));
            }
        }
        return sides;
    }

    /**
     * Is this registered portal position part of an immersive ARRIVAL, whose
     * blocks the projector fakes away so the aperture reads as a window
     * rather than a nether portal?
     *
     * <p>Asked by {@code PortalHelper.spawnTargetPortalParticles} so the
     * mod's own dust thins to match. Reads the arrival index only — no world
     * access — and is false for every non-immersive portal.
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

    /**
     * A coloured border on the frame blocks around an active preview,
     * marking where the real world stops and the projection starts. Spawned
     * on the FRAME ring (positions adjacent to the interior, in-plane),
     * which is exactly where the existing interior particles are not.
     *
     * <p>Emits on {@link #EDGE_PARTICLE_INTERVAL}, not every tick; the gate
     * is the first thing here so the nine ticks in ten that emit nothing
     * also build no ring set.
     */
    private static void spawnEdgeParticles(ServerWorld world, PortalHelper.PortalZone zone,
            PortalDefinition def, ImmersiveSettings settings, BlockPos centre, long tick) {
        // The ring is part of the opening, so it answers to the same density:
        // a portal asked for no particles has none on its frame either.
        if (!PortalAperture.emitsAtAll(settings.particleDensity())) {
            return;
        }
        long phased = tick + particlePhase(centre, EDGE_PARTICLE_INTERVAL);
        if (phased % EDGE_PARTICLE_INTERVAL != 0) {
            return;
        }
        // The frame is the portal, so the ring keeps full coverage while the
        // opening thins — and it carries the far side's colour and light, so
        // what edges the doorway is the world through it.
        int colour = glowFor(zone).applyTo(PortalHelper.parseColor(def.getColor()),
                settings.destinationTint(), settings.destinationLight());
        DustParticleEffect effect = new DustParticleEffect(dustColour(colour), EDGE_PARTICLE_SCALE);
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
            // only headless evidence this pass is running. Phased with the
            // emission, or an unphased check would never coincide with an
            // emitting tick for a zone with a non-zero stagger offset.
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
     * <ul>
     *   <li><b>DISCONNECT</b> — the packets would go nowhere; vanilla's
     *       chunk resend on the next login corrects any leftover fake
     *       blocks.</li>
     *   <li><b>JOIN</b> — a fresh client has just been sent REAL chunk data,
     *       so any surviving {@code lastSent} baseline is a lie. Without
     *       this, a player who relogs while still in range keeps a non-null
     *       state, every position compares equal to the stale baseline on
     *       the next {@code sendDelta}, and NOTHING is sent until they walk
     *       out of range and back.</li>
     * </ul>
     *
     * <p>{@code forget()} rather than {@code cleanup()} in both cases: there
     * is nothing to restore, since the client's block data is already the
     * real thing. Chunk tickets are untouched — they follow zone proximity,
     * not players.
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
     * <p><b>The restore is the point.</b> Releasing chunk tickets and
     * clearing {@link #ACTIVE} without sending anything would orphan every
     * projected position on every still-connected client (see "Rule 3" in
     * the class comment). Called from {@code WorldLoaderMixin.onShutdown},
     * which injects at {@code MinecraftServer.shutdown} HEAD, before the
     * network stops and before players are disconnected.
     */
    public static void clear() {
        MinecraftServer running = server;
        restoreEverything(running);
        for (PortalHelper.PortalZone zone : new ArrayList<>(HELD.keySet())) {
            releaseChunks(zone, running);
        }
        HELD.clear();
        GLOW.clear();
        ACTIVE.clear();
        ARRIVALS.clear();
        ARRIVAL_SETTINGS.clear();
        ARRIVAL_INDEX_TICK.clear();
        server = null;
    }

    /**
     * Restore every live projection, in both directions, through the ordinary
     * {@link PlayerProjectionState#cleanup} path, so shutdown gets the same
     * real-block-not-AIR restore and the same loaded-chunk guard as walking
     * out of range does.
     *
     * <p><b>Nothing here may prevent the server stopping.</b> {@code
     * shutdown()} saves every world after this returns, so an exception
     * escaping this method would cost world data — far worse than the
     * cosmetic residue it is cleaning up. Each projection is isolated, and
     * the whole pass catches {@code Throwable} deliberately: at this point
     * continuing to shut down is the correct response to any failure.
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
            ProjectionVolume.TargetMapping mapping, ImmersiveSettings settings, long tick,
            Collection<Direction> localFarSides) {
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
        List<ChunkPos> previewBox = held != null ? held.previewBox
                : ProjectionVolume.targetChunks(zone.interior, zone.axis, mapping,
                        settings.previewDepth(), settings.previewRadius());
        if (previewBox.isEmpty()) {
            return;
        }
        // Recomputed every refresh, not cached: the core runs forward from the
        // arrival on each side a local drawer is standing, and they move.
        List<ChunkPos> chunks = holdSet(previewBox,
                mapping.arrivalX() >> 4, mapping.arrivalZ() >> 4, localFarSides);
        for (ChunkPos pos : chunks) {
            // Re-adding an identical ticket resets its expiry without
            // touching the chunk level, so this is idempotent and cheap.
            targetWorld.getChunkManager().addTicket(PREVIEW_TICKET, pos, TICKET_RADIUS, pos);
        }
        boolean sizeChanged = held == null || held.chunks.size() != chunks.size();
        // Replaced rather than mutated: HELD is read by wantedByAnotherZone
        // while this zone is being refreshed. A column that has dropped out
        // keeps a ticket until TICKET_EXPIRY_TICKS retires it.
        HELD.put(zone, new HeldChunks(targetKey, previewBox, chunks, tick));
        if (sizeChanged) {
            MultiverseServer.LOGGER.info(
                    "immersive: holding {} arrival chunks in {} for zone {} {}",
                    chunks.size(), targetKey.getValue(), zone.sourceWorld.getValue(),
                    chunks.get(0));
        }
    }

    /**
     * The columns one zone tickets: the block slab's preview box always, plus
     * the columns a local drawer's own box reads, once per side anyone is
     * looking from.
     *
     * <p>The core runs FORWARD from the arrival —
     * {@link DestinationFeed#CORE_DEPTH} columns along the far side's normal,
     * {@link DestinationFeed#CORE_RADIUS} either side of it. A square centred
     * on the arrival spends nearly half its columns behind the aperture plane,
     * which no box reads, and reaches a third of the depth the client draws.
     *
     * <p>A column is ticketed whether or not it is resident, because a ticket
     * is how the far side comes to exist: the manager generates whatever the
     * level reaches ({@code ChunkTicketManager.addTicket} builds it at
     * {@code getLevelFromType(FULL) - radius}). Generation is asynchronous and
     * this never waits on it, which is what keeps it off [K6];
     * {@code ImmersivePreloader} does the same thing with a heavier ticket on
     * the approach path. The companion path's block source is meant to become
     * the client's own — this widens the server feed because the feed is what
     * exists today, not because feeding more is the destination.
     */
    public static List<ChunkPos> holdSet(List<ChunkPos> previewBox, int arrivalChunkX, int arrivalChunkZ,
            Collection<Direction> farSides) {
        LinkedHashSet<ChunkPos> held = new LinkedHashSet<>(previewBox);
        for (Direction side : farSides) {
            addCore(held, arrivalChunkX, arrivalChunkZ, side);
        }
        return new ArrayList<>(held);
    }

    /**
     * One viewer side's core, from {@link DestinationFeed#inCore} — the same
     * predicate the feed's wedge bypass reads, so the two cannot name
     * different columns.
     */
    private static void addCore(Set<ChunkPos> held, int arrivalChunkX, int arrivalChunkZ,
            Direction side) {
        DestinationFeed.Normal normal = side == null
                ? DestinationFeed.Normal.Y
                : DestinationFeed.normalOf(side.getAxis());
        boolean towardsHigh = side != null
                && side.getOffsetX() + side.getOffsetY() + side.getOffsetZ() > 0;
        int span = Math.max(DestinationFeed.CORE_RADIUS, DestinationFeed.CORE_DEPTH);
        for (int ox = -span; ox <= span; ox++) {
            for (int oz = -span; oz <= span; oz++) {
                if (DestinationFeed.inCore(ox, oz, normal, towardsHigh)) {
                    held.add(new ChunkPos(arrivalChunkX + ox, arrivalChunkZ + oz));
                }
            }
        }
    }

    /** Drop this zone's ticket, keeping chunks another zone still wants. */
    private static void releaseChunks(PortalHelper.PortalZone zone, MinecraftServer running) {
        // No ticket, no guarantee the arrival chunk is resident, so the
        // destination sample stops being evidence of anything. Dropped
        // unconditionally, ahead of the early return: a zone can be asked to
        // release without holding chunks, and a stale glow outliving its
        // ticket is what would tint an opening after its far side moved.
        GLOW.remove(zone);
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

    /**
     * The source -&gt; target transform for one zone, shared by the preview and
     * by every teleport through it — the player traversal in {@code
     * ServerWorldMixin} and the entity crossing in {@code EntityPassthrough}.
     *
     * <p>A second copy of this expression is how a portal comes to SHOW one
     * place and PUT you in another: a fixed-direction {@code /
     * def.getScale()} is right leaving the overworld and wrong leaving the
     * scaled world, and the two answers are {@code scale^2} apart.
     */
    public static ProjectionVolume.TargetMapping mappingFor(PortalHelper.PortalZone zone, PortalDefinition def) {
        if (def.hasAnchor()) {
            int[] anchor = def.getAnchorPos();
            return ProjectionVolume.anchorMapping(zone.interior, anchor[0], anchor[2]);
        }
        return ProjectionVolume.scaledMapping(zone.interior,
                scaleOf(zone.sourceWorld, def), scaleOf(zone.targetWorld, def));
    }

    /**
     * One side's coordinate scale. A zone lit inside the world its definition
     * leads to runs backwards through that definition ({@code PortalZone}
     * normalises its target to the overworld), so the scale cannot be applied
     * in a fixed direction — each side is asked for its own.
     *
     * <p>The definition is the authority for its own dimension and the
     * config for anything else, so a mod-owned portal keeps answering from
     * the definition that owns it.
     */
    private static double scaleOf(RegistryKey<World> world, PortalDefinition def) {
        try {
            if (world != null && world.equals(def.getTargetKey())) {
                return def.getScale();
            }
        } catch (RuntimeException ignored) {
            // A malformed targetDimension must not break the transform for a
            // zone that is otherwise fine; fall through to the config.
        }
        return MultiverseConfig.getInstance().getScaleFor(world);
    }

    /**
     * Cross-portal audio: biome ambience and mood sound bleeding through
     * from the target dimension, played at the SOURCE-side portal centre —
     * never in the target world, and never as a game event. {@link
     * World#playSound} sends packets straight to nearby players, bypassing
     * {@code GameEventSuppressionMixin}'s game-event drop for a managed
     * world with no players.
     *
     * <p>{@code arrivalPos} is the same column {@link ArrivalResolver}
     * already resolved for the block projection, so its chunk is guaranteed
     * loaded and sampling its biome never force-loads anything (Rule 1).
     *
     * <p>No weather relay: every {@code ServerWorld} except the overworld
     * shares one weather flag across the whole save, so
     * {@code isRaining()}/{@code isThundering()} can never disagree between
     * dimensions — do not add a rain/thunder relay without first giving
     * dimensions independent weather state.
     *
     * <p>Each leak has its own cadence, checked independently. Volumes stay
     * low and vanilla's distance falloff does the rest.
     */
    private static void tickAudio(ServerWorld world, ServerWorld targetWorld, BlockPos portalCentre,
            BlockPos arrivalPos, long tick) {
        if (tick % 40 == 0) {
            // Nether-family biomes carry a distinctive loop; most overworld
            // biomes have none, so overworld-to-overworld portals are
            // correctly silent here.
            targetWorld.getBiome(arrivalPos).value().getLoopSound().ifPresent(sound -> {
                world.playSound(null, portalCentre, sound.value(), SoundCategory.AMBIENT, 0.3f, 1.0f);
                MultiverseServer.LOGGER.debug("immersive: biome loop sound at {} from {}",
                        portalCentre.toShortString(), targetWorld.getRegistryKey().getValue());
            });
        }
        if (tick % 60 == 0) {
            // Mood sound (cave ambience) stands in for per-mob sounds:
            // LivingEntity.getAmbientSound() is protected and not worth
            // reaching for. Rolled at 15% so it doesn't loop constantly even
            // when the arrival column qualifies every pass.
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
