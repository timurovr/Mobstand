package com.mobstands.mixin;

import com.mojang.serialization.Lifecycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips the "Experimental Settings" warning when creating a world: just create it. */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {
    @Inject(method = "confirmWorldCreation", at = @At("HEAD"), cancellable = true, require = 0)
    private static void mobstands$noWarning(Minecraft mc, CreateWorldScreen screen, Lifecycle lifecycle, Runnable create, boolean skipWarnings, CallbackInfo ci) {
        create.run();
        ci.cancel();
    }
}
