package com.customdimensions.client.dev;

import com.customdimensions.client.ArrivalScreen;
import com.customdimensions.client.render.ClientProjection;
import com.customdimensions.client.render.ProjectionMesh;
import com.customdimensions.client.render.ProjectionStore;
import com.customdimensions.client.render.QuadCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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

    /** Absent, with the reason, when there is no player rather than a guessed one. */
    private static String player(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return PlayerFacts.absent("no player in the world");
        }
        Vec3d pos = player.getPos();
        BlockPos block = player.getBlockPos();
        return new PlayerFacts(
                player.getWorld().getRegistryKey().getValue().toString(),
                pos.x, pos.y, pos.z,
                block.getX(), block.getY(), block.getZ(),
                new PlayerFacts.Rotation(player.getYaw(), player.getPitch(),
                        player.getHeadYaw(), player.getBodyYaw(),
                        player.getHorizontalFacing().asString()),
                new PlayerFacts.Vitals(player.getHealth(), player.getMaxHealth(),
                        player.getHungerManager().getFoodLevel(),
                        player.getHungerManager().getSaturationLevel(),
                        player.getAir(), player.getMaxAir(),
                        player.experienceLevel, player.experienceProgress),
                held(player),
                new PlayerFacts.Status(player.getPose().name(), flags(player),
                        player.isOnGround(), player.fallDistance))
                .json();
    }

    private static PlayerFacts.Held held(ClientPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        List<PlayerFacts.Item> hotbar = new ArrayList<>(PlayerInventory.getHotbarSize());
        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            hotbar.add(item(inventory.getStack(slot)));
        }
        return new PlayerFacts.Held(item(player.getMainHandStack()),
                item(player.getOffHandStack()), inventory.selectedSlot, hotbar);
    }

    /** Null for an empty slot, and null durability for anything that cannot wear. */
    private static PlayerFacts.Item item(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        boolean wears = stack.isDamageable();
        return new PlayerFacts.Item(
                Registries.ITEM.getId(stack.getItem()).toString(),
                stack.getCount(),
                wears ? stack.getDamage() : null,
                wears ? stack.getMaxDamage() : null);
    }

    private static Set<PlayerFacts.Flag> flags(ClientPlayerEntity player) {
        Set<PlayerFacts.Flag> flags = EnumSet.noneOf(PlayerFacts.Flag.class);
        add(flags, PlayerFacts.Flag.SNEAKING, player.isSneaking());
        add(flags, PlayerFacts.Flag.SPRINTING, player.isSprinting());
        add(flags, PlayerFacts.Flag.SWIMMING, player.isSwimming());
        add(flags, PlayerFacts.Flag.CRAWLING, player.isCrawling());
        add(flags, PlayerFacts.Flag.GLIDING, player.isFallFlying());
        add(flags, PlayerFacts.Flag.SLEEPING, player.isSleeping());
        add(flags, PlayerFacts.Flag.RIDING, player.hasVehicle());
        add(flags, PlayerFacts.Flag.ON_FIRE, player.isOnFire());
        add(flags, PlayerFacts.Flag.IN_LAVA, player.isInLava());
        add(flags, PlayerFacts.Flag.IN_WATER, player.isTouchingWater());
        add(flags, PlayerFacts.Flag.SUBMERGED, player.isSubmergedInWater());
        add(flags, PlayerFacts.Flag.CLIMBING, player.isClimbing());
        add(flags, PlayerFacts.Flag.BLOCKING, player.isBlocking());
        add(flags, PlayerFacts.Flag.SPECTATOR, player.isSpectator());
        return flags;
    }

    private static void add(Set<PlayerFacts.Flag> flags, PlayerFacts.Flag flag, boolean on) {
        if (on) {
            flags.add(flag);
        }
    }

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
