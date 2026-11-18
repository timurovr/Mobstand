package com.mobstands.mixin;

import com.mojang.serialization.Lifecycle;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The mod adds a dimension (the Phantom's dream world) through a datapack, which makes Minecraft flag every world as
 * using "Experimental Settings" and nag you each time you open it. It isn't experimental: report it as stable.
 */
@Mixin(PrimaryLevelData.class)
public abstract class PrimaryLevelDataMixin {
    @Inject(method = "worldGenSettingsLifecycle", at = @At("HEAD"), cancellable = true, require = 0)
    private void mobstands$stable(CallbackInfoReturnable<Lifecycle> cir) {
        cir.setReturnValue(Lifecycle.stable());
    }
}
