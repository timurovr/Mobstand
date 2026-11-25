package com.mobstands.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pigstep's Another World: each player only gets told about the entities in their own world. Whoever's in another
 * world (you and your mob) is removed from everyone else's game, and everyone else from yours - for as long as it lasts.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {
    @Shadow @Final Entity entity;

    @Shadow
    public abstract void removePlayer(ServerPlayer player);

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void mobstands$anotherWorld(ServerPlayer player, CallbackInfo ci) {
        if (player != entity && (com.mobstands.stand.OtherWorld.hiddenFrom(entity, player)
                || com.mobstands.stand.Zipper.hiddenFrom(entity, player))) {
            removePlayer(player);
            ci.cancel();
        }
    }
}
