package com.customdimensions.client.dev;

import com.customdimensions.client.ArrivalScreen;
import com.customdimensions.client.CompanionPayloads;
import com.customdimensions.client.PortalViewDeclaration;
import com.customdimensions.client.config.RealtimeControls;
import com.customdimensions.client.config.RealtimeSettings;
import com.customdimensions.client.realtime.DestinationChunks;
import com.customdimensions.client.realtime.DestinationEntities;
import com.customdimensions.client.realtime.DestinationWorlds;
import com.customdimensions.client.realtime.PortalFrames;
import com.customdimensions.client.realtime.RealtimeView;
import com.customdimensions.client.realtime.SpectatorPass;
import com.customdimensions.client.render.AmbientLift;
import com.customdimensions.client.render.ClientProjection;
import com.customdimensions.client.render.ClipTally;
import com.customdimensions.client.render.DepthReconstruction;
import com.customdimensions.client.render.LightFacts;
import com.customdimensions.client.render.ProjectionMesh;
import com.customdimensions.client.render.DestinationActors;
import com.customdimensions.client.render.ProjectionRenderer;
import com.customdimensions.client.render.ProjectionStore;
import com.customdimensions.client.render.QuadCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * What the client can be asked, as facts rather than log lines. Render thread
 * only — every field here is read straight off live client state.
 */
final class DevState {

    /** Floats per vertex times four vertices. */
    private static final int FLOATS_PER_QUAD = QuadCapture.STRIDE * 4;

    /**
     * Cells the light profile walks out of the opening, towards the viewer's
     * side. Block light falls one level per step, so a portal at level 11 is
     * still above zero at the far end and the whole gradient is one reading.
     */
    private static final int PROFILE_CELLS = 8;

    /** Blocks each way from the camera {@link #sourceEntities} reaches. */
    private static final double SOURCE_ENTITY_REACH = 24.0;

    /** Rows {@link #sourceEntities} prints, however many stand in reach. */
    private static final int SOURCE_ENTITY_CAP = 24;

    private DevState() {}

    static String json(MinecraftClient client, int tick) {
        return Json.obj()
                .bool("ok", true)
                .num("tick", tick)
                .raw("player", player(client))
                .raw("client", clientState(client))
                .raw("projections", projections(client))
                .raw("realtime", realtime(client))
                .toString();
    }

