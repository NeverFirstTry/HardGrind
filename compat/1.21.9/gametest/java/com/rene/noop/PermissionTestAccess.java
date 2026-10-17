package com.rene.noop;

import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Permission checks for the game tests, 1.21.9 - 1.21.10 (int permission levels). */
final class PermissionTestAccess {
    private PermissionTestAccess() {
    }

    static void opAtOwnerLevel(MinecraftServer server, ServerPlayer player) {
        // Explicit level: the test server hands out level 0 to plain op() calls.
        server.getPlayerList().op(player.nameAndId(), Optional.of(4), Optional.of(false));
    }

    /** True if the player passes the level-2 check used by cheat commands and the F3+F4 switcher. */
    static boolean hasCheatPermissions(ServerPlayer player) {
        return player.getPermissionLevel() > 0
                || player.hasPermissions(2)
                || player.createCommandSourceStack().hasPermission(2);
    }

    static boolean consoleIsOwner(MinecraftServer server) {
        return server.createCommandSourceStack().hasPermission(4);
    }
}
