package com.mobstands.client;

import com.mobstands.entity.StandEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Green Temperance on screen: a wobbling shell of translucent green goo over anything it's eating (and over its user
 * while they wear it as armour) - and the Masquerade: its user drawn as the creature it's imitating.
 */
public final class GooFx {
    private GooFx() {}

    private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath("mobstands", "textures/misc/white.png");
    private static long clientTicks;
    /** Entity id -> until (client ticks): being eaten. */
    private static final Map<Integer, Long> EATEN = new HashMap<>();
    /** Player id -> wearing the goo / the shape they've taken. Rebuilt every tick from their Stands. */
    private static final Map<Integer, Boolean> ARMORED = new HashMap<>();
    private static final Map<Integer, Integer> DISGUISE = new HashMap<>();
    private static final Map<EntityType<?>, Entity> DUMMIES = new HashMap<>();

    public static void eaten(int id, int ticks) {
        if (ticks <= 0) EATEN.remove(id);
        else EATEN.put(id, clientTicks + ticks);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        clientTicks++;
        EATEN.values().removeIf(t -> t < clientTicks);
        ARMORED.clear();
        DISGUISE.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            DUMMIES.clear();
            return;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof StandEntity s) || s.getStandType() != com.mobstands.stand.StandType.SLIME) continue;
            Player o = s.getOwnerPlayer();
            if (o == null) continue;
            if (s.isGoo()) ARMORED.put(o.getId(), true);
            if (s.disguise() >= 0) DISGUISE.put(o.getId(), s.disguise());
        }
    }

    /** Is this player disguised right now (so their Stand stays hidden too)? */
    public static boolean disguised(Entity e) {
        return e != null && !DISGUISE.isEmpty() && DISGUISE.containsKey(e.getId());
    }

    // ------------------------------------------------------------------ the goo shell

    @SubscribeEvent
    public static void onRendered(RenderLivingEvent.Post<?, ?> event) {
        LivingEntity e = event.getEntity();
        boolean eaten = EATEN.containsKey(e.getId());
        boolean armor = ARMORED.containsKey(e.getId()) && !DISGUISE.containsKey(e.getId());
        if (!eaten && !armor) return;
        float partial = event.getPartialTick();
        float t = clientTicks + partial;
        EntityModel<?> model = event.getRenderer().getModel();
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        float bodyYaw = Mth.rotLerp(partial, e.yBodyRotO, e.yBodyRot);
        ps.mulPose(Axis.YP.rotationDegrees(180.0F - bodyYaw));
        ps.scale(-1.0F, -1.0F, 1.0F);
        float sc = e.getScale();
        ps.scale(sc, sc, sc);
        // a little bigger than the body, wobbling like jelly
        float wob = 1.07F + Mth.sin(t * 0.35F) * 0.02F;
        ps.translate(0.0F, 0.75F, 0.0F);
        ps.scale(wob, wob - Mth.sin(t * 0.35F) * 0.03F, wob);
        ps.translate(0.0F, -0.75F, 0.0F);
        ps.translate(0.0F, -1.501F, 0.0F);
        int alpha = eaten ? 150 : 95;
        int col = (alpha << 24) | (eaten ? 0x4FD13A : 0x7EFF6E);
        try {
            model.renderToBuffer(ps, event.getMultiBufferSource().getBuffer(RenderType.entityTranslucent(WHITE)), event.getPackedLight(),
                    OverlayTexture.NO_OVERLAY, col);
        } catch (RuntimeException ignored) {
            // some models don't like being drawn twice: just no goo on those
        }
        ps.popPose();
    }

    // ------------------------------------------------------------------ Masquerade

    @SubscribeEvent
    public static void onPlayer(RenderPlayerEvent.Pre event) {
        Player p = event.getEntity();
        Integer typeId = DISGUISE.get(p.getId());
        if (typeId == null) return;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.byId(typeId);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity d = DUMMIES.computeIfAbsent(type, k -> {
            Entity x = k.create(mc.level);
            if (x != null) {
                x.setSilent(true);
                x.setNoGravity(true);
            }
            return x;
        });
        if (!(d instanceof LivingEntity dl)) return;
        event.setCanceled(true);
        float partial = event.getPartialTick();
        // move exactly like the player
        dl.setPos(p.getX(), p.getY(), p.getZ());
        dl.xo = p.xo;
        dl.yo = p.yo;
        dl.zo = p.zo;
        dl.setYRot(p.getYRot());
        dl.yRotO = p.yRotO;
        dl.setXRot(p.getXRot());
        dl.xRotO = p.xRotO;
        dl.yBodyRot = p.yBodyRot;
        dl.yBodyRotO = p.yBodyRotO;
        dl.yHeadRot = p.yHeadRot;
        dl.yHeadRotO = p.yHeadRotO;
        dl.tickCount = p.tickCount;
        dl.setOnGround(p.onGround());
        if (dl.walkAnimation.speed() != p.walkAnimation.speed() || dl.tickCount % 1 == 0) {
            dl.walkAnimation.setSpeed(p.walkAnimation.speed());
        }
        dl.walkAnimation.update(p.walkAnimation.speed(partial), 1.0F);
        @SuppressWarnings("unchecked")
        EntityRenderer<Entity> r = (EntityRenderer<Entity>) mc.getEntityRenderDispatcher().getRenderer(dl);
        float yaw = Mth.rotLerp(partial, p.yRotO, p.getYRot());
        r.render(dl, yaw, partial, event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight());
    }
}
