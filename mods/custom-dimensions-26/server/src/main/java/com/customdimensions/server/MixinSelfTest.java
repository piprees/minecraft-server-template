package com.customdimensions.server;

import com.customdimensions.server.companion.CompanionSelfTest;
import com.customdimensions.server.firewall.FirewallSelfTest;
import com.customdimensions.server.level.BasinFluidPicker;
import com.customdimensions.server.mixin.ChunkGeneratorAccessor;
import com.customdimensions.server.mixin.NoiseBasedChunkGeneratorAccessor;
import com.customdimensions.server.structure.StructureRuntime;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One line per mixin whose effect shows without generating terrain, each
 * naming its mixin. The structures section reads {@code StructureBiomeBypassMixin}
 * and {@code BeardifierAdaptationMixin}, the zones section
 * {@code NoiseBasedAquiferMixin} and {@code DryFluidFeatureMixin}.
 */
final class MixinSelfTest {

    private MixinSelfTest() {
    }

    static List<String> run(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        lines.add(check("ChunkBlockChangedMixin", "a block change reaches the companion feed", () -> CompanionSelfTest.blockChangedProbe(server)));
        lines.add(check("ChunkGeneratorAccessor", "the overworld generator's biome feature getter reads and writes", () -> generationSettings(server)));
        lines.add(check("NoiseBasedChunkGeneratorAccessor", "the overworld generator's fluid rule reads and writes", () -> fluidRule(server)));
        lines.add(check("StructureStateMixin", "a switched-off structure set leaves a new structure state", () -> FirewallSelfTest.structureState(server)));
        lines.add(check("ChunkMapStructuresMixin", "the chunk map builds the overworld's structure state with its control", MixinSelfTest::structureControl));
        lines.add(check("PrepareSpawnTaskMixin", "a login saved in a level that is not open is placed at the world spawn", () -> savedLevel(server)));
        lines.add(check("DisabledAquiferMixin", "a disabled aquifer schedules basin fluid near the rim to flow", () -> basinFlow(server)));
        return lines;
    }

    private static String savedLevel(MinecraftServer server) {
        var who = new net.minecraft.server.players.NameAndId(java.util.UUID.nameUUIDFromBytes("selftest".getBytes()), "SelfTest");
        // Constructing the task links and verifies the class the handler is merged into.
        new net.minecraft.server.network.config.PrepareSpawnTask(server, who);
        if (!com.customdimensions.server.level.SavedLevel.mixinApplied()) throw new AssertionError("the spawn task has no merged handler");
        var pos = java.util.Optional.of(new net.minecraft.world.phys.Vec3(4.5, -60, -8.5));
        var gone = net.minecraft.resources.ResourceKey.create(Registries.DIMENSION,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("customdimensions", "selftest_not_open"));
        var away = com.customdimensions.server.level.SavedLevel.placeable(server, who,
                new net.minecraft.server.level.ServerPlayer.SavedPosition(java.util.Optional.of(gone), pos, java.util.Optional.empty()));
        if (away.dimension().isPresent() || away.position().isPresent()) throw new AssertionError("a level that is not open kept " + away);
        var home = new net.minecraft.server.level.ServerPlayer.SavedPosition(java.util.Optional.of(net.minecraft.world.level.Level.OVERWORLD), pos,
                java.util.Optional.empty());
        if (com.customdimensions.server.level.SavedLevel.placeable(server, who, home) != home) throw new AssertionError("an open level's position was changed");
        return "the handler is merged into PrepareSpawnTask; a position saved in a level that is not open is dropped (world spawn), one in the overworld is kept";
    }

    private static String generationSettings(MinecraftServer server) {
        ChunkGenerator generator = server.overworld().getChunkSource().getGenerator();
        ChunkGeneratorAccessor accessor = (ChunkGeneratorAccessor) (Object) generator;
        Function<Holder<Biome>, BiomeGenerationSettings> original = accessor.customdimensions$generationSettingsGetter();
        if (original == null) throw new AssertionError("the getter read null");
        Holder<Biome> plains = server.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        AtomicInteger calls = new AtomicInteger();
        BiomeGenerationSettings through;
        accessor.customdimensions$setGenerationSettingsGetter(b -> {
            calls.incrementAndGet();
            return original.apply(b);
        });
        try {
            through = generator.getBiomeGenerationSettings(plains);
        } finally {
            accessor.customdimensions$setGenerationSettingsGetter(original);
        }
        if (calls.get() < 1) throw new AssertionError("the generator did not call the getter set through the accessor");
        if (accessor.customdimensions$generationSettingsGetter() != original) throw new AssertionError("the restored getter did not read back");
        if (through.features().size() != original.apply(plains).features().size()) throw new AssertionError("plains read differently through the getter set");
        return "the generator called the getter set through the accessor " + calls.get() + " time(s), plains " + through.features().size()
                + " feature steps; the original read back once restored";
    }

