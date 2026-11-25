package com.mobstands.client;

import com.mobstands.entity.TrainCarEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/** Iron Express's trailing cars: the same minecart as yours. */
public class TrainCarRenderer extends EntityRenderer<TrainCarEntity> {
    public TrainCarRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.5F;
    }

    @Override
    public void render(TrainCarEntity e, float entityYaw, float partial, PoseStack ps, MultiBufferSource buffers, int light) {
        float yaw = Mth.rotLerp(partial, e.yRotO, e.getYRot());
        CartRide.drawCart(ps, buffers, light, yaw, 1.0F);
        super.render(e, entityYaw, partial, ps, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(TrainCarEntity e) {
        return ResourceLocation.withDefaultNamespace("textures/entity/minecart.png");
    }
}
