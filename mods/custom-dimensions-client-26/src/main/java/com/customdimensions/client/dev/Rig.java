package com.customdimensions.client.dev;

import com.customdimensions.client.CompanionClient;
import com.customdimensions.client.feed.Feed;
import com.customdimensions.client.view.PortalRenderer;
import java.io.File;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The rig's in-game half, active only when {@code -Dcustomdimensions.rig.shots}
 * names a directory (the {@code -Prig} client run sets it): once joined as an
 * operator it builds a platform and a basalt frame high above spawn, lights it
 * with flint and steel through the real use-item path (clicking again when
 * the first click did not light it), stands in front of it,
 * waits for the feed, screenshots two distances into
 * {@code <shots>/screenshots/<label>-near.png} and {@code -far.png}, logs the
 * feed counts and quits.
 */
public final class Rig {
    static final int X = 8;
    static final int Y = 180;
    static final int Z = 8;

    private static int tick = -1;

    private Rig() {
    }

    public static void register() {
        String shots = System.getProperty("customdimensions.rig.shots", "");
        if (shots.isEmpty()) {
            return;
        }
        String label = System.getProperty("customdimensions.rig.label", "shot");
        ClientTickEvents.END_CLIENT_TICK.register(mc -> step(mc, new File(shots), label));
    }

    private static void command(Minecraft mc, String command) {
        mc.player.connection.sendCommand(command);
    }

    private static void step(Minecraft mc, File shots, String label) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        tick++;
        switch (tick) {
            case 60 -> {
                command(mc, "gamemode creative");
                command(mc, "time set 6000");
                command(mc, "weather clear");
                command(mc, "fill " + (X - 6) + " " + (Y - 1) + " " + (Z - 9) + " " + (X + 7) + " " + (Y - 1) + " " + (Z + 2)
                        + " minecraft:stone");
                command(mc, "fill " + (X - 6) + " " + Y + " " + (Z - 9) + " " + (X + 7) + " " + (Y + 6) + " " + (Z + 2)
                        + " minecraft:air");
                command(mc, "fill " + (X - 1) + " " + Y + " " + Z + " " + (X + 2) + " " + (Y + 4) + " " + Z
                        + " minecraft:basalt");
                command(mc, "fill " + X + " " + (Y + 1) + " " + Z + " " + (X + 1) + " " + (Y + 3) + " " + Z + " minecraft:air");
                command(mc, "item replace entity @s weapon.mainhand with minecraft:flint_and_steel");
                command(mc, "tp @s " + (X + 1) + ".0 " + Y + " " + (Z - 2) + ".5 0 0");
            }
            case 100, 130 -> {
                if (lit(mc)) {
                    return;
                }
                BlockPos clicked = new BlockPos(X, Y, Z);
                var result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(new Vec3(X + 0.5, Y + 1.0, Z + 0.5), Direction.UP, clicked, false));
                CompanionClient.LOG.info("rig: clicked the frame at {} -> {}", clicked, result);
            }
            case 160 -> {
                CompanionClient.LOG.info("rig: portal lit={} opening holds {}", lit(mc),
                        mc.level.getBlockState(new BlockPos(X, Y + 1, Z)));
                command(mc, "item replace entity @s weapon.mainhand with minecraft:air");
                command(mc, "tp @s " + (X + 1) + ".0 " + Y + " " + (Z - 3) + ".5 0 8");
            }
            case 500 -> shot(mc, shots, label + "-near");
            case 520 -> command(mc, "tp @s " + (X - 1) + ".5 " + Y + " " + (Z - 7) + ".5 -15 8");
            case 760 -> shot(mc, shots, label + "-far");
            case 800 -> {
                CompanionClient.LOG.info("rig: done frames={} received chunks={} held chunks={} entity snapshots={} "
                                + "held entities={} drawn openings={} drawn faces={} last faces={} renderer failed={}",
                        Feed.frames().size(), Feed.chunksReceived, Feed.chunksHeld(), Feed.entitySnapshots,
                        Feed.entitiesHeld(), PortalRenderer.openingsDrawn, PortalRenderer.facesDrawn,
                        PortalRenderer.lastFaces, PortalRenderer.failed());
                mc.stop();
            }
            default -> {
            }
        }
    }

    private static boolean lit(Minecraft mc) {
        return mc.level.getBlockState(new BlockPos(X, Y + 1, Z)).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL);
    }

    private static void shot(Minecraft mc, File shots, String name) {
        Screenshot.grab(shots, name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
                message -> CompanionClient.LOG.info("rig: screenshot {} -> {}", name, message.getString()));
    }
}
