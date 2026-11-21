package com.customdimensions.server.survey;

import com.customdimensions.core.survey.SurveyMetrics;
import com.customdimensions.server.structure.SetPiecePart;
import com.customdimensions.server.structure.StructureRuntime;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The dossier's {@code places.named} for a level's set pieces
 * (places/set-pieces.md § Finding places): each forced or wanted start at its
 * chosen chunk, and each site of a set piece's group the census gives it
 * (only those its repetition ceiling leaves it),
 * inside the border; with its id, name, source, coordinates, size, and the
 * seat height once its chunk has generated.
 */
public final class NamedPlaces {

    private NamedPlaces() {
    }

    public static JsonArray named(ServerLevel level, int radius) {
        String id = level.dimension().identifier().toString();
        List<StructureRuntime.SetPieceEntry> pieces = StructureRuntime.setPieces(id);
        JsonArray out = new JsonArray();
        if (pieces.isEmpty()) return out;
        Map<String, StructureRuntime.SetPieceEntry> byId = new HashMap<>();
        pieces.forEach(p -> byId.put(p.structure(), p));
        StructureRuntime.applied(id).ifPresent(applied -> {
            for (StructureRuntime.Placed p : applied.placed()) {
                StructureRuntime.SetPieceEntry piece = byId.get(p.structure());
                if (piece != null) out.add(entry(level, piece, p.kind(), p.chunkX() * 16 + 8, p.chunkZ() * 16 + 8));
            }
        });
        if (pieces.stream().anyMatch(p -> p.set() != null)) {
            for (SurveyMetrics.Site s : StructureCensus.sites(level, radius)) {
                StructureRuntime.SetPieceEntry piece = byId.get(s.structure());
                if (piece == null || piece.set() == null) continue;
                java.util.Set<Long> cap = StructureRuntime.cap(id, level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                        .getValue(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(piece.structure()))));
                if (cap != null && !cap.contains(net.minecraft.world.level.ChunkPos.pack(s.x() >> 4, s.z() >> 4))) continue;
                out.add(entry(level, piece, "group", s.x(), s.z()));
            }
        }
        return out;
    }

    private static JsonObject entry(ServerLevel level, StructureRuntime.SetPieceEntry piece, String kind, int x, int z) {
        JsonObject o = new JsonObject();
        o.addProperty("id", piece.structure());
        o.addProperty("name", piece.name());
        o.addProperty("source", "set piece");
        o.addProperty("kind", kind);
        if (piece.group() != null) o.addProperty("group", piece.group());
        o.addProperty("x", x);
        o.addProperty("z", z);
        Integer y = seatHeight(level, piece.structure(), x >> 4, z >> 4);
        if (y != null) o.addProperty("y", y);
        o.addProperty("size", piece.size());
        return o;
    }

    /** The seat height of the set piece's start in a generated chunk, or null when the chunk is not loaded or holds none. */
    static Integer seatHeight(ServerLevel level, String structureId, int cx, int cz) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return null;
        Structure structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .getValue(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(structureId)));
        if (structure == null) return null;
        StructureStart start = chunk.getStartForStructure(structure);
        if (start == null || !start.isValid()) return null;
        for (StructurePiece piece : start.getPieces()) {
            if (piece instanceof SetPiecePart part && part.data().has("origin")) return part.data().get("origin").getAsInt();
        }
        return null;
    }
}
