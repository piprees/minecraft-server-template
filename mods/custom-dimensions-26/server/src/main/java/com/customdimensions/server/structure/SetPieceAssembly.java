package com.customdimensions.server.structure;

import com.customdimensions.core.structure.Assemblies;
import com.customdimensions.core.structure.SetPieceKinds;
import com.customdimensions.core.structure.SetPiecePlan;
import com.customdimensions.core.structure.SetPieceSpec;
import com.customdimensions.server.CustomDimensions;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A set piece's placements routed to their pieces: templates to their own
 * {@link SetPiecePart}, blocks, chests and spawners to the blocks part,
 * things to the things part, and an assembly expanded ({@link Assemblies})
 * with its parts seated on the ground at its own column (or at each part's,
 * for a part that follows the ground). A wreck's debris is drawn from its
 * template's own blocks.
 */
final class SetPieceAssembly {

    /** Blocks a template holds that are never debris. */
    static final Set<String> NOT_DEBRIS = Set.of("minecraft:air", "minecraft:structure_void", "minecraft:jigsaw", "minecraft:chest",
            "minecraft:barrel", "minecraft:spawner", "minecraft:water", "minecraft:lava", "minecraft:ender_chest");

    /** Where the parts of one start go. */
    record Sink(StructurePiecesBuilder builder, Structure.GenerationContext context, SetPieceSpec spec, BlockPos origin,
                List<SetPiecePart.Item> items, List<JsonObject> things) {}

    private SetPieceAssembly() {
    }

    /** Whether placement {@code p} is placed whole by {@link SetPieceThings}. */
    static boolean thing(SetPiecePlan.Placement p) {
        if (SetPieceKinds.THINGS.contains(p.kind())) return true;
        JsonObject o = p.source().params();
        return "chest".equals(p.kind()) && (o.has("arrangement") || !o.has("loot"));
    }

    /** Routes one placement at its world position. */
    static void route(Sink sink, SetPiecePlan.Placement p, int x, int y, int z) {
        if ("template".equals(p.kind())) {
            SetPiecePart.template(sink.context().structureTemplateManager(), sink.spec(), sink.origin(), p, x, y, z).ifPresent(sink.builder()::addPiece);
        } else if (thing(p)) {
            JsonObject t = p.source().params().deepCopy();
            t.addProperty("kind", p.kind());
            t.addProperty("x", x);
            t.addProperty("y", y);
            t.addProperty("z", z);
            t.addProperty("seed", p.seed());
            sink.things().add(t);
        } else {
            sink.items().add(SetPiecePart.Item.of(p, x, y, z));
        }
    }

    /** Expands assembly {@code p} and routes its parts; the whole assembly declines when its own column has no seat. */
    static void expand(Sink sink, SetPiecePlan.Placement p) {
        Structure.GenerationContext c = sink.context();
        int ax = sink.origin().getX() + p.dx();
        int az = sink.origin().getZ() + p.dz();
        Integer anchor = SetPieceStructure.groundAt(sink.spec(), c.chunkGenerator(), c.heightAccessor(), c.randomState(), ax, az, sink.origin().getY());
        if (anchor == null) {
            CustomDimensions.LOG.info("set piece {}: its {} at {}, {} has no seat; not placed", sink.spec().key(), p.kind(), ax, az);
            return;
        }
        List<SetPiecePlan.Placement> parts = Assemblies.expand(sink.spec(), p);
        List<String> debris = null;
        for (SetPiecePlan.Placement part : parts) {
            int x = sink.origin().getX() + part.dx();
            int z = sink.origin().getZ() + part.dz();
            Integer ground = part.source().params().has("follow")
                    ? SetPieceStructure.groundAt(sink.spec(), c.chunkGenerator(), c.heightAccessor(), c.randomState(), x, z, anchor)
                    : Integer.valueOf(anchor);
            if (ground == null) continue;
            SetPiecePlan.Placement placed = part;
            if (Assemblies.TEMPLATE_BLOCK.equals(part.block())) {
                if (debris == null) debris = debris(c.structureTemplateManager(), parts);
                if (debris.isEmpty()) continue;
                String block = debris.get((int) Math.floorMod(part.seed(), (long) debris.size()));
                placed = new SetPiecePlan.Placement(part.element(), part.kind(), part.dx(), part.dy(), part.dz(), part.source(), block,
                        part.rotation(), part.mirror(), part.age(), part.seed(), part.roll());
            }
            route(sink, placed, x, ground + part.dy(), z);
        }
    }

    /** The solid blocks of the first template among {@code parts}, by name: a wreck's own material. */
    static List<String> debris(StructureTemplateManager templates, List<SetPiecePlan.Placement> parts) {
        List<String> out = new ArrayList<>();
        for (SetPiecePlan.Placement part : parts) {
            if (!"template".equals(part.kind())) continue;
            StructureTemplate template = templates.get(Identifier.parse(part.source().string("id", ""))).orElse(null);
            if (template == null) return out;
            CompoundTag tag = template.save(new CompoundTag());
            ListTag palette = tag.contains("palette") ? tag.getListOrEmpty("palette") : tag.getListOrEmpty("palettes").getListOrEmpty(0);
            for (int i = 0; i < palette.size(); i++) {
                String name = palette.getCompoundOrEmpty(i).getStringOr("Name", "minecraft:air");
                if (!NOT_DEBRIS.contains(name) && !name.endsWith("_door") && !name.endsWith("_sign") && !out.contains(name)) out.add(name);
            }
            return out;
        }
        return out;
    }
}
