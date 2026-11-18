package com.mobstands.mixin;

import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stopped time uses the game's own freeze: let players' Stands (and freshly shot arrows) keep moving through it. */
@Mixin(TickRateManager.class)
public abstract class TickRateManagerMixin {
    @Inject(method = "isEntityFrozen", at = @At("HEAD"), cancellable = true)
    private void mobstands$standsMoveInStoppedTime(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (com.mobstands.stand.TimeStop.keepsMoving(entity)) cir.setReturnValue(false);
    }
}
