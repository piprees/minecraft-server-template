package com.customdimensions.server.firewall;

import com.customdimensions.core.firewall.Firewall;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

/**
 * Each mod's mixin configs and the classes their mixins target, read from the
 * mod's own files: its {@code fabric.mod.json} {@code mixins}, each config's
 * common and side lists, and each mixin class's {@code @Mixin} annotation.
 * Mixin's own registry is drained once configs are selected, so it holds
 * nothing by SERVER_STARTING.
 */
final class MixinTargets {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    private MixinTargets() {}

    static List<Firewall.MixinConfig> read() {
        EnvType side = FabricLoader.getInstance().getEnvironmentType();
        List<Firewall.MixinConfig> out = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            for (String name : configNames(mod, side)) {
                Optional<Path> file = mod.findPath(name);
                if (file.isEmpty()) continue;
                out.add(new Firewall.MixinConfig(id, name, targets(mod, file.get(), side)));
            }
        }
        return out;
    }

    private static List<String> configNames(ModContainer mod, EnvType side) {
        List<String> names = new ArrayList<>();
        Optional<JsonObject> meta = json(mod.findPath("fabric.mod.json"));
        if (meta.isEmpty() || !meta.get().has("mixins")) return names;
        for (JsonElement entry : meta.get().getAsJsonArray("mixins")) {
            if (entry.isJsonPrimitive()) {
                names.add(entry.getAsString());
            } else if (entry.isJsonObject() && entry.getAsJsonObject().has("config")) {
                JsonObject o = entry.getAsJsonObject();
                String environment = o.has("environment") ? o.get("environment").getAsString() : "*";
                if (environment.equals("*") || environment.equalsIgnoreCase(side.name())) names.add(o.get("config").getAsString());
            }
        }
        return names;
    }

    private static Set<String> targets(ModContainer mod, Path config, EnvType side) {
        Set<String> out = new TreeSet<>();
        Optional<JsonObject> json = json(Optional.of(config));
        if (json.isEmpty()) return out;
        String pkg = json.get().has("package") ? json.get().get("package").getAsString().replace('.', '/') + "/" : "";
        List<String> classes = new ArrayList<>();
        for (String list : List.of("mixins", side == EnvType.SERVER ? "server" : "client")) {
            if (json.get().has(list) && json.get().get(list).isJsonArray()) {
                for (JsonElement e : json.get().getAsJsonArray(list)) classes.add(e.getAsString());
            }
        }
        for (String mixin : classes) {
            Optional<Path> file = mod.findPath(pkg + mixin.replace('.', '/') + ".class");
            if (file.isEmpty()) continue;
            try {
                ClassNode node = new ClassNode();
                new ClassReader(Files.readAllBytes(file.get())).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                annotationTargets(node.invisibleAnnotations, out);
                annotationTargets(node.visibleAnnotations, out);
            } catch (IOException | RuntimeException e) {
                out.add("unreadable mixin " + pkg + mixin);
            }
        }
        return out;
    }

    private static void annotationTargets(List<AnnotationNode> annotations, Set<String> out) {
        if (annotations == null) return;
        for (AnnotationNode a : annotations) {
            if (!MIXIN.equals(a.desc) || a.values == null) continue;
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                Object value = a.values.get(i + 1);
                if (!(value instanceof List<?> list)) continue;
                for (Object v : list) {
                    if (v instanceof Type type) out.add(type.getClassName());
                    else if (v instanceof String s) out.add(s.replace('/', '.'));
                }
            }
        }
    }

    private static Optional<JsonObject> json(Optional<Path> file) {
        if (file.isEmpty()) return Optional.empty();
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file.get()));
            return root.isJsonObject() ? Optional.of(root.getAsJsonObject()) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
