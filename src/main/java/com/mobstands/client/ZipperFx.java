package com.mobstands.client;

import com.mobstands.entity.ZipperEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Silver Fingers, on the client: people sliding in and out through zippers, and - for the one inside - the pocket
 * dimension: a swirling dark purple, the zipper you came through as a slit of the real world down the middle of your
 * screen, closing until there's nothing left but purple. You can't move while a zipper has you.
 */
public final class ZipperFx {
    private ZipperFx() {}

    /** Player entity id -> the zipper that has them. Rebuilt every tick. */
    private static final Map<Integer, ZipperEntity> ZIPS = new HashMap<>();

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        ZIPS.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ZipperEntity z) || z.occupant() < 0) continue;
            int s = z.state();
            if (s > ZipperEntity.SLIDE_OUT) continue;
            ZipperEntity cur = ZIPS.get(z.occupant());
            if (cur == null || z.stateAt() >= cur.stateAt()) ZIPS.put(z.occupant(), z);
        }
    }

    public static ZipperEntity zipOf(Entity e) {
        return e == null || ZIPS.isEmpty() ? null : ZIPS.get(e.getId());
    }

    /** How far into its zipper this player is (0 outside, 1 all the way in). */
    public static float inAmount(Entity e, float partial) {
        ZipperEntity z = zipOf(e);
        return z == null ? 0.0F : z.inAmount(partial);
    }

    /** Where to draw someone partway through a zipper: drawn into the surface, through the split. */
    private static Vec3 slideOffset(Player p, ZipperEntity z, float k, float partial) {
        Vec3 n = z.normalVec();
        Vec3 pos = p.getPosition(partial);
        Vec3 to = z.position().subtract(pos);
        double depth = -to.dot(n); // how far in front of the surface you are
        Vec3 lateral = to.subtract(n.scale(to.dot(n)));
        if (Math.abs(n.y) < 0.5D) lateral = new Vec3(lateral.x, 0.0D, lateral.z);
        double in = Math.abs(n.y) > 0.5D ? 2.1D : 1.0D;
        return lateral.scale(Math.min(1.0D, k * 1.6D)).add(n.scale(-(Math.max(0.0D, depth) + in) * k));
    }

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        Player p = event.getEntity();
        ZipperEntity z = zipOf(p);
        if (z == null) return;
        float partial = event.getPartialTick();
        float k = z.inAmount(partial);
        if (k >= 0.999F) {
            event.setCanceled(true);
            return;
        }
        if (k <= 0.0F) return;
        Vec3 o = slideOffset(p, z, k, partial);
        event.getPoseStack().translate(o.x, o.y, o.z);
    }

    @SubscribeEvent
    public static void onHand(RenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (inAmount(mc.player, event.getPartialTick()) > 0.5F) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onInput(MovementInputUpdateEvent event) {
        if (zipOf(event.getEntity()) == null) return;
        var in = event.getInput();
        in.forwardImpulse = 0.0F;
        in.leftImpulse = 0.0F;
        in.up = in.down = in.left = in.right = false;
        in.jumping = false;
        in.shiftKeyDown = false;
    }

    // ------------------------------------------------------------------ the pocket dimension

    private static final int CELL = 4;
    private static final long SEED = 0x5EEDL;

    @SubscribeEvent
    public static void onGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        ZipperEntity z = zipOf(mc.player);
        if (z == null) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        float k = z.inAmount(partial);
        if (k <= 0.01F) return;
        int st = z.state();
        // The slit of real world you can still see: the zipper you came in through, as it shuts (or opens again).
        float open = st == ZipperEntity.INSIDE || st == ZipperEntity.OPEN_EXIT ? z.openAmount(partial) : 1.0F;
        GuiGraphics g = event.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight();
        float time = mc.level.getGameTime() + partial;
        float cx = w * 0.5F;
        float slider = open * h;                     // the slider runs top to bottom
        float maxHw = w * 0.22F * (1.0F - 0.6F * (1.0F - k)); // (narrower while you're still sliding in)
        float lensHw = Math.min(maxHw, slider * 0.28F);
        int alpha = (int) (235 * Mth.clamp(k * 1.3F, 0.0F, 1.0F));

        for (int y = 0; y < h; y += CELL) {
            float yc = y + CELL * 0.5F;
            float hw = slitHalf(yc, slider, lensHw);
            for (int x = 0; x < w; x += CELL) {
                float xc = x + CELL * 0.5F;
                if (Math.abs(xc - cx) < hw) continue;
                // See-through: only a faint purple haze in the middle, thickening into the swirl at the edges.
                int ca = (int) (alpha * see(xc / w, yc / h));
                if (ca > 2) g.fill(x, y, x + CELL, y + CELL, (ca << 24) | dimension(xc / w, yc / h, time));
            }
        }
        // Crackling streaks through it.
        for (int l = 0; l < 6; l++) {
            float flick = Mth.sin(time * 0.37F + l * 2.1F);
            if (flick < 0.2F) continue;
            java.util.Random r = new java.util.Random(SEED + l * 31L + (long) (time / 9.0F));
            float x = r.nextFloat() * w, y = r.nextFloat() * h;
            float ang = r.nextFloat() * Mth.TWO_PI;
            int la = (int) (alpha * Mth.clamp(flick, 0.0F, 1.0F) * 0.8F);
            for (int sgm = 0; sgm < 9; sgm++) {
                ang += (r.nextFloat() - 0.5F) * 1.6F;
                float len = 6.0F + r.nextFloat() * 10.0F;
                for (float t = 0.0F; t < len; t += 1.5F) {
                    float px = x + Mth.cos(ang) * t, py = y + Mth.sin(ang) * t;
                    if (Math.abs(px - cx) < slitHalf(py, slider, lensHw) + 2.0F) continue;
                    int pa = (int) (la * see(px / w, py / h));
                    if (pa > 2) g.fill((int) px, (int) py, (int) px + 2, (int) py + 1, (pa << 24) | 0xE0C0FF);
                }
                x += Mth.cos(ang) * len;
                y += Mth.sin(ang) * len;
            }
        }
        // The zipper's teeth along both edges of the slit, and the slider at the end of the open part.
        int ta = Math.min(255, alpha + 20);
        for (int y = 0; y < h; y += 4) {
            float hw = slitHalf(y + 2.0F, slider, lensHw);
            if (y < slider) {
                int lx = (int) (cx - hw), rx = (int) (cx + hw);
                g.fill(lx - 4, y, lx, y + 2, (ta << 24) | 0xF0C25A);
                g.fill(lx - 4, y + 2, lx, y + 3, (ta << 24) | 0x9A6E22);
                g.fill(rx, y + 2, rx + 4, y + 4, (ta << 24) | 0xF0C25A);
                g.fill(rx, y + 4, rx + 4, y + 5, (ta << 24) | 0x9A6E22);
            } else {
                boolean left = (y / 4) % 2 == 0; // locked together
                g.fill(left ? (int) cx - 4 : (int) cx - 1, y, left ? (int) cx + 1 : (int) cx + 4, y + 3, (ta << 24) | 0xF0C25A);
            }
        }
        if (open < 0.999F) {
            int sy = (int) slider;
            g.fill((int) cx - 7, sy - 5, (int) cx + 7, sy + 6, (ta << 24) | 0xB8862A);
            g.fill((int) cx - 5, sy - 4, (int) cx + 5, sy + 5, (ta << 24) | 0xF2C75A);
            g.fill((int) cx - 3, sy + 5, (int) cx + 3, sy + 16, (ta << 24) | 0xE8B84A);
            g.fill((int) cx - 1, sy + 11, (int) cx + 1, sy + 14, (ta << 24) | 0x3A2408);
        }
        if (st == ZipperEntity.INSIDE && z.since(partial) > ZipperEntity.OPEN_TICKS + 10) {
            String key = KeyLabels.of(StandKeys.ABILITY2);
            String msg = "Press " + key + " to unzip";
            g.drawCenteredString(mc.font, msg, w / 2, h - 52, 0xD8B8FF);
        }
    }

    /** How thick the dimension is at (u, v): a light haze in the middle of the screen, dense at the edges. */
    private static float see(float u, float v) {
        float dx = (u - 0.5F) * 2.0F, dy = (v - 0.5F) * 2.0F;
        float r = Mth.sqrt(dx * dx * 0.8F + dy * dy);
        float t = Mth.clamp((r - 0.55F) / 0.6F, 0.0F, 1.0F);
        t = t * t * (3.0F - 2.0F * t);
        return 0.16F + 0.74F * t;
    }

    private static float slitHalf(float y, float slider, float hw) {
        if (y >= slider || slider < 1.0F) return 0.0F;
        float t = y / slider;
        return hw * (float) Math.pow(Math.max(0.0F, Mth.sin(t * Mth.PI)), 0.7D);
    }

    /** The pocket dimension's colour at (u, v): deep purple, swirling, brighter veins drifting through it. */
    private static int dimension(float u, float v, float time) {
        float t = time * 0.02F;
        float dx = u - 0.5F, dy = (v - 0.5F) * 0.6F;
        float r = Mth.sqrt(dx * dx + dy * dy);
        float ang = (float) Math.atan2(dy, dx);
        float f = Mth.sin(ang * 3.0F + r * 14.0F - t * 3.0F + Mth.sin(r * 7.0F + t) * 1.5F) * 0.5F + 0.5F;
        float g = Mth.sin(u * 9.0F + t * 2.0F + Mth.sin(v * 7.0F - t) * 2.0F) * 0.5F + 0.5F;
        float val = (float) Math.pow(f * 0.6F + g * 0.4F, 2.2D);
        float vig = Mth.clamp(1.0F - r * 0.9F, 0.35F, 1.0F);
        float rr = Mth.lerp(val, 18, 92), gg = Mth.lerp(val, 5, 30), bb = Mth.lerp(val, 34, 168);
        if (val > 0.8F) { // the brightest veins go lilac
            float hi = (val - 0.8F) / 0.2F;
            rr = Mth.lerp(hi, rr, 190); gg = Mth.lerp(hi, gg, 120); bb = Mth.lerp(hi, bb, 255);
        }
        return ((int) (rr * vig) << 16) | ((int) (gg * vig) << 8) | (int) (bb * vig);
    }
}
