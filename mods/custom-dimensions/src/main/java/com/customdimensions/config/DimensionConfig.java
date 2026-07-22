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
    @SerializedName("biomes")
    private List<String> biomes;
    /** Legacy comma-separated biome list (multiverse_config.json). */
    @SerializedName("biome")
    private String biome;
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

    /**
     * Biome list as the comma-separated string the generator plumbing
     * expects. New-format "biomes" arrays win; the legacy "biome" string
     * passes through. Null when neither is set.
     */
    public String getBiome() {
        if (this.biomes != null && !this.biomes.isEmpty()) {
            return String.join(",", this.biomes);
        }
        return this.biome != null && !this.biome.isBlank() ? this.biome : null;
    }

    public List<String> getBiomes() {
        return this.biomes;
    }

    public void setBiome(String biome) {
        this.biome = biome;
        this.biomes = null;
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
        return this.portal != null && this.portal.frameBlock != null;
    }

    /**
     * Runtime portal view for PortalHelper and the ignition/travel mixins.
     * The portal id is the dimension slug; the target is this dimension.
     */
    public PortalDefinition toPortalDefinition() {
        if (this.portal == null) {
            return null;
        }
        PortalDefinition def = new PortalDefinition(
                this.name,
                this.portal.frameBlock,
                this.portal.igniterItem != null ? this.portal.igniterItem : "",
                this.getDimensionId(),
                this.portal.color,
                this.portal.lightLevel != null ? this.portal.lightLevel : 0);
        def.setScale(this.portal.scale != null ? this.portal.scale : 1.0);
        def.setCooldown(this.portal.cooldown != null ? this.portal.cooldown : 40);
        def.setParticleType(this.portal.particleType);
        def.setIgniteSound(this.portal.getIgniteSound());
        def.setEnterSound(this.portal.getEnterSound());
        def.setExitSound(this.portal.getExitSound());
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

    public static class Structures {
        @SerializedName("wants")
        public Map<String, StructureWant> wants;
        @SerializedName("shuns")
        public Map<String, StructureShun> shuns;
        @SerializedName("endgame")
        public EndgameConfig endgame;
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
        @SerializedName("frameBlock")
        public String frameBlock;
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
