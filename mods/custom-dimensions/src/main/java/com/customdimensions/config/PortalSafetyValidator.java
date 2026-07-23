package com.customdimensions.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Boot-time stranding check: a dimension whose portal can shut behind the
 * player (portal.singleUse) or that suppresses per-source return portals
 * (portal.anchor) must carry an exitPortal, or players can be stranded by
 * config. Same policy as the fingerprint drift warning: WARN and keep going —
 * never crash the boot, never auto-fix the config.
 */
public final class PortalSafetyValidator {

    private PortalSafetyValidator() {
    }

    public static List<String> validate(Collection<DimensionConfig> configs) {
        List<String> warnings = new ArrayList<>();
        // Known link targets: every configured dimension id + the base worlds.
        java.util.Set<String> knownIds = new java.util.HashSet<>(
                java.util.Set.of("minecraft:overworld", "minecraft:the_nether",
                        "minecraft:the_end", "paradise_lost:paradise_lost"));
        for (DimensionConfig config : configs) {
            knownIds.add(config.getDimensionId());
        }
        for (DimensionConfig config : configs) {
            validateLinks(config, knownIds, warnings);
            if (config.isBaseWorld()) {
                continue;
            }
            // Death-only exits: a dimension whose ONLY way out is dying is
            // stranding-by-config for anyone who wants to leave alive.
            if (!config.getExits().isEmpty() && config.getPortal() == null
                    && !config.hasExitPortal()
                    && config.getExits().keySet().stream().allMatch(k -> k.startsWith("death"))) {
                warnings.add(String.format(
                        "Dimension %s: the only configured exits are death triggers and there is no "
                        + "portal or exitPortal — players who want to leave alive cannot. KEEPING the "
                        + "config as written; add an \"exitPortal\" or a non-death exit (never auto-fixed).",
                        config.getName()));
            }
            if (config.getPortal() == null || config.hasExitPortal()) {
                continue;
            }
            DimensionConfig.Portal portal = config.getPortal();
            if (portal.singleUse != null && Boolean.TRUE.equals(portal.singleUse.enabled)) {
                warnings.add(String.format(
                        "Dimension %s: portal.singleUse is enabled with no exitPortal — the way in "
                        + "crumbles behind the player and nothing guarantees a way home. KEEPING the "
                        + "config as written; add an \"exitPortal\" block to fix (never auto-fixed).",
                        config.getName()));
            }
            if (portal.anchor != null) {
                warnings.add(String.format(
                        "Dimension %s: portal.anchor suppresses per-source return portals and there is "
                        + "no exitPortal — if the anchor arrival portal breaks, players are stranded "
                        + "until the next arrival rebuilds it. KEEPING the config as written; add an "
                        + "\"exitPortal\" block to fix (never auto-fixed).",
                        config.getName()));
            }
        }
        return warnings;
    }

    // Dimension-link hygiene: every exit target that names a dimension must
    // name one that exists (cyclic links are fine — chains and hubs are the
    // point; a link to NOWHERE falls back to the overworld spawn at runtime,
    // which is safe but almost certainly a typo worth surfacing).
    private static void validateLinks(DimensionConfig config, java.util.Set<String> knownIds,
                                      List<String> warnings) {
        java.util.Map<String, String> links = new java.util.LinkedHashMap<>();
        if (config.getExitPortal() != null) {
            links.put("exitPortal.target", config.getExitPortal().getTargetMode());
        }
        if (config.getPortal() != null && config.getPortal().anchor != null) {
            links.put("portal.anchor.exit", config.getPortal().anchor.getExit());
        }
        for (java.util.Map.Entry<String, DimensionConfig.ExitRule> e : config.getExits().entrySet()) {
            com.customdimensions.dimension.ExitTarget t =
                    com.customdimensions.dimension.ExitTarget.parse(e.getValue().target);
            if (t != null) {
                links.put("exits." + e.getKey(), t.canonical());
            }
        }
        for (java.util.Map.Entry<String, String> link : links.entrySet()) {
            com.customdimensions.dimension.ExitTarget t =
                    com.customdimensions.dimension.ExitTarget.parse(link.getValue());
            if (t == null || t.getKind() != com.customdimensions.dimension.ExitTarget.Kind.DIMENSION) {
                continue;
            }
            if (!knownIds.contains(t.getDimensionId())) {
                warnings.add(String.format(
                        "Dimension %s: %s links to '%s', which is not a configured dimension or base "
                        + "world — players taking it will land at the overworld spawn instead. KEEPING "
                        + "the config as written (never auto-fixed).",
                        config.getName(), link.getKey(), t.getDimensionId()));
            }
        }
    }
}
