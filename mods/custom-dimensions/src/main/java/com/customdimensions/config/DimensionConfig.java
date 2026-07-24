package com.customdimensions.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One dimension, fully described by a single JSON file at
 * config/custom-dimensions/dimensions/{slug}.json — the v4 unified schema
 * replacing DimensionDefinition + PortalDefinition + WorldSeedDefinition.
 *
 * The slug (name) comes from the FILENAME, never from the JSON; the loader
 * stamps it (and the resolved namespace) after deserialisation. Every field
 * is optional: getters return sensible defaults so a two-line base-world
 * file ({"seed": ..., "spawn": [...]}) is a complete config.
 *
 * Legacy multiverse_config.json fields ("biome" comma string, top-level
 * "hostileSpawning", explicit "dimensionId") are declared too so the legacy
 * converter and old-format entries deserialise into the same class.
 */
public class DimensionConfig {

    /** Filenames that override existing worlds instead of creating new ones. */
    public static final Set<String> BASE_WORLDS =
            Set.of("overworld", "the_nether", "the_end", "paradise_lost");

    private static final Map<String, String> BASE_WORLD_IDS = Map.of(
            "overworld", "minecraft:overworld",
            "the_nether", "minecraft:the_nether",
            "the_end", "minecraft:the_end",
            "paradise_lost", "paradise_lost:paradise_lost");

    public static final int DEFAULT_BORDER_RADIUS = 8192;

    // === Identity (loader-stamped, never serialised) ===
    private transient String name;
    private transient String namespace = "adventure";

    @SerializedName("type")
    private String type;
    @SerializedName("description")
    private String description;

    // === World generation ===
    /** A number, or the string "env" (base worlds: read SEED from the environment). */
    @SerializedName("seed")
    private JsonElement seed;
    @SerializedName("spawn")
    private int[] spawn;
    @SerializedName("noiseSettings")
    private String noiseSettings;
    /**
     * New-format biome entries: plain id strings, or (Tier 3)
     * {"id": "...", "parameters": {...}} objects with explicit multi-noise
     * placement intervals. Kept as raw JSON so both forms coexist per entry.
     */
    @SerializedName("biomes")
    private List<JsonElement> biomes;
    /** Legacy comma-separated biome list (multiverse_config.json). */
    @SerializedName("biome")
    private String biome;
    /** Checkerboard biome-source scale (vanilla codec 0-62). Null = vanilla default 2. */
    @SerializedName("checkerboardScale")
    private Integer checkerboardScale;
    /** Superflat custom layers, bottom-up like vanilla (height = thickness). */
    @SerializedName("layers")
    private List<FlatLayer> layers;
    /** Superflat biome id. Null = plains. */
    @SerializedName("flatBiome")
    private String flatBiome;
    /** Whitelisted ChunkGeneratorSettings field swaps (Tier 3). */
    @SerializedName("settingsOverrides")
    private SettingsOverrides settingsOverrides;
    /** Fixed circular biome patches over the generated layout (precision placement). */
    @SerializedName("biomePatches")
    private List<BiomePatch> biomePatches;
    /** Base-world travel-scale metadata (worlds[].scale) — tooling only. */
    @SerializedName("scale")
    private Double scale;

    @SerializedName("borders")
    private Borders borders;
    @SerializedName("difficulty")
    private Difficulty difficulty;
    /** Legacy top-level flag; difficulty.hostileSpawning wins when present. */
    @SerializedName("hostileSpawning")
    private Boolean hostileSpawning;

    @SerializedName("structureDensity")
    private String structureDensity;
    @SerializedName("structures")
    private Structures structures;
    @SerializedName("portal")
    private Portal portal;
    @SerializedName("exitPortal")
    private ExitPortal exitPortal;
    /** Trigger -> exit rule: ways OUT without a portal (boot-re-read). */
    @SerializedName("exits")
    private Map<String, ExitRule> exits;
    /** Scattered exit-shrine structures (the pretty way home). */
    @SerializedName("exitShrines")
    private ExitShrines exitShrines;
    @SerializedName("environment")
    private Environment environment;
    @SerializedName("seedRoll")
    private SeedRoll seedRoll;

    /** Legacy explicit id; when absent the id is {namespace}:{slug} (vanilla ids for base worlds). */
    @SerializedName("dimensionId")
    private String dimensionId;

    public DimensionConfig() {
    }

    // --- identity -----------------------------------------------------------

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNamespace() {
        return this.namespace;
    }

    public void setNamespace(String namespace) {
        if (namespace != null && !namespace.isBlank()) {
            this.namespace = namespace;
        }
    }

    public String getType() {
        return this.type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return this.description;
    }

    /** True for filenames that override existing worlds (overworld, the_nether, the_end, paradise_lost). */
    public boolean isBaseWorld() {
        return this.name != null && BASE_WORLDS.contains(this.name);
    }

    /** Full dimension id for an explicit namespace: {namespace}:{slug} (base worlds keep vanilla ids). */
    public String getDimensionId(String namespace) {
        if (this.name != null && BASE_WORLD_IDS.containsKey(this.name)) {
            return BASE_WORLD_IDS.get(this.name);
        }
        if (this.dimensionId != null && !this.dimensionId.isBlank()) {
            return this.dimensionId.toLowerCase();
        }
        return namespace + ":" + this.name;
    }

    public String getDimensionId() {
        return this.getDimensionId(this.namespace);
    }

    public Identifier getDimensionIdentifier() {
        return Identifier.of(this.getDimensionId());
    }

    // --- world generation ---------------------------------------------------

