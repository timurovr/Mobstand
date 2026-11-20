package com.mobstands.client;

import com.mobstands.entity.StandPresence;
import com.mobstands.network.ClientStandCache;
import com.mobstands.stand.Ability;
import com.mobstands.stand.StandData;
import com.mobstands.stand.StandType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * Compact in-game overlay: Stand name, level, XP and ability cooldowns. Only shown while the Stand is actually
 * out in the world, and themed with the colours of that Stand's mob.
 */
public final class StandHud {
    private static final KeyMapping[] KEYS = {StandKeys.BASIC, StandKeys.ABILITY1, StandKeys.ABILITY2, StandKeys.ULTIMATE, StandKeys.ABILITY4, StandKeys.ABILITY5};

    /** Fades the HUD in when the Stand appears and out when it leaves. */
    private static float visibility;
    /** Last game tick the Stand was seen (keeps the HUD up while e.g. the Creeper Stand reforms). */
    private static long lastSeen = -1000L;

    private StandHud() {}

    /** accent = the mob's signature colour, base = its darkest body colour (panel background). */
    private record Palette(int accent, int base) {}

    private static Palette palette(StandType t) {
        return switch (t) {
            case CREEPER -> new Palette(0x5FD14A, 0x0F2A0C);    // creeper green
            case CREEPER_REQUIEM -> new Palette(0x7FD4FF, 0x0C1E2A); // charged-creeper blue
            case SKELETON -> new Palette(0xDCDCD2, 0x2A2A28);   // bone white
            case SPIDER -> new Palette(0xD8261E, 0x241C17);     // red eyes on dark brown
            case SLIME -> new Palette(0x83D46E, 0x1E3A18);      // jelly green
            case BLAZE -> new Palette(0xFF8A1E, 0x3A1A05);      // blaze-rod orange
            case IRON_GOLEM -> new Palette(0xD8D0C4, 0x2C2A26); // iron with a hint of rust
            case GER -> new Palette(0xF2C744, 0x1E1A0C);        // gold on deep bronze
            case PIGLIN_REQUIEM -> new Palette(0xB07CFF, 0x1C1030); // amethyst on deep purple
            case PHANTOM -> new Palette(0x7FA0E0, 0x161C30);    // night blue
            case ENDERMAN -> new Palette(0xD65CFF, 0x120A18);   // purple eyes on black
            case ENDER_WORLD -> new Palette(0xFFD54A, 0x14101E); // gold on black: The World
            case MINECART -> new Palette(0xB8C2CC, 0x1E2226);    // iron grey
            case WARDEN -> new Palette(0x2FE0EB, 0x082A30);     // sculk cyan on deep teal
            case WARDEN_REQUIEM -> new Palette(0x9FF7FB, 0x05181C); // pale sculk light on the deepest dark
            case SQUID -> new Palette(0x6FA3E8, 0x0D1828);      // rain blue on deep water
            case AXOLOTL -> new Palette(0xF7A1C4, 0x3A1424);    // axolotl pink
            case HORSE -> new Palette(0xC9925A, 0x2E1E10);      // chestnut brown
            case ALLAY -> new Palette(0x7FE6FF, 0x0E2A3A);      // allay sky blue
            case PIG -> new Palette(0xF4A8A8, 0x3A1A1A);        // pig pink
            case SNIFFER -> new Palette(0x5FD18A, 0x3A1E14);    // mossy green on sniffer red-brown
            case PIGLIN -> new Palette(0xF2C94C, 0x3A2410);     // piglin gold
            case ZOMBIFIED_PIGLIN -> new Palette(0x8DBE6A, 0x2A1E1E); // rotten green
            case SHULKER -> new Palette(0x7ED957, 0x1E2A14);
            case DOLPHIN -> new Palette(0x8FC9FF, 0x16222E);    // rain blue on storm grey    // C-Moon green on dark shell
        };
    }

    /** The Stand's signature colour (also used for its punch aura). */
    public static int auraColor(StandType t) {
        return palette(t).accent();
    }

