package com.mobstands.client;

import com.mobstands.network.ClientStandCache;
import com.mobstands.network.StandActionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ClientEvents {
    private ClientEvents() {}

    /**
     * Right click is the default basic-attack key. It is shared with vanilla "use item", so the Stand only
     * takes over when your hands are empty and you are not pointing at a block or villager. Rebind the
     * basic attack key to avoid this entirely.
     */
    private static boolean basicAllowed(Minecraft mc) {
        if (!ClientStandCache.get().isSummoned() || ClientStandCache.get().getType() == null) return false;
        if (!ClientStandCache.get().getType().hasBasic()) return false; // Squid / Axolotl: right click stays vanilla
        if (!StandKeys.BASIC.getKey().equals(mc.options.keyUse.getKey())) return true;
        if (mc.player == null) return false;
        if (!mc.player.getMainHandItem().isEmpty() || !mc.player.getOffhandItem().isEmpty()) return false;
        HitResult hit = mc.hitResult;
        if (hit == null) return true;
        if (hit.getType() == HitResult.Type.BLOCK) return false;
        return !(hit instanceof EntityHitResult ehr && ehr.getEntity() instanceof AbstractVillager);
    }

    @SubscribeEvent
    public static void onUseKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isAttack()) {
            // Punching one of your duplicate Allays.
            if (ClientStandCache.get().getType() == com.mobstands.stand.StandType.ALLAY && AllayDupeHits.tryHit()) {
                event.setCanceled(true);
                event.setSwingHand(true);
            }
            return;
        }
        if (!event.isUseItem()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        if (StandKeys.BASIC.getKey().equals(mc.options.keyUse.getKey()) && basicAllowed(mc)) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
        // Spinning: right click winds up a throw instead of using/placing the item.
        // (A spinning bow or crossbow still shoots normally: its arrows orbit you.)
        if (mc.player != null && (ClientStandCache.get().offSpinning || (ClientStandCache.get().spinning
                && !(mc.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem)))) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /** Time Stop's wave follows the clock on your Stand's chest across the screen. */
    @SubscribeEvent
    public static void onRenderStage(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        TimeStopFx.frame(event);
    }

    /** The Phantom's dream world: thick, dark, horror-movie fog. */
    @SubscribeEvent
    public static void onFog(net.neoforged.neoforge.client.event.ViewportEvent.RenderFog event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(com.mobstands.stand.DreamWalk.DREAM)) return;
        event.setNearPlaneDistance(event.getMode() == net.minecraft.client.renderer.FogRenderer.FogMode.FOG_SKY ? 0.0F : 1.5F);
        event.setFarPlaneDistance(event.getMode() == net.minecraft.client.renderer.FogRenderer.FogMode.FOG_SKY ? 4.0F : 15.0F);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onFogColor(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(com.mobstands.stand.DreamWalk.DREAM)) return;
        event.setRed(0.09F);
        event.setGreen(0.085F);
        event.setBlue(0.11F);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        TimeStopFx.tick(mc);
        if (mc.player == null) return;
        boolean open = mc.screen == null;

        while (StandKeys.SUMMON.consumeClick()) {
            if (open) send(0);
        }
        while (StandKeys.BASIC.consumeClick()) {
            if (open && basicAllowed(mc)) send(1);
        }
        while (StandKeys.ABILITY1.consumeClick()) {
            if (open) send(2);
        }
        boolean allay = ClientStandCache.get().getType() == com.mobstands.stand.StandType.ALLAY;
        while (StandKeys.ABILITY2.consumeClick()) {
            if (open && !allay) send(3);
        }
        if (allay) {
            // Allay: tap X = Allay Hold (grab the enemy you're looking at); hold X = let go.
            boolean down = open && StandKeys.ABILITY2.isDown();
            long t = net.minecraft.Util.getMillis();
            if (down && xDownAt < 0) {
                xDownAt = t;
                xFired = false;
            } else if (down && !xFired && t - xDownAt >= 450L) {
                xFired = true;
                send(20);
            } else if (!down && xDownAt >= 0) {
                if (!xFired) send(3);
                xDownAt = -1L;
            }
        }
        while (StandKeys.ULTIMATE.consumeClick()) {
            if (open) send(4);
        }
        while (StandKeys.ABILITY4.consumeClick()) {
            if (open) send(24); // the 5th ability
        }
        while (StandKeys.POSE.consumeClick()) {
            if (open) send(5);
        }
        while (StandKeys.INFO.consumeClick()) {
            if (open) mc.setScreen(new StandInfoScreen());
        }
        while (mc.options.keySprint.consumeClick()) {
            // Sprint taps are timed precisely in onKey (below).
        }
        if (mc.level != null) RushFx.tick(mc.level.getGameTime());
        SpinInput.tick();
    }

    /** Spin users: Q while holding an item goes to the Spin skill check instead of a plain drop. */
    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        RushFx.preTick();
        if (mc.player == null || mc.screen != null) return;
        if (!ClientStandCache.get().spinLearned) return;
        if ((mc.player.getMainHandItem().isEmpty() && mc.player.getOffhandItem().isEmpty()) || mc.player.isSpectator()) return;
        while (mc.options.keyDrop.consumeClick()) {
            // Spin users: Q presses are handled by SpinInput (tap = drop, hold = wind up the spin).
        }
    }

    /** First person: the spinning item whirls in your hand. */
    @SubscribeEvent
    public static void onRenderHand(net.neoforged.neoforge.client.event.RenderHandEvent event) {
        boolean off = event.getHand() == net.minecraft.world.InteractionHand.OFF_HAND;
        boolean rolling = !off && SpinFx.slashing(event.getPartialTick()); // the slash used up the spin, but the roll finishes
        boolean spun = off ? ClientStandCache.get().offSpinning : ClientStandCache.get().spinning;
        if (!spun && !rolling) return;
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.item.ItemStack stack = event.getItemStack();
        if (mc.player == null || stack.isEmpty()) return;
        event.setCanceled(true);
        boolean right = (mc.player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT) != off;
        float side = right ? 1.0F : -1.0F;
        com.mojang.blaze3d.vertex.PoseStack ps = event.getPoseStack();
        float el = Math.max(0.0F, SpinFx.elapsed(event.getPartialTick(), off));
        float k = SpinFx.speed(el);
        ps.pushPose();
        ps.translate(side * 0.56F, -0.52F + event.getEquipProgress() * -0.6F, -0.72F);
        ps.translate(-side * 0.12F, 0.2F, -0.08F); // floating just above the palm
        ps.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(Mth.sin(el * 1.7F) * 6.0F * k)); // a violent shudder while it's fast
        if (!off && SpinFx.slashing(event.getPartialTick())) {
            // Just struck the ground: the sword rolls forward over and over, like a wheel.
            ps.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-SpinFx.slashAngle(event.getPartialTick())));
        } else {
            ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(SpinFx.angle(el)));             // the spin, slowing to a stop
        }
        // A small cube (or flat item) like it's held between the fingers.
        ps.pushPose();
        ps.scale(0.5F, 0.5F, 0.5F);
        mc.getItemRenderer().renderStatic(mc.player, stack, net.minecraft.world.item.ItemDisplayContext.FIXED,
                false, ps, event.getMultiBufferSource(), mc.level, event.getPackedLight(),
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, mc.player.getId());
        ps.popPose();
        if (spun) SpinFx.drawRings(ps, event.getMultiBufferSource(), el, 0.2F);
        ps.popPose();
    }

    /** Third person (your own player): the held item spins in your hand. Called from ItemInHandLayerMixin. */
    public static void spinThirdPerson(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.entity.HumanoidArm arm,
                                       com.mojang.blaze3d.vertex.PoseStack ps, net.minecraft.client.renderer.MultiBufferSource buffers) {
        Minecraft mc = Minecraft.getInstance();
        if (entity != mc.player || mc.player == null) return;
        boolean off = arm != mc.player.getMainArm();
        if (!(off ? ClientStandCache.get().offSpinning : ClientStandCache.get().spinning)) return;
        float el = Math.max(0.0F, SpinFx.elapsed(mc.getTimer().getGameTimeDeltaPartialTick(true), off));
        ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(SpinFx.angle(el)));
        SpinFx.drawRings(ps, buffers, el, 0.28F);
    }

    @SubscribeEvent
    public static void onFov(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event) {
        float cart = CartRide.fovScale();
        if (cart != 1.0F) event.setFOV(event.getFOV() * cart); // Minecart boosts: the world stretches as you speed up
        if (RushFx.idle()) return;
        event.setFOV(event.getFOV() * RushFx.fovScale((float) event.getPartialTick()));
    }

    /** Horse Rush timing: the 2nd tap must come within this long of the 1st (ms). */
    private static final long RUSH_FIRST_GAP_MS = 300L;
    private static int rushTaps;
    private static long rushLastTap;
    private static long rushLastGap;

    /**
     * Horse Stand: tap Ctrl three times, each tap a little quicker than the one before (the 2nd within 0.3 s of the
     * 1st, the 3rd sooner after the 2nd than the 2nd was after the 1st). Too slow and it starts over from that tap.
     */
    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.options.keyDrop.matches(event.getKey(), event.getScanCode())
                && event.getAction() != org.lwjgl.glfw.GLFW.GLFW_REPEAT) {
            SpinInput.onDropKey(event.getAction() == org.lwjgl.glfw.GLFW.GLFW_PRESS);
        }
        if (event.getAction() != org.lwjgl.glfw.GLFW.GLFW_PRESS) return;
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        if (!mc.options.keySprint.matches(event.getKey(), event.getScanCode())) return;
        if (ClientStandCache.get().getType() != com.mobstands.stand.StandType.HORSE) return;
        long t = net.minecraft.Util.getMillis();
        long gap = t - rushLastTap;
        rushLastTap = t;
        net.minecraft.world.effect.MobEffectInstance speed = mc.player.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED);
        if (speed != null && speed.getAmplifier() >= 4) {
            // Rushing: a quick double tap of Ctrl stops it.
            if (rushTaps == 1 && gap <= 350L) {
                rushTaps = 0;
                send(6);
            } else {
                rushTaps = 1;
            }
            return;
        }
        if (rushTaps == 0 || gap > RUSH_FIRST_GAP_MS) {
            rushTaps = 1;
        } else if (rushTaps == 1) {
            rushTaps = 2;
            rushLastGap = gap;
        } else if (gap < rushLastGap) {
            rushTaps = 3;
        } else {
            rushTaps = 1; // not faster than the last one: start again from this tap
        }
        // Already rushing: this triple-tap stops it, so no focus effect.
        net.minecraft.world.effect.MobEffectInstance sp = mc.player.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED);
        boolean rushing = sp != null && sp.getAmplifier() >= 4;
        if (!rushing) RushFx.tap(rushTaps, mc.level.getGameTime());
        if (rushTaps >= 3) {
            rushTaps = 0;
            send(6);
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientStandCache.clear();
    }

    private static long xDownAt = -1L;
    private static boolean xFired;

    private static void send(int action) {
        PacketDistributor.sendToServer(new StandActionPayload(action));
    }
}
