package com.customdimensions.client.view;

import com.customdimensions.client.CompanionClient;
import com.customdimensions.client.CompanionPayloads;
import com.customdimensions.client.feed.Feed;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * The far side's mobs and projectiles, drawn in the opening by their own
 * renderers. Each fed entity is a client-side entity built as vanilla's packet
 * handler builds one ({@code EntityType.create}, {@code recreateFromPacket}, the
 * snapshot's synched data), never added to a level and never ticked; it is
 * placed on its {@link Motion.Track} every frame, extracted by the entity render
 * dispatcher, lit from the fed chunk under it, and submitted through a
 * {@link ClippedCollector}, which clips and squashes its models exactly as the
 * far side's blocks are. Read and written on the client thread only.
 *
 * <p>Any failure switches the entity pass off for the session with one log
 * line; the far side's blocks keep drawing.
 */
public final class EntityPass {
    static final double FOG_START = PortalRenderer.DEPTH * 0.55;
    static final double FOG_END = PortalRenderer.DEPTH;

    /** The opening an entity is drawn through, as its clip needs it. */
    record View(double[][] planes, double[] eye, int normalAxis, double d0, int fog) {
    }

    /** One entity's draw through one opening this frame; its callbacks add the quads in and those kept. */
    static final class Frame {
        final View view;
        final Identifier type;
        int quadsIn;
        int quadsOut;
        int dropped;

        Frame(View view, Identifier type) {
            this.view = view;
            this.type = type;
        }

        void render(PoseStack.Pose pose, VertexConsumer vc, Consumer<ClipConsumer> body) {
            if (failed) {
                return;
            }
            ClipConsumer c = new ClipConsumer(vc, pose, view.planes, view.eye, view.normalAxis, view.d0,
                    PortalRenderer.EPSILON, view.fog, FOG_START, FOG_END);
            try {
                body.accept(c);
                c.finish();
            } catch (Throwable t) {
                fail(t);
            }
            quadsIn += c.quadsIn;
            quadsOut += c.quadsOut;
        }
    }

    /** What the last settled frame drew through one opening: when, how many entities, and of which types. */
    public record Seen(long nanos, int count, Map<String, Integer> types, int quads) {
    }

    private static final class Actor {
        final Entity entity;
        final Identifier type;
        final Motion.Track track;
        Feed.Shown shown;
        double[] lastTick;

        Actor(Entity entity, Identifier type) {
            this.entity = entity;
            this.type = type;
            this.track = new Motion.Track(entity instanceof Projectile);
        }
    }

    private static final Map<Identifier, Map<Integer, Actor>> ACTORS = new HashMap<>();
    private static final Map<BlockPos, List<Frame>> PENDING = new HashMap<>();
    private static final Map<BlockPos, Long> PENDING_AT = new HashMap<>();
    private static final Map<BlockPos, Seen> DRAWN = new HashMap<>();
    private static final Set<Identifier> UNMADE = new HashSet<>();
    private static boolean failed;

    public static long entitiesDrawn;
    public static long entitiesUnmade;

    private EntityPass() {
    }

    public static boolean failed() {
        return failed;
    }

    public static void reset() {
        ACTORS.clear();
        PENDING.clear();
        PENDING_AT.clear();
        DRAWN.clear();
        UNMADE.clear();
    }

    /** What the last settled frame drew through the opening at this aperture origin, or null. */
    public static Seen drawn(BlockPos apertureOrigin) {
        return DRAWN.get(apertureOrigin);
    }

    /** Where a fed entity of this destination is drawn now, in destination coordinates, or null when it has no actor. */
    public static double[] drawnPosition(Identifier destination, int id) {
        Map<Integer, Actor> actors = ACTORS.get(destination);
        Actor a = actors == null ? null : actors.get(id);
        return a == null || !a.track.started() ? null : a.track.position(ticksNow());
    }

    private static void fail(Throwable t) {
        if (!failed) {
            failed = true;
            CompanionClient.LOG.error("companion-off: the entity pass failed, the far side draws without its entities", t);
        }
    }

    private static double ticksNow() {
        return System.nanoTime() / 50_000_000.0;
    }

    /**
     * Records the previous frame's draws, whose callbacks have all run by now,
     * and drops the actors of destinations the feed no longer holds.
     */
    static void settle() {
        for (Map.Entry<BlockPos, List<Frame>> e : PENDING.entrySet()) {
            int count = 0;
            int quads = 0;
            Map<String, Integer> types = new TreeMap<>();
            for (Frame f : e.getValue()) {
                if (f.quadsOut > 0) {
                    count++;
                    quads += f.quadsOut;
                    types.merge(String.valueOf(f.type), 1, Integer::sum);
                }
            }
            entitiesDrawn += count;
            DRAWN.put(e.getKey(), new Seen(PENDING_AT.getOrDefault(e.getKey(), System.nanoTime()), count, types, quads));
        }
        PENDING.clear();
        PENDING_AT.clear();
        ACTORS.keySet().removeIf(id -> Feed.destination(id) == null);
    }

    /** Submits the fed entities of {@code d} seen through one opening this frame. */
    static void submit(LevelRenderContext ctx, ClientLevel level, CompanionPayloads.PortalFrame frame,
                       Feed.Destination d, DimensionType type, Opening op, double[] eye, double d0,
                       double[][] planes, int fog) {
        if (failed || d.entities.isEmpty()) {
            return;
        }
        try {
            draw(ctx, level, frame, d, type, op, eye, d0, planes, fog);
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void draw(LevelRenderContext ctx, ClientLevel level, CompanionPayloads.PortalFrame frame,
                             Feed.Destination d, DimensionType type, Opening op, double[] eye, double d0,
                             double[][] planes, int fog) {
        Minecraft mc = Minecraft.getInstance();
        Map<Integer, Actor> actors = sync(level, frame.destination(), d);
        double now = ticksNow();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        View view = new View(planes, eye, op.normalAxis(), d0, fog);
        BlockPos origin = frame.apertureOrigin();
        List<Frame> frames = PENDING.computeIfAbsent(origin, k -> new ArrayList<>());
        PENDING_AT.put(origin, System.nanoTime());
        for (Actor a : actors.values()) {
            if (!a.track.started()) {
                continue;
            }
            Entity e = a.entity;
            double[] p = a.track.position(now);
            double[] s = ModelClip.source(p, frame.dx(), frame.dy(), frame.dz());
            double[] b = ModelClip.bounds(s, e.getBbWidth(), e.getBbHeight());
            if (ModelClip.outside(planes, new double[] {b[0], b[1], b[2]}, b[3])) {
                continue;
            }
            place(a, p, now);
            EntityRenderState state = dispatcher.extractEntity(e, partial);
            state.lightCoords = PortalRenderer.light(d, type, (int) Math.floor(p[0]), (int) Math.floor(p[1] + 0.5),
                    (int) Math.floor(p[2]), 0);
            Frame f = new Frame(view, a.type);
            frames.add(f);
            dispatcher.submit(state, ctx.levelState().cameraRenderState, s[0] - eye[0], s[1] - eye[1], s[2] - eye[2],
                    new PoseStack(), new ClippedCollector(ctx.submitNodeCollector(), ctx.submitNodeCollector(),
                            ctx.poseStack(), f));
        }
    }

    private static void place(Actor a, double[] p, double now) {
        Entity e = a.entity;
        float yaw = a.track.yaw(now);
        float pitch = a.track.pitch(now);
        e.setYRot(yaw);
        e.setXRot(pitch);
        e.setPos(p[0], p[1], p[2]);
        e.setOldPosAndRot();
        if (e instanceof LivingEntity living) {
            float head = a.track.head(now);
            living.setYHeadRot(head);
            living.yHeadRotO = head;
            living.setYBodyRot(yaw);
            living.yBodyRotO = yaw;
        }
    }

    /** The actors of one destination, made, moved on and dropped to match what the feed holds. */
    private static Map<Integer, Actor> sync(ClientLevel level, Identifier destination, Feed.Destination d) {
        Map<Integer, Actor> actors = ACTORS.computeIfAbsent(destination, k -> new HashMap<>());
        actors.keySet().removeIf(id -> !d.entities.containsKey(id));
        double now = ticksNow();
        for (Feed.Shown s : d.entities.values()) {
            Actor a = actors.get(s.id());
            if (a != null && a.entity.level() != level) {
                a = null;
            }
            if (a == null || !a.type.equals(s.type())) {
                a = make(level, s);
                if (a == null) {
                    actors.remove(s.id());
                    continue;
                }
                actors.put(s.id(), a);
            }
            if (a.shown != s) {
                a.shown = s;
                if (!s.data().isEmpty()) {
                    a.entity.getEntityData().assignValues(s.data());
                }
                var v = s.packet().getMovement();
                a.track.update(new double[] {s.x(), s.y(), s.z()}, new double[] {v.x, v.y, v.z},
                        a.entity.getGravity(), s.yRot(), s.xRot(), s.packet().getYHeadRot(), now);
            }
        }
        return actors;
    }

    private static Actor make(ClientLevel level, Feed.Shown s) {
        if (UNMADE.contains(s.type())) {
            return null;
        }
        Entity e;
        try {
            e = s.packet().getType().create(level, EntitySpawnReason.LOAD);
            if (e != null) {
                e.recreateFromPacket(s.packet());
            }
        } catch (RuntimeException ex) {
            e = null;
        }
        if (e == null) {
            UNMADE.add(s.type());
            entitiesUnmade++;
            CompanionClient.LOG.info("companion-entities: {} cannot be made on the client, not drawn", s.type());
            return null;
        }
        return new Actor(e, s.type());
    }

    /** Every client tick: each actor ages and a living one swings its limbs by the distance it was drawn moving. */
    public static void tick() {
        if (failed || ACTORS.isEmpty()) {
            return;
        }
        double now = ticksNow();
        try {
            for (Map<Integer, Actor> actors : ACTORS.values()) {
                for (Iterator<Actor> it = actors.values().iterator(); it.hasNext(); ) {
                    Actor a = it.next();
                    a.entity.tickCount++;
                    if (!a.track.started() || !(a.entity instanceof LivingEntity living)) {
                        continue;
                    }
                    double[] p = a.track.position(now);
                    if (a.lastTick != null) {
                        double dx = p[0] - a.lastTick[0];
                        double dz = p[2] - a.lastTick[2];
                        living.walkAnimation.update((float) Math.min(Math.sqrt(dx * dx + dz * dz) * 4.0, 1.0), 0.4f,
                                1.0f);
                    }
                    a.lastTick = p;
                }
            }
        } catch (Throwable t) {
            fail(t);
        }
    }
}
