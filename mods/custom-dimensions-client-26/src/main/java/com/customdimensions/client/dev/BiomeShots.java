package com.customdimensions.client.dev;

import com.customdimensions.client.CompanionClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The rig's biome-shots mode, active only when {@code -Dcustomdimensions.rig.biomes}
 * names a shot list (the {@code -Prig} client run with {@code -PrigBiomes} sets it):
 * a JSON array of {@code {dimension, x, y, z, yaw, pitch, name}} or, with {@code at: [x, y, z]}
 * in place of yaw and pitch, a camera turned to face that block. {@code y} is a number,
 * {@code "surface[+N]"} (N blocks over the motion-blocking height at x, z) or
 * {@code "ground[+N]"} (over the ground under any trees and plants); {@code nightVision: true} lights a shot in a cavern
 * with night vision, which every other shot clears. As the operator, in spectator mode with the HUD hidden, at noon in
 * clear weather, it takes each entry in turn: {@code /customdim tp} into the dimension
 * when it is not already there, a teleport high over the spot, the surface read from
 * the client's own heightmap once that chunk arrives, the teleport to the shot, a wait
 * until every chunk within {@link #RADIUS} is loaded and the renderer has compiled every
 * visible section (or {@link #CAP} ticks), then a screenshot to
 * {@code <shots>/screenshots/<name>.png}. It quits after the last.
 */
public final class BiomeShots {
    /** Chunks round the camera that must be on the client before a shot. */
    static final int RADIUS = 6;
    /** Ticks a shot waits for chunks and meshes at most. */
    static final int CAP = 1200;
    /** Ticks after the final teleport before the renderer's state is trusted. */
    static final int SETTLE = 80;

    private record Shot(String dimension, double x, String y, double z, float yaw, float pitch, double[] at, String name, boolean nightVision) {
    }

    private enum Phase { START, ENTER, HIGH, PLACE, WAIT, SHOOT, NEXT, DONE }

    private final List<Shot> shots;
    private final File out;
    private int index;
    private Phase phase = Phase.START;
    private int wait;
    private int tick;

    private BiomeShots(List<Shot> shots, File out) {
        this.shots = shots;
        this.out = out;
    }

    /** True when the shot list property is set: the mode is registered and the portal script stays off. */
    public static boolean register(File out) {
        String list = System.getProperty("customdimensions.rig.biomes", "");
        if (list.isEmpty()) {
            return false;
        }
        List<Shot> shots;
        try {
            shots = read(new File(list));
        } catch (IOException | RuntimeException e) {
            CompanionClient.LOG.error("rig: cannot read the shot list {}: {}", list, e.toString());
            return true;
        }
        CompanionClient.LOG.info("rig: biome shots: {} from {}", shots.size(), list);
        BiomeShots run = new BiomeShots(shots, out);
        ClientTickEvents.END_CLIENT_TICK.register(run::step);
        return true;
    }

    static List<Shot> read(File file) throws IOException {
        JsonArray array = JsonParser.parseString(Files.readString(file.toPath())).getAsJsonArray();
        List<Shot> out = new ArrayList<>();
        for (JsonElement e : array) {
            JsonObject o = e.getAsJsonObject();
            double[] at = null;
            if (o.has("at")) {
                JsonArray a = o.getAsJsonArray("at");
                at = new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
            }
            out.add(new Shot(o.get("dimension").getAsString(), o.get("x").getAsDouble(), o.get("y").getAsString(),
                    o.get("z").getAsDouble(), o.has("yaw") ? o.get("yaw").getAsFloat() : 0, o.has("pitch") ? o.get("pitch").getAsFloat() : 0,
                    at, o.get("name").getAsString(), o.has("nightVision") && o.get("nightVision").getAsBoolean()));
        }
        return out;
    }

    private static void command(Minecraft mc, String command) {
        mc.player.connection.sendCommand(command);
    }

    private void step(Minecraft mc) {
        if (mc.player == null || mc.level == null || phase == Phase.DONE) {
            return;
        }
        tick++;
        wait++;
        switch (phase) {
            case START -> {
                if (tick < 60) {
                    return;
                }
                command(mc, "gamemode spectator");
                command(mc, "gamerule advance_time false");
                command(mc, "gamerule advance_weather false");
                command(mc, "time set 6000");
                command(mc, "weather clear");
                if (!mc.gui.hud.isHidden()) {
                    mc.gui.hud.toggle();
                }
                go(Phase.ENTER);
                if (shots.isEmpty()) {
                    finish(mc);
                }
            }
            case ENTER -> {
                Shot shot = shots.get(index);
                if (!here(mc, shot)) {
                    if (wait == 1) {
                        command(mc, "customdim tp " + slug(shot.dimension()));
                    } else if (wait > CAP) {
                        CompanionClient.LOG.warn("rig: could not enter {} for {}", shot.dimension(), shot.name());
                        go(Phase.NEXT);
                    }
                    return;
                }
                go(Phase.HIGH);
            }
            case HIGH -> {
                Shot shot = shots.get(index);
                command(mc, String.format(Locale.ROOT, "execute in %s run tp @s %.1f %d %.1f %.1f %.1f", shot.dimension(), shot.x(),
                        mc.level.getMaxY() - 8, shot.z(), shot.yaw(), shot.pitch()));
                go(Phase.PLACE);
            }
            case PLACE -> {
                Shot shot = shots.get(index);
                int cx = ((int) Math.floor(shot.x())) >> 4;
                int cz = ((int) Math.floor(shot.z())) >> 4;
                if (!mc.level.getChunkSource().hasChunk(cx, cz) || mc.player.distanceToSqr(shot.x(), mc.player.getY(), shot.z()) > 4) {
                    if (wait > CAP) {
                        CompanionClient.LOG.warn("rig: the chunk under {} never arrived", shot.name());
                        go(Phase.NEXT);
                    }
                    return;
                }
                double y = y(mc, shot);
                float yaw = shot.yaw();
                float pitch = shot.pitch();
                if (shot.at() != null) {
                    double dx = shot.at()[0] + 0.5 - shot.x();
                    double dy = shot.at()[1] + 0.5 - (y + mc.player.getEyeHeight());
                    double dz = shot.at()[2] + 0.5 - shot.z();
                    yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                }
                command(mc, shot.nightVision() ? "effect give @s minecraft:night_vision infinite 0 true" : "effect clear @s minecraft:night_vision");
                command(mc, String.format(Locale.ROOT, "execute in %s run tp @s %.2f %.2f %.2f %.1f %.1f", shot.dimension(), shot.x(), y,
                        shot.z(), yaw, pitch));
                CompanionClient.LOG.info("rig: {} at {} {} {} yaw {} pitch {}", shot.name(), shot.x(), y, shot.z(), yaw, pitch);
                go(Phase.WAIT);
            }
            case WAIT -> {
                if (wait < SETTLE) {
                    return;
                }
                boolean ready = loaded(mc) && mc.levelRenderer.hasRenderedAllSections();
                if (ready || wait > CAP) {
                    if (!ready) {
                        CompanionClient.LOG.warn("rig: {} shot before every section was drawn", shots.get(index).name());
                    }
                    go(Phase.SHOOT);
                }
            }
            case SHOOT -> {
                if (wait < 10) {
                    return;
                }
                String name = shots.get(index).name();
                Screenshot.grab(out, name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
                        message -> CompanionClient.LOG.info("rig: screenshot {} -> {}", name, message.getString()));
                go(Phase.NEXT);
            }
            case NEXT -> {
                if (wait < 10) {
                    return;
                }
                index++;
                if (index >= shots.size()) {
                    finish(mc);
                } else {
                    go(Phase.ENTER);
                }
            }
            default -> {
            }
        }
    }

    private void finish(Minecraft mc) {
        CompanionClient.LOG.info("rig: done biome shots={}", shots.size());
        phase = Phase.DONE;
        mc.stop();
    }

    private void go(Phase next) {
        phase = next;
        wait = 0;
    }

    private static boolean here(Minecraft mc, Shot shot) {
        return mc.level.dimension().identifier().toString().equals(shot.dimension());
    }

    private static String slug(String dimension) {
        int colon = dimension.indexOf(':');
        return colon < 0 ? dimension : dimension.substring(colon + 1);
    }

    /** The feet height: a number, {@code surface[+N]} over the motion-blocking height, or {@code ground[+N]} under trees and plants. */
    private static double y(Minecraft mc, Shot shot) {
        String y = shot.y().trim();
        String base = y.startsWith("surface") ? "surface" : y.startsWith("ground") ? "ground" : null;
        if (base == null) {
            return Double.parseDouble(y);
        }
        double above = y.length() > base.length() ? Double.parseDouble(y.substring(base.length())) : 0;
        int x = (int) Math.floor(shot.x());
        int z = (int) Math.floor(shot.z());
        int surface = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        if (base.equals("ground")) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, surface - 1, z);
            while (pos.getY() > mc.level.getMinY()) {
                BlockState state = mc.level.getBlockState(pos);
                if (!state.getFluidState().isEmpty()) {
                    break;
                }
                if (!state.isAir() && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS) && !state.canBeReplaced() && state.isSolid()) {
                    break;
                }
                pos.move(0, -1, 0);
            }
            surface = pos.getY() + 1;
        }
        return surface + above;
    }

    private static boolean loaded(Minecraft mc) {
        int cx = mc.player.chunkPosition().x();
        int cz = mc.player.chunkPosition().z();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (!mc.level.getChunkSource().hasChunk(cx + dx, cz + dz)) {
                    return false;
                }
            }
        }
        return true;
    }
}
