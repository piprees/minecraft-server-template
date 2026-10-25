package com.customdimensions.server.firewall;

import com.customdimensions.core.firewall.BiomeBaseline;
import com.customdimensions.server.level.PackInstaller;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/** The self-test's firewall section: the start-up audit ran and passed, switching off a set empties it from a level's structure state, and vanilla biomes carry nothing unadopted. */
public final class FirewallSelfTest {
    private FirewallSelfTest() {}

    public static List<String> run(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        lines.add(check("firewall audit ran at start-up and passed", () -> {
            WorldgenFirewall.Report r = WorldgenFirewall.report().orElseThrow(() -> new AssertionError("no report"));
            if (r.verdict().refused()) throw new AssertionError("refusals " + r.verdict().refusals());
            return r.verdict().allowed().size() + " mods allowed worldgen mixins, " + r.switchedOff().size() + " structure sets switched off, "
                    + r.bridges().size() + " bridges, " + r.removals().size() + " biome removals";
        }));
        lines.add(check("firewall: a switched-off structure set leaves the structure state, the others stay", () -> structureState(server)));
        lines.add(check("firewall: vanilla biomes hold only vanilla and bridged features", () -> biomes(server)));
        return lines;
    }

    private static String structureState(MinecraftServer server) {
        ServerLevel level = server.overworld();
        Registry<StructureSet> sets = server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
        ResourceKey<StructureSet> villages = ResourceKey.create(Registries.STRUCTURE_SET, Identifier.withDefaultNamespace("villages"));
        ResourceKey<StructureSet> outposts = ResourceKey.create(Registries.STRUCTURE_SET, Identifier.withDefaultNamespace("pillager_outposts"));
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        int before = state(level, generator, sets).possibleStructureSets().size();
        Set<ResourceKey<StructureSet>> previous = WorldgenFirewall.switchOff(Set.of(villages));
        List<Holder<StructureSet>> possible;
        try {
            possible = state(level, generator, sets).possibleStructureSets();
        } finally {
            WorldgenFirewall.switchOff(previous);
        }
        if (possible.stream().anyMatch(h -> h.is(villages))) throw new AssertionError("villages still in the structure state");
        if (possible.stream().noneMatch(h -> h.is(outposts))) throw new AssertionError("pillager_outposts missing");
        if (possible.size() != before - 1) throw new AssertionError(before + " sets before, " + possible.size() + " with villages off");
        if (level.getChunkSource().getGeneratorState().possibleStructureSets().stream().noneMatch(h -> h.is(villages))) {
            throw new AssertionError("the live overworld lost villages");
        }
        return before + " sets in the overworld's state, " + possible.size() + " with minecraft:villages switched off";
    }

    private static ChunkGeneratorStructureState state(ServerLevel level, ChunkGenerator generator, Registry<StructureSet> sets) {
        return ChunkGeneratorStructureState.createForNormal(level.getChunkSource().randomState(), level.getSeed(), new ChunkPos(0, 0),
                generator.getBiomeSource(), sets);
    }

    private static String biomes(MinecraftServer server) {
        Registry<Biome> registry = server.registryAccess().lookupOrThrow(Registries.BIOME);
        var vanilla = PackInstaller.vanilla().source();
        int checked = 0;
        List<String> extra = new ArrayList<>();
        Set<String> adopted = WorldgenFirewall.adoptedFeatures();
        for (var entry : registry.entrySet()) {
            String id = entry.getKey().identifier().toString();
            if (!id.startsWith("minecraft:")) continue;
            Optional<String> file = vanilla.read("data/minecraft/worldgen/biome/" + entry.getKey().identifier().getPath() + ".json");
            if (file.isEmpty()) continue;
            List<List<String>> current = new ArrayList<>();
            for (HolderSet<?> step : entry.getValue().getGenerationSettings().features()) {
                current.add(step.stream().map(Holder::unwrapKey).flatMap(Optional::stream).map(k -> k.identifier().toString()).toList());
            }
            BiomeBaseline.parse(file.get()).featureAdditions(current, adopted).forEach(a -> extra.add(id + " " + a.id()));
            checked++;
        }
        if (checked == 0) throw new AssertionError("no vanilla biome checked");
        if (!extra.isEmpty()) throw new AssertionError(extra.size() + " unadopted features, first " + extra.subList(0, Math.min(5, extra.size())));
        return checked + " vanilla biomes match vanilla's files";
    }

    private interface Check {
        String run() throws Exception;
    }

    private static String check(String name, Check check) {
        try {
            return "PASS " + name + ": " + check.run();
        } catch (Throwable t) {
            return "FAIL " + name + ": " + t;
        }
    }
}
