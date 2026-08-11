package com.customdimensions.web;

import com.customdimensions.MultiverseServer;
import com.customdimensions.command.Artefacts;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.tryout.TryOut;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
 */
public final class Picker {

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
        Path overlayDir = Artefacts.overlayDimensionsDir();
        if (!Files.isDirectory(overlayDir)) {
            return new Result(false, "The overlay is not mounted — set \"overrides\": {\"seed\": "
                    + seed + "} in overlay/config/custom-dimensions/dimensions/"
                    + dimensionSlug + ".json by hand", null);
        }

        int[] spawn = spawnFromTryOut(server, def.getDimensionIdentifier(), seed);
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
}
