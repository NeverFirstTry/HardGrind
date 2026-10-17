package com.rene.noop;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Freecam (MinecraftFreecam) is allowed in locked worlds, but only in a fair mode: while a locked world is
 * running, {@link com.rene.noop.mixin.FreecamConfigMixin} forces collision on (no flying through blocks,
 * glass or doors) and no interacting from the camera, whatever the user puts in Freecam's own config.
 * Fullbright and fog settings are left to the user. On servers, {@link FairModeSync} tells the player's game
 * that the world is locked.
 */
final class FreecamGuard {
    // The config verified() is testing. Fair mode is forced on it in any world, so /hardgrind can check Freecam
    // in an unlocked world too, without touching how the player's own Freecam behaves there.
    private static volatile Object probe;

    private FreecamGuard() {
    }

    static boolean installed() {
        return FabricLoader.getInstance().isModLoaded("freecam");
    }

    static boolean isProbe(Object freecamConfig) {
        return freecamConfig != null && freecamConfig == probe;
    }

    /**
     * True if the fair-mode override really works on the installed Freecam version: a Freecam config with
     * noclip and camera interactions switched on must still come back with them off. If Freecam changed its
     * internals, this fails, and locked worlds treat Freecam as a cheat mod until HardGrind is updated.
     */
    static boolean verified() {
        try {
            Class<?> dataClass = Class.forName("net.xolt.freecam.config.model.ModConfigDTO");
            Object data = dataClass.getConstructor().newInstance();
            Object collision = dataClass.getField("collision").get(data);
            collision.getClass().getField("ignoreAll").setBoolean(collision, true);
            Object utility = dataClass.getField("utility").get(data);
            utility.getClass().getField("allowInteract").setBoolean(utility, true);

            Object config = configFor(dataClass, data);
            Class<?> configClass = config.getClass();
            probe = config;
            try {
                // ignoreCollisionWith is what Freecam's block collision actually asks, so it's checked too.
                boolean noclip = (boolean) configClass.getMethod("ignoreAllCollision").invoke(config)
                        || (boolean) configClass.getMethod("ignoreCollisionWith", Block.class).invoke(config, Blocks.STONE)
                        || !(boolean) configClass.getMethod("shouldCheckInitialCollision").invoke(config);
                boolean interactions = !(boolean) configClass.getMethod("shouldPreventInteractions").invoke(config);
                return !noclip && !interactions;
            } finally {
                probe = null;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            NoOpMod.LOG.warn("[HardGrind] Can't check this Freecam version: {}", e.toString());
            return false;
        }
    }

    /** Why a locked world won't let the player in when {@link #verified()} fails. */
    static Component unsupportedMessage() {
        return Component.literal("HardGrind: this world is locked, and HardGrind can't force this version "
                + "of Freecam into fair mode (collision on).\n\nRemove Freecam, or update HardGrind, to keep playing.");
    }

    /** The object Freecam asks for its settings: the config data itself on 1.4, a ModConfigImpl wrapping it on 1.5. */
    private static Object configFor(Class<?> dataClass, Object data) throws ReflectiveOperationException {
        try {
            return Class.forName("net.xolt.freecam.config.model.ModConfigImpl").getConstructor(dataClass).newInstance(data);
        } catch (ClassNotFoundException e) {
            return data;
        }
    }
}
