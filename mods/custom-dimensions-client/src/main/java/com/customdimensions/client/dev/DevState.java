package com.customdimensions.client.dev;

import com.customdimensions.client.ArrivalScreen;
import com.customdimensions.client.render.ClientProjection;
import com.customdimensions.client.render.ProjectionMesh;
import com.customdimensions.client.render.ProjectionStore;
import com.customdimensions.client.render.QuadCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * What the client can be asked, as facts rather than log lines. Render thread
 * only — every field here is read straight off live client state.
 */
final class DevState {

    /** Floats per vertex times four vertices. */
    private static final int FLOATS_PER_QUAD = QuadCapture.STRIDE * 4;

    private DevState() {}

    static String json(MinecraftClient client, int tick) {
        return Json.obj()
                .bool("ok", true)
                .num("tick", tick)
                .raw("player", player(client))
                .raw("client", clientState(client))
                .raw("projections", projections())
                .toString();
    }

    private static String player(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return "null";
        }
        Vec3d pos = player.getPos();
        BlockPos block = player.getBlockPos();
        ItemStack held = player.getMainHandStack();
        return Json.obj()
                .str("dimension", player.getWorld().getRegistryKey().getValue().toString())
                .raw("pos", Json.numbers(pos.x, pos.y, pos.z))
                .raw("blockPos", Json.numbers(block.getX(), block.getY(), block.getZ()))
                .num("yaw", player.getYaw())
                .num("pitch", player.getPitch())
                .bool("onGround", player.isOnGround())
                .num("health", player.getHealth())
                .str("mainHandItem",
                        held.isEmpty() ? null : Registries.ITEM.getId(held.getItem()).toString())
                .toString();
    }

    /**
     * {@code currentScreen} is the runtime class name, which for a VANILLA screen
     * is its intermediary name ({@code class_424}, not {@code TitleScreen}) —
     * this mod's own classes are not remapped, so only they read as themselves.
     * Assert on {@code arrivalScreen}, which is an instance check and cannot be
     * fooled by remapping, or on {@code screenTitle}, which is text.
     */
    private static String clientState(MinecraftClient client) {
        Screen screen = client.currentScreen;
        return Json.obj()
                .str("currentScreen", screen == null ? null : screen.getClass().getSimpleName())
                .str("screenTitle", screen == null ? null : screen.getTitle().getString())
                .bool("arrivalScreen", screen instanceof ArrivalScreen)
                .num("fps", client.getCurrentFps())
                .num("loadedChunks",
                        client.world == null ? 0 : client.world.getChunkManager().getLoadedChunkCount())
                .bool("worldLoaded", client.world != null)
                .bool("paused", client.isPaused())
                .toString();
    }

    /** This mod's own render state: one entry per portal the client holds. */
    private static String projections() {
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (ClientProjection projection : ProjectionStore.all()) {
            BlockPos aperture = projection.apertureOrigin();
            ProjectionMesh mesh = projection.meshIfReady();
            out.append(first ? "" : ",").append(Json.obj()
                    .str("destination", projection.payload().destination().toString())
                    .raw("aperture",
                            Json.numbers(aperture.getX(), aperture.getY(), aperture.getZ()))
                    .bool("meshReady", mesh != null)
                    .num("quads", mesh == null ? 0 : mesh.quads())
                    .raw("layers", layers(mesh))
                    .toString());
            first = false;
        }
        return out.append(']').toString();
    }

    private static String layers(ProjectionMesh mesh) {
        if (mesh == null) {
            return "[]";
        }
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (ProjectionMesh.Layer layer : mesh.layers()) {
            out.append(first ? "" : ",").append(Json.obj()
                    .str("layer", String.valueOf(layer.layer()))
                    .num("quads", layer.floats() / FLOATS_PER_QUAD)
                    .toString());
            first = false;
        }
        return out.append(']').toString();
    }
}
