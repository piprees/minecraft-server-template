package com.customdimensions.server.map;

import com.customdimensions.core.render.map.ExploredChunk;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/**
 * Reads a level's saved chunks for the web map without loading them into the
 * world and without writing anything. Which chunks a region file holds comes
 * from its 4 KiB location table, read through a read-only channel. A chunk of
 * an open level is read through the level's own chunk storage
 * ({@code ChunkMap}, a {@code SimpleRegionStorage}: its {@code read} queues on
 * the storage's IO worker, which serves a chunk waiting to be saved from
 * memory, and is safe from any thread); a chunk of a level that is not open is
 * read from the region file itself, read-only, decompressed by vanilla's
 * {@link RegionFileVersion}. A chunk that cannot be read (torn by a write in
 * flight, a level closing) is reported missing, never repaired.
 */
public final class SavedChunks {
    public static final int TABLE = 4096;
    /** How long one chunk read through the level's storage may wait. */
    public static final long READ_SECONDS = 30;

    private SavedChunks() {}

    /** The chunks present in a region file, as chunk indices {@code z * 32 + x} within it. */
    public static List<Integer> present(Path region) throws IOException {
        List<Integer> out = new ArrayList<>();
        try (FileChannel ch = FileChannel.open(region, StandardOpenOption.READ)) {
            if (ch.size() < TABLE) return out;
            ByteBuffer table = ByteBuffer.allocate(TABLE);
            while (table.hasRemaining() && ch.read(table, table.position()) > 0) {
                // read until the table is full
            }
            table.flip();
            for (int i = 0; i < 1024; i++) if (table.getInt(i * 4) != 0) out.add(i);
        }
        return out;
    }

    /** A region file's header: each chunk's location entry (0 when absent) and when it was last written, in seconds. */
    public record Header(int[] locations, long[] written) {}

    /** The 8 KiB header of {@code region}: its location table and its timestamp table. */
    public static Header header(Path region) throws IOException {
        int[] locations = new int[1024];
        long[] written = new long[1024];
        try (FileChannel ch = FileChannel.open(region, StandardOpenOption.READ)) {
            ByteBuffer table = ByteBuffer.allocate(2 * TABLE);
            while (table.hasRemaining() && ch.read(table, table.position()) > 0) {
                // read until the header is full or the file ends
            }
            int filled = table.position();
            for (int i = 0; i < 1024; i++) {
                if ((i + 1) * 4 <= filled) locations[i] = table.getInt(i * 4);
                if (TABLE + (i + 1) * 4 <= filled) written[i] = Integer.toUnsignedLong(table.getInt(TABLE + i * 4));
            }
        }
        return new Header(locations, written);
    }

    /** Chunk {@code pos}'s saved tag: through {@code level}'s storage when it is open, else from {@code region} read-only. */
    public static Optional<CompoundTag> read(ServerLevel level, Path region, ChunkPos pos) {
        try {
            if (level != null) return level.getChunkSource().chunkMap.read(pos).get(READ_SECONDS, TimeUnit.SECONDS);
            return Optional.ofNullable(readFile(region, pos));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** A chunk straight from a region file, read-only; null when the table has no entry. */
    static CompoundTag readFile(Path region, ChunkPos pos) throws IOException {
        int index = (pos.z() & 31) * 32 + (pos.x() & 31);
        try (FileChannel ch = FileChannel.open(region, StandardOpenOption.READ)) {
            ByteBuffer entry = ByteBuffer.allocate(4);
            ch.read(entry, index * 4L);
            entry.flip();
            int location = entry.getInt();
            if (location == 0) return null;
            long offset = (long) (location >>> 8) * 4096;
            int sectors = location & 0xFF;
            ByteBuffer head = ByteBuffer.allocate(5);
            ch.read(head, offset);
            head.flip();
            int length = head.getInt();
            int type = head.get() & 0xFF;
            InputStream raw;
            if ((type & 0x80) != 0) {
                Path external = region.resolveSibling("c." + pos.x() + "." + pos.z() + ".mcc");
                raw = new ByteArrayInputStream(Files.readAllBytes(external));
            } else {
                if (length < 1 || length > sectors * 4096) throw new IOException("chunk " + pos + " length " + length);
                ByteBuffer body = ByteBuffer.allocate(length - 1);
                while (body.hasRemaining() && ch.read(body, offset + 5 + body.position()) > 0) {
                    // read until the body is full
                }
                raw = new ByteArrayInputStream(body.array());
            }
            RegionFileVersion version = RegionFileVersion.fromId(type & 0x7F);
            if (version == null) throw new IOException("chunk " + pos + " compression " + type);
            try (DataInputStream in = new DataInputStream(version.wrap(raw))) {
                return NbtIo.read(in, NbtAccounter.unlimitedHeap());
            }
        }
    }

    /** Whether the chunk finished generating ({@code Status} {@code minecraft:full}). */
    public static boolean full(CompoundTag chunk) {
        String status = chunk.getStringOr("Status", "");
        return status.equals("minecraft:full") || status.equals("full");
    }

    /**
     * A palette entry's block id, water for a waterlogged state. 26.3 saves a
     * state as its string form ({@code minecraft:oak_stairs[waterlogged=true,...]});
     * the older compound ({@code Name}, {@code Properties}) is read too.
     */
    static String block(net.minecraft.nbt.Tag entry) {
        if (entry instanceof CompoundTag state) {
            boolean wet = "true".equals(state.getCompoundOrEmpty("Properties").getStringOr("waterlogged", ""));
            return wet ? ExploredChunk.WATER : state.getStringOr("Name", "minecraft:air");
        }
        String s = entry.asString().orElse("minecraft:air");
        int bracket = s.indexOf('[');
        if (bracket < 0) return s;
        return s.indexOf("waterlogged=true", bracket) >= 0 ? ExploredChunk.WATER : s.substring(0, bracket);
    }

    /** The chunk's sections in {@link ExploredChunk}'s form; a waterlogged state is passed as water. */
    public static List<ExploredChunk.Section> sections(CompoundTag chunk) {
        List<ExploredChunk.Section> out = new ArrayList<>();
        for (CompoundTag s : chunk.getListOrEmpty("sections").compoundStream().toList()) {
            CompoundTag states = s.getCompoundOrEmpty("block_states");
            ListTag palette = states.getListOrEmpty("palette");
            if (palette.isEmpty()) continue;
            List<String> blocks = new ArrayList<>(palette.size());
            for (int i = 0; i < palette.size(); i++) blocks.add(block(palette.get(i)));
            CompoundTag biomeTag = s.getCompoundOrEmpty("biomes");
            ListTag biomePalette = biomeTag.getListOrEmpty("palette");
            List<String> biomes = new ArrayList<>(biomePalette.size());
            for (int i = 0; i < biomePalette.size(); i++) biomes.add(biomePalette.getStringOr(i, "minecraft:plains"));
            out.add(new ExploredChunk.Section(s.getByteOr("Y", (byte) 0), blocks, states.getLongArray("data").orElse(null),
                    biomes.isEmpty() ? null : biomes, biomeTag.getLongArray("data").orElse(null)));
        }
        return out;
    }
}
