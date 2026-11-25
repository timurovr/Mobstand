package com.mobstands.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Stand tools on screen: Oh! Lonesome Llama's rope (thick, brown, sagging, braided) and Reef Boy's fishing line (thin
 * and pale, drawn taut - and straight through walls, like Beach Boy's), from your hand to whatever's on the other end.
 */
public final class RopeFx {
    private RopeFx() {}

    private record Line(int target, Vec3 spot, int kind) {}

    private static final Map<Integer, Line> LINES = new HashMap<>();

    public static void set(int owner, int target, double x, double y, double z, int kind, boolean on) {
        if (!on) LINES.remove(owner);
        else LINES.put(owner, new Line(target, new Vec3(x, y, z), kind));
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || LINES.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            LINES.clear();
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(ModRenderTypes.cosmos());
        Matrix4f m = new PoseStack().last().pose();
        for (Map.Entry<Integer, Line> en : LINES.entrySet()) {
            if (!(mc.level.getEntity(en.getKey()) instanceof Player owner)) continue;
            Line l = en.getValue();
            Vec3 b;
            if (l.target() >= 0) {
                Entity t = mc.level.getEntity(l.target());
                if (t == null) continue;
                b = t.getPosition(partial).add(0.0D, t.getBbHeight() * 0.55D, 0.0D);
            } else b = l.spot();
            Vec3 a = hand(mc, owner, partial);
            boolean lasso = l.kind() == 0;
            int n = 24;
            Vec3 prev = null;
            double len = b.distanceTo(a);
            for (int i = 0; i <= n; i++) {
                double u = (double) i / n;
                // a rope sags under its own weight; a fishing line stays taut
                double sag = lasso ? Math.sin(u * Math.PI) * Math.min(1.2D, len * 0.08D) : Math.sin(u * Math.PI) * Math.min(0.3D, len * 0.01D);
                Vec3 pt = a.add(b.subtract(a).scale(u)).add(0.0D, -sag, 0.0D);
                if (prev != null) {
                    int col = lasso ? ((i % 2 == 0) ? 0x8A5A2E : 0xB07A44) : 0xE8F4F2;
                    segment(vc, m, cam, prev, pt, lasso ? 0.035F : 0.012F, col);
                }
                prev = pt;
            }
            if (!lasso && l.target() < 0) {
                // the bobber
                cube(vc, m, cam, b, 0.07F, 0xE03A3A);
            }
            if (lasso && l.target() >= 0) {
                // the loop around it
                Entity t = mc.level.getEntity(l.target());
                double r = Math.max(0.3D, t.getBbWidth() * 0.6D);
                Vec3 c = b;
                Vec3 p0 = null;
                for (int k = 0; k <= 16; k++) {
                    double ang = k * Math.PI * 2.0D / 16.0D;
                    Vec3 pt = c.add(Math.cos(ang) * r, 0.0D, Math.sin(ang) * r);
                    if (p0 != null) segment(vc, m, cam, p0, pt, 0.035F, 0x9A6A38);
                    p0 = pt;
                }
            }
        }
        buffers.endBatch(ModRenderTypes.cosmos());
    }

    private static Vec3 hand(Minecraft mc, Player p, float partial) {
        if (p == mc.player && mc.options.getCameraType().isFirstPerson()) {
            // in first person: from just below and to the right of your view
            Vec3 eye = p.getEyePosition(partial);
            Vec3 look = p.getViewVector(partial);
            Vec3 right = look.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
            return eye.add(look.scale(0.6D)).add(right.scale(0.35D)).add(0.0D, -0.35D, 0.0D);
        }
        float yaw = Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot) * Mth.DEG_TO_RAD;
        Vec3 right = new Vec3(-Mth.cos(yaw), 0.0D, -Mth.sin(yaw));
        return p.getPosition(partial).add(0.0D, 1.3D, 0.0D).add(right.scale(0.4D));
    }

    private static void segment(VertexConsumer vc, Matrix4f m, Vec3 cam, Vec3 a, Vec3 b, float w, int rgb) {
        Vec3 d = b.subtract(a).normalize();
        Vec3 s1 = d.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (s1.lengthSqr() < 1.0E-4D) s1 = new Vec3(1.0D, 0.0D, 0.0D);
        s1 = s1.normalize().scale(w);
        Vec3 s2 = s1.cross(d).normalize().scale(w);
        quad(vc, m, cam, a.add(s1), a.subtract(s1), b.subtract(s1), b.add(s1), rgb);
        quad(vc, m, cam, a.add(s2), a.subtract(s2), b.subtract(s2), b.add(s2), rgb);
    }

    private static void cube(VertexConsumer vc, Matrix4f m, Vec3 cam, Vec3 c, float r, int rgb) {
        segment(vc, m, cam, c.add(0.0D, -r, 0.0D), c.add(0.0D, r, 0.0D), r, rgb);
    }

    private static void quad(VertexConsumer vc, Matrix4f m, Vec3 cam, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, bl = rgb & 255;
        vc.addVertex(m, (float) (a.x - cam.x), (float) (a.y - cam.y), (float) (a.z - cam.z)).setColor(r, g, bl, 255);
        vc.addVertex(m, (float) (b.x - cam.x), (float) (b.y - cam.y), (float) (b.z - cam.z)).setColor(r, g, bl, 255);
        vc.addVertex(m, (float) (c.x - cam.x), (float) (c.y - cam.y), (float) (c.z - cam.z)).setColor(r, g, bl, 255);
        vc.addVertex(m, (float) (d.x - cam.x), (float) (d.y - cam.y), (float) (d.z - cam.z)).setColor(r, g, bl, 255);
    }
}
