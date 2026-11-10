package com.customdimensions.server.portal;

import com.customdimensions.core.BlockPos;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the portal subsystem has done since boot, kept for {@code customdim
 * e2e-state}: the recent ignitions and crossings, each player's last of both,
 * the vanilla presentation (particle bursts and to whom, the approach title),
 * the aura passes per site and the companion payloads sent per player. Game
 * free, so the record shapes are testable without a server; written on the
 * server thread, read by the command on the same thread.
 */
public final class PortalJournal {
    /** Recent ignitions and traversals kept across all players. */
    static final int RECENT = 32;

    /** A lit opening: who lit it (null for a dispenser or the self-test), what and where. */
    public record Ignition(String player, String definition, String world, String target, String axis,
                           BlockPos landing, int cells, long tick) {
    }

    /** A crossing that completed: the post-teleport step ran, so the entity is in {@code to}. */
    public record Traversal(String player, String from, String to, BlockPos portal, BlockPos landing,
                            boolean arrival, boolean preloadedSent, long tick) {
    }

    /** One zone's particle bursts: how many, the last one, what was sent and who was in range of it. */
    public record Particles(long bursts, long lastTick, String particle, int color, Set<String> recipients) {
        public Particles {
            recipients = Set.copyOf(new TreeSet<>(recipients));
        }
    }

    /** The approach title a player was last sent. */
    public record Title(String player, String world, BlockPos portal, String destination, String text, long tick) {
    }

    /** One aura's passes: how many ran, units spent by them and claim vetoes, and the last one's tick. */
    public record AuraPasses(long passes, long spent, long vetoed, long lastTick) {
    }

    private static final Deque<Ignition> IGNITIONS = new ArrayDeque<>();
    private static final Deque<Traversal> TRAVERSALS = new ArrayDeque<>();
    private static final Map<String, Ignition> LAST_IGNITION = new ConcurrentHashMap<>();
    private static final Map<String, Traversal> LAST_TRAVERSAL = new ConcurrentHashMap<>();
    private static final Map<String, Particles> PARTICLES = new ConcurrentHashMap<>();
    private static final Map<String, Title> TITLES = new ConcurrentHashMap<>();
    private static final Map<String, AuraPasses> AURAS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> COMPANION_PAYLOADS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> PRELOADED = new ConcurrentHashMap<>();

    private PortalJournal() {
    }

    /** A zone's journal key: the world it stands in and its landing cell. */
    public static String key(String world, BlockPos landing) {
        return world + "@" + landing.x() + "," + landing.y() + "," + landing.z();
    }

    public static synchronized void ignited(Ignition ignition) {
        push(IGNITIONS, ignition);
        if (ignition.player() != null) {
            LAST_IGNITION.put(ignition.player(), ignition);
        }
    }

    public static synchronized void traversed(Traversal traversal) {
        push(TRAVERSALS, traversal);
        LAST_TRAVERSAL.put(traversal.player(), traversal);
    }

    public static void particles(String zoneKey, long tick, String particle, int color, Set<String> recipients) {
        PARTICLES.merge(zoneKey, new Particles(1, tick, particle, color, recipients),
                (was, now) -> new Particles(was.bursts() + 1, tick, particle, color, recipients));
    }

    public static void titled(Title title) {
        TITLES.put(title.player(), title);
    }

    public static void auraPass(String siteKey, int spent, int vetoed, long tick) {
        AURAS.merge(siteKey, new AuraPasses(1, spent, vetoed, tick),
                (was, now) -> new AuraPasses(was.passes() + 1, was.spent() + spent, was.vetoed() + vetoed, tick));
    }

    /** One payload left for a companion; a vanilla client's count stays absent. */
    public static void companionPayload(UUID player) {
        COMPANION_PAYLOADS.merge(player, 1L, Long::sum);
    }

    /** The server told this player its crossing into {@code destination} is pre-loaded. */
    public static void preloadedSent(UUID player, String destination) {
        PRELOADED.put(player, destination);
    }

    /** Whether a pre-loaded transfer into {@code destination} was told to this player; consumed by the crossing. */
    public static boolean takePreloaded(UUID player, String destination) {
        return destination.equals(PRELOADED.remove(player));
    }

    public static synchronized List<Ignition> ignitions() {
        return List.copyOf(IGNITIONS);
    }

    public static synchronized List<Traversal> traversals() {
        return List.copyOf(TRAVERSALS);
    }

    public static Ignition lastIgnition(String player) {
        return LAST_IGNITION.get(player);
    }

    public static Traversal lastTraversal(String player) {
        return LAST_TRAVERSAL.get(player);
    }

    public static Particles particlesOf(String zoneKey) {
        return PARTICLES.get(zoneKey);
    }

    public static Title titleOf(String player) {
        return TITLES.get(player);
    }

    public static AuraPasses auraOf(String siteKey) {
        return AURAS.get(siteKey);
    }

    /** Payloads sent to this player since it joined; null when none ever was. */
    public static Long companionPayloads(UUID player) {
        return COMPANION_PAYLOADS.get(player);
    }

    /** A player left: its per-connection counts go, its history stays. */
    public static void forget(UUID player) {
        COMPANION_PAYLOADS.remove(player);
        PRELOADED.remove(player);
    }

    public static synchronized void clear() {
        IGNITIONS.clear();
        TRAVERSALS.clear();
        LAST_IGNITION.clear();
        LAST_TRAVERSAL.clear();
        PARTICLES.clear();
        TITLES.clear();
        AURAS.clear();
        COMPANION_PAYLOADS.clear();
        PRELOADED.clear();
    }

    private static <T> void push(Deque<T> deque, T item) {
        deque.addLast(item);
        while (deque.size() > RECENT) {
            deque.removeFirst();
        }
    }
}
