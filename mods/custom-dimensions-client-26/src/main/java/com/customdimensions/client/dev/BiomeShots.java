package com.customdimensions.client.dev;

import com.customdimensions.client.CompanionClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * with night vision, which every other shot clears; {@code radius} (chunks, default {@link #RADIUS}) widens the ring that
 * must be loaded and {@code hold} (seconds) waits that long after it is drawn, for far views and Distant Horizons LODs. As the operator, in spectator mode with the HUD hidden, at noon in
 * clear weather, it takes each entry in turn: {@code /customdim tp} into the dimension
 * when it is not already there, a teleport high over the spot, the surface read from
 * the client's own heightmap once that chunk arrives, the teleport to the shot, a wait
 * until every chunk within {@link #RADIUS} is loaded and the renderer has compiled every
 * visible section (or {@link #CAP} ticks), then a screenshot to
 * {@code <shots>/screenshots/<name>.png}. It quits after the last.
 *
 * <p>Spectator mode draws through solid blocks, so before each screenshot the camera must be clear: every block in the
 * 3×3×3 box round the eye block has an empty collision shape (fluids are clear). A camera that is not is lifted a block
 * at a time, up to {@link #LIFT}, logging {@code rig: <name> camera lifted N} at each step; one still not clear is
 * skipped with {@code rig: <name> camera inside terrain at x y z}. {@code check: true} on an entry only teleports,
 * waits for the chunks within its radius, logs {@code rig: <name> camera clear}, {@code camera clear after lift N} (and re-places the
 * camera there) or the inside-terrain line, takes the {@link FrameCensus} and no screenshot. Every screenshot is preceded by its
 * census, written to {@code <shots>/screenshots/<name>.frame.json} with a one-line caption made only from its shares.
 *
 * <p>Every frame passes {@link FrameGate} before it is saved. An entry may carry {@code alternates}: a list of objects
 * whose fields ({@code x y z yaw pitch at nightVision why}) replace the entry's for another try. A rejected frame logs
 * {@code rig: <name> rejected <reason>, trying alt N}, keeps its census and image as {@code <name>.try<N>.frame.json}
 * and {@code <name>.try<N>.rejected.png}, and the next alternate is taken; a camera inside terrain is rejected the same
 * way. When every try fails, {@code <name>.rejected.json} lists the reasons and no {@code <name>.png} is written.
 * {@code empty: true} marks a shot meant to show sky or void, which the gate's empty rule then spares;
 * {@code voidFeature: true} marks a shot whose place is a hole in the world, which may be mostly void but must still hit
 * blocks ({@link FrameGate#VOID_HITS_MIN}).
 *
 * <p>{@code pair: true} takes two frames from the same camera: {@code <name>.png} as a player sees it, without effects,
 * then {@code <name>-nv.png} with night vision ({@link #NV_SETTLE} ticks after the effect). Each frame has its own census
 * and gate result in its {@code .frame.json} ({@code nightVision}, {@code frame}, {@code gate.passed}, {@code gate.reasons});
 * the pair is kept, both frames, when either passes, and otherwise rejected as one try, its frames kept as
 * {@code <name>.try<N>.rejected.png} and {@code <name>-nv.try<N>.rejected.png}.
 */
public final class BiomeShots {
    /** Chunks round the camera that must be on the client before a shot. */
    static final int RADIUS = 6;
    /** Ticks a shot waits for chunks and meshes at most. */
    static final int CAP = 1200;
    /** Ticks after the final teleport before the renderer's state is trusted. */
    static final int SETTLE = 80;
    /** Blocks the camera may be lifted out of terrain before the shot is skipped. */
    static final int LIFT = 8;
    /** Ticks between night vision being given and the second frame of a pair. */
    static final int NV_SETTLE = 40;

    record Shot(String dimension, double x, String y, double z, float yaw, float pitch, double[] at, String name, boolean nightVision,
            int radius, int hold, boolean check, boolean empty, boolean voidFeature, boolean pair, List<Shot> alternates) {
    }

    private enum Phase { START, ENTER, HIGH, PLACE, WAIT, SHOOT, NEXT, DONE }

    private final List<Shot> shots;
    private final File out;
    private int index;
    private Phase phase = Phase.START;
    private int wait;
    private int tick;
    private int readyAt = -1;
    private double feet;
    private float yaw;
    private float pitch;
    private int lifted;
    private int attempt;
    private boolean requested;
    private FrameCensus.Census pending;
    private List<String> verdict;
    private int frame;
    private List<String> asSeen;
    private final List<String> tries = new ArrayList<>();

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
            List<Shot> alternates = new ArrayList<>();
            if (o.has("alternates")) {
                for (JsonElement a : o.getAsJsonArray("alternates")) {
                    JsonObject merged = o.deepCopy();
                    merged.remove("alternates");
                    merged.remove("at");
                    merged.remove("yaw");
                    merged.remove("pitch");
                    for (Map.Entry<String, JsonElement> f : a.getAsJsonObject().entrySet()) {
                        merged.add(f.getKey(), f.getValue());
                    }
                    alternates.add(shot(merged, List.of()));
                }
            }
            out.add(shot(o, alternates));
        }
        return out;
    }

    private static Shot shot(JsonObject o, List<Shot> alternates) {
        double[] at = null;
        if (o.has("at")) {
            JsonArray a = o.getAsJsonArray("at");
            at = new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
        }
        return new Shot(o.get("dimension").getAsString(), o.get("x").getAsDouble(), o.get("y").getAsString(),
                o.get("z").getAsDouble(), o.has("yaw") ? o.get("yaw").getAsFloat() : 0, o.has("pitch") ? o.get("pitch").getAsFloat() : 0,
                at, o.get("name").getAsString(), o.has("nightVision") && o.get("nightVision").getAsBoolean(),
                o.has("radius") ? o.get("radius").getAsInt() : RADIUS, o.has("hold") ? o.get("hold").getAsInt() : 0,
                flag(o, "check"), flag(o, "empty"), flag(o, "voidFeature"), flag(o, "pair"), alternates);
    }

    private static boolean flag(JsonObject o, String key) {
        return o.has(key) && o.get(key).getAsBoolean();
    }

    /** The entry being taken: the listed shot, or its alternate for the current try. */
    private Shot current() {
        Shot shot = shots.get(index);
        return attempt == 0 ? shot : shot.alternates().get(attempt - 1);
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
                Shot shot = current();
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
                Shot shot = current();
                command(mc, String.format(Locale.ROOT, "execute in %s run tp @s %.1f %d %.1f %.1f %.1f", shot.dimension(), shot.x(),
                        mc.level.getMaxY() - 8, shot.z(), shot.yaw(), shot.pitch()));
                go(Phase.PLACE);
            }
            case PLACE -> {
                Shot shot = current();
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
                // Each dimension keeps its own clock: noon and clear weather are set where the shot is taken.
                command(mc, "execute in " + shot.dimension() + " run time set 6000");
                command(mc, "execute in " + shot.dimension() + " run weather clear");
                command(mc, shot.nightVision() && !shot.pair() ? "effect give @s minecraft:night_vision infinite 0 true" : "effect clear @s minecraft:night_vision");
                this.feet = y;
                this.yaw = yaw;
                this.pitch = pitch;
                place(mc, shot);
                go(Phase.WAIT);
            }
            case WAIT -> {
                if (wait < SETTLE) {
                    return;
                }
                Shot shot = current();
                if (readyAt < 0) {
                    boolean ready = shot.check() ? loaded(mc, shot.radius()) : loaded(mc, shot.radius()) && mc.levelRenderer.hasRenderedAllSections();
                    if (ready || wait > CAP + shot.hold() * 20) {
                        if (!ready) {
                            CompanionClient.LOG.warn("rig: {} shot before every section was drawn", shot.name());
                        }
                        int lift = lift(mc, shot);
                        if (lift < 0) {
                            CompanionClient.LOG.warn("rig: {} camera inside terrain at {} {} {}", shot.name(), shot.x(), feet, shot.z());
                            if (shot.check()) {
                                go(Phase.NEXT);
                            } else {
                                reject(shot, List.of(String.format(Locale.ROOT, "inside terrain at %.1f %.1f %.1f", shot.x(), feet, shot.z())));
                            }
                            return;
                        }
                        if (lift > 0) {
                            if (shot.check()) {
                                CompanionClient.LOG.info("rig: {} camera clear after lift {}", shot.name(), lift);
                            }
                            lifted += lift;
                            feet += lift;
                            place(mc, shot);
                            go(Phase.WAIT);
                            return;
                        }
                        if (shot.check()) {
                            if (lifted == 0) {
                                CompanionClient.LOG.info("rig: {} camera clear", shot.name());
                            }
                            census(mc, shot);
                            go(Phase.NEXT);
                            return;
                        }
                        readyAt = wait;
                    }
                    return;
                }
                if (wait - readyAt >= shot.hold() * 20) {
                    CompanionClient.LOG.info("rig: {} ready after {} s, held {} s", shot.name(), readyAt / 20, shot.hold());
                    go(Phase.SHOOT);
                }
            }
            case SHOOT -> {
                if (wait < (frame == 1 ? NV_SETTLE : 10)) {
                    return;
                }
                Shot shot = current();
                if (!requested) {
                    requested = true;
                    verdict = null;
                    pending = FrameCensus.measure(mc, frameName(shot), shot.dimension());
                    Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> judge(shot, image));
                    return;
                }
                if (verdict == null) {
                    if (wait > CAP) {
                        CompanionClient.LOG.warn("rig: {} screenshot never arrived", shot.name());
                        go(Phase.NEXT);
                    }
                    return;
                }
                if (shot.pair() && frame == 0) {
                    asSeen = verdict;
                    frame = 1;
                    wait = 0;
                    requested = false;
                    verdict = null;
                    command(mc, "effect give @s minecraft:night_vision infinite 0 true");
                    return;
                }
                if (shot.pair()) {
                    List<String> nightVision = verdict;
                    boolean kept = FrameGate.keepsPair(asSeen, nightVision);
                    settle(shot, shot.name(), kept);
                    settle(shot, shot.name() + "-nv", kept);
                    command(mc, "effect clear @s minecraft:night_vision");
                    if (kept) {
                        CompanionClient.LOG.info("rig: pair {} kept on try {} (as seen: {}; night vision: {})", shot.name(), attempt,
                                asSeen.isEmpty() ? "passed" : String.join("; ", asSeen), nightVision.isEmpty() ? "passed" : String.join("; ", nightVision));
                        go(Phase.NEXT);
                    } else {
                        reject(shot, List.of("as seen: " + String.join("; ", asSeen), "night vision: " + String.join("; ", nightVision)));
                    }
                    return;
                }
                if (verdict.isEmpty()) {
                    go(Phase.NEXT);
                } else {
                    reject(shot, verdict);
                }
            }
            case NEXT -> {
                if (wait < 10) {
                    return;
                }
                index++;
                lifted = 0;
                attempt = 0;
                tries.clear();
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

    /** The frame being taken: the shot's name, with {@code -nv} for a pair's night-vision frame. */
    private String frameName(Shot shot) {
        return frame == 1 ? shot.name() + "-nv" : shot.name();
    }

    /**
     * Gates the screenshot against the census taken with it; saves it as the shot when it passes. A pair's frame is
     * written as {@code <frame>.try<N>} whatever its verdict, and {@link #settle} names it once both are judged.
     */
    private void judge(Shot shot, NativeImage image) {
        File dir = new File(out, "screenshots");
        String name = frameName(shot);
        try (image) {
            int w = image.getWidth(), h = image.getHeight();
            int[] pixels = image.getPixels();
            FrameGate.Measures m = pending.with(FrameGate.image(pixels, w, h, pending.fogRgb()));
            List<String> reasons = FrameGate.reasons(m, shot.empty(), shot.voidFeature());
            JsonObject gate = new JsonObject();
            gate.addProperty("try", attempt);
            gate.addProperty("luma", Math.round(m.luma() * 10) / 10.0);
            gate.addProperty("fogPercent", Math.round(m.fogPercent() * 10) / 10.0);
            gate.addProperty("passed", reasons.isEmpty());
            JsonArray why = new JsonArray();
            reasons.forEach(why::add);
            gate.add("reasons", why);
            pending.json().add("gate", gate);
            boolean nightVision = shot.pair() ? frame == 1 : shot.nightVision();
            pending.json().addProperty("nightVision", nightVision);
            if (shot.pair()) {
                pending.json().addProperty("frame", nightVision ? "with night vision" : "as a player sees it");
            }
            pending.json().addProperty("voidFeature", shot.voidFeature());
            String base = reasons.isEmpty() && !shot.pair() ? name : name + ".try" + attempt;
            FrameCensus.write(dir, base, pending.json());
            image.writeToFile(new File(dir, base + (shot.pair() ? ".pending.png" : reasons.isEmpty() ? ".png" : ".rejected.png")));
            if (reasons.isEmpty()) {
                CompanionClient.LOG.info("rig: {} census {}", name, pending.caption());
                CompanionClient.LOG.info("rig: screenshot {} passed the gate on try {} (luma {}, fog {}%)", name, attempt,
                        Math.round(m.luma()), Math.round(m.fogPercent()));
            }
            verdict = reasons;
        } catch (IOException | RuntimeException e) {
            CompanionClient.LOG.warn("rig: {} gate failed: {}", shot.name(), e.toString());
            verdict = List.of("gate failed: " + e);
        }
    }

    /** Names a judged pair frame: {@code <frame>.png} and its census when the pair is kept, {@code .try<N>.rejected.png} when not. */
    private void settle(Shot shot, String name, boolean kept) {
        File dir = new File(out, "screenshots");
        String tried = name + ".try" + attempt;
        try {
            java.nio.file.Path png = new File(dir, tried + ".pending.png").toPath();
            if (kept) {
                Files.move(new File(dir, tried + ".frame.json").toPath(), new File(dir, name + ".frame.json").toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.move(png, new File(dir, name + ".png").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(png, new File(dir, tried + ".rejected.png").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            CompanionClient.LOG.warn("rig: {} cannot name the pair frame {}: {}", shot.name(), name, e.toString());
        }
    }

    /** Logs a failed try and moves to the next alternate, or writes {@code <name>.rejected.json} when none is left. */
    private void reject(Shot shot, List<String> reasons) {
        Shot listed = shots.get(index);
        tries.add("try " + attempt + ": " + String.join("; ", reasons));
        if (attempt < listed.alternates().size()) {
            attempt++;
            CompanionClient.LOG.info("rig: {} rejected {}, trying alt {}", shot.name(), String.join("; ", reasons), attempt);
            lifted = 0;
            go(Phase.ENTER);
            return;
        }
        CompanionClient.LOG.warn("rig: {} rejected {}, no alternate left", shot.name(), String.join("; ", reasons));
        JsonObject o = new JsonObject();
        o.addProperty("name", listed.name());
        o.addProperty("dimension", listed.dimension());
        JsonArray list = new JsonArray();
        tries.forEach(list::add);
        o.add("tries", list);
        try {
            File dir = new File(out, "screenshots");
            java.nio.file.Files.createDirectories(dir.toPath());
            java.nio.file.Files.writeString(new File(dir, listed.name() + ".rejected.json").toPath(),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (IOException e) {
            CompanionClient.LOG.warn("rig: {} cannot write the rejection: {}", listed.name(), e.toString());
        }
        go(Phase.NEXT);
    }

    private void census(Minecraft mc, Shot shot) {
        try {
            String caption = FrameCensus.write(mc, new File(out, "screenshots"), shot.name(), shot.dimension());
            CompanionClient.LOG.info("rig: {} census {}", shot.name(), caption);
        } catch (IOException | RuntimeException e) {
            CompanionClient.LOG.warn("rig: {} census failed: {}", shot.name(), e.toString());
        }
    }

    private void place(Minecraft mc, Shot shot) {
        command(mc, String.format(Locale.ROOT, "execute in %s run tp @s %.2f %.2f %.2f %.1f %.1f", shot.dimension(), shot.x(), feet,
                shot.z(), yaw, pitch));
        CompanionClient.LOG.info("rig: {} at {} {} {} yaw {} pitch {}", shot.name(), shot.x(), feet, shot.z(), yaw, pitch);
    }

    /** Blocks the camera must rise to be clear: 0 when it already is, -1 when {@link #LIFT} is not enough. */
    private int lift(Minecraft mc, Shot shot) {
        for (int n = 0; n <= LIFT; n++) {
            if (n > 0) {
                CompanionClient.LOG.info("rig: {} camera lifted {}", shot.name(), n);
            }
            if (clear(mc, BlockPos.containing(shot.x(), feet + n + mc.player.getEyeHeight(), shot.z()))) {
                return n;
            }
        }
        return -1;
    }

    private static boolean clear(Minecraft mc, BlockPos eye) {
        for (BlockPos pos : BlockPos.betweenClosed(eye.offset(-1, -1, -1), eye.offset(1, 1, 1))) {
            if (!mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void finish(Minecraft mc) {
        CompanionClient.LOG.info("rig: done biome shots={}", shots.size());
        phase = Phase.DONE;
        mc.stop();
    }

    private void go(Phase next) {
        phase = next;
        frame = 0;
        wait = 0;
        readyAt = -1;
        requested = false;
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

    private static boolean loaded(Minecraft mc, int radius) {
        int cx = mc.player.chunkPosition().x();
        int cz = mc.player.chunkPosition().z();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!mc.level.getChunkSource().hasChunk(cx + dx, cz + dz)) {
                    return false;
                }
            }
        }
        return true;
    }
}
