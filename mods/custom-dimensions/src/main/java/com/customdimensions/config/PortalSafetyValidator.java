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
        for (DimensionConfig config : configs) {
            if (config.isBaseWorld() || config.getPortal() == null || config.hasExitPortal()) {
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
}
