package com.mobstands.client;

import com.mobstands.entity.CurseEntity;
import com.mobstands.stand.Curses;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;

/** A line at the top of the screen while a cursed Stand has you: which one, and how to get rid of it. */
public final class CurseHud {
    private CurseHud() {}

    private static final String[] HINTS = {
            "Let a villager or player see your back",
            "Wears off in a few minutes",
            "It chases the fastest thing. Sneak. Sleep to lose it",
            "Win " + Curses.RPS_WINS + " rounds of rock-paper-scissors",
    };

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        CurseEntity mine = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof CurseEntity c && c.owner() == mc.player.getId()) {
                mine = c;
                break;
            }
        }
        if (mine == null) return;
        int k = mine.kind();
        int w = g.guiWidth();
        int y = 4;
        String title = "☠ " + Curses.NAMES[k];
        g.drawString(mc.font, title, (w - mc.font.width(title)) / 2, y, Curses.COLORS[k]);
        String hint = HINTS[k];
        g.drawString(mc.font, hint, (w - mc.font.width(hint)) / 2, y + 11, 0xA0A0A0);
        String sup = "Your Stand is smothered until it's gone";
        g.drawString(mc.font, sup, (w - mc.font.width(sup)) / 2, y + 22, 0xC04060);
        if (k == CurseEntity.BOY_II_MAN) {
            int d = mine.data();
            int wins = (d >> 8) & 15, losses = (d >> 12) & 15;
            String s = "Wins " + wins + "/" + Curses.RPS_WINS + " · Hearts taken: " + Math.min(5, losses);
            g.drawString(mc.font, s, (w - mc.font.width(s)) / 2, y + 33, 0xD8B8FF);
        }
    }
}
