package com.customdimensions.mixin.compat;

import com.customdimensions.MultiverseServer;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
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
            "BetterEndTerrainInitMixin", "betterend");

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
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
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

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
