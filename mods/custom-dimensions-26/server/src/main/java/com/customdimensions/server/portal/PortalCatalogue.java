package com.customdimensions.server.portal;

import com.customdimensions.core.Id;
import com.customdimensions.core.config.DimensionConfig;
import com.customdimensions.core.config.DimensionConfigLoader;
import com.customdimensions.core.config.MultiverseConfig;
import com.customdimensions.core.config.PortalDefinition;
import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.definition.DefinitionReader;
import com.customdimensions.core.definition.DefinitionSettings;
import com.customdimensions.core.portal.FrameForms;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.level.LevelIndex;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;

/**
 * The portal side of the v2 definitions: each definition's {@code portals},
 * {@code spawn} and {@code exitPortal} read into a {@link DimensionConfig},
 * the shape core's {@link MultiverseConfig} answers portal questions from
 * (igniter candidates, presentation, scale, the default frames).
 */
public final class PortalCatalogue {
    private static final Gson GSON = new Gson();

    private PortalCatalogue() {
    }

    public static void load(LevelIndex index) {
        DimensionConfigLoader.Settings settings = new DimensionConfigLoader.Settings();
        settings.namespace = index.namespace();
        Optional<Path> dir = LevelIndex.definitionsDir();
        if (dir.isPresent()) {
            try {
                DefinitionSettings read = DefinitionReader.readSettings(dir.get().resolve("settings.json"));
                JsonObject frames = read.frames();
                if (frames != null) {
                    if (frames.has("overworld")) settings.frameOverworld = frames.get("overworld").getAsString();
                    if (frames.has("nether")) settings.frameNether = frames.get("nether").getAsString();
                    if (frames.has("end")) settings.frameEnd = frames.get("end").getAsString();
                }
            } catch (RuntimeException e) {
                CustomDimensions.LOG.warn("portal catalogue: settings.json frames not read: {}", e.getMessage());
            }
        }
        Map<String, DimensionConfig> configs = new LinkedHashMap<>();
        index.baseWorlds().forEach(w -> put(configs, w.slug(), w.definition(), index.namespace()));
        index.lazy().values().forEach(e -> put(configs, e.slug(), e.definition(), index.namespace()));
        MultiverseConfig.getInstance().applyLoadResult(new DimensionConfigLoader.LoadResult(settings, configs));
        FrameForms.setTagMembership(PortalCatalogue::isInTag);
        CustomDimensions.LOG.info("portal catalogue: {} portal definitions from {} dimensions",
                MultiverseConfig.getInstance().getPortals().size(), configs.size());
    }

    private static void put(Map<String, DimensionConfig> configs, String slug, Definition definition, String namespace) {
        if (definition == null) {
            return;
        }
        JsonObject json = new JsonObject();
        if (definition.spawn() != null) {
            JsonArray spawn = new JsonArray();
            spawn.add(definition.spawn().x());
            spawn.add(definition.spawn().y());
            spawn.add(definition.spawn().z());
            json.add("spawn", spawn);
        }
        JsonArray portals = new JsonArray();
        definition.portals().forEach(portals::add);
        json.add("portal", portals);
        if (definition.exitPortal() != null) {
            json.add("exitPortal", definition.exitPortal());
        }
        DimensionConfig config = GSON.fromJson(json, DimensionConfig.class);
        config.setName(slug);
        config.setNamespace(namespace);
        configs.put(slug, config);
    }

    private static boolean isInTag(String blockId, String tagId) {
        Identifier block = Identifier.tryParse(blockId);
        Identifier tag = Identifier.tryParse(tagId);
        if (block == null || tag == null) {
            return false;
        }
        return BuiltInRegistries.BLOCK.getOptional(block)
                .map(b -> b.builtInRegistryHolder().is(TagKey.create(Registries.BLOCK, tag)))
                .orElse(false);
    }

    /** The configuration of the dimension behind {@code world}, or null. */
    public static DimensionConfig dimension(ResourceKey<Level> world) {
        return MultiverseConfig.getInstance().getDimension(Id.parse(world.identifier().toString()));
    }

    /**
     * One side's coordinate scale. A definition is the authority for the
     * dimension it leads to; anything else asks the configuration.
     */
    public static double scaleOf(ResourceKey<Level> world, PortalDefinition def) {
        String id = world.identifier().toString();
        if (def != null && id.equals(def.getTargetDimension())) {
            return def.getScale();
        }
        return MultiverseConfig.getInstance().getScaleFor(Id.parse(id));
    }
}
