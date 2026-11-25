package com.mobstands.client;

import com.mobstands.entity.ZipperEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * A Sticky Fingers zipper: a line of golden teeth running across the surface with a slider and a dangling pull tab.
 * As the slider runs, the teeth part behind it into a wide split - and inside the split is a dark purple pocket
 * dimension, glowing streaks drifting through it.
 */
public class ZipperRenderer extends EntityRenderer<ZipperEntity> {
    private static final ResourceLocation NONE = ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    public ZipperRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    // frame: b (across), a (along the zipper), n (out of the surface)
    private Vec3 b, a, n;
    private Matrix4f m;
    private float light;

    @Override
    public void render(ZipperEntity z, float entityYaw, float partial, PoseStack ps, MultiBufferSource buffers, int packedLight) {
        n = z.normalVec();
        Vec3 al = Vec3.atLowerCornerOf(z.along().getNormal());
        boolean mob = z.state() >= ZipperEntity.MOB_OPEN;
        // On walls (and creatures) the slider runs downward, so its tab hangs the way gravity pulls it.
        a = al.y > 0.5D ? al.scale(-1.0D) : al;
        b = n.cross(a);
        int packed = LevelRenderer.getLightColor(z.level(), BlockPos.containing(z.position().add(n.scale(0.5D))));
        light = Math.max(LightTexture.block(packed), LightTexture.sky(packed)) / 15.0F * 0.7F + 0.3F;

        float open = z.openAmount(partial);
        float len = z.len();
        int st = z.state();
        float since = z.since(partial);
        // Shut and finished: it fades away (shrinks into nothing).
        if ((st == ZipperEntity.CLOSE || st == ZipperEntity.MOB_CLOSE) && since > ZipperEntity.OPEN_TICKS) {
            len *= Mth.clamp(1.0F - (since - ZipperEntity.OPEN_TICKS) / 6.0F, 0.0F, 1.0F);
        }
        // Appearing: the zipper "draws" itself across the surface in the first couple of ticks.
        if ((st == ZipperEntity.OPEN_ENTER || st == ZipperEntity.MOB_OPEN) && z.tickCount < 3) {
            len *= Mth.clamp((z.tickCount + partial) / 3.0F, 0.0F, 1.0F);
        }
        if (len < 0.02F) return;

        ps.pushPose();
        m = ps.last().pose();
        float age = z.tickCount + partial;
        float v0 = -len * 0.5F, v1 = len * 0.5F;
        float s = v0 + open * len; // slider
        float maxW = mob ? Math.min(0.28F, len * 0.3F) : Math.min(0.5F, len * 0.3F);
        float span = s - v0;
        float w = Math.min(maxW, span * 0.36F);

        VertexConsumer solid = buffers.getBuffer(ModRenderTypes.cosmos());
        final int seg = 24;
        // The split: a dark core with a violet rim.
        if (span > 0.01F) {
            for (int i = 0; i < seg; i++) {
                float va = v0 + span * i / seg, vb = v0 + span * (i + 1) / seg;
                float ha = hw(va, v0, span, w), hb = hw(vb, v0, span, w);
                quad(solid, -ha, va, -0.0F, va, 0.0F, vb, -hb, vb, 0.004F, 0x4A1C80, 0x0C0316, 0x0C0316, 0x4A1C80, false);
                quad(solid, 0.0F, va, ha, va, hb, vb, 0.0F, vb, 0.004F, 0x0C0316, 0x4A1C80, 0x4A1C80, 0x0C0316, false);
            }
            // Streaks of light drifting through the dimension.
            VertexConsumer glow = buffers.getBuffer(ModRenderTypes.glow());
            for (int k = 0; k < 4; k++) {
                for (int i = 0; i < seg; i++) {
                    float va = v0 + span * i / seg, vb = v0 + span * (i + 1) / seg;
                    float ua = hw(va, v0, span, w) * 0.75F * Mth.sin(va * 9.0F + age * 0.15F + k * 1.7F);
                    float ub = hw(vb, v0, span, w) * 0.75F * Mth.sin(vb * 9.0F + age * 0.15F + k * 1.7F);
                    float t = 0.006F + 0.004F * k;
                    int alpha = (int) (110 + 60 * Mth.sin(age * 0.3F + k));
                    int col = k % 2 == 0 ? 0xB070FF : 0xFF7FE0;
                    glowQuad(glow, ua - t, va, ua + t, va, ub + t, vb, ub - t, vb, 0.006F, col, alpha);
                }
            }
            // (switching to the glow buffer closed the solid one: pick it back up)
            solid = buffers.getBuffer(ModRenderTypes.cosmos());
            // Teeth along both edges of the split.
            for (float v = v0 + 0.02F; v < s - 0.01F; v += 0.05F) {
                float h = hw(v, v0, span, w);
                tooth(solid, -h - 0.034F, -h + 0.004F, v - 0.012F, v + 0.012F);
                tooth(solid, h - 0.004F, h + 0.034F, v + 0.013F, v + 0.037F);
            }
        }
        // The closed part: teeth locked together, left and right in turn.
        int i = 0;
        for (float v = Math.max(s, v0) + 0.012F; v < v1 - 0.005F; v += 0.024F, i++) {
            if (i % 2 == 0) tooth(solid, -0.036F, 0.006F, v - 0.011F, v + 0.011F);
            else tooth(solid, -0.006F, 0.036F, v - 0.011F, v + 0.011F);
        }
        // End stops.
        box(solid, -0.03F, 0.03F, v1 - 0.03F, v1, 0.010F, 0xC8962E);
        box(solid, -0.03F, 0.03F, v0, v0 + 0.02F, 0.010F, 0xC8962E);
        // The slider and its pull tab (hanging toward the closed end).
        float sv = Mth.clamp(s, v0 + 0.04F, v1 - 0.02F);
        box(solid, -0.055F, 0.055F, sv - 0.04F, sv + 0.05F, 0.016F, 0xB8862A);
        box(solid, -0.045F, 0.045F, sv - 0.03F, sv + 0.04F, 0.020F, 0xF2C75A);
        float swing = Mth.sin(age * 0.25F) * 0.008F;
        box(solid, -0.022F + swing, 0.022F + swing, sv + 0.03F, sv + 0.15F, 0.024F, 0xE8B84A);
        box(solid, -0.010F + swing, 0.010F + swing, sv + 0.10F, sv + 0.13F, 0.028F, 0x3A2408); // the hole in the tab
        ps.popPose();
        super.render(z, entityYaw, partial, ps, buffers, packedLight);
    }

