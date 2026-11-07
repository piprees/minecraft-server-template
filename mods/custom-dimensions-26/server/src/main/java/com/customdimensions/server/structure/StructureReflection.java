package com.customdimensions.server.structure;

import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.AbstractSpreadingStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * The private members structure control reads: a structure state's biome
 * source, ring seed and constructor; a spreading placement's frequency, salt,
 * reduction method and exclusion zone; a structure manager's level. 26.x runs
 * with Mojang's names, so these resolve by name; {@link #check()} is the
 * self-test's proof that they do.
 */
public final class StructureReflection {

    private static final Field BIOME_SOURCE = field(ChunkGeneratorStructureState.class, "biomeSource");
    private static final Field RINGS_SEED = field(ChunkGeneratorStructureState.class, "concentricRingsSeed");
    private static final Constructor<ChunkGeneratorStructureState> STATE = constructor();
    private static final Method FREQUENCY = method("frequency");
    private static final Method SALT = method("salt");
    private static final Method REDUCTION = method("frequencyReductionMethod");
    private static final Method EXCLUSION = method("exclusionZone");
    private static final Field MANAGER_LEVEL = field(StructureManager.class, "level");

    private StructureReflection() {
    }

    /** The state with another set list, everything else as it was. */
    static ChunkGeneratorStructureState withSets(ChunkGeneratorStructureState state, List<Holder<StructureSet>> sets) {
        try {
            return STATE.newInstance(state.randomState(), (BiomeSource) BIOME_SOURCE.get(state), state.getLevelSeed(),
                    state.getDimensionOrigin(), RINGS_SEED.getLong(state), List.copyOf(sets));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot rebuild a structure state", e);
        }
    }

    /** A random-spread placement with another spacing, separation and frequency; salt, offset and zone kept. */
    @SuppressWarnings("unchecked")
    static RandomSpreadStructurePlacement respaced(RandomSpreadStructurePlacement p, int spacing, int separation, float frequency) {
        try {
            return new RandomSpreadStructurePlacement(p.locateOffset(),
                    (AbstractSpreadingStructurePlacement.FrequencyReductionMethod) REDUCTION.invoke(p), frequency,
                    (int) SALT.invoke(p), (Optional<AbstractSpreadingStructurePlacement.ExclusionZone>) EXCLUSION.invoke(p),
                    spacing, separation, p.spreadType());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot copy a random-spread placement", e);
        }
    }

    static float frequency(AbstractSpreadingStructurePlacement p) {
        try {
            return (float) FREQUENCY.invoke(p);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static LevelAccessor level(StructureManager manager) {
        try {
            return (LevelAccessor) MANAGER_LEVEL.get(manager);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every member resolved; the self-test's line. */
    public static boolean check() {
        return BIOME_SOURCE != null && RINGS_SEED != null && STATE != null && FREQUENCY != null && SALT != null
                && REDUCTION != null && EXCLUSION != null && MANAGER_LEVEL != null;
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(owner.getName() + "." + name + " is missing", e);
        }
    }

    private static Method method(String name) {
        try {
            Method m = AbstractSpreadingStructurePlacement.class.getDeclaredMethod(name);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("AbstractSpreadingStructurePlacement." + name + " is missing", e);
        }
    }

    private static Constructor<ChunkGeneratorStructureState> constructor() {
        try {
            Constructor<ChunkGeneratorStructureState> c = ChunkGeneratorStructureState.class.getDeclaredConstructor(
                    RandomState.class, BiomeSource.class, long.class, ChunkPos.class, long.class, List.class);
            c.setAccessible(true);
            return c;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("the ChunkGeneratorStructureState constructor is missing", e);
        }
    }
}
