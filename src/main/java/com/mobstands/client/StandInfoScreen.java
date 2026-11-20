package com.mobstands.client;

import com.mobstands.network.ClientStandCache;
import com.mobstands.stand.Ability;
import com.mobstands.stand.StandData;
import com.mobstands.stand.StandType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Stand information: name, rarity, level, XP, abilities with live cooldowns, passive and progression path. */
public class StandInfoScreen extends Screen {
    private static final KeyMapping[] KEYS = {StandKeys.BASIC, StandKeys.ABILITY1, StandKeys.ABILITY2, StandKeys.ULTIMATE, StandKeys.ABILITY4, StandKeys.ABILITY5};
    private static final String[] SLOT_NAMES = {"Basic Attack", "Ability 1", "Ability 2", "Ultimate", "Ability 4", "Ability 5"};

    private int scroll;
    private int contentHeight;

    public StandInfoScreen() {
        super(Component.translatable("screen.mobstands.info"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (StandKeys.INFO.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = (int) Math.max(0, Math.min(Math.max(0, contentHeight - (height - 30)), scroll - scrollY * 14));
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Minecraft mc = Minecraft.getInstance();
        StandData d = ClientStandCache.get();
        StandType t = d.getType();

        int pw = Math.min(width - 20, 340);
        int px = (width - pw) / 2;
        int py = 10;
        int ph = height - 20;
        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, 0xFF404060);
        g.fill(px, py, px + pw, py + ph, 0xE6101018);

        if (t == null) {
            g.drawCenteredString(font, "You have no Stand.", width / 2, height / 2 - 10, 0xFFFFFF);
            g.drawCenteredString(font, "Use an Echoing Arrow to awaken one.", width / 2, height / 2 + 4, 0xAAAAAA);
            Component pool = poolLine(d);
            g.drawString(font, pool, width / 2 - font.width(pool) / 2, height / 2 + 22, 0xFFFFFF, false);
            return;
        }

        g.enableScissor(px, py, px + pw, py + ph);
        int x = px + 10;
        int tw = pw - 20;
        int y = py + 8 - scroll;
        long now = mc.level == null ? 0L : mc.level.getGameTime();
        int top = y;

        g.drawString(font, t.standName(), x, y, 0xFF000000 | t.color(), true);
        y += 12;
        g.drawString(font, t.mobName() + " Stand  -  " + t.rarity().label(), x, y, 0xFF000000 | t.rarity().color(), false);
        y += 14;
        if (!t.lore().isEmpty()) {
            // The Stand's story, in italics.
            for (FormattedCharSequence line : font.split(Component.literal(t.lore()).withStyle(net.minecraft.ChatFormatting.ITALIC), tw)) {
                g.drawString(font, line, x, y, 0xFF9C8FB8, false);
                y += 9;
            }
            y += 6;
        }

        g.drawString(font, (t.requiem() ? "Requiem - complete, every ability unlocked" : "Level " + d.getLevel() + " / " + StandData.MAX_LEVEL)
                + (d.isSummoned() ? "" : "   (dismissed)"), x, y, 0xFFFFFF, false);
        y += 11;
        g.fill(x, y, x + tw, y + 7, 0xFF202030);
        boolean max = d.getLevel() >= StandData.MAX_LEVEL;
        float frac = max ? 1.0F : d.getXp() / (float) d.xpForNext();
        g.fill(x, y, x + (int) (tw * frac), y + 7, 0xFF000000 | t.rarity().color());
        String xpText = t.requiem() ? "REQUIEM" : max ? "MAX LEVEL" : d.getXp() + " / " + d.xpForNext() + " XP";
        g.drawString(font, xpText, x + tw / 2 - font.width(xpText) / 2, y, 0xFFFFFF, true);
        y += 16;

        // Which Stands an Echoing Arrow can roll for you right now.
        g.drawString(font, poolLine(d), x, y, 0xFFFFFF, false);
        y += 14;

        g.drawString(font, "Abilities", x, y, 0xFFD700, false);
        y += 12;
        for (int slot = 0; slot < StandData.SLOTS; slot++) {
            Ability a = t.ability(slot);
            if (a.isEmpty()) continue;
            boolean locked = d.getLevel() < a.unlockLevel();
            int remaining = locked ? 0 : d.cooldownRemaining(slot, now);
            boolean passive = t == StandType.AXOLOTL && slot == 0;
            String head = "[" + (passive ? "Auto" : KeyLabels.of(KEYS[slot])) + "] " + a.name() + "  (" + (passive ? "Passive" : SLOT_NAMES[slot]) + ")";
            g.drawString(font, head, x, y, locked ? 0x777777 : 0x55FF55, false);
            y += 10;
            for (FormattedCharSequence line : font.split(Component.literal(a.description()), tw - 6)) {
                g.drawString(font, line, x + 6, y, locked ? 0x666666 : 0xBBBBBB, false);
                y += 9;
            }
            String status;
            if (locked) status = "Locked - unlocks at level " + a.unlockLevel();
            else if (remaining > 0) status = String.format("Cooldown: %.1fs / %.1fs", remaining / 20.0F, a.cooldownTicks(d.getLevel()) / 20.0F);
            else status = String.format("Ready  (cooldown %.1fs)", a.cooldownTicks(d.getLevel()) / 20.0F);
            g.drawString(font, status, x + 6, y, locked ? 0xAA5555 : (remaining > 0 ? 0xFFAA55 : 0x88FF88), false);
            y += 10;
            if (!locked) {
                int total = Math.max(1, a.cooldownTicks(d.getLevel()));
                g.fill(x + 6, y, x + tw, y + 3, 0xFF202030);
                float ready = 1.0F - remaining / (float) total;
                g.fill(x + 6, y, x + 6 + (int) ((tw - 6) * ready), y + 3, remaining > 0 ? 0xFFCC7733 : 0xFF55DD55);
            }
            y += 9;
        }

        y += 2;
        g.drawString(font, "Progression path", x, y, 0xFFD700, false);
        y += 12;
        for (int lvl = 1; lvl <= StandData.MAX_LEVEL; lvl++) {
            String entry = null;
            for (int slot = 0; slot < StandData.SLOTS; slot++) {
                if (!t.ability(slot).isEmpty() && t.ability(slot).unlockLevel() == lvl) entry = (slot == 0 ? "Basic: " : "Ability: ") + t.ability(slot).name();
            }
            if (t.passive().level() == lvl && !t.passive().name().isEmpty()) {
                String p = "Passive: " + t.passive().name() + " - " + t.passive().description();
                entry = entry == null ? p : entry + "; " + p;
            }
            if (entry == null) continue;
            boolean reached = d.getLevel() >= lvl;
            for (FormattedCharSequence line : font.split(Component.literal("Lv " + lvl + ": " + entry), tw)) {
                g.drawString(font, line, x, y, reached ? 0x88FF88 : 0x777777, false);
                y += 9;
            }
        }
        // Requiem Trial: only once you've reached the End with a max-strength Stand.
        if (com.mobstands.stand.RequiemTrials.open(d)) {
            y += 8;
            g.drawString(font, "Requiem Trial", x, y, 0xFFD9A8FF, false);
            y += 12;
            for (String step : com.mobstands.stand.RequiemTrials.lines(d)) {
                boolean done = step.startsWith("+");
                for (FormattedCharSequence line : font.split(Component.literal((done ? "\u2714 " : "\u25C7 ") + step.substring(2)), tw - 6)) {
                    g.drawString(font, line, x + 6, y, done ? 0x88FF88 : 0xBBA8D8, false);
                    y += 9;
                }
            }
        }
        y += 6;
        g.drawString(font, "Higher levels also boost damage and shorten cooldowns.", x, y, 0x888888, false);
        y += 14;
        contentHeight = y - top + 20;
        g.disableScissor();
    }

    /** "Arrow pool: ✔ Overworld  ✔ Nether  ✖ End" - lit up as you reach each part of the world. */
    private static Component poolLine(com.mobstands.stand.StandData d) {
        net.minecraft.network.chat.MutableComponent c = Component.literal("Arrow pool:  ").withStyle(s -> s.withColor(0xFFD700));
        c.append(part("Overworld", true, 0x7EDB6E));
        c.append(Component.literal("   "));
        c.append(part("Nether", d.reachedNether, 0xFF7A3A));
        c.append(Component.literal("   "));
        c.append(part("End", d.reachedEnd, 0xD9A8FF));
        return c;
    }

    private static Component part(String name, boolean open, int color) {
        return Component.literal((open ? "\u2714 " : "\u2716 ") + name).withStyle(s -> s.withColor(open ? color : 0x666666));
    }
}
