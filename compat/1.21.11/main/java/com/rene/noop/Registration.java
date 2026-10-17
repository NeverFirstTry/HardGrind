package com.rene.noop;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/** Game registrations for 1.21.11 and 26.x (ids are Identifiers). */
final class Registration {
    private Registration() {
    }

    /** The sound event the lock datapack points at; see {@link WorldLock#LOCK_SOUND}. */
    static void registerLockSound() {
        Identifier id = Identifier.parse(WorldLock.LOCK_SOUND);
        Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }

    /** A network payload type in HardGrind's namespace; see {@link FairModeSync}. */
    static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(NoOpMod.MOD_ID, path));
    }
}
