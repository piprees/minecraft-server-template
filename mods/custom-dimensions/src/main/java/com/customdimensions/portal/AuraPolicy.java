package com.customdimensions.portal;

import java.util.Locale;

/**
 * What a portal aura is allowed to eat — {@code portal.aura.subsume}.
 *
 * <p>Three values, and they are lore as much as safety settings:
 *
 * <ul>
 *   <li>{@code none} — never replaces an existing block. The aura still
 *       adds flora and fluids to bare ground, so the place still feels
 *       touched, but nothing that is already there changes. This is what
 *       infrastructure wants: an exit-portal fixture is meant to stay a
 *       stable, recognisable landmark, and a dimension meant to be
 *       discreet should stay easy to hide.</li>
 *   <li>{@code natural} — the default. Converts natural terrain and never
 *       anything crafted or shaped. A portal on a beach slowly takes the
 *       sand around it; a cobblestone wall standing in the same sand is
 *       left alone. This is the world breathing, not the world eating.</li>
 *   <li>{@code everything} — converts whatever it reaches, player builds
 *       included. A narrative device for dimensions whose story IS the
 *       encroachment (the sculk and void families imply something is
 *       rotting the world). Opt-in per dimension, and it belongs in that
 *       dimension's description: it is a promise that a build near the
 *       portal is at risk.</li>
 * </ul>
 *
 * <p><b>Claims are an absolute veto and are NOT one of these cases.</b>
 * The claim check is a hard gate evaluated before the policy, and
 * {@code everything} does not bypass it (see {@code ClaimsCompat} and the
 * call site in {@code PortalAuraManager.runPass}). One rule, no
 * exceptions — an exception would make the guarantee unexplainable to
 * players. Claiming land is how a player says "I am prepared to host this
 * thing"; not claiming it is a decision with consequences.
 *
 * <p><b>Why "crafted" is decided by a block tag and not by history.</b>
 * The obvious discriminator is "did a player put this here", and the
 * obvious implementations are both wrong here. Querying Ledger's SQLite
 * per candidate block from the world tick is the same class of mistake as
 * sync-loading a chunk. Snapshotting the natural terrain at link time
 * fails the motivating case outright: people build the house first and
 * put the portal in the basement afterwards, so the basement is already
 * standing when the snapshot is taken and every cobble in it reads as
 * natural. A block-identity test has no such ordering hole — cobblestone
 * is a made thing whenever it appears — and it gives players a rule they
 * can state in one sentence: the aura spreads through the world, not
 * through what you made.
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
     * May the aura replace a block that is already there?
     *
     * <p>Applies to EVERY replacement, explicit {@code conversions}
     * included. A conversion map names what an author wants changed, but
     * it does not carry permission to eat a player's walls — an author who
     * means that says so with {@code everything}.
     *
     * @param subsume the policy value (any case; null = natural)
     * @param crafted whether the block is in {@code #adventure:aura_protected}
     */
    /**
     * May the aura ADD fire or a fluid to an empty cell?
     *
     * <p>Additions to air are not replacements, so the policy would let
     * them all through on a literal reading — but fire and lava are the
     * two things an aura does that damage what is around them, and
     * {@code none} exists for dimensions that are meant to be unassuming
     * and easy to hide. Flora and configured trees still appear under
     * {@code none}: the place should still feel touched.
     */
    public static boolean allowsHazardousAdditions(String subsume) {
        return !NONE.equals(normalise(subsume));
    }

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
