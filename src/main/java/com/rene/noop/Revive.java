package com.rene.noop;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;

/**
 * /hardgrind revive: a second chance after dying in a hardcore world. Hardcore goes off for good, the dead respawn
 * the way a normal death does, and the run log says so forever. Never in ironman worlds.
 */
final class Revive {
    private Revive() {
    }

    /** Why this player can't revive the world now, or null if they can. */
    static String refusal(MinecraftServer server, ServerPlayer player) {
        if (Policy.ironman() || Policy.dead()) {
            return "Ironman worlds can't be revived. Your death ends the run.";
        }
        if (!server.isHardcore()) {
            return "This isn't a hardcore world.";
        }
        // Dead hardcore players are spectators (after "Spectate World"); the interaction manager's mode, as in NoOpMod.
        if (player == null || player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
            return "You can only revive after dying.";
        }
        return null;
    }

    /** Turns hardcore off and brings back everyone who died. Call refusal() first. */
    static void revive(MinecraftServer server, ServerPlayer reviver) {
        String name = reviver.getName().getString();
        Policy.recordHardcoreRevive(server, name);  // from here on the game treats the world as not hardcore
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
                respawnDead(server, player);
            }
        }
        // Only now: in a non-hardcore world the refreshed pack counts anyone still in Spectator as cheating.
        Policy.refreshLock(server);
        server.getPlayerList().broadcastSystemMessage(Component.literal("[HardGrind] Hardcore is off for this world: "
                + name + " got a second chance. Rejoin the world to get normal hearts back.").withStyle(ChatFormatting.GOLD), false);
    }

    /** A spectator in a revived world: someone who died before the revive and wasn't online for it. */
    static boolean diedBeforeTheRevive(ServerPlayer player) {
        return Policy.hardcoreUnlocked() && player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR;
    }

    /**
     * Brings a dead (spectating) player back the way a normal death does: bed or respawn anchor, else world spawn,
     * empty inventory, Survival. Returns the new player entity.
     */
    static ServerPlayer respawnDead(MinecraftServer server, ServerPlayer player) {
        ServerPlayer revived = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
        // What vanilla's own respawn (ServerGamePacketListenerImpl.handleClientCommand) does next. Without it the
        // connection keeps driving the removed player: frozen until a rejoin, and on a server no rejoin at all.
        revived.connection.player = revived;
        revived.connection.resetPosition();
        revived.setGameMode(GameType.SURVIVAL);  // respawn keeps the old (Spectator) game mode
        return revived;
    }
}
