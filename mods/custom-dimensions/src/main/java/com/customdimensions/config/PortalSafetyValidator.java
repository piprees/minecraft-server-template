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
        int sourceRadius = overworldBorderRadius(configs);
        for (DimensionConfig config : configs) {
            validateLinks(config, knownIds, warnings);
            validateFrameConfig(config, warnings);
            validateArrivalReachability(config, sourceRadius, warnings);
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

    /**
     * Slack subtracted from the destination border before deciding whether an
     * arrival fits. <b>Zero, deliberately.</b>
     *
     * <p>PHASE-9 specified a non-zero margin here so an arrival's frame ring
     * and egress pocket would be inside the border too, not just its centre
     * cell. That is a real concern — vanilla forbids placing blocks outside
     * the border, so a portal centred one block inside cannot be built. But
     * every dimension in the shipped set is authored as EXACTLY
     * {@code overworldBorder / scale} (8192/8 = 1024, 8192/1 = 8192, and so
     * on), so any margin at all makes all 74 of them fail by exactly that
     * margin. A warning that fires on every dimension is not a warning; it is
     * something people learn to scroll past, and this check only earns its
     * place by being quiet until something is actually wrong.
     *
     * <p>So the boot check answers the first-order question — does the scaled
     * arrival COLUMN land inside the border — and the last few blocks at the
     * extreme corner are left to the arrival site search, which is where
     * "nudge inward until it fits" belongs anyway. Raising this to 8 is a
     * one-character change if the authoring convention ever gains headroom.
     */
    static final int ARRIVAL_MARGIN = 0;

    /**
     * The radius a portal can be built at in the world portals come FROM.
     *
     * <p>The overworld, in practice: it is where almost every portal into a
     * custom dimension is lit, and it is the widest world in the set. A
     * chained dimension's own (smaller) border only ever makes the check more
     * permissive, so using the overworld's is the conservative reading.
     */
    private static int overworldBorderRadius(Collection<DimensionConfig> configs) {
        for (DimensionConfig config : configs) {
            if ("overworld".equals(config.getName())) {
                return config.getPlayerBorderRadius();
            }
        }
        return DimensionConfig.DEFAULT_BORDER_RADIUS;
    }

    /**
     * Can every portal a player could build actually be arrived at?
     *
     * <p>Entering DIVIDES by scale, so a portal at source radius R arrives at
     * R / scale and needs {@code destBorder >= R / scale + margin}. Outside
     * the destination's PLAYER border vanilla forbids breaking AND placing
     * every block, so the player arrives unable to touch the portal, the
     * frame, or the ground — a symptom whose diagnosis naturally points at
     * protection code rather than at a number in a config file. This check
     * turns that into a boot warning instead.
     *
     * <p>Anchor dimensions are exempt: their arrival is a fixed configured
     * position, not a scaled one, so the source radius is irrelevant.
     *
     * <p>Note for anyone re-deriving this: the tempting formula is
     * {@code destinationBorder < sourceBorder * scale} — that MULTIPLIES on
     * entry, which is the wrong direction; entering DIVIDES.
     * {@link com.customdimensions.portal.ArrivalReachability} has the
     * correct arithmetic and is the authority.
     */
    private static void validateArrivalReachability(DimensionConfig config, int sourceRadius,
                                                    List<String> warnings) {
        DimensionConfig.Portal portal = config.getPortal();
        if (portal == null || portal.anchor != null) {
            return;
        }
        double scale = config.getScale();
        int destRadius = config.getPlayerBorderRadius();
        if (destRadius <= 0) {
            return; // 0 = explicitly borderless, so nothing can land outside it
        }
        if (com.customdimensions.portal.ArrivalReachability.allArrivalsReachable(
                scale, sourceRadius, destRadius, ARRIVAL_MARGIN)) {
            return;
        }
        int usable = com.customdimensions.portal.ArrivalReachability.usableSourceRadius(
                scale, destRadius, ARRIVAL_MARGIN);
        int required = com.customdimensions.portal.ArrivalReachability.requiredDestBorderRadius(
                scale, sourceRadius, ARRIVAL_MARGIN);
        warnings.add(String.format(
                "Dimension %s: portal.scale %s against borders.player %d means only portals built "
                + "within %d blocks of origin arrive inside this dimension's border — beyond that a "
                + "player lands outside it and cannot break or place ANY block, including the portal "
                + "they arrived through. Raise borders.player to %d, or lower portal.scale. KEEPING "
                + "the config as written (never auto-fixed).",
                config.getName(), trimScale(scale), destRadius, usable, required));
    }

    /** "8" rather than "8.0" — these are ratios people say out loud. */
    private static String trimScale(double scale) {
        return scale == Math.floor(scale) && !Double.isInfinite(scale)
                ? String.valueOf((long) scale)
                : String.valueOf(scale);
    }

    private static final java.util.Set<String> ORIENTATIONS = java.util.Set.of(
            "vertical", "horizontal", "vertical_x", "vertical_z", "any");

    // Frame-material hygiene (Tier 1 of further-portal-customisations):
    // malformed accept forms, unknown colour groups, missing framePlaceBlock
    // on non-plain frames, unknown orientation values. WARN and keep going.
    private static void validateFrameConfig(DimensionConfig config, List<String> warnings) {
        DimensionConfig.Portal portal = config.getPortal();
        if (portal == null) {
            return;
        }
        List<String> forms = portal.getFrameAcceptForms();
        if (portal.frameBlock != null && !portal.frameBlock.isJsonNull() && forms.isEmpty()) {
            warnings.add(String.format(
                    "Dimension %s: portal.frameBlock has an unusable shape (expected a block id, "
                    + "\"#ns:tag\", a list of those, or {\"colorGroup\": \"<colour>\"}) — the portal "
                    + "can never ignite. KEEPING the config as written (never auto-fixed).",
                    config.getName()));
            return;
        }
        for (String form : forms) {
            String idPart = form.startsWith("#") ? form.substring(1) : form;
            if (net.minecraft.util.Identifier.tryParse(idPart) == null) {
                warnings.add(String.format(
                        "Dimension %s: portal frame form '%s' is not a valid identifier — it will "
                        + "never match. KEEPING the config as written (never auto-fixed).",
                        config.getName(), form));
            }
        }
        String colour = portal.getColorGroup();
        if (colour != null && !DimensionConfig.Portal.DYE_COLOURS.contains(colour)) {
            warnings.add(String.format(
                    "Dimension %s: portal.frameBlock colorGroup '%s' is not one of the 16 dye "
                    + "colours — the #adventure:%s_blocks tag does not exist and the portal can "
                    + "never ignite. KEEPING the config as written (never auto-fixed).",
                    config.getName(), colour, colour));
        }
        boolean nonPlain = forms.stream().anyMatch(f -> f.startsWith("#")) || forms.size() > 1;
        if (nonPlain && portal.resolvePlacementBlockId() == null) {
            warnings.add(String.format(
                    "Dimension %s: portal.frameBlock accepts tags but no framePlaceBlock is set — "
                    + "mod-built frames (arrival portals, exitPortal) fall back to obsidian. "
                    + "KEEPING the config as written; set \"framePlaceBlock\" to fix (never auto-fixed).",
                    config.getName()));
        }
        if (portal.orientation != null && !portal.orientation.isBlank()
                && !ORIENTATIONS.contains(portal.orientation.trim())) {
            warnings.add(String.format(
                    "Dimension %s: portal.orientation '%s' is not one of %s — treated as \"any\". "
                    + "KEEPING the config as written (never auto-fixed).",
                    config.getName(), portal.orientation.trim(), ORIENTATIONS));
        }
        validateShapeConfig(config, portal, warnings);
        validateFrameMaterials(config, portal, warnings);
        validateAura(config, portal, warnings);
    }

    // Aura hygiene: unknown sides values and unparseable ids. WARN and keep
    // going — a malformed entry just never places anything.
    private static void validateAura(DimensionConfig config, DimensionConfig.Portal portal,
                                     List<String> warnings) {
        DimensionConfig.Aura aura = portal.aura;
        if (aura == null) {
            return;
        }
        if (aura.sides != null && !java.util.Set.of("source", "target", "both").contains(aura.sides)) {
            warnings.add(String.format(
                    "Dimension %s: portal.aura.sides '%s' is not source/target/both — treated as "
                    + "\"both\". KEEPING the config as written (never auto-fixed).",
                    config.getName(), aura.sides));
        }
        java.util.Map<String, List<String>> idLists = new java.util.LinkedHashMap<>();
        idLists.put("palette", aura.palette);
        idLists.put("flora", aura.flora);
        idLists.put("trees", aura.trees);
        idLists.put("fluids", aura.fluids);
        for (java.util.Map.Entry<String, List<String>> list : idLists.entrySet()) {
            if (list.getValue() == null) {
                continue;
            }
            for (String id : list.getValue()) {
                if (net.minecraft.util.Identifier.tryParse(id) == null) {
                    warnings.add(String.format(
                            "Dimension %s: portal.aura.%s entry '%s' is not a valid identifier — "
                            + "it will never place. KEEPING the config as written (never auto-fixed).",
                            config.getName(), list.getKey(), id));
                }
            }
        }
        if (aura.conversions != null) {
            for (java.util.Map.Entry<String, String> conv : aura.conversions.entrySet()) {
                String fromId = conv.getKey().startsWith("#") ? conv.getKey().substring(1) : conv.getKey();
                if (net.minecraft.util.Identifier.tryParse(fromId) == null
                        || net.minecraft.util.Identifier.tryParse(conv.getValue()) == null) {
                    warnings.add(String.format(
                            "Dimension %s: portal.aura.conversions entry '%s' -> '%s' has an invalid "
                            + "identifier — it will never convert. KEEPING the config as written "
                            + "(never auto-fixed).",
                            config.getName(), conv.getKey(), conv.getValue()));
                }
            }
        }
    }

    // Per-part material hygiene (Tier 2b): frameBlock/frameMaterials
    // exclusivity, unknown part keys, malformed forms, horizontal misuse,
    // unresolvable per-part placement. WARN and keep going.
    private static void validateFrameMaterials(DimensionConfig config, DimensionConfig.Portal portal,
                                               List<String> warnings) {
        if (portal.frameMaterials == null) {
            return;
        }
        if (portal.frameBlock != null && !portal.frameBlock.isJsonNull()) {
            warnings.add(String.format(
                    "Dimension %s: portal.frameBlock and portal.frameMaterials are both set — they "
                    + "are mutually exclusive and frameMaterials wins. KEEPING the config as "
                    + "written (never auto-fixed).",
                    config.getName()));
        }
        for (String key : portal.frameMaterials.keySet()) {
            if (!DimensionConfig.Portal.FRAME_PARTS.contains(key)) {
                warnings.add(String.format(
                        "Dimension %s: portal.frameMaterials key '%s' is not one of %s — ignored. "
                        + "KEEPING the config as written (never auto-fixed).",
                        config.getName(), key, DimensionConfig.Portal.FRAME_PARTS));
            }
        }
        java.util.Map<String, List<String>> parts = portal.getFramePartAcceptForms();
        if (parts.isEmpty()) {
            warnings.add(String.format(
                    "Dimension %s: portal.frameMaterials has no usable part entries — the portal "
                    + "can never ignite. KEEPING the config as written (never auto-fixed).",
                    config.getName()));
            return;
        }
        for (java.util.Map.Entry<String, List<String>> part : parts.entrySet()) {
            for (String form : part.getValue()) {
                String idPart = form.startsWith("#") ? form.substring(1) : form;
                if (net.minecraft.util.Identifier.tryParse(idPart) == null) {
                    warnings.add(String.format(
                            "Dimension %s: portal.frameMaterials.%s form '%s' is not a valid "
                            + "identifier — it will never match. KEEPING the config as written "
                            + "(never auto-fixed).",
                            config.getName(), part.getKey(), form));
                }
            }
            boolean noPlain = part.getValue().stream().allMatch(f -> f.startsWith("#"));
            if (noPlain && portal.resolvePlacementBlockId() == null) {
                warnings.add(String.format(
                        "Dimension %s: portal.frameMaterials.%s is tag-only and no framePlaceBlock "
                        + "is set — mod-built frames fall back to obsidian for that part. KEEPING "
                        + "the config as written; set \"framePlaceBlock\" to fix (never auto-fixed).",
                        config.getName(), part.getKey()));
            }
        }
        String orientation = portal.orientation != null ? portal.orientation.trim() : null;
        boolean horizontalOnly = "horizontal".equals(orientation)
                || com.customdimensions.portal.PortalShape.END_EXIT.equals(portal.getShapeName());
        if (horizontalOnly) {
            warnings.add(String.format(
                    "Dimension %s: portal.frameMaterials has no effect on horizontal (Y-axis) "
                    + "portals — the union of all parts applies instead. KEEPING the config as "
                    + "written (never auto-fixed).",
                    config.getName()));
        }
    }

    // Shape hygiene (Tier 2 + deep tier): unknown preset names, malformed
    // pattern objects, shape/orientation contradictions, and centreBlock
    // misuse. WARN and keep going.
    private static void validateShapeConfig(DimensionConfig config, DimensionConfig.Portal portal,
                                            List<String> warnings) {
        String shape = portal.getShapeName();
        if (shape != null
                && !com.customdimensions.portal.PortalShape.KNOWN.contains(shape)) {
            warnings.add(String.format(
                    "Dimension %s: portal.shape '%s' is not one of %s — the portal can never "
                    + "ignite. KEEPING the config as written (never auto-fixed).",
                    config.getName(), shape, com.customdimensions.portal.PortalShape.KNOWN));
        }
        if (portal.shape != null && portal.shape.isJsonObject()) {
            List<String> template = portal.getShapeTemplate();
            if (template == null) {
                warnings.add(String.format(
                        "Dimension %s: portal.shape object is not a valid pattern (expected "
                        + "{\"type\": \"pattern\", \"template\": [rows...]}) — the portal can "
                        + "never ignite. KEEPING the config as written (never auto-fixed).",
                        config.getName()));
            } else {
                java.util.Map<String, String> legend = portal.getShapeLegend();
                boolean hasInterior = false;
                for (String row : template) {
                    for (char c : row.toCharArray()) {
                        if ("interior".equals(legend.get(String.valueOf(c)))) {
                            hasInterior = true;
                            break;
                        }
                    }
                }
                if (!hasInterior) {
                    warnings.add(String.format(
                            "Dimension %s: portal.shape pattern has no interior cells (legend %s) — "
                            + "the portal can never ignite. KEEPING the config as written "
                            + "(never auto-fixed).",
                            config.getName(), legend));
                }
            }
        }
        // An explicit orientation that excludes every axis the shape can
        // exist on means ignition can never succeed — surface it.
        String orientation = portal.orientation != null ? portal.orientation.trim() : null;
        if (orientation != null && !orientation.isBlank() && shape != null && !shape.isBlank()) {
            boolean verticalShape = com.customdimensions.portal.PortalShape.DOOR.equals(shape)
                    || com.customdimensions.portal.PortalShape.DOORWAY.equals(shape);
            boolean horizontalShape = com.customdimensions.portal.PortalShape.END_EXIT.equals(shape);
            if ((verticalShape && "horizontal".equals(orientation))
                    || (horizontalShape && orientation.startsWith("vertical"))) {
                warnings.add(String.format(
                        "Dimension %s: portal.shape '%s' cannot exist under portal.orientation '%s' — "
                        + "the portal can never ignite. KEEPING the config as written (never auto-fixed).",
                        config.getName(), shape, orientation));
            }
        }
        if (portal.centreBlock != null && !portal.centreBlock.isBlank()) {
            if (!com.customdimensions.portal.PortalShape.END_EXIT.equals(shape)) {
                warnings.add(String.format(
                        "Dimension %s: portal.centreBlock is set but portal.shape is not \"end_exit\" — "
                        + "it will never be placed. KEEPING the config as written (never auto-fixed).",
                        config.getName()));
            } else if (net.minecraft.util.Identifier.tryParse(portal.centreBlock.trim()) == null) {
                warnings.add(String.format(
                        "Dimension %s: portal.centreBlock '%s' is not a valid identifier — nothing "
                        + "will be placed. KEEPING the config as written (never auto-fixed).",
                        config.getName(), portal.centreBlock.trim()));
            }
        }
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
