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
     * There is deliberately no allow-list here.
     *
     * <p>An earlier version of this test carried three known-bad dimensions as
     * an explicit exception list, because they were awaiting a content
     * decision (pocket dimensions at {@code scale 1.0} into a 256-block
     * border). All three were given a {@code portal.anchor} on 2026-07-26 — a
     * fixed arrival rather than a scaled one, the same fix
     * {@code the_starwell} already had — so the exception list is gone rather
     * than being kept around empty.
     *
     * <p>Deriving the expected set from the configs would make the assertion
     * tautological: it would pass whatever the configs said, which is the one
     * thing a config test must not do. The expectation is a fixed value —
     * ZERO — and any offender is named in the failure message.
     */
    private List<String> unreachableDimensions(Map<String, DimensionConfig> dims) {
        DimensionConfig overworld = dims.get("overworld");
        assertNotNull(overworld, "the overworld config is the source radius this check is built on");
        int sourceRadius = overworld.getPlayerBorderRadius();

        List<String> unreachable = new ArrayList<>();
        for (DimensionConfig config : dims.values()) {
            if (config.getPortal() == null || config.getPortal().anchor != null) {
                continue; // no portal to arrive through; anchors are fixed
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
    void noShippedDimensionCanStrandAPlayerOutsideItsOwnBorder() {
        List<String> unreachable = unreachableDimensions(shipped());

        assertEquals(List.of(), unreachable.stream().sorted().toList(),
                "these dimensions' portals can strand a player: a portal built further than "
                + "`borders.player * portal.scale` from the overworld origin arrives OUTSIDE this "
                + "dimension's border, where vanilla forbids breaking or placing any block — "
                + "including the portal they arrived through. Fix by raising borders.player, "
                + "lowering portal.scale, or giving the dimension a portal.anchor (a fixed arrival "
                + "is not scaled, so the source radius stops mattering).");
    }

    @Test
    void theBootValidatorAgreesWithThePureCheck() {
        // Same invariant through the path that actually runs at boot, so a
        // regression in the WIRING is caught as well as one in the configs.
        // ArrivalReachability shipped fully written with no callers at all;
        // nothing would have noticed.
        List<String> warned = PortalSafetyValidator.validate(shipped().values()).stream()
                .filter(w -> w.contains("arrive inside this dimension's border"))
                .map(w -> w.substring("Dimension ".length(), w.indexOf(':')))
                .sorted()
                .toList();

        assertEquals(unreachableDimensions(shipped()).stream().sorted().toList(), warned,
                "the boot warning must fire for exactly the dimensions the pure check flags — "
                + "no more (noise) and no fewer (a silent trap)");
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
            if (config.getPortal() != null && config.getPortal().anchor == null) {
                scaled++;
            }
        }

        assertTrue(scaled > 40, "expected the bulk of the set to use scaled arrivals, got " + scaled);
        assertTrue(unreachableDimensions(dims).size() * 10 < scaled,
                "reachability failures must be the rare exception, not the norm");
    }

    @Test
    void noShippedDimensionIsConfiguredSoItCanNeverIgnite() {
        // The validator already says "the portal can never ignite" out loud at
        // boot for a self-contradictory config. Nobody read it: five
        // dimensions shipped with a vertical shape (door/doorway) pinned to
        // orientation "horizontal", so ignition could not succeed on any axis
        // and the dimensions were simply unreachable (2026-07-26).
        //
        // A warning that only exists in a boot log is a warning that gets
        // scrolled past. This is the same class of defect as the reachability
        // check above, so it gets the same treatment: fail the build.
        List<String> unignitable = PortalSafetyValidator.validate(shipped().values()).stream()
                .filter(w -> w.contains("can never ignite"))
                .toList();

        assertEquals(List.of(), unignitable,
                "these dimensions cannot be entered at all — the portal config contradicts itself, "
                + "so ignition fails on every axis. Note that removing `orientation` is not always "
                + "the fix: a shape preset implies its own orientation (door/doorway are vertical, "
                + "end_exit is horizontal), so a dimension that is MEANT to have a floor portal "
                + "wants the shape dropped instead, leaving free-form flood-fill plus "
                + "\"orientation\": \"horizontal\".");
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
