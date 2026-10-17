package com.rene.noop.mixin;

import com.rene.noop.Hooks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every player's permissions come from here: their commands, the F3+F4 game mode switcher, the "Allow Cheats"
 * toggle in Open to LAN, and the op status sent to the client. The server console and datapack functions get
 * theirs elsewhere, so they keep working. (1.21.11+: permission sets instead of int levels.)
 *
 * <p>Build worlds get vanilla's permissions back, and the singleplayer host gets theirs even if the world was
 * made with cheats off.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerPermissionMixin {
    @Inject(method = "getProfilePermissions", at = @At("HEAD"), cancellable = true)
    private void hardgrind$denyOp(NameAndId player, CallbackInfoReturnable<LevelBasedPermissionSet> cir) {
        if (!Hooks.buildWorld()) {
            cir.setReturnValue(LevelBasedPermissionSet.ALL);
        } else if (((MinecraftServer) (Object) this).isSingleplayerOwner(player)) {
            cir.setReturnValue(LevelBasedPermissionSet.OWNER);
        }
    }
}
