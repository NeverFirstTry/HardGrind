package com.rene.noop;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** The player's side of {@link FairModeSync}: Freecam's fair mode follows the lock of the world being played. */
public class NoOpClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(FairModeSync.Payload.TYPE, (payload, context) -> {
            FairModeSync.serverLocked = payload.locked();
            // On a server only this game knows which Freecam it has, so it applies NoOpMod's join rule itself.
            if (payload.locked() && FreecamGuard.installed() && !FreecamGuard.verified()) {
                context.player().connection.getConnection().disconnect(FreecamGuard.unsupportedMessage());
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> FairModeSync.serverLocked = false);
    }
}
