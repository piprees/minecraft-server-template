package com.customdimensions.mixin.compat;

import com.customdimensions.MultiverseServer;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies each compatibility mixin only when the mod it targets is loaded.
 *
 * <p>The mixins carry {@code require = 1}, so once their mod IS loaded a moved
 * target fails the boot rather than silently not applying. A mixin missing
 * from {@link #MOD_BY_MIXIN} is never applied and says so — an unlisted
 * compatibility mixin that quietly does nothing is the failure this guards.
 */
public class CompatMixinPlugin implements IMixinConfigPlugin {

    /** Compatibility mixin simple name -> the mod id it targets. */
    private static final Map<String, String> MOD_BY_MIXIN = Map.of(
            "SnowBlanketVoidFloorMixin", "wilderwild",
            "BetterEndTerrainInitMixin", "betterend",
            "BetterCavesAquiferDuckMixin", "bettercaves",
            "BetterCavesCarveFloorMixin", "bettercaves");

    /**
     * Better Caves' duck interface, its one method and that method's exact
     * descriptor. Written out rather than imported: neither type is on this
     * mod's compile classpath, and the descriptor is what the mod's
     * {@code invokeinterface} resolves.
     */
    private static final String LIQUID_REGIONS_PROVIDER =
            "com/yungnickyoung/minecraft/bettercaves/duck/ILiquidRegionsProvider";
    private static final String GET_LIQUID_REGIONS = "bettercaves$getLiquidRegions";
    private static final String GET_LIQUID_REGIONS_DESC =
            "()Lcom/yungnickyoung/minecraft/bettercaves/worldgen/liquidregion/LiquidRegions;";

    @Override
    public void onLoad(String mixinPackage) {
        for (Map.Entry<String, String> e : MOD_BY_MIXIN.entrySet()) {
            MultiverseServer.LOGGER.info("Compatibility mixin {}: {} {}",
                    e.getKey(), e.getValue(),
                    FabricLoader.getInstance().isModLoaded(e.getValue()) ? "present — applying"
                            : "absent — not applied");
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simple = simpleName(mixinClassName);
        String modId = MOD_BY_MIXIN.get(simple);
        if (modId == null) {
            MultiverseServer.LOGGER.warn(
                    "Compatibility mixin {} names no mod in CompatMixinPlugin — NOT applied", simple);
            return false;
        }
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    /**
     * Gives {@code AquiferSampler} Better Caves' {@code ILiquidRegionsProvider}
     * as a superinterface, with a default method answering null (T79).
     *
     * <p>Generated rather than declared because both types belong to Better
     * Caves and this mod compiles against neither. Null is the mod's own
     * "no liquid regions here" answer — {@code MasterController.carve} and
     * {@code NoiseChunkMixin} each null-check the result and fall through to
     * vanilla aquifers, which is what a dimension absent from
     * {@code liquidregions.json} already gets.
     *
     * <p>Keyed on the mixin, matching {@link #shouldApplyMixin}, because the
     * target class name arrives intermediary at runtime and named in dev.
     */
    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
            IMixinInfo mixinInfo) {
        if (!"BetterCavesAquiferDuckMixin".equals(simpleName(mixinClassName))) {
            return;
        }
        if (!targetClass.interfaces.contains(LIQUID_REGIONS_PROVIDER)) {
            targetClass.interfaces.add(LIQUID_REGIONS_PROVIDER);
        }
        for (MethodNode existing : targetClass.methods) {
            if (GET_LIQUID_REGIONS.equals(existing.name)
                    && GET_LIQUID_REGIONS_DESC.equals(existing.desc)) {
                return;
            }
        }
        MethodNode fallback = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC,
                GET_LIQUID_REGIONS, GET_LIQUID_REGIONS_DESC, null, null);
        fallback.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        fallback.instructions.add(new InsnNode(Opcodes.ARETURN));
        fallback.maxStack = 1;
        fallback.maxLocals = 1;
        targetClass.methods.add(fallback);
        MultiverseServer.LOGGER.info("Compatibility mixin BetterCavesAquiferDuckMixin: {} now "
                + "provides {} — sea-level samplers answer null instead of failing the cast",
                targetClass.name, LIQUID_REGIONS_PROVIDER);
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static String simpleName(String mixinClassName) {
        return mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
    }
}
