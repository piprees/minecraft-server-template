package com.customdimensions.server.portal;

import com.customdimensions.core.portal.PortalTints;
import com.customdimensions.server.CustomDimensions;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.BlockResourceCreator;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * The tripwire states the server pack shows as tinted panes ({@link PortalTints}),
 * and the rule for sending one: the server's configured pack is this jar's own
 * pack (same SHA-1), the state was granted by polymer-blocks, and the client
 * answered that pack with a load. Anything else is sent the vanilla portal.
 */
public final class PortalTintStates {
    private static final PortalTints.Plane[] PLANES = PortalTints.Plane.values();
    private static final BlockState[][] GRANTED = new BlockState[PLANES.length][PortalTints.COUNT];
    private static volatile UUID packId;
    private static volatile String embeddedSha1;

    private PortalTintStates() {
    }

    static void register() {
        BlockResourceCreator creator = creator();
        int granted = 0;
        for (PortalTints.Plane plane : PLANES) {
            for (int tint = 0; tint < PortalTints.COUNT; tint++) {
                PortalTints.Tripwire wire = PortalTints.state(plane, tint);
                BlockState wanted = tripwire(wire);
                BlockModelType type = wire.attached() ? BlockModelType.TRIPWIRE_FLAT : BlockModelType.TRIPWIRE;
                BlockState got = creator == null ? null : creator.requestBlock(type, s -> s == wanted,
                        PolymerBlockModel.of(Identifier.parse(PortalTints.model(plane, tint))));
                GRANTED[plane.ordinal()][tint] = got;
                if (got == null) {
                    CustomDimensions.LOG.warn("portal tint {} on {} is shown as the vanilla portal: {} is held by another mod",
                            PortalTints.DYES[tint], plane.id(), wire.variant());
                } else {
                    granted++;
                }
            }
        }
        CustomDimensions.LOG.info("portal tints: {} of {} tripwire states granted", granted, PLANES.length * PortalTints.COUNT);
        embeddedSha1 = sha1(PortalTintStates.class.getClassLoader().getResourceAsStream(PortalTints.PACK_RESOURCE));
        ServerLifecycleEvents.SERVER_STARTED.register(PortalTintStates::configure);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> packId = null);
    }

    /** Arms the tints when the server offers this jar's pack, and says which way it went. */
    static void configure(MinecraftServer server) {
        packId = armedFor(server.getServerResourcePack(), embeddedSha1);
        Optional<MinecraftServer.ServerResourcePackInfo> info = server.getServerResourcePack();
        if (packId != null) {
            CustomDimensions.LOG.info("portal tints: on for clients that load pack {} ({})", packId, embeddedSha1);
        } else if (info.isEmpty()) {
            CustomDimensions.LOG.info("portal tints: off, the server offers no resource pack");
        } else {
            CustomDimensions.LOG.warn("portal tints: off, the server's pack ({}) is not this jar's ({})",
                    info.get().hash(), embeddedSha1);
        }
    }

    /** The pack id to check players against, or null when the offered pack is not {@code sha1}. */
    static UUID armedFor(Optional<MinecraftServer.ServerResourcePackInfo> info, String sha1) {
        if (info.isEmpty() || sha1 == null || info.get().hash() == null) {
            return null;
        }
        return info.get().hash().toLowerCase(Locale.ROOT).equals(sha1) ? info.get().id() : null;
    }

    /** What the client behind {@code context} is sent for a portal cell. */
    static BlockState clientState(Direction.Axis axis, int tint, BlockState vanilla, PacketContext context) {
        UUID id = packId;
        if (id == null || context == null || tint < 0 || tint >= PortalTints.COUNT) {
            return vanilla;
        }
        BlockState granted = GRANTED[plane(axis).ordinal()][tint];
        if (granted == null || !PolymerResourcePackUtils.hasPack(context, id)) {
            return vanilla;
        }
        return granted;
    }

    /** Whether the client behind {@code context} is shown tints at all. */
    public static boolean tinting(PacketContext context) {
        UUID id = packId;
        return id != null && context != null && PolymerResourcePackUtils.hasPack(context, id);
    }

    /** The tinted state a pack client is sent, for reports; null when that state was not granted. */
    public static BlockState tinted(Direction.Axis axis, int tint) {
        return GRANTED[plane(axis).ordinal()][tint];
    }

    public static boolean armed() {
        return packId != null;
    }

    public static String embeddedSha1() {
        return embeddedSha1;
    }

    static PortalTints.Plane plane(Direction.Axis axis) {
        return switch (axis) {
            case X -> PortalTints.Plane.X;
            case Z -> PortalTints.Plane.Z;
            case Y -> PortalTints.Plane.Y;
        };
    }

    static BlockState tripwire(PortalTints.Tripwire wire) {
        Map<BooleanProperty, Boolean> values = Map.of(TripWireBlock.ATTACHED, wire.attached(), TripWireBlock.DISARMED, true,
                TripWireBlock.NORTH, wire.north(), TripWireBlock.SOUTH, wire.south(), TripWireBlock.EAST, wire.east(),
                TripWireBlock.WEST, wire.west(), TripWireBlock.POWERED, wire.powered());
        BlockState state = Blocks.TRIPWIRE.defaultBlockState();
        for (Map.Entry<BooleanProperty, Boolean> e : values.entrySet()) {
            state = state.setValue(e.getKey(), e.getValue());
        }
        return state;
    }

    /** polymer-blocks' shared creator: the only one whose states its block mapper keeps genuine tripwire off. */
    private static BlockResourceCreator creator() {
        try {
            var field = PolymerBlockResourceUtils.class.getDeclaredField("CREATOR");
            field.setAccessible(true);
            return (BlockResourceCreator) field.get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            CustomDimensions.LOG.warn("portal tints: polymer-blocks' creator is out of reach ({}); every portal is sent vanilla",
                    e.toString());
            return null;
        }
    }

    static String sha1(InputStream in) {
        if (in == null) {
            CustomDimensions.LOG.warn("portal tints: the jar holds no {}", PortalTints.PACK_RESOURCE);
            return null;
        }
        try (in) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            CustomDimensions.LOG.warn("portal tints: could not hash {} ({})", PortalTints.PACK_RESOURCE, e.toString());
            return null;
        }
    }
}
