package com.mobstands.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.TickRateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Time Stop only stops time in the dimension it was used in. Minecraft's freeze is server-wide, so while time is
 * stopped somewhere, every other dimension is handed an ordinary, running clock instead.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelTickRateMixin {
    @Inject(method = "tickRateManager", at = @At("HEAD"), cancellable = true)
    private void mobstands$onlyThisDimension(CallbackInfoReturnable<TickRateManager> cir) {
        if (com.mobstands.stand.TimeStop.perDimension() && !com.mobstands.stand.TimeStop.stoppedIn((ServerLevel) (Object) this)) {
            cir.setReturnValue(com.mobstands.stand.TimeStop.NORMAL);
        }
    }
}
