package com.mobstands.mixin;

import com.mobstands.stand.GravityBox;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** C-Moon: on a wall, your hitbox lies along your body (see GravityBox). Optional: never fails the game. */
@Mixin(Entity.class)
public abstract class EntityBoxMixin {
    @Inject(method = "makeBoundingBox()Lnet/minecraft/world/phys/AABB;", at = @At("HEAD"), cancellable = true, require = 0)
    private void mobstands$box(CallbackInfoReturnable<AABB> cir) {
        AABB box = GravityBox.box((Entity) (Object) this);
        if (box != null) cir.setReturnValue(box);
    }
}
