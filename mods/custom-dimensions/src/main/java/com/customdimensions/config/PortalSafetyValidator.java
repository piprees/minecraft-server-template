package com.customdimensions.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Config-safety checks: a dimension whose portal can shut behind the player
 * (portal.singleUse) or that suppresses per-source return portals
 * (portal.anchor) must carry an exitPortal, plus frame, shape, aura and link
 * hygiene. Same policy as the fingerprint drift warning: WARN and keep going —
 * never crash the boot, never auto-fix the config.
 *
 * <p>One implementation, two renderings, the same shape
 * {@link #frameCollisions} already uses: {@link #findings} carries the fields
 * {@code customdim lint} and CI read, and {@link #validate} renders them as
 * the prose the boot log prints.
 */
public final class PortalSafetyValidator {

    private PortalSafetyValidator() {
    }

    /**
     * One config-safety fault. {@code subject} is whatever the finding is
     * about — a field path, an offending id, a scale — so findings can be
     * diffed between runs without parsing prose.
     */
    public record SafetyFinding(String dimension, String severity, String check,
                                String subject, String message, String fix) {
    }

    /** The boot log's rendering: one sentence per finding, message then fix. */
    public static List<String> validate(Collection<DimensionConfig> configs) {
        List<String> out = new ArrayList<>();
        for (SafetyFinding f : findings(configs)) {
            out.add(String.format(
                    "Dimension %s: %s. KEEPING the config as written; %s (never auto-fixed).",
                    f.dimension(), f.message(), f.fix()));
        }
        return out;
    }

    public static List<SafetyFinding> findings(Collection<DimensionConfig> configs) {
        List<SafetyFinding> out = new ArrayList<>();
        // Known link targets: every configured dimension id + the reserved dimensions.
        java.util.Set<String> knownIds = new java.util.HashSet<>(
                java.util.Set.of("minecraft:overworld", "minecraft:the_nether",
                        "minecraft:the_end", "paradise_lost:paradise_lost"));
        for (DimensionConfig config : configs) {
            knownIds.add(config.getDimensionId());
        }
        int sourceRadius = overworldBorderRadius(configs);
        for (DimensionConfig config : configs) {
            validateLinks(config, knownIds, out);
            for (DimensionConfig.Portal portal : config.getPortals()) {
                validateFrameConfig(config, portal, out);
                validateArrivalReachability(config, portal, sourceRadius, out);
                validateStranding(config, portal, out);
                validateVanillaManaged(config, portal, out);
            }
            validatePrimaryPortal(config, out);
            // Death-only exits: a dimension whose ONLY way out is dying is
            // stranding-by-config for anyone who wants to leave alive.
            if (!config.getExits().isEmpty() && config.getPortal() == null
                    && !config.hasExitPortal()
                    && config.getExits().keySet().stream().allMatch(k -> k.startsWith("death"))) {
                out.add(new SafetyFinding(config.getName(), WARN, "exits_death_only", "exits",
                        "the only configured exits are death triggers and there is no portal or "
                        + "exitPortal — players who want to leave alive cannot",
                        "add an \"exitPortal\", or a non-death exit"));
            }
        }
        return out;
    }

    /**
     * The exit builders read {@code getPortal()} — portal entry 1, by
     * position, not by eligibility. A reserved first entry on a dimension that
     * builds its own exits would have them built from the entry vanilla owns.
     */
    private static void validatePrimaryPortal(DimensionConfig config, List<SafetyFinding> out) {
        List<DimensionConfig.Portal> portals = config.getPortals();
        if (portals.isEmpty() || !portals.get(0).isVanillaManaged()) {
            return;
        }
        if (!config.hasExitPortal() && !config.hasExitShrines()) {
            return;
        }
        out.add(new SafetyFinding(config.getName(), WARN, "primary_portal_is_vanilla_managed",
                "portal[0]",
                "the first portal entry is vanillaManaged and this dimension builds its own "
                + "exits — ExitPortalManager and ExitShrineManager read portal entry 1 by "
                + "position, so the frame, colour, cooldown and shape of every exit would come "
                + "from the entry vanilla performs the traversal for",
                "put the mod-owned portal entry first, or drop exitPortal/exitShrines from a "
                + "dimension whose only portal vanilla owns"));
    }

    // Vanilla's own routes, encoded. A vanillaManaged entry documents the
    // classic portal rather than claiming it, so a field the mod cannot honour
    // and a value that contradicts what vanilla does are both worth surfacing.
    private static final java.util.Map<String, Double> VANILLA_SCALES =
            java.util.Map.of("minecraft:the_nether", 8.0);

    private static void validateVanillaManaged(DimensionConfig config, DimensionConfig.Portal portal,
                                               List<SafetyFinding> out) {
        if (!portal.isVanillaManaged()) {
            return;
        }
        if (portal.aura != null) {
            out.add(new SafetyFinding(config.getName(), WARN, "vanilla_managed_aura",
                    "portal.aura",
                    "portal.aura is set on a vanillaManaged portal — auras are linked when this "
                    + "mod performs the traversal and vanilla performs this one, so nothing will "
                    + "ever leak",
                    "remove \"aura\", or remove \"vanillaManaged\""));
        }
        Double vanillaScale = VANILLA_SCALES.get(config.getDimensionId());
        double scale = portal.scale != null ? portal.scale : 1.0;
        if (vanillaScale != null && scale != vanillaScale) {
            out.add(new SafetyFinding(config.getName(), WARN, "vanilla_managed_scale_conflict",
                    trimScale(scale),
                    String.format(
                            "portal.scale %s on a vanillaManaged portal contradicts vanilla, which "
                            + "moves this dimension at 1:%s whatever the config says — borders and "
                            + "immersive previews would be built on a ratio players never travel at",
                            trimScale(scale), trimScale(vanillaScale)),
                    String.format("set \"scale\": %s", trimScale(vanillaScale))));
        }
    }

    // A portal that can shut behind the player, or that suppresses per-source
    // return portals, needs an exitPortal to guarantee a way home.
    private static void validateStranding(DimensionConfig config, DimensionConfig.Portal portal,
                                          List<SafetyFinding> out) {
        if (config.hasExitPortal()) {
            return;
        }
        if (portal.singleUse != null && Boolean.TRUE.equals(portal.singleUse.enabled)) {
            out.add(new SafetyFinding(config.getName(), WARN, "portal_single_use_no_exit_portal",
                    "portal.singleUse",
                    "portal.singleUse is enabled with no exitPortal — the way in crumbles behind "
                    + "the player and nothing guarantees a way home",
                    "add an \"exitPortal\" block"));
        }
        if (portal.anchor != null) {
            out.add(new SafetyFinding(config.getName(), WARN, "portal_anchor_no_exit_portal",
                    "portal.anchor",
                    "portal.anchor suppresses per-source return portals and there is no "
                    + "exitPortal — if the anchor arrival portal breaks, players are stranded "
                    + "until the next arrival rebuilds it",
                    "add an \"exitPortal\" block"));
        }
    }

    /**
     * Slack subtracted from the destination border before deciding whether an
     * arrival fits. Zero, deliberately: every shipped dimension is authored
     * as EXACTLY {@code overworldBorder / scale} (8192/8 = 1024, 8192/1 =
     * 8192, and so on), so any non-zero margin fails all of them by exactly
     * that margin. The boot check only answers whether the scaled arrival
     * COLUMN lands inside the border — the last few blocks at the extreme
     * corner are left to the arrival site search.
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
     * frame, or the ground.
     *
     * <p>Anchor dimensions are exempt: their arrival is a fixed configured
     * position, not a scaled one, so the source radius is irrelevant.
     *
     * <p>The tempting formula is {@code destinationBorder < sourceBorder *
     * scale} — that MULTIPLIES on entry, which is the wrong direction;
     * entering DIVIDES. {@link com.customdimensions.portal.ArrivalReachability}
     * has the correct arithmetic.
     */
    private static void validateArrivalReachability(DimensionConfig config,
                                                    DimensionConfig.Portal portal,
                                                    int sourceRadius, List<SafetyFinding> out) {
        if (portal.anchor != null) {
            return;
        }
        double scale = portal.scale != null ? portal.scale : 1.0;
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
        out.add(new SafetyFinding(config.getName(), WARN, "arrival_unreachable",
                trimScale(scale),
                String.format(
                        "portal.scale %s against borders.player %d means only portals built within "
                        + "%d blocks of origin arrive inside this dimension's border — beyond that "
                        + "a player lands outside it and cannot break or place ANY block, "
                        + "including the portal they arrived through",
                        trimScale(scale), destRadius, usable),
                String.format("raise borders.player to %d, or lower portal.scale", required)));
    }

    /** "8" rather than "8.0" — these are ratios people say out loud. */
    private static String trimScale(double scale) {
        return scale == Math.floor(scale) && !Double.isInfinite(scale)
                ? String.valueOf((long) scale)
                : String.valueOf(scale);
    }

    private static final java.util.Set<String> ORIENTATIONS = java.util.Set.of(
            "vertical", "horizontal", "vertical_x", "vertical_z", "any");

    // Frame-material hygiene: malformed accept forms, unknown colour groups,
    // missing framePlaceBlock on non-plain frames, unknown orientation
    // values. WARN and keep going.
    private static void validateFrameConfig(DimensionConfig config, DimensionConfig.Portal portal,
                                            List<SafetyFinding> out) {
        String name = config.getName();
        List<String> forms = portal.getFrameAcceptForms();
        if (portal.frameBlock != null && !portal.frameBlock.isJsonNull() && forms.isEmpty()) {
            out.add(new SafetyFinding(name, WARN, "frame_block_unusable", "portal.frameBlock",
                    "portal.frameBlock has an unusable shape (expected a block id, \"#ns:tag\", a "
                    + "list of those, or {\"colorGroup\": \"<colour>\"}) — the portal can never "
                    + "ignite",
                    "give frameBlock one of those four shapes"));
            return;
        }
        for (String form : forms) {
            String idPart = form.startsWith("#") ? form.substring(1) : form;
            if (net.minecraft.util.Identifier.tryParse(idPart) == null) {
                out.add(new SafetyFinding(name, WARN, "frame_form_invalid_id", form,
                        String.format(
                                "portal frame form '%s' is not a valid identifier — it will never "
                                + "match", form),
                        "correct it to a \"namespace:path\" id or a \"#namespace:path\" tag, or "
                        + "remove it"));
            }
        }
        String colour = portal.getColorGroup();
        if (colour != null && !DimensionConfig.Portal.DYE_COLOURS.contains(colour)) {
            out.add(new SafetyFinding(name, WARN, "frame_color_group_unknown", colour,
                    String.format(
                            "portal.frameBlock colorGroup '%s' is not one of the 16 dye colours — "
                            + "the #adventure:%s_blocks tag does not exist and the portal can "
                            + "never ignite", colour, colour),
                    "use one of the 16 dye colours, or name the frame blocks directly"));
        }
        boolean nonPlain = forms.stream().anyMatch(f -> f.startsWith("#")) || forms.size() > 1;
        if (nonPlain && portal.resolvePlacementBlockId() == null) {
            out.add(new SafetyFinding(name, WARN, "frame_tag_no_place_block",
                    "portal.framePlaceBlock",
                    "portal.frameBlock accepts tags but no framePlaceBlock is set — mod-built "
                    + "frames (arrival portals, exitPortal) fall back to obsidian",
                    "set \"framePlaceBlock\" to the block those frames should be built from"));
        }
        if (portal.orientation != null && !portal.orientation.isBlank()
                && !ORIENTATIONS.contains(portal.orientation.trim())) {
            out.add(new SafetyFinding(name, WARN, "portal_orientation_unknown",
                    portal.orientation.trim(),
                    String.format(
                            "portal.orientation '%s' is not one of %s — treated as \"any\"",
                            portal.orientation.trim(), ORIENTATIONS),
                    "use one of " + ORIENTATIONS + ", or drop the field"));
        }
        validateShapeConfig(config, portal, out);
        validateFrameMaterials(config, portal, out);
        validateAura(config, portal, out);
    }

    // Aura hygiene: unknown sides values and unparseable ids. WARN and keep
    // going — a malformed entry just never places anything.
    private static void validateAura(DimensionConfig config, DimensionConfig.Portal portal,
                                     List<SafetyFinding> out) {
        String name = config.getName();
        DimensionConfig.Aura aura = portal.aura;
        if (aura == null) {
            return;
        }
        if (aura.sides != null && !java.util.Set.of("source", "target", "both").contains(aura.sides)) {
            out.add(new SafetyFinding(name, WARN, "aura_sides_unknown", aura.sides,
                    String.format(
                            "portal.aura.sides '%s' is not source/target/both — treated as \"both\"",
                            aura.sides),
                    "set \"sides\" to source, target or both, or drop the field"));
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
                    out.add(new SafetyFinding(name, WARN, "aura_invalid_id", String.valueOf(id),
                            String.format(
                                    "portal.aura.%s entry '%s' is not a valid identifier — it will "
                                    + "never place", list.getKey(), id),
                            "correct it to a \"namespace:path\" id, or remove it"));
                }
            }
        }
        if (aura.conversions != null) {
            for (java.util.Map.Entry<String, String> conv : aura.conversions.entrySet()) {
                String fromId = conv.getKey().startsWith("#") ? conv.getKey().substring(1) : conv.getKey();
                if (net.minecraft.util.Identifier.tryParse(fromId) == null
                        || net.minecraft.util.Identifier.tryParse(conv.getValue()) == null) {
                    out.add(new SafetyFinding(name, WARN, "aura_conversion_invalid_id",
                            conv.getKey() + " -> " + conv.getValue(),
                            String.format(
                                    "portal.aura.conversions entry '%s' -> '%s' has an invalid "
                                    + "identifier — it will never convert",
                                    conv.getKey(), conv.getValue()),
                            "correct both sides to \"namespace:path\" ids (the left may be a "
                            + "\"#tag\"), or remove the entry"));
                }
            }
        }
    }

    // Per-part material hygiene (Tier 2b): frameBlock/frameMaterials
    // exclusivity, unknown part keys, malformed forms, horizontal misuse,
    // unresolvable per-part placement. WARN and keep going.
    private static void validateFrameMaterials(DimensionConfig config, DimensionConfig.Portal portal,
                                               List<SafetyFinding> out) {
        String name = config.getName();
        if (portal.frameMaterials == null) {
            return;
        }
        if (portal.frameBlock != null && !portal.frameBlock.isJsonNull()) {
            out.add(new SafetyFinding(name, WARN, "frame_materials_conflict",
                    "portal.frameMaterials",
                    "portal.frameBlock and portal.frameMaterials are both set — they are mutually "
                    + "exclusive and frameMaterials wins",
                    "remove whichever of the two you did not mean"));
        }
        for (String key : portal.frameMaterials.keySet()) {
            if (!DimensionConfig.Portal.FRAME_PARTS.contains(key)) {
                out.add(new SafetyFinding(name, WARN, "frame_materials_key_unknown", key,
                        String.format(
                                "portal.frameMaterials key '%s' is not one of %s — ignored",
                                key, DimensionConfig.Portal.FRAME_PARTS),
                        "rename it to one of " + DimensionConfig.Portal.FRAME_PARTS
                        + ", or remove it"));
            }
        }
        java.util.Map<String, List<String>> parts = portal.getFramePartAcceptForms();
        if (parts.isEmpty()) {
            out.add(new SafetyFinding(name, WARN, "frame_materials_empty",
                    "portal.frameMaterials",
                    "portal.frameMaterials has no usable part entries — the portal can never "
                    + "ignite",
                    "give at least one of " + DimensionConfig.Portal.FRAME_PARTS
                    + " a block id, a \"#tag\", or a list of those"));
            return;
        }
        for (java.util.Map.Entry<String, List<String>> part : parts.entrySet()) {
            for (String form : part.getValue()) {
                String idPart = form.startsWith("#") ? form.substring(1) : form;
                if (net.minecraft.util.Identifier.tryParse(idPart) == null) {
                    out.add(new SafetyFinding(name, WARN, "frame_materials_invalid_id", form,
                            String.format(
                                    "portal.frameMaterials.%s form '%s' is not a valid identifier "
                                    + "— it will never match", part.getKey(), form),
                            "correct it to a \"namespace:path\" id or a \"#namespace:path\" tag, "
                            + "or remove it"));
                }
            }
            boolean noPlain = part.getValue().stream().allMatch(f -> f.startsWith("#"));
            if (noPlain && portal.resolvePlacementBlockId() == null) {
                out.add(new SafetyFinding(name, WARN, "frame_materials_no_place_block",
                        part.getKey(),
                        String.format(
                                "portal.frameMaterials.%s is tag-only and no framePlaceBlock is "
                                + "set — mod-built frames fall back to obsidian for that part",
                                part.getKey()),
                        "set \"framePlaceBlock\" to the block that part should be built from"));
            }
        }
        String orientation = portal.orientation != null ? portal.orientation.trim() : null;
        boolean horizontalOnly = "horizontal".equals(orientation)
                || com.customdimensions.portal.PortalShape.END_EXIT.equals(portal.getShapeName());
        if (horizontalOnly) {
            out.add(new SafetyFinding(name, WARN, "frame_materials_horizontal",
                    "portal.frameMaterials",
                    "portal.frameMaterials has no effect on horizontal (Y-axis) portals — the "
                    + "union of all parts applies instead",
                    "drop frameMaterials for a flat portal, or make the portal vertical"));
        }
    }

    // Shape hygiene (Tier 2 + deep tier): unknown preset names, malformed
    // pattern objects, shape/orientation contradictions, and centreBlock
    // misuse. WARN and keep going.
    private static void validateShapeConfig(DimensionConfig config, DimensionConfig.Portal portal,
                                            List<SafetyFinding> out) {
        String name = config.getName();
        String shape = portal.getShapeName();
        if (portal.getFrameAcceptForms().isEmpty()) {
            out.add(new SafetyFinding(name, WARN, "portal_no_frame_block", "portal.frameBlock",
                    "portal has no frameBlock — a portal is its frame, so this one can never be "
                    + "lit and the dimension has no way in",
                    "set \"frameBlock\" to the block the frame is built from"));
        }
        if (shape != null
                && !com.customdimensions.portal.PortalShape.KNOWN.contains(shape)) {
            out.add(new SafetyFinding(name, WARN, "portal_shape_unknown", shape,
                    String.format(
                            "portal.shape '%s' is not one of %s — the portal can never ignite",
                            shape, com.customdimensions.portal.PortalShape.KNOWN),
                    "use one of " + com.customdimensions.portal.PortalShape.KNOWN
                    + ", or drop the field for free-form flood-fill"));
        }
        if (portal.shape != null && portal.shape.isJsonObject()) {
            List<String> template = portal.getShapeTemplate();
            if (template == null) {
                out.add(new SafetyFinding(name, WARN, "portal_shape_not_a_pattern", "portal.shape",
                        "portal.shape object is not a valid pattern (expected {\"type\": "
                        + "\"pattern\", \"template\": [rows...]}) — the portal can never ignite",
                        "give the object a \"type\" of \"pattern\" and a \"template\" array of "
                        + "rows, or name a preset shape instead"));
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
                    out.add(new SafetyFinding(name, WARN, "portal_shape_no_interior",
                            "portal.shape",
                            String.format(
                                    "portal.shape pattern has no interior cells (legend %s) — the "
                                    + "portal can never ignite", legend),
                            "mark at least one template cell as \"interior\" in the legend"));
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
                out.add(new SafetyFinding(name, WARN, "portal_shape_orientation_conflict",
                        shape + " + " + orientation,
                        String.format(
                                "portal.shape '%s' cannot exist under portal.orientation '%s' — "
                                + "the portal can never ignite", shape, orientation),
                        "drop the orientation (door and doorway are vertical, end_exit is "
                        + "horizontal, and each implies its own), or choose a shape that fits it"));
            }
        }
        if (portal.centreBlock != null && !portal.centreBlock.isBlank()) {
            if (!com.customdimensions.portal.PortalShape.END_EXIT.equals(shape)) {
                out.add(new SafetyFinding(name, WARN, "centre_block_not_end_exit",
                        "portal.centreBlock",
                        "portal.centreBlock is set but portal.shape is not \"end_exit\" — it will "
                        + "never be placed",
                        "set \"shape\": \"end_exit\", or remove centreBlock"));
            } else if (net.minecraft.util.Identifier.tryParse(portal.centreBlock.trim()) == null) {
                out.add(new SafetyFinding(name, WARN, "centre_block_invalid_id",
                        portal.centreBlock.trim(),
                        String.format(
                                "portal.centreBlock '%s' is not a valid identifier — nothing will "
                                + "be placed", portal.centreBlock.trim()),
                        "correct it to a \"namespace:path\" id, or remove it"));
            }
        }
    }

    public static final String ERROR = "error";
    public static final String WARN = "warn";

    /**
     * Two portal definitions built from the same frame material. Carries the
     * lint's fields so one implementation serves both the boot log and
     * {@code customdim lint}.
     */
    public record FrameCollision(String dimension, String severity, String check,
                                 String subject, String message, String fix) {
    }

    /** One accept form of one portal entry, flattened for comparison. */
    private record FrameEntry(String dimension, String portalId, String form,
                              String igniter, boolean vanillaManaged) {
    }

    /**
     * Portal definitions that build from the same frame material.
     *
     * <p>A frame block belongs to exactly one portal definition, so every
     * overlap is an ERROR — an already-lit portal carries no record of what lit
     * it, and adoption cannot tell two definitions on one frame apart.
     *
     * <p>Cross-dimension, so it runs over the whole set rather than inside
     * {@link #findings}'s per-dimension loop. Two entries of one dimension
     * collide the same way two dimensions do — portal ids are positional
     * ({@code slug}, {@code slug#2}), so both are compared.
     */
    public static List<FrameCollision> frameCollisions(Collection<DimensionConfig> configs) {
        java.util.Map<String, List<FrameEntry>> byForm = new java.util.LinkedHashMap<>();
        for (DimensionConfig config : configs) {
            List<DimensionConfig.Portal> portals = config.getPortals();
            for (int i = 0; i < portals.size(); i++) {
                DimensionConfig.Portal portal = portals.get(i);
                String portalId = i == 0 ? config.getName() : config.getName() + "#" + (i + 1);
                for (String form : portal.getFrameAcceptForms()) {
                    String key = normalise(form);
                    if (key.isEmpty()) {
                        continue;
                    }
                    byForm.computeIfAbsent(key, f -> new ArrayList<>())
                            .add(new FrameEntry(config.getName(), portalId, key,
                                    normalise(portal.igniterItem), portal.isVanillaManaged()));
                }
            }
        }
        List<FrameCollision> out = new ArrayList<>();
        for (List<FrameEntry> group : byForm.values()) {
            for (int a = 0; a < group.size(); a++) {
                for (int b = a + 1; b < group.size(); b++) {
                    FrameCollision collision = compareFrames(group.get(a), group.get(b));
                    if (collision != null) {
                        out.add(collision);
                    }
                }
            }
        }
        return out;
    }

    /** {@code first} is earlier in config order, which is what decides ties. */
    private static FrameCollision compareFrames(FrameEntry first, FrameEntry second) {
        if (first.portalId().equals(second.portalId())) {
            return null; // one entry listing the same form twice
        }
        String form = first.form();
        // A vanillaManaged definition holds the frame outright, so the other
        // side is not a coin flip — it is unreachable by adoption.
        if (first.vanillaManaged() != second.vanillaManaged()) {
            FrameEntry reserved = first.vanillaManaged() ? first : second;
            FrameEntry loser = first.vanillaManaged() ? second : first;
            return new FrameCollision(loser.dimension(), ERROR, "portal_frame_reserved", form,
                    "portal frame " + form + " is also declared by " + reserved.portalId()
                    + ", which is vanillaManaged — that definition holds the frame, so this "
                    + "portal is reached only by deliberate ignition with "
                    + igniterPhrase(loser) + ", never by adopting an existing " + form
                    + " portal",
                    "give this dimension its own frameBlock — a frame block belongs to one "
                    + "portal definition");
        }
        boolean bothVanilla = first.vanillaManaged() && second.vanillaManaged();
        if (bothVanilla || first.igniter().equals(second.igniter())) {
            String cause = bothVanilla
                    ? "both are vanillaManaged"
                    : "both use igniter " + igniterPhrase(first);
            return new FrameCollision(second.dimension(), ERROR, "portal_igniter_collision",
                    igniterPhrase(first) + " + " + form,
                    "portal frame " + form + " is shared with " + first.portalId() + " and "
                    + cause + " — nothing distinguishes the two definitions, so ignition and "
                    + "adoption alike fall to whichever comes first in config order",
                    "give this dimension its own frameBlock, or its own igniterItem");
        }
        return new FrameCollision(second.dimension(), ERROR, "portal_frame_shared", form,
                "portal frame " + form + " is shared with " + first.portalId() + " (igniters "
                + igniterPhrase(first) + " and " + igniterPhrase(second) + ") — ignition tells "
                + "the two apart by igniter, but an already-lit portal carries no record of "
                + "what lit it, so every unclaimed " + form + " frame is adopted by "
                + first.portalId() + ", first in config order",
                "give this dimension its own frameBlock — a frame block belongs to one "
                + "portal definition");
    }

    private static String igniterPhrase(FrameEntry entry) {
        return entry.igniter().isEmpty() ? "(none)" : entry.igniter();
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    // Dimension-link hygiene: every exit target that names a dimension must
    // name one that exists (cyclic links are fine — chains and hubs are the
    // point; a link to NOWHERE falls back to the overworld spawn at runtime,
    // which is safe but almost certainly a typo worth surfacing).
    private static void validateLinks(DimensionConfig config, java.util.Set<String> knownIds,
                                      List<SafetyFinding> out) {
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
                out.add(new SafetyFinding(config.getName(), WARN, "link_unknown_dimension",
                        link.getKey(),
                        String.format(
                                "%s links to '%s', which is not a configured dimension or base "
                                + "world — players taking it will land at the overworld spawn "
                                + "instead", link.getKey(), t.getDimensionId()),
                        "correct the dimension id, or add a config for it"));
            }
        }
    }
}
