package com.customdimensions.server.structure;

import com.customdimensions.core.structure.LootRules;
import com.customdimensions.core.structure.StructureSpec;
import com.customdimensions.server.CustomDimensions;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A level's structure-control loot rules ({@code structures.loot}) applied to
 * container loot as it is rolled: a chest, barrel or other container opened in
 * a piece of a structure a rule names gains the rule's item at its chance.
 * Container loot is told apart by its parameters: an origin, no block, tool or
 * damage, and no entity but the player opening it. A table nested in another
 * shares its context and rolls once.
 */
public final class StructureLoot {

    private static final Set<LootContext> ROLLED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private StructureLoot() {
    }

    public static void register() {
        LootTableEvents.MODIFY_DROPS.register((table, context, drops) -> {
            if (!isContainer(context)) return;
            ServerLevel level = context.getLevel();
            StructureSpec spec = StructureRuntime.spec(level.dimension().identifier().toString()).orElse(null);
            if (spec == null || spec.loot().isEmpty() || !ROLLED.add(context)) return;
            BlockPos pos = BlockPos.containing(context.getOptional(LootContextParams.ORIGIN));
            for (String id : LootRules.roll(spec.loot(), target -> standsIn(level, pos, target), () -> context.getRandom().nextDouble())) {
                Optional<Item> item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id));
                if (item.isPresent()) {
                    drops.add(new ItemStack(item.get()));
                } else {
                    CustomDimensions.LOG.warn("{}: structures.loot item {} is not an item", level.dimension().identifier(), id);
                }
            }
        });
    }

    static boolean isContainer(LootContext context) {
        Vec3 origin = context.getOptional(LootContextParams.ORIGIN);
        Entity entity = context.getOptional(LootContextParams.THIS_ENTITY);
        return origin != null && !context.hasParameter(LootContextParams.BLOCK_STATE) && !context.hasParameter(LootContextParams.TOOL)
                && !context.hasParameter(LootContextParams.DAMAGE_SOURCE) && (entity == null || entity instanceof Player);
    }

    /** Whether {@code pos} lies in a piece of a structure {@code target} names: a group under this level's control, an id or a #tag. */
    static boolean standsIn(ServerLevel level, BlockPos pos, String target) {
        return level.structureManager().getStructureWithPieceAt(pos, h -> named(level, h, target)).isValid();
    }

    private static boolean named(ServerLevel level, Holder<Structure> structure, String target) {
        if (target.startsWith("#")) {
            return structure.is(TagKey.create(Registries.STRUCTURE, Identifier.parse(target.substring(1))));
        }
        if (target.contains(":")) {
            return structure.unwrapKey().map(k -> k.identifier().toString().equals(target)).orElse(false);
        }
        return target.equals(StructureRuntime.groupOf(level.dimension(), structure.value()));
    }
}
