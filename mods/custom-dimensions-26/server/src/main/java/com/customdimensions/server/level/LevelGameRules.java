package com.customdimensions.server.level;

import com.customdimensions.core.definition.DefinitionReader;
import com.customdimensions.core.definition.DefinitionSettings;
import com.customdimensions.core.definition.GameRuleValues;
import com.customdimensions.server.CustomDimensions;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleMap;
import net.minecraft.world.level.gamerules.GameRuleTypeVisitor;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * Game rules per dimension (decisions.md RB-38). Every level answers a rule
 * with its definition's {@code gameRules} value, else {@code settings.json}'s
 * {@code defaults.gameRules} value, else the server-wide store's.
 * {@code ServerLevelGameRulesMixin} hands a level with any value of its own a
 * {@link View} from {@code ServerLevel.getGameRules()}, the read almost every
 * rule passes through; Fantasy's lazy levels fall through to it, since they
 * carry no rules of their own. Writes through a view reach the server-wide
 * store, so {@code /gamerule <rule> <value>} keeps its server-wide meaning
 * and the dimension's own value stays in force.
 * <p>
 * The values are read when the server starts, from every definition, before
 * any level exists; a rule of another mod's namespace is checked then against
 * the live registry, and a bad one stops the start.
 */
public final class LevelGameRules {
    private static volatile Map<ResourceKey<Level>, Map<GameRule<?>, Object>> overrides = Map.of();
    private static final Map<ResourceKey<Level>, View> VIEWS = new java.util.concurrent.ConcurrentHashMap<>();

