package com.mobstands.client;

import com.mobstands.entity.SpinItemEntity;
import com.mobstands.stand.StandManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;

/** The spinning item on the floor: whirling fast (winding down), with a yellow glow and a ring marking its area. */
public class SpinItemRenderer extends EntityRenderer<SpinItemEntity> {
    public SpinItemRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.15F;
    }

    @Override
    public void render(SpinItemEntity e, float yaw, float partial, PoseStack ps, MultiBufferSource buffers, int light) {
        renderTrail(e, partial, ps, buffers);
        float el = e.spinAge(partial);
        float k = e.speed(partial); // spin speed 1 -> 0
        float angle = e.angle(partial);
        // The item, spinning just above the ground (with a little shudder while it's fast).
        ps.pushPose();
        ps.translate(0.0F, 0.2F, 0.0F);
        SpinFx.wobble(ps, el, k);
        ps.mulPose(Axis.YP.rotationDegrees(angle));
        // Spins around its own centre. 3D models (blocks, the Steel Ball) spin upright; swords and other flat items lie
        // flat and whirl horizontally like a saw blade (drawn without the ground offset, which would swing them off-centre).
        net.minecraft.client.resources.model.BakedModel model = Minecraft.getInstance().getItemRenderer().getModel(e.getItem(), e.level(), null, e.getId());
        if (model.isGui3d()) {
            ps.translate(0.0F, -0.15F, 0.0F); // the ground pose lifts 3D models a little: keep the centre on the axis
            ps.scale(1.6F, 1.6F, 1.6F);
            Minecraft.getInstance().getItemRenderer().renderStatic(e.getItem(), ItemDisplayContext.GROUND, light,
                    OverlayTexture.NO_OVERLAY, ps, buffers, e.level(), e.getId());
        } else {
            ps.mulPose(Axis.XP.rotationDegrees(90.0F));
            ps.scale(0.8F, 0.8F, 0.8F);
            Minecraft.getInstance().getItemRenderer().renderStatic(e.getItem(), ItemDisplayContext.FIXED, light,
                    OverlayTexture.NO_OVERLAY, ps, buffers, e.level(), e.getId());
        }
        ps.popPose();
        // Yellow glow around it, and a pulsing ring on the ground showing the area it covers.
        float pulse = 0.75F + 0.25F * Mth.sin(el * 0.25F);
        ps.pushPose();
        ps.translate(0.0F, 0.2F, 0.0F);
        SpinFx.drawGlow(ps, buffers, el, 0.3F, 0.55F * (0.35F + 0.65F * k) * pulse);
        ps.popPose();
        super.render(e, yaw, partial, ps, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(SpinItemEntity e) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    /** The golden streak behind a thrown spinning item: a glowing ribbon that tapers off toward the tail. */
    private static void renderTrail(SpinItemEntity e, float partial, PoseStack ps, MultiBufferSource buffers) {
        if (e.trail.size() < 2) return;
        net.minecraft.world.phys.Vec3 here = e.getPosition(partial);
        java.util.List<net.minecraft.world.phys.Vec3> pts = new java.util.ArrayList<>();
        pts.add(here);
        pts.addAll(e.trail);
        net.minecraft.world.phys.Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        com.mojang.blaze3d.vertex.VertexConsumer vc = buffers.getBuffer(com.mobstands.client.ModRenderTypes.glow());
        org.joml.Matrix4f m = ps.last().pose();
        int n = pts.size();
        for (int pass = 0; pass < 2; pass++) {
            float width = pass == 0 ? 0.28F : 0.09F;
            int r = 255, g = pass == 0 ? 205 : 245, b = pass == 0 ? 60 : 180;
            float maxA = pass == 0 ? 0.45F : 0.9F;
            for (int i = 0; i < n - 1; i++) {
                net.minecraft.world.phys.Vec3 p0 = pts.get(i).add(0.0D, 0.2D, 0.0D), p1 = pts.get(i + 1).add(0.0D, 0.2D, 0.0D);
                net.minecraft.world.phys.Vec3 seg = p1.subtract(p0);
                if (seg.lengthSqr() < 1.0E-6D) continue;
                float t0 = i / (float) (n - 1), t1 = (i + 1) / (float) (n - 1);
                float w0 = width * (1.0F - t0), w1 = width * (1.0F - t1);
                int a0 = (int) (255 * maxA * (1.0F - t0)), a1 = (int) (255 * maxA * (1.0F - t1));
                net.minecraft.world.phys.Vec3 side0 = seg.cross(p0.subtract(cam)).normalize();
                net.minecraft.world.phys.Vec3 side1 = seg.cross(p1.subtract(cam)).normalize();
                net.minecraft.world.phys.Vec3 l0 = p0.subtract(here).add(side0.scale(w0)), r0 = p0.subtract(here).subtract(side0.scale(w0));
                net.minecraft.world.phys.Vec3 l1 = p1.subtract(here).add(side1.scale(w1)), r1 = p1.subtract(here).subtract(side1.scale(w1));
                vtx(vc, m, l0, r, g, b, a0); vtx(vc, m, r0, r, g, b, a0); vtx(vc, m, r1, r, g, b, a1); vtx(vc, m, l1, r, g, b, a1);
                vtx(vc, m, l1, r, g, b, a1); vtx(vc, m, r1, r, g, b, a1); vtx(vc, m, r0, r, g, b, a0); vtx(vc, m, l0, r, g, b, a0);
            }
        }
    }

    private static void vtx(com.mojang.blaze3d.vertex.VertexConsumer vc, org.joml.Matrix4f m, net.minecraft.world.phys.Vec3 p, int r, int g, int b, int a) {
        vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }

    @Override
    public boolean shouldRender(SpinItemEntity e, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z) {
        return true; // the trail can stretch outside its tiny box
    }
}
