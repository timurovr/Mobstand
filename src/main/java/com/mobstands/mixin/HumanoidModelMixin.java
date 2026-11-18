package com.mobstands.mixin;

import com.mobstands.client.PlayerPoses;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs after vanilla has posed a humanoid model, so we can layer our player animations on top. */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void mobstands$playerPoses(LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                                       float netHeadYaw, float headPitch, CallbackInfo ci) {
        PlayerPoses.apply((HumanoidModel<?>) (Object) this, entity, limbSwing, limbSwingAmount, ageInTicks, headPitch);
    }
}