    /**
     * Resolved seed: a number stays a number; the "env" sentinel reads the
     * given environment value (numeric, or vanilla-hashed when not). Null
     * when unset or unresolvable.
     */
    public Long getEffectiveSeed(String envSeed) {
        if (this.seed == null || this.seed.isJsonNull() || !this.seed.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = this.seed.getAsJsonPrimitive();
        if (p.isNumber()) {
            return p.getAsLong();
        }
        if (p.isString()) {
            String s = p.getAsString().trim();
            if ("env".equalsIgnoreCase(s)) {
                if (envSeed == null || envSeed.isBlank()) {
                    return null;
                }
                try {
                    return Long.parseLong(envSeed.trim());
                } catch (NumberFormatException e) {
                    // Vanilla semantics for non-numeric seeds.
                    return (long) envSeed.trim().hashCode();
                }
            }
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public Long getSeed() {
        return this.getEffectiveSeed(System.getenv("SEED"));
    }

    public void setSeed(Long seed) {
        this.seed = seed == null ? null : new JsonPrimitive(seed);
    }

    /** Raw seed element ("env" sentinel included) — for tooling/round-trips. */
    public JsonElement getRawSeed() {
        return this.seed;
    }

    /** Optional [x, y, z] spawn point (seed-roller-written or manual). */
    public int[] getSpawn() {
        return this.spawn != null && this.spawn.length == 3 ? this.spawn : null;
    }

    public void setSpawn(int[] spawn) {
        this.spawn = spawn;
    }

    public String getNoiseSettings() {
        return this.noiseSettings;
    }

    public void setNoiseSettings(String noiseSettings) {
        this.noiseSettings = noiseSettings;
    }

    /** Biome id from either entry form: "ns:path" or {"id": "ns:path", ...}. */
    private static String biomeIdOf(JsonElement entry) {
        if (entry == null || entry.isJsonNull()) {
            return null;
        }
        if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
            String s = entry.getAsString().trim();
            return s.isEmpty() ? null : s;
        }
        if (entry.isJsonObject()) {
            JsonElement id = entry.getAsJsonObject().get("id");
            if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) {
                String s = id.getAsString().trim();
                return s.isEmpty() ? null : s;
            }
        }
        return null;
    }

    /**
     * Biome list as the comma-separated string the generator plumbing
     * expects. New-format "biomes" arrays win (object entries contribute
     * their "id"); the legacy "biome" string passes through. Null when
     * neither is set.
     */
    public String getBiome() {
        List<String> ids = this.getBiomes();
        if (ids != null && !ids.isEmpty()) {
            return String.join(",", ids);
        }
        return this.biome != null && !this.biome.isBlank() ? this.biome : null;
    }

    /** Biome ids from the new-format array (both entry forms); null when unset. */
    public List<String> getBiomes() {
        if (this.biomes == null) {
            return null;
        }
        List<String> ids = new java.util.ArrayList<>();
        for (JsonElement entry : this.biomes) {
            String id = biomeIdOf(entry);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Explicit multi-noise parameter overrides from object-form biomes
     * entries (Tier 3): biome id -> raw "parameters" object. Empty map when
     * none. Interval validation happens at use (DimensionManager) — this is
     * pure extraction.
     */
    public Map<String, JsonObject> getBiomeParameters() {
        Map<String, JsonObject> out = new java.util.LinkedHashMap<>();
        if (this.biomes == null) {
            return out;
        }
        for (JsonElement entry : this.biomes) {
            if (!entry.isJsonObject()) {
                continue;
            }
            String id = biomeIdOf(entry);
            JsonElement params = entry.getAsJsonObject().get("parameters");
            if (id != null && params != null && params.isJsonObject()) {
                out.put(id, params.getAsJsonObject());
            }
        }
        return out;
    }

    public void setBiome(String biome) {
        this.biome = biome;
        this.biomes = null;
    }

    /** Checkerboard scale as configured (vanilla codec 0-62); null when unset. Validated at use. */
    public Integer getCheckerboardScale() {
        return this.checkerboardScale;
    }

    /** Superflat custom layers (bottom-up); null/empty means the default bedrock/dirt/grass stack. */
    public List<FlatLayer> getLayers() {
        return this.layers;
    }

    /** Superflat biome id; null means plains. */
    public String getFlatBiome() {
        return this.flatBiome;
    }

    public SettingsOverrides getSettingsOverrides() {
        return this.settingsOverrides;
    }

    /** Configured biome patches; null when unset. Validated at use. */
    public List<BiomePatch> getBiomePatches() {
        return this.biomePatches;
    }

    /**
     * Canonical "biome@x,z,r;..." string for creation-time fingerprinting;
     * null when no patches are configured.
     */
    public String getBiomePatchesFingerprint() {
        if (this.biomePatches == null || this.biomePatches.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (BiomePatch p : this.biomePatches) {
            if (sb.length() > 0) {
                sb.append(";");
            }
            sb.append(p.biome).append("@").append(p.x).append(",").append(p.z).append(",").append(p.radius);
            if (p.replace != null && !p.replace.isBlank()) {
                sb.append(">").append(p.replace);
            }
            if (p.scope != null && !p.scope.isBlank()) {
                sb.append("!").append(p.scope);
            }
            if (p.shape != null && !p.shape.isBlank()) {
                sb.append("#").append(p.shape);
            }
            if (p.blend != null) {
                sb.append("~").append(p.blend);
            }
        }
        return sb.toString();
    }

    /**
     * Canonical "id={params json},..." string for creation-time
     * fingerprinting; null when no entry carries parameters. JsonObject
     * preserves insertion order, so the same file always fingerprints equal.
     */
    public String getBiomeParametersFingerprint() {
        Map<String, JsonObject> params = this.getBiomeParameters();
        if (params.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, JsonObject> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Canonical "key=value,..." string for creation-time fingerprinting;
     * null when no overrides are set. Only set fields appear, in a fixed
     * order, so semantically equal configs always fingerprint equal.
     */
    public String getSettingsOverridesFingerprint() {
        SettingsOverrides so = this.settingsOverrides;
        if (so == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        appendFingerprintField(sb, "seaLevel", so.seaLevel);
        appendFingerprintField(sb, "defaultBlock", so.defaultBlock);
        appendFingerprintField(sb, "defaultFluid", so.defaultFluid);
        appendFingerprintField(sb, "disableMobGeneration", so.disableMobGeneration);
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static void appendFingerprintField(StringBuilder sb, String key, Object value) {
        if (value == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(",");
        }
        sb.append(key).append("=").append(value);
    }

    /**
     * Canonical "height*block,..." string for creation-time fingerprinting
     * (worldgen drift detection); null when no custom layers are set.
     */
    public String getLayersFingerprint() {
        if (this.layers == null || this.layers.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (FlatLayer layer : this.layers) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(layer.height).append("*").append(layer.block);
        }
        return sb.toString();
    }

    /** Base-world travel scale (tooling metadata); custom dims use portal.scale. */
    public double getScale() {
        if (this.scale != null) {
            return this.scale;
        }
        return this.portal != null && this.portal.scale != null ? this.portal.scale : 1.0;
    }

    // --- borders / difficulty -----------------------------------------------

    public Borders getBorders() {
        return this.borders;
    }

    public int getPlayerBorderRadius() {
        return this.borders != null && this.borders.player != null
                ? this.borders.player : DEFAULT_BORDER_RADIUS;
    }

    public int getGenerationBorderRadius() {
        return this.borders != null && this.borders.generation != null
                ? this.borders.generation : DEFAULT_BORDER_RADIUS;
    }

    public Difficulty getDifficulty() {
        return this.difficulty;
    }

    public boolean isHostileSpawningEnabled() {
        if (this.difficulty != null && this.difficulty.hostileSpawning != null) {
            return this.difficulty.hostileSpawning;
        }
        return this.hostileSpawning == null || this.hostileSpawning;
    }

    public void setHostileSpawning(Boolean hostileSpawning) {
        this.hostileSpawning = hostileSpawning;
    }

    public String getStructureDensity() {
        return this.structureDensity;
    }

    public void setStructureDensity(String structureDensity) {
        this.structureDensity = structureDensity;
    }

    public Structures getStructures() {
        return this.structures;
    }

    public Environment getEnvironment() {
        return this.environment;
    }

    public SeedRoll getSeedRoll() {
        return this.seedRoll;
    }

    /** Max locate distance for the seed roller: explicit, or generation border + 1000. */
    public int getLocateCap() {
        if (this.seedRoll != null && this.seedRoll.locateCap != null) {
            return this.seedRoll.locateCap;
        }
        return this.getGenerationBorderRadius() + 1000;
    }

    // --- portal ---------------------------------------------------------------

    public Portal getPortal() {
        return this.portal;
    }

    public boolean hasPortal() {
        return this.portal != null && !this.portal.getFrameAcceptForms().isEmpty();
    }

    public ExitPortal getExitPortal() {
        return this.exitPortal;
    }

    /** Exit-condition rules keyed by trigger; empty map when unset. */
    public Map<String, ExitRule> getExits() {
        return this.exits != null ? this.exits : Map.of();
    }

    public ExitShrines getExitShrines() {
        return this.exitShrines;
    }

    /** True when adventure:exit_shrine structures generate (and register) here. */
    public boolean hasExitShrines() {
        return this.exitShrines != null && !Boolean.FALSE.equals(this.exitShrines.enabled);
    }

    /** True when the mod must build and maintain an exit portal near spawn. */
    public boolean hasExitPortal() {
        return this.exitPortal != null && !Boolean.FALSE.equals(this.exitPortal.enabled);
    }

    /**
     * Runtime portal view for PortalHelper and the ignition/travel mixins.
     * The portal id is the dimension slug; the target is this dimension.
     *
     * Unlike worldgen config (creation-time-only, baked into level.dat),
     * everything here — anchor, singleUse, sounds, scale — is re-read every
     * boot, so portal behaviour changes apply to existing dimensions
     * without a world wipe.
     */
    public PortalDefinition toPortalDefinition() {
        if (this.portal == null) {
            return null;
        }
        // Primary frame form: ALWAYS a plain, parseable block id — the plain
        // config id, else the placement block, else obsidian (the documented
        // build fallback). Never a "#tag" form: definitions persist into
        // portal_links.json zone records, and older jars Identifier.of() the
        // frameBlock in an UNCAUGHT world-tick path — a '#' there crash-loops
        // any server that downgrades (hit live 2026-07-23 testing v3.6.0
        // against new-format records). With a parseable-but-wrong id, old
        // jars just drop the zone as invalid, which is the graceful floor.
        List<String> accepts = this.portal.getFrameAcceptForms();
        String plainId = this.portal.getFrameBlockId();
        String place = this.portal.resolvePlacementBlockId();
        String primary = plainId != null ? plainId
                : (place != null ? place : "minecraft:obsidian");
        PortalDefinition def = new PortalDefinition(
                this.name,
                primary,
                this.portal.igniterItem != null ? this.portal.igniterItem : "",
                this.getDimensionId(),
                this.portal.color,
                this.portal.lightLevel != null ? this.portal.lightLevel : 0);
        // Only store what the simple form can't express — keeps legacy-shaped
        // definitions (and their persisted zone records) unchanged.
        if (!accepts.equals(List.of(primary))) {
            def.setFrameAccepts(accepts);
        }
        Map<String, List<String>> parts = this.portal.getFramePartAcceptForms();
        if (!parts.isEmpty()) {
            def.setFramePartAccepts(parts);
        }
        // Plumb the explicit framePlaceBlock through: without this, a plain
        // frameBlock (e.g. "minecraft:stone") silently overrides a differing
        // explicit framePlaceBlock in getFramePlaceBlock()'s fallback chain
        // (found live 2026-07-24 while verifying per-part placement).
        if (this.portal.framePlaceBlock != null && !this.portal.framePlaceBlock.isBlank()) {
            def.setFramePlaceBlock(this.portal.framePlaceBlock.trim());
        }
        if (this.portal.orientation != null && !this.portal.orientation.isBlank()) {
            def.setOrientation(this.portal.orientation.trim());
        }
        if (this.portal.shape != null && !this.portal.shape.isBlank()
                && !com.customdimensions.portal.PortalShape.STANDARD.equals(this.portal.shape.trim())) {
            def.setShape(this.portal.shape.trim());
        }
        if (this.portal.centreBlock != null && !this.portal.centreBlock.isBlank()) {
            def.setCentreBlock(this.portal.centreBlock.trim());
        }
        def.setScale(this.portal.scale != null ? this.portal.scale : 1.0);
        def.setCooldown(this.portal.cooldown != null ? this.portal.cooldown : 40);
        def.setParticleType(this.portal.particleType);
        def.setIgniteSound(this.portal.getIgniteSound());
        def.setEnterSound(this.portal.getEnterSound());
        def.setExitSound(this.portal.getExitSound());
        if (this.portal.anchor != null) {
            def.setAnchorPos(this.portal.anchor.resolvePos(this.getSpawn()));
            def.setAnchorExit(this.portal.anchor.getExit());
        }
        if (this.portal.singleUse != null && Boolean.TRUE.equals(this.portal.singleUse.enabled)) {
            def.setSingleUse(true);
            def.setSingleUseDelayTicks(this.portal.singleUse.getDelaySeconds() * 20);
            def.setSingleUseBreakMode(this.portal.singleUse.getBreakMode());
            def.setSingleUseDecayMap(this.portal.singleUse.decayMap);
        }
        if (this.portal.aura != null) {
            PortalDefinition.AuraSettings aura = new PortalDefinition.AuraSettings();
            aura.enabled = this.portal.aura.enabled;
            aura.radius = this.portal.aura.radius;
            aura.interval = this.portal.aura.interval;
            aura.blocksPerPass = this.portal.aura.blocksPerPass;
            aura.budget = this.portal.aura.budget;
            aura.sides = this.portal.aura.sides;
            aura.palette = this.portal.aura.palette;
            aura.flora = this.portal.aura.flora;
            aura.trees = this.portal.aura.trees;
            aura.fluids = this.portal.aura.fluids;
            aura.conversions = this.portal.aura.conversions;
            aura.fireChance = this.portal.aura.fireChance;
            def.setAura(aura);
        }
        return def;
    }

    // === Nested schema blocks =================================================

    public static class Borders {
        @SerializedName("player")
        public Integer player;
        @SerializedName("generation")
        public Integer generation;
    }

    public static class Difficulty {
        @SerializedName("hostileSpawning")
        public Boolean hostileSpawning;
        @SerializedName("mobMultiplier")
        public Double mobMultiplier;
        @SerializedName("attributes")
        public Attributes attributes;
        @SerializedName("playerLuck")
        public Double playerLuck;
        @SerializedName("depthScaling")
        public DepthScaling depthScaling;

        public double getMobMultiplier() {
            return this.mobMultiplier != null ? this.mobMultiplier : 1.0;
        }

        public double getPlayerLuck() {
            return this.playerLuck != null ? this.playerLuck : 1.0;
        }
    }

    public static class Attributes {
        @SerializedName("health")
        public Boolean health;
        @SerializedName("damage")
        public Boolean damage;
        @SerializedName("armor")
        public Boolean armor;
        @SerializedName("speed")
        public Boolean speed;
        @SerializedName("knockback")
        public Boolean knockback;
    }

    public static class DepthScaling {
        @SerializedName("enabled")
        public Boolean enabled;
        @SerializedName("startY")
        public Integer startY;
        @SerializedName("endY")
        public Integer endY;
        @SerializedName("minMultiplier")
        public Double minMultiplier;
        @SerializedName("maxMultiplier")
        public Double maxMultiplier;
    }

    /**
     * One fixed circular biome patch: the biome claims every column within
     * `radius` blocks of (x, z), the delegate source answers everywhere
     * else. Creation-time worldgen (the wrapped source is baked into
     * level.dat). A patch at spawn deletes the seed-roll spawn lottery.
     */
    public static class BiomePatch {
        @SerializedName("biome")
        public String biome;
        @SerializedName("x")
        public Integer x;
        @SerializedName("z")
        public Integer z;
        @SerializedName("radius")
        public Integer radius;
        /**
         * Unset = stamp mode (the area claims everything). A biome id =
         * swap mode: only columns resolving to that biome are substituted
         * (shape preserved). "*" = any biome.
         */
        @SerializedName("replace")
        public String replace;
        /** Edge jitter in blocks (0-64, default 8; 0 = razor edge). Local patches only. */
        @SerializedName("blend")
        public Integer blend;
        /** "clip" (default) = bounded by the area; "global" = dimension-wide swap. */
        @SerializedName("scope")
        public String scope;
        /** "circle" (default) or "square" (Chebyshev — tiles cleanly). */
        @SerializedName("shape")
        public String shape;
    }

    /**
     * Whitelisted per-dimension ChunkGeneratorSettings swaps (Tier 3 of the
     * Custom-world-settings matrix). Applied AFTER noiseSettings resolution:
     * the resolved preset (or the type's default) is cloned with these
     * fields replaced. Creation-time worldgen — baked into level.dat.
     * Arbitrary inline noise settings stay unsupported by design.
     */
    public static class SettingsOverrides {
        @SerializedName("seaLevel")
        public Integer seaLevel;
        /** Block id, e.g. "minecraft:netherrack". */
        @SerializedName("defaultBlock")
        public String defaultBlock;
        /** Block id of a fluid block, e.g. "minecraft:lava". */
        @SerializedName("defaultFluid")
        public String defaultFluid;
        @SerializedName("disableMobGeneration")
        public Boolean disableMobGeneration;
    }

    /** One superflat layer, bottom-up like vanilla: height = thickness in blocks. */
    public static class FlatLayer {
        @SerializedName("block")
        public String block;
        @SerializedName("height")
        public Integer height;
    }

    public static class Structures {
        @SerializedName("wants")
        public Map<String, StructureWant> wants;
        @SerializedName("shuns")
        public Map<String, StructureShun> shuns;
        @SerializedName("endgame")
        public EndgameConfig endgame;
        /**
         * Runtime per-set placement overrides (Tier 3), keyed by structure
         * SET id (e.g. "minecraft:villages" — the worldgen/structure_set
         * registry key, NOT a structure id). Unlike wants/shuns (roller-only
         * scoring), this changes real generation: the set's random_spread
         * placement is rebuilt with these exact values. Creation-time-ish:
         * applies to newly generated chunks only (grid re-rolls at the
         * explored-terrain border, same caveat as datapack spacing edits).
         */
        @SerializedName("spacing")
        public Map<String, SpacingOverride> spacing;
    }

    /** Explicit placement values for one structure set (see Structures.spacing). */
    public static class SpacingOverride {
        @SerializedName("spacing")
        public Integer spacing;
        @SerializedName("separation")
        public Integer separation;
    }

    public static class StructureWant {
        @SerializedName("min")
        public Integer min;
        @SerializedName("max")
        public Integer max;
    }

    public static class StructureShun {
        @SerializedName("minDistance")
        public Integer minDistance;
    }

    public static class EndgameConfig {
        @SerializedName("allow")
        public Boolean allow;
        @SerializedName("safeRadius")
        public Integer safeRadius;
    }

    public static class Portal {
        /** The 16 dye colours, for colorGroup validation and tag sugar. */
        public static final Set<String> DYE_COLOURS = Set.of(
                "white", "orange", "magenta", "light_blue", "yellow", "lime",
                "pink", "gray", "light_gray", "cyan", "purple", "blue",
                "brown", "green", "red", "black");

        /**
         * What the frame ACCEPTS — one of four forms:
         *   "minecraft:oak_planks"        single block id (legacy, unchanged)
         *   "#minecraft:logs"             block tag reference
         *   ["minecraft:oak_planks", ...] explicit list (ids and #tags mix)
         *   {"colorGroup": "red"}         sugar for "#adventure:red_blocks"
         *                                 (16 tags shipped in the jar datapack)
         * Kept as raw JSON so every form deserialises into the same field.
         */
        @SerializedName("frameBlock")
        public JsonElement frameBlock;
        /**
         * Per-part frame requirements (Tier 2b): keys "top" / "sides" /
         * "bottom", each value any frameBlock accept form (id, "#ns:tag",
         * list, {"colorGroup": ...}). Mutually exclusive with frameBlock —
         * both present WARNs and frameMaterials wins. Vertical portals
         * only; horizontal (Y-axis) fills validate against the union.
         * Parts left out accept the union of the specified parts.
         */
        @SerializedName("frameMaterials")
        public JsonObject frameMaterials;
        /**
         * Concrete block the mod places when it builds frames (arrival and
         * exit portals). Required in spirit when frameBlock is not a single
         * plain id — accepting is not placing. Falls back to the first
         * plain id in a list, or "<colour>_wool" for colour groups.
         */
        @SerializedName("framePlaceBlock")
        public String framePlaceBlock;
        /**
         * Ignition orientation constraint: "vertical" (X/Z) | "horizontal"
         * (Y) | "vertical_x" | "vertical_z" | "any". Absent = "any" —
         * identical to today's behaviour (all three axes attempted).
         */
        @SerializedName("orientation")
        public String orientation;
        /**
         * Named shape preset: "standard" (absent — free-form flood-fill) |
         * "door" (1x2 vertical) | "doorway" (2x3 vertical) | "end_exit"
         * (horizontal ring). Shapes imply an orientation default; an
         * explicit "orientation" always wins.
         */
        @SerializedName("shape")
        public String shape;
        /**
         * Block id placed at the interior centre on end_exit ignition (a
         * pedestal — dragon egg, trophy). Only meaningful with
         * shape "end_exit".
         */
        @SerializedName("centreBlock")
        public String centreBlock;
        @SerializedName("igniterItem")
        public String igniterItem;
        @SerializedName("color")
        public String color;
        @SerializedName("lightLevel")
        public Integer lightLevel;
        @SerializedName("scale")
        public Double scale;
        @SerializedName("cooldown")
        public Integer cooldown;
        @SerializedName("particleType")
        public String particleType;
        @SerializedName("sounds")
        public PortalSounds sounds;
        // Legacy flat sound keys (multiverse_config.json portals[]).
        @SerializedName("igniteSound")
        public String igniteSound;
        @SerializedName("enterSound")
        public String enterSound;
        @SerializedName("exitSound")
        public String exitSound;
        @SerializedName("anchor")
        public Anchor anchor;
        @SerializedName("singleUse")
        public SingleUse singleUse;
        /**
         * Environmental spread around this dimension's portals (both
         * sides). Absent = the derived bi-directional leak: each side's
         * surroundings are sampled at link time and leak through to the
         * other. See PortalAuraManager.
         */
        @SerializedName("aura")
        public Aura aura;

        public String getIgniteSound() {
            if (this.sounds != null && this.sounds.ignite != null) {
                return this.sounds.ignite;
            }
            return this.igniteSound != null ? this.igniteSound : "block.portal.trigger";
        }

        public String getEnterSound() {
            if (this.sounds != null && this.sounds.enter != null) {
                return this.sounds.enter;
            }
            return this.enterSound != null ? this.enterSound : "block.portal.travel";
        }

        public String getExitSound() {
            if (this.sounds != null && this.sounds.exit != null) {
                return this.sounds.exit;
            }
            return this.exitSound != null ? this.exitSound : "block.portal.travel";
        }

        /**
         * Normalised accept forms (block ids and "#ns:path" tags) from any
         * frameBlock shape. Empty when unset/unusable. Colour groups
         * resolve to the jar-shipped "#adventure:<colour>_blocks" tag —
         * unknown colour names still emit the tag (it won't exist, so it
         * never matches; PortalSafetyValidator warns).
         */
        public List<String> getFrameAcceptForms() {
            Map<String, List<String>> parts = this.getFramePartAcceptForms();
            if (!parts.isEmpty()) {
                // frameMaterials wins over frameBlock: the union of every
                // part's forms is what the flood-fill accepts.
                List<String> union = new java.util.ArrayList<>();
                for (List<String> forms : parts.values()) {
                    for (String form : forms) {
                        if (!union.contains(form)) {
                            union.add(form);
                        }
                    }
                }
                return union;
            }
            return acceptFormsFrom(this.frameBlock);
        }

        /** The frame part names per-part materials recognise, in order. */
        public static final List<String> FRAME_PARTS = List.of("top", "sides", "bottom");

        /**
         * Per-part accept forms from frameMaterials ("top"/"sides"/
         * "bottom" -> normalised forms). Empty map when unset. Unknown
         * keys are ignored here (PortalSafetyValidator warns).
         */
        public Map<String, List<String>> getFramePartAcceptForms() {
            Map<String, List<String>> out = new java.util.LinkedHashMap<>();
            if (this.frameMaterials == null) {
                return out;
            }
            for (String part : FRAME_PARTS) {
                JsonElement e = this.frameMaterials.get(part);
                if (e != null && !e.isJsonNull()) {
                    List<String> forms = acceptFormsFrom(e);
                    if (!forms.isEmpty()) {
                        out.put(part, forms);
                    }
                }
            }
            return out;
        }

        /**
         * Normalised accept forms from ANY frame-form JSON element: a
         * plain/tag id string, an array of those, or {"colorGroup": ...}
         * (sugar for the jar-shipped "#adventure:<colour>_blocks" tag).
         */
        public static List<String> acceptFormsFrom(JsonElement e) {
            List<String> out = new java.util.ArrayList<>();
            if (e == null || e.isJsonNull()) {
                return out;
            }
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                String s = e.getAsString().trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            } else if (e.isJsonArray()) {
                for (JsonElement entry : e.getAsJsonArray()) {
                    if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                        String s = entry.getAsString().trim();
                        if (!s.isEmpty()) {
                            out.add(s);
                        }
                    }
                }
            } else if (e.isJsonObject()) {
                String colour = colorGroupOf(e);
                if (colour != null) {
                    out.add("#adventure:" + colour + "_blocks");
                }
            }
            return out;
        }

        /** The colorGroup name of a {"colorGroup": ...} element, else null. */
        public static String colorGroupOf(JsonElement e) {
            if (e == null || !e.isJsonObject()) {
                return null;
            }
            JsonElement cg = e.getAsJsonObject().get("colorGroup");
            if (cg != null && cg.isJsonPrimitive() && cg.getAsJsonPrimitive().isString()) {
                String s = cg.getAsString().trim().toLowerCase();
                return s.isEmpty() ? null : s;
            }
            return null;
        }

        /** The colorGroup name when frameBlock is that form, else null. */
        public String getColorGroup() {
            return colorGroupOf(this.frameBlock);
        }

        /** The single plain block id when frameBlock is exactly that legacy form, else null. */
        public String getFrameBlockId() {
            if (this.frameBlock != null && this.frameBlock.isJsonPrimitive()
                    && this.frameBlock.getAsJsonPrimitive().isString()) {
                String s = this.frameBlock.getAsString().trim();
                if (!s.isEmpty() && !s.startsWith("#")) {
                    return s;
                }
            }
            return null;
        }

        /**
         * Concrete block id the mod places when it builds frames: explicit
         * framePlaceBlock wins; a plain frameBlock id is its own place
         * block; lists fall back to their first plain id; colour groups to
         * "<colour>_wool". Null for tag-only configs without a place block
         * (callers keep their existing fallbacks; validator warns).
         */
        public String resolvePlacementBlockId() {
            if (this.framePlaceBlock != null && !this.framePlaceBlock.isBlank()) {
                return this.framePlaceBlock.trim();
            }
            String plain = this.getFrameBlockId();
            if (plain != null) {
                return plain;
            }
            for (String form : this.getFrameAcceptForms()) {
                if (!form.startsWith("#")) {
                    return form;
                }
            }
            String colour = this.getColorGroup();
            if (colour != null && DYE_COLOURS.contains(colour)) {
                return "minecraft:" + colour + "_wool";
            }
            return null;
        }
    }

    /**
     * End-gateway-style fixed landing: every source portal for this
     * dimension arrives at one anchor position and no per-source target
     * portal is ever built. Boot-re-read like the rest of the portal block.
     */
    public static class Anchor {
        /** [x, y, z], or the string "spawn" (also the default when absent). */
        @SerializedName("pos")
        public JsonElement pos;
        /**
         * Exit target for the anchor arrival portal: "origin" (default) |
         * "bed" | "worldSpawn", or a dimension-link descriptor
         * {"dimension": ..., "arrival": ...} for chained dimensions.
         */
        @SerializedName("exit")
        public JsonElement exit;

        /** Canonical exit-mode string (ExitTarget grammar); "origin" default/fallback. */
        public String getExit() {
            if (this.exit == null || this.exit.isJsonNull()) {
                return "origin";
            }
            return com.customdimensions.dimension.ExitTarget.canonicalise(this.exit, "origin");
        }

        /**
         * Anchor position as [x, y, z]: an explicit array wins; "spawn" (or
         * absent) uses the dimension's configured spawn, falling back to the
         * border centre (0, 64, 0). Y is a hint only — arrival surfaces via
         * findSurfaceY on the anchor column.
         */
        public int[] resolvePos(int[] dimensionSpawn) {
            if (this.pos != null && this.pos.isJsonArray() && this.pos.getAsJsonArray().size() == 3) {
                return new int[]{
                        this.pos.getAsJsonArray().get(0).getAsInt(),
                        this.pos.getAsJsonArray().get(1).getAsInt(),
                        this.pos.getAsJsonArray().get(2).getAsInt()};
            }
            return dimensionSpawn != null ? dimensionSpawn.clone() : new int[]{0, 64, 0};
        }
    }

    /**
     * Portal aura: themed environmental spread around a portal pair.
     * Every field optional; absent block = derived bi-directional leak
     * (sampled palettes, defaults below). All values are runtime-only —
     * boot-re-read like the rest of the portal block.
     */
    public static class Aura {
        /** Explicit off switch; absent/true = aura runs. */
        @SerializedName("enabled")
        public Boolean enabled;
        /** Blocks from the portal centre a pass may reach (default 8, max 32). */
        @SerializedName("radius")
        public Integer radius;
        /** Ticks between passes (default 40, min 10). */
        @SerializedName("interval")
        public Integer interval;
        /** Conversion attempts per pass (default 2, max 16). */
        @SerializedName("blocksPerPass")
        public Integer blocksPerPass;
        /** Lifetime conversions per portal side; -1 = endless creep (default 300). */
        @SerializedName("budget")
        public Integer budget;
        /** "source" | "target" | "both" (default). */
        @SerializedName("sides")
        public String sides;
        /** Terrain palette THIS dimension emits (overrides sampling). Empty = emit none. */
        @SerializedName("palette")
        public List<String> palette;
        @SerializedName("flora")
        public List<String> flora;
        /** ConfiguredFeature ids (e.g. "minecraft:oak"). */
        @SerializedName("trees")
        public List<String> trees;
        @SerializedName("fluids")
        public List<String> fluids;
        /** Explicit from (id or #tag) -> to conversions, outside the frame. */
        @SerializedName("conversions")
        public Map<String, String> conversions;
        /** Per-pass ignition chance on exposed surfaces (default 0). */
        @SerializedName("fireChance")
        public Double fireChance;
    }

    /**
     * The way shuts behind you: a countdown starts at the source portal's
     * first traversal, then the frame breaks per breakMode. The countdown
     * persists with the zone in portal_links.json (survives restarts).
     */
    public static class SingleUse {
        @SerializedName("enabled")
        public Boolean enabled;
        /** Seconds from first traversal to frame break (default 10). */
        @SerializedName("delaySeconds")
        public Integer delaySeconds;
        /** "destroy" | "decay" | "partial" (default "decay"). */
        @SerializedName("breakMode")
        public String breakMode;
        /** Frame block id -> decayed block id; merged over PortalDecay defaults. */
        @SerializedName("decayMap")
        public Map<String, String> decayMap;

        public int getDelaySeconds() {
            return this.delaySeconds != null && this.delaySeconds > 0 ? this.delaySeconds : 10;
        }

        public String getBreakMode() {
            return this.breakMode != null && !this.breakMode.isBlank() ? this.breakMode : "decay";
        }
    }

    /**
     * Mod-built, mod-maintained way home: a frame near dimension spawn,
     * registered as a permanent exit zone targeting the overworld, rebuilt
     * if broken. The counterweight to anchor/singleUse stranding.
     */
    public static class ExitPortal {
        @SerializedName("enabled")
        public Boolean enabled;
        /** [x, y, z], or "spawn" (default) for spawn + a deterministic offset. */
        @SerializedName("pos")
        public JsonElement pos;
        /**
         * Exit target: "bed" (default) | "worldSpawn" | "origin", or a
         * dimension-link descriptor {"dimension": "ns:slug", "arrival":
         * "anchor" | "spawn" | [x, y, z]} — exits can lead ANYWHERE.
         */
        @SerializedName("target")
        public JsonElement target;

        /** Canonical exit-mode string (ExitTarget grammar); "bed" default/fallback. */
        public String getTargetMode() {
            if (this.target == null || this.target.isJsonNull()) {
                return "bed";
            }
            return com.customdimensions.dimension.ExitTarget.canonicalise(this.target, "bed");
        }

        /** Explicit [x, y, z] when configured, null for the "spawn" default. */
        public int[] getExplicitPos() {
            if (this.pos != null && this.pos.isJsonArray() && this.pos.getAsJsonArray().size() == 3) {
                return new int[]{
                        this.pos.getAsJsonArray().get(0).getAsInt(),
                        this.pos.getAsJsonArray().get(1).getAsInt(),
                        this.pos.getAsJsonArray().get(2).getAsInt()};
            }
            return null;
        }
    }

    /**
     * One exit-condition rule ("exits" block). Triggers (the map key):
     * "void" (fell below minY), "death" (any death), "death:&lt;cause&gt;"
     * (damage-type id path, e.g. "death:lava", "death:mob:minecraft:zombie"),
     * "enderPearl" (pearl thrown), "fallFrom" (fell minHeight blocks and
     * survived). Boot-re-read like portal config — no world wipes.
     *
     * Targets: the shorthand strings "bed" | "worldSpawn" | "origin", or a
     * descriptor {"dimension": "ns:slug", "arrival": "anchor" | "spawn" |
     * [x, y, z]} linking to ANY dimension. Actions: "teleport" (intercept —
     * for death triggers this cancels the death), "respawnAt" (die normally,
     * respawn at the target), "kill" (explicit vanilla opt-in for "void").
     */
    public static class ExitRule {
        @SerializedName("action")
        public String action;
        @SerializedName("target")
        public JsonElement target;
        /** fallFrom only: minimum fall distance in blocks (default 100). */
        @SerializedName("minHeight")
        public Integer minHeight;

        public String getAction() {
            return this.action != null && !this.action.isBlank() ? this.action : "teleport";
        }

        public int getMinHeight() {
            return this.minHeight != null && this.minHeight > 0 ? this.minHeight : 100;
        }
    }

    /**
     * The pretty way home: adventure:exit_shrine jigsaw ruins scattered
     * through the dimension, each carrying a beacon-marked portal frame the
     * mod detects on chunk load and registers as an exit zone. The shrine
     * structure SET ships with a near-zero frequency, raised to full only
     * for dimensions that enable this block (DimensionStructures), so
     * shrines never leak into base worlds or unopted dims. The spawn
     * exitPortal remains the guarantee; shrines are scenery.
     */
    public static class ExitShrines {
        @SerializedName("enabled")
        public Boolean enabled;
        /** Exit target (ExitTarget grammar); default "bed", same as exitPortal. */
        @SerializedName("target")
        public JsonElement target;

        public String getTargetMode() {
            if (this.target == null || this.target.isJsonNull()) {
                return "bed";
            }
            return com.customdimensions.dimension.ExitTarget.canonicalise(this.target, "bed");
        }
    }

    public static class PortalSounds {
        @SerializedName("ignite")
        public String ignite;
        @SerializedName("enter")
        public String enter;
        @SerializedName("exit")
        public String exit;
    }

    public static class Environment {
        @SerializedName("skyColor")
        public String skyColor;
        @SerializedName("fogColor")
        public String fogColor;
        @SerializedName("ambientLight")
        public Double ambientLight;
        @SerializedName("fixedTime")
        public Long fixedTime;
        @SerializedName("hasCeiling")
        public Boolean hasCeiling;
        @SerializedName("hasSkylight")
        public Boolean hasSkylight;
        @SerializedName("ultraWarm")
        public Boolean ultraWarm;
        @SerializedName("natural")
        public Boolean natural;
        @SerializedName("bedWorks")
        public Boolean bedWorks;
        @SerializedName("respawnAnchorWorks")
        public Boolean respawnAnchorWorks;
        @SerializedName("piglinSafe")
        public Boolean piglinSafe;
        @SerializedName("hasRaids")
        public Boolean hasRaids;
        @SerializedName("minY")
        public Integer minY;
        @SerializedName("height")
        public Integer height;
        @SerializedName("logicalHeight")
        public Integer logicalHeight;
        // Vanilla dimension-type fields (Tier 1 of the Custom-world-settings
        // support matrix — see mods/.ideas/vanilla-custom-world-settings.md).
        @SerializedName("coordinateScale")
        public Double coordinateScale;
        @SerializedName("effects")
        public String effects;
        @SerializedName("infiniburn")
        public String infiniburn;
        // int (constant) or [min, max] (uniform), both within 0..15.
        @SerializedName("monsterSpawnLightLevel")
        public JsonElement monsterSpawnLightLevel;
        @SerializedName("monsterSpawnBlockLightLimit")
        public Integer monsterSpawnBlockLightLimit;
    }

    /**
     * Seed-roll scoring config. The mod ignores this at runtime — it exists
     * so per-dimension files are self-contained for the Python roller.
     * wants/shuns stay raw JSON: Phase 1 preserves the legacy band-name
     * shape verbatim (ranges land in Phase 6 under "structures").
     */
    public static class SeedRoll {
        /** True = the roller ignores this dimension entirely (no measurement, no scoring). */
        @SerializedName("skip")
        public Boolean skip;
        @SerializedName("mood")
        public String mood;
        @SerializedName("spawnFilter")
        public List<String> spawnFilter;
        @SerializedName("spawnRadius")
        public Integer spawnRadius;
        @SerializedName("water")
        public String water;
        @SerializedName("locateCap")
        public Integer locateCap;
        @SerializedName("terrain")
        public String terrain;
        @SerializedName("heightRange")
        public int[] heightRange;
        @SerializedName("family")
        public String family;
        @SerializedName("allowEndgameNearSpawn")
        public Boolean allowEndgameNearSpawn;
        @SerializedName("description")
        public String description;
        @SerializedName("wants")
        public JsonObject wants;
        @SerializedName("shuns")
        public JsonElement shuns;
    }
}
