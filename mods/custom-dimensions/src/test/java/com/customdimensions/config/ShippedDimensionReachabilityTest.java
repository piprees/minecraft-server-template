package com.customdimensions.config;

import com.customdimensions.portal.ArrivalReachability;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * <p>The failure is invisible from inside the game: outside the destination's
 * player border vanilla forbids breaking AND placing every block, so the
 * player arrives unable to touch anything, and every diagnosis of that
 * symptom points at protection code rather than at two numbers in a config
 * file.
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
     * There is deliberately no allow-list here: deriving the expected set
     * from the configs would make the assertion tautological — it would
     * pass whatever the configs said, which is the one thing a config test
     * must not do. The expectation is a fixed value — ZERO — and any
     * offender is named in the failure message.
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

    /** The dimensions the boot validator calls unreachable, in name order. */
    private static List<String> bootValidatorWarnings(Map<String, DimensionConfig> dims) {
        // Keyed on the check id rather than on a phrase in the prose: a
        // substring match silently starts asserting nothing the moment
        // somebody rewords the message.
        return PortalSafetyValidator.findings(dims.values()).stream()
                .filter(f -> "arrival_unreachable".equals(f.check()))
                .map(PortalSafetyValidator.SafetyFinding::dimension)
                .sorted()
                .toList();
    }

    /**
     * Same invariant through the path that actually runs at boot, so a
     * regression in the WIRING is caught as well as one in the configs — on a
     * set built to TRIP it. Over the shipped 82 both sides of this comparison
     * are empty, and empty equals empty whatever the validator does; the
     * shipped set's own expectations live in
     * {@link #noShippedDimensionCanStrandAPlayerOutsideItsOwnBorder} (the pure
     * check) and {@link #theShippedSetTripsExactlyTheSafetyChecksWeAcceptToday}
     * (the validator, pinned to an exact map).
     *
     * <p>Single-portal configs throughout, deliberately: the pure check reads
     * the PRIMARY portal's scale and the validator reads every entry's, so a
     * second entry is a divergence by design rather than a fault, and it is
     * not what this asserts.
     */
    @Test
    void theBootValidatorAgreesWithThePureCheckOnConfigsThatTripIt() {
        Map<String, DimensionConfig> fixture = arrivalFixture();

        List<String> pure = unreachableDimensions(fixture).stream().sorted().toList();
        List<String> warned = bootValidatorWarnings(fixture);

        assertEquals(List.of("the_narrow_shell", "the_short_leash"), pure,
                "the fixture exists to make this check fire; if the pure arithmetic no longer "
                + "flags these two, either the arithmetic changed or the fixture stopped "
                + "tripping it — and an agreement asserted over two empty lists proves nothing");
        assertEquals(pure, warned,
                "the boot warning must fire for exactly the dimensions the pure check flags — "
                + "no more (noise) and no fewer (a silent trap)");
    }

    /**
     * A source world 8192 wide, and destinations either side of the line.
     * Entering DIVIDES by scale, so a portal at source radius R arrives at
     * R / scale and needs {@code destBorder >= R / scale}: 8192 / 8 = 1024
     * fits a 1024 border exactly, which is how every shipped dimension is
     * authored, and anything tighter strands somebody.
     */
    private static Map<String, DimensionConfig> arrivalFixture() {
        Map<String, DimensionConfig> dims = new LinkedHashMap<>();
        put(dims, "overworld", "{\"borders\":{\"player\":8192}}");
        // 8192 / 8 = 1024 arrives inside 1024. The shipped shape.
        put(dims, "the_wide_road", "{\"borders\":{\"player\":1024},\"portal\":"
                + "{\"frameBlock\":\"minecraft:obsidian\",\"scale\":8.0}}");
        // 8192 / 8 = 1024 against a 512 border: everything past 4096 strands.
        put(dims, "the_short_leash", "{\"borders\":{\"player\":512},\"portal\":"
                + "{\"frameBlock\":\"minecraft:obsidian\",\"scale\":8.0}}");
        // 8192 / 4 = 2048 against a 1024 border: the same fault at a scale
        // that looks generous.
        put(dims, "the_narrow_shell", "{\"borders\":{\"player\":1024},\"portal\":"
                + "{\"frameBlock\":\"minecraft:obsidian\",\"scale\":4.0}}");
        // Exempt: a fixed arrival is not scaled, however tight the border.
        put(dims, "the_anchored_hall", "{\"borders\":{\"player\":16},\"portal\":"
                + "{\"frameBlock\":\"minecraft:obsidian\",\"scale\":8.0,"
                + "\"anchor\":{\"pos\":[0,64,0]}}}");
        // Exempt: 0 is explicitly borderless, so nothing can land outside it.
        put(dims, "the_borderless_deep", "{\"borders\":{\"player\":0},\"portal\":"
                + "{\"frameBlock\":\"minecraft:obsidian\",\"scale\":64.0}}");
        return dims;
    }

    private static void put(Map<String, DimensionConfig> dims, String slug, String json) {
        DimensionConfig config = new Gson().fromJson(json, DimensionConfig.class);
        config.setName(slug);
        dims.put(slug, config);
    }

    @Test
    void theShippedSetReallyReachesTheReachabilityArithmetic() {
        // What makes the sibling's empty result mean something: 40-odd
        // dimensions take a scaled arrival and are actually measured, so "no
        // offenders" cannot quietly mean "every config was skipped".
        Map<String, DimensionConfig> dims = shipped();
        int scaled = 0;
        for (DimensionConfig config : dims.values()) {
            if (config.getPortal() != null && config.getPortal().anchor == null) {
                scaled++;
            }
        }

        assertTrue(scaled > 40, "expected the bulk of the set to use scaled arrivals, got " + scaled);
    }

    /**
     * Every check whose verdict is "this portal cannot be lit at all". Named
     * by id rather than matched on the phrase "can never ignite", which is
     * prose seven of these eight happen to share today — and which
     * {@code portal_no_frame_block} never used, so the phrase match had been
     * quietly missing the plainest case of all.
     */
    private static final List<String> UNIGNITABLE_CHECKS = List.of(
            "portal_no_frame_block", "frame_block_unusable", "frame_color_group_unknown",
            "frame_materials_empty", "portal_shape_unknown", "portal_shape_not_a_pattern",
            "portal_shape_no_interior", "portal_shape_orientation_conflict");

    @Test
    void noShippedDimensionIsConfiguredSoItCanNeverIgnite() {
        // The validator already says so out loud at boot for a
        // self-contradictory config, but a warning that only exists in a boot
        // log gets scrolled past. This is the same class of defect as the
        // reachability check above, so it gets the same treatment: fail the
        // build.
        List<String> unignitable = PortalSafetyValidator.findings(shipped().values()).stream()
                .filter(f -> UNIGNITABLE_CHECKS.contains(f.check()))
                .map(f -> f.dimension() + ": " + f.check())
                .sorted()
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
    void theShippedSetTripsExactlyTheSafetyChecksWeAcceptToday() {
        // Measured by running this validator over config/custom-dimensions:
        // 82 configs, one finding. Pinned so the next config that trips a
        // check shows up in a diff rather than in a boot log nobody reads.
        Map<String, Long> byCheck = PortalSafetyValidator.findings(shipped().values()).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        PortalSafetyValidator.SafetyFinding::check,
                        java.util.TreeMap::new, java.util.stream.Collectors.counting()));

        assertEquals(Map.of("portal_anchor_no_exit_portal", 1L), byCheck,
                "a new safety warning on the shipped set is a config decision, not a silent "
                + "line in a boot log — fix the config, or move this expectation deliberately");
    }

    @Test
    void theOneAcceptedWarningIsTheOneWeThinkItIs() {
        // Naming it, so the count above cannot be satisfied by a DIFFERENT
        // dimension tripping the same check.
        List<String> anchored = PortalSafetyValidator.findings(shipped().values()).stream()
                .filter(f -> "portal_anchor_no_exit_portal".equals(f.check()))
                .map(PortalSafetyValidator.SafetyFinding::dimension)
                .sorted()
                .toList();

        assertEquals(List.of("the_pale_reach"), anchored);
    }

    @Test
    void noShippedDimensionUsesAFractionalScale() {
        // Scale is the ratio as people say it: 8 nether : 1 over, a whole
        // number, applied as a divisor on entry. A fractional scale is the
        // fingerprint of an inverted (multiply-on-entry) reading.
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
