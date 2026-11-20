package com.mobstands.mixin;

import com.mobstands.client.GravityClient;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** C-Moon: standing on a wall or ceiling, the camera sits where your turned head is. Optional: never fails the game. */
@Mixin(Camera.class)
public abstract class CameraGravityMixin {
    @Shadow private Vec3 position;

    @Shadow protected abstract void setPosition(Vec3 pos);

    @Inject(method = "setup", at = @At("TAIL"), require = 0)
    private void mobstands$gravity(BlockGetter level, Entity entity, boolean detached, boolean mirror, float partial, CallbackInfo ci) {
        Vec3 d = GravityClient.cameraShift(entity, partial);
        if (d != null) setPosition(position.add(d));
    }
}
