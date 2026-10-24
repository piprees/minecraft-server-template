package com.customdimensions.server.companion;

import com.customdimensions.core.companion.FeedBudget;
import com.customdimensions.core.config.MultiverseConfig;
import com.customdimensions.core.config.PortalDefinition;
import com.customdimensions.core.portal.PortalShape;
import com.customdimensions.server.level.LevelIndex;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.portal.Ignition;
import com.customdimensions.server.portal.PortalSelfTest;
import com.customdimensions.server.portal.PortalTickets;
import com.customdimensions.server.portal.PortalWorld;
import com.customdimensions.server.portal.PortalZone;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.impl.networking.UntrackedPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The companion feed against a live server: a lit portal into an open
 * destination, a companion, a vanilla player and a mismatched companion
 * standing at it, each on a packet listener that records every custom payload
 * it is sent (TROUBLESHOOTING.md T127). Hellos and view declarations go
 * through their codecs as a client's would.
 */
public final class CompanionSelfTest {
    static final BlockPos BAY = new BlockPos(3072, 200, 3072);
    static final int CAP = 20 * 60;

    private final MinecraftServer server;
    private final List<String> lines = new ArrayList<>();
    private final List<Waiter> waiters = new ArrayList<>();

    private record Waiter(BooleanSupplier done, CompletableFuture<Boolean> future, long deadline) {
    }

    /** One custom payload a test player was sent, and the tick it was sent on. */
    record Sent(long tick, CustomPacketPayload payload) {
        String id() {
            return payload.type().id().toString();
        }
    }

    /** A listener that records every custom payload instead of writing it to a socket. */
    static final class Recorder extends ServerGamePacketListenerImpl implements UntrackedPacketListener {
        final List<Sent> sent = new ArrayList<>();
        private final MinecraftServer clock;

        Recorder(MinecraftServer server, ServerPlayer player) {
            super(server, new Connection(PacketFlow.SERVERBOUND), player,
                    CommonListenerCookie.createInitial(player.getGameProfile(), false));
            this.clock = server;
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener) {
            if (packet instanceof ClientboundCustomPayloadPacket custom) {
                sent.add(new Sent(clock == null ? -1 : clock.getTickCount(), custom.payload()));
            }
        }

        long count(CustomPacketPayload.Type<?> type) {
            return sent.stream().filter(s -> s.payload().type().equals(type)).count();
        }
    }

    /** A plain server player on a {@link Recorder}, never added to the player list. */
    static final class Probe extends ServerPlayer {
        final Recorder recorder;

        Probe(MinecraftServer server, ServerLevel level, String name) {
            super(server, level, new GameProfile(UUID.nameUUIDFromBytes(("companion-" + name).getBytes()), name),
                    ClientInformation.createDefault());
            this.recorder = new Recorder(server, this);
            this.connection = recorder;
        }
    }

    private CompanionSelfTest(MinecraftServer server) {
        this.server = server;
    }

    public static CompletableFuture<List<String>> run(MinecraftServer server) {
        CompanionSelfTest test = new CompanionSelfTest(server);
        ServerTickEvents.END_SERVER_TICK.register(s -> test.poll());
        return test.run();
    }

    private void poll() {
        long now = server.getTickCount();
        List<Waiter> ready = new ArrayList<>();
        Iterator<Waiter> it = waiters.iterator();
        while (it.hasNext()) {
            Waiter w = it.next();
            boolean done;
            try {
                done = w.done.getAsBoolean();
            } catch (RuntimeException e) {
                it.remove();
                w.future.completeExceptionally(e);
                continue;
            }
            if (done || now >= w.deadline) {
                it.remove();
                ready.add(w);
            }
        }
        ready.forEach(w -> w.future.complete(w.done.getAsBoolean()));
    }

