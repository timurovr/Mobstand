package com.mobstands.client;

import com.mobstands.entity.CurseEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.DrownedModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.LavaSlimeModel;
import net.minecraft.client.model.VexModel;
import net.minecraft.client.model.WitchModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Cursed Stands: Cheap Trick hugging its host's back, Survivor sitting on a shoulder, Boy II Man floating at your
 * side - and Notorious B.I.G., a glowing Magma Cube that wobbles after whatever moves fastest.
 */
public class CurseRenderer extends EntityRenderer<CurseEntity> {
    private static final ResourceLocation VEX_TEX = ResourceLocation.withDefaultNamespace("textures/entity/illager/vex.png");
    private static final ResourceLocation DROWNED_TEX = ResourceLocation.withDefaultNamespace("textures/entity/zombie/drowned.png");
    private static final ResourceLocation WITCH_TEX = ResourceLocation.withDefaultNamespace("textures/entity/witch.png");
    private static final ResourceLocation MAGMA_TEX = ResourceLocation.withDefaultNamespace("textures/entity/slime/magmacube.png");

    private final VexModel vex;
    private final DrownedModel<?> drowned;
    private final WitchModel<Entity> witch;
    private final LavaSlimeModel<net.minecraft.world.entity.monster.MagmaCube> magma;

    public CurseRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
        vex = new VexModel(ctx.bakeLayer(ModelLayers.VEX));
        drowned = new DrownedModel<>(ctx.bakeLayer(ModelLayers.DROWNED));
        witch = new WitchModel<>(ctx.bakeLayer(ModelLayers.WITCH));
        magma = new LavaSlimeModel<>(ctx.bakeLayer(ModelLayers.MAGMA_CUBE));
        // the little Drowned reaches out, zombie-style
        drowned.rightArm.xRot = -1.4F;
        drowned.leftArm.xRot = -1.4F;
    }

    @Override
    public void render(CurseEntity e, float entityYaw, float partial, PoseStack ps, MultiBufferSource buffers, int packedLight) {
        float t = e.tickCount + partial;
        if (e.kind() == CurseEntity.BIG) {
            renderBig(e, t, partial, ps, buffers, packedLight);
            return;
        }
        Entity host = e.level().getEntity(e.host());
        if (!(host instanceof LivingEntity h) || h.isInvisible() || ZipperFx.inAmount(h, partial) > 0.3F) return;
        Minecraft mc = Minecraft.getInstance();
        boolean firstPersonSelf = h == mc.player && mc.options.getCameraType().isFirstPerson();
        Vec3 hp = h.getPosition(partial);
        Vec3 ep = e.getPosition(partial);
        float bodyYaw = Mth.rotLerp(partial, h.yBodyRotO, h.yBodyRot);
        float yr = bodyYaw * Mth.DEG_TO_RAD;
        Vec3 f = new Vec3(-Mth.sin(yr), 0.0D, Mth.cos(yr));
        Vec3 r = new Vec3(-Mth.cos(yr), 0.0D, -Mth.sin(yr));
        float hh = h.getBbHeight();
        int light = LevelRenderer.getLightColor(e.level(), BlockPos.containing(hp.x, hp.y + hh * 0.7D, hp.z));

        Vec3 at;
        float yaw, scale, lean = 0.0F;
        EntityModel<?> model;
        ResourceLocation tex;
        switch (e.kind()) {
            case CurseEntity.CHEAP_TRICK -> {
                if (firstPersonSelf) return; // it's on your back: you never see it
                at = hp.add(0.0D, hh * 0.66D + Mth.sin(t * 0.2F) * 0.02D, 0.0D).subtract(f.scale(0.33D));
                yaw = bodyYaw;
                scale = 0.6F;
                lean = 18.0F;
                model = vex;
                tex = VEX_TEX;
            }
            case CurseEntity.SURVIVOR -> {
                if (firstPersonSelf) return;
                at = hp.add(0.0D, hh * 0.78D + Math.abs(Mth.sin(t * 0.35F)) * 0.04D, 0.0D).add(r.scale(0.34D));
                yaw = bodyYaw + Mth.sin(t * 0.7F) * 12.0F; // twitchy
                scale = 0.24F;
                model = drowned;
                tex = DROWNED_TEX;
            }
            default -> { // Boy II Man, floating at your left
                at = hp.add(0.0D, 0.85D + Mth.sin(t * 0.08F) * 0.12D, 0.0D).subtract(r.scale(0.9D)).add(f.scale(0.2D));
                yaw = bodyYaw - 30.0F;
                scale = 0.42F;
                model = witch;
                tex = WITCH_TEX;
            }
        }
        ps.pushPose();
        ps.translate(at.x - ep.x, at.y - ep.y, at.z - ep.z);
        ps.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
        if (lean != 0.0F) ps.mulPose(Axis.XP.rotationDegrees(-lean));
        ps.scale(-scale, -scale, scale);
        ps.translate(0.0F, -1.501F, 0.0F);
        model.renderToBuffer(ps, buffers.getBuffer(RenderType.entityCutoutNoCull(tex)), light, OverlayTexture.NO_OVERLAY);
        ps.popPose();
    }

    private void renderBig(CurseEntity e, float t, float partial, PoseStack ps, MultiBufferSource buffers, int light) {
        float size = e.size();
        float sc = size / 0.5F;
        float wob = Mth.sin(t * 0.45F) * 0.08F;
        Vec3 mv = e.position().subtract(e.xo, e.yo, e.zo);
        float yaw = mv.horizontalDistanceSqr() > 1.0E-5D ? (float) Math.toDegrees(Math.atan2(-mv.x, mv.z)) : e.getYRot();
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
        ps.scale(-1.0F, -1.0F, 1.0F);
        ps.scale(sc * (1.0F + wob), sc * (1.0F - wob), sc * (1.0F + wob));
        ps.translate(0.0F, -1.501F, 0.0F);
        // Molten: always glowing (full brightness), like a real Magma Cube.
        magma.renderToBuffer(ps, buffers.getBuffer(RenderType.entityCutoutNoCull(MAGMA_TEX)), 0xF000F0, OverlayTexture.NO_OVERLAY);
        ps.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(CurseEntity e) {
        return MAGMA_TEX;
    }
}
