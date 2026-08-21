package com.customdimensions.dimension;

import com.customdimensions.config.DimensionConfig;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DimensionManagerTest {

    static final class Seedable {
        long lastSeed = Long.MIN_VALUE;
        final String value;

        Seedable(String value) {
            this.value = value;
        }

        public Seedable withSeed(long seed) {
            this.lastSeed = seed;
            return this;
        }
    }

    static final class NotSeedable {
        final String value;

        NotSeedable(String value) {
            this.value = value;
        }
    }

    private static DimensionConfig config(String namespace, String name) {
        DimensionConfig def = new DimensionConfig();
        def.setNamespace(namespace);
        def.setName(name);
        return def;
    }

    @Test
    void singletonInstanceIsConsistent() {
        DimensionManager a = DimensionManager.getInstance();
        DimensionManager b = DimensionManager.getInstance();
        assertSame(a, b);
    }

    @Test
    void dimensionExistsReturnsFalseForUnknown() {
        assertFalse(DimensionManager.getInstance().dimensionExists("nonexistent_dimension_xyz"));
    }

    @Test
    void getServerReturnsNullBeforeInit() {
        // Other tests may already have initialised a server, so this only
        // checks that a random dimension name is never reported as existing.
        DimensionManager dm = DimensionManager.getInstance();
        assertFalse(dm.dimensionExists("random_test_" + System.nanoTime()));
    }

    @Test
    void invokeWithSeedReflectivelyReturnsSeededObjectWhenMethodExists() {
        Seedable seedable = new Seedable("ok");

        Object result = DimensionManager.invokeWithSeedReflectively(seedable, 22222L);

        assertSame(seedable, result);
        assertEquals(22222L, seedable.lastSeed);
    }

    @Test
    void invokeWithSeedReflectivelyReturnsNullWhenMethodMissing() {
        NotSeedable notSeedable = new NotSeedable("nope");

        Object result = DimensionManager.invokeWithSeedReflectively(notSeedable, 11111L);

        assertNull(result);
    }

    @Test
    void invokeWithSeedReflectivelyReturnsNullWhenInputNull() {
        Object result = DimensionManager.invokeWithSeedReflectively(null, 42L);
        assertNull(result);
    }

    // generatorSettingsId is the id a runtime-built ChunkGeneratorSettings
    // variant (settingsOverrides, surface composition) registers under, so
    // registerGeneratorSettings can hand back a REFERENCE entry instead of
    // RegistryEntry.of's DIRECT entry. The registration itself needs a live
    // dynamic registry and is exercised by the live boot oracle, not unit
    // tests — same limitation as DimensionTypeBuilderTest.

    @Test
    void idIsDeterministicAcrossRepeatedCalls() {
        // The seed roller measures one dimension across many candidate
        // seeds — each call must land on the SAME id, or a registered-once
        // check would never find its own earlier registration and the
        // registry would grow one entry per measurement instead of reusing
        // one.
        DimensionConfig def = config("adventure", "the_dustbowl");
        Identifier first = DimensionManager.generatorSettingsId(def, "_settings_overrides");
        Identifier second = DimensionManager.generatorSettingsId(def, "_settings_overrides");
        assertEquals(first, second);
    }

    @Test
    void settingsOverridesAndSurfaceCompositionNeverShareAnId() {
        // A dimension can need both transforms — settingsOverrides, then
        // surface composition on top of it. If the two suffixes ever
        // collided, the second registerGeneratorSettings call would find
        // the first's id already registered and hand back ITS entry: the
        // composed surface rule would silently never take effect.
        DimensionConfig def = config("adventure", "the_frozen_strait");
        Identifier overrides = DimensionManager.generatorSettingsId(def, "_settings_overrides");
        Identifier composed = DimensionManager.generatorSettingsId(def, "_surface_composed");
        assertNotEquals(overrides, composed);
    }

    @Test
    void idIsScopedByNamespaceAndByName() {
        // Two dimensions must never collide: the same slug in two
        // namespaces (a platform dimension and a same-named BRAND_SLUG
        // consumer dimension), or two different slugs in one namespace.
        Identifier platform = DimensionManager.generatorSettingsId(
                config("adventure", "the_gauntlet"), "_settings_overrides");
        Identifier consumerNamesake = DimensionManager.generatorSettingsId(
                config("elfydd", "the_gauntlet"), "_settings_overrides");
        Identifier sibling = DimensionManager.generatorSettingsId(
                config("adventure", "the_dustbowl"), "_settings_overrides");

        assertNotEquals(platform, consumerNamesake, "namespace must be part of the id");
        assertNotEquals(platform, sibling, "name must be part of the id");
    }
}