    private LevelGameRules() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(LevelGameRules::start);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            overrides = Map.of();
            VIEWS.clear();
        });
    }

    /** The view {@code key}'s level answers rules from, over {@code store}; null when the level has no values of its own. Read on every {@code getGameRules()}. */
    public static GameRules viewOrNull(ResourceKey<Level> key, GameRules store) {
        Map<GameRule<?>, Object> own = overrides.get(key);
        if (own == null) return null;
        View view = VIEWS.get(key);
        if (view == null || view.parent != store) {
            view = new View(store, own);
            VIEWS.put(key, view);
        }
        return view;
    }

    /**
     * A level that holds its own {@code advance_weather} has weather of its own,
     * saved in its own data storage: first a copy of the server's, then advanced
     * (or held) by its own rule. Every other level shares the server's; null for them.
     */
    public static WeatherData weatherOrNull(ServerLevel level, WeatherData shared) {
        if (!ownsWeather(ownOf(level.dimension()))) return null;
        SavedDataStorage storage = level.getDataStorage();
        WeatherData own = storage.get(WeatherData.TYPE);
        if (own == null) {
            own = copy(shared);
            own.setDirty();
            storage.set(WeatherData.TYPE, own);
        }
        return own;
    }

    /** Whether a level holding {@code own} has weather of its own. */
    static boolean ownsWeather(Map<GameRule<?>, Object> own) {
        return own != null && own.containsKey(GameRules.ADVANCE_WEATHER);
    }

    static WeatherData copy(WeatherData w) {
        return new WeatherData(w.getClearWeatherTime(), w.getRainTime(), w.getThunderTime(), w.isRaining(), w.isThundering());
    }

    /** The values {@code key}'s level holds of its own, or null. */
    static Map<GameRule<?>, Object> ownOf(ResourceKey<Level> key) {
        return overrides.get(key);
    }

    /** The self-test's hook: {@code key}'s level holds {@code own}, or no values of its own when null. */
    static synchronized void selfTestValues(ResourceKey<Level> key, Map<GameRule<?>, Object> own) {
        Map<ResourceKey<Level>, Map<GameRule<?>, Object>> next = new HashMap<>(overrides);
        if (own == null) next.remove(key);
        else next.put(key, Map.copyOf(own));
        overrides = Map.copyOf(next);
        VIEWS.remove(key);
    }

    /** The level's own values by rule id, for {@code customdim info}. */
    public static Map<String, String> ownValues(ResourceKey<Level> key) {
        Map<String, String> out = new java.util.TreeMap<>();
        overrides.getOrDefault(key, Map.of()).forEach((rule, value) -> out.put(rule.id(), String.valueOf(value)));
        return out;
    }

    private static void start(MinecraftServer server) {
        Optional<Path> dir = LevelIndex.definitionsDir();
        if (dir.isEmpty()) {
            overrides = Map.of();
            return;
        }
        DefinitionSettings settings = DefinitionReader.readSettings(dir.get().resolve("settings.json"));
        String namespace = settings.namespace() != null ? settings.namespace() : LevelIndex.DEFAULT_NAMESPACE;
        GameRuleValues defaults = GameRuleValues.fromDefaults(settings.defaults(), "settings.json");
        Map<String, String> baseKeys = LevelIndex.baseWorldKeys(namespace);
        List<String> problems = new ArrayList<>();
        Map<ResourceKey<Level>, Map<GameRule<?>, Object>> out = new HashMap<>();
        int levels = 0;
        try (Stream<Path> files = Files.list(dir.get())) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")
                    && !f.getFileName().toString().equals("settings.json")).sorted().toList()) {
                String slug = file.getFileName().toString().replaceFirst("\\.json$", "");
                GameRuleValues own;
                try {
                    own = DefinitionReader.read(file).gameRules();
                } catch (RuntimeException e) {
                    continue; // LevelIndex reports a definition that does not read
                }
                GameRuleValues resolved = GameRuleValues.resolve(defaults, own);
                if (resolved.isEmpty()) continue;
                Map<GameRule<?>, Object> rules = typed(slug, resolved, problems);
                ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                        Identifier.parse(baseKeys.getOrDefault(slug, namespace + ":" + slug)));
                out.put(key, Map.copyOf(rules));
                levels++;
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("game rules: " + String.join("; ", problems));
        }
        overrides = Map.copyOf(out);
        VIEWS.clear();
        CustomDimensions.LOG.info("game rules: {} level(s) hold values of their own{}", levels,
                defaults.isEmpty() ? "" : ", defaults " + defaults.values());
    }

    /** {@code values} as the live registry's rules, each value parsed by the rule's own command argument. */
    static Map<GameRule<?>, Object> typed(String slug, GameRuleValues values, List<String> problems) {
        Map<GameRule<?>, Object> out = new LinkedHashMap<>();
        values.values().forEach((id, value) -> {
            GameRule<?> rule = BuiltInRegistries.GAME_RULE.getValue(Identifier.parse(id));
            if (rule == null) {
                problems.add(slug + ": gameRules." + id + " is not a game rule of this server");
                return;
            }
            if (GameRuleValues.SERVER_WIDE.contains(id)) {
                problems.add(slug + ": gameRules." + id + " is server-wide; " + GameRuleValues.INSTEAD);
                return;
            }
            try {
                Object parsed = rule.argument().parse(new StringReader(String.valueOf(value)));
                if (!rule.valueClass().isInstance(parsed)) throw new IllegalArgumentException("takes " + rule.valueClass().getSimpleName());
                out.put(rule, parsed);
            } catch (CommandSyntaxException | RuntimeException e) {
                problems.add(slug + ": gameRules." + id + " = " + value + ": " + e.getMessage());
            }
        });
        return out;
    }

    /**
     * A level's rules: its own values over the server-wide store. Reads answer
     * the level's value where it holds one; writes, and every other operation,
     * act on the store.
     */
    public static final class View extends GameRules {
        private final GameRules parent;
        private final Map<GameRule<?>, Object> own;

        View(GameRules parent, Map<GameRule<?>, Object> own) {
            super(List.of());
            this.parent = parent;
            this.own = own;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(GameRule<T> rule) {
            Object value = own.get(rule);
            return value != null ? (T) value : parent.get(rule);
        }

        @Override
        public <T> void set(GameRule<T> rule, T value, MinecraftServer server) {
            parent.set(rule, value, server);
        }

        @Override
        public Stream<GameRule<?>> availableRules() {
            return parent.availableRules();
        }

        @Override
        public GameRules copy(FeatureFlagSet flags) {
            GameRules copy = parent.copy(flags);
            own.forEach((rule, value) -> setUnchecked(copy, rule, value));
            return copy;
        }

        @SuppressWarnings("unchecked")
        private static <T> void setUnchecked(GameRules rules, GameRule<T> rule, Object value) {
            rules.set(rule, (T) value, null);
        }

        @Override
        public void setAll(GameRules other, MinecraftServer server) {
            parent.setAll(other, server);
        }

        @Override
        public void setAll(GameRuleMap other, MinecraftServer server) {
            parent.setAll(other, server);
        }

        @Override
        public void visitGameRuleTypes(GameRuleTypeVisitor visitor) {
            parent.visitGameRuleTypes(visitor);
        }

        /** Whether the level holds its own value for {@code rule}. */
        public boolean holds(GameRule<?> rule) {
            return own.containsKey(rule);
        }
    }
}
