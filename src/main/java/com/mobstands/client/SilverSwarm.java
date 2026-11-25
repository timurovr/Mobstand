package com.mobstands.client;

import com.mobstands.entity.StandEntity;
import com.mobstands.stand.StandType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.SilverfishModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Silver Fingers' body: not one Stand but a swarm of silverfish. At a low level they're barely tame - they skitter
 * around you in a wide, restless circle. The higher your Stand's level, the tamer they get: they come in closer, and
 * more and more of them climb up onto you and cling there, crawling slowly over your body - until at the top level
 * the whole swarm lives on you. When you strike, half the swarm pours off you and onto the target.
 */
public final class SilverSwarm {
    private SilverSwarm() {}

    private static final ResourceLocation TEX = ResourceLocation.withDefaultNamespace("textures/entity/silverfish.png");
    private static SilverfishModel<Entity> model;

    private static SilverfishModel<Entity> model() {
        if (model == null) {
            model = new SilverfishModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.SILVERFISH));
        }
        return model;
    }

    /** How many silverfish in the swarm at this level. */
    public static int count(int level) {
        return 10 + Mth.clamp(level, 1, 10);
    }

    /** How many of them cling to you (none at level 1, all of them from level 9). */
    public static int clinging(int level) {
        return Math.round(count(level) * Mth.clamp((level - 1) / 8.0F, 0.0F, 1.0F));
    }

    private static float h(int i, int k) {
        int x = i * 374761393 + k * 668265263;
        x = (x ^ (x >>> 13)) * 1274126177;
        return ((x ^ (x >>> 16)) & 0xFFFF) / 65535.0F;
    }

    public static void render(StandEntity stand, float partial, PoseStack ps, MultiBufferSource buffers) {
        Player owner = stand.getOwnerPlayer();
        if (owner == null) return;
        // Gone into a zipper with you.
        float zipIn = ZipperFx.inAmount(owner, partial);
        if (zipIn > 0.35F) return;
        float fIn = Mth.clamp((stand.tickCount + partial) / (float) StandEntity.fadeTicks(StandType.SILVERFISH), 0.0F, 1.0F);
        float fOut = stand.dismissProgress(partial);
        float fade = fIn * (1.0F - fOut) * (1.0F - zipIn / 0.35F);
        if (fade <= 0.01F) return;

        Minecraft mc = Minecraft.getInstance();
        Level level = stand.level();
        int lv = Math.max(1, stand.swarmLevel());
        int n = count(lv), cling = clinging(lv);
        float tame = Mth.clamp((lv - 1) / 9.0F, 0.0F, 1.0F);
        float t = stand.tickCount + partial;
        Vec3 o = owner.getPosition(partial);
        Vec3 sp = stand.getPosition(partial);
        float bodyYaw = Mth.rotLerp(partial, owner.yBodyRotO, owner.yBodyRot) * Mth.DEG_TO_RAD;
        boolean firstPerson = owner == mc.player && mc.options.getCameraType().isFirstPerson();
        float crouch = owner.isCrouching() ? 0.82F : 1.0F;
        // Striking: half the swarm rushes to where the Stand lunges.
        float strike = 0.0F;
        int slot = stand.animSlot();
        float prog = stand.animProgress(partial);
        if (prog >= 0.0F && (slot == 0 || slot == 1)) strike = Mth.sin(Mth.clamp(prog, 0.0F, 1.0F) * Mth.PI);

        SilverfishModel<Entity> mdl = model();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TEX));

        ps.pushPose();
        ps.translate(o.x - sp.x, o.y - sp.y, o.z - sp.z); // work around the owner's feet
        for (int i = 0; i < n; i++) {
            float speed = 0.7F + h(i, 1) * 0.6F;
            Vec3 pos;
            float yaw, heading = 0.0F;
            boolean onBody = i < cling;
            float size = (onBody ? 0.42F : 0.55F) * (0.85F + h(i, 2) * 0.3F) * fade;
            if (onBody) {
                // Clinging to you, crawling slowly around your body.
                float a = h(i, 3) * Mth.TWO_PI + Mth.sin(t * 0.013F * speed + h(i, 4) * 7.0F) * 0.9F + t * 0.004F * (h(i, 5) > 0.5F ? 1 : -1);
                float hh = (0.12F + 1.5F * (0.5F + 0.5F * Mth.sin(t * 0.011F * speed + h(i, 6) * 11.0F))) * crouch;
                if (firstPerson && hh > 1.05F) continue; // (not crawling over your eyes)
                float r = hh > 1.45F ? 0.27F : 0.33F;
                float ang = a + bodyYaw;
                Vec3 out = new Vec3(Mth.cos(ang), 0.0D, Mth.sin(ang));
                pos = out.scale(r).add(0.0D, hh, 0.0D);
                // which way it's crawling over the surface
                float da = Mth.cos(t * 0.013F * speed + h(i, 4) * 7.0F) * 0.9F * 0.013F * speed + 0.004F * (h(i, 5) > 0.5F ? 1 : -1);
                float dh = Mth.cos(t * 0.011F * speed + h(i, 6) * 11.0F) * 0.75F * 0.011F * speed;
                heading = (float) Math.toDegrees(Math.atan2(da * r, dh));
                yaw = (float) Math.toDegrees(Math.atan2(out.x, -out.z)); // facing into your body
            } else {
                // Roaming: skittering around you in bursts - wide and restless when they're wild, close when tame.
                float radius = Mth.lerp(tame, 5.5F, 2.2F);
                float tt = t + 9.0F * Mth.sin(t * 0.05F * speed + h(i, 7) * 6.0F); // stop-start scurrying
                Vec3 a0 = roam(i, tt, radius, speed, tame), a1 = roam(i, tt + 0.6F, radius, speed, tame);
                pos = a0;
                Vec3 d = a1.subtract(a0);
                yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            }
            // The strike: every other one pours off toward the target and back.
            if (strike > 0.0F && i % 2 == 0) {
                Vec3 tgt = sp.subtract(o).add((h(i, 8) - 0.5F) * 0.8F, 0.4F + (h(i, 9) - 0.5F) * 0.8F, (h(i, 10) - 0.5F) * 0.8F);
                pos = pos.add(tgt.subtract(pos).scale(strike));
                Vec3 dd = tgt.subtract(pos);
                if (onBody && strike > 0.3F) {
                    onBody = false;
                    yaw = (float) Math.toDegrees(Math.atan2(-dd.x, dd.z));
                }
            }
            if (!onBody) {
                // on the ground
                double gy = ground(level, o.x + pos.x, o.y + pos.y, o.z + pos.z);
                if (strike <= 0.0F || i % 2 != 0) pos = new Vec3(pos.x, gy - o.y, pos.z);
            }
            BlockPos lp = BlockPos.containing(o.x + pos.x, o.y + pos.y + 0.2D, o.z + pos.z);
            int light = LevelRenderer.getLightColor(level, lp);

            ps.pushPose();
            ps.translate(pos.x, pos.y, pos.z);
            ps.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
            if (onBody) {
                ps.mulPose(Axis.XP.rotationDegrees(-90.0F)); // belly against you, back facing out
                ps.mulPose(Axis.YP.rotationDegrees(heading));
            }
            ps.scale(-size, -size, size);
            ps.translate(0.0F, -1.501F, 0.0F);
            mdl.setupAnim(null, 0.0F, 0.0F, t * speed * (onBody ? 0.5F : 1.4F) + i * 13.0F, 0.0F, 0.0F);
            mdl.renderToBuffer(ps, vc, light, OverlayTexture.NO_OVERLAY);
            ps.popPose();
        }
        ps.popPose();
    }

    private static Vec3 roam(int i, float t, float radius, float speed, float tame) {
        float ang = h(i, 11) * Mth.TWO_PI + t * 0.01F * speed * (h(i, 12) > 0.5F ? 1 : -1) + 0.7F * Mth.sin(t * 0.017F + h(i, 13) * 5.0F);
        float d = radius * (0.4F + 0.6F * (0.5F + 0.5F * Mth.sin(t * 0.023F * speed + h(i, 14) * 13.0F)));
        // Wild ones sometimes bolt away from you.
        if (tame < 0.35F && i % 3 == 0) d *= 1.0F + (0.35F - tame) * 2.0F * Math.max(0.0F, Mth.sin(t * 0.009F + h(i, 15) * 9.0F));
        return new Vec3(Mth.cos(ang) * d, 0.0D, Mth.sin(ang) * d);
    }

    /** Height of the floor at (x, z), searching around the height {@code y}. */
    private static double ground(Level level, double x, double y, double z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int bx = Mth.floor(x), bz = Mth.floor(z), by = Mth.floor(y);
        for (int dy = 2; dy >= -4; dy--) {
            p.set(bx, by + dy, bz);
            BlockState s = level.getBlockState(p);
            if (!s.getCollisionShape(level, p).isEmpty()) {
                p.set(bx, by + dy + 1, bz);
                if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                    p.set(bx, by + dy, bz);
                    return by + dy + s.getCollisionShape(level, p).max(net.minecraft.core.Direction.Axis.Y);
                }
            }
        }
        return y;
    }
}
