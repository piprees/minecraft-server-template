package com.customdimensions.server.level;

import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * The three game rules a client holds (decisions.md RB-38): {@code immediate_respawn}
 * and {@code limited_crafting} as game events, {@code reduced_debug_info} as an
 * entity event on the player. Vanilla sends them at login from the player's
 * level, which answers through its {@link LevelGameRules.View}, and to every
 * player on a server-wide change from the store. Here a player is sent the values
 * of the level they are in after every change of level and every respawn, and
 * after a server-wide change of one of the three ({@code MinecraftServerGameRulesMixin}),
 * so a dimension's own values reach its players.
 */
public final class LevelClientRules {
    /** The client-held rules, by the rule objects vanilla's packets carry. */
    static final Set<GameRule<?>> HELD = Set.of(GameRules.IMMEDIATE_RESPAWN, GameRules.LIMITED_CRAFTING, GameRules.REDUCED_DEBUG_INFO);
    /** {@code Player.handleEntityEvent}'s ids: reduced debug info on, off. */
    static final byte REDUCED_DEBUG_ON = 22;
    static final byte REDUCED_DEBUG_OFF = 23;

    /** What a client is told: the three values in force in one level. */
    public record Values(boolean immediateRespawn, boolean limitedCrafting, boolean reducedDebugInfo) {
        static Values of(GameRules rules) {
            return new Values(rules.get(GameRules.IMMEDIATE_RESPAWN), rules.get(GameRules.LIMITED_CRAFTING),
                    rules.get(GameRules.REDUCED_DEBUG_INFO));
        }
    }

    private LevelClientRules() {
    }

    public static void register() {
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> send(player));
        ServerPlayerEvents.AFTER_RESPAWN.register((old, fresh, alive) -> send(fresh));
    }

    /** Whether a change of {@code rule} must reach each player as their own level's value. */
    public static boolean heldByClient(GameRule<?> rule) {
        return HELD.contains(rule);
    }

    /** The packets that set a client to {@code values}, for {@code player}. */
    static List<Packet<? super ClientGamePacketListener>> packets(Entity player, Values values) {
        return List.of(
                new ClientboundGameEventPacket(ClientboundGameEventPacket.IMMEDIATE_RESPAWN, values.immediateRespawn() ? 1.0F : 0.0F),
                new ClientboundGameEventPacket(ClientboundGameEventPacket.LIMITED_CRAFTING, values.limitedCrafting() ? 1.0F : 0.0F),
                new ClientboundEntityEventPacket(player, values.reducedDebugInfo() ? REDUCED_DEBUG_ON : REDUCED_DEBUG_OFF));
    }

    /** Sends {@code player} the values in force in the level they are in. */
    public static void send(ServerPlayer player) {
        if (player.connection == null) return;
        packets(player, Values.of(player.level().getGameRules())).forEach(player.connection::send);
    }

    /** After a server-wide change of {@code rule}, each of {@code players} is sent its own level's values. */
    public static void afterServerWideChange(GameRule<?> rule, List<ServerPlayer> players) {
        if (!heldByClient(rule)) return;
        players.forEach(LevelClientRules::send);
    }
}