    private static String fluidRule(MinecraftServer server) {
        ChunkGenerator generator = server.overworld().getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) throw new AssertionError("the overworld's generator is " + generator.getClass().getName());
        NoiseBasedChunkGeneratorAccessor accessor = (NoiseBasedChunkGeneratorAccessor) (Object) noise;
        Supplier<Aquifer.FluidPicker> original = accessor.customdimensions$globalFluidPicker();
        Aquifer.FluidPicker picker = original == null ? null : original.get();
        if (picker == null) throw new AssertionError("the getter read no fluid rule");
        Supplier<Aquifer.FluidPicker> probe = original::get;
        accessor.customdimensions$setGlobalFluidPicker(probe);
        Supplier<Aquifer.FluidPicker> read;
        try {
            read = accessor.customdimensions$globalFluidPicker();
        } finally {
            accessor.customdimensions$setGlobalFluidPicker(original);
        }
        if (read != probe) throw new AssertionError("the rule set through the accessor did not read back");
        if (accessor.customdimensions$globalFluidPicker() != original) throw new AssertionError("the restored rule did not read back");
        int y = generator.getSeaLevel() - 1;
        BlockState at = picker.computeFluid(0, y, 0).at(y);
        return "rule " + picker.getClass().getSimpleName() + " (" + at.getBlock().getName().getString() + " at y " + y
                + "); a rule set through the accessor read back, the original once restored";
    }

    private static String structureControl() {
        return StructureRuntime.applied("minecraft:overworld")
                .map(a -> a.kept() + " set(s) kept, " + a.dropped() + " dropped, built in " + a.millis() + " ms")
                .orElseThrow(() -> new AssertionError("no structure control was applied to minecraft:overworld"));
    }

    private static String basinFlow(MinecraftServer server) {
        RandomState random = server.overworld().getChunkSource().randomState();
        Aquifer.FluidPicker beneath = (x, y, z) -> new Aquifer.FluidStatus(-64, Blocks.AIR.defaultBlockState());
        BlockState water = Blocks.WATER.defaultBlockState();
        BasinFluidPicker nearRim = new BasinFluidPicker(beneath, random, DensityFunctions.constant(0.5f), DensityFunctions.zero(), 64, water);
        BasinFluidPicker deep = new BasinFluidPicker(beneath, random, DensityFunctions.constant(1.5f), DensityFunctions.zero(), 64, water);
        Aquifer rim = Aquifer.createDisabled(nearRim);
        BlockState rimState = rim.computeSubstance(0, 10, 0, -1);
        boolean rimFlows = rim.shouldScheduleFluidUpdate();
        Aquifer middle = Aquifer.createDisabled(deep);
        BlockState middleState = middle.computeSubstance(0, 10, 0, -1);
        boolean middleFlows = middle.shouldScheduleFluidUpdate();
        String detail = "basin 0.5: " + name(rimState) + ", scheduled " + rimFlows + " (counted " + nearRim.scheduledCount() + "); basin 1.5: "
                + name(middleState) + ", scheduled " + middleFlows;
        if (!rimState.is(Blocks.WATER) || !middleState.is(Blocks.WATER)) throw new AssertionError("the basin holds no water: " + detail);
        if (!rimFlows || nearRim.scheduledCount() != 1 || middleFlows) throw new AssertionError(detail);
        return detail;
    }

    private static String name(BlockState state) {
        return state == null ? "null" : state.getBlock().getName().getString();
    }

    private interface Check {
        String run() throws Exception;
    }

    private static String check(String mixin, String what, Check check) {
        try {
            return "PASS mixin " + mixin + ": " + what + ": " + check.run();
        } catch (Throwable t) {
            return "FAIL mixin " + mixin + ": " + what + ": " + t;
        }
    }
}
