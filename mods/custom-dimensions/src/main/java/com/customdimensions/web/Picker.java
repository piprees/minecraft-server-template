package com.customdimensions.web;

import com.customdimensions.MultiverseServer;
import com.customdimensions.command.Artefacts;
import com.customdimensions.command.InputHash;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.roll.CandidateRender;
import com.customdimensions.roll.SeedBank;
import com.customdimensions.tryout.TryOut;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Choosing a seed: the last step, and the only one a person makes.
 *
 * <p>Writes the chosen seed into the consumer's committed overlay file —
 * {@code overlay/config/custom-dimensions/dimensions/&lt;slug&gt;.json}, under
 * {@code overrides} so it patches the platform default rather than replacing
 * it. Git is what records the choice; the candidate bank is only a cache.
 *
 * <p>If the person is standing in that candidate's try-out when they pick,
 * their position becomes the dimension's spawn. That is the whole point of
 * being able to fly around first: stand somewhere good, then choose.
 *
 * <p>Refuses when the overlay directory is not mounted. A seed written
 * anywhere else reaches neither git nor the next roll, and would be destroyed
 * by the next config refresh — silently doing nothing useful is worse than
 * saying no.
 *
 * <p>The winning candidate's own renders are copied beside the JSON as
 * {@code &lt;slug&gt;_low.png} / {@code &lt;slug&gt;_high.png} — the map
 * sidebar's source for that dimension's card (see
 * {@code docker/unmined-render/render-loop.sh}), so a preview exists for
 * every picked dimension without the server ever rendering the live world for
 * one. Committed to git alongside the config, so both copies are downscaled
 * on the way out: {@link #THUMB_LOW_MAX_SIDE} is a card thumbnail's size,
 * {@link #THUMB_HIGH_MAX_SIDE} is a lightbox's — well below the roller's own
 * render sizes, which exist to be judged on screen, not stored forever.
 */
public final class Picker {

    /** Sidebar card thumbnail — a few hundred px is all a card ever shows. */
    private static final int THUMB_LOW_MAX_SIDE = 400;
    /** Lightbox copy — bigger than the card, still a fraction of a highres render. */
    private static final int THUMB_HIGH_MAX_SIDE = 960;

    private Picker() {
    }

    /** What a pick did, for the browser to show. */
    public record Result(boolean ok, String message, int[] spawn) {
    }

    public static Result pick(MinecraftServer server, String dimensionSlug, long seed) {
        DimensionConfig def = BankView.resolve(dimensionSlug);
        if (def == null) {
            return new Result(false, "No configured dimension " + dimensionSlug, null);
        }
        int[] spawn = spawnFromTryOut(server, def.getDimensionIdentifier(), seed);
        return write(server, def, dimensionSlug, seed, spawn);
    }

    /**
     * The unattended write path: promotes {@code seed} to current with a
     * SPECIFIC spawn (or {@code null} for none), rather than deriving one
     * from a player standing in the candidate's try-out — a roll has nobody
     * standing anywhere. Used only by {@code RollPipeline}'s auto-promote;
     * {@link #pick} ("Use this seed") is unchanged and does not call this.
     */
    public static Result pickWithSpawn(MinecraftServer server, String dimensionSlug, long seed,
                                       int[] spawn) {
        DimensionConfig def = BankView.resolve(dimensionSlug);
        if (def == null) {
            return new Result(false, "No configured dimension " + dimensionSlug, null);
        }
        return write(server, def, dimensionSlug, seed, spawn);
    }

    private static Result write(MinecraftServer server, DimensionConfig def, String dimensionSlug,
                                long seed, int[] spawn) {
        Path overlayDir = Artefacts.overlayDimensionsDir();
        if (!Files.isDirectory(overlayDir)) {
            return new Result(false, "The overlay is not mounted — set \"overrides\": {\"seed\": "
                    + seed + "} in overlay/config/custom-dimensions/dimensions/"
                    + dimensionSlug + ".json by hand", null);
        }

        Path target = overlayDir.resolve(dimensionSlug + ".json");
        try {
            JsonObject root = Files.isRegularFile(target)
                    ? JsonParser.parseString(Files.readString(target)).getAsJsonObject()
                    : new JsonObject();
            JsonObject overrides = root.has("overrides") && root.get("overrides").isJsonObject()
                    ? root.getAsJsonObject("overrides") : new JsonObject();
            overrides.addProperty("seed", seed);
            if (spawn != null) {
                JsonArray array = new JsonArray();
                for (int v : spawn) {
                    array.add(v);
                }
                overrides.add("spawn", array);
            }
            root.add("overrides", overrides);
            Artefacts.write(target, new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n");
            copyThumbnails(server, def, dimensionSlug, seed, overlayDir);
            String where = spawn == null ? ""
                    : " spawn=" + spawn[0] + "," + spawn[1] + "," + spawn[2];
            MultiverseServer.LOGGER.info("pick {}: seed={}{} -> {}", dimensionSlug, seed, where, target);
            return new Result(true, "seed=" + seed + where + " written to " + target, spawn);
        } catch (IOException | RuntimeException e) {
            MultiverseServer.LOGGER.error("Failed to write pick for {}", dimensionSlug, e);
            return new Result(false, "Write failed: " + e, null);
        }
    }

    /**
     * True when both thumbnails already sit beside the dimension's JSON AND
     * were drawn from the config in force now. Priming skips a dimension that
     * has them, so a roll's picks are never redrawn from the configured seed.
     *
     * <p>Checked overlay first, then the platform defaults — the same
     * precedence {@code render-loop.sh}'s {@code thumb_file_for} publishes by.
     *
     * <p>The filename carries only the slug, and a slug does not identify a
     * world: {@link InputHash} covers the whole config bar the seed, so the
     * same slug and the same seed draw a different picture either side of a
     * biome edit or a consumer overlay. Without the recorded hash a pair goes
     * stale silently — the bank re-renders under the new key while the
     * committed PNG keeps showing a world nobody generates any more.
     */
    public static boolean thumbnailsPresent(MinecraftServer server, DimensionConfig def,
                                            String dimensionSlug) {
        String hash = InputHash.of(def, server);
        return currentPairIn(Artefacts.overlayDimensionsDir(), dimensionSlug, hash)
                || currentPairIn(Artefacts.dir("dimensions"), dimensionSlug, hash);
    }

    private static boolean currentPairIn(Path dir, String dimensionSlug, String hash) {
        return Files.isRegularFile(dir.resolve(dimensionSlug + "_low.png"))
                && Files.isRegularFile(dir.resolve(dimensionSlug + "_high.png"))
                && hash.equals(recordedHash(dir, dimensionSlug));
    }

    /** The hash a committed pair was drawn under, or null when unrecorded. */
    private static String recordedHash(Path dir, String dimensionSlug) {
        Path sidecar = dir.resolve(dimensionSlug + "_thumb.json");
        if (!Files.isRegularFile(sidecar)) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(sidecar)).getAsJsonObject();
            return root.has("inputHash") ? root.get("inputHash").getAsString() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Publishes the configured seed's renders beside its JSON, writing only
     * the sizes that are missing and never clearing one. {@link #pick} deletes
     * a target it has no source for, because a stale thumbnail there would
     * belong to a seed nobody chose; priming has no such claim — a committed
     * PNG it cannot re-source is the only copy of that picture, and the bank
     * it would be re-sourced from is re-keyed by any config edit.
     *
     * <p>The JSON is untouched: priming publishes what the config already
     * says, it does not choose anything.
     */
    public static void exportMissingThumbnails(MinecraftServer server, DimensionConfig def,
                                               String dimensionSlug, long seed) {
        Path dir = Artefacts.overlayDimensionsDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        String dimension = def.getDimensionIdentifier().toString();
        String inputHash = InputHash.of(def, server);
        Path low = dir.resolve(dimensionSlug + "_low.png");
        if (!Files.isRegularFile(low)) {
            writeThumbnail(SeedBank.candidateImagePath(inputHash, dimension, seed,
                    CandidateRender.Resolution.LOWRES), low, THUMB_LOW_MAX_SIDE);
        }
        Path high = dir.resolve(dimensionSlug + "_high.png");
        if (!Files.isRegularFile(high)) {
            writeThumbnail(SeedBank.candidateImagePath(inputHash, dimension, seed,
                    CandidateRender.Resolution.HIGHRES), high, THUMB_HIGH_MAX_SIDE);
        }
        recordProvenance(dir, dimensionSlug, inputHash, seed);
    }

    /**
     * Stamps the pair with the config it was drawn under. Read back by
     * {@link #thumbnailsPresent}, which treats an unstamped or stale pair as
     * missing — the only thing that stops a committed PNG outliving the world
     * it shows.
     */
    private static void recordProvenance(Path dir, String dimensionSlug, String inputHash, long seed) {
        if (!Files.isRegularFile(dir.resolve(dimensionSlug + "_low.png"))) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("inputHash", inputHash);
        root.addProperty("seed", seed);
        try {
            Artefacts.write(dir.resolve(dimensionSlug + "_thumb.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n");
        } catch (IOException | RuntimeException e) {
            MultiverseServer.LOGGER.warn("Could not stamp thumbnails for {}: {}",
                    dimensionSlug, e.toString());
        }
    }

    /**
     * Where the person is standing, but only if they are standing in this
     * exact candidate's try-out. Standing anywhere else says nothing about
     * where this dimension's spawn should be.
     */
    private static int[] spawnFromTryOut(MinecraftServer server, Identifier dimensionId, long seed) {
        Identifier worldId = TryOut.worldIdFor(dimensionId, seed);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.getWorld().getRegistryKey().getValue().equals(worldId)) {
                return new int[]{(int) Math.floor(player.getX()),
                        (int) Math.floor(player.getY()),
                        (int) Math.floor(player.getZ())};
            }
        }
        return null;
    }

    /**
     * Copies (or clears) the winner's low/high renders beside its JSON. Best
     * effort: a candidate picked straight off the leaderboard may never have
     * had a highres render drawn, and that must not fail the pick — the seed
     * itself is what matters. Always resolved from THIS pick's inputs, so a
     * re-pick under a stale hash never copies a render for the wrong seed.
     */
    private static void copyThumbnails(MinecraftServer server, DimensionConfig def,
                                       String dimensionSlug, long seed, Path overlayDir) {
        String dimension = def.getDimensionIdentifier().toString();
        String inputHash = InputHash.of(def, server);
        writeThumbnail(
                SeedBank.candidateImagePath(inputHash, dimension, seed, CandidateRender.Resolution.LOWRES),
                overlayDir.resolve(dimensionSlug + "_low.png"), THUMB_LOW_MAX_SIDE);
        writeThumbnail(
                SeedBank.candidateImagePath(inputHash, dimension, seed, CandidateRender.Resolution.HIGHRES),
                overlayDir.resolve(dimensionSlug + "_high.png"), THUMB_HIGH_MAX_SIDE);
        recordProvenance(overlayDir, dimensionSlug, inputHash, seed);
    }

    /**
     * One render copied to {@code target}, downscaled to {@code maxSide} on
     * its long edge when it is bigger. Missing source (no render at this
     * resolution) clears a stale {@code target} left by an earlier pick,
     * rather than leaving a thumbnail that no longer matches the chosen seed.
     */
    private static void writeThumbnail(Path source, Path target, int maxSide) {
        try {
            if (!Files.isRegularFile(source)) {
                Files.deleteIfExists(target);
                return;
            }
            BufferedImage src = ImageIO.read(source.toFile());
            if (src == null) {
                return;
            }
            BufferedImage out = Math.max(src.getWidth(), src.getHeight()) <= maxSide
                    ? src : downscale(src, maxSide);
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            if (!ImageIO.write(out, "png", tmp.toFile())) {
                throw new IOException("no PNG writer registered for this JVM");
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | RuntimeException e) {
            MultiverseServer.LOGGER.warn("Could not write thumbnail {} -> {}: {}", source, target, e.toString());
        }
    }

    /**
     * Box-average downsample to at most {@code maxSide} on the long edge.
     * These renders are flat-colour maps, not photos, so an average over each
     * destination cell's source block reads as a clean shrink rather than
     * needing a resampling filter.
     */
    private static BufferedImage downscale(BufferedImage src, int maxSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = maxSide / (double) Math.max(w, h);
        int outW = Math.max(1, (int) Math.round(w * scale));
        int outH = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        for (int oy = 0; oy < outH; oy++) {
            int sy0 = oy * h / outH;
            int sy1 = Math.max(sy0 + 1, (oy + 1) * h / outH);
            for (int ox = 0; ox < outW; ox++) {
                int sx0 = ox * w / outW;
                int sx1 = Math.max(sx0 + 1, (ox + 1) * w / outW);
                long r = 0;
                long g = 0;
                long b = 0;
                long n = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    for (int sx = sx0; sx < sx1; sx++) {
                        int rgb = src.getRGB(sx, sy);
                        r += (rgb >> 16) & 0xFF;
                        g += (rgb >> 8) & 0xFF;
                        b += rgb & 0xFF;
                        n++;
                    }
                }
                int avg = (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
                out.setRGB(ox, oy, avg);
            }
        }
        return out;
    }
}
