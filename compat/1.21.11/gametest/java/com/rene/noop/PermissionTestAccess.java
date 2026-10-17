package com.rene.noop;

import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permissions;

/** Permission checks for the game tests, 1.21.11 and 26.x (permission sets). */
final class PermissionTestAccess {
    private PermissionTestAccess() {
    }

    static void opAtOwnerLevel(MinecraftServer server, ServerPlayer player) {
        server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.of(false));
    }

    /** True if the player passes the gamemaster check used by cheat commands and the F3+F4 switcher. */
    static boolean hasCheatPermissions(ServerPlayer player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
                || player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    static boolean consoleIsOwner(MinecraftServer server) {
        return server.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }
}
