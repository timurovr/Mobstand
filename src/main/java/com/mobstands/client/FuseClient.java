package com.mobstands.client;

import com.mobstands.stand.PropertySwap;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Pigstep's Volatility: any mob carrying it swells and flashes white like a creeper about to blow. */
public final class FuseClient {
    private FuseClient() {}

    /** entity id -> {fuse now, fuse last tick, fuse as received} */
    private static final Map<Integer, int[]> FUSES = new HashMap<>();
    private static final Deque<LivingEntity> PUSHED = new ArrayDeque<>();

    public static void onFuse(int id, int fuse) {
        if (fuse <= 0) {
            FUSES.remove(id);
            return;
        }
        int[] f = FUSES.computeIfAbsent(id, k -> new int[]{fuse, fuse});
        f[1] = f[0];
        f[0] = fuse;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        for (int[] f : FUSES.values()) f[1] = f[0];
    }

    /** 0..1 how swollen it is (creeper maths). */
    public static float swelling(LivingEntity e, float partial) {
        int[] f = FUSES.get(e.getId());
        if (f == null) return 0.0F;
        return Mth.lerp(partial, f[1], f[0]) / (float) (PropertySwap.FUSE - 2);
    }

    /** The white flash, exactly like a creeper's (for the renderer's overlay); -1 = not fusing. */
    public static float whiteOverlay(LivingEntity e, float partial) {
        if (!FUSES.containsKey(e.getId())) return -1.0F;
        float f = swelling(e, partial);
        return (int) (f * 10.0F) % 2 == 0 ? 0.0F : Mth.clamp(f, 0.5F, 1.0F);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderPre(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity e = event.getEntity();
        if (!FUSES.containsKey(e.getId())) return;
        float f = swelling(e, event.getPartialTick());
        float wobble = 1.0F + Mth.sin(f * 100.0F) * f * 0.01F;
        f = Mth.clamp(f, 0.0F, 1.0F);
        f = f * f;
        f = f * f;
        float xz = (1.0F + f * 0.4F) * wobble;
        float y = (1.0F + f * 0.1F) / wobble;
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.scale(xz, y, xz);
        PUSHED.push(e);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderPost(RenderLivingEvent.Post<?, ?> event) {
        if (PUSHED.isEmpty() || PUSHED.peek() != event.getEntity()) return;
        PUSHED.pop();
        event.getPoseStack().popPose();
    }

    /** Gone from view: forget its fuse (entity numbers get reused). */
    @SubscribeEvent
    public static void onLeave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) FUSES.remove(event.getEntity().getId());
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        FUSES.clear();
        PUSHED.clear();
    }
}
