package com.customdimensions.mixin.compat;

import com.customdimensions.MultiverseServer;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies the Wilder Wild compatibility mixins only when that mod is present.
 *
 * <p>The mixins themselves carry {@code require = 1}, so once Wilder Wild IS
 * loaded a moved target fails the boot instead of silently not applying and
 * letting K6 back in with nothing naming us.
 */
public class WilderWildMixinPlugin implements IMixinConfigPlugin {

    private static final String WILDER_WILD = "wilderwild";

    private boolean present;

    @Override
    public void onLoad(String mixinPackage) {
        this.present = FabricLoader.getInstance().isModLoaded(WILDER_WILD);
        MultiverseServer.LOGGER.info(
                this.present
                        ? "Wilder Wild present — applying the snow blanket world floor (K6)"
                        : "Wilder Wild absent — snow blanket compatibility not applied");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return this.present;
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
