package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.mixin.SimpleRegistryAccessor;
import net.minecraft.registry.MutableRegistry;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntryInfo;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.dimension.DimensionType;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Custom DimensionType entries from the config's "environment" block
 * (v4 Phase 4): fixedTime, ceiling/skylight, ultraWarm, natural, bedWorks,
 * respawnAnchorWorks, piglinSafe, hasRaids, minY/height/logicalHeight and
 * ambientLight. Every unset field inherits from the dimension's base type
 * (the overworld/nether/end type it would have cloned anyway), so a
 * partial environment block is safe.
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
        OptionalLong fixedTime = env.fixedTime != null
                ? OptionalLong.of(env.fixedTime) : base.fixedTime();
        return new DimensionType(
                fixedTime,
                env.hasSkylight != null ? env.hasSkylight : base.hasSkyLight(),
                env.hasCeiling != null ? env.hasCeiling : base.hasCeiling(),
                env.ultraWarm != null ? env.ultraWarm : base.ultrawarm(),
                env.natural != null ? env.natural : base.natural(),
                base.coordinateScale(),
                env.bedWorks != null ? env.bedWorks : base.bedWorks(),
                env.respawnAnchorWorks != null ? env.respawnAnchorWorks : base.respawnAnchorWorks(),
                minY,
                height,
                logicalHeight,
                base.infiniburn(),
                base.effects(),
                env.ambientLight != null ? env.ambientLight.floatValue() : base.ambientLight(),
                new DimensionType.MonsterSettings(
                        env.piglinSafe != null ? env.piglinSafe : base.monsterSettings().piglinSafe(),
                        env.hasRaids != null ? env.hasRaids : base.monsterSettings().hasRaids(),
                        base.monsterSettings().monsterSpawnLightTest(),
                        base.monsterSettings().monsterSpawnBlockLightLimit()));
    }
}
