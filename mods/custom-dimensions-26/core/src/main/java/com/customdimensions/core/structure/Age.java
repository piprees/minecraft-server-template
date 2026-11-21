package com.customdimensions.core.structure;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How old a place is, worn the same way whatever built it (lore/placement.md
 * § Age): integrity (the share of blocks kept), burial (blocks sunk below the
 * seat), weathering swaps (stone to mossy or cracked, copper to its oxidation
 * stage, planks dropped, wool and carpet gone) and growth (cobwebs, vines).
 * A set piece, an element or a definition's {@code structures.age} names one;
 * an element's own wins, then its set piece's, then the definition's.
 */
public enum Age {
    RECENT(0.9, 1.0, 0, 0.0, 0.05, 1, 0.0, 0.0, 0.0, 0.0),
    OLD(0.7, 0.85, 0.08, 0.3, 0.2, 2, 0.25, 1.0, 0.02, 0.15),
    ANCIENT(0.5, 0.7, 0.33, 0.6, 0.35, 3, 0.6, 1.0, 0.04, 0.3),
    DEEP(0.3, 0.5, 0.66, 0.7, 0.3, 3, 0.9, 1.0, 0.02, 0.2);

    public static final Set<String> NAMES = Set.of("recent", "old", "ancient", "deep");
    /** Wood whose planks, slabs and stairs rot away. */
    static final List<String> WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
            "bamboo", "crimson", "warped", "pale_oak");
    static final String[] COPPER_STAGES = {"", "exposed_", "weathered_", "oxidized_"};
    public static final String AIR = "minecraft:air";

    /** The lowest and highest integrity of this age. */
    public final double integrityMin;
    public final double integrityMax;
    /** The share of a template's height sunk below the seat (old ages sink one or two blocks whatever their height). */
    public final double burial;
    public final double mossy;
    public final double cracked;
    /** The oxidation stage copper reaches: 1 exposed, 2 weathered, 3 oxidised. */
    public final int copperStage;
    public final double planksDropped;
    public final double woolRemoved;
    public final double cobweb;
    public final double vine;

    Age(double integrityMin, double integrityMax, double burial, double mossy, double cracked, int copperStage,
        double planksDropped, double woolRemoved, double cobweb, double vine) {
        this.integrityMin = integrityMin;
        this.integrityMax = integrityMax;
        this.burial = burial;
        this.mossy = mossy;
        this.cracked = cracked;
        this.copperStage = copperStage;
        this.planksDropped = planksDropped;
        this.woolRemoved = woolRemoved;
        this.cobweb = cobweb;
        this.vine = vine;
    }

    public static Age parse(String name) {
        if (name == null) return null;
        if (!NAMES.contains(name)) throw new IllegalArgumentException("age `" + name + "` must be one of " + NAMES);
        return valueOf(name.toUpperCase(Locale.ROOT));
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The share of sand and gravel in a template's buried storey turned suspicious, with archaeology loot (placement.md § Age). */
    public double archaeology() {
        return switch (this) {
            case RECENT -> 0;
            case OLD -> 0.05;
            case ANCIENT -> 0.25;
            case DEEP -> 0.5;
        };
    }

    /** The archaeology loot table of this age's buried storeys. */
    public String archaeologyLoot() {
        return this == DEEP ? "minecraft:archaeology/trail_ruins_rare" : "minecraft:archaeology/trail_ruins_common";
    }

    /** The integrity at {@code roll} (0–1) across this age's range. */
    public double integrity(double roll) {
        return integrityMin + (integrityMax - integrityMin) * roll;
    }

    /** Blocks a template {@code height} tall sinks below its seat: one or two for an old one, a share of its height after. */
    public int embed(int height, double roll) {
        if (this == RECENT) return 0;
        if (this == OLD) return roll < 0.5 ? 1 : 2;
        return (int) Math.round(height * burial);
    }

    /**
     * The block {@code id} weathered to this age, at two rolls in 0–1 for this
     * position: a mossy or cracked variant, a later copper stage, air for rotted
     * planks and lost wool, or {@code id} itself. A returned id may not exist
     * (not every stone has a cracked form); the caller keeps the original then.
     */
    public String weather(String id, double roll, double roll2) {
        String ns = id.contains(":") ? id.substring(0, id.indexOf(':') + 1) : "minecraft:";
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (!ns.equals("minecraft:")) return id;
        if ((path.endsWith("_wool") || path.endsWith("_carpet")) && roll < woolRemoved) return AIR;
        if (wooden(path) && roll < planksDropped) return AIR;
        if (path.contains("copper") && !path.contains("waxed") && !path.contains("raw_copper") && !path.contains("copper_ore")) {
            return ns + copper(path, roll < 0.75 ? copperStage : Math.max(0, copperStage - 1));
        }
        if (roll < mossy) {
            String m = mossy(path);
            if (m != null) return ns + m;
        }
        if (roll2 < cracked) {
            String c = cracked(path);
            if (c != null) return ns + c;
        }
        return id;
    }

    static boolean wooden(String path) {
        for (String w : WOODS) {
            if (path.equals(w + "_planks") || path.equals(w + "_slab") || path.equals(w + "_stairs")) return true;
        }
        return false;
    }

    static String mossy(String path) {
        if (path.startsWith("mossy_")) return null;
        if (path.equals("stone_bricks") || path.startsWith("stone_brick_")) return "mossy_" + path;
        if (path.equals("cobblestone") || path.startsWith("cobblestone_")) return "mossy_" + path;
        return null;
    }

    static String cracked(String path) {
        return switch (path) {
            case "stone_bricks", "deepslate_bricks", "deepslate_tiles", "polished_blackstone_bricks", "nether_bricks" -> "cracked_" + path;
            default -> null;
        };
    }

    /** A copper block's path at {@code stage}, never younger than it already is. */
    static String copper(String path, int stage) {
        int current = 0;
        String base = path;
        for (int i = 1; i < COPPER_STAGES.length; i++) {
            if (path.startsWith(COPPER_STAGES[i])) {
                current = i;
                base = path.substring(COPPER_STAGES[i].length());
            }
        }
        if (base.equals("copper")) base = "copper_block";
        int target = Math.max(current, stage);
        if (target == 0) return base;
        if (base.equals("copper_block")) return COPPER_STAGES[target] + "copper";
        return COPPER_STAGES[target] + base;
    }
}
