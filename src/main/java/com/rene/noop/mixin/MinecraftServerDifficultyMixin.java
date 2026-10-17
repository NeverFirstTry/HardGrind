package com.rene.noop.mixin;

import com.rene.noop.Hooks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The singleplayer owner may change difficulty from the Options screen without OP, and that request arrives here
 * unforced. HardGrind's own restores (and /difficulty, which needs OP anyway) are forced, so they still go through.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerDifficultyMixin {
    @Inject(method = "setDifficulty", at = @At("HEAD"), cancellable = true)
    private void hardgrind$keepDifficulty(Difficulty difficulty, boolean forced, CallbackInfo ci) {
        if (!forced && Hooks.difficultyLocked()) {
            ci.cancel();
        }
    }
}
