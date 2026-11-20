package com.mobstands.mixin;

import com.mobstands.client.FuseClient;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Volatility: any mob about to blow flashes white like a creeper. Optional: never fails the game. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingRendererFuseMixin {
    @Inject(method = "getWhiteOverlayProgress", at = @At("HEAD"), cancellable = true, require = 0)
    private void mobstands$fuse(LivingEntity entity, float partial, CallbackInfoReturnable<Float> cir) {
        float f = FuseClient.whiteOverlay(entity, partial);
        if (f >= 0.0F) cir.setReturnValue(f);
    }
}
