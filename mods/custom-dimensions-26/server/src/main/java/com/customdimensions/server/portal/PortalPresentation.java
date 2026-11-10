package com.customdimensions.server.portal;

import com.customdimensions.server.MessageFile;
import com.customdimensions.server.companion.CompanionNetwork;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * What a client without the companion is shown of a portal beyond the block
 * itself and its particles: the destination's name as a title, once per
 * approach. A companion draws the far side instead and is sent no title.
 */
public final class PortalPresentation {
    public static final String TITLE_KEY = "portal.approach.title";
    /** How near the eye comes to an opening cell, in blocks, for the title to show. */
    static final double APPROACH = 6.0;
    /** How far it must go again before the next approach titles. */
    static final double LEAVE = 12.0;
    static final int INTERVAL = 10;
    static final int FADE_IN = 10;
    static final int STAY = 40;
    static final int FADE_OUT = 10;

    /** The zone each player was last titled at, until it walks away. */
    private static final Map<UUID, String> TITLED = new ConcurrentHashMap<>();

    private PortalPresentation() {
    }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(PortalPresentation::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> TITLED.remove(handler.getPlayer().getUUID()));
    }

    static void clear() {
        TITLED.clear();
    }

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % INTERVAL != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (CompanionNetwork.isCompanion(player.getUUID()) || player.isSpectator()) {
                continue;
            }
            step(server, player);
        }
    }

    private static void step(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = player.level();
        Vec3 eye = player.getEyePosition();
        PortalZone nearest = null;
        double best = Double.MAX_VALUE;
        for (PortalZone zone : PortalRegistry.get().zones(level.dimension())) {
            double d = distance(zone, eye);
            if (d < best) {
                best = d;
                nearest = zone;
            }
        }
        String dimension = level.dimension().identifier().toString();
        String held = TITLED.get(player.getUUID());
        double heldDistance = Double.POSITIVE_INFINITY;
        if (held != null) {
            for (PortalZone zone : PortalRegistry.get().zones(level.dimension())) {
                if (held.equals(PortalJournal.key(dimension, Pos.core(zone.landingCell())))) {
                    heldDistance = distance(zone, eye);
                }
            }
        }
        String key = nearest == null ? null : PortalJournal.key(dimension, Pos.core(nearest.landingCell()));
        Step next = next(held, heldDistance, key, best);
        if (next.held() == null) {
            TITLED.remove(player.getUUID());
        }
        if (!next.title()) {
            return;
        }
        ResourceKey<Level> destination = destination(level, nearest);
        if (destination == null) {
            return;
        }
        String name = displayName(destination.identifier().getPath());
        String text = MessageFile.get().format(TITLE_KEY, Map.of("destination", name));
        if (text.equals(TITLE_KEY)) {
            text = name;
        }
        player.connection.send(new ClientboundSetTitlesAnimationPacket(FADE_IN, STAY, FADE_OUT));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(text)));
        TITLED.put(player.getUUID(), next.held());
        PortalJournal.titled(new PortalJournal.Title(player.getName().getString(), level.dimension().identifier().toString(),
                Pos.core(nearest.landingCell()), destination.identifier().toString(), text, server.getTickCount()));
    }

    /** Whether this step titles, and the zone held afterwards. */
    record Step(boolean title, String held) {
    }

    /**
     * The approach rule: a held zone is let go once the eye is more than {@link #LEAVE} from it
     * (or it is gone), whichever zone is nearest; the nearest zone titles when the eye is within
     * {@link #APPROACH} of it and it is not the one held.
     */
    static Step next(String held, double heldDistance, String nearestKey, double nearestDistance) {
        String kept = held != null && heldDistance > LEAVE ? null : held;
        if (nearestKey == null || nearestDistance > APPROACH || nearestKey.equals(kept)) {
            return new Step(false, kept);
        }
        return new Step(true, nearestKey);
    }

    /** Where walking in leads: a source's target, or an arrival's recorded way back. */
    static ResourceKey<Level> destination(ServerLevel level, PortalZone zone) {
        if (!zone.arrival) {
            return zone.target;
        }
        PortalZone.ReturnTarget t = PortalRegistry.get().target(level.dimension(), zone.landingCell());
        return t == null ? null : t.leadsTo();
    }

    static double distance(PortalZone zone, Vec3 eye) {
        double best = Double.MAX_VALUE;
        for (BlockPos p : zone.interior) {
            best = Math.min(best, eye.distanceTo(Vec3.atCenterOf(p)));
        }
        return best;
    }

    /** {@code the_nether} reads as "The Nether". */
    public static String displayName(String path) {
        StringBuilder out = new StringBuilder();
        for (String word : path.split("[_/]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return out.isEmpty() ? path : out.toString();
    }
}
