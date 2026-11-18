package com.mobstands.client;

import com.mobstands.entity.HeartAttackEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.CreeperModel;
import net.minecraft.client.model.MinecartModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/** Sheer Heart Attack: a little creeper sitting in a little minecart, rattling along, flashing white when it blows. */
public class HeartAttackRenderer extends EntityRenderer<HeartAttackEntity> {
    private static final ResourceLocation CART_TEX = ResourceLocation.withDefaultNamespace("textures/entity/minecart.png");
    private static final ResourceLocation CREEPER_TEX = ResourceLocation.withDefaultNamespace("textures/entity/creeper/creeper.png");
    /** Size of the minecart and of the creeper riding it (1 = normal). */
    private static final float CART_SIZE = 0.5F, CREEPER_SIZE = 0.42F;

    private final MinecartModel<HeartAttackEntity> cart;
    private final CreeperModel<HeartAttackEntity> creeper;

    public HeartAttackRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.cart = new MinecartModel<>(ctx.bakeLayer(ModelLayers.MINECART));
        this.creeper = new CreeperModel<>(ctx.bakeLayer(ModelLayers.CREEPER));
        this.shadowRadius = 0.3F;
    }

    @Override
    public void render(HeartAttackEntity e, float entityYaw, float partial, PoseStack ps, MultiBufferSource buffers, int light) {
        float age = e.tickCount + partial;
        float flash = e.blastFlash(partial);
        int overlay = OverlayTexture.pack(OverlayTexture.u(flash), 10);
        float yaw = Mth.rotLerp(partial, e.yRotO, e.getYRot());
        double speed = Math.sqrt(Mth.square(e.getX() - e.xo) + Mth.square(e.getZ() - e.zo));

        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
        // Rattling over the ground while it rolls.
        if (speed > 0.01D) {
            ps.mulPose(Axis.ZP.rotationDegrees(Mth.sin(age * 1.7F) * 2.0F));
            ps.translate(0.0F, Math.abs(Mth.sin(age * 1.1F)) * 0.025F, 0.0F);
        }

        // The little minecart (turned so it rolls lengthways).
        ps.pushPose();
        ps.scale(CART_SIZE, CART_SIZE, CART_SIZE);
        ps.translate(0.0F, 0.375F, 0.0F);
        ps.mulPose(Axis.YP.rotationDegrees(90.0F));
        ps.scale(-1.0F, -1.0F, 1.0F);
        cart.setupAnim(e, 0.0F, 0.0F, age, 0.0F, 0.0F);
        cart.renderToBuffer(ps, buffers.getBuffer(cart.renderType(CART_TEX)), light, overlay);
        ps.popPose();

        // The little creeper sitting in it, swelling as it blows.
        ps.pushPose();
        float swell = 1.0F + flash * 0.25F;
        ps.translate(0.0F, 0.06F, 0.0F);
        ps.scale(CREEPER_SIZE * swell, CREEPER_SIZE * swell, CREEPER_SIZE * swell);
        ps.scale(-1.0F, -1.0F, 1.0F);
        ps.translate(0.0F, -1.501F, 0.0F);
        creeper.setupAnim(e, 0.0F, 0.0F, age, 0.0F, 0.0F);
        creeper.renderToBuffer(ps, buffers.getBuffer(creeper.renderType(CREEPER_TEX)), light, overlay);
        ps.popPose();

        ps.popPose();
        super.render(e, entityYaw, partial, ps, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(HeartAttackEntity e) {
        return CART_TEX;
    }
}
