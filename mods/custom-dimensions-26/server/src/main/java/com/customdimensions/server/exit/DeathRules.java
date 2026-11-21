package com.customdimensions.server.exit;

import com.customdimensions.core.definition.Deaths;
import com.customdimensions.server.CustomDimensions;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * A definition's {@code deaths} (decisions.md RB-31): {@code keep} or
 * {@code drop} for the inventory and the experience of a player who dies
 * there; absent, the {@code keepInventory} game rule decides.
 * <p>
 * Fabric's allow-death event runs before vanilla's death, so what is kept is
 * taken there and handed back by the copy-from event on respawn; what is
 * dropped against the game rule is dropped there the way vanilla would. The
 * listener runs in a late phase, after any listener that cancels the death,
 * and stands down when a death-protection item (a totem) will save the
 * player. A death cancelled after it ran gives everything back the next tick.
 */
public final class DeathRules {
    private static final Map<UUID, Kept> KEPT = new ConcurrentHashMap<>();

    record Kept(ServerPlayer from, List<ItemStack> items, boolean xp, int level, float progress, int total, long tick) {
    }

    private DeathRules() {
    }

    static Deaths rulesFor(ServerLevel level) {
        Definitions.Of of = Definitions.of(level.dimension());
        return of == null ? null : of.definition().deaths();
    }

    /** The death will happen unless a death-protection item in either hand absorbs it, as vanilla checks it. */
    static boolean deathProtected(ServerPlayer player, DamageSource source) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return false;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).has(DataComponents.DEATH_PROTECTION)) {
                return true;
            }
        }
        return false;
    }

    static void beforeDeath(ServerPlayer player, ServerLevel level, DamageSource source) {
        Deaths deaths = rulesFor(level);
        if (deaths == null || player.isSpectator() || deathProtected(player, source)) {
            return;
        }
        boolean gameRule = level.getGameRules().get(GameRules.KEEP_INVENTORY);
        boolean keepItems = Deaths.keeps(deaths.inventory(), gameRule);
        boolean keepXp = Deaths.keeps(deaths.experience(), gameRule);
        Inventory inventory = player.getInventory();
        List<ItemStack> items = null;
        if (keepItems && !gameRule) {
            items = new ArrayList<>();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                items.add(inventory.removeItemNoUpdate(i));
            }
        } else if (!keepItems && gameRule) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (EnchantmentHelper.has(inventory.getItem(i), EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
                    inventory.removeItemNoUpdate(i);
                }
            }
            inventory.dropAll();
        }
        boolean takeXp = keepXp && !gameRule;
        Kept kept = new Kept(player, items, takeXp, player.experienceLevel, player.experienceProgress,
                player.totalExperience, level.getServer().getTickCount());
        if (!keepXp && gameRule) {
            ExperienceOrb.award(level, player.position(), Math.min(player.experienceLevel * 7, 100));
        }
        if (keepXp != gameRule) {
            player.experienceLevel = 0;
            player.experienceProgress = 0;
            player.totalExperience = 0;
        }
        if (items != null || takeXp) {
            KEPT.put(player.getUUID(), kept);
        }
    }

    /** Fabric's copy-from event, after vanilla's own copy: hands back what was kept. */
    static void copyFrom(ServerPlayer from, ServerPlayer to, boolean alive) {
        if (alive) {
            return;
        }
        Kept kept = KEPT.remove(from.getUUID());
        if (kept != null) {
            restore(kept, to);
        }
    }

    /** A death cancelled after the take: the player is still alive, so everything goes back. */
    static void serverTick(MinecraftServer server) {
        Iterator<Kept> it = KEPT.values().iterator();
        while (it.hasNext()) {
            Kept k = it.next();
            if (server.getTickCount() > k.tick() && k.from().isAlive() && !k.from().isRemoved()) {
                CustomDimensions.LOG.info("death of {} was cancelled; kept items and experience returned",
                        k.from().getName().getString());
                restore(k, k.from());
                it.remove();
            }
        }
    }

    private static void restore(Kept kept, ServerPlayer to) {
        if (kept.items() != null) {
            Inventory inventory = to.getInventory();
            for (int i = 0; i < kept.items().size() && i < inventory.getContainerSize(); i++) {
                if (!kept.items().get(i).isEmpty()) {
                    inventory.setItem(i, kept.items().get(i));
                }
            }
        }
        if (kept.xp()) {
            to.experienceLevel = kept.level();
            to.experienceProgress = kept.progress();
            to.totalExperience = kept.total();
        }
    }

    static void forget(UUID id) {
        KEPT.remove(id);
    }
}
