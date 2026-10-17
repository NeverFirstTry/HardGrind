package com.rene.noop.mixin;

import com.rene.noop.Hooks;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A hardcore world revived with /hardgrind revive isn't hardcore anymore. Everything that asks (respawning after a
 * death, the hearts sent when a player joins, HardGrind itself) asks this method, which is the same in every
 * supported version, so the flag in level.dat stays as it is.
 */
@Mixin(PrimaryLevelData.class)
public abstract class PrimaryLevelDataHardcoreMixin {
    @Inject(method = "isHardcore", at = @At("HEAD"), cancellable = true)
    private void hardgrind$revived(CallbackInfoReturnable<Boolean> cir) {
        if (Hooks.hardcoreUnlocked()) {
            cir.setReturnValue(false);
        }
    }
}
