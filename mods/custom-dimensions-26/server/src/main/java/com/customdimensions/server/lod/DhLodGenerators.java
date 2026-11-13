package com.customdimensions.server.lod;

import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.level.LevelIndex;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.level.LevelMaths;
import com.customdimensions.server.render.fast.LodSource;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Binds a {@link DhLodGenerator} to every server level Distant Horizons
 * loads (base worlds and lazy dimensions alike: the pack generates them all)
 * when DH loads it, and lets it go when DH unloads it. A level's reach, the
 * larger of its player and generation borders ({@link LevelMaths#lodReach}),
 * is read from the level index on its first coarse request, after start-up.
 * Only loaded when DH is installed.
 */
public final class DhLodGenerators {
    private static final Map<ResourceKey<Level>, Bound> BOUND = new ConcurrentHashMap<>();

    /** A level's generator and the wrapper DH knows the level by. */
    public record Bound(String level, IDhApiLevelWrapper wrapper, DhLodGenerator generator) {}

    private DhLodGenerators() {}

    static void register() {
        DhApi.events.bind(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(DhApiEventParam<EventParam> input) {
                bind(input.value.levelWrapper);
            }
        });
        DhApi.events.bind(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent() {
            @Override
            public void onLevelUnload(DhApiEventParam<EventParam> input) {
                if (input.value.levelWrapper.getWrappedMcObject() instanceof ServerLevel level) {
                    Bound gone = BOUND.remove(level.dimension());
                    if (gone != null) gone.generator().close();
                }
            }
        });
        CustomDimensions.LOG.info("Distant Horizons {}: LOD generation for every custom-dimensions level comes from its noise "
                + "(no structures, features or carvers)", DhApi.getModVersion());
    }

    static void bind(IDhApiLevelWrapper wrapper) {
        if (!(wrapper.getWrappedMcObject() instanceof ServerLevel level)) return;
        ResourceKey<Level> key = level.dimension();
        String name = key.identifier().toString();
        DhLodGenerator generator = new DhLodGenerator(LodSource.forLevel(level, name, () -> reach(key)), wrapper);
        DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(wrapper, generator);
        if (!result.success) {
            generator.close();
            CustomDimensions.LOG.warn("Distant Horizons refused the LOD generator for {}: {}", name, result.message);
            return;
        }
        Bound previous = BOUND.put(key, new Bound(name, wrapper, generator));
        if (previous != null) previous.generator().close();
        CustomDimensions.LOG.info("Distant Horizons LOD generator bound to {}", name);
    }

    /** The larger of a level's player and generation borders, else {@link LodSource#DEFAULT_REACH}. */
    static int reach(ResourceKey<Level> key) {
        Optional<LevelManager> manager = LevelManager.get();
        if (manager.isEmpty()) return LodSource.DEFAULT_REACH;
        LevelIndex index = manager.get().index();
        var definition = index.lazy().values().stream().filter(e -> e.key().equals(key)).findFirst().map(LevelIndex.Entry::definition)
                .or(() -> index.baseWorlds().stream().filter(b -> b.key().equals(key)).findFirst().map(LevelIndex.BaseWorld::definition))
                .orElse(null);
        Integer reach = LevelMaths.lodReach(definition == null ? null : definition.borders(), index.defaults());
        return reach == null ? LodSource.DEFAULT_REACH : reach;
    }

    /** The generator bound to {@code level}, if DH has loaded it. */
    public static Optional<Bound> bound(ResourceKey<Level> level) {
        return Optional.ofNullable(BOUND.get(level));
    }
}
