package com.customdimensions.command;

import com.customdimensions.config.ImmersiveSettings;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.config.PortalDefinition;
import com.customdimensions.facts.Json;
import com.customdimensions.immersive.DestinationGlow;
import com.customdimensions.immersive.ImmersiveProjector;
import com.customdimensions.immersive.PlayerProjectionState;
import com.customdimensions.mixin.MinecraftServerAccessor;
import com.customdimensions.portal.PortalHelper;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@code /customdim portal-light} — what every live portal is doing about
 * light, from the server's side, in one RCON round trip.
 *
 * <p>A portal has two light paths and they fail independently:
 *
 * <ul>
 *   <li><b>The aperture's own light.</b> {@code PlayerProjectionState} paints
 *       {@code minecraft:light} at the portal's configured level over the
 *       opening. It is a FAKE block sent to one viewer, so it exists in no
 *       world — {@code serverAperture} reads air with zero block light on a
 *       working portal, and that reading is the proof no {@code execute if
 *       block} probe can ever answer this question. What the client actually
 *       holds is the companion mod's {@code /state}, field
 *       {@code apertureLight}.</li>
 *   <li><b>The destination's light.</b> {@link DestinationGlow} samples the
 *       far side's light level and biome fog at the arrival column and tints
 *       the opening's PARTICLE with it. A {@code particleType} in the config
 *       bypasses it outright — a named effect carries its own colour — so
 *       {@code particleTypeOverride} being set means this path is inert for
 *       that portal however bright the far side is.</li>
 * </ul>
 *
 * <p>Nothing loads a chunk. A cold zone reports {@code "resident": false} and
 * a null aperture reading, so "dark" and "nobody could tell" stay apart.
 */
public final class PortalLightCommand {

    /** One file, overwritten each call: live state, not a keyed record. */
    static final String FILE_NAME = "portal-light.json";

    private PortalLightCommand() {
    }

    // ------------------------------------------------------------------
    // The record — plain values, no server
    // ------------------------------------------------------------------

    public record Pos(int x, int y, int z) {
    }

    /** One opening cell as the SERVER's own world holds it. */
    public record Cell(Pos at, String block, int luminance, int blockLight, int skyLight) {
    }

    public record Viewer(String player, int projectedCells, int carryOver) {
    }

    /**
     * The far side as last sampled. {@code sampledAtTick} is -1 for never,
     * which is a different fact from a destination that sampled dark.
     */
    public record Glow(long sampledAtTick, int light, int tint, double brightness,
                       int portalColour, int particleColour) {
    }

    public record Settings(boolean enabled, boolean destinationLight, double destinationTint,
                           int previewDepth, int activationRange) {
    }

    public record ZoneLight(String kind, String world, String targetWorld, String axis,
                            int configuredLightLevel, boolean painted,
                            String particleTypeOverride, boolean resident,
                            List<Cell> aperture, List<Viewer> viewers, Glow glow,
                            Settings settings) {

        public ZoneLight {
            aperture = List.copyOf(aperture);
            viewers = List.copyOf(viewers);
            if (resident == aperture.isEmpty()) {
                throw new IllegalArgumentException(
                        "a zone reads its opening exactly when its chunks are resident: "
                        + kind + " in " + world);
            }
        }
    }

    public record Report(List<ZoneLight> zones) {
    }

    // ------------------------------------------------------------------
    // Pure derivations
    // ------------------------------------------------------------------

    /** Takes the directory so the naming rule is testable without Fabric. */
    static Path artefactPath(Path directory) {
        return directory.resolve(FILE_NAME);
    }

    /**
     * The receipt. Every count carries its denominator: "0 painting" over 0
     * zones has measured nothing, and reads identically to a portal subsystem
     * that has stopped painting (TROUBLESHOOTING.md#t63).
     */
    static String summary(Report report, Path target) {
        List<ZoneLight> zones = report.zones();
        int painted = 0;
        int cold = 0;
        int glowed = 0;
        int muted = 0;
        int viewers = 0;
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (ZoneLight zone : zones) {
            if (zone.painted()) {
                painted++;
                lowest = Math.min(lowest, zone.configuredLightLevel());
                highest = Math.max(highest, zone.configuredLightLevel());
            }
            if (!zone.resident()) {
                cold++;
            }
            if (zone.glow().sampledAtTick() >= 0) {
                glowed++;
            }
            if (zone.particleTypeOverride() != null) {
                muted++;
            }
            viewers += zone.viewers().size();
        }
        String levels = painted == 0 ? "none" : lowest + ".." + highest;
        return "portal-light: " + zones.size() + " zone(s), " + painted
                + " painting light (levels " + levels + "), " + cold + " cold, "
                + glowed + " with a destination sample, " + muted
                + " with a particleType that bypasses it, " + viewers
                + " viewer hold(s) -> " + target;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private static final Comparator<Pos> BY_COORDINATE =
            Comparator.comparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z);

