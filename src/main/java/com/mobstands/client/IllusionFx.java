package com.mobstands.client;

import com.mobstands.entity.StandEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Smoke and Mirrors on screen: shimmering, see-through copies of its user circling them (one fewer each time a copy
 * takes a hit) - and, while they've vanished, nothing of them at all: no armour, no held items, not even their Stand.
 */
public final class IllusionFx {
    private IllusionFx() {}

    private static final Map<Integer, Integer> MIRRORS = new HashMap<>();
    private static final Map<Integer, Boolean> VANISHED = new HashMap<>();
    private static long clientTicks;

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        clientTicks++;
        MIRRORS.clear();
        VANISHED.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof StandEntity s) || s.getStandType() != com.mobstands.stand.StandType.ILLUSIONER) continue;
            Player o = s.getOwnerPlayer();
            if (o == null) continue;
            if (s.mirror() > 0) MIRRORS.put(o.getId(), s.mirror());
            if (s.isVanished()) VANISHED.put(o.getId(), true);
        }
    }

    /** Vanished: everyone else sees nothing at all (not even armour). */
    @SubscribeEvent
    public static void onPlayerPre(RenderPlayerEvent.Pre event) {
        Player p = event.getEntity();
        if (VANISHED.containsKey(p.getId()) && p != Minecraft.getInstance().player) event.setCanceled(true);
    }

    /** The mirror images: copies of you, see-through and shimmering, circling you. */
    @SubscribeEvent
    public static void onPlayerPost(RenderPlayerEvent.Post event) {
        Player p = event.getEntity();
        Integer n = MIRRORS.get(p.getId());
        if (n == null || n <= 0) return;
        float partial = event.getPartialTick();
        float t = clientTicks + partial;
        var renderer = event.getRenderer();
        var model = renderer.getModel();
        ResourceLocation skin = renderer.getTextureLocation((net.minecraft.client.player.AbstractClientPlayer) p);
        PoseStack ps = event.getPoseStack();
        float bodyYaw = Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot);
        for (int i = 0; i < n; i++) {
            double a = t * 0.04D + i * Math.PI * 2.0D / 4.0D;
            double r = 1.25D + 0.15D * Math.sin(t * 0.3D + i * 1.7D);
            // the illusioner's flicker: the copies jump about a little
            double jx = Math.sin(t * 1.9D + i * 3.1D) * 0.06D, jz = Math.cos(t * 2.3D + i * 1.3D) * 0.06D;
            ps.pushPose();
            ps.translate(Math.cos(a) * r + jx, 0.0D, Math.sin(a) * r + jz);
            ps.mulPose(Axis.YP.rotationDegrees(180.0F - bodyYaw));
            ps.scale(-1.0F, -1.0F, 1.0F);
            ps.scale(0.9375F, 0.9375F, 0.9375F); // (the player renderer's own scale)
            ps.translate(0.0F, -1.501F, 0.0F);
            int alpha = (int) (110 + 50 * Math.sin(t * 0.5D + i));
            try {
                model.renderToBuffer(ps, event.getMultiBufferSource().getBuffer(RenderType.entityTranslucent(skin)), event.getPackedLight(),
                        OverlayTexture.NO_OVERLAY, (alpha << 24) | 0xC8D8FF);
            } catch (RuntimeException ignored) {
            }
            ps.popPose();
        }
    }
}