    private CompletableFuture<Boolean> until(BooleanSupplier done, int capTicks) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        waiters.add(new Waiter(done, f, server.getTickCount() + capTicks));
        return f;
    }

    private CompletableFuture<Boolean> ticks(int n) {
        long end = server.getTickCount() + n;
        return until(() -> server.getTickCount() >= end, n + 1);
    }

    // --- State shared across the steps ---------------------------------------------------------

    private PortalDefinition def;
    private String slug;
    private ResourceKey<Level> target;
    private PortalSelfTest.Bay bay;
    private PortalZone zone;
    private Probe companion;
    private Probe vanilla;
    private Probe mismatched;
    private BlockPos standing;

    private CompletableFuture<List<String>> run() {
        int pause = holdAwake();
        ServerLevel overworld = server.overworld();
        overworld.setChunkForced(BAY.getX() >> 4, BAY.getZ() >> 4, true);
        return choose()
                .thenCompose(v -> until(() -> overworld.getChunkSource().getChunkNow(BAY.getX() >> 4, BAY.getZ() >> 4)
                        != null, CAP))
                .thenCompose(v -> activate())
                .thenCompose(v -> light(overworld))
                .thenCompose(v -> standAndFeed(overworld))
                .thenCompose(v -> invalidation())
                .thenCompose(v -> handover(overworld))
                .thenCompose(v -> walkAway())
                .thenCompose(v -> breakPortal(overworld))
                .thenCompose(v -> unloadDestination(overworld))
                .thenCompose(v -> preload())
                .thenCompose(v -> cleanup(overworld))
                .handle((v, error) -> {
                    if (error != null) lines.add("FAIL companion feed: the check chain broke: " + cause(error));
                    for (Probe p : new Probe[]{companion, vanilla, mismatched}) {
                        if (p != null) CompanionNetwork.forget(p.getUUID());
                    }
                    overworld.setChunkForced(BAY.getX() >> 4, BAY.getZ() >> 4, false);
                    restorePause(pause);
                    lines.add(noneActive());
                    return List.copyOf(lines);
                });
    }

    private int holdAwake() {
        if (!(server instanceof DedicatedServer dedicated)) return -1;
        int seconds = dedicated.pauseWhenEmptySeconds();
        if (seconds > 0) dedicated.setPauseWhenEmptySeconds(0);
        return seconds;
    }

    private void restorePause(int seconds) {
        if (seconds > 0 && server instanceof DedicatedServer dedicated) dedicated.setPauseWhenEmptySeconds(seconds);
    }

    /** The first upright, two-wide definition into a lazy dimension whose frame and igniter exist here. */
    private CompletableFuture<Void> choose() {
        LevelManager m = LevelManager.get().orElseThrow(() -> new IllegalStateException("no level manager"));
        List<PortalDefinition> candidates = new ArrayList<>(MultiverseConfig.getInstance().getPortals());
        candidates.sort(Comparator.comparing(PortalDefinition::getId));
        for (PortalDefinition d : candidates) {
            String shape = PortalShape.normalise(d.getShape());
            if (d.isVanillaManaged() || d.hasAnchor() || !d.allowsAxis(com.customdimensions.core.Direction.Axis.X)
                    || PortalShape.END_GATEWAY.equals(shape) || PortalShape.DOOR.equals(shape)
                    || PortalShape.END_EXIT.equals(shape) || PortalWorld.block(d.getFramePlaceBlock()) == null
                    || item(d.getIgniterItem()) == null) {
                continue;
            }
            ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    Identifier.parse(d.getTargetDimension()));
            Optional<String> s = m.slugOf(key);
            if (s.isPresent() && m.index().lazy(s.get()).isPresent()) {
                def = d;
                slug = s.get();
                target = key;
                return CompletableFuture.completedFuture(null);
            }
        }
        throw new IllegalStateException("no definition leads to a lazy dimension with a usable frame");
    }

    private static net.minecraft.world.item.Item item(String id) {
        Identifier key = id == null ? null : Identifier.tryParse(id);
        return key != null && BuiltInRegistries.ITEM.containsKey(key) ? BuiltInRegistries.ITEM.getValue(key) : null;
    }

    private CompletableFuture<Void> activate() {
        return LevelManager.get().orElseThrow().activate(slug).thenApply(a -> null);
    }

    private CompletableFuture<Void> light(ServerLevel overworld) {
        bay = PortalSelfTest.build(overworld, def, Direction.Axis.X, BAY, null);
        Ignition.Outcome outcome = Ignition.ignite(overworld, bay.clicked(), new ItemStack(item(def.getIgniterItem())),
                null, InteractionHand.MAIN_HAND);
        if (outcome.zone() == null) {
            throw new IllegalStateException("not lit: " + outcome.refusal() + " " + outcome.detail());
        }
        zone = outcome.zone();
        return CompletableFuture.completedFuture(null);
    }

    private <T extends CustomPacketPayload> T viaWire(StreamCodec<RegistryFriendlyByteBuf, T> codec, T payload) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            codec.encode(buf, payload);
            return codec.decode(buf);
        } finally {
            buf.release();
        }
    }

    private void declare(Probe p, String version, boolean renderLocally) {
        if (version != null) {
            CompanionNetwork.hello(p, viaWire(CompanionPayloads.Hello.CODEC, new CompanionPayloads.Hello(version)));
        }
        CompanionNetwork.portalView(p, viaWire(CompanionPayloads.PortalView.CODEC,
                new CompanionPayloads.PortalView(renderLocally, false, 8, 64)));
    }

    private static void place(Probe p, BlockPos at) {
        p.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
    }

    private CompletableFuture<Void> standAndFeed(ServerLevel overworld) {
        standing = zone.landingCell().north(4);
        companion = new Probe(server, overworld, "companion");
        vanilla = new Probe(server, overworld, "vanilla");
        mismatched = new Probe(server, overworld, "skewed");
        for (Probe p : new Probe[]{companion, vanilla, mismatched}) place(p, standing);
        declare(companion, CompanionNetwork.version(), true);
        declare(vanilla, null, true);
        declare(mismatched, "0.0.0-not-this-release", true);
        long start = server.getTickCount();
        return until(() -> companion.recorder.count(CompanionPayloads.DestinationChunk.TYPE) >= 9
                && companion.recorder.count(CompanionPayloads.DestinationEntities.TYPE) >= 1, CAP).thenAccept(ok -> {
            Recorder r = companion.recorder;
            long frames = r.count(CompanionPayloads.PortalFrame.TYPE);
            long chunks = r.count(CompanionPayloads.DestinationChunk.TYPE);
            long entities = r.count(CompanionPayloads.DestinationEntities.TYPE);
            boolean only = ok && frames >= 1 && vanilla.recorder.sent.isEmpty() && mismatched.recorder.sent.isEmpty();
            lines.add((only ? "PASS" : "FAIL") + " companion feed reaches only the companion: into " + slug
                    + " the companion got " + histogram(r) + "; vanilla got " + vanilla.recorder.sent.size()
                    + " custom payloads " + histogram(vanilla.recorder) + "; the mismatched hello got "
                    + mismatched.recorder.sent.size() + " " + histogram(mismatched.recorder)
                    + "; companion admitted " + CompanionNetwork.isCompanion(companion.getUUID())
                    + ", mismatched admitted " + CompanionNetwork.isCompanion(mismatched.getUUID()));
            lines.add(budgetLine(r, start));
            lines.add(codecLine(r, frames, chunks, entities));
        });
    }

    private static String histogram(Recorder r) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        r.sent.forEach(s -> counts.merge(s.id().replace(CompanionPayloads.NAMESPACE + ":", ""), 1, Integer::sum));
        return counts.toString();
    }

    private int bytes(CustomPacketPayload p) {
        if (p instanceof CompanionPayloads.DestinationChunk c) {
            return CompanionNetwork.size(c, CompanionPayloads.DestinationChunk.CODEC, server.registryAccess());
        }
        if (p instanceof CompanionPayloads.DestinationEntities e) {
            return CompanionNetwork.size(e, CompanionPayloads.DestinationEntities.CODEC, server.registryAccess());
        }
        if (p instanceof CompanionPayloads.PortalFrame f) {
            return CompanionNetwork.size(f, CompanionPayloads.PortalFrame.CODEC, server.registryAccess());
        }
        if (p instanceof CompanionPayloads.ProjectionClear c) {
            return CompanionNetwork.size(c, CompanionPayloads.ProjectionClear.CODEC, server.registryAccess());
        }
        return 0;
    }

    /** At most the per-pass chunk count on any tick, and chunk bytes under the bucket's ceiling for the window. */
    private String budgetLine(Recorder r, long start) {
        Map<Long, Integer> perTick = new LinkedHashMap<>();
        long chunkBytes = 0;
        long otherBytes = 0;
        long last = start;
        for (Sent s : r.sent) {
            int b = bytes(s.payload());
            if (s.payload() instanceof CompanionPayloads.DestinationChunk) {
                perTick.merge(s.tick(), 1, Integer::sum);
                chunkBytes += b;
            } else {
                otherBytes += b;
            }
            last = Math.max(last, s.tick());
        }
        int worst = perTick.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        long window = last - start + 1;
        long ceiling = FeedBudget.ceiling(FeedBudget.DEFAULT_BYTES_PER_SECOND, window);
        boolean ok = worst > 0 && worst <= FeedBudget.DEFAULT_CHUNKS_PER_PASS && chunkBytes <= ceiling;
        return (ok ? "PASS" : "FAIL") + " companion feed stays within its budget: at most " + worst
                + " chunks on one tick (limit " + FeedBudget.DEFAULT_CHUNKS_PER_PASS + "), " + chunkBytes
                + " chunk bytes and " + otherBytes + " other bytes over " + window + " ticks (chunk ceiling "
                + ceiling + ")";
    }

    /** The live payloads round-trip through their codecs with this server's registries. */
    private String codecLine(Recorder r, long frames, long chunks, long entities) {
        try {
            int checked = 0;
            for (Sent s : r.sent) {
                CustomPacketPayload p = s.payload();
                if (p instanceof CompanionPayloads.DestinationChunk c) {
                    CompanionPayloads.DestinationChunk back = viaWire(CompanionPayloads.DestinationChunk.CODEC, c);
                    if (back.chunk().x() != c.chunk().x() || back.chunk().z() != c.chunk().z()
                            || !back.destination().equals(c.destination())) {
                        throw new AssertionError("chunk " + c.chunk().x() + "," + c.chunk().z() + " came back different");
                    }
                    checked++;
                } else if (p instanceof CompanionPayloads.DestinationEntities e) {
                    CompanionPayloads.DestinationEntities back = viaWire(CompanionPayloads.DestinationEntities.CODEC, e);
                    if (back.present().size() != e.present().size() || back.tracked().size() != e.tracked().size()) {
                        throw new AssertionError("entity snapshot came back with a different count");
                    }
                    checked++;
                } else if (p instanceof CompanionPayloads.PortalFrame f) {
                    if (!viaWire(CompanionPayloads.PortalFrame.CODEC, f).equals(f)) {
                        throw new AssertionError("frame came back different");
                    }
                    checked++;
                }
            }
            return "PASS companion payloads round-trip with live registries: " + checked + " payloads (" + frames
                    + " frames, " + chunks + " chunks, " + entities + " entity snapshots)";
        } catch (Throwable t) {
            return "FAIL companion payloads round-trip with live registries: " + t;
        }
    }

    /** A block change in a chunk already sent reaches the companion again (the one mixin, ChunkBlockChangedMixin). */
    private CompletableFuture<Void> invalidation() {
        ServerLevel there = LevelManager.get().orElseThrow().level(slug).orElseThrow();
        CompanionPayloads.DestinationChunk first = companion.recorder.sent.stream()
                .map(Sent::payload).filter(p -> p instanceof CompanionPayloads.DestinationChunk)
                .map(p -> (CompanionPayloads.DestinationChunk) p).findFirst().orElseThrow();
        int cx = first.chunk().x();
        int cz = first.chunk().z();
        BlockPos at = new BlockPos((cx << 4) + 8, there.getMinY() + there.getHeight() - 2, (cz << 4) + 8);
        if (there.getChunkSource().getChunkNow(cx, cz) == null) {
            lines.add("FAIL a block change on the far side is sent again: chunk " + cx + "," + cz + " is not resident");
            return CompletableFuture.completedFuture(null);
        }
        long before = timesSent(cx, cz);
        there.setBlock(at, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
        return until(() -> timesSent(cx, cz) > before, CAP).thenAccept(ok -> {
            there.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            lines.add((ok && vanilla.recorder.sent.isEmpty() ? "PASS" : "FAIL")
                    + " a block change on the far side is sent again: chunk " + cx + "," + cz + " sent "
                    + before + " time(s) before glass at " + at.toShortString() + ", " + timesSent(cx, cz) + " after");
        });
    }

    private long timesSent(int cx, int cz) {
        return companion.recorder.sent.stream().map(Sent::payload)
                .filter(p -> p instanceof CompanionPayloads.DestinationChunk c && c.chunk().x() == cx && c.chunk().z() == cz)
                .count();
    }

    private CompletableFuture<Void> handover(ServerLevel overworld) {
        long before = companion.recorder.count(CompanionPayloads.EntityHandover.TYPE);
        BlockPos key = zone.landingCell();
        ItemEntity item = new ItemEntity(overworld, key.getX() + 0.5, key.getY() + 0.3, key.getZ() + 0.5,
                new ItemStack(Items.DIAMOND), 0, 0, 0);
        item.setUnlimitedLifetime();
        item.setNeverPickUp();
        overworld.addFreshEntity(item);
        UUID id = item.getUUID();
        return until(() -> companion.recorder.count(CompanionPayloads.EntityHandover.TYPE) > before, CAP)
                .thenAccept(ok -> {
                    Entity arrived = null;
                    for (ServerLevel l : server.getAllLevels()) {
                        Entity e = l.getEntity(id);
                        if (e != null && !e.isRemoved()) arrived = e;
                    }
                    String where = arrived == null ? "nowhere" : arrived.level().dimension().identifier().toString();
                    if (arrived != null) arrived.discard();
                    lines.add((ok && vanilla.recorder.sent.isEmpty() ? "PASS" : "FAIL")
                            + " a crossing is handed over to the companion only: "
                            + (companion.recorder.count(CompanionPayloads.EntityHandover.TYPE) - before)
                            + " entity-handover sent for an item that landed in " + where + "; vanilla got "
                            + vanilla.recorder.sent.size());
                });
    }

    private long clears() {
        return companion.recorder.count(CompanionPayloads.ProjectionClear.TYPE);
    }

    private CompletableFuture<Boolean> fedAgain() {
        long frames = companion.recorder.count(CompanionPayloads.PortalFrame.TYPE);
        return until(() -> companion.recorder.count(CompanionPayloads.PortalFrame.TYPE) > frames
                && CompanionFeed.watching(companion.getUUID()) != null, CAP);
    }

    private CompletableFuture<Void> walkAway() {
        long before = clears();
        place(companion, standing.north(40));
        return until(() -> clears() > before && CompanionFeed.watching(companion.getUUID()) == null, CAP)
                .thenAccept(ok -> lines.add((ok ? "PASS" : "FAIL")
                        + " walking away stops the feed: " + (clears() - before) + " projection-clear at "
                        + bay.interior().stream().min(Comparator.naturalOrder()).orElseThrow().toShortString()
                        + " once the companion stood " + 40 + " blocks off, watching "
                        + CompanionFeed.watching(companion.getUUID())));
    }

    private CompletableFuture<Void> breakPortal(ServerLevel overworld) {
        place(companion, standing);
        return fedAgain().thenCompose(back -> {
            long before = clears();
            BlockPos side = bay.ring().stream()
                    .filter(r -> bay.interior().stream().anyMatch(c -> c.distManhattan(r) == 1))
                    .findFirst().orElse(bay.ring().get(0));
            overworld.setBlock(side, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            return until(() -> clears() > before && CompanionFeed.watching(companion.getUUID()) == null, CAP)
                    .thenAccept(ok -> lines.add((back && ok ? "PASS" : "FAIL")
                            + " breaking the portal stops the feed: fed again on return " + back + ", "
                            + (clears() - before) + " projection-clear after a frame block was removed, portal blocks left "
                            + bay.interior().stream().filter(p -> PortalWorld.isPortal(overworld.getBlockState(p))).count()));
        });
    }

    private CompletableFuture<Void> unloadDestination(ServerLevel overworld) {
        CompletableFuture<Void> relit = light(overworld);
        return relit.thenCompose(v -> fedAgain()).thenCompose(back -> {
            long before = clears();
            int ticketsBefore = PortalTickets.held(target);
            return LevelManager.get().orElseThrow().deactivate(slug).handle((t, e) -> e).thenCompose(error ->
                    until(() -> clears() > before, 40).thenAccept(ok -> {
                        boolean closed = !server.levelKeys().contains(target);
                        lines.add((back && ok && closed && PortalTickets.held(target) == 0 && error == null
                                ? "PASS" : "FAIL") + " unloading the destination stops the feed: fed again after relighting "
                                + back + ", " + (clears() - before) + " projection-clear, level closed " + closed
                                + ", arrival tickets " + ticketsBefore + " before and " + PortalTickets.held(target)
                                + " after" + (error == null ? "" : ", unload failed: " + cause(error)));
                    }));
        });
    }

    /**
     * A declined view and a vanilla player leave the destination lazy; a
     * declared one opens it for the companion; an unload while the companion
     * stays is not undone.
     */
    private CompletableFuture<Void> preload() {
        LevelManager m = LevelManager.get().orElseThrow();
        declare(companion, null, false);
        return ticks(100).thenCompose(v -> {
            LevelManager.State declined = m.state(slug);
            declare(companion, null, true);
            return fedAgain().thenCompose(opened -> {
                LevelManager.State after = m.state(slug);
                return m.deactivate(slug).handle((t, e) -> e).thenCompose(error -> ticks(100).thenAccept(x -> {
                    LevelManager.State stayed = m.state(slug);
                    lines.add((declined == LevelManager.State.LAZY && opened && after == LevelManager.State.ACTIVE
                            ? "PASS" : "FAIL") + " the pre-load opens a lazy destination only for a companion that draws it: "
                            + "state with the view declined and a vanilla player present " + declined
                            + ", after declaring " + after + ", fed " + opened);
                    lines.add((stayed == LevelManager.State.LAZY && error == null ? "PASS" : "FAIL")
                            + " the pre-load never reopens a destination unloaded while the companion stays: state 100 ticks"
                            + " after the unload " + stayed + ", arrival tickets " + PortalTickets.held(target));
                }));
            });
        });
    }

    private CompletableFuture<Void> cleanup(ServerLevel overworld) {
        PortalZone lit = zone;
        if (lit != null && com.customdimensions.server.portal.PortalRegistry.get().contains(lit)) {
            com.customdimensions.server.portal.Portals.breakZone(overworld, lit);
        }
        LevelManager m = LevelManager.get().orElseThrow();
        if (m.state(slug) == LevelManager.State.ACTIVE) {
            return m.deactivate(slug).handle((t, e) -> null);
        }
        return CompletableFuture.completedFuture(null);
    }

    private String noneActive() {
        LevelManager m = LevelManager.get().orElse(null);
        if (m == null) return "FAIL companion feed leaves no lazy level active: no level manager";
        List<String> active = new ArrayList<>();
        for (LevelIndex.Entry e : m.index().lazy().values()) {
            if (server.levelKeys().contains(e.key()) || m.state(e.slug()) != LevelManager.State.LAZY) {
                active.add(e.slug() + " (" + m.state(e.slug()) + ")");
            }
        }
        return active.isEmpty()
                ? "PASS companion feed leaves no lazy level active: every destination it opened was unloaded"
                : "FAIL companion feed leaves no lazy level active: " + String.join(", ", active);
    }

    private static String cause(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && t != t.getCause()) t = t.getCause();
        return t.toString();
    }
}
