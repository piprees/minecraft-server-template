package com.customdimensions.compiler.datapack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per Minecraft release, the formats the compiler emits that differ between
 * releases. 26.3 is the baseline; each later release lists only what it
 * changes, measured from that release's own data files.
 */
public final class VersionTable {

    /**
     * What one release accepts.
     *
     * @param id                     the Minecraft version id
     * @param packFormat             {@code pack.mcmeta} data pack format
     * @param noiseSettingsDefaultBlock whether noise settings carry {@code default_block}
     * @param multiNoiseDisjoint     whether multi-noise entries must not overlap; the compiler then
     *                               carves overlaps apart ({@link MultiNoise#carve}), since 26.3's own
     *                               overworld list overlaps among its cave entries
     */
    public record Version(String id, int packFormat, boolean noiseSettingsDefaultBlock, boolean multiNoiseDisjoint) {
    }

    public static final Version V26_3 = new Version("26.3", 121, true, false);
    /** Measured from 26.4-snapshot-5: data pack format 125 and no {@code default_block} in noise settings. */
    public static final Version V26_4 = new Version("26.4", 125, false, true);

    private static final Map<String, Version> VERSIONS = new LinkedHashMap<>();

    static {
        for (Version v : List.of(V26_3, V26_4)) {
            VERSIONS.put(v.id(), v);
        }
    }

    private VersionTable() {
    }

    /** The release with this id; a snapshot id ({@code 26.4-snapshot-5}) resolves to its release. */
    public static Version forId(String id) {
        Version exact = VERSIONS.get(id);
        if (exact != null) {
            return exact;
        }
        int dash = id.indexOf('-');
        Version release = dash > 0 ? VERSIONS.get(id.substring(0, dash)) : null;
        if (release == null) {
            throw new IllegalArgumentException("No version table entry for Minecraft " + id + "; known: " + VERSIONS.keySet());
        }
        return release;
    }

    public static List<Version> all() {
        return List.copyOf(VERSIONS.values());
    }
}
