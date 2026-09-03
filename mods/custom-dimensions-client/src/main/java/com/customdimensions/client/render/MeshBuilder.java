package com.customdimensions.client.render;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The one thread destination meshes are built on.
 *
 * <p>Vanilla meshes chunk sections off the render thread through the same
 * {@code BlockRenderManager}; a several-thousand-cell volume walked on the
 * render thread is a frame that never ends. One thread, below normal priority:
 * a portal's mesh is worth a few milliseconds of latency and never worth a
 * dropped frame.
 */
final class MeshBuilder {

    static final String THREAD_NAME = "customdimensions-mesh";

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, THREAD_NAME);
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private MeshBuilder() {}

    static Future<?> submit(Runnable task) {
        return POOL.submit(task);
    }
}
