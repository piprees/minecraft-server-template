package com.customdimensions.config;

import com.customdimensions.portal.ArrivalReachability;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The REAL shipped dimension set, checked against the reachability invariant.
 *
 * <p>Every other test in this suite runs on fixtures. This one runs on
 * {@code config/custom-dimensions/dimensions/*.json} as it will actually be
 * deployed, so authoring a new dimension with a scale/border pair that strands
 * players fails the build instead of failing a player.
 *
 * <p>It exists because that failure is invisible from inside the game: outside
 * the destination's player border vanilla forbids breaking AND placing every
 * block, so the player arrives unable to touch anything, and every diagnosis
 * of that symptom points at protection code rather than at two numbers in a
 * config file (2026-07-25, two sessions).
 *
 * <p>PHASE-9 predicted this test would fail on 58 dimensions "by design". That
 * prediction was written under the MULTIPLY-on-entry bug. Entering divides, so
 * the requirement inverted from {@code border >= 8192 * scale} to
 * {@code border >= 8192 / scale} — which is how every dimension was already
 * authored. It passes, and the prediction is the stale half.
 */
class ShippedDimensionReachabilityTest {

    /** Repo root, from the Gradle project directory (mods/custom-dimensions). */
    private static final Path CONFIG_ROOT = Path.of("..", "..", "config", "custom-dimensions");

    private Map<String, DimensionConfig> shipped() {
        assertTrue(Files.isDirectory(CONFIG_ROOT.resolve("dimensions")),
                "shipped dimension configs not found at " + CONFIG_ROOT.toAbsolutePath()
                        + " — this test must run against the real set, never silently skip");
        DimensionConfigLoader.Settings settings = new DimensionConfigLoader.Settings();
        settings.namespace = "adventure";
        Map<String, DimensionConfig> dims =
                DimensionConfigLoader.loadDimensions(CONFIG_ROOT, null, settings, null);
        assertFalse(dims.isEmpty(), "loaded no dimensions at all");
        return dims;
    }

    /**
     * Dimensions known to fail the invariant, awaiting a CONTENT decision.
     *
     * <p>All three are "pocket" dimensions: {@code scale 1.0} into a 256-block
     * border, so a portal built more than 256 blocks from the overworld origin
     * arrives outside their border and the player can touch nothing. The
     * sibling pocket dimension {@code the_starwell} has exactly the same
     * scale/border pair and is fine, because it declares a {@code portal.anchor}
     * — a fixed arrival, not a scaled one. That is very probably the fix for
     * these three too, but PHASE-9's stated policy is that 9b "makes the choice
     * visible; it does not make it", so the code does not quietly re-author
     * somebody's dimensions.
     *
     * <p>This list is an ALLOW-list, not a mute button: a new dimension with
     * the same defect fails the build, and fixing one of these fails the build
     * too until it is removed from here. See PLAN.md § "Outstanding for the
     * owner".
     */
    private static final List<String> KNOWN_UNREACHABLE = List.of(
            "the_emberglass_foundry", "the_tidepools", "the_wuthering_wisteria");

    private List<String> unreachableDimensions(Map<String, DimensionConfig> dims) {
        DimensionConfig overworld = dims.get("overworld");
        assertNotNull(overworld, "the overworld config is the source radius this check is built on");
        int sourceRadius = overworld.getPlayerBorderRadius();

        List<String> unreachable = new ArrayList<>();
        for (DimensionConfig config : dims.values()) {
            if (config.isBaseWorld() || config.getPortal() == null
                    || config.getPortal().anchor != null) {
                continue; // base worlds have no scaled arrival; anchors are fixed
            }
            int destRadius = config.getPlayerBorderRadius();
            if (destRadius <= 0) {
                continue; // borderless
            }
            if (!ArrivalReachability.allArrivalsReachable(config.getScale(), sourceRadius,
                    destRadius, PortalSafetyValidator.ARRIVAL_MARGIN)) {
                unreachable.add(config.getName());
            }
        }
        return unreachable;
    }

    @Test
    void noNewDimensionCanStrandAPlayerOutsideItsOwnBorder() {
        List<String> unreachable = unreachableDimensions(shipped());

        assertEquals(KNOWN_UNREACHABLE, unreachable.stream().sorted().toList(),
                "the set of dimensions whose portals can strand a player has CHANGED. A new entry is "
                + "a new trap: a portal built beyond `border * scale` from the overworld origin "
                + "arrives outside this dimension's border, where vanilla forbids breaking or "
                + "placing any block. A missing entry means one was fixed — delete it from "
                + "KNOWN_UNREACHABLE.");
    }

    @Test
    void theBootValidatorWarnsAboutExactlyTheKnownUnreachableDimensions() {
        // Same invariant through the path that actually runs at boot, so a
        // regression in the WIRING is caught as well as one in the configs.
        // ArrivalReachability shipped fully written with no callers at all;
        // nothing would have noticed.
        List<String> warned = PortalSafetyValidator.validate(shipped().values()).stream()
                .filter(w -> w.contains("arrive inside this dimension's border"))
                .map(w -> w.substring("Dimension ".length(), w.indexOf(':')))
                .sorted()
                .toList();

        assertEquals(KNOWN_UNREACHABLE, warned,
                "the boot warning must fire for exactly the dimensions the pure check flags");
    }

    @Test
    void everyOtherShippedDimensionIsReachable() {
        // The positive half, stated separately so it cannot be lost in the
        // allow-list: 70-odd dimensions are authored correctly and the check
        // is silent about all of them. A check that warns about everything is
        // not a check.
        Map<String, DimensionConfig> dims = shipped();
        int scaled = 0;
        for (DimensionConfig config : dims.values()) {
            if (!config.isBaseWorld() && config.getPortal() != null
                    && config.getPortal().anchor == null) {
                scaled++;
            }
        }

        assertTrue(scaled > 40, "expected the bulk of the set to use scaled arrivals, got " + scaled);
        assertTrue(unreachableDimensions(dims).size() * 10 < scaled,
                "reachability failures must be the rare exception, not the norm");
    }

    @Test
    void noShippedDimensionUsesAFractionalScale() {
        // A fractional scale is the fingerprint of the inverted reading that
        // caused all of this — the old README's "0.125 for nether-style 1:8".
        // Scale is the ratio as people say it: 8 nether : 1 over, a whole
        // number, applied as a divisor on entry.
        List<String> fractional = new ArrayList<>();
        for (DimensionConfig config : shipped().values()) {
            double scale = config.getScale();
            if (scale > 0 && scale != Math.floor(scale)) {
                fractional.add(config.getName() + " = " + scale);
            }
        }

        assertTrue(fractional.isEmpty(),
                "fractional portal.scale almost certainly means the inverted reading: " + fractional);
    }
}
