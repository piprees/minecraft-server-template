package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Invariants a persisted portal_links.json record must hold before it enters
 * memory. Every failure here is a WARN-and-skip for that one record — never
 * a crash, never an auto-fix. Deploys roll back, so persisted state is a
 * compatibility contract, not just today's schema: a persisted "#tag"
 * frameBlock form throws when an older jar's world-tick path
 * ({@code PortalHelper.isZoneValid} ← {@code restoreZones}) calls
 * {@code Identifier.of()} on it uncaught.
 */
public final class PortalStateValidator {

    public static final Set<String> VALID_EXIT_MODES = Set.of("origin", "bed", "worldSpawn");

    private PortalStateValidator() {
    }

    /** Failures for one source-zone-v1 record; empty when every invariant holds. */
    public static List<String> validateZone(PortalHelper.StoredPortalZone zone) {
        List<String> failures = new ArrayList<>();
        if (!isNamespacedId(zone.sourceWorld)) {
            failures.add("sourceWorld is not a namespaced id: " + zone.sourceWorld);
        }
        if (!isNamespacedId(zone.targetWorld)) {
            failures.add("targetWorld is not a namespaced id: " + zone.targetWorld);
        }
        PortalDefinition definition = zone.definition;
        String frame = definition != null ? definition.getFrameBlock() : null;
        if (frame == null || frame.isBlank() || frame.startsWith("#")) {
            failures.add("frameBlock is not a plain id: " + frame + " — accept forms belong in "
                    + "frameAccepts, a '#tag' here crash-loops a rolled-back jar");
        }
        boolean singleUse = definition != null && definition.isSingleUse();
        if (zone.singleUseTicksLeft != null && zone.singleUseTicksLeft != -1 && !singleUse) {
            failures.add("singleUseTicksLeft (" + zone.singleUseTicksLeft + ") is armed on a zone "
                    + "with singleUse=" + singleUse);
        }
        return failures;
    }

    /**
     * True when either end of a (structurally valid) zone names a dimension
     * absent from the current config. Not a failure — the mod reconciles
     * orphans by unloading them, and a consumer may legitimately disable a
     * dimension via an empty overlay — so this is worth a WARN, never a skip.
     */
    public static boolean isOrphanZone(PortalHelper.StoredPortalZone zone, Set<String> knownDimensionIds) {
        return !knownDimensionIds.contains(zone.sourceWorld) || !knownDimensionIds.contains(zone.targetWorld);
    }

    /** Failures for one aura-site-v1 record; empty when every invariant holds. */
    public static List<String> validateAuraSite(PortalHelper.AuraSite site) {
        List<String> failures = new ArrayList<>();
        if (!isNamespacedId(site.world)) {
            failures.add("world is not a namespaced id: " + site.world);
        }
        if (site.budgetSpent < 0) {
            failures.add("budgetSpent is negative: " + site.budgetSpent);
        }
        if (site.interior == null || site.interior.isEmpty()) {
            failures.add("interior is empty");
        }
        return failures;
    }

    /** Failures for one legacy return-target record (no recordType). */
    public static List<String> validateLegacyTarget(String targetWorldId, String portalWorldId, String exitMode) {
        List<String> failures = new ArrayList<>();
        if (!isNamespacedId(targetWorldId)) {
            failures.add("targetWorld is not a namespaced id: " + targetWorldId);
        }
        if (portalWorldId != null && !isNamespacedId(portalWorldId)) {
            failures.add("portalWorld is not a namespaced id: " + portalWorldId);
        }
        if (exitMode != null && !VALID_EXIT_MODES.contains(exitMode)) {
            failures.add("exitMode is not known: " + exitMode + ", expected one of " + VALID_EXIT_MODES);
        }
        return failures;
    }

    private static boolean isNamespacedId(String value) {
        if (value == null) {
            return false;
        }
        int colon = value.indexOf(':');
        return colon > 0 && colon < value.length() - 1;
    }
}
