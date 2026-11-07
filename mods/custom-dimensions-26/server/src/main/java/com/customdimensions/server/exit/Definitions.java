package com.customdimensions.server.exit;

import com.customdimensions.core.definition.Definition;
import com.customdimensions.server.level.LevelIndex;
import com.customdimensions.server.level.LevelManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** The v2 definition behind a level, and its slug, from the running {@link LevelIndex}. */
final class Definitions {
    record Of(String slug, Definition definition) {
    }

    private static LevelIndex indexed;
    private static Map<ResourceKey<Level>, Of> byKey = Map.of();

    private Definitions() {
    }

    static synchronized Of of(ResourceKey<Level> key) {
        Optional<LevelIndex> index = LevelManager.get().map(LevelManager::index);
        if (index.isEmpty()) {
            return null;
        }
        if (index.get() != indexed) {
            Map<ResourceKey<Level>, Of> map = new HashMap<>();
            index.get().baseWorlds().forEach(w -> {
                if (w.definition() != null) map.put(w.key(), new Of(w.slug(), w.definition()));
            });
            index.get().lazy().values().forEach(e -> {
                if (e.definition() != null) map.put(e.key(), new Of(e.slug(), e.definition()));
            });
            byKey = map;
            indexed = index.get();
        }
        return byKey.get(key);
    }

    static String namespace() {
        return LevelManager.get().map(m -> m.index().namespace()).orElse(LevelIndex.DEFAULT_NAMESPACE);
    }
}
