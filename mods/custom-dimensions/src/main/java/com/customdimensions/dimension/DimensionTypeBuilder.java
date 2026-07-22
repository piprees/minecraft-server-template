package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.mixin.SimpleRegistryAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.block.Block;
import net.minecraft.registry.MutableRegistry;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntryInfo;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.intprovider.ConstantIntProvider;
import net.minecraft.util.math.intprovider.IntProvider;
import net.minecraft.util.math.intprovider.UniformIntProvider;
import net.minecraft.world.dimension.DimensionType;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Custom DimensionType entries from the config's "environment" block
 * (v4 Phase 4): fixedTime, ceiling/skylight, ultraWarm, natural, bedWorks,
 * respawnAnchorWorks, piglinSafe, hasRaids, minY/height/logicalHeight and
 * ambientLight — plus the Tier 1 vanilla fields (see
 * mods/.ideas/vanilla-custom-world-settings.md): coordinateScale, effects
 * (one of the three vanilla dimension effects), infiniburn (block tag),
 * monsterSpawnLightLevel (int or [min,max]) and
 * monsterSpawnBlockLightLimit. Every unset field inherits from the
 * dimension's base type (the overworld/nether/end type it would have
 * cloned anyway), so a partial environment block is safe.
 *
 * coordinateScale here is the VANILLA travel scale (nether-portal maths,
 * map scaling). The mod's own portal system scales via portal.scale —
 * setting both double-applies; pick one per dimension.
 *
 * Each custom type registers once as {namespace}:{slug}_type in the
 * dynamic DIMENSION_TYPE registry (unfrozen/refrozen exactly like the
 * DimensionOptions registry). Types register during boot-time dimension
 * registration — before any client logs in — so the login registry sync
 * carries them.
 *
 * skyColor/fogColor are configurator metadata only: those are CLIENT
 * rendering concerns (dimension effects / biome tinting) that a server
 * mod cannot apply; they are ignored here with a one-line note.
 *
 * Failure policy (plan open question 2): a malformed environment block
 * logs a warning and falls back to the base type — a typo in one
 * dimension must never turn the boot into a crash loop.
 */
public final class DimensionTypeBuilder {

    private DimensionTypeBuilder() {
    }

    /** The type entry for a dimension: its custom type when "environment" is set, else the base. */
    public static RegistryEntry<DimensionType> typeEntryFor(MinecraftServer server,
                                                            DimensionConfig config,
                                                            RegistryEntry<DimensionType> base) {
        DimensionConfig.Environment env = config.getEnvironment();
        if (env == null) {
            return base;
        }
        Registry<DimensionType> registry = server.getCombinedDynamicRegistries()
                .getCombinedRegistryManager().get(RegistryKeys.DIMENSION_TYPE);
        Identifier id = Identifier.of(config.getNamespace(), config.getName() + "_type");
        RegistryKey<DimensionType> key = RegistryKey.of(RegistryKeys.DIMENSION_TYPE, id);
        Optional<? extends RegistryEntry<DimensionType>> existing = registry.getEntry(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        DimensionType built = build(env, base.value(), config.getName());
        if (built == null) {
            return base;
        }
        if (env.skyColor != null || env.fogColor != null) {
            MultiverseServer.LOGGER.info(
                    "Dimension {}: skyColor/fogColor are client-side effects and are not applied server-side",
                    config.getName());
        }
        MutableRegistry<DimensionType> mutable = (MutableRegistry<DimensionType>) registry;
        SimpleRegistryAccessor accessor = (SimpleRegistryAccessor) mutable;
        boolean wasFrozen = accessor.isFrozen();
        if (wasFrozen) {
            accessor.setFrozen(false);
        }
        try {
            RegistryEntry<DimensionType> entry = mutable.add(key, built, RegistryEntryInfo.DEFAULT);
            MultiverseServer.LOGGER.info("Registered dimension type: {} (from environment config)", id);
            return entry;
        } catch (Exception e) {
            MultiverseServer.LOGGER.error(
                    "Dimension {}: failed to register custom dimension type — using the base type",
                    config.getName(), e);
            return base;
        } finally {
            if (wasFrozen) {
                accessor.setFrozen(true);
            }
        }
    }

    /** Base type with the environment overrides applied; null when invalid. */
    static DimensionType build(DimensionConfig.Environment env, DimensionType base, String name) {
        int minY = env.minY != null ? env.minY : base.minY();
        int height = env.height != null ? env.height : base.height();
        int logicalHeight = env.logicalHeight != null ? env.logicalHeight : base.logicalHeight();
        if (minY % 16 != 0 || height % 16 != 0 || height < 16
                || logicalHeight > height || minY + height > 2032 || minY < -2032) {
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: invalid environment heights (minY={}, height={}, logicalHeight={}) "
                    + "— must be multiples of 16 within the +/-2032 build limit; using the base type",
                    name, minY, height, logicalHeight);
            return null;
        }
        double coordinateScale = base.coordinateScale();
        if (env.coordinateScale != null) {
            if (env.coordinateScale < 0.00001 || env.coordinateScale > 30000000.0) {
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: coordinateScale {} outside 0.00001..30000000 — using the base type",
                        name, env.coordinateScale);
                return null;
            }
            coordinateScale = env.coordinateScale;
        }