    private static final Comparator<ZoneLight> BY_PLACE =
            Comparator.comparing(ZoneLight::world).thenComparing(ZoneLight::kind)
                    .thenComparing(zone -> zone.aperture().stream().map(Cell::at)
                            .min(BY_COORDINATE).orElse(null),
                            Comparator.nullsFirst(BY_COORDINATE))
                    .thenComparing(ZoneLight::targetWorld);

    /** The document, given {@link Artefacts#jsonHeader}'s opening. */
    static String render(String header, Report report) {
        StringBuilder body = new StringBuilder(header);
        body.append(" \"zones\": [");
        List<ZoneLight> zones = new ArrayList<>(report.zones());
        zones.sort(BY_PLACE);
        for (int i = 0; i < zones.size(); i++) {
            body.append(i > 0 ? ",\n  " : "\n  ").append(zoneJson(zones.get(i)));
        }
        return body.append(zones.isEmpty() ? "]\n}\n" : "\n ]\n}\n").toString();
    }

    private static String zoneJson(ZoneLight zone) {
        StringBuilder json = new StringBuilder("{\"kind\": ").append(Json.quote(zone.kind()))
                .append(", \"world\": ").append(Json.quote(zone.world()))
                .append(", \"targetWorld\": ").append(Json.quote(zone.targetWorld()))
                .append(", \"axis\": ").append(Json.quote(zone.axis()))
                .append(", \"configuredLightLevel\": ")
                .append(Json.number((long) zone.configuredLightLevel()))
                .append(", \"painted\": ").append(zone.painted())
                .append(", \"particleTypeOverride\": ")
                .append(Json.quote(zone.particleTypeOverride()))
                .append(", \"resident\": ").append(zone.resident())
                .append(", \"settings\": ").append(settingsJson(zone.settings()))
                .append(", \"glow\": ").append(glowJson(zone.glow()))
                .append(", \"viewers\": [");
        for (int i = 0; i < zone.viewers().size(); i++) {
            Viewer viewer = zone.viewers().get(i);
            json.append(i > 0 ? ", " : "").append("{\"player\": ")
                    .append(Json.quote(viewer.player()))
                    .append(", \"projectedCells\": ")
                    .append(Json.number((long) viewer.projectedCells()))
                    .append(", \"carryOver\": ")
                    .append(Json.number((long) viewer.carryOver())).append('}');
        }
        json.append("], \"aperture\": ");
        if (!zone.resident()) {
            return json.append("null}").toString();
        }
        List<Cell> cells = new ArrayList<>(zone.aperture());
        cells.sort(Comparator.comparing(Cell::at, BY_COORDINATE));
        json.append('[');
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            json.append(i > 0 ? ", " : "").append("{\"at\": ").append(posJson(cell.at()))
                    .append(", \"block\": ").append(Json.quote(cell.block()))
                    .append(", \"luminance\": ").append(Json.number((long) cell.luminance()))
                    .append(", \"blockLight\": ").append(Json.number((long) cell.blockLight()))
                    .append(", \"skyLight\": ").append(Json.number((long) cell.skyLight()))
                    .append('}');
        }
        return json.append("]}").toString();
    }

    private static String settingsJson(Settings settings) {
        return "{\"enabled\": " + settings.enabled()
                + ", \"destinationLight\": " + settings.destinationLight()
                + ", \"destinationTint\": " + Json.number(settings.destinationTint())
                + ", \"previewDepth\": " + Json.number((long) settings.previewDepth())
                + ", \"activationRange\": " + Json.number((long) settings.activationRange()) + "}";
    }

    private static String glowJson(Glow glow) {
        return "{\"sampledAtTick\": " + Json.number(glow.sampledAtTick())
                + ", \"light\": " + Json.number((long) glow.light())
                + ", \"tint\": " + Json.number((long) glow.tint())
                + ", \"brightness\": " + Json.number(glow.brightness())
                + ", \"portalColour\": " + Json.number((long) glow.portalColour())
                + ", \"particleColour\": " + Json.number((long) glow.particleColour()) + "}";
    }

    private static String posJson(Pos pos) {
        return pos == null ? "null" : "[" + pos.x() + ", " + pos.y() + ", " + pos.z() + "]";
    }

    // ------------------------------------------------------------------
    // Gathering — the only half that needs a server
    // ------------------------------------------------------------------

    static int portalLight(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        Report report = new Report(zones(source.getServer()));

        Path target = artefactPath(Artefacts.canWriteDurably()
                ? Artefacts.rollingDir() : Artefacts.dir("portal-light"));
        try {
            Artefacts.write(target, render(Artefacts.jsonHeader("portal-light"), report));
        } catch (IOException e) {
            source.sendError(Text.literal("portal-light: write failed: " + e.getMessage()));
            return 0;
        }
        final String out = summary(report, target);
        source.sendFeedback(() -> Text.literal(out), false);
        return 1;
    }

    private static List<ZoneLight> zones(MinecraftServer server) {
        List<ZoneLight> out = new ArrayList<>();
        for (Map.Entry<RegistryKey<World>, ServerWorld> entry
                : ((MinecraftServerAccessor) server).getWorlds().entrySet()) {
            collect(out, entry.getValue(), "source", PortalHelper.getSourceZones(entry.getKey()));
            collect(out, entry.getValue(), "presentation",
                    PortalHelper.getPresentationZones(entry.getKey()));
            collect(out, entry.getValue(), "arrival", PortalHelper.getArrivalZones(entry.getKey()));
        }
        return out;
    }

    private static void collect(List<ZoneLight> out, ServerWorld world, String kind,
                                List<PortalHelper.PortalZone> zones) {
        for (PortalHelper.PortalZone zone : zones) {
            if (zone.interior.isEmpty()) {
                continue;
            }
            PortalDefinition definition = zone.definition;
            int level = PlayerProjectionState.apertureLightLevel(definition);
            boolean resident = readable(world, zone);
            List<Cell> aperture = new ArrayList<>();
            if (resident) {
                for (BlockPos pos : zone.interior) {
                    aperture.add(new Cell(new Pos(pos.getX(), pos.getY(), pos.getZ()),
                            Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString(),
                            world.getBlockState(pos).getLuminance(),
                            world.getLightLevel(LightType.BLOCK, pos),
                            world.getLightLevel(LightType.SKY, pos)));
                }
            }
            ImmersiveSettings settings =
                    MultiverseConfig.getInstance().getImmersiveFor(zone.targetWorld);
            String particleType = definition == null ? null : definition.getParticleType();
            out.add(new ZoneLight(kind, world.getRegistryKey().getValue().toString(),
                    zone.targetWorld.getValue().toString(), zone.axis.name(),
                    level, level > 0,
                    particleType == null || particleType.isEmpty() ? null : particleType,
                    resident, aperture,
                    viewers(zone), glow(zone, definition, settings),
                    settings(settings)));
        }
    }

    private static List<Viewer> viewers(PortalHelper.PortalZone zone) {
        List<Viewer> out = new ArrayList<>();
        for (ImmersiveProjector.Viewer viewer : ImmersiveProjector.viewersOf(zone)) {
            out.add(new Viewer(viewer.playerName(), viewer.projectedCells(), viewer.carryOver()));
        }
        out.sort(Comparator.comparing(Viewer::player));
        return out;
    }

    /**
     * The far-side sample and what it does to the opening's colour. The
     * particle colour is computed through {@link DestinationGlow#applyTo},
     * the same call the particle pass makes, so the number here is the
     * number a player sees.
     */
    private static Glow glow(PortalHelper.PortalZone zone, PortalDefinition definition,
                             ImmersiveSettings settings) {
        DestinationGlow sampled = ImmersiveProjector.glowFor(zone);
        int portalColour = PortalHelper.parseColor(definition == null ? null : definition.getColor());
        int particleColour = settings == null ? portalColour
                : sampled.applyTo(portalColour, settings.destinationTint(),
                        settings.destinationLight());
        return new Glow(ImmersiveProjector.glowSampledAt(zone), sampled.light(), sampled.tint(),
                DestinationGlow.brightness(sampled.light()), portalColour, particleColour);
    }

    private static Settings settings(ImmersiveSettings settings) {
        if (settings == null) {
            return new Settings(false, false, 0.0, 0, 0);
        }
        return new Settings(settings.enabled(), settings.destinationLight(),
                settings.destinationTint(), settings.previewDepth(), settings.activationRange());
    }

    /** Whether the opening can be read without loading anything. */
    private static boolean readable(ServerWorld world, PortalHelper.PortalZone zone) {
        for (BlockPos pos : zone.interior) {
            if (PortalHelper.residentChunk(world, pos.getX() >> 4, pos.getZ() >> 4) == null) {
                return false;
            }
        }
        return true;
    }
}
