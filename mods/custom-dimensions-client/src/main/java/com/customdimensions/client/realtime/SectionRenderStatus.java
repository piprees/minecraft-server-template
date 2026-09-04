package com.customdimensions.client.realtime;

/**
 * Vanilla's per-section render marking, over its own inputs.
 *
 * <p>{@code ClientChunkManager.loadChunkFromPacket} is a radius gate, a
 * {@code loadFromPacket}, a {@code chunks.set} and a colour reset — it marks
 * nothing renderable. The only place 1.21.1 calls
 * {@code LightingProvider.setSectionStatus} and
 * {@code ClientWorld.scheduleBlockRenders} per section is
 * {@code ClientPlayNetworkHandler.scheduleRenderChunk}, which vanilla runs from
 * the chunk-update queue AFTER {@code readLightData}. A fed chunk that never
 * gets that loop keeps every section flagged not-ready, nothing is queued to the
 * chunk builder, and the pass draws sky.
 *
 * <p>The loop lives here rather than inline so it can be asserted: a
 * {@code ChunkSection} cannot be constructed in this JVM, so the sink takes the
 * two calls and the emptiness comes in as a plain array.
 */
public final class SectionRenderStatus {

    /** The two calls vanilla makes per section, once each, in this order. */
    public interface Sink {

        /** {@code LightingProvider.setSectionStatus} — {@code notReady}. */
        void sectionStatus(int sectionY, boolean empty);

        /** {@code ClientWorld.scheduleBlockRenders}. */
        void scheduleBlockRenders(int chunkX, int sectionY, int chunkZ);
    }

    private SectionRenderStatus() {}

    /** Vanilla's {@code HeightLimitView.sectionIndexToCoord}. */
    public static int sectionCoord(int bottomSectionCoord, int index) {
        return index + bottomSectionCoord;
    }

    /**
     * Marks one loaded chunk's sections, bottom index upwards.
     *
     * @param emptyBySection one entry per section, in section-array order
     */
    public static void mark(int chunkX, int chunkZ, int bottomSectionCoord,
            boolean[] emptyBySection, Sink sink) {
        if (emptyBySection == null || sink == null) {
            return;
        }
        for (int index = 0; index < emptyBySection.length; index++) {
            int sectionY = sectionCoord(bottomSectionCoord, index);
            sink.sectionStatus(sectionY, emptyBySection[index]);
            sink.scheduleBlockRenders(chunkX, sectionY, chunkZ);
        }
    }
}