        Identifier effects = base.effects();
        if (env.effects != null) {
            Identifier parsed = Identifier.tryParse(env.effects);
            if (parsed == null || !VALID_EFFECTS.contains(parsed)) {
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: effects '{}' is not one of minecraft:overworld/the_nether/the_end "
                        + "(custom effects need a client mod) — using the base type",
                        name, env.effects);
                return null;
            }
            effects = parsed;
        }

        TagKey<Block> infiniburn = base.infiniburn();
        if (env.infiniburn != null) {
            String raw = env.infiniburn.startsWith("#") ? env.infiniburn.substring(1) : env.infiniburn;
            Identifier tagId = Identifier.tryParse(raw);
            if (tagId == null) {
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: infiniburn '{}' is not a valid block tag id — using the base type",
                        name, env.infiniburn);
                return null;
            }
            // Tag existence can't be validated at registration time — an
            // unknown tag simply matches nothing (fire burns out everywhere).
            infiniburn = TagKey.of(RegistryKeys.BLOCK, tagId);
        }

        IntProvider spawnLight = base.monsterSettings().monsterSpawnLightTest();
        if (env.monsterSpawnLightLevel != null) {
            int[] range = validateSpawnLight(env.monsterSpawnLightLevel, name);
            if (range == null) {
                return null;
            }
            spawnLight = range[0] == range[1]
                    ? ConstantIntProvider.create(range[0])
                    : UniformIntProvider.create(range[0], range[1]);
        }
        int spawnBlockLight = base.monsterSettings().monsterSpawnBlockLightLimit();
        if (env.monsterSpawnBlockLightLimit != null) {
            if (env.monsterSpawnBlockLightLimit < 0 || env.monsterSpawnBlockLightLimit > 15) {
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: monsterSpawnBlockLightLimit {} outside 0..15 — using the base type",
                        name, env.monsterSpawnBlockLightLimit);
                return null;
            }
            spawnBlockLight = env.monsterSpawnBlockLightLimit;
        }

        OptionalLong fixedTime = env.fixedTime != null
                ? OptionalLong.of(env.fixedTime) : base.fixedTime();
        return new DimensionType(
                fixedTime,
                env.hasSkylight != null ? env.hasSkylight : base.hasSkyLight(),
                env.hasCeiling != null ? env.hasCeiling : base.hasCeiling(),
                env.ultraWarm != null ? env.ultraWarm : base.ultrawarm(),
                env.natural != null ? env.natural : base.natural(),
                coordinateScale,
                env.bedWorks != null ? env.bedWorks : base.bedWorks(),
                env.respawnAnchorWorks != null ? env.respawnAnchorWorks : base.respawnAnchorWorks(),
                minY,
                height,
                logicalHeight,
                infiniburn,
                effects,
                env.ambientLight != null ? env.ambientLight.floatValue() : base.ambientLight(),
                new DimensionType.MonsterSettings(
                        env.piglinSafe != null ? env.piglinSafe : base.monsterSettings().piglinSafe(),
                        env.hasRaids != null ? env.hasRaids : base.monsterSettings().hasRaids(),
                        spawnLight,
                        spawnBlockLight));
    }

    private static final Set<Identifier> VALID_EFFECTS = Set.of(
            Identifier.of("minecraft", "overworld"),
            Identifier.of("minecraft", "the_nether"),
            Identifier.of("minecraft", "the_end"));

    /** int -> {v,v}, [min,max] -> {min,max}; bounded 0..15. Null = invalid
     * (logged). Pure JSON validation — provider construction stays in
     * build() because IntProvider class init needs the game bootstrap. */
    static int[] validateSpawnLight(JsonElement raw, String name) {
        try {
            if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isNumber()) {
                int v = raw.getAsInt();
                if (v >= 0 && v <= 15) {
                    return new int[] {v, v};
                }
            } else if (raw.isJsonArray()) {
                JsonArray arr = raw.getAsJsonArray();
                if (arr.size() == 2) {
                    int min = arr.get(0).getAsInt();
                    int max = arr.get(1).getAsInt();
                    if (min >= 0 && max <= 15 && min <= max) {
                        return new int[] {min, max};
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the warn below
        }
        MultiverseServer.LOGGER.warn(
                "Dimension {}: monsterSpawnLightLevel must be an int or [min,max] within 0..15 "
                + "(got {}) — using the base type", name, raw);
        return null;
    }
}