    /**
     * The real-time path's own counters. {@code projections} above is the
     * server-drawn slab, so the two together say which path a portal is on
     * without reading a log at a level the client does not print.
     */
    private static String realtime(MinecraftClient client) {
        RealtimeSettings settings = RealtimeControls.settings();
        Map<Identifier, Integer> held = DestinationWorlds.loadedCounts();
        Map<Identifier, Integer> received = DestinationChunks.counts();
        // Every destination anything is known about: one holding entities and
        // no chunks is the shape of a feed half-arrived, and keying on chunks
        // alone hides it entirely.
        Set<Identifier> named = new LinkedHashSet<>(received.keySet());
        named.addAll(held.keySet());
        named.addAll(DestinationEntities.counts().keySet());
        StringBuilder worlds = new StringBuilder("[");
        boolean first = true;
        for (Identifier destination : named) {
            worlds.append(first ? "" : ",").append(Json.obj()
                    .str("dimension", destination.toString())
                    .bool("worldStanding", held.containsKey(destination))
                    .num("chunksInWorld", held.getOrDefault(destination, 0))
                    .num("chunksReceived", received.getOrDefault(destination, 0))
                    .num("renderedSections", DestinationWorlds.renderedSections(destination))
                    .num("entities", DestinationEntities.count(destination))
                    .raw("entityIds", Json.strings(DestinationEntities.heldIds(destination)))
                    .raw("entityList", Json.strings(DestinationEntities.listing(destination)))
                    .toString());
            first = false;
        }
        return Json.obj()
                .bool("renderClientSidePortals", settings.renderClientSidePortals())
                .bool("renderServerSidePortals", settings.renderServerSidePortals())
                .bool("spectatorPass", settings.spectatorPass())
                .bool("apertureBackdrop", settings.apertureBackdrop())
                .bool("apertureTerrain", settings.apertureTerrain())
                .bool("apertureFarStamp", settings.apertureFarStamp())
                .bool("apertureFarStampEarly", settings.apertureFarStampEarly())
                .bool("apertureMeshDepth", settings.apertureMeshDepth())
                .bool("apertureUnshadedDestination", settings.apertureUnshadedDestination())
                .num("apertureBackdropGain", settings.apertureBackdropGain())
                .str("apertureStamps", ProjectionRenderer.stampSummary())
                .str("apertureRenderUs", ProjectionRenderer.lastCost())
                .str("realtimeBuildUs", RealtimeView.buildCost())
                .bool("effectiveServerSide", settings.effectiveServerSide())
                .bool("clientSideRefused", PortalViewDeclaration.refused())
                .str("clientSideRefusal", PortalViewDeclaration.reason())
                .num("maxRenderDistance", settings.maxRenderDistance())
                .num("frames", PortalFrames.count())
                .num("slabProjections", ProjectionStore.count())
                .num("destinationWorlds", DestinationWorlds.count())
                .num("destinationChunks", DestinationChunks.total())
                .num("renderedSections", DestinationWorlds.renderedSections())
                .num("destinationEntities", DestinationEntities.total())
                // Monotonic. Two readings subtract to a count over a window;
                // zero means the path never ran.
                .num("entitySnapshots", DestinationEntities.snapshots())
                .num("entitySnapshotsDropped", DestinationEntities.snapshotsDropped())
                .num("entitiesSpawned", DestinationEntities.spawned())
                .num("entitiesMoved", DestinationEntities.moved())
                .num("entitiesRemoved", DestinationEntities.removed())
                .num("entitiesRefused", DestinationEntities.refused())
                .num("entityHandovers", DestinationEntities.handovers())
                .num("entityHandoversDropped", DestinationEntities.handoversDropped())
                .num("apertureEntities", DestinationActors.entities())
                .num("apertureBlockEntities", DestinationActors.blockEntities())
                .num("apertureQuadsIn", DestinationActors.quadsIn())
                .num("apertureQuadsOut", DestinationActors.quadsOut())
                .str("apertureEntityLight", DestinationActors.lastLight())
                .raw("apertureSample", apertureSample(client == null ? null : client.world))
                .raw("sourceEntityList", Json.strings(sourceEntities(client)))
                .num("spectatorPasses", SpectatorPass.passes())
                .num("spectatorLastUs", SpectatorPass.lastMicros())
                .num("spectatorMeanUs", SpectatorPass.meanMicros())
                .bool("spectatorDisabled", SpectatorPass.disabled())
                .str("spectatorRefusal", SpectatorPass.refusal())
                .num("spectatorBoundBefore", SpectatorPass.boundBefore())
                .num("spectatorBoundAfter", SpectatorPass.boundAfter())
                .num("spectatorRebinds", SpectatorPass.rebinds())
                .raw("worlds", worlds.append(']').toString())
                .toString();
    }

    /**
     * What the SOURCE world holds near the camera, as {@code id type x,y,z} —
     * the near half of a crossing, so one reading says which side of a portal
     * is holding an entity and which side is not.
     */
    private static List<String> sourceEntities(MinecraftClient client) {
        List<String> out = new ArrayList<>();
        ClientWorld world = client == null ? null : client.world;
        ClientPlayerEntity player = client == null ? null : client.player;
        if (world == null || player == null) {
            return out;
        }
        Box box = player.getBoundingBox().expand(SOURCE_ENTITY_REACH);
        for (Entity entity : nearestFirst(world.getOtherEntities(player, box),
                entity -> entity.squaredDistanceTo(player), SOURCE_ENTITY_CAP)) {
            out.add(String.format("%d %s %.3f,%.3f,%.3f", entity.getId(),
                    entity.getType().getUntranslatedName(),
                    entity.getX(), entity.getY(), entity.getZ()));
        }
        return out;
    }

