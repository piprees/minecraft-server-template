package com.customdimensions.core.render.map;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.imageio.ImageIO;

/** ARGB PNGs for the web map, each written through a temporary file so a reader never sees half of one. */
public final class MapImages {
    private MapImages() {}

    public static void png(Path out, int width, int height, int[] argb) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        if (!ImageIO.write(image, "png", tmp.toFile())) throw new IOException("no PNG writer");
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The {@code w × h} north-west part of a {@code side}-wide image. */
    public static int[] crop(int[] argb, int side, int w, int h) {
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) System.arraycopy(argb, y * side, out, y * w, w);
        return out;
    }
}
