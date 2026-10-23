package com.customdimensions.server.portal;

import com.customdimensions.core.config.DimensionConfig;
import com.customdimensions.core.config.MultiverseConfig;
import com.customdimensions.core.config.PortalDefinition;
import com.customdimensions.core.portal.IgnitionRefusal;
import com.customdimensions.core.portal.PortalGeometry;
import com.customdimensions.core.portal.PortalShape;
import com.customdimensions.server.level.LevelIndex;
import com.customdimensions.server.level.LevelManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The portal part of the start-up self-test, run across ticks after the
 * runtime-level checks. The matrix builds a bay for every definition's portal
 * (and a flat one where the definition allows it) at a test row in the
 * overworld, lights it through {@link Ignition#ignite}, drops an item in,
 * and follows it out, back, and through a frame break, one bay at a time,
 * unloading each lazy destination before the next.
 */
public final class PortalSelfTest {
    static final int ROW_X = 2048;
    static final int ROW_Z = 2048;
    static final int BAY_Y = 200;
    static final int BAY_SPACING = 16;
    static final int TRAVEL_CAP_TICKS = 20 * 90;
    static final String PERSIST_FILE = "customdimensions-portal-persist.txt";
    static final String PERSIST_PORTAL = "the_ashgrove";
    static final BlockPos PERSIST_BAY = new BlockPos(1536, BAY_Y, 1536);

    private final MinecraftServer server;
    private final List<String> lines = new ArrayList<>();
    private final List<Waiter> waiters = new ArrayList<>();
    private int nextBay;
    private int netherBays;

    private record Waiter(BooleanSupplier done, CompletableFuture<Boolean> future, long deadline) {}

    /** A built frame: the opening, the frame block a player would click, and the ring. */
    record Bay(ServerLevel level, PortalDefinition def, Set<BlockPos> interior, Direction.Axis axis, BlockPos clicked,
               List<BlockPos> ring) {}

    private PortalSelfTest(MinecraftServer server) {
        this.server = server;
    }

    public static CompletableFuture<List<String>> run(MinecraftServer server) {
        PortalSelfTest test = new PortalSelfTest(server);
        ServerTickEvents.END_SERVER_TICK.register(s -> test.poll());
        return test.run();
    }

    private void poll() {
        long now = server.getTickCount();
        Iterator<Waiter> it = waiters.iterator();
        List<Waiter> ready = new ArrayList<>();
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

    /** Completes true once {@code done} holds, false after {@code capTicks}. */
    private CompletableFuture<Boolean> until(BooleanSupplier done, int capTicks) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        waiters.add(new Waiter(done, f, server.getTickCount() + capTicks));
        return f;
    }

    private CompletableFuture<Boolean> ticks(int n) {
        long end = server.getTickCount() + n;
        return until(() -> server.getTickCount() >= end, n + 1);
    }

    private CompletableFuture<List<String>> run() {
        int pause = holdAwake();
        String mode = System.getProperty(com.customdimensions.server.level.LevelSelfTest.PERSIST_PROPERTY, "");
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        if (!mode.isEmpty()) {
            chain = chain.thenCompose(v -> persistence(mode));
        }
        return chain
                .thenCompose(v -> refusals())
                .thenCompose(v -> vanilla())
                .thenCompose(v -> standingAndCrossing())
                .thenRun(() -> lines.addAll(AuraSelfTest.run(server)))
                .thenCompose(v -> matrix())
                .thenRun(() -> lines.add(noneActive()))
                .handle((v, error) -> {
                    if (error != null) lines.add("FAIL portals: the check chain broke: " + cause(error));
                    restorePause(pause);
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

    // --- Bays --------------------------------------------------------------------------------

    private BlockPos nextBayOrigin() {
        int i = nextBay++;
        return new BlockPos(ROW_X + (i % 16) * BAY_SPACING, BAY_Y, ROW_Z + (i / 16) * BAY_SPACING);
    }

    /** Forces the bay's chunk and completes once it is loaded. */
    private CompletableFuture<Boolean> load(ServerLevel level, BlockPos at) {
        level.setChunkForced(at.getX() >> 4, at.getZ() >> 4, true);
        return until(() -> level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4) != null, 20 * 60);
    }

    private static void unforce(ServerLevel level, BlockPos at) {
        level.setChunkForced(at.getX() >> 4, at.getZ() >> 4, false);
    }

    /** The opening a definition's shape asks for, with its lowest corner at {@code at}. */
    static Set<BlockPos> opening(String shape, Direction.Axis axis, BlockPos at) {
        int w;
        int h;
        if (axis == Direction.Axis.Y) {
            w = PortalShape.END_GATEWAY.equals(shape) ? 1 : 3;
            h = w;
        } else {
            w = PortalShape.END_GATEWAY.equals(shape) || PortalShape.DOOR.equals(shape) ? 1 : 2;
            h = PortalShape.END_GATEWAY.equals(shape) ? 1 : PortalShape.DOOR.equals(shape) ? 2 : 3;
        }
        Set<BlockPos> out = new HashSet<>();
        for (int a = 0; a < w; a++) {
            for (int b = 0; b < h; b++) {
                out.add(switch (axis) {
                    case X -> at.offset(a, b, 0);
                    case Z -> at.offset(0, b, a);
                    case Y -> at.offset(a, 0, b);
                });
            }
        }
        return out;
    }

    /** Builds the frame with the definition's own blocks and empties the opening. */
    static Bay build(ServerLevel level, PortalDefinition def, Direction.Axis axis, BlockPos at, Block frameOverride) {
        String shape = PortalShape.normalise(def.getShape());
        Set<BlockPos> interior = opening(shape, axis, at);
        Set<com.customdimensions.core.BlockPos> core = new HashSet<>();
        interior.forEach(p -> core.add(Pos.core(p)));
        int minY = at.getY();
        int maxY = interior.stream().mapToInt(BlockPos::getY).max().orElse(minY);
        List<BlockPos> ring = new ArrayList<>();
        for (com.customdimensions.core.BlockPos c : PortalGeometry.frameRing(core, Pos.core(axis))) {
            BlockPos p = Pos.mc(c);
            ring.add(p);
            String part = def.hasPartMaterials() && axis != Direction.Axis.Y
                    ? PortalGeometry.classifyFramePart(c, minY, maxY) : null;
            BlockState state = frameOverride != null ? frameOverride.defaultBlockState() : PortalWorld.placeState(def, part);
            level.setBlock(p, state, PortalWorld.QUIET);
        }
        for (BlockPos p : interior) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), PortalWorld.QUIET);
            if (axis == Direction.Axis.Y) {
                level.setBlock(p.below(), Blocks.STONE.defaultBlockState(), PortalWorld.QUIET);
            }
        }
        BlockPos clicked = axis == Direction.Axis.Y ? at.west() : at.below();
        return new Bay(level, def, interior, axis, clicked, ring);
    }

    private static Optional<Item> item(String id) {
        Identifier key = id == null ? null : Identifier.tryParse(id);
        return key != null && BuiltInRegistries.ITEM.containsKey(key) ? Optional.of(BuiltInRegistries.ITEM.getValue(key))
                : Optional.empty();
    }

    private static Ignition.Outcome ignite(Bay bay) {
        Item igniter = item(bay.def().getIgniterItem()).orElse(Items.AIR);
        return Ignition.ignite(bay.level(), bay.clicked(), new ItemStack(igniter), null, InteractionHand.MAIN_HAND);
    }

    /** An item inside the opening's landing cell, or falling into a flat opening. */
    private static ItemEntity drop(ServerLevel level, Set<BlockPos> interior, Direction.Axis axis) {
        BlockPos key = Pos.mc(PortalGeometry.keyCell(coreOf(interior)));
        double y = axis == Direction.Axis.Y ? key.getY() + 0.6 : key.getY() + 0.3;
        double x = key.getX() + (axis == Direction.Axis.Y && interior.size() > 1 ? 1.5 : 0.5);
        double z = key.getZ() + (axis == Direction.Axis.Y && interior.size() > 1 ? 1.5 : 0.5);
        ItemEntity entity = new ItemEntity(level, x, y, z, new ItemStack(Items.DIAMOND), 0, 0, 0);
        entity.setUnlimitedLifetime();
        entity.setNeverPickUp();
        level.addFreshEntity(entity);
        return entity;
    }

    private static Set<com.customdimensions.core.BlockPos> coreOf(Set<BlockPos> cells) {
        Set<com.customdimensions.core.BlockPos> out = new HashSet<>();
        cells.forEach(p -> out.add(Pos.core(p)));
        return out;
    }

    /** The entity with this UUID, in whichever level holds it now. */
    private Entity find(UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null && !e.isRemoved()) return e;
        }
        return null;
    }

    /** Completes with the entity once it stands in a level other than {@code from}, or null at the cap. */
    private CompletableFuture<Entity> travelled(UUID id, ResourceKey<Level> from) {
        Entity[] found = new Entity[1];
        return until(() -> {
            Entity e = find(id);
            if (e != null && !e.level().dimension().equals(from)) {
                found[0] = e;
                return true;
            }
            return false;
        }, TRAVEL_CAP_TICKS).thenApply(ok -> ok ? found[0] : null);
    }

    private CompletableFuture<Void> deactivate(ResourceKey<Level> key) {
        Optional<LevelManager> m = LevelManager.get();
        Optional<String> slug = m.flatMap(x -> x.slugOf(key));
        if (slug.isEmpty() || m.get().state(slug.get()) != LevelManager.State.ACTIVE) {
            return CompletableFuture.completedFuture(null);
        }
        return m.get().deactivate(slug.get()).handle((t, e) -> null);
    }

    // --- The matrix --------------------------------------------------------------------------

    private record Case(String slug, PortalDefinition def, Direction.Axis axis) {}

    private static final class Tally {
        int built;
        int ignited;
        int traversed;
        int returned;
        int broken;
        int flat;
        int flatInTarget;
        int skipped;
        int singleUse;
        final List<String> failures = new ArrayList<>();
        final List<String> skips = new ArrayList<>();
        final List<String> singleUseLines = new ArrayList<>();
        final List<String> breakLines = new ArrayList<>();
    }

    private List<Case> cases() {
        List<Case> out = new ArrayList<>();
        for (PortalDefinition def : MultiverseConfig.getInstance().getPortals()) {
            if (def.isVanillaManaged()) continue;
            String shape = PortalShape.normalise(def.getShape());
            boolean upright = def.allowsAxis(com.customdimensions.core.Direction.Axis.X)
                    && !PortalShape.END_EXIT.equals(shape);
            boolean uprightZ = !upright && def.allowsAxis(com.customdimensions.core.Direction.Axis.Z)
                    && !PortalShape.END_EXIT.equals(shape);
            boolean flat = def.allowsAxis(com.customdimensions.core.Direction.Axis.Y)
                    && !PortalShape.DOOR.equals(shape) && !PortalShape.DOORWAY.equals(shape)
                    && !PortalShape.PATTERN.equals(shape);
            if (upright) out.add(new Case(def.getId(), def, Direction.Axis.X));
            if (uprightZ) out.add(new Case(def.getId(), def, Direction.Axis.Z));
            if (flat) out.add(new Case(def.getId(), def, Direction.Axis.Y));
        }
        return out;
    }

    /** Where a definition's bay stands: the overworld, or the Nether for the portal that leads to the overworld. */
    private ServerLevel hostFor(PortalDefinition def) {
        return Level.OVERWORLD.identifier().toString().equals(def.getTargetDimension())
                ? server.getLevel(Level.NETHER) : server.overworld();
    }

    private CompletableFuture<Void> matrix() {
        Tally tally = new Tally();
        List<Case> cases = cases();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Case c : cases) {
            chain = chain.thenCompose(v -> bay(c, tally).handle((x, e) -> {
                if (e != null) tally.failures.add(c.slug + "/" + c.axis + ": " + cause(e));
                com.customdimensions.server.CustomDimensions.LOG.info("portal matrix: {}/{} {}", c.slug, c.axis,
                        e == null ? "done" : "failed: " + cause(e));
                return null;
            }));
        }
        return chain.thenRun(() -> {
            String counts = cases.size() + " cases from " + MultiverseConfig.getInstance().getPortals().stream()
                    .filter(d -> !d.isVanillaManaged()).count() + " definitions: " + tally.built + " bays built, "
                    + tally.ignited + " ignited, " + tally.traversed + " traversed, " + tally.returned + " returned, "
                    + tally.broken + " broken and unlinked, " + tally.skipped + " skipped"
                    + (tally.skips.isEmpty() ? "" : " (" + String.join("; ", tally.skips) + ")")
                    + unopened();
            lines.add((tally.failures.isEmpty() ? "PASS" : "FAIL") + " portal matrix: " + counts
                    + (tally.failures.isEmpty() ? "" : "; failures: " + String.join(" | ", tally.failures)));
            lines.add((tally.flat > 0 && tally.flat == tally.flatInTarget ? "PASS" : "FAIL")
                    + " flat portals are ours, not vanilla End portals: " + tally.flatInTarget + "/" + tally.flat
                    + " flat bays sent their item to the definition's dimension, none to minecraft:the_end");
            lines.add((tally.singleUse > 0 && tally.singleUseLines.stream().allMatch(l -> l.startsWith("ok"))
                    ? "PASS" : "FAIL") + " single-use portals decay after their delay: "
                    + String.join("; ", tally.singleUseLines));
            lines.add((!tally.breakLines.isEmpty() && tally.breakLines.stream().allMatch(l -> l.startsWith("ok"))
                    ? "PASS" : "FAIL") + " breaking one frame block removes the portal blocks and unlinks: "
                    + tally.broken + " bays; " + String.join("; ", tally.breakLines.stream().limit(3).toList()));
        });
    }

    /** Definitions on disk whose dimension has no level here (not compiled), so their portals have nowhere to go. */
    private String unopened() {
        Optional<Path> dir = LevelIndex.definitionsDir();
        if (dir.isEmpty()) return "";
        Set<String> known = new HashSet<>();
        MultiverseConfig.getInstance().getAllDimensions().forEach(d -> known.add(d.getName()));
        List<String> missing = new ArrayList<>();
        try (var files = Files.list(dir.get())) {
            files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".json") && !n.equals("settings.json"))
                    .map(n -> n.substring(0, n.length() - 5)).sorted().filter(n -> !known.contains(n)).forEach(missing::add);
        } catch (IOException e) {
            return "; definitions not listed: " + e.getMessage();
        }
        return missing.isEmpty() ? "" : "; " + missing.size() + " definitions have no level on this server and were not built: "
                + String.join(", ", missing);
    }

    private CompletableFuture<Void> bay(Case c, Tally tally) {
        PortalDefinition def = c.def;
        String name = c.slug + "/" + c.axis;
        String frameId = def.getFramePlaceBlock();
        if (PortalWorld.block(frameId) == null || item(def.getIgniterItem()).isEmpty()) {
            tally.skipped++;
            tally.skips.add(name + ": " + (PortalWorld.block(frameId) == null ? frameId : def.getIgniterItem())
                    + " is not registered on this server");
            return CompletableFuture.completedFuture(null);
        }
        ServerLevel host = hostFor(def);
        BlockPos at = host.dimension().equals(Level.NETHER) ? new BlockPos(96 + 16 * netherBays++, BAY_Y, 96)
                : nextBayOrigin();
        return load(host, at).thenCompose(loaded -> {
            if (!loaded) throw new IllegalStateException("bay chunk at " + at + " did not load");
            Bay bay = build(host, def, c.axis, at, null);
            tally.built++;
            Ignition.Outcome outcome = ignite(bay);
            if (outcome.zone() == null) {
                throw new IllegalStateException("not lit: " + outcome.refusal() + " " + outcome.detail());
            }
            if (!def.getId().equals(outcome.detail())) {
                throw new IllegalStateException("lit " + outcome.detail() + " instead");
            }
            tally.ignited++;
            PortalZone zone = outcome.zone();
            ItemEntity item = drop(host, bay.interior(), c.axis);
            return travelled(item.getUUID(), host.dimension()).thenCompose(arrived -> {
                if (arrived == null) {
                    item.discard();
                    throw new IllegalStateException("the item never left " + host.dimension().identifier()
                            + " (zone " + (PortalRegistry.get().zoneAt(host.dimension(), zone.landingCell()) != null)
                            + ", item in " + (find(item.getUUID()) == null ? "nowhere" : find(item.getUUID()).level().dimension().identifier()) + ")");
                }
                ResourceKey<Level> target = arrived.level().dimension();
                if (!target.equals(zone.target)) {
                    arrived.discard();
                    throw new IllegalStateException("arrived in " + target.identifier() + ", not " + zone.target.identifier());
                }
                tally.traversed++;
                if (c.axis == Direction.Axis.Y) {
                    tally.flat++;
                    if (!target.equals(Level.END)) tally.flatInTarget++;
                }
                ServerLevel there = (ServerLevel) arrived.level();
                PortalZone arrival = PortalRegistry.get().zoneAt(target, arrived.blockPosition());
                if (arrival == null) arrival = PortalRegistry.get().zoneAt(target, arrived.blockPosition().above());
                if (arrival == null) arrival = PortalRegistry.get().arrivalNear(target, arrived.getBlockX(), arrived.getBlockZ(), 6, a -> true);
                if (arrival == null) {
                    arrived.discard();
                    throw new IllegalStateException("no arrival portal registered near " + arrived.blockPosition());
                }
                PortalZone.ReturnTarget back = PortalRegistry.get().target(target, arrival.landingCell());
                if (back == null || !PortalWorld.isPortal(there.getBlockState(arrival.landingCell()))) {
                    arrived.discard();
                    throw new IllegalStateException("arrival at " + arrival.landingCell() + " is not built and linked");
                }
                ResourceKey<Level> expectedHome = back.exitMode() == null ? host.dimension()
                        : back.exitMode().startsWith("dim!") ? null : Level.OVERWORLD;
                arrived.setPortalCooldown(0);
                UUID id = arrived.getUUID();
                PortalZone arrivalZone = arrival;
                return travelled(id, target).thenCompose(home -> {
                    if (home == null) {
                        Entity still = find(id);
                        if (still != null) still.discard();
                        throw new IllegalStateException("the item did not come back through the arrival at "
                                + arrivalZone.landingCell() + " (" + back.exitMode() + ")");
                    }
                    if (expectedHome != null && !home.level().dimension().equals(expectedHome)) {
                        home.discard();
                        throw new IllegalStateException("came back to " + home.level().dimension().identifier()
                                + ", not " + expectedHome.identifier());
                    }
                    tally.returned++;
                    home.discard();
                    if (def.isSingleUse()) {
                        return singleUse(host, zone, bay, tally, name);
                    }
                    return frameBreak(host, there, zone, arrivalZone, back, bay, tally, name);
                });
            });
        }).handle((v, e) -> {
            unforce(host, at);
            return e;
        }).thenCompose(e -> settleAndDeactivate(PortalRegistry.key(def.getTargetDimension())).thenAccept(x -> {
            if (e != null) throw e instanceof CompletionException ce ? ce : new CompletionException(e);
        }));
    }

    /** Waits out an activation still in flight, then unloads the level. */
    private CompletableFuture<Void> settleAndDeactivate(ResourceKey<Level> key) {
        Optional<LevelManager> m = LevelManager.get();
        Optional<String> slug = m.flatMap(x -> x.slugOf(key));
        if (slug.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return until(() -> {
            LevelManager.State state = m.get().state(slug.get());
            return state != LevelManager.State.QUEUED && state != LevelManager.State.OPENING;
        }, 20 * 120).thenCompose(x -> deactivate(key));
    }

    private CompletableFuture<Void> singleUse(ServerLevel host, PortalZone zone, Bay bay, Tally tally, String name) {
        tally.singleUse++;
        int delay = zone.definition.getSingleUseDelayTicks();
        long start = server.getTickCount();
        return until(() -> PortalRegistry.get().zoneAt(host.dimension(), zone.landingCell()) == null, delay + 200)
                .thenAccept(gone -> {
                    long took = server.getTickCount() - start;
                    long portalLeft = bay.interior().stream().filter(p -> PortalWorld.isPortal(host.getBlockState(p))).count();
                    long frameChanged = bay.ring().stream()
                            .filter(p -> !PortalWorld.blockId(host.getBlockState(p)).equals(PortalWorld.blockId(
                                    PortalWorld.placeState(zone.definition, null)))).count();
                    String mode = zone.definition.getSingleUseBreakMode();
                    boolean ok = gone && portalLeft == 0 && ("partial".equals(mode) ? frameChanged >= 1 && frameChanged <= 2
                            : frameChanged >= 1);
                    tally.singleUseLines.add((ok ? "ok " : "bad ") + name + " " + mode + " after " + took + " ticks (delay "
                            + delay + "), " + frameChanged + " frame block(s) changed, " + portalLeft + " portal blocks left");
                    if (!ok) tally.failures.add(name + ": single-use did not expire as configured");
                });
    }

    private CompletableFuture<Void> frameBreak(ServerLevel host, ServerLevel there, PortalZone zone, PortalZone arrival,
                                               PortalZone.ReturnTarget back, Bay bay, Tally tally, String name) {
        BlockPos frame = bay.clicked();
        host.destroyBlock(frame, false);
        boolean shared = zone.definition.hasAnchor() || !back.breaksSymmetrically();
        return until(() -> PortalRegistry.get().zoneAt(host.dimension(), zone.landingCell()) == null, 20).thenAccept(gone -> {
            long sourceLeft = bay.interior().stream().filter(p -> PortalWorld.isPortal(host.getBlockState(p))).count();
            boolean arrivalRegistered = PortalRegistry.get().zones(arrival.world).contains(arrival);
            long arrivalLeft = arrival.interior.stream().filter(p -> PortalWorld.isPortal(there.getBlockState(p))).count();
            boolean ok = gone && sourceLeft == 0 && (shared ? arrivalRegistered : !arrivalRegistered && arrivalLeft == 0
                    && PortalRegistry.get().target(arrival.world, arrival.landingCell()) == null);
            if (ok) {
                tally.broken++;
                if (tally.breakLines.size() < 3) {
                    tally.breakLines.add("ok " + name + (shared ? " (shared arrival kept)" : " (arrival closed)"));
                }
            } else {
                tally.breakLines.add("bad " + name);
                tally.failures.add(name + ": frame break left source zone " + !gone + ", " + sourceLeft
                        + " source blocks, arrival registered " + arrivalRegistered + ", " + arrivalLeft + " arrival blocks");
            }
        });
    }

    // --- Refusals and vanilla ----------------------------------------------------------------

    private PortalDefinition portal(String slug) {
        return MultiverseConfig.getInstance().getPortal(slug);
    }

    private CompletableFuture<Void> refusals() {
        ServerLevel level = server.overworld();
        BlockPos a = nextBayOrigin();
        BlockPos b = nextBayOrigin();
        BlockPos c = nextBayOrigin();
        return load(level, a).thenCompose(x -> load(level, b)).thenCompose(x -> load(level, c)).thenAccept(x -> {
            List<String> results = new ArrayList<>();
            boolean ok = true;
            PortalDefinition ashgrove = portal("the_ashgrove");
            Ignition.Outcome wrongMaterial = ignite(build(level, ashgrove, Direction.Axis.X, a, Blocks.COBBLESTONE));
            boolean materialRefused = wrongMaterial.zone() == null
                    && (wrongMaterial.refusal() == IgnitionRefusal.OPENING_NOT_ENCLOSED
                    || wrongMaterial.refusal() == IgnitionRefusal.OPENING_TOO_LARGE);
            ok &= materialRefused;
            results.add("cobblestone frame with an amethyst shard: " + wrongMaterial.refusal());
            PortalDefinition doorway = portal("the_sun_kingdoms");
            Bay wide = build(level, doorway, Direction.Axis.X, b, null);
            for (int y = 0; y < 3; y++) {
                level.setBlock(b.offset(2, y, 0), Blocks.AIR.defaultBlockState(), PortalWorld.QUIET);
                level.setBlock(b.offset(3, y, 0), Blocks.SANDSTONE.defaultBlockState(), PortalWorld.QUIET);
            }
            level.setBlock(b.offset(2, -1, 0), Blocks.SANDSTONE.defaultBlockState(), PortalWorld.QUIET);
            level.setBlock(b.offset(2, 3, 0), Blocks.SANDSTONE.defaultBlockState(), PortalWorld.QUIET);
            Ignition.Outcome shape = ignite(wide);
            ok &= shape.zone() == null && shape.refusal() == IgnitionRefusal.SHAPE_MISMATCH;
            results.add("3x3 sandstone doorway: " + shape.refusal());
            PortalDefinition canvas = portal("the_canvas");
            Ignition.Outcome axis = ignite(build(level, canvas, Direction.Axis.X, c, null));
            ok &= axis.zone() == null && axis.refusal() == IgnitionRefusal.AXIS_NOT_ALLOWED;
            results.add("upright white_concrete for a horizontal-only portal: " + axis.refusal());
            lines.add((ok ? "PASS" : "FAIL") + " ignition refuses a wrong frame with the 1.21.1 reason: "
                    + String.join("; ", results));
            unforce(level, a);
            unforce(level, b);
            unforce(level, c);
        });
    }

    /**
     * A lit nylium frame stays lit once its covered blocks decay to netherrack
     * and breaks on a wrong block; an item never opens a lazy level, so it
     * waits at a portal whose target is closed.
     */
    private CompletableFuture<Void> standingAndCrossing() {
        ServerLevel level = server.overworld();
        PortalDefinition def = portal("the_twisted_groves");
        ResourceKey<Level> target = PortalRegistry.key(def.getTargetDimension());
        BlockPos at = nextBayOrigin();
        PortalZone[] lit = new PortalZone[1];
        UUID[] item = new UUID[1];
        List<String> results = new ArrayList<>();
        boolean[] ok = {true};
        return load(level, at).thenCompose(x -> {
            Ignition.Outcome outcome = ignite(build(level, def, Direction.Axis.X, at, null));
            if (outcome.zone() == null) {
                throw new IllegalStateException("not lit: " + outcome.refusal());
            }
            lit[0] = outcome.zone();
            return settleAndDeactivate(target);
        }).thenCompose(x -> {
            PortalZone zone = lit[0];
            int decayed = 0;
            for (com.customdimensions.core.BlockPos c : PortalGeometry.frameRing(zone.coreInterior(), Pos.core(zone.axis))) {
                BlockPos p = Pos.mc(c);
                if (level.getBlockState(p).is(Blocks.WARPED_NYLIUM)) {
                    level.setBlock(p, Blocks.NETHERRACK.defaultBlockState(), PortalWorld.QUIET);
                    decayed++;
                }
            }
            Portals.validate(level, zone);
            boolean standing = decayed > 0 && PortalRegistry.get().contains(zone)
                    && zone.interior.stream().allMatch(p -> PortalWorld.isPortal(level.getBlockState(p)));
            ok[0] &= standing;
            results.add(decayed + " covered nylium blocks decayed to netherrack: " + (standing ? "still lit" : "broke"));
            item[0] = drop(level, zone.interior, zone.axis).getUUID();
            return ticks(60);
        }).thenAccept(x -> {
            PortalZone zone = lit[0];
            Entity waiting = find(item[0]);
            String state = LevelManager.get().flatMap(m -> m.slugOf(target)).map(slug -> LevelManager.get().get()
                    .state(slug).name()).orElse("none");
            boolean held = waiting != null && waiting.level() == level && "LAZY".equals(state);
            ok[0] &= held;
            results.add("an item at a portal into a closed lazy level after 60 ticks: "
                    + (waiting == null ? "gone" : waiting.level().dimension().identifier()) + ", target " + state);
            if (waiting != null) waiting.discard();
            com.customdimensions.core.BlockPos first = PortalGeometry.frameRing(zone.coreInterior(), Pos.core(zone.axis)).get(0);
            level.setBlock(Pos.mc(first), Blocks.COBBLESTONE.defaultBlockState(), PortalWorld.QUIET);
            Portals.validate(level, zone);
            boolean broken = !PortalRegistry.get().contains(zone)
                    && zone.interior.stream().noneMatch(p -> PortalWorld.isPortal(level.getBlockState(p)));
            ok[0] &= broken;
            results.add("one frame block swapped for cobblestone: " + (broken ? "broke" : "still lit"));
            lines.add((ok[0] ? "PASS" : "FAIL") + " a lit frame survives covered decay and items never open a lazy level: "
                    + String.join("; ", results));
        }).whenComplete((v, e) -> unforce(level, at));
    }

    private CompletableFuture<Void> vanilla() {
        ServerLevel level = server.overworld();
        BlockPos a = nextBayOrigin();
        BlockPos b = nextBayOrigin();
        return load(level, a).thenCompose(x -> load(level, b)).thenCompose(x -> {
            PortalDefinition nether = portal("the_nether");
            PortalDefinition end = portal("the_end");
            List<String> problems = new ArrayList<>();
            if (nether == null || !nether.isVanillaManaged()) problems.add("the_nether is not vanillaManaged");
            if (end == null || !end.isVanillaManaged()) problems.add("the_end is not vanillaManaged");
            boolean candidates = MultiverseConfig.getInstance().getPortalsByIgniter("minecraft:flint_and_steel", "minecraft:obsidian")
                    .stream().anyMatch(PortalDefinition::isVanillaManaged)
                    || MultiverseConfig.getInstance().getPortalsByIgniter("minecraft:ender_eye", "minecraft:end_portal_frame")
                    .stream().anyMatch(PortalDefinition::isVanillaManaged);
            if (candidates) problems.add("a vanillaManaged definition is an ignition candidate");
            Bay obsidian = build(level, nether, Direction.Axis.X, a, Blocks.OBSIDIAN);
            Ignition.Outcome lit = ignite(obsidian);
            if (lit.consumed()) problems.add("flint and steel on obsidian was taken by " + lit.detail());
            Bay frame = build(level, end, Direction.Axis.Y, b, Blocks.END_PORTAL_FRAME);
            Ignition.Outcome eye = Ignition.ignite(level, frame.clicked(), new ItemStack(Items.ENDER_EYE), null,
                    InteractionHand.MAIN_HAND);
            if (eye.consumed()) problems.add("an ender eye on an end portal frame was taken by " + eye.detail());
            Optional<net.minecraft.world.level.portal.PortalShape> shape =
                    net.minecraft.world.level.portal.PortalShape.findEmptyPortalShape(level, a, Direction.Axis.X);
            if (shape.isEmpty()) {
                problems.add("vanilla found no nether portal shape in the obsidian frame");
                return CompletableFuture.completedFuture(problems);
            }
            shape.get().createPortalBlocks(level);
            ItemEntity item = drop(level, obsidian.interior(), Direction.Axis.X);
            return travelled(item.getUUID(), level.dimension()).thenApply(arrived -> {
                if (arrived == null) {
                    problems.add("an item in a vanilla nether portal did not travel");
                } else {
                    if (!arrived.level().dimension().equals(Level.NETHER)) {
                        problems.add("the vanilla portal sent its item to " + arrived.level().dimension().identifier());
                    }
                    arrived.discard();
                }
                if (PortalRegistry.get().zoneAt(level.dimension(), a) != null) problems.add("a zone was registered");
                return problems;
            });
        }).thenAccept(problems -> {
            lines.add((problems.isEmpty() ? "PASS" : "FAIL") + " vanillaManaged portals are left to vanilla: "
                    + (problems.isEmpty() ? "obsidian + flint and steel and end_portal_frame + ender eye pass through "
                    + "ignition untouched; vanilla lit the obsidian frame and its item reached minecraft:the_nether"
                    : String.join("; ", problems)));
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 2; x++) level.setBlock(a.offset(x, y, 0), Blocks.AIR.defaultBlockState(), PortalWorld.QUIET);
            }
            unforce(level, a);
            unforce(level, b);
        });
    }

    // --- Persistence -------------------------------------------------------------------------

    private CompletableFuture<Void> persistence(String mode) {
        String name = "portal persistence " + mode;
        ServerLevel level = server.overworld();
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(PERSIST_FILE);
        PortalDefinition def = portal(PERSIST_PORTAL);
        if (def == null || !(mode.equals("write") || mode.equals("verify"))) {
            lines.add("FAIL " + name + ": " + (def == null ? "no " + PERSIST_PORTAL + " portal" : "mode must be write or verify"));
            return CompletableFuture.completedFuture(null);
        }
        ResourceKey<Level> target = PortalRegistry.key(def.getTargetDimension());
        CompletableFuture<Void> done = load(level, PERSIST_BAY).thenCompose(loaded -> {
            if (mode.equals("write")) {
                Bay bay = build(level, def, Direction.Axis.X, PERSIST_BAY, null);
                Ignition.Outcome lit = ignite(bay);
                if (lit.zone() == null) throw new IllegalStateException("not lit: " + lit.refusal());
                ItemEntity item = drop(level, bay.interior(), Direction.Axis.X);
                return travelled(item.getUUID(), level.dimension()).thenApply(arrived -> {
                    if (arrived == null) throw new IllegalStateException("the item never crossed");
                    PortalZone arrival = PortalRegistry.get().arrivalNear(arrived.level().dimension(),
                            arrived.getBlockX(), arrived.getBlockZ(), 6, a -> true);
                    arrived.discard();
                    if (arrival == null) throw new IllegalStateException("no arrival registered");
                    String aura = AuraSelfTest.persistWrite(lit.zone(), arrival);
                    PortalRegistry.get().save();
                    try {
                        Files.writeString(file, lit.zone().landingCell().toShortString() + "\n"
                                + arrival.landingCell().toShortString() + "\n" + aura + "\n");
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                    return "PASS " + name + ": " + PERSIST_PORTAL + " lit at " + lit.zone().landingCell().toShortString()
                            + ", arrival in " + target.identifier() + " at " + arrival.landingCell().toShortString()
                            + "; " + PortalRegistry.get().zoneCount() + " zones saved; aura spent " + aura;
                });
            }
            return verify(level, def, target, file, name);
        }).thenAccept(line -> lines.add(line)).exceptionally(e -> {
            lines.add("FAIL " + name + ": " + cause(e));
            return null;
        });
        return done.whenComplete((v, e) -> unforce(level, PERSIST_BAY)).thenCompose(v -> settleAndDeactivate(target));
    }

    private CompletableFuture<String> verify(ServerLevel level, PortalDefinition def, ResourceKey<Level> target,
                                             Path file, String name) {
        List<String> recorded;
        try {
            recorded = Files.readAllLines(file);
        } catch (IOException e) {
            throw new IllegalStateException(file + " is missing; run write first");
        }
        BlockPos source = parse(recorded.get(0));
        BlockPos arrivalCell = parse(recorded.get(1));
        PortalZone zone = PortalRegistry.get().zoneAt(level.dimension(), source);
        if (zone == null) throw new IllegalStateException("no source zone at " + source.toShortString() + " after restart");
        long blocks = zone.interior.stream().filter(p -> PortalWorld.isPortal(level.getBlockState(p))).count();
        if (blocks != zone.interior.size()) {
            throw new IllegalStateException(blocks + "/" + zone.interior.size() + " source portal blocks intact");
        }
        String aura = AuraSelfTest.persistVerify(zone, target, arrivalCell, recorded.size() > 2 ? recorded.get(2) : "");
        PortalZone.ReturnTarget back = PortalRegistry.get().target(target, arrivalCell);
        if (back == null || !back.leadsTo().equals(level.dimension())) {
            throw new IllegalStateException("arrival link at " + arrivalCell.toShortString() + " missing");
        }
        Optional<String> slug = LevelManager.get().flatMap(m -> m.slugOf(target));
        if (slug.isEmpty()) throw new IllegalStateException(target.identifier() + " is not lazy");
        return LevelManager.get().get().activate(slug.get()).thenCompose(a -> {
            ServerLevel there = a.level();
            PortalTickets.hold(there, arrivalCell.getX(), arrivalCell.getZ());
            return until(() -> there.getChunkSource().getChunkNow(arrivalCell.getX() >> 4, arrivalCell.getZ() >> 4) != null,
                    20 * 60).thenApply(ok -> {
                boolean built = PortalWorld.isPortal(there.getBlockState(arrivalCell));
                if (!built) throw new IllegalStateException("arrival portal block at " + arrivalCell.toShortString() + " gone");
                return "PASS " + name + ": " + PERSIST_PORTAL + " source zone and its " + blocks
                        + " portal blocks intact at " + source.toShortString() + "; arrival in " + target.identifier()
                        + " at " + arrivalCell.toShortString() + " linked back and built; " + aura;
            });
        });
    }

    private static BlockPos parse(String s) {
        String[] p = s.split(",\\s*");
        return new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
    }

    private String noneActive() {
        LevelManager m = LevelManager.get().orElse(null);
        if (m == null) return "FAIL portal matrix leaves no lazy level active: no level manager";
        List<String> active = new ArrayList<>();
        for (LevelIndex.Entry e : m.index().lazy().values()) {
            if (server.levelKeys().contains(e.key()) || m.state(e.slug()) != LevelManager.State.LAZY) {
                active.add(e.slug() + " (" + m.state(e.slug()) + ")");
            }
        }
        return active.isEmpty()
                ? "PASS portal matrix leaves no lazy level active: every destination it opened was unloaded"
                : "FAIL portal matrix leaves no lazy level active: " + String.join(", ", active);
    }

    private static String cause(Throwable error) {
        Throwable c = error;
        while (c instanceof CompletionException && c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}
