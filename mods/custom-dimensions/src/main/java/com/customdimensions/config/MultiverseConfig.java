package com.customdimensions.config;

import com.customdimensions.MultiverseServer;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The mod's runtime view of the dimension configuration.
 *
 * Reads the per-dimension directory config/custom-dimensions/
 * (settings.json + dimensions/*.json + overlay/) via DimensionConfigLoader.
 * Everything — getCustomDimension/getCustomDimensions,
 * getReservedDimension/getReservedDimensionBySlug, getAllDimensions,
 * getPortal/getPortals, getWorldSeedOverride — resolves against one
 * Map&lt;String, DimensionConfig&gt;.
 */
public class MultiverseConfig {
    private static final MultiverseConfig INSTANCE = new MultiverseConfig();
    private static final String CONFIG_DIR_NAME = "custom-dimensions";

    private transient Path configRoot;
    private transient MinecraftServer server;

    /** Every configured entry, keyed by slug — reserved dimensions included. */
    private Map<String, DimensionConfig> configs = new LinkedHashMap<>();
    /** Portal views for the custom dimensions that declare one. */
    private List<PortalDefinition> portals = new ArrayList<>();
    private DimensionConfigLoader.Settings settings = new DimensionConfigLoader.Settings();
    /** Namespaces this mod owns (platform namespace + consumer BRAND_SLUG). */
    private Set<String> managedNamespaces = new LinkedHashSet<>(List.of("adventure"));

    public static MultiverseConfig getInstance() {
        return INSTANCE;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
        this.configRoot = server.getRunDirectory().resolve("config");
    }

    public void load() {
        if (this.configRoot == null) {
            return;
        }
        Path configDir = this.configRoot.resolve(CONFIG_DIR_NAME);
        if (!Files.isDirectory(configDir.resolve("dimensions"))) {
            MultiverseServer.LOGGER.info("No config/custom-dimensions/dimensions/ found — no dimensions to load");
            return;
        }
        DimensionConfigLoader.LoadResult result =
                DimensionConfigLoader.loadAllWithSettings(configDir, configDir.resolve("overlay"));
        MultiverseServer.LOGGER.info("Loaded {} dimension config(s) from {}",
                result.dimensions().size(), configDir);
        this.applyLoadResult(result);
    }

    /** Also the test seam: apply a LoadResult without a live server. */
    void applyLoadResult(DimensionConfigLoader.LoadResult result) {
        this.settings = result.settings();
        this.configs = new LinkedHashMap<>(result.dimensions());
        this.portals = new ArrayList<>();
        this.managedNamespaces = new LinkedHashSet<>();
        this.managedNamespaces.add(this.settings.namespace);
        for (DimensionConfig config : this.configs.values()) {
            // Reserved dimensions are deliberately NOT added to
            // managedNamespaces: minecraft: and paradise_lost: are
            // namespaces other mods also populate, and the mixins'
            // definition lookup behind that gate is by PATH. Reserved
            // dimensions resolve by exact dimension id instead
            // (getReservedDimension) — no collisions.
            if (!config.isReserved()) {
                this.managedNamespaces.add(config.getNamespace());
            }
            if (config.hasPortal()) {
                this.portals.add(config.toPortalDefinition());
            }
        }
        for (String warning : PortalSafetyValidator.validate(this.configs.values())) {
            MultiverseServer.LOGGER.warn(warning);
        }
    }

    /** Globally suppressed structure SET ids (settings.json suppress.structures). */
    public java.util.List<String> getSuppressedStructureSets() {
        return this.settings.suppressStructures;
    }

    /** Globally suppressed biome ids (settings.json suppress.biomes). */
    public java.util.List<String> getSuppressedBiomes() {
        return this.settings.suppressBiomes;
    }

    /** The platform namespace (settings.json / legacy "namespace" field). */
    public String getNamespace() {
        return this.settings.namespace;
    }

    /**
     * True when this mod owns dimensions in the given namespace — the
     * platform namespace plus any consumer (BRAND_SLUG) namespace in use.
     * The mixins' path-based definition lookups must never match another
     * mod's dimensions, so they gate on this first.
     */
    public boolean isManagedNamespace(String namespace) {
        return this.managedNamespaces.contains(namespace);
    }

    /** A dimension THIS MOD CREATES, by slug (reserved dimensions resolve via getReservedDimensionBySlug). */
    public DimensionConfig getCustomDimension(String name) {
        DimensionConfig config = this.configs.get(name);
        return config != null && !config.isReserved() ? config : null;
    }

    /** Every dimension THIS MOD CREATES — not the reserved four, which it only configures. */
    public List<DimensionConfig> getCustomDimensions() {
        return this.configs.values().stream().filter(c -> !c.isReserved()).toList();
    }

    public List<String> getDimensionNames() {
        return this.getCustomDimensions().stream().map(DimensionConfig::getName).toList();
    }

    public PortalDefinition getPortal(String id) {
        return this.portals.stream()
                .filter(p -> p.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    public List<PortalDefinition> getPortals() {
        return Collections.unmodifiableList(new ArrayList<>(this.portals));
    }

    public List<String> getPortalIds() {
        return this.portals.stream().map(PortalDefinition::getId).toList();
    }

    public Optional<PortalDefinition> getPortalByIgniter(String itemId) {
        return this.getPortalsByIgniter(itemId, null).stream().findFirst();
    }

    /**
     * Every portal using this igniter, ordered so definitions whose frame
     * matches the clicked block come first. Igniter items are SHARED across
     * dimensions (eight dims use ender_eye) — a first-match-wins lookup would
     * leave every shared-igniter portal except the alphabetically first
     * unignitable, because ignition would hunt for the wrong frame block.
     */
    public List<PortalDefinition> getPortalsByIgniter(String itemId, String clickedBlockId) {
        List<PortalDefinition> matches = new ArrayList<>();
        for (PortalDefinition p : this.portals) {
            if (p.getIgniterItem() != null && p.getIgniterItem().equals(itemId)) {
                // Matcher-aware ordering: tag/list frames count as a clicked-
                // frame match too (plain ids compare registry-free).
                if (clickedBlockId != null && p.resolveFrameMatcher().acceptsBlockId(clickedBlockId)) {
                    matches.add(0, p);
                } else {
                    matches.add(p);
                }
            }
        }
        return matches;
    }

    /**
     * Live immersive settings for a target dimension, or null. Zone records
     * in portal_links.json carry a Gson-deserialised PortalDefinition whose
     * transient immersive field is always null — restoreZones re-stamps
     * from here so "immersive" stays boot-re-read for zones ignited before
     * the setting existed, or before it last changed. Never throws — a
     * malformed/legacy targetDimension string on any live portal must not
     * abort the lookup for every other zone.
     */
    public ImmersiveSettings getImmersiveFor(RegistryKey<World> targetWorld) {
        if (targetWorld == null) {
            return null;
        }
        for (PortalDefinition p : this.portals) {
            try {
                if (targetWorld.equals(p.getTargetKey())) {
                    return p.getImmersive();
                }
            } catch (RuntimeException ignored) {
                // Malformed targetDimension on an unrelated portal must
                // never break the lookup for the rest of the list.
            }
        }
        return null;
    }

    /**
     * The live {@code portal.aura.subsume} policy for the dimension a
     * portal targets, or null when no configured portal targets it.
     *
     * <p>Same shape and same reason as {@link #getImmersiveFor}: the
     * value persisted in a zone record is a snapshot of ignition time,
     * and this is how a config edit reaches portals that are already lit.
     * Both sides of a link resolve through this one key — the source
     * zone's aura and the arrival site's aura are both governed by the
     * TARGET dimension's config, because that is the dimension whose
     * nature is leaking.
     */
    public String getAuraSubsumeFor(RegistryKey<World> targetWorld) {
        if (targetWorld == null) {
            return null;
        }
        for (PortalDefinition p : this.portals) {
            try {
                if (targetWorld.equals(p.getTargetKey())) {
                    return p.getAura().getSubsume();
                }
            } catch (RuntimeException ignored) {
                // A malformed targetDimension on an unrelated portal must
                // never break the lookup for the rest of the list.
            }
        }
        return null;
    }

    /**
     * The portal definition describing travel INTO {@code targetWorld}, or
     * null when nothing configures one.
     *
     * <p>Used for the RETURN direction too: the portal standing in a custom
     * dimension that leads home is presented using the destination
     * (overworld) portal's look and sound, not the dimension's own.
     */
    public PortalDefinition getPortalFor(RegistryKey<World> targetWorld) {
        if (targetWorld == null) {
            return null;
        }
        for (PortalDefinition p : this.portals) {
            try {
                if (targetWorld.equals(p.getTargetKey())) {
                    return p;
                }
            } catch (RuntimeException ignored) {
                // A malformed targetDimension elsewhere must not break this.
            }
        }
        return null;
    }

    public PortalDefinition getDefaultPortalForFrameBlock(String blockId) {
        if (blockId.equals(this.settings.frameOverworld)) {
            return new PortalDefinition("default_overworld", this.settings.frameOverworld, "", "minecraft:overworld", "#00AAAA", 0);
        }
        if (blockId.equals(this.settings.frameNether)) {
            return new PortalDefinition("default_nether", this.settings.frameNether, "", "minecraft:the_nether", "#AA0000", 0);
        }
        if (blockId.equals(this.settings.frameEnd)) {
            return new PortalDefinition("default_end", this.settings.frameEnd, "", "minecraft:the_end", "#00AA00", 0);
        }
        return null;
    }

    public String getFrameOverworld() {
        return this.settings.frameOverworld;
    }

    public String getFrameNether() {
        return this.settings.frameNether;
    }

    public String getFrameEnd() {
        return this.settings.frameEnd;
    }

    public int getIdleUnloadMinutes() {
        return this.settings.idleUnloadMinutes;
    }

    /**
     * Seed override for a static world, by full dimension id ("minecraft:
     * overworld" etc.). Driven by the reserved-dimension files (overworld.json,
     * the_nether.json, ...); "seed": "env" reads the SEED environment
     * variable. The config loads at createWorlds HEAD, so the override is
     * active from the overworld's very first chunk.
     */
    public Long getWorldSeedOverride(String dimensionId) {
        return this.configs.values().stream()
                .filter(DimensionConfig::isReserved)
                .filter(c -> dimensionId.equals(c.getDimensionId()))
                .map(DimensionConfig::getSeed)
                .filter(s -> s != null)
                .findFirst()
                .orElse(null);
    }

    /**
     * The reserved-dimension config for an EXACT dimension id, or null.
     *
     * <p>Reserved dimensions are managed exactly like custom dimensions —
     * seed, border, difficulty, portal, structures — but they are resolved by
     * full id rather than through {@link #isManagedNamespace}. {@code minecraft:}
     * and {@code paradise_lost:} carry other mods' dimensions, and the lookup
     * behind the namespace gate is by PATH, so a third party's
     * {@code minecraft:whatever} would resolve against one of our configs.
     */
    public DimensionConfig getReservedDimension(String dimensionId) {
        if (dimensionId == null) {
            return null;
        }
        for (DimensionConfig config : this.configs.values()) {
            if (config.isReserved() && dimensionId.equals(config.getDimensionId())) {
                return config;
            }
        }
        return null;
    }

    /** The reserved-dimension config for a given name (e.g. "overworld"), or null. */
    public DimensionConfig getReservedDimensionBySlug(String name) {
        DimensionConfig config = this.configs.get(name);
        return config != null && config.isReserved() ? config : null;
    }

    /** Every configured dimension, custom and reserved alike. */
    public List<DimensionConfig> getAllDimensions() {
        return List.copyOf(this.configs.values());
    }
}
