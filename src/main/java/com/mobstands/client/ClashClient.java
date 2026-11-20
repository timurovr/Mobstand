package com.mobstands.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Barrage Clash on your screen: a tug-of-war bar - your Stand's colour on the left, theirs on the right, the clash
 * point between them - a shrinking timer, and the key to mash, pulsing every time you hit it.
 */
public final class ClashClient {
    private ClashClient() {}

    private static boolean active;
    private static float meter, shown, timeLeft;
    private static int rivalColor;
    private static long pressFlash;

    public static void onPayload(boolean on, float m, float left, int color) {
        active = on;
        meter = m;
        timeLeft = left;
        rivalColor = color;
        if (!on) shown = 0.0F;
    }

    public static boolean active() {
        return active;
    }

    /** You hit the key: tell the server, flash the prompt. */
    public static void press() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.mobstands.network.ClashPressPayload());
        pressFlash = net.minecraft.Util.getMillis();
    }

    @SubscribeEvent
    public static void onRender(RenderGuiEvent.Post event) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        GuiGraphics g = event.getGuiGraphics();
        shown += (meter - shown) * 0.25F;
        var d = com.mobstands.network.ClientStandCache.get();
        int mine = d.getType() == null ? 0x55AAFF : d.getType().color();
        int w = 200, h = 10;
        int x = (mc.getWindow().getGuiScaledWidth() - w) / 2;
        int y = mc.getWindow().getGuiScaledHeight() / 2 - 60;
        int split = x + (int) (w * (0.5F + 0.5F * Mth.clamp(shown, -1.0F, 1.0F)));
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xC0000000);
        g.fill(x, y, split, y + h, 0xFF000000 | mine);
        g.fill(split, y, x + w, y + h, 0xFF000000 | rivalColor);
        // The clash point: a white spark where the fists meet, shaking.
        int jit = (int) (Math.sin(net.minecraft.Util.getMillis() * 0.08D) * 1.5D);
        g.fill(split - 2 + jit, y - 4, split + 2 + jit, y + h + 4, 0xFFFFFFFF);
        // Time running out.
        g.fill(x, y + h + 4, x + (int) (w * Mth.clamp(timeLeft, 0.0F, 1.0F)), y + h + 6, 0xFFDDDDDD);
        // The key to mash, popping on every press.
        String key = StandKeys.ABILITY1.getTranslatedKeyMessage().getString().toUpperCase();
        float pop = Mth.clamp(1.0F - (net.minecraft.Util.getMillis() - pressFlash) / 120.0F, 0.0F, 1.0F);
        float s = 2.0F + pop * 0.8F;
        g.pose().pushPose();
        g.pose().translate(x + w / 2.0F, y + h + 22.0F, 0.0F);
        g.pose().scale(s, s, 1.0F);
        g.drawString(mc.font, key, -mc.font.width(key) / 2, -4, pop > 0.0F ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        g.pose().popPose();
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        active = false;
    }
}
