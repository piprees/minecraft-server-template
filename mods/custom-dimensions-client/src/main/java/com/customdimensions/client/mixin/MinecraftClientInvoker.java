package com.customdimensions.client.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The client's own right-click path. Reaching it means a block's {@code onUse},
 * the stack's {@code useOnBlock} and every mixin on either fire in vanilla's
 * order, which no reimplementation of the call would give.
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientInvoker {

    @Invoker("doItemUse")
    void customdimensionsclient$doItemUse();
}
