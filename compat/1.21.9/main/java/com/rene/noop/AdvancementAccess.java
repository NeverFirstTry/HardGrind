package com.rene.noop;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Advancement lookup for 1.21.9 - 1.21.10 (ids are ResourceLocations). */
final class AdvancementAccess {
    private AdvancementAccess() {
    }

    static boolean isDone(MinecraftServer server, ServerPlayer player, String id) {
        AdvancementHolder advancement = server.getAdvancements().get(ResourceLocation.parse(id));
        return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }
}
