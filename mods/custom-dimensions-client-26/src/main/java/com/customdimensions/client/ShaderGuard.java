package com.customdimensions.client;

import java.lang.reflect.Method;

/**
 * Stands the far side down while an Iris shader pack is in use: a pack
 * replaces the solid block pipeline the far side is drawn with, and the
 * slab it is squashed onto would be lit and shadowed as if it stood in the
 * opening. One log line on each change; Iris is read through its public API
 * by reflection, so the mod neither needs nor ships it.
 */
public final class ShaderGuard {
    private static Method inUse;
    private static Object api;
    private static boolean looked;
    private static boolean last;

    private ShaderGuard() {
    }

    public static boolean blocks() {
        boolean now = shaderPackInUse();
        if (now != last) {
            last = now;
            CompanionClient.LOG.info(now
                    ? "companion-off: an Iris shader pack is in use, portals draw as vanilla"
                    : "companion-on: no shader pack in use, the far side is drawn");
        }
        return now;
    }

    private static boolean shaderPackInUse() {
        if (!looked) {
            looked = true;
            try {
                Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = c.getMethod("getInstance").invoke(null);
                inUse = c.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | LinkageError e) {
                api = null;
            }
        }
        if (api == null) {
            return false;
        }
        try {
            return (Boolean) inUse.invoke(api);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }
}
