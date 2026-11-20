package com.mobstands.mixin;

import com.mobstands.client.GravityClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * C-Moon, your own game only: while your gravity is turned, the mouse turns you around your new "up", and your eyes
 * (what you aim and mine from) are where your turned head really is. Optional: never fails the game.
 */
@Mixin(Entity.class)
public abstract class EntityGravityMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true, require = 0)
    private void mobstands$turn(double yRot, double xRot, CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer && GravityClient.onTurn(yRot, xRot)) ci.cancel();
    }

    @Inject(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true, require = 0)
    private void mobstands$eye(float partial, CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this instanceof LocalPlayer) {
            Vec3 eye = GravityClient.eye(partial);
            if (eye != null) cir.setReturnValue(eye);
        }
    }
}