    /**
     * The {@code cap} nearest, nearest first. The world hands entities back in
     * section order, so capping that order keeps whatever sits furthest into the
     * box and drops what is standing beside the camera — which is the entity any
     * crossing is about.
     */
    static <T> List<T> nearestFirst(Collection<T> items, ToDoubleFunction<T> distanceSq, int cap) {
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingDouble(distanceSq));
        return cap < sorted.size() ? new ArrayList<>(sorted.subList(0, cap)) : sorted;
    }

    /**
     * Where a screen-space pass puts the middle of the last opening drawn, and
     * what the SOURCE world holds there.
     *
     * <p>{@code windowZ} is read back from the depth buffer where the opening's
     * centre landed; {@code sliceZ} is what the same pixel reports when the
     * destination is drawn inside the compressed range instead, which pins the
     * whole opening to one block at the doorway.
     */
    private static String apertureSample(ClientWorld world) {
        DepthReconstruction.Sample sample = DepthReconstruction.last();
        if (sample == null) {
            return Json.obj().str("absent", "no portal drawn inside a depth slice").toString();
        }
        BlockPos pos = new BlockPos(sample.blockX(), sample.blockY(), sample.blockZ());
        Json.Obj out = Json.obj()
                // Six decimals: the whole slice is about a thousandth of the
                // depth range, and three prints every value the same.
                .raw("windowZ", String.format("%.6f", sample.windowZ()))
                .num("ndcX", sample.ndcX())
                .num("ndcY", sample.ndcY())
                .num("distance", sample.distance())
                .raw("sliceZ", String.format("%.6f", sample.sliceZ()))
                .num("sliceDistance", sample.sliceDistance())
                .num("farDistance", sample.farDistance())
                .raw("farWindowZ", String.format("%.6f", ProjectionRenderer.FAR_STAMP_DEPTH))
                .raw("cameraRelative", Json.numbers(sample.x(), sample.y(), sample.z()))
                .raw("at", Json.numbers(pos.getX(), pos.getY(), pos.getZ()));
        if (world == null) {
            return out.str("absent", "no world").toString();
        }
        return out
                .str("id", Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString())
                .bool("skyVisible", world.isSkyVisible(pos))
                .num("sky", world.getLightLevel(LightType.SKY, pos))
                .num("block", world.getLightLevel(LightType.BLOCK, pos))
                .toString();
    }

    /**
     * With no player this is {@code {"absent": "..."}} carrying the reason, never
     * JSON null — so {@code .player == null} is not the test for "no player".
     */
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
                        player.getHeadYaw(), player.getBodyYaw()),
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
                .bool("hudHidden", client.options.hudHidden)
                .toString();
    }

    /** This mod's own render state: one entry per portal the client holds. */
    private static String projections(MinecraftClient client) {
        ClientWorld world = client == null ? null : client.world;
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
                    .raw("clip", clip(aperture))
                    .raw("destLight", light(LightFacts.ofPacked(projection.payload().light())))
                    .raw("meshLight", light(meshLight(mesh)))
                    .raw("ambient", ambient(projection, mesh))
                    .raw("tints", tints(projection))
                    .raw("apertureLight", apertureLight(world, projection))
                    .raw("lightProfile", lightProfile(world, projection))
                    .toString());
            first = false;
        }
        return out.append(']').toString();
    }

    /**
     * The per-column tints as they arrived: how many distinct triples the
     * palette holds and how many columns carry one. More than one entry is a
     * view spanning more than one biome; a single entry over every column is
     * the whole volume wearing one biome's grass.
     */
    private static String tints(ClientProjection projection) {
        CompanionPayloads.Projection payload = projection.payload();
        int columns = payload.columnCount();
        int carried = 0;
        Set<Integer> grasses = new LinkedHashSet<>();
        for (int column = 0; column < columns; column++) {
            int grass = payload.columnTint(column, CompanionPayloads.Projection.TINT_GRASS);
            if (grass >= 0) {
                carried++;
            }
            grasses.add(grass);
        }
        List<String> swatches = new ArrayList<>();
        for (int grass : grasses) {
            swatches.add(grass < 0 ? "none" : String.format("#%06X", grass & 0xFFFFFF));
        }
        return Json.obj()
                .num("columns", columns)
                .num("carried", carried)
                .num("paletteEntries",
                        payload.tintPalette().length / CompanionPayloads.Projection.TINT_CHANNELS)
                .num("distinctGrass", grasses.size())
                .raw("grass", Json.strings(swatches))
                .toString();
    }

    /**
     * The destination's ambient light beside the source's, and the lift between
     * them at three levels. {@code destination} of -1 with a built mesh means
     * the value never reached the payload; an equal pair with {@code lift}
     * reading {@code 0>0} means it arrived and the two dimensions agree.
     */
    private static String ambient(ClientProjection projection, ProjectionMesh mesh) {
        if (mesh == null) {
            return Json.obj().str("absent", "no mesh").toString();
        }
        float destination = projection.payload().ambientLight();
        return Json.obj()
                .num("destination", destination)
                .num("source", mesh.sourceAmbient())
                .str("lift", AmbientLift.label(destination, mesh.sourceAmbient()))
                .toString();
    }

    /** Every layer's vertices as one reading — the mesh's own lightmap levels. */
    private static LightFacts meshLight(ProjectionMesh mesh) {
        if (mesh == null) {
            return LightFacts.EMPTY;
        }
        LightFacts total = LightFacts.EMPTY;
        for (ProjectionMesh.Layer layer : mesh.layers()) {
            LightFacts one = LightFacts.ofVertices(layer.data(), layer.floats(), QuadCapture.STRIDE);
            total = merge(total, one);
        }
        return total;
    }

    private static LightFacts merge(LightFacts a, LightFacts b) {
        if (a.cells() == 0) {
            return b;
        }
        if (b.cells() == 0) {
            return a;
        }
        return new LightFacts(a.cells() + b.cells(), a.lit() + b.lit(),
                Math.min(a.blockMin(), b.blockMin()), Math.max(a.blockMax(), b.blockMax()),
                a.blockSum() + b.blockSum(),
                Math.min(a.skyMin(), b.skyMin()), Math.max(a.skyMax(), b.skyMax()),
                a.skySum() + b.skySum());
    }

    private static String light(LightFacts facts) {
        return Json.obj()
                .num("cells", facts.cells())
                .num("lit", facts.lit())
                .num("blockMin", facts.blockMin())
                .num("blockMax", facts.blockMax())
                .num("blockMean", facts.blockMean())
                .num("skyMin", facts.skyMin())
                .num("skyMax", facts.skyMax())
                .num("skyMean", facts.skyMean())
                .toString();
    }

    /**
     * What this client's OWN world holds at the opening: the block it is
     * showing there and the light it is computing from it.
     *
     * <p>The aperture's light source is a fake block sent to one player, so it
     * exists nowhere but here — no server-side probe can see it, and this is
     * the only place the question can be asked.
     */
    private static String apertureLight(ClientWorld world, ClientProjection projection) {
        if (world == null) {
            return Json.obj().str("absent", "no world").toString();
        }
        StringBuilder cells = new StringBuilder("[");
        boolean first = true;
        for (BlockPos pos : projection.payload().aperture()) {
            cells.append(first ? "" : ",").append(Json.obj()
                    .raw("at", Json.numbers(pos.getX(), pos.getY(), pos.getZ()))
                    .str("id", Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString())
                    .num("luminance", world.getBlockState(pos).getLuminance())
                    .num("block", world.getLightLevel(LightType.BLOCK, pos))
                    .num("sky", world.getLightLevel(LightType.SKY, pos))
                    .toString());
            first = false;
        }
        return cells.append(']').toString();
    }

    /**
     * Block light stepping out of the opening towards the viewer's side, which
     * is where a player stands. A portal that is lighting the source world
     * reads as a gradient falling one level per block; one that is not reads as
     * whatever the source world was already doing.
     */
    private static String lightProfile(ClientWorld world, ClientProjection projection) {
        if (world == null) {
            return "[]";
        }
        Direction towardsViewer = projection.normal().getOpposite();
        BlockPos.Mutable probe = projection.apertureCentre().mutableCopy();
        StringBuilder out = new StringBuilder("[");
        for (int step = 0; step < PROFILE_CELLS; step++) {
            out.append(step == 0 ? "" : ",").append(Json.obj()
                    .num("d", step)
                    .raw("at", Json.numbers(probe.getX(), probe.getY(), probe.getZ()))
                    .str("id", Registries.BLOCK.getId(world.getBlockState(probe).getBlock()).toString())
                    .num("block", world.getLightLevel(LightType.BLOCK, probe))
                    .num("sky", world.getLightLevel(LightType.SKY, probe))
                    .toString());
            probe.move(towardsViewer);
        }
        return out.append(']').toString();
    }

    /**
     * What the clip did on the last frame it sampled: the camera in the
     * volume's own space, and per layer what went in, what came out, and which
     * edge of the opening cut the rest.
     */
    private static String clip(BlockPos aperture) {
        ClipTally.Portal portal = ClipTally.of(aperture);
        if (portal == null) {
            return "null";
        }
        StringBuilder layers = new StringBuilder("[");
        boolean first = true;
        for (ClipTally.Layer layer : portal.layers()) {
            layers.append(first ? "" : ",").append(Json.obj()
                    .str("layer", layer.layer())
                    .num("quadsIn", layer.quadsIn())
                    .num("emitted", layer.emitted())
                    .raw("rejectedBy", Json.numbers(layer.rejectedBy()[0], layer.rejectedBy()[1],
                            layer.rejectedBy()[2], layer.rejectedBy()[3]))
                    .toString());
            first = false;
        }
        return Json.obj()
                .raw("cam", Json.numbers(portal.cam()[0], portal.cam()[1], portal.cam()[2]))
                .num("camToPlane", portal.camToPlane())
                .num("planes", portal.planes())
                .raw("layers", layers.append(']').toString())
                .toString();
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
