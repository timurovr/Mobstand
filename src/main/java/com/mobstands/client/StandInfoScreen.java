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

/** Stand information: name, kind of Stand, level, XP, abilities with live cooldowns, passive and progression path. */
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

        if (t == null && d.spinLearned) {
            renderSpin(g, d, px, py, pw, ph);
            return;
        }
        if (t == null) {
            g.drawCenteredString(font, "You have no Stand.", width / 2, height / 2 - 10, 0xFFFFFF);
            g.drawCenteredString(font, "Use an Echoing Arrow to awaken one.", width / 2, height / 2 + 4, 0xAAAAAA);
            g.drawCenteredString(font, "(Or read a Spin scroll and learn the Spin instead.)", width / 2, height / 2 + 38, 0xC9A24A);
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

        g.drawString(font, t.standName() + (t.pigAct() > 0 ? " " + t.actLabel() : ""), x, y, 0xFF000000 | t.color(), true);
        y += 12;
        g.drawString(font, t.mobName() + " Stand  -  " + (t.requiem() ? "Requiem, " : "") + t.kind().label(), x, y, 0xFF000000 | t.kind().color(), false);
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
        g.fill(x, y, x + (int) (tw * frac), y + 7, 0xFF000000 | t.color());
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

        // Pigstep (ACT 4): every Steal option explained, with examples.
        if (t == StandType.PIGLIN_REQUIEM) {
            y += 4;
            g.drawString(font, "Steal Guide", x, y, 0xFFD700, false);
            y += 12;
            for (com.mobstands.stand.StealGuide.Entry en : com.mobstands.stand.StealGuide.ENTRIES) {
                if (en.header()) {
                    y += 3;
                    g.drawString(font, en.name(), x, y, en.color(), false);
                    y += 11;
                    continue;
                }
                g.drawString(font, "\u25C6 " + en.name(), x + 4, y, 0xFF000000 | en.color(), false);
                y += 10;
                for (FormattedCharSequence line : font.split(Component.literal(en.text()), tw - 12)) {
                    g.drawString(font, line, x + 12, y, 0xBBBBBB, false);
                    y += 9;
                }
                y += 3;
            }
            y += 6;
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

    // ------------------------------------------------------------------ the Spin

    private record Tech(String name, String keys, int level, String text) {}

    /** Every Spin technique: its name, how to do it, the Spin level it needs, and what it does. */
    private static Tech[] techs(String q) {
        return new Tech[]{
                new Tech("Spinning", "Hold " + q + ", then hit the cue", 1,
                        "Hold " + q + " with any item in your hand. A cue pops up: below Spin mastery, hit " + q + " while the marker "
                                + "is in the gold zone (the better you are, the bigger the zone and the slower the marker); at mastery "
                                + "you just spam it. Land it and the item starts spinning - violently at first, slowly winding down. "
                                + "Tap " + q + " alone and you just drop the item, like normal."),
                new Tech("Spin Throw", "Tap " + q + " while it spins", 1,
                        "Hurl the spinning item. Whatever it hits is spun round and round on the spot, ends up facing the wrong "
                                + "way and stays confused for a while - it can't lock onto anyone. Where it lands it keeps spinning on "
                                + "the floor for up to 30 seconds and blasts anyone who comes close."),
                new Tech("Stop", "Hold " + q + " while it spins", 1, "Hold it for a second and a bar fills: the spin stops and you keep the item."),
                new Tech("Off-Hand Spin", "Shift (or empty main hand) + " + q, 1,
                        "Everything works on your off hand too. Press F and a spinning item keeps spinning in whichever hand it ends "
                                + "up in, so you can spin one in each hand. Spinning both at once needs a longer cue."),
                new Tech("Double Throw", "Both hands spinning, tap " + q, 1, "Both items fly out at once, a little apart."),
                new Tech("Spin Rocket", "Both spinning, look down, tap " + q, 1,
                        "Hurl both at your own feet: the double spin kicks off the ground and rockets you up (higher the longer you "
                                + "wound up). No fall damage until you land."),
                new Tech("Ground Slash", "Spinning sword: hit the ground", 1,
                        "With a spinning sword in your main hand, strike the ground: the sword rolls forward in your hand and a chain "
                                + "of golden crescent slashes races along the ground in front of you, curving like a spinning blade, "
                                + "cutting everyone in its path. Uses up the spin."),
                new Tech("Orbiting Arrows", "Spinning bow or crossbow: shoot", 1,
                        "Arrows from a spinning bow don't fly off - they orbit you, faster and faster, cutting into anything that "
                                + "gets close, until they tear apart. 1 arrow at once for a rookie, 2 from Spin level 6, 3 for a master."),
                new Tech("Steel Balls", "Master (Spin level 10)", 10,
                        "Only a master can spin a Steel Ball. Its spin gets into the flesh: whoever it hits is slowed and weakened, "
                                + "an archer it hits turns its bow on another mob instead of you, and wherever it bounces it leaves a "
                                + "golden patch of spin behind for a moment that catches everyone in it."),
                new Tech("Return", "Master (Spin level 10)", 10,
                        "Thrown Steel Balls and weapons come back to your hand - still spinning. Right-click a far-off ball and it "
                                + "flies back to you."),
                new Tech("Sky Toss", "Master: Steel Ball, look up, tap " + q, 10,
                        "Toss a spinning Steel Ball up into the sky - then it follows where you look: look down at a mob and it "
                                + "dives on it, or swing it sideways."),
                new Tech("Horse Spin", "Riding, spinning Steel Ball, right-click", 10,
                        "The Spin goes into your horse's legs: it runs faster, jumps higher and never takes fall damage. Zig-zag hard "
                                + "left, right, left (tacking, like a ship against the wind) to build up to 5 extra bursts of speed; ride "
                                + "dead straight and they drain away. Look straight down and right-click with an empty hand to take your balls back. A Sky Toss while "
                                + "riding and looking straight down sends the ball under your horse and launches it into the sky."),
        };
    }

    private void renderSpin(GuiGraphics g, StandData d, int px, int py, int pw, int ph) {
        Minecraft mc = Minecraft.getInstance();
        g.enableScissor(px, py, px + pw, py + ph);
        int x = px + 10, tw = pw - 20, y = py + 8 - scroll, top = y;
        int lv = d.spinLevel;
        boolean master = lv >= StandData.SPIN_MAX_LEVEL;
        g.drawString(font, "The Spin", x, y, 0xFFE8C25A, true);
        y += 12;
        g.drawString(font, "Technique  -  " + (master ? "Master" : "Student"), x, y, 0xFFC9A24A, false);
        y += 14;
        String lore = "Not a Stand - a technique, passed down a family of executioners. Anything set spinning in a perfect golden "
                + "rotation carries a power of its own: it twists flesh, turns arrows, and comes home to the hand that threw it. "
                + "It can't be learned by anyone who already has a Stand.";
        for (FormattedCharSequence line : font.split(Component.literal(lore).withStyle(net.minecraft.ChatFormatting.ITALIC), tw)) {
            g.drawString(font, line, x, y, 0xFFB8A87A, false);
            y += 9;
        }
        y += 6;
        g.drawString(font, "Spin Level " + lv + " / " + StandData.SPIN_MAX_LEVEL, x, y, 0xFFFFFF, false);
        y += 11;
        g.fill(x, y, x + tw, y + 7, 0xFF202030);
        float frac = master ? 1.0F : d.spinXp / (float) StandData.xpForNext(lv);
        g.fill(x, y, x + (int) (tw * frac), y + 7, 0xFFE8C25A);
        String xpText = master ? "MASTERED" : d.spinXp + " / " + StandData.xpForNext(lv) + " XP";
        g.drawString(font, xpText, x + tw / 2 - font.width(xpText) / 2, y, 0xFFFFFF, true);
        y += 12;
        g.drawString(font, "You get better by landing spins (missing the timing still teaches you a little).", x, y, 0x888888, false);
        y += 14;
        // What your level means right now.
        g.drawString(font, "Your Spin", x, y, 0xFFD700, false);
        y += 12;
        int strength = Math.round(com.mobstands.stand.StandManager.spinMult(lv) * 100.0F);
        int seconds = com.mobstands.stand.StandManager.spinTicks(lv) / 20;
        int arrows = com.mobstands.stand.StandManager.spinArrowsFor(lv);
        String[] stats = {
                "Strength: " + strength + "% (a rookie hits at half strength, a master at full)",
                "A spin lasts: " + seconds + " s (18 s for a rookie, a full minute for a master)",
                "Orbiting arrows: " + arrows + " at once",
                master ? "Starting a spin: just spam the key" : "Starting a spin: a timing test (gets easier every level)",
                master ? "Steel Balls: yes" : "Steel Balls: only at Spin level 10",
        };
        for (String st : stats) {
            for (FormattedCharSequence line : font.split(Component.literal("\u2022 " + st), tw - 6)) {
                g.drawString(font, line, x + 6, y, 0xBBBBBB, false);
                y += 9;
            }
        }
        y += 6;
        g.drawString(font, "Techniques", x, y, 0xFFD700, false);
        y += 12;
        String q = KeyLabels.of(mc.options.keyDrop);
        for (Tech tech : techs(q)) {
            boolean locked = lv < tech.level();
            g.drawString(font, "[" + tech.keys() + "] " + tech.name(), x, y, locked ? 0x777777 : 0x55FF55, false);
            y += 10;
            for (FormattedCharSequence line : font.split(Component.literal(tech.text()), tw - 6)) {
                g.drawString(font, line, x + 6, y, locked ? 0x666666 : 0xBBBBBB, false);
                y += 9;
            }
            g.drawString(font, locked ? "Locked - needs Spin level " + tech.level() : "Ready", x + 6, y, locked ? 0xAA5555 : 0x88FF88, false);
            y += 13;
        }
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
