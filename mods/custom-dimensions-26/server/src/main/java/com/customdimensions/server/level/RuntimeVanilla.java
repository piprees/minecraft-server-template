package com.customdimensions.server.level;

import com.customdimensions.compiler.datapack.VanillaSource;
import com.google.gson.JsonElement;
import com.google.gson.stream.JsonWriter;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.DataProvider;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;

/**
 * Vanilla's files from the game the server runs: {@code data/minecraft/**}
 * from the Minecraft jar's root, and each multi-noise parameter list encoded
 * from {@link MultiNoiseBiomeSourceParameterList#knownPresets()} with the codec
 * the data generator's biome-parameters report uses and written in its key
 * order, so {@code reports/biome_parameters/minecraft/<list>.json} reads as
 * that report does.
 */
public final class RuntimeVanilla implements VanillaSource {
    private static final String PROBE = "data/minecraft/dimension_type/overworld.json";
    private static final String REPORTS = "reports/biome_parameters/";
    private static final Codec<Climate.ParameterList<ResourceKey<Biome>>> REPORT_CODEC =
            Climate.ParameterList.codec(ResourceKey.codec(Registries.BIOME).fieldOf("biome")).fieldOf("biomes").codec();

    private final Path root;
    private final Map<String, String> reports = new TreeMap<>();

    private RuntimeVanilla(Path root) {
        this.root = root;
        MultiNoiseBiomeSourceParameterList.knownPresets().forEach((preset, list) -> reports.put(
                REPORTS + preset.id().getNamespace() + "/" + preset.id().getPath() + ".json",
                stable(REPORT_CODEC.encodeStart(JsonOps.INSTANCE, list).getOrThrow())));
    }

    /** The JSON as the data generator writes a report: keys in {@link DataProvider#KEY_COMPARATOR} order. */
    private static String stable(JsonElement json) {
        StringWriter out = new StringWriter();
        try (JsonWriter writer = new JsonWriter(out)) {
            writer.setIndent("  ");
            GsonHelper.writeValue(writer, json, DataProvider.KEY_COMPARATOR);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    /** The Minecraft mod container's root that holds {@code data/minecraft/}. */
    public static RuntimeVanilla load() {
        List<Path> roots = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getRootPaths();
        for (Path root : roots) {
            if (Files.isRegularFile(root.resolve(PROBE))) return new RuntimeVanilla(root);
        }
        throw new IllegalStateException("no root of the minecraft mod holds " + PROBE + ": " + roots);
    }

    /** The report paths this source encodes, sorted. */
    public List<String> reportPaths() {
        return List.copyOf(reports.keySet());
    }

    @Override
    public Optional<String> read(String path) {
        if (path.startsWith(REPORTS)) return Optional.ofNullable(reports.get(path));
        Path file = root.resolve(path);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<String> list(String folder) {
        return VanillaSource.names(root.resolve(folder));
    }

    @Override
    public String describe() {
        return "the running game (" + root.toUri() + ")";
    }
}
