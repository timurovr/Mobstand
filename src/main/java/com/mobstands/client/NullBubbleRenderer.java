package com.mobstands.client;

import com.mobstands.entity.NullBubbleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;

/** The Null Bubble: a shimmering golden bubble, a gold nugget tumbling fast inside it on every axis at once. */
public class NullBubbleRenderer extends EntityRenderer<NullBubbleEntity> {
    /** Bubble radius (blocks). */
    private static final float R = 0.11F;
    private static final ItemStack NUGGET = new ItemStack(Items.GOLD_NUGGET);

    public NullBubbleRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(NullBubbleEntity e, float entityYaw, float partial, PoseStack ps, MultiBufferSource buffers, int light) {
        float age = e.tickCount + partial;
        ps.pushPose();
        ps.translate(0.0F, R, 0.0F);
        // Pops in when it's made.
        float grow = Mth.clamp(age / 5.0F, 0.0F, 1.0F);
        float wobble = 1.0F + Mth.sin(age * 0.5F) * 0.03F;
        ps.scale(grow * wobble, grow * (2.0F - wobble), grow * wobble);

        // The nugget, tumbling fast in different directions at once.
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(age * 41.0F));
        ps.mulPose(Axis.XP.rotationDegrees(age * 29.0F));
        ps.mulPose(Axis.ZP.rotationDegrees(age * -53.0F));
        ps.scale(0.45F, 0.45F, 0.45F);
        ps.translate(0.0F, -0.1F, 0.0F);
        Minecraft.getInstance().getItemRenderer().renderStatic(NUGGET, ItemDisplayContext.GROUND, LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, ps, buffers, e.level(), e.getId());
        ps.popPose();

        // The bubble: a pixel-art golden bubble (bright outline, faint film, a glint), always facing you.
        ps.mulPose(entityRenderDispatcher.cameraOrientation());
        float pulse = 1.0F + 0.05F * Mth.sin(age * 0.6F);
        float half = R * 1.3F * pulse;
        PoseStack.Pose pose = ps.last();
        VertexConsumer vc = buffers.getBuffer(net.minecraft.client.renderer.RenderType.entityTranslucent(TEX));
        quad(vc, pose, half);
        ps.popPose();
        super.render(e, entityYaw, partial, ps, buffers, light);
    }

    private static final ResourceLocation TEX = ResourceLocation.fromNamespaceAndPath("mobstands", "textures/entity/null_bubble.png");

    private static void quad(VertexConsumer vc, PoseStack.Pose pose, float h) {
        corner(vc, pose, -h, -h, 0.0F, 1.0F);
        corner(vc, pose, h, -h, 1.0F, 1.0F);
        corner(vc, pose, h, h, 1.0F, 0.0F);
        corner(vc, pose, -h, h, 0.0F, 0.0F);
    }

    private static void corner(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float u, float v) {
        vc.addVertex(pose, x, y, 0.0F).setColor(255, 255, 255, 255).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    /** A flat ring (in the XY plane) from radius {@code r0} to {@code r1}: bright at the inner edge, fading out. */
    private static void ring(VertexConsumer vc, Matrix4f m, float r0, float r1, int cr, int cg, int cb, float alpha) {
        final int seg = 32;
        int a = (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        for (int i = 0; i < seg; i++) {
            float p0 = Mth.TWO_PI * i / seg, p1 = Mth.TWO_PI * (i + 1) / seg;
            float c0 = Mth.cos(p0), s0 = Mth.sin(p0), c1 = Mth.cos(p1), s1 = Mth.sin(p1);
            vc.addVertex(m, c0 * r0, s0 * r0, 0.0F).setColor(cr, cg, cb, a);
            vc.addVertex(m, c0 * r1, s0 * r1, 0.0F).setColor(cr, cg, cb, 0);
            vc.addVertex(m, c1 * r1, s1 * r1, 0.0F).setColor(cr, cg, cb, 0);
            vc.addVertex(m, c1 * r0, s1 * r0, 0.0F).setColor(cr, cg, cb, a);
        }
    }

    /** A UV sphere of coloured quads. */
    private static void sphere(VertexConsumer vc, Matrix4f m, float r, int cr, int cg, int cb, float alpha) {
        final int lat = 10, lon = 16;
        int a = (int) (alpha * 255.0F);
        for (int i = 0; i < lat; i++) {
            float t0 = Mth.PI * i / lat - Mth.HALF_PI, t1 = Mth.PI * (i + 1) / lat - Mth.HALF_PI;
            for (int j = 0; j < lon; j++) {
                float p0 = Mth.TWO_PI * j / lon, p1 = Mth.TWO_PI * (j + 1) / lon;
                v(vc, m, r, t0, p0, cr, cg, cb, a);
                v(vc, m, r, t0, p1, cr, cg, cb, a);
                v(vc, m, r, t1, p1, cr, cg, cb, a);
                v(vc, m, r, t1, p0, cr, cg, cb, a);
            }
        }
    }

    private static void v(VertexConsumer vc, Matrix4f m, float r, float t, float p, int cr, int cg, int cb, int a) {
        float c = Mth.cos(t);
        vc.addVertex(m, r * c * Mth.cos(p), r * Mth.sin(t), r * c * Mth.sin(p)).setColor(cr, cg, cb, a);
    }

    @Override
    public ResourceLocation getTextureLocation(NullBubbleEntity e) {
        return TEX;
    }
}