    private static int argb(int alpha, int rgb) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static int mix(int a, int b, float t) {
        int r = (int) Mth.lerp(t, a >> 16 & 255, b >> 16 & 255);
        int g = (int) Mth.lerp(t, a >> 8 & 255, b >> 8 & 255);
        int bl = (int) Mth.lerp(t, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }

    /** Client: how long you've been in water (mirrors the server's count, for the refill label). */
    private static int waterTicks;
    private static long lastWaterTick;

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.level.getGameTime() != lastWaterTick) {
            lastWaterTick = mc.level.getGameTime();
            waterTicks = mc.player.isInWater() ? waterTicks + 1 : 0;
        }
        StandData d = ClientStandCache.get();
        StandType t = d.getType();
        long now = mc.level.getGameTime();
        renderSpin(g, mc, d, Math.min(1.0F, delta.getRealtimeDeltaTicks()));

        boolean present = t != null && StandPresence.get(mc.player.getUUID(), now) != null;
        if (present) lastSeen = now;
        boolean show = t != null && d.isSummoned() && (present || now - lastSeen < 100);
        float dt = Math.min(1.0F, delta.getRealtimeDeltaTicks());
        visibility = Mth.clamp(visibility + (show ? 0.3F : -0.35F) * dt, 0.0F, 1.0F);
        // Below ~5% Minecraft would draw text fully opaque (alpha under 4 means "no alpha"), so stop early.
        if (visibility < 0.05F || t == null || mc.options.hideGui) return;

        Palette pal = palette(t);
        int accent = pal.accent();
        int base = pal.base();
        float v = visibility;

        // Wide enough for the name plus "Requiem"/"Lv 10", and for every ability with its cooldown timer.
        int w = 138;
        w = Math.max(w, mc.font.width(t.standName()) + mc.font.width(t.requiem() ? "Requiem" : "Lv 10") + 12);
        for (int slot = 0; slot < StandData.SLOTS; slot++) {
            Ability ab = t.ability(slot);
            if (ab.isEmpty()) continue;
            String lab = "[" + (t == StandType.AXOLOTL && slot == 0 ? "Auto" : KeyLabels.of(KEYS[slot])) + "] " + ab.name();
            if (d.getLevel() < ab.unlockLevel()) lab += "  Lv" + ab.unlockLevel();
            w = Math.max(w, mc.font.width(lab) + mc.font.width("88.8") + 12);
        }
        int x = 6;
        // The panel grows with the number of rows (some Stands have a 5th ability).
        int rows = 0;
        for (int slot = 0; slot < StandData.SLOTS; slot++) if (!t.ability(slot).isEmpty()) rows++;
        if (specialControl(t, mc) != null) rows++;
        int panelH = 19 + rows * 12; // just tall enough for what this Stand has
        int y = mc.getWindow().getGuiScaledHeight() - 7 - panelH;

        // Panel: mob-coloured background with a thin accent border.
        g.fill(x - 4, y - 4, x + w + 4, y + panelH + 4, argb((int) (110 * v), accent));
        g.fill(x - 3, y - 3, x + w + 3, y + panelH + 3, argb((int) (205 * v), base));

        g.drawString(mc.font, t.standName(), x, y, argb((int) (255 * v), accent), true);
        if (t.requiem()) {
            String rq = "Requiem";
            g.drawString(mc.font, rq, x + w - mc.font.width(rq), y, argb((int) (255 * v), accent), true);
        } else if (t != StandType.HORSE) { // the Horse Stand doesn't level up
            String lv = "Lv " + d.getLevel();
            g.drawString(mc.font, lv, x + w - mc.font.width(lv), y, argb((int) (255 * v), 0xFFFFFF), true);

            // XP bar
            int barY = y + 11;
            g.fill(x, barY, x + w, barY + 4, argb((int) (255 * v), mix(base, 0x000000, 0.4F)));
            float xpFrac = d.getLevel() >= StandData.MAX_LEVEL ? 1.0F : d.getXp() / (float) d.xpForNext();
            g.fill(x, barY, x + (int) (w * xpFrac), barY + 4, argb((int) (255 * v), accent));
        }

