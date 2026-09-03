package com.customdimensions.client.render;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Where mesh work runs, and that the draw path is no longer switched off.
 *
 * <p>The build itself needs a live {@code MinecraftClient} and cannot be run
 * here; what is testable is that the work leaves the calling thread and that
 * the one thread it lands on is shared, so several portals cannot fan out.
 */
class MeshBuilderTest {

    @Test
    void workRunsOffTheCallingThread() throws Exception {
        String caller = Thread.currentThread().getName();
        AtomicReference<String> ranOn = new AtomicReference<>();

        MeshBuilder.submit(() -> ranOn.set(Thread.currentThread().getName()))
                .get(5, TimeUnit.SECONDS);

        assertEquals(MeshBuilder.THREAD_NAME, ranOn.get());
        assertNotEquals(caller, ranOn.get(), "the mesh build ran on the caller's thread");
    }

    @Test
    void theBuilderIsOneThread() throws Exception {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(2);

        MeshBuilder.submit(() -> {
            first.set(Thread.currentThread().getName());
            done.countDown();
        });
        MeshBuilder.submit(() -> {
            second.set(Thread.currentThread().getName());
            done.countDown();
        });

        assertTrue(done.await(5, TimeUnit.SECONDS), "the builder did not drain its queue");
        assertEquals(first.get(), second.get(), "two builds ran on different threads");
    }

    @Test
    void theBuilderThreadIsADaemonSoItCannotHoldTheGameOpen() throws Exception {
        AtomicReference<Boolean> daemon = new AtomicReference<>();
        MeshBuilder.submit(() -> daemon.set(Thread.currentThread().isDaemon()))
                .get(5, TimeUnit.SECONDS);
        assertEquals(Boolean.TRUE, daemon.get());
    }

    /** Removed with the workload fix; a reintroduced gate ships a dead feature. */
    @Test
    void theDrawPathIsNotGatedBehindASystemProperty() {
        for (Field field : ProjectionRenderer.class.getDeclaredFields()) {
            if ("ENABLED".equals(field.getName())) {
                fail("ProjectionRenderer.ENABLED is back: " + Arrays.toString(
                        ProjectionRenderer.class.getDeclaredFields()));
            }
        }
    }

    @Test
    void buildMarkerIsTheAgreedLiteral() {
        assertEquals("companion-client:projection-mesh", ProjectionMesh.BUILD_MARKER);
    }
}
