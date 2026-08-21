package com.customdimensions.portal;

import java.util.Locale;

/**
 * What a portal aura is allowed to eat — {@code portal.aura.subsume}.
 *
 * <ul>
 *   <li>{@code none} — never replaces an existing block; still adds flora
 *       and fluids to bare ground.</li>
 *   <li>{@code natural} — the default. Converts natural terrain, never
 *       anything crafted or shaped (see {@link #allowsReplacement}).</li>
 *   <li>{@code everything} — converts whatever it reaches, including
 *       player builds. Opt-in per dimension.</li>
 * </ul>
 *
 * <p>Claims are a hard veto evaluated before this policy — {@code
 * everything} does not bypass a claim (see {@code ClaimsCompat} and the
 * call site in {@code PortalAuraManager.runPass}).
 *
 * <p>"Crafted" is decided by a block tag
 * ({@code #adventure:aura_protected}), not by build history: a
 * block-identity test has no ordering hole (e.g. a portal placed in the
 * basement of an already-standing house), unlike snapshotting terrain at
 * link time or querying Ledger per block from the world tick.
 */
public final class AuraPolicy {

    public static final String NONE = "none";
    public static final String NATURAL = "natural";
    public static final String EVERYTHING = "everything";

    private AuraPolicy() {
    }

    /** Unknown, blank and absent values all mean the default, {@code natural}. */
    public static String normalise(String raw) {
        if (raw == null) {
            return NATURAL;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return NONE.equals(value) || EVERYTHING.equals(value) ? value : NATURAL;
    }

    /**
     * May the aura ADD fire or a fluid to an empty cell? {@code none} blocks
     * this — fire and lava are the two things an aura does that damage what
     * is around them. Flora and configured trees still appear under
     * {@code none}.
     */
    public static boolean allowsHazardousAdditions(String subsume) {
        return !NONE.equals(normalise(subsume));
    }

    /**
     * May the aura replace a block that is already there? Applies to every
     * replacement, including explicit {@code conversions} entries — an
     * author who wants to override a player's walls uses {@code everything}.
     *
     * @param subsume the policy value (any case; null = natural)
     * @param crafted whether the block is in {@code #adventure:aura_protected}
     */
    public static boolean allowsReplacement(String subsume, boolean crafted) {
        String policy = normalise(subsume);
        if (EVERYTHING.equals(policy)) {
            return true;
        }
        if (NONE.equals(policy)) {
            return false;
        }
        return !crafted;
    }
}
