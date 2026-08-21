package com.customdimensions.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which seeds a dimension shows, in what order, wearing which badge.
 *
 * <p>A board of the ten highest-scoring candidates answers "what did the
 * search find" and nothing else. It cannot answer the question a person
 * actually opens the page with — what have I got RIGHT NOW, and is anything
 * the search found better than it — because the seed the dimension is
 * configured with need not be a candidate at all, and after a pick it is
 * whatever was chosen rather than whatever scored highest.
 *
 * <p>So four seeds are named before the ranking gets a say:
 *
 * <ul>
 *   <li>{@link Role#CURRENT} — what the config says right now, overlay
 *       override included. Changes the moment a pick is written.
 *   <li>{@link Role#STARTING} — what the config said when the server booted,
 *       which is the world actually generated and running.
 *   <li>{@link Role#BEST} — the highest-scoring candidate in the bank.
 *   <li>{@link Role#SHORTLISTED} — kept by hand, and kept across rolls.
 * </ul>
 *
 * <p>One seed, one card. A seed holding several roles wears the strongest one
 * it holds ({@code BEST > CURRENT > STARTING > SHORTLISTED}) and sits in the
 * earliest slot it qualifies for — so a seed that is current, starting and
 * best is a single starred card at the front, not three cards saying the same
 * thing. {@link Role#OTHER} fills the rest from the ranking, and only the
 * others are capped: a shortlist is a decision and does not compete with the
 * search for places.
 *
 * <p>Pure — no config, no filesystem, no server — because the dedup and
 * precedence rules are the part worth pinning with tests.
 */
public final class SeedRoster {

    /** How many purely-ranked candidates a dimension shows beneath its named seeds. */
    public static final int OTHERS = 5;

    /** Strongest first: a seed holding several roles wears the first one it matches. */
    public enum Role {
        CURRENT("current", "📌"),
        STARTING("starting", "⌛"),
        BEST("best", "⭐"),
        SHORTLISTED("shortlisted", "📋"),
        OTHER("other", "");

        private final String id;
        private final String badge;

        Role(String id, String badge) {
            this.id = id;
            this.badge = badge;
        }

        public String id() {
            return this.id;
        }

        /** The emoji the card carries, or empty for an ordinary candidate. */
        public String badge() {
            return this.badge;
        }

        /** Whether this role survives regardless of score — named, so it is always drawn. */
        public boolean pinned() {
            return this != OTHER;
        }
    }

    /** One card: which seed, and the role it is being shown for. */
    public record Slot(long seed, Role role) {
    }

    private SeedRoster() {
    }

    /**
     * The dimension's cards, deduplicated and in display order.
     *
     * @param current     the configured seed, or null when the config names none
     * @param starting    the seed the server booted with, or null
     * @param ranked      every banked candidate, highest score first
     * @param shortlisted seeds kept by hand
     * @param others      how many purely-ranked candidates to add
     */
    public static List<Slot> of(Long current, Long starting, List<Long> ranked,
                                Set<Long> shortlisted, int others) {
        // Slot order is the order roles are offered here; the strongest role a
        // seed holds is resolved separately, so a seed can sit in the CURRENT
        // slot wearing the BEST badge.
        Map<Long, Role> placed = new LinkedHashMap<>();
        Long best = ranked == null || ranked.isEmpty() ? null : ranked.get(0);

        offer(placed, current, Role.CURRENT, current, starting, best, shortlisted);
        offer(placed, starting, Role.STARTING, current, starting, best, shortlisted);
        offer(placed, best, Role.BEST, current, starting, best, shortlisted);
        if (shortlisted != null) {
            for (Long seed : shortlisted) {
                offer(placed, seed, Role.SHORTLISTED, current, starting, best, shortlisted);
            }
        }

        int added = 0;
        if (ranked != null) {
            for (Long seed : ranked) {
                if (added >= others) {
                    break;
                }
                if (placed.containsKey(seed)) {
                    continue;
                }
                placed.put(seed, Role.OTHER);
                added++;
            }
        }

        List<Slot> out = new ArrayList<>(placed.size());
        for (Map.Entry<Long, Role> e : placed.entrySet()) {
            out.add(new Slot(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** Convenience for the live page: {@link #OTHERS} ranked candidates. */
    public static List<Slot> of(Long current, Long starting, List<Long> ranked,
                                Set<Long> shortlisted) {
        return of(current, starting, ranked, shortlisted, OTHERS);
    }

    /** Every seed on the roster — what has to be drawn, and what a sweep must keep. */
    public static Set<Long> seeds(List<Slot> roster) {
        Set<Long> out = new LinkedHashSet<>();
        for (Slot s : roster) {
            out.add(s.seed());
        }
        return out;
    }

    private static void offer(Map<Long, Role> placed, Long seed, Role slot, Long current,
                              Long starting, Long best, Set<Long> shortlisted) {
        if (seed == null || placed.containsKey(seed)) {
            return;
        }
        placed.put(seed, strongest(seed, current, starting, best, shortlisted, slot));
    }

    /**
     * The badge a seed wears: the first role it actually holds, in declaration
     * order. {@code fallback} covers a seed offered for a slot it does not
     * otherwise qualify for, which cannot happen through {@link #of} but keeps
     * this total rather than nullable.
     */
    private static Role strongest(long seed, Long current, Long starting, Long best,
                                  Set<Long> shortlisted, Role fallback) {
        if (best != null && best == seed) {
            return Role.BEST;
        }
        if (current != null && current == seed) {
            return Role.CURRENT;
        }
        if (starting != null && starting == seed) {
            return Role.STARTING;
        }
        if (shortlisted != null && shortlisted.contains(seed)) {
            return Role.SHORTLISTED;
        }
        return fallback;
    }
}
