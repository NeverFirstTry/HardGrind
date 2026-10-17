package com.rene.noop;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Advancement lookup for 1.21.11 and 26.x (ids are Identifiers). */
final class AdvancementAccess {
    private AdvancementAccess() {
    }

    static boolean isDone(MinecraftServer server, ServerPlayer player, String id) {
        AdvancementHolder advancement = server.getAdvancements().get(Identifier.parse(id));
        return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }
}