    /** Half-width of the split at {@code v}: a long lens, widest in the middle of the opened part. */
    private static float hw(float v, float v0, float span, float w) {
        float t = Mth.clamp((v - v0) / span, 0.0F, 1.0F);
        return w * (float) Math.pow(Math.max(0.0F, Mth.sin(t * Mth.PI)), 0.75D);
    }

    private void tooth(VertexConsumer vc, float u0, float u1, float va, float vb) {
        float mid = (va + vb) * 0.5F;
        box(vc, u0, u1, va, mid, 0.010F, 0xF0C25A);
        box(vc, u0, u1, mid, vb, 0.010F, 0x9A6E22);
    }

    private void box(VertexConsumer vc, float u0, float u1, float va, float vb, float e, int rgb) {
        quad(vc, u0, va, u1, va, u1, vb, u0, vb, e, rgb, rgb, rgb, rgb, true);
    }

    private void quad(VertexConsumer vc, float ua, float va, float ub, float vb2, float uc, float vc2, float ud, float vd, float e,
                      int c1, int c2, int c3, int c4, boolean lit) {
        vert(vc, ua, va, e, c1, lit);
        vert(vc, ub, vb2, e, c2, lit);
        vert(vc, uc, vc2, e, c3, lit);
        vert(vc, ud, vd, e, c4, lit);
    }

    private void vert(VertexConsumer vc, float u, float v, float e, int rgb, boolean lit) {
        float f = lit ? light : 1.0F;
        double x = b.x * u + a.x * v + n.x * e, y = b.y * u + a.y * v + n.y * e, zz = b.z * u + a.z * v + n.z * e;
        vc.addVertex(m, (float) x, (float) y, (float) zz)
                .setColor((int) (((rgb >> 16) & 255) * f), (int) (((rgb >> 8) & 255) * f), (int) ((rgb & 255) * f), 255);
    }

    private void glowQuad(VertexConsumer vc, float ua, float va, float ub, float vb2, float uc, float vc2, float ud, float vd, float e,
                          int rgb, int alpha) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, bl = rgb & 255;
        gv(vc, ua, va, e, r, g, bl, alpha);
        gv(vc, ub, vb2, e, r, g, bl, alpha);
        gv(vc, uc, vc2, e, r, g, bl, alpha);
        gv(vc, ud, vd, e, r, g, bl, alpha);
    }

    private void gv(VertexConsumer vc, float u, float v, float e, int r, int g, int bl, int alpha) {
        double x = b.x * u + a.x * v + n.x * e, y = b.y * u + a.y * v + n.y * e, zz = b.z * u + a.z * v + n.z * e;
        vc.addVertex(m, (float) x, (float) y, (float) zz).setColor(r, g, bl, Mth.clamp(alpha, 0, 255));
    }

    @Override
    public ResourceLocation getTextureLocation(ZipperEntity e) {
        return NONE;
    }
}
