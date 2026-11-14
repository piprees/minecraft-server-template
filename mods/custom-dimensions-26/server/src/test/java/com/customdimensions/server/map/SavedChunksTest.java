package com.customdimensions.server.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.render.map.BiomeTint;
import com.customdimensions.core.render.map.ExploredChunk;
import com.customdimensions.core.render.map.MapPalette;
import com.customdimensions.core.render.map.PackedIndices;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavedChunksTest {
    private static final MapPalette P = MapPalette.vanilla();
    private static final ExploredChunk.Resolver RESOLVER = new ExploredChunk.Resolver() {
        @Override
        public MapPalette.Entry block(String id) {
            MapPalette.Entry e = P.entry(id);
            return e != null ? e : MapPalette.Entry.opaque(0x808080);
        }

        @Override
        public BiomeTint biome(String id) {
            return BiomeTint.PLAINS;
        }
    };

    /** A finished chunk at section 4: grass at y 64 in column (0,0), a waterlogged stair under two water blocks over stone in (1,0). */
    static CompoundTag chunk(String status) {
        int[] idx = new int[4096];
        idx[0] = 1;
        idx[1] = 3;
        idx[16 * 16 + 1] = 2;
        idx[2 * 16 * 16 + 1] = 4;
        String[] names = {"minecraft:air", "minecraft:grass_block", "minecraft:oak_stairs", "minecraft:stone", "minecraft:water"};
        ListTag palette = new ListTag();
        for (String n : names) {
            CompoundTag state = new CompoundTag();
            state.putString("Name", n);
            if (n.equals("minecraft:oak_stairs")) {
                CompoundTag props = new CompoundTag();
                props.putString("waterlogged", "true");
                state.put("Properties", props);
            }
            palette.add(state);
        }
        CompoundTag states = new CompoundTag();
        states.put("palette", palette);
        states.put("data", new LongArrayTag(PackedIndices.pack(idx, names.length, 4)));
        CompoundTag biomes = new CompoundTag();
        ListTag biomePalette = new ListTag();
        biomePalette.add(StringTag.valueOf("minecraft:plains"));
        biomes.put("palette", biomePalette);
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) 4);
        section.put("block_states", states);
        section.put("biomes", biomes);
        ListTag sections = new ListTag();
        sections.add(section);
        CompoundTag root = new CompoundTag();
        root.putString("Status", status);
        root.put("sections", sections);
        return root;
    }

    /** A region file holding {@code tag} at chunk index {@code index}, zlib-compressed in sector 2. */
    static void region(Path file, int index, CompoundTag tag) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(body))) {
            NbtIo.write(tag, out);
        }
        byte[] data = body.toByteArray();
        int sectors = Math.ceilDiv(data.length + 5, 4096);
        ByteBuffer buf = ByteBuffer.allocate(8192 + sectors * 4096);
        buf.putInt(index * 4, 2 << 8 | sectors);
        buf.position(8192);
        buf.putInt(data.length + 1);
        buf.put((byte) 2);
        buf.put(data);
        Files.write(file, buf.array());
    }

    @Test
    void aSavedChunkIsReadFromItsRegionFileWithoutWriting(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("r.0.0.mca");
        CompoundTag tag = chunk("minecraft:full");
        region(file, 2 * 32 + 1, tag);
        long before = Files.getLastModifiedTime(file).toMillis();
        byte[] bytes = Files.readAllBytes(file);
        assertEquals(List.of(65), SavedChunks.present(file));
        CompoundTag back = SavedChunks.readFile(file, new ChunkPos(1, 2));
        assertEquals(tag, back);
        assertTrue(SavedChunks.read(null, file, new ChunkPos(1, 2)).isPresent());
        assertTrue(SavedChunks.read(null, file, new ChunkPos(0, 0)).isEmpty(), "a chunk the table does not hold");
        assertTrue(SavedChunks.full(back));
        assertFalse(SavedChunks.full(chunk("minecraft:features")));
        List<ExploredChunk.Section> sections = SavedChunks.sections(back);
        assertEquals(1, sections.size());
        assertEquals(ExploredChunk.WATER, sections.getFirst().blocks().get(2), "a waterlogged stair reads as water");
        ExploredChunk.Columns c = ExploredChunk.columns(sections, RESOLVER);
        assertEquals(MapPalette.colour(P.entry("minecraft:grass_block"), BiomeTint.PLAINS), c.rgb()[0]);
        assertEquals(64, c.height()[0]);
        assertTrue(c.water()[1]);
        assertEquals(66, c.height()[1]);
        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertEquals(before, Files.getLastModifiedTime(file).toMillis());
    }

    @Test
    void theExploredLayerRedrawsOnlyChangedRegions(@TempDir Path dir) throws Exception {
        Path regions = Files.createDirectories(dir.resolve("region"));
        region(regions.resolve("r.0.0.mca"), 0, chunk("minecraft:full"));
        region(regions.resolve("r.-1.0.mca"), 31, chunk("minecraft:carvers"));
        region(regions.resolve("r.5.5.mca"), 0, chunk("minecraft:full"));
        Path out = dir.resolve("out");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            ExploredExport.Result first = ExploredExport.run("test", null, regions, RESOLVER, 1024, out, pool, false);
            assertEquals(2, first.regions(), "a region past the border is left out");
            assertEquals(1, first.chunks());
            assertEquals(1, first.chunksUnfinished());
            assertTrue(first.tiles() >= 2);
            JsonObject json = JsonParser.parseString(Files.readString(out.resolve(ExploredExport.JSON))).getAsJsonObject();
            assertEquals("[\"1/1\"]", json.getAsJsonObject("index").getAsJsonArray("1").toString(), "the index names the tile drawn");
            assertTrue(Files.isRegularFile(out.resolve("explored/1/1/1.png")), "chunk 0,0 lies at the centre of a 2048-block map");
            ExploredExport.Result again = ExploredExport.run("test", null, regions, RESOLVER, 1024, out, pool, false);
            assertTrue(again.skipped());
            Files.setLastModifiedTime(regions.resolve("r.0.0.mca"), FileTime.fromMillis(System.currentTimeMillis() + 5000));
            ExploredExport.Result changed = ExploredExport.run("test", null, regions, RESOLVER, 1024, out, pool, false);
            assertFalse(changed.skipped());
            assertEquals(1, changed.regionsRead());
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertArrayEquals(byte[] a, byte[] b) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(a, b, "the region file is unchanged");
    }
}
