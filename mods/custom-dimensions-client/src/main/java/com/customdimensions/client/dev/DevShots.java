package com.customdimensions.client.dev;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The game's own framebuffer, written where the caller asked. Render thread
 * only — the readback is a GL call.
 */
final class DevShots {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    record Shot(String path, long bytes, int width, int height) {}

    private DevShots() {}

    static Shot capture(MinecraftClient client, String path) throws IOException {
        Path target = Paths.get(path);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (NativeImage image = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
            image.writeTo(target);
            return new Shot(target.toString(), Files.size(target),
                    image.getWidth(), image.getHeight());
        }
    }

    /** Where an action's before/after pair goes when the caller names no directory. */
    static String allocate(String directory, String action, String phase) {
        String dir = directory == null || directory.isBlank()
                ? System.getProperty("java.io.tmpdir") + "/customdim-dev"
                : directory.trim();
        return dir.replaceAll("/+$", "") + "/" + action + "-"
                + SEQUENCE.incrementAndGet() + "-" + phase + ".png";
    }

    /** A shot that failed reports why; it never fails the action it belongs to. */
    static String json(MinecraftClient client, String path) {
        try {
            Shot shot = capture(client, path);
            return Json.obj()
                    .str("path", shot.path())
                    .num("bytes", shot.bytes())
                    .num("width", shot.width())
                    .num("height", shot.height())
                    .toString();
        } catch (IOException | RuntimeException e) {
            return Json.obj().str("path", null).str("error", String.valueOf(e)).toString();
        }
    }
}