        int ready = mix(accent, 0xFFFFFF, 0.25F);
        int row = 0;
        for (int slot = 0; slot < StandData.SLOTS; slot++) {
            Ability a = t.ability(slot);
            if (a.isEmpty()) continue; // no ability on this key
            int ry = y + 19 + row++ * 12;
            String key = "[" + (t == StandType.AXOLOTL && slot == 0 ? "Auto" : KeyLabels.of(KEYS[slot])) + "]";
            boolean locked = d.getLevel() < a.unlockLevel();
            int remaining = locked ? 0 : d.cooldownRemaining(slot, now);

            g.fill(x, ry, x + w, ry + 10, argb((int) (150 * v), mix(base, 0x000000, 0.35F)));
            if (remaining > 0) {
                int total = Math.max(1, a.cooldownTicks(d.getLevel()));
                int fill = (int) (w * (remaining / (float) total));
                g.fill(x, ry, x + Math.min(w, fill), ry + 10, argb((int) (95 * v), accent));
            }
            int color = locked ? 0x707070 : (remaining > 0 ? 0xA8A8A8 : ready);
            String label = key + " " + a.name();
            if (slot == 0 && PilotClient.active()) {
                label = key + " Shoot"; // piloting the Phantom: right click fires instead of punching
                remaining = 0;
            }
            if (locked) label += "  Lv" + a.unlockLevel();
            g.drawString(mc.font, label, x + 2, ry + 1, argb((int) (255 * v), color), false);
            if (remaining > 0) {
                String secs = String.format("%.1f", remaining / 20.0F);
                g.drawString(mc.font, secs, x + w - mc.font.width(secs) - 2, ry + 1, argb((int) (255 * v), 0xFFFFFF), false);
            }
        }
        // Allay: how long each duplicate has left (they glow blue while water is filling them back up).
        if (t == StandType.ALLAY && !d.allayDupes.isEmpty()) {
            int n = d.allayDupes.size();
            int top = y - 8 - 11 - n * 6;
            g.fill(x - 4, top - 3, x + w + 4, y - 6, argb((int) (110 * v), accent));
            g.fill(x - 3, top - 2, x + w + 3, y - 7, argb((int) (205 * v), base));
            boolean refilling = mc.player.isInWater() && waterTicks >= com.mobstands.stand.StandManager.ALLAY_WATER_DELAY;
            g.drawString(mc.font, refilling ? "Duplicates  (refilling)" : "Duplicates", x, top, argb((int) (255 * v), refilling ? 0x7FD4FF : accent), true);
            for (int i = 0; i < n; i++) {
                long left = d.allayDupes.get(i) - now;
                float frac = Mth.clamp(left / (float) com.mobstands.stand.StandAbilities.ALLAY_DUPE_TICKS, 0.0F, 1.0F);
                int by = top + 11 + i * 6;
                g.fill(x, by, x + w, by + 3, argb((int) (255 * v), mix(base, 0x000000, 0.4F)));
                g.fill(x, by, x + (int) (w * frac), by + 3, argb((int) (255 * v), refilling ? 0x7FD4FF : accent));
            }
        }
        // Pigstep: the stolen sounds, as spawn eggs above the panel (the ready one is framed).
        if (t == StandType.PIGLIN_REQUIEM && !d.stolenSounds.isEmpty() && v > 0.5F) {
            int n = d.stolenSounds.size();
            int top = y - 8 - 20;
            g.fill(x - 4, top - 3, x + 4 + n * 20 + 2, y - 6, argb((int) (110 * v), accent));
            g.fill(x - 3, top - 2, x + 3 + n * 20 + 2, y - 7, argb((int) (205 * v), base));
            for (int i = 0; i < n; i++) {
                int ix = x + i * 20;
                if (i == d.soundSel) g.fill(ix - 1, top - 1, ix + 17, top + 17, argb((int) (220 * v), accent));
                g.renderItem(com.mobstands.stand.SoundSteal.icon(d.stolenSounds.get(i)), ix, top);
            }
        }
        // Special controls that aren't on an ability key.
        String[] extra = specialControl(t, mc);
        if (extra != null) {
            int ry = y + 19 + row * 12;
            boolean on = extra[2] != null;
            g.fill(x, ry, x + w, ry + 10, argb((int) (150 * v), mix(base, 0x000000, 0.35F)));
            if (on) g.fill(x, ry, x + w, ry + 10, argb((int) (95 * v), accent));
            g.drawString(mc.font, "[" + extra[0] + "] " + extra[1], x + 2, ry + 1, argb((int) (255 * v), ready), false);
            if (on) g.drawString(mc.font, extra[2], x + w - mc.font.width(extra[2]) - 2, ry + 1, argb((int) (255 * v), 0xFFFFFF), false);
        }
    }

    // ---- The Spin technique: its own small gold panel, bottom right, shown while you're using it ----
    private static float spinVis;
    private static int lastSpinLevel = -1, lastSpinXp = -1;
    private static long spinShowUntil;

    private static void renderSpin(GuiGraphics g, Minecraft mc, StandData d, float dt) {
        long ms = net.minecraft.Util.getMillis();
        if (d.canSpin() && (d.spinLevel != lastSpinLevel || d.spinXp != lastSpinXp)) {
            if (lastSpinLevel != -1) spinShowUntil = ms + 3000L; // you just practised: show the progress for a moment
            lastSpinLevel = d.spinLevel;
            lastSpinXp = d.spinXp;
        }
        boolean show = d.canSpin() && (d.spinning || d.offSpinning || SpinInput.busy() || ms < spinShowUntil);
        spinVis = Mth.clamp(spinVis + (show ? 0.3F : -0.2F) * dt, 0.0F, 1.0F);
        if (spinVis < 0.05F || !d.canSpin() || mc.options.hideGui) return;
        float v = spinVis;
        int accent = 0xE8C25A, base = 0x2A2010;
        int w = 110;
        int x = mc.getWindow().getGuiScaledWidth() - w - 8;
        int y = mc.getWindow().getGuiScaledHeight() - 44;
        g.fill(x - 4, y - 4, x + w + 4, y + 33, argb((int) (110 * v), accent));
        g.fill(x - 3, y - 3, x + w + 3, y + 32, argb((int) (205 * v), base));
        g.drawString(mc.font, "Spin", x, y, argb((int) (255 * v), accent), true);
        boolean master = d.spinLevel >= StandData.SPIN_MAX_LEVEL;
        String lv = master ? "Master" : "Lv " + d.spinLevel;
        g.drawString(mc.font, lv, x + w - mc.font.width(lv), y, argb((int) (255 * v), 0xFFFFFF), true);
        int barY = y + 11;
        g.fill(x, barY, x + w, barY + 4, argb((int) (255 * v), mix(base, 0x000000, 0.4F)));
        float frac = master ? 1.0F : d.spinXp / (float) StandData.xpForNext(d.spinLevel);
        g.fill(x, barY, x + (int) (w * frac), barY + 4, argb((int) (255 * v), accent));
        int ry = y + 19;
        boolean on = d.spinning || d.offSpinning;
        g.fill(x, ry, x + w, ry + 10, argb((int) (150 * v), mix(base, 0x000000, 0.35F)));
        if (on) g.fill(x, ry, x + w, ry + 10, argb((int) (95 * v), accent));
        String label = on ? "[" + KeyLabels.of(mc.options.keyDrop) + "] Drop  (hold: stop)" : "[Hold " + KeyLabels.of(mc.options.keyDrop) + "] Spin";
        g.drawString(mc.font, label, x + 2, ry + 1, argb((int) (255 * v), mix(accent, 0xFFFFFF, 0.25F)), false);
    }

    /** {key label, name, state or null} for a Stand's non-ability control, or null if it has none. */
    private static String[] specialControl(StandType t, Minecraft mc) {
        if (t == StandType.HORSE) {
            String key = KeyLabels.of(mc.options.keySprint) + " x3";
            net.minecraft.world.effect.MobEffectInstance sp = mc.player.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED);
            boolean rushing = sp != null && sp.getAmplifier() >= 4;
            return new String[]{key, "Rush", RushFx.charging() ? "..." : rushing ? "ON" : null};
        }
        return null;
    }
}
