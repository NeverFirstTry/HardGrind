package com.rene.noop.mixin;

import com.rene.noop.Hooks;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fair-mode Freecam in locked worlds (see com.rene.noop.FreecamGuard). Optional: does nothing if Freecam isn't
 * installed, and FreecamGuard.verified() catches a Freecam version this no longer matches.
 *
 * <p>Freecam 1.4 answers these on ModConfigDTO itself; 1.5 moved them to ModConfigImpl and left ModConfigDTO as
 * plain data, so both are targeted and whichever has the methods gets the injections.
 */
@Pseudo
@Mixin(targets = {"net.xolt.freecam.config.model.ModConfigDTO", "net.xolt.freecam.config.model.ModConfigImpl"}, remap = false)
public abstract class FreecamConfigMixin {
    @Inject(method = "ignoreAllCollision", at = @At("HEAD"), cancellable = true, require = 0)
    private void hardgrind$noNoclip(CallbackInfoReturnable<Boolean> cir) {
        if (Hooks.freecamFairMode(this)) {
            cir.setReturnValue(false);
        }
    }

    // Reads Freecam's noclip setting directly, not through ignoreAllCollision(): without this, noclip on would
    // skip the wall check when the camera is first placed, so it could start on the far side of a wall.
    @Inject(method = "shouldCheckInitialCollision", at = @At("HEAD"), cancellable = true, require = 0)
    private void hardgrind$checkStart(CallbackInfoReturnable<Boolean> cir) {
        if (Hooks.freecamFairMode(this)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "ignoreCollisionWith", at = @At("HEAD"), cancellable = true, require = 0)
    private void hardgrind$solidBlocks(Block block, CallbackInfoReturnable<Boolean> cir) {
        if (Hooks.freecamFairMode(this)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "shouldPreventInteractions", at = @At("HEAD"), cancellable = true, require = 0)
    private void hardgrind$lookOnly(CallbackInfoReturnable<Boolean> cir) {
        if (Hooks.freecamFairMode(this)) {
            cir.setReturnValue(true);
        }
    }
}
