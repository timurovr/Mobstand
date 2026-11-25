package com.mobstands.client;

import com.mobstands.entity.NuggetShotEntity;
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
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * GER's Iron Shot: the nugget charging up between GER's fingers (spinning faster, a swelling glow, two rings whirling
 * round it, trembling before the flick), then a muzzle flash, a blazing white-hot beam with gold and emerald strands
 * spiralling round it and sonic-boom rings punched out along it, and a blinding impact with a huge ring.
 */
public class NuggetShotRenderer extends EntityRenderer<NuggetShotEntity> {
    private static final ItemStack NUGGET = new ItemStack(Items.IRON_NUGGET);

    public NuggetShotRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    private static final int GOLD = 0xF2C744, EMERALD = 0x3CFF8A;
    /** Ticks for the shot to cross the whole distance, and for the beam to burn off. */
    private static final float FLIGHT = 1.0F, BURN = 16.0F;

    @Override
    public void render(NuggetShotEntity e, float entityYaw, float partial, PoseStack ps, MultiBufferSource buffers, int light) {
        float since = e.sinceFired(partial);
        float age = e.tickCount + partial;
        var cam = Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation();
        if (since < 0.0F) {
            // Charging up between GER's fingers: spinning faster and faster, a glow swelling round it, two golden
            // rings whirling about it, and it starts to tremble just before the flick.
            float charge = Mth.clamp(age / com.mobstands.stand.GerShot.HOLD, 0.0F, 1.0F);
            float tremble = charge > 0.7F ? (charge - 0.7F) / 0.3F : 0.0F;
            ps.pushPose();
            ps.translate(Mth.sin(age * 7.3F) * 0.015F * tremble, Mth.sin(age * 9.1F) * 0.015F * tremble, 0.0F);
            // the glow (always facing you)
            ps.pushPose();
            ps.mulPose(cam);
            float throb = 0.85F + 0.15F * Mth.sin(age * (0.6F + charge * 1.4F));
            StandRenderer.disc(ps, buffers, 0.25F + 0.35F * charge * throb, GOLD, 0.35F + 0.4F * charge);
            StandRenderer.disc(ps, buffers, 0.1F + 0.12F * charge, 0xFFFFFF, 0.3F + 0.5F * charge * charge);
            ps.popPose();
            // two rings whirling round it on different tilts
            for (int i = 0; i < 2; i++) {
                ps.pushPose();
                ps.mulPose(Axis.YP.rotationDegrees(age * (8.0F + 14.0F * charge) * (i == 0 ? 1 : -1)));
                ps.mulPose(Axis.XP.rotationDegrees(i == 0 ? 70.0F : 20.0F));
                StandRenderer.ring(ps, buffers, 0.32F - 0.08F * charge, 0.03F, i == 0 ? GOLD : EMERALD, 0.25F + 0.55F * charge);
                ps.popPose();
            }
            // the nugget
            ps.mulPose(Axis.YP.rotationDegrees(age * (12.0F + 30.0F * charge)));
            ps.scale(1.6F, 1.6F, 1.6F);
            Minecraft.getInstance().getItemRenderer().renderStatic(NUGGET, ItemDisplayContext.GROUND, LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY, ps, buffers, e.level(), e.getId());
            ps.popPose();
            return;
        }
        // Fired.
        Vec3 from = e.getPosition(partial);
        Vec3 d = e.end().subtract(from);
        double len = d.length();
        if (len < 0.01D) return;
        // Burns off fast: gone in under a second.
        float fade = 1.0F - Mth.clamp(since / BURN, 0.0F, 1.0F);
        fade = fade * fade;
        float flicker = 0.85F + 0.15F * Mth.sin(age * 4.1F);
        // It's there almost instantly (one tick to cross the whole distance)...
        float flight = Mth.clamp(since / FLIGHT, 0.0F, 1.0F);
        float reach = (float) (len * flight);
        // ...and blasts out fat and white-hot, then snaps down to a thinner beam.
        float blast = 1.0F + 1.6F * (float) Math.exp(-since / 1.2F);

        // The muzzle flash where it was flicked from.
        if (since < 3.0F) {
            ps.pushPose();
            ps.mulPose(cam);
            float k = since / 3.0F;
            StandRenderer.disc(ps, buffers, 0.9F + 1.6F * k, 0xFFF4C0, 1.0F * (1.0F - k));
            ps.popPose();
        }

        ps.pushPose();
        float yaw = (float) Math.toDegrees(Math.atan2(d.x, d.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        ps.mulPose(Axis.YP.rotationDegrees(yaw));
        ps.mulPose(Axis.XP.rotationDegrees(pitch));
        Matrix4f m = ps.last().pose();
        VertexConsumer vc = buffers.getBuffer(ModRenderTypes.glow());
        // The beam: a cross of quads (round from any side), white-hot core, gold glow, wide halo. Thins as it burns out.
        float f = fade * flicker;
        float core = (0.07F + 0.05F * f) * blast, glow = (0.28F + 0.16F * f) * blast, halo = 0.7F * (0.45F + 0.55F * f) * blast;
        beam(vc, m, reach, halo, 255, 170, 40, (int) (80 * f));
        beam(vc, m, reach, glow, 255, 220, 120, (int) (185 * f));
        beam(vc, m, reach, core, 255, 255, 245, (int) (255 * f));
        // Two strands spiralling round the beam: gold and emerald.
        helix(vc, m, reach, 0.22F * (0.6F + 0.4F * fade), age * 0.5F, GOLD, (int) (200 * fade));
        helix(vc, m, reach, 0.22F * (0.6F + 0.4F * fade), age * 0.5F + Mth.PI, EMERALD, (int) (170 * fade));
        // Sonic-boom rings punched out along the shot as it breaks the air.
        for (int i = 0; i < 4; i++) {
            float at = 0.6F + i * 1.6F;
            if (at > reach) break;
            float k = Mth.clamp((since - i * 0.25F) / 4.0F, 0.0F, 1.0F);
            if (k <= 0.0F || k >= 1.0F) continue;
            ps.pushPose();
            ps.translate(0.0F, 0.0F, at);
            float ek = 1.0F - (1.0F - k) * (1.0F - k);
            StandRenderer.ring(ps, buffers, 0.25F + ek * (1.3F - i * 0.2F), 0.12F * (1.0F - k) + 0.02F, i % 2 == 0 ? 0xFFFFFF : GOLD, 0.7F * (1.0F - k));
            ps.popPose();
        }
        // The impact: a blinding flash and a huge ring blasting out where it hit.
        if (flight >= 1.0F) {
            float k = Mth.clamp((since - FLIGHT) / 7.0F, 0.0F, 1.0F);
            float ek = 1.0F - (1.0F - k) * (1.0F - k) * (1.0F - k);
            ps.pushPose();
            ps.translate(0.0F, 0.0F, (float) len);
            StandRenderer.ring(ps, buffers, 0.3F + ek * 3.2F, 0.3F * (1.0F - k) + 0.04F, GOLD, 0.9F * (1.0F - k));
            StandRenderer.ring(ps, buffers, 0.2F + ek * 2.2F, 0.1F * (1.0F - k) + 0.02F, 0xFFFFFF, 0.7F * (1.0F - k));
            ps.popPose();
        }
        ps.popPose();
        if (flight >= 1.0F && since < FLIGHT + 5.0F) {
            ps.pushPose();
            ps.translate(d.x, d.y, d.z);
            ps.mulPose(cam);
            float k = (since - FLIGHT) / 5.0F;
            StandRenderer.disc(ps, buffers, 1.0F + 1.5F * k, 0xFFF4C0, 0.95F * (1.0F - k));
            ps.popPose();
        }

        // The nugget itself, buried at the end of the trail for a moment.
        if (since < 4.0F) {
            ps.pushPose();
            Vec3 tip = d.normalize().scale(reach);
            ps.translate(tip.x, tip.y, tip.z);
            ps.scale(1.2F, 1.2F, 1.2F);
            Minecraft.getInstance().getItemRenderer().renderStatic(NUGGET, ItemDisplayContext.GROUND, LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY, ps, buffers, e.level(), e.getId());
            ps.popPose();
        }
    }

    /** A thin strand winding round the beam (along +Z) from 0 to len. */
    private static void helix(VertexConsumer vc, Matrix4f m, float len, float r, float phase, int rgb, int a) {
        if (a <= 0 || len <= 0.0F) return;
        int cr = (rgb >> 16) & 0xFF, cg = (rgb >> 8) & 0xFF, cb = rgb & 0xFF;
        final float step = 0.2F, w = 0.035F;
        for (float z = 0.0F; z < len; z += step) {
            float z1 = Math.min(len, z + step);
            float t0 = Mth.clamp(z / 1.5F, 0.0F, 1.0F), t1 = Mth.clamp(z1 / 1.5F, 0.0F, 1.0F); // grows out of the nugget
            float a0 = z * 2.2F + phase, a1 = z1 * 2.2F + phase;
            float x0 = Mth.cos(a0) * r * t0, y0 = Mth.sin(a0) * r * t0, x1 = Mth.cos(a1) * r * t1, y1 = Mth.sin(a1) * r * t1;
            int al0 = (int) (a * t0), al1 = (int) (a * t1);
            vc.addVertex(m, x0 - w, y0, z).setColor(cr, cg, cb, al0);
            vc.addVertex(m, x0 + w, y0, z).setColor(cr, cg, cb, al0);
            vc.addVertex(m, x1 + w, y1, z1).setColor(cr, cg, cb, al1);
            vc.addVertex(m, x1 - w, y1, z1).setColor(cr, cg, cb, al1);
            vc.addVertex(m, x0, y0 - w, z).setColor(cr, cg, cb, al0);
            vc.addVertex(m, x0, y0 + w, z).setColor(cr, cg, cb, al0);
            vc.addVertex(m, x1, y1 + w, z1).setColor(cr, cg, cb, al1);
            vc.addVertex(m, x1, y1 - w, z1).setColor(cr, cg, cb, al1);
        }
    }

    /** Length of the narrow start of the beam (it widens out from the nugget instead of starting at full size). */
    private static final float TAPER = 1.5F;

    /** Two crossed quads along +Z, {@code w} wide (half-width), from 0 to {@code len}. Narrow and faint at the start. */
    private static void beam(VertexConsumer vc, Matrix4f m, float len, float w, int r, int g, int b, int a) {
        if (a <= 0 || len <= 0.0F) return;
        float t = Math.min(TAPER, len);
        float wt = w * (0.15F + 0.85F * t / TAPER);
        int at = (int) (a * t / TAPER);
        int tail = (int) (a * 0.75F);
        // The taper: from a thin, faint point at the nugget out to the full beam.
        cross(vc, m, 0.0F, t, w * 0.15F, wt, r, g, b, 0, at);
        // The rest of the beam.
        if (len > t) cross(vc, m, t, len, wt, w, r, g, b, at, tail);
    }

    private static void cross(VertexConsumer vc, Matrix4f m, float z0, float z1, float w0, float w1, int r, int g, int b, int a0, int a1) {
        vc.addVertex(m, -w0, 0.0F, z0).setColor(r, g, b, a0);
        vc.addVertex(m, w0, 0.0F, z0).setColor(r, g, b, a0);
        vc.addVertex(m, w1, 0.0F, z1).setColor(r, g, b, a1);
        vc.addVertex(m, -w1, 0.0F, z1).setColor(r, g, b, a1);
        vc.addVertex(m, 0.0F, -w0, z0).setColor(r, g, b, a0);
        vc.addVertex(m, 0.0F, w0, z0).setColor(r, g, b, a0);
        vc.addVertex(m, 0.0F, w1, z1).setColor(r, g, b, a1);
        vc.addVertex(m, 0.0F, -w1, z1).setColor(r, g, b, a1);
    }

    @Override
    public ResourceLocation getTextureLocation(NuggetShotEntity e) {
        return ResourceLocation.withDefaultNamespace("textures/item/iron_nugget.png");
    }
}
