package com.rene.noop;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tells each player's game whether the world it's playing is locked. Freecam runs in the player's own game, which
 * only knows the world's policy by itself in singleplayer; this makes fair mode (see {@link FreecamGuard}) hold on
 * dedicated servers and for LAN guests too. The receiving side is {@link NoOpClient}.
 */
final class FairModeSync {
    /** Client side: the world this game is connected to is locked. Reset on disconnect. */
    static volatile boolean serverLocked;

    private FairModeSync() {
    }

    record Payload(boolean locked) implements CustomPacketPayload {
        static final CustomPacketPayload.Type<Payload> TYPE = Registration.payloadType("world_locked");
        static final StreamCodec<ByteBuf, Payload> CODEC = ByteBufCodecs.BOOL.map(Payload::new, Payload::locked);

        @Override
        public Type<Payload> type() {
            return TYPE;
        }
    }

    static void register() {
        clientboundPlay().register(Payload.TYPE, Payload.CODEC);
    }

    /** Tells one player whether this world is locked. Skipped for a game without HardGrind (an unlocked world). */
    static void send(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, Payload.TYPE)) {
            ServerPlayNetworking.send(player, new Payload(Policy.lockActive()));
        }
    }

    /** After /hardgrind lock, unlock or ironman. */
    static void sendAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(player);
        }
    }

    // Fabric API renamed playS2C() to clientboundPlay() for 26.1, and compat/1.21.11 builds jars on both sides of
    // that. The game tests run this on every version, so a future rename fails the build rather than a player's game.
    @SuppressWarnings("unchecked")
    private static PayloadTypeRegistry<RegistryFriendlyByteBuf> clientboundPlay() {
        for (String name : new String[] {"clientboundPlay", "playS2C"}) {
            try {
                return (PayloadTypeRegistry<RegistryFriendlyByteBuf>) PayloadTypeRegistry.class.getMethod(name).invoke(null);
            } catch (NoSuchMethodException e) {
                // Not this version's name; try the next.
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Can't register HardGrind's network payload", e);
            }
        }
        throw new IllegalStateException("Can't register HardGrind's network payload: Fabric API has no clientbound play registry");
    }
}
