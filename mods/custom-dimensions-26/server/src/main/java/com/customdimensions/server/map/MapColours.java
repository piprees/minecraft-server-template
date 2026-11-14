package com.customdimensions.server.map;

import com.customdimensions.core.render.map.BiomeTint;
import com.customdimensions.core.render.map.ExploredChunk;
import com.customdimensions.core.render.map.MapPalette;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The web map's colours for one level's registries: a block's
 * {@link MapPalette} entry by its id, or its map colour, opaque, when the
 * palette does not name it (a modded block); a biome's {@link BiomeTint}
 * from its climate (read through its network codec, which carries the
 * temperature and downfall the game keeps private) and special effects, plains for an id the registry does
 * not hold. Thread-safe; both are cached.
 */
public final class MapColours implements ExploredChunk.Resolver {
    private final MapPalette palette = MapPalette.vanilla();
    private final Registry<Biome> biomes;
    private final Map<String, MapPalette.Entry> blocks = new ConcurrentHashMap<>();
    private final Map<String, BiomeTint> tints = new ConcurrentHashMap<>();

    public MapColours(RegistryAccess registries) {
        this.biomes = registries.lookupOrThrow(Registries.BIOME);
    }

    public MapPalette palette() {
        return palette;
    }

    @Override
    public MapPalette.Entry block(String id) {
        return blocks.computeIfAbsent(id, k -> {
            MapPalette.Entry e = palette.entry(k);
            if (e != null) return e;
            Identifier key = Identifier.tryParse(k);
            Block block = key == null ? null : BuiltInRegistries.BLOCK.getValue(key);
            return MapPalette.Entry.opaque(block == null ? 0xFF00FF : block.defaultBlockState().getMapColor(null, null).col);
        });
    }

    public MapPalette.Entry block(BlockState state) {
        return block(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    @Override
    public BiomeTint biome(String id) {
        return tints.computeIfAbsent(id, k -> {
            Identifier key = Identifier.tryParse(k);
            Biome biome = key == null ? null : biomes.getValue(key);
            return biome == null ? BiomeTint.PLAINS : tint(palette, biome);
        });
    }

    public static BiomeTint tint(MapPalette palette, Biome biome) {
        JsonObject climate = Biome.NETWORK_CODEC.encodeStart(JsonOps.INSTANCE, biome).result().map(JsonElement::getAsJsonObject)
                .orElseGet(JsonObject::new);
        float temperature = climate.has("temperature") ? climate.get("temperature").getAsFloat() : biome.getBaseTemperature();
        float downfall = climate.has("downfall") ? climate.get("downfall").getAsFloat() : 0.4f;
        BiomeSpecialEffects fx = biome.getSpecialEffects();
        BiomeTint.GrassModifier modifier = switch (fx.grassColorModifier()) {
            case DARK_FOREST -> BiomeTint.GrassModifier.DARK_FOREST;
            case SWAMP -> BiomeTint.GrassModifier.SWAMP;
            default -> BiomeTint.GrassModifier.NONE;
        };
        return BiomeTint.of(palette, temperature, downfall, fx.grassColorOverride().orElse(null),
                fx.foliageColorOverride().orElse(null), fx.dryFoliageColorOverride().orElse(null), fx.waterColor(), modifier);
    }
}
