package com.customdimensions.client;

/**
 * Which world the projection stores' coordinates belong to.
 *
 * <p>Two callers notice a world change and either may be first: the join
 * itself, and the client tick that compares {@code MinecraftClient.world}
 * against what is bound. {@link #bind} answers only the first of them, so the
 * second does not clear a store the new world's payloads have already reached.
 */
public final class WorldBinding {

    private Object bound;
    private boolean armed;

    /** Whether {@code world} is new here, binding it either way. */
    public synchronized boolean bind(Object world) {
        if (armed && world == bound) {
            return false;
        }
        bound = world;
        armed = true;
        return true;
    }

    /** Nothing is bound, so the next world is a change whatever it is. */
    public synchronized void clear() {
        bound = null;
        armed = false;
    }
}
