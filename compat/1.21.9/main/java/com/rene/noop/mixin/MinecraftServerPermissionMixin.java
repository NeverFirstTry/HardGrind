package com.rene.noop.mixin;

import com.rene.noop.Hooks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every player's op level comes from here: their commands, the F3+F4 game mode switcher, the "Allow Cheats"
 * toggle in Open to LAN, and the op status sent to the client. The server console and datapack functions get
 * their level elsewhere, so they keep working. (1.21.9 - 1.21.10: levels are plain ints.)
 *
 * <p>Build worlds get vanilla's levels back, and the singleplayer host gets theirs even if the world was made
 * with cheats off.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerPermissionMixin {
    @Inject(method = "getProfilePermissions", at = @At("HEAD"), cancellable = true)
    private void hardgrind$denyOp(NameAndId player, CallbackInfoReturnable<Integer> cir) {
        if (!Hooks.buildWorld()) {
            cir.setReturnValue(0);
        } else if (((MinecraftServer) (Object) this).isSingleplayerOwner(player)) {
            cir.setReturnValue(4);
        }
    }
}
