package com.customdimensions.server.command;

import com.customdimensions.core.companion.ViewPreference;
import com.customdimensions.core.config.PortalDefinition;
import com.customdimensions.core.portal.PortalTints;
import com.customdimensions.server.command.CustomDimCommands.Dimension;
import com.customdimensions.server.companion.CompanionFeed;
import com.customdimensions.server.companion.CompanionNetwork;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.portal.PortalBlock;
import com.customdimensions.server.portal.PortalJournal;
import com.customdimensions.server.portal.PortalRegistry;
import com.customdimensions.server.portal.PortalTintStates;
import com.customdimensions.server.portal.PortalWorld;
import com.customdimensions.server.portal.PortalZone;
import com.customdimensions.server.portal.Portals;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.context.PacketContextProvider;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code /customdim e2e-state [player]}: everything an end-to-end assertion
 * needs about the live server, as one JSON file, in one RCON round trip
 * ({@link E2eState} holds the shape). Registered only when the JVM runs with
 * {@code -Dcustomdimensions.e2e=true}. Nothing loads a chunk: a zone is read
 * only where every chunk its frame check reads is resident.
 */
final class E2eStateCommand {
    static final String PROPERTY = "customdimensions.e2e";
    static final String FILE = "e2e-state.json";

    /** Self-test hook: where the artefact goes instead of the seed-rolling folder. */
    static Path outputOverride;

