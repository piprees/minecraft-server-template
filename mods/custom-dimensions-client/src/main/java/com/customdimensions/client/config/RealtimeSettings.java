package com.customdimensions.client.config;

import com.customdimensions.client.dev.Json;
import com.customdimensions.client.dev.JsonReader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The player's controls over the portal view, as held on disk.
 *
 * <p>No Minecraft types on purpose: everything here is parsed, clamped and
 * rendered without a bootstrapped game, so the rules are unit-testable.
 *
 * <p>Two toggles describe the same far side: {@code renderClientSidePortals} is
 * the enhanced local render, {@code renderServerSidePortals} is the block slab
 * the server streams. They are redundant, so the local render wins and
 * {@link #effectiveServerSide()} is the single value that goes on the wire.
 * The other fields bound the local render and never leave this client.
 *
 * <p>{@code chosen} names the keys the player actually set. The file lists
 * every key whatever its provenance, so it stays hand-editable; that set is
 * the only thing separating a value somebody picked from one nobody did.
 */
public record RealtimeSettings(
        boolean renderClientSidePortals,
        int maxRenderDistance,
        boolean distantHorizons,
        boolean renderServerSidePortals,
        boolean spectatorPass,
        boolean apertureBackdrop,
        boolean apertureTerrain,
        boolean apertureFarStamp,
        boolean apertureFarStampEarly,
        boolean apertureMeshDepth,
        boolean apertureUnshadedDestination,
        double apertureBackdropGain,
        boolean apertureUnshadedBackdrop,
        int apertureAtlasFilter,
        int viewDepth,
        Set<String> chosen) {

    /**
     * The schema this version writes. A file below it is migrated on read, and
     * a key that file did not record as chosen takes the current default —
     * which is the only way a changed default reaches a file that names it.
     */
    public static final int CONFIG_VERSION = 3;

    static final String KEY_CHOSEN = "chosen";
    static final String KEY_RENDER_CLIENT_SIDE_PORTALS = "renderClientSidePortals";
    static final String KEY_MAX_RENDER_DISTANCE = "maxRenderDistance";
    static final String KEY_DISTANT_HORIZONS = "distantHorizons";
    static final String KEY_RENDER_SERVER_SIDE_PORTALS = "renderServerSidePortals";
    static final String KEY_SPECTATOR_PASS = "spectatorPass";
    static final String KEY_APERTURE_BACKDROP = "apertureBackdrop";
    static final String KEY_APERTURE_TERRAIN = "apertureTerrain";
    static final String KEY_APERTURE_FAR_STAMP = "apertureFarStamp";
    static final String KEY_APERTURE_FAR_STAMP_EARLY = "apertureFarStampEarly";
    static final String KEY_APERTURE_MESH_DEPTH = "apertureMeshDepth";
    static final String KEY_APERTURE_UNSHADED_DESTINATION = "apertureUnshadedDestination";
    static final String KEY_APERTURE_BACKDROP_GAIN = "apertureBackdropGain";
    static final String KEY_APERTURE_UNSHADED_BACKDROP = "apertureUnshadedBackdrop";
    static final String KEY_APERTURE_ATLAS_FILTER = "apertureAtlasFilter";
    static final String KEY_VIEW_DEPTH = "viewDepth";

    /** The field name a schema below 1 held the local render under. */
    private static final String LEGACY_KEY_ENABLED = "enabled";

    /**
     * The enhanced portal is what installing the mod gets you. Turning it off
     * is a choice the player makes.
     */
    public static final boolean DEFAULT_RENDER_CLIENT_SIDE_PORTALS = true;

    /**
     * On, so it is already in effect the moment the local render stops:
     * nothing has to invent a fallback, the slab simply resumes.
     */
    public static final boolean DEFAULT_RENDER_SERVER_SIDE_PORTALS = true;

    /** Chunks of the destination the local view is allowed to reach. */
    public static final int DEFAULT_RENDER_DISTANCE = 16;
    public static final int MIN_RENDER_DISTANCE = 2;
    public static final int MAX_RENDER_DISTANCE = 32;

    /** Extend the far side past the render distance where DH is installed. */
    public static final boolean DEFAULT_DISTANT_HORIZONS = true;

    /**
     * Off. The spectator pass draws the far side through a second
     * {@code WorldRenderer}, which calls {@code WorldRenderer.render} a second
     * time in one frame and so drives a shader pack's whole pipeline twice
     * ({@code TROUBLESHOOTING.md#t97}). The aperture path draws the same far
     * side inside the source frame's own pass.
     */
    public static final boolean DEFAULT_SPECTATOR_PASS = false;

    /**
     * The two halves of what the opening draws, separable so a portal showing
     * the wrong thing can be bisected without a rebuild: the fog-coloured quad
     * behind the far side, and the meshed destination in front of it.
     */
    public static final boolean DEFAULT_APERTURE_BACKDROP = true;
    public static final boolean DEFAULT_APERTURE_TERRAIN = true;

    /**
     * The opening's depth rewritten far at the end of the frame, after every
     * draw that depth-tests, so a shader pack's composite passes read the far
     * end of the captured volume rather than the portal surface.
     */
    public static final boolean DEFAULT_APERTURE_FAR_STAMP = true;

    /**
     * The same far depth at the portal's own draw, where a pack's deferred
     * programs and its pre-translucent depth copy read it. A mixin restores the
     * source world's depth before anything tests against it.
     */
    public static final boolean DEFAULT_APERTURE_FAR_STAMP_EARLY = true;

    /**
     * The meshed destination drawn once more, depth only, at its own true depth
     * after the far stamp. The flat stamps alone fog near destination terrain;
     * this is what keeps the near field, so the three move together.
     */
    public static final boolean DEFAULT_APERTURE_MESH_DEPTH = true;

    /**
     * The destination lit from its own levels rather than the source world's
     * lightmap and shadow map: free with no pack, and it removes a
     * yaw-dependent shadow ramp under one ({@code TROUBLESHOOTING.md#t104}).
     */
    public static final boolean DEFAULT_APERTURE_UNSHADED_DESTINATION = true;

    /**
     * Compensation, not correction: an unshaded backdrop is the authored fog
     * colour at full value, and a pack's bloom takes a near-white quad past
     * saturation. 1.0 changes nothing, which is right with no pack.
     */
    public static final double DEFAULT_APERTURE_BACKDROP_GAIN = 1.0;

    /**
     * Off: the authored sky colour at full value is exactly right with no pack
     * and blooms under one, and the same value is emitted in both, so no
     * constant serves both. {@link #DEFAULT_APERTURE_BACKDROP_GAIN} is the knob.
     */
    public static final boolean DEFAULT_APERTURE_UNSHADED_BACKDROP = false;

    /**
     * -1 inherits whatever filter the shared block atlas was last left with; 0
     * is plain, 1 mipmapped. The backdrop's white.png carries no mip levels and
     * takes plain under either.
     */
    public static final int DEFAULT_APERTURE_ATLAS_FILTER = -1;

    /**
     * How far past the opening the local view reaches, in blocks. Declared to
     * the server, which sizes the chunk core it feeds from it, so the depth is
     * one quantity held here rather than a constant on each side.
     */
    public static final int DEFAULT_VIEW_DEPTH = 64;
    public static final int MIN_VIEW_DEPTH = 32;
    public static final int MAX_VIEW_DEPTH = 192;

    public static final RealtimeSettings DEFAULTS = new RealtimeSettings(
            DEFAULT_RENDER_CLIENT_SIDE_PORTALS, DEFAULT_RENDER_DISTANCE,
            DEFAULT_DISTANT_HORIZONS, DEFAULT_RENDER_SERVER_SIDE_PORTALS,
            DEFAULT_SPECTATOR_PASS, DEFAULT_APERTURE_BACKDROP, DEFAULT_APERTURE_TERRAIN,
            DEFAULT_APERTURE_FAR_STAMP, DEFAULT_APERTURE_FAR_STAMP_EARLY,
            DEFAULT_APERTURE_MESH_DEPTH);

    public RealtimeSettings {
        maxRenderDistance = Math.max(MIN_RENDER_DISTANCE,
                Math.min(MAX_RENDER_DISTANCE, maxRenderDistance));
        apertureBackdropGain = Double.isNaN(apertureBackdropGain)
                ? DEFAULT_APERTURE_BACKDROP_GAIN
                : Math.max(0.0, Math.min(1.0, apertureBackdropGain));
        apertureAtlasFilter = apertureAtlasFilter == 0 || apertureAtlasFilter == 1
                ? apertureAtlasFilter
                : DEFAULT_APERTURE_ATLAS_FILTER;
        viewDepth = Math.max(MIN_VIEW_DEPTH, Math.min(MAX_VIEW_DEPTH, viewDepth));
        chosen = chosen == null ? Set.of() : Set.copyOf(chosen);
    }

    /** Values alone: nothing here was chosen by a player. */
    public RealtimeSettings(boolean renderClientSidePortals, int maxRenderDistance,
                            boolean distantHorizons, boolean renderServerSidePortals,
                            boolean spectatorPass, boolean apertureBackdrop,
                            boolean apertureTerrain, boolean apertureFarStamp,
                            boolean apertureFarStampEarly, boolean apertureMeshDepth) {
        this(renderClientSidePortals, maxRenderDistance, distantHorizons,
                renderServerSidePortals, spectatorPass, apertureBackdrop, apertureTerrain,
                apertureFarStamp, apertureFarStampEarly, apertureMeshDepth,
                DEFAULT_APERTURE_UNSHADED_DESTINATION, DEFAULT_APERTURE_BACKDROP_GAIN,
                DEFAULT_APERTURE_UNSHADED_BACKDROP, DEFAULT_APERTURE_ATLAS_FILTER,
                DEFAULT_VIEW_DEPTH, Set.of());
    }

    /**
     * A setter that leaves the value where it was records nothing:
     * {@code DevServer.realtime} re-asserts every field on every call, and
     * counting that as ten choices would pin the lot.
     */
    private Set<String> choosing(String key, boolean changed) {
        if (!changed || this.chosen.contains(key)) {
            return this.chosen;
        }
        Set<String> next = new LinkedHashSet<>(this.chosen);
        next.add(key);
        return next;
    }

    /**
     * Whether the server still has to describe the far side. The two views are
     * redundant, so a client rendering its own is never sent the slab however
     * the player has set it.
     */
    public boolean effectiveServerSide() {
        return !this.renderClientSidePortals && this.renderServerSidePortals;
    }

    public RealtimeSettings withRenderClientSidePortals(boolean value) {
        return new RealtimeSettings(value, this.maxRenderDistance, this.distantHorizons,
                this.renderServerSidePortals, this.spectatorPass, this.apertureBackdrop,
                this.apertureTerrain, this.apertureFarStamp, this.apertureFarStampEarly,
                this.apertureMeshDepth, this.apertureUnshadedDestination, this.apertureBackdropGain, this.apertureUnshadedBackdrop,
                this.apertureAtlasFilter, this.viewDepth,
                choosing(KEY_RENDER_CLIENT_SIDE_PORTALS, value != this.renderClientSidePortals));
    }

    public RealtimeSettings withMaxRenderDistance(int value) {
        return new RealtimeSettings(this.renderClientSidePortals, value, this.distantHorizons,
                this.renderServerSidePortals, this.spectatorPass, this.apertureBackdrop,
                this.apertureTerrain, this.apertureFarStamp, this.apertureFarStampEarly,
                this.apertureMeshDepth, this.apertureUnshadedDestination, this.apertureBackdropGain, this.apertureUnshadedBackdrop,
                this.apertureAtlasFilter, this.viewDepth,
                choosing(KEY_MAX_RENDER_DISTANCE, value != this.maxRenderDistance));
    }

    public RealtimeSettings withDistantHorizons(boolean value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance, value,
                this.renderServerSidePortals, this.spectatorPass, this.apertureBackdrop,
                this.apertureTerrain, this.apertureFarStamp, this.apertureFarStampEarly,
                this.apertureMeshDepth, this.apertureUnshadedDestination, this.apertureBackdropGain, this.apertureUnshadedBackdrop,
                this.apertureAtlasFilter, this.viewDepth,
                choosing(KEY_DISTANT_HORIZONS, value != this.distantHorizons));
    }

    public RealtimeSettings withRenderServerSidePortals(boolean value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, value, this.spectatorPass, this.apertureBackdrop,
                this.apertureTerrain, this.apertureFarStamp, this.apertureFarStampEarly,
                this.apertureMeshDepth, this.apertureUnshadedDestination, this.apertureBackdropGain, this.apertureUnshadedBackdrop,
                this.apertureAtlasFilter, this.viewDepth,
                choosing(KEY_RENDER_SERVER_SIDE_PORTALS, value != this.renderServerSidePortals));
    }

    public RealtimeSettings withSpectatorPass(boolean value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, this.renderServerSidePortals, value, this.apertureBackdrop,
                this.apertureTerrain, this.apertureFarStamp, this.apertureFarStampEarly,
                this.apertureMeshDepth, this.apertureUnshadedDestination, this.apertureBackdropGain, this.apertureUnshadedBackdrop,
                this.apertureAtlasFilter, this.viewDepth,
                choosing(KEY_SPECTATOR_PASS, value != this.spectatorPass));
    }

    /**
     * What the key flips. Only the local render: the player's server-side
     * setting is left alone so it takes effect on its own the moment the
     * local one goes off.
     */
    public RealtimeSettings toggled() {
        return withRenderClientSidePortals(!this.renderClientSidePortals);
    }

    public RealtimeSettings withApertureBackdrop(boolean value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, this.renderServerSidePortals, this.spectatorPass, value,
                this.apertureTerrain, this.apertureFarStamp, this.apertureFarStampEarly,
                this.apertureMeshDepth, this.apertureUnshadedDestination, this.apertureBackdropGain, this.apertureUnshadedBackdrop,
                this.apertureAtlasFilter, this.viewDepth,
                choosing(KEY_APERTURE_BACKDROP, value != this.apertureBackdrop));
    }

    public RealtimeSettings withApertureTerrain(boolean value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, this.renderServerSidePortals, this.spectatorPass,
                this.apertureBackdrop, value, this.apertureFarStamp, this.apertureFarStampEarly,
                this.apertureMeshDepth, this.apertureUnshadedDestination, this.apertureBackdropGain, this.apertureUnshadedBackdrop,
                this.apertureAtlasFilter, this.viewDepth,
                choosing(KEY_APERTURE_TERRAIN, value != this.apertureTerrain));
    }

    public RealtimeSettings withApertureFarStamp(boolean value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, this.renderServerSidePortals, this.spectatorPass,
                this.apertureBackdrop, this.apertureTerrain, value, this.apertureFarStampEarly,
                this.apertureMeshDepth, this.apertureUnshadedDestination, this.apertureBackdropGain, this.apertureUnshadedBackdrop,
                this.apertureAtlasFilter, this.viewDepth,
                choosing(KEY_APERTURE_FAR_STAMP, value != this.apertureFarStamp));
    }

    public RealtimeSettings withApertureFarStampEarly(boolean value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, this.renderServerSidePortals, this.spectatorPass,
                this.apertureBackdrop, this.apertureTerrain, this.apertureFarStamp, value,
                this.apertureMeshDepth, this.apertureUnshadedDestination, this.apertureBackdropGain, this.apertureUnshadedBackdrop,
                this.apertureAtlasFilter, this.viewDepth,
                choosing(KEY_APERTURE_FAR_STAMP_EARLY, value != this.apertureFarStampEarly));
    }

    public RealtimeSettings withApertureMeshDepth(boolean value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, this.renderServerSidePortals, this.spectatorPass,
                this.apertureBackdrop, this.apertureTerrain, this.apertureFarStamp,
                this.apertureFarStampEarly, value, this.apertureUnshadedDestination, this.apertureBackdropGain, this.apertureUnshadedBackdrop,
                this.apertureAtlasFilter, this.viewDepth,
                choosing(KEY_APERTURE_MESH_DEPTH, value != this.apertureMeshDepth));
    }

    public RealtimeSettings withApertureUnshadedDestination(boolean value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, this.renderServerSidePortals, this.spectatorPass,
                this.apertureBackdrop, this.apertureTerrain, this.apertureFarStamp,
                this.apertureFarStampEarly, this.apertureMeshDepth, value,
                this.apertureBackdropGain, this.apertureUnshadedBackdrop,
                this.apertureAtlasFilter, this.viewDepth,
                choosing(KEY_APERTURE_UNSHADED_DESTINATION,
                        value != this.apertureUnshadedDestination));
    }

    public RealtimeSettings withApertureBackdropGain(double value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, this.renderServerSidePortals, this.spectatorPass,
                this.apertureBackdrop, this.apertureTerrain, this.apertureFarStamp,
                this.apertureFarStampEarly, this.apertureMeshDepth,
                this.apertureUnshadedDestination, value, this.apertureUnshadedBackdrop,
                this.apertureAtlasFilter, this.viewDepth,
                choosing(KEY_APERTURE_BACKDROP_GAIN, value != this.apertureBackdropGain));
    }

    public RealtimeSettings withApertureUnshadedBackdrop(boolean value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, this.renderServerSidePortals, this.spectatorPass,
                this.apertureBackdrop, this.apertureTerrain, this.apertureFarStamp,
                this.apertureFarStampEarly, this.apertureMeshDepth,
                this.apertureUnshadedDestination, this.apertureBackdropGain, value,
                this.apertureAtlasFilter, this.viewDepth,
                choosing(KEY_APERTURE_UNSHADED_BACKDROP, value != this.apertureUnshadedBackdrop));
    }

    public RealtimeSettings withApertureAtlasFilter(int value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, this.renderServerSidePortals, this.spectatorPass,
                this.apertureBackdrop, this.apertureTerrain, this.apertureFarStamp,
                this.apertureFarStampEarly, this.apertureMeshDepth,
                this.apertureUnshadedDestination, this.apertureBackdropGain,
                this.apertureUnshadedBackdrop, value, this.viewDepth,
                choosing(KEY_APERTURE_ATLAS_FILTER, value != this.apertureAtlasFilter));
    }

    public RealtimeSettings withViewDepth(int value) {
        return new RealtimeSettings(this.renderClientSidePortals, this.maxRenderDistance,
                this.distantHorizons, this.renderServerSidePortals, this.spectatorPass,
                this.apertureBackdrop, this.apertureTerrain, this.apertureFarStamp,
                this.apertureFarStampEarly, this.apertureMeshDepth,
                this.apertureUnshadedDestination, this.apertureBackdropGain,
                this.apertureUnshadedBackdrop, this.apertureAtlasFilter, value,
                choosing(KEY_VIEW_DEPTH, value != this.viewDepth));
    }

    public String toJson() {
        List<String> recorded = new ArrayList<>(this.chosen);
        recorded.sort(String::compareTo);
        return Json.obj()
                .num("configVersion", CONFIG_VERSION)
                .bool(KEY_RENDER_CLIENT_SIDE_PORTALS, this.renderClientSidePortals)
                .num(KEY_MAX_RENDER_DISTANCE, this.maxRenderDistance)
                .bool(KEY_DISTANT_HORIZONS, this.distantHorizons)
                .bool(KEY_RENDER_SERVER_SIDE_PORTALS, this.renderServerSidePortals)
                .bool(KEY_SPECTATOR_PASS, this.spectatorPass)
                .bool(KEY_APERTURE_BACKDROP, this.apertureBackdrop)
                .bool(KEY_APERTURE_TERRAIN, this.apertureTerrain)
                .bool(KEY_APERTURE_FAR_STAMP, this.apertureFarStamp)
                .bool(KEY_APERTURE_FAR_STAMP_EARLY, this.apertureFarStampEarly)
                .bool(KEY_APERTURE_MESH_DEPTH, this.apertureMeshDepth)
                .bool(KEY_APERTURE_UNSHADED_DESTINATION, this.apertureUnshadedDestination)
                .num(KEY_APERTURE_BACKDROP_GAIN, this.apertureBackdropGain)
                .bool(KEY_APERTURE_UNSHADED_BACKDROP, this.apertureUnshadedBackdrop)
                .num(KEY_APERTURE_ATLAS_FILTER, this.apertureAtlasFilter)
                .num(KEY_VIEW_DEPTH, this.viewDepth)
                .raw(KEY_CHOSEN, Json.strings(recorded))
                .toString();
    }

    /**
     * Reads a config file. Anything unreadable — malformed, absent, an array,
     * a field of the wrong type — falls back to the default for that field
     * rather than throwing: a config file cannot be allowed to stop the mod
     * loading, because the symptom is a client with no portals and no error.
     */
    public static RealtimeSettings parse(String text) {
        Map<String, Object> raw;
        try {
            raw = JsonReader.object(text);
        } catch (JsonReader.Malformed e) {
            return DEFAULTS;
        }
        int version = version(raw);
        if (version < 1) {
            return migrate(raw);
        }
        Stored stored = new Stored(raw, version);
        return new RealtimeSettings(
                stored.bool(KEY_RENDER_CLIENT_SIDE_PORTALS, DEFAULT_RENDER_CLIENT_SIDE_PORTALS),
                stored.integer(KEY_MAX_RENDER_DISTANCE, DEFAULT_RENDER_DISTANCE),
                stored.bool(KEY_DISTANT_HORIZONS, DEFAULT_DISTANT_HORIZONS),
                stored.bool(KEY_RENDER_SERVER_SIDE_PORTALS, DEFAULT_RENDER_SERVER_SIDE_PORTALS),
                stored.bool(KEY_SPECTATOR_PASS, DEFAULT_SPECTATOR_PASS),
                stored.bool(KEY_APERTURE_BACKDROP, DEFAULT_APERTURE_BACKDROP),
                stored.bool(KEY_APERTURE_TERRAIN, DEFAULT_APERTURE_TERRAIN),
                stored.bool(KEY_APERTURE_FAR_STAMP, DEFAULT_APERTURE_FAR_STAMP),
                stored.bool(KEY_APERTURE_FAR_STAMP_EARLY, DEFAULT_APERTURE_FAR_STAMP_EARLY),
                stored.bool(KEY_APERTURE_MESH_DEPTH, DEFAULT_APERTURE_MESH_DEPTH),
                stored.bool(KEY_APERTURE_UNSHADED_DESTINATION,
                        DEFAULT_APERTURE_UNSHADED_DESTINATION),
                stored.decimal(KEY_APERTURE_BACKDROP_GAIN, DEFAULT_APERTURE_BACKDROP_GAIN),
                stored.bool(KEY_APERTURE_UNSHADED_BACKDROP,
                        DEFAULT_APERTURE_UNSHADED_BACKDROP),
                stored.integer(KEY_APERTURE_ATLAS_FILTER, DEFAULT_APERTURE_ATLAS_FILTER),
                stored.integer(KEY_VIEW_DEPTH, DEFAULT_VIEW_DEPTH),
                stored.chosen());
    }

    /**
     * Whether reading this text migrated it, so the store rewrites the file and
     * the stamp lands. A file that will not parse is left exactly as it is.
     */
    public static boolean needsMigration(String text) {
        try {
            return version(JsonReader.object(text)) < CONFIG_VERSION;
        } catch (JsonReader.Malformed e) {
            return false;
        }
    }

    /**
     * An unstamped file, written when the fields were {@code enabled} and
     * {@code fallbackToSlab}. {@code enabled} is the player's own choice and
     * carries over; {@code fallbackToSlab} was written false by a default
     * nobody chose, and an unstamped file was served the slab whenever the
     * local render was off, so server-side comes back on either way. The three
     * fields it carries are the three it can prove somebody set.
     */
    private static RealtimeSettings migrate(Map<String, Object> raw) {
        Set<String> chosen = new LinkedHashSet<>();
        if (raw.get(LEGACY_KEY_ENABLED) instanceof Boolean) {
            chosen.add(KEY_RENDER_CLIENT_SIDE_PORTALS);
        }
        if (raw.get(KEY_MAX_RENDER_DISTANCE) instanceof Number) {
            chosen.add(KEY_MAX_RENDER_DISTANCE);
        }
        if (raw.get(KEY_DISTANT_HORIZONS) instanceof Boolean) {
            chosen.add(KEY_DISTANT_HORIZONS);
        }
        return new RealtimeSettings(
                bool(raw, LEGACY_KEY_ENABLED, DEFAULT_RENDER_CLIENT_SIDE_PORTALS),
                integer(raw, KEY_MAX_RENDER_DISTANCE, DEFAULT_RENDER_DISTANCE),
                bool(raw, KEY_DISTANT_HORIZONS, DEFAULT_DISTANT_HORIZONS),
                DEFAULT_RENDER_SERVER_SIDE_PORTALS,
                DEFAULT_SPECTATOR_PASS,
                DEFAULT_APERTURE_BACKDROP,
                DEFAULT_APERTURE_TERRAIN,
                DEFAULT_APERTURE_FAR_STAMP,
                DEFAULT_APERTURE_FAR_STAMP_EARLY,
                DEFAULT_APERTURE_MESH_DEPTH,
                DEFAULT_APERTURE_UNSHADED_DESTINATION,
                DEFAULT_APERTURE_BACKDROP_GAIN,
                DEFAULT_APERTURE_UNSHADED_BACKDROP,
                DEFAULT_APERTURE_ATLAS_FILTER,
                DEFAULT_VIEW_DEPTH,
                chosen);
    }

    private static int version(Map<String, Object> raw) {
        return integer(raw, "configVersion", 0);
    }

    private static boolean bool(Map<String, Object> raw, String key, boolean fallback) {
        Object value = raw.get(key);
        return value instanceof Boolean b ? b : fallback;
    }

    private static int integer(Map<String, Object> raw, String key, int fallback) {
        Object value = raw.get(key);
        return value instanceof Number n ? (int) n.doubleValue() : fallback;
    }

    /**
     * One stamped file, read key by key, accumulating what it says was chosen.
     * A key nobody chose holds the default in force when the file was written,
     * so below {@link #CONFIG_VERSION} the current default wins instead.
     *
     * <p>A file recording no choices at all has every key it names read as one:
     * nothing about it can be inferred, so it keeps what the player has and
     * follows no default again.
     */
    private static final class Stored {

        private final Map<String, Object> raw;
        private final int version;
        private final Set<String> declared;
        private final Set<String> chosen = new LinkedHashSet<>();

        Stored(Map<String, Object> raw, int version) {
            this.raw = raw;
            this.version = version;
            this.declared = declaredChoices(raw);
        }

        boolean bool(String key, boolean fallback) {
            Object value = this.raw.get(key);
            return value instanceof Boolean b ? take(key, b, fallback) : fallback;
        }

        int integer(String key, int fallback) {
            Object value = this.raw.get(key);
            return value instanceof Number n ? take(key, (int) n.doubleValue(), fallback) : fallback;
        }

        double decimal(String key, double fallback) {
            Object value = this.raw.get(key);
            return value instanceof Number n ? take(key, n.doubleValue(), fallback) : fallback;
        }

        Set<String> chosen() {
            return this.chosen;
        }

        private <T> T take(String key, T stored, T fallback) {
            if (this.declared == null || this.declared.contains(key)) {
                this.chosen.add(key);
                return stored;
            }
            return this.version < CONFIG_VERSION ? fallback : stored;
        }

        /** Null when the file records nothing about what was chosen. */
        private static Set<String> declaredChoices(Map<String, Object> raw) {
            if (!(raw.get(KEY_CHOSEN) instanceof List<?> list)) {
                return null;
            }
            Set<String> names = new LinkedHashSet<>();
            for (Object name : list) {
                if (name instanceof String s) {
                    names.add(s);
                }
            }
            return names;
        }
    }
}
