package com.mobstands.client;

import com.mobstands.network.ClientStandCache;
import com.mobstands.stand.HeartStop;
import com.mobstands.stand.StandData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Stop Heart, on your screen: the world drained grey and dark, a heart monitor across the bottom flatlining - then,
 * once your body starts to fail, a red pulse with every hit. When your heart restarts, the line spikes and the colour
 * floods back.
 */
public final class HeartStopHud {
    private HeartStopHud() {}

    @SubscribeEvent
    public static void onGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        StandData d = ClientStandCache.get();
        if (d.heartStopAt < 0L) return;
        float now = mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(true);
        float t = now - d.heartStopAt;
        float after = now - d.heartStopEnd; // > 0 once it's restarted
        boolean stopped = t >= 0.0F && after < 0.0F;
        boolean restarting = after >= 0.0F && after < 24.0F && d.heartStopEnd > d.heartStopAt;
        if (!stopped && !restarting) return;
        GuiGraphics g = event.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight();

        // Drained and dark (fades in as it stops, and out as it restarts).
        float dim = stopped ? Mth.clamp(t / 8.0F, 0.0F, 1.0F) : Math.max(0.0F, 1.0F - after / 10.0F);
        g.fill(0, 0, w, h, ((int) (110 * dim) << 24) | 0x14161C);
        int edge = (int) (170 * dim);
        g.fillGradient(0, 0, w, h / 3, edge << 24, 0);
        g.fillGradient(0, h - h / 3, w, h, 0, edge << 24);

        // Failing: a red pulse with every hit.
        if (stopped && t >= HeartStop.FREE_TICKS) {
            float since = (t - HeartStop.FREE_TICKS) % 20.0F;
            float pulse = (float) Math.exp(-since / 4.0F);
            int a = (int) (150 * pulse);
            g.fillGradient(0, 0, w, h / 2, a << 24 | 0x8A0000, 0);
            g.fillGradient(0, h / 2, w, h, 0, a << 24 | 0x8A0000);
        }

        // The heart monitor.
        int x0 = w / 4, x1 = w - w / 4, base = h - 56;
        int col = stopped && t >= HeartStop.FREE_TICKS ? 0xFF4A4A : 0x6FF0B0;
        float sweep = (now * 4.0F) % (x1 - x0);
        int prevY = base;
        for (int x = x0; x < x1; x++) {
            float age = (sweep - (x - x0) + (x1 - x0)) % (x1 - x0); // how long ago the sweep passed here
            int a = (int) (255 * Math.max(0.0F, 1.0F - age / (x1 - x0) * 1.2F) * Math.max(dim, restarting ? 1.0F : 0.0F));
            if (a <= 6) {
                prevY = base;
                continue;
            }
            int y = base;
            if (restarting) {
                // The restart: a huge spike right where the sweep is.
                float dx = (x - x0) - (d.heartStopEnd * 4.0F) % (x1 - x0);
                float k = after < 16.0F ? 1.0F - after / 16.0F : 0.0F;
                if (Math.abs(dx) < 10.0F) y = base - (int) (Math.sin(dx / 10.0F * Math.PI) * 30.0F * k) - (int) ((10.0F - Math.abs(dx)) * 2.0F * k);
            }
            g.fill(x, Math.min(prevY, y), x + 1, Math.max(prevY, y) + 1, (a << 24) | col);
            prevY = y;
        }
        // the bright head of the sweep
        g.fill(x0 + (int) sweep - 1, base - 1, x0 + (int) sweep + 2, base + 2, ((int) (255 * Math.max(dim, restarting ? 1.0F : 0.0F)) << 24) | 0xFFFFFF);

        if (stopped) {
            String label;
            int lc;
            if (t < HeartStop.FREE_TICKS) {
                label = "HEART STOPPED  " + String.format("%.1f", (HeartStop.FREE_TICKS - t) / 20.0F) + "s";
                lc = 0x6FF0B0;
            } else {
                label = "YOUR BODY IS FAILING - RESTART YOUR HEART";
                lc = 0xFF4A4A;
            }
            g.drawCenteredString(mc.font, label, w / 2, base + 10, ((int) (255 * dim) << 24) | lc);
        }
    }
}