    private E2eStateCommand() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        if (!enabled()) {
            return;
        }
        root.then(Commands.literal("e2e-state").executes(c -> run(c.getSource(), null))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(c -> run(c.getSource(), StringArgumentType.getString(c, "player")))));
    }

    /** {@code <game dir>/../.seed-rolling/e2e-state.json}: the consumer's {@code .seed-rolling} mount. */
    static Path output() {
        if (outputOverride != null) {
            return outputOverride;
        }
        return FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize()
                .resolveSibling(".seed-rolling").resolve(FILE);
    }

    static int run(CommandSourceStack source, String playerName) {
        MinecraftServer server = source.getServer();
        E2eState.State state = gather(server, playerName);
        Path out = output();
        try {
            Files.createDirectories(out.getParent());
            Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
            Files.writeString(tmp, E2eState.render(state));
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            CustomDimCommands.fail(source, "customdim.write_failed", "path", out.toString(),
                    "reason", CustomDimCommands.reason(e));
            return 0;
        }
        long online = state.players().stream().filter(E2eState.Player::online).count();
        long open = state.dimensions().stream().filter(E2eState.Dimension::open).count();
        long cold = state.zones().stream().filter(z -> !z.resident()).count();
        CustomDimCommands.ok(source, "customdim.e2e_state.done", "online", String.valueOf(online),
                "players", String.valueOf(state.players().size()), "open", String.valueOf(open),
                "dimensions", String.valueOf(state.dimensions().size()), "zones", String.valueOf(state.zones().size()),
                "cold", String.valueOf(cold), "tick", String.valueOf(state.server().tick()), "path", out.toString());
        return 1;
    }

    static E2eState.State gather(MinecraftServer server, String playerName) {
        long nanos = server.getAverageTickTimeNanos();
        E2eState.Server s = new E2eState.Server(server.getTickCount(), nanos > 0 ? nanos / 1.0e6 : null,
                nanos > 0 ? Math.min(20.0, 1.0e9 / nanos) : null, CompanionNetwork.version(),
                PortalTintStates.armed() ? PortalTintStates.embeddedSha1() : null);
        List<E2eState.Player> players = new ArrayList<>();
        List<PortalJournal.Ignition> lastIgnitions = new ArrayList<>();
        List<PortalJournal.Traversal> lastTraversals = new ArrayList<>();
        List<PortalJournal.Title> titles = new ArrayList<>();
        List<ServerPlayer> online = playerName == null ? server.getPlayerList().getPlayers()
                : Optional.ofNullable(server.getPlayerList().getPlayerByName(playerName)).map(List::of).orElse(List.of());
        if (playerName != null && online.isEmpty()) {
            players.add(E2eState.Player.offline(playerName));
        }
        List<String> names = new ArrayList<>();
        online.forEach(p -> {
            players.add(describe(server, p));
            names.add(p.getName().getString());
        });
        if (playerName != null) {
            names.add(playerName);
        }
        for (String name : names) {
            Optional.ofNullable(PortalJournal.lastIgnition(name)).ifPresent(lastIgnitions::add);
            Optional.ofNullable(PortalJournal.lastTraversal(name)).ifPresent(lastTraversals::add);
            Optional.ofNullable(PortalJournal.titleOf(name)).ifPresent(titles::add);
        }
        return new E2eState.State(Instant.now().toString(), s, players, dimensions(server), zones(server),
                PortalJournal.ignitions(), PortalJournal.traversals(), titles, lastIgnitions, lastTraversals);
    }

    private static E2eState.Player describe(MinecraftServer server, ServerPlayer p) {
        BlockPos b = p.blockPosition();
        boolean companion = CompanionNetwork.isCompanion(p.getUUID());
        ViewPreference v = CompanionNetwork.view(p.getUUID());
        E2eState.View view = companion ? new E2eState.View(v.rendersLocally(), v.keepSlab(), v.maxRenderDistance(),
                v.viewDepth()) : null;
        return new E2eState.Player(p.getName().getString(), true, p.getUUID().toString(),
                p.level().dimension().identifier().toString(), new double[] {p.getX(), p.getY(), p.getZ()}, core(b),
                p.getYRot(), p.getXRot(), p.onGround(),
                BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString(), p.getPortalCooldown(),
                companion, view, PortalJournal.companionPayloads(p.getUUID()), feed(server, p),
                p.connection instanceof PacketContextProvider provider && PortalTintStates.tinting(provider.getPacketContext()));
    }

    private static E2eState.Feed feed(MinecraftServer server, ServerPlayer p) {
        CompanionFeed.Subscription sub = CompanionFeed.subscription(p.getUUID());
        if (sub == null) {
            return null;
        }
        ServerLevel target = server.getLevel(sub.destination());
        List<E2eState.SeenEntity> seen = new ArrayList<>();
        for (int id : sub.entityIds()) {
            Entity e = target == null ? null : target.getEntity(id);
            seen.add(e == null ? new E2eState.SeenEntity(id, null, 0, 0, 0)
                    : new E2eState.SeenEntity(id, BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
                            e.getX(), e.getY(), e.getZ()));
        }
        return new E2eState.Feed(core(sub.portal()), sub.world().identifier().toString(),
                sub.destination().identifier().toString(),
                sub.apertureOrigin() == null ? null : core(sub.apertureOrigin()), sub.chunksSent(), seen,
                sub.entityTick());
    }

    private static List<E2eState.Dimension> dimensions(MinecraftServer server) {
        List<E2eState.Dimension> out = new ArrayList<>();
        Optional<LevelManager> manager = LevelManager.get();
        if (manager.isEmpty()) {
            return out;
        }
        LevelManager m = manager.get();
        for (Dimension d : CustomDimCommands.all(m)) {
            Optional<ServerLevel> level = CustomDimCommands.level(server, m, d);
            out.add(new E2eState.Dimension(d.slug(), d.key().identifier().toString(),
                    d.key().identifier().getNamespace().equals(m.index().namespace()), CustomDimCommands.state(m, d),
                    level.isPresent(), level.map(l -> l.players().size()).orElse(0),
                    level.map(l -> l.getChunkSource().getLoadedChunksCount()).orElse(0)));
        }
        return out;
    }

    private static List<E2eState.Zone> zones(MinecraftServer server) {
        List<E2eState.Zone> out = new ArrayList<>();
        PortalRegistry registry = PortalRegistry.get();
        for (PortalZone zone : registry.allZones()) {
            ServerLevel level = server.getLevel(zone.world);
            boolean resident = level != null && Portals.loaded(level, zone);
            List<com.customdimensions.core.BlockPos> interior = new ArrayList<>();
            zone.interior.forEach(p -> interior.add(core(p)));
            Boolean stands = null;
            Boolean lit = null;
            List<E2eState.Cell> cells = null;
            if (resident) {
                stands = PortalWorld.frameIntact(level, zone);
                boolean all = true;
                cells = new ArrayList<>();
                for (BlockPos p : zone.interior) {
                    BlockState state = level.getBlockState(p);
                    boolean portal = PortalWorld.isPortal(state);
                    all &= portal;
                    BlockState tinted = portal && PortalTintStates.armed()
                            ? PortalTintStates.tinted(state.getValue(PortalBlock.AXIS), state.getValue(PortalBlock.TINT)) : null;
                    cells.add(new E2eState.Cell(core(p), state.toString(),
                            portal ? Portals.block().getPolymerBlockState(state, null).toString() : state.toString(),
                            tinted == null ? null : tinted.toString(),
                            portal ? String.format("#%06X", PortalTints.rgb(state.getValue(PortalBlock.TINT))) : null));
                }
                lit = all;
            }
            String world = zone.world.identifier().toString();
            com.customdimensions.core.BlockPos landing = core(zone.landingCell());
            String key = PortalJournal.key(world, landing);
            E2eState.ReturnTo back = null;
            if (zone.arrival) {
                PortalZone.ReturnTarget t = registry.target(zone.world, zone.landingCell());
                if (t != null) {
                    back = new E2eState.ReturnTo(t.leadsTo().identifier().toString(), t.exitMode(), t.sourceX(),
                            t.sourceZ());
                }
            }
            out.add(new E2eState.Zone(zone.arrival ? "arrival" : "source", world, zone.target.identifier().toString(),
                    zone.definition == null ? null : zone.definition.getId(), zone.axis.name(), interior, resident,
                    stands, lit, cells, zone.singleUseTicksLeft < 0 ? null : zone.singleUseTicksLeft, back,
                    aura(registry, zone), PortalJournal.particlesOf(key)));
        }
        return out;
    }

    /** A source's aura from the zone, an arrival's from the aura site its link left there. */
    private static E2eState.Aura aura(PortalRegistry registry, PortalZone zone) {
        PortalDefinition def = zone.definition;
        String key = PortalJournal.key(zone.world.identifier().toString(), core(zone.landingCell()));
        if (!zone.arrival) {
            boolean enabled = def != null && def.isAuraEnabled();
            return new E2eState.Aura(enabled, zone.aura == null ? null : (long) zone.aura.spent,
                    enabled ? def.getAura().getBudget() : null, PortalJournal.auraOf(key));
        }
        PortalRegistry.AuraSite site = registry.auraSites(zone.world).values().stream()
                .filter(a -> a.interior().equals(zone.interior)).findFirst().orElse(null);
        if (site == null) {
            return null;
        }
        return new E2eState.Aura(true, (long) site.state().spent,
                site.settings() == null ? null : site.settings().getBudget(), PortalJournal.auraOf(key));
    }

    private static com.customdimensions.core.BlockPos core(BlockPos p) {
        return new com.customdimensions.core.BlockPos(p.getX(), p.getY(), p.getZ());
    }
}
