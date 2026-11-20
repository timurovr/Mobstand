package com.mobstands.client;

import com.mobstands.network.ClientStandCache;
import com.mobstands.stand.RainPowers;
import com.mobstands.stand.StandData;
import com.mobstands.stand.StandType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Catch the Rainbow on your own game: standing on the rain, and the rain timer above your hotbar. */
public final class RainClient {
    private RainClient() {}

    private static int left;
    private static int lastSecond = -1;
    private static boolean holding;

    public static void onTime(int ticks) {
        left = ticks;
    }

    private static boolean dolphin() {
        return ClientStandCache.get().getType() == StandType.DOLPHIN;
    }

    // ------------------------------------------------------------------ Rain Walk

    private static boolean standing(LocalPlayer p) {
        StandData d = ClientStandCache.get();
        return d.getType() == StandType.DOLPHIN && d.rainWalk && RainPowers.wet(p) && !p.isShiftKeyDown() && !p.isInWater()
                && !p.getAbilities().flying && !p.isPassenger() && !p.isFallFlying() && !p.isSpectator() && !GravityClient.overriding();
    }

    @SubscribeEvent
    public static void onPlayerTickPre(PlayerTickEvent.Pre event) {
        if (!(event.getEntity() instanceof LocalPlayer p) || p != Minecraft.getInstance().player) return;
        // The drops under you hold you up whenever you're not rising (a jump still carries you higher).
        if (standing(p) && p.getDeltaMovement().y <= 0.0D) {
            p.setNoGravity(true);
            Vec3 v = p.getDeltaMovement();
            if (v.y < 0.0D) p.setDeltaMovement(v.x, 0.0D, v.z);
            p.setOnGround(true); // walk and sprint at full ground speed, not the slow in-air speed
            holding = true;
        } else if (holding) {
            p.setNoGravity(false);
            holding = false;
        }
    }

    @SubscribeEvent
    public static void onPlayerTickPost(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof LocalPlayer p) || p != Minecraft.getInstance().player || !holding) return;
        p.setOnGround(true); // so you can walk, sprint and jump off the drops
        p.fallDistance = 0.0F;
        // The frozen drops you're standing on, right under your feet.
        if (p.tickCount % 2 == 0 && p.level().noCollision(p, p.getBoundingBox().move(0.0D, -0.3D, 0.0D))) {
            for (int i = 0; i < 3; i++) {
                p.level().addParticle(RainPowers.DROP, p.getX() + (p.getRandom().nextDouble() - 0.5D) * 0.7D, p.getY() + 0.02D,
                        p.getZ() + (p.getRandom().nextDouble() - 0.5D) * 0.7D, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    // ------------------------------------------------------------------ rain timer

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused() || mc.player == null) return;
        if (left > 0) left--;
        if (!dolphin() || left <= 0) {
            lastSecond = -1;
            return;
        }
        int sec = (left + 19) / 20;
        if (sec <= 5 && sec != lastSecond) {
            // The last 5 seconds: a drip every second, so you know to get down.
            mc.player.playSound(SoundEvents.POINTED_DRIPSTONE_DRIP_WATER, 1.0F, 0.8F + (5 - sec) * 0.1F);
        }
        lastSecond = sec;
    }

    private static final String[] DROP = {
            "..#..",
            "..#..",
            ".###.",
            "##o##",
            "#####",
            "#####",
            ".###."};

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || left <= 0 || !dolphin()) return;
        int sec = (left + 19) / 20;
        String text = (sec / 60) + ":" + String.format("%02d", sec % 60);
        boolean warn = sec <= 5;
        boolean flash = warn && (mc.player.tickCount / 5) % 2 == 0;
        int textColor = flash ? 0xFFFF5555 : 0xFFFFFFFF;
        int dropColor = flash ? 0xFFFF5555 : 0xFF3F76D6;
        int w = mc.font.width(text);
        int total = 5 + 3 + w;
        int x = g.guiWidth() / 2 - total / 2;
        int y = g.guiHeight() - 68;
        for (int row = 0; row < DROP.length; row++) {
            for (int col = 0; col < 5; col++) {
                char ch = DROP[row].charAt(col);
                if (ch == '.') continue;
                g.fill(x + col, y + row, x + col + 1, y + row + 1, ch == 'o' ? 0xFFB8DCFF : dropColor);
            }
        }
        g.drawString(mc.font, text, x + 8, y, textColor, true);
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        left = 0;
        holding = false;
    }
}
