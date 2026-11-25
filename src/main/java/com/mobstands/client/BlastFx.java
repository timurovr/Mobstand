package com.mobstands.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The screen side of a creeper Stand's blast: a white-hot fireball that swells and fades in the blast's colour, a
 * shock bubble bursting out, shockwave rings racing along the ground, and the camera shaking (harder the closer you
 * are). Creeper: creeper green. Green Detonator Requiem: charged electric blue.
 */
public final class BlastFx {
    private BlastFx() {}

    private static final class Blast {
        Vec3 at;
        float power;
        boolean requiem;
        int age;
    }

    private static final List<Blast> BLASTS = new ArrayList<>();
    private static final int LIFE = 22;
    private static float shake;
    private static long ticks;

    public static void add(double x, double y, double z, float power, boolean requiem) {
        Blast b = new Blast();
        b.at = new Vec3(x, y, z);
        b.power = Math.max(1.0F, power);
        b.requiem = requiem;
        BLASTS.add(b);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            double d = mc.player.position().distanceTo(b.at);
            float k = (float) Math.max(0.0D, 1.0D - d / (16.0D + b.power * 4.0D));
            shake = Math.max(shake, k * Math.min(3.0F, 0.8F + b.power * 0.4F));
        }
    }

    public static void tick() {
        ticks++;
        shake *= 0.78F;
        if (shake < 0.02F) shake = 0.0F;
        Iterator<Blast> it = BLASTS.iterator();
        while (it.hasNext()) if (++it.next().age > LIFE) it.remove();
    }

    public static void shake(net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles event) {
        if (shake <= 0.0F) return;
        float t = ticks + (float) event.getPartialTick();
        float s = shake * Minecraft.getInstance().options.screenEffectScale().get().floatValue();
        event.setYaw(event.getYaw() + s * (float) Math.sin(t * 9.7F));
        event.setPitch(event.getPitch() + s * 0.8F * (float) Math.sin(t * 12.3F + 1.1F));
        event.setRoll(event.getRoll() + s * 0.6F * (float) Math.sin(t * 7.9F + 2.3F));
    }

    public static void render(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (BLASTS.isEmpty() || event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        var cq = event.getCamera().rotation();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack ps = new PoseStack();
        for (Blast b : BLASTS) {
            float t = b.age + partial;
            float r = 1.2F + b.power * 0.55F;
            int hue = b.requiem ? 0x6FD8FF : 0x6BEA4A;   // charged blue / creeper green
            int hot = b.requiem ? 0xE8FBFF : 0xF4FFE0;
            ps.pushPose();
            ps.translate(b.at.x - cam.x, b.at.y - cam.y, b.at.z - cam.z);
            // The fireball: white-hot, swelling and fading into its colour.
            ps.pushPose();
            ps.mulPose(cq);
            float k = Mth.clamp(t / 9.0F, 0.0F, 1.0F);
            float e = 1.0F - (1.0F - k) * (1.0F - k) * (1.0F - k);
            StandRenderer.disc(ps, buffers, r * (0.5F + 1.1F * e), hue, 0.9F * (1.0F - k));
            StandRenderer.disc(ps, buffers, r * (0.3F + 0.6F * e), hot, 1.0F * (1.0F - k) * (1.0F - k));
            // The shock bubble: a thin ring bursting out fast.
            float k2 = Mth.clamp(t / 6.0F, 0.0F, 1.0F);
            StandRenderer.ring(ps, buffers, r * (0.4F + 2.4F * (1.0F - (1.0F - k2) * (1.0F - k2))), 0.12F * r * (1.0F - k2) + 0.03F, 0xFFFFFF, 0.8F * (1.0F - k2));
            ps.popPose();
            // Shockwave rings racing along the ground.
            ps.pushPose();
            ps.translate(0.0F, -0.55F, 0.0F);
            ps.mulPose(Axis.XP.rotationDegrees(90.0F));
            for (int i = 0; i < 2; i++) {
                float k3 = Mth.clamp((t - i * 2.0F) / 14.0F, 0.0F, 1.0F);
                if (k3 <= 0.0F || k3 >= 1.0F) continue;
                float e3 = 1.0F - (1.0F - k3) * (1.0F - k3);
                StandRenderer.ring(ps, buffers, r * (0.5F + 3.2F * e3), 0.35F * r * (1.0F - k3) + 0.05F, i == 0 ? hue : 0xFFFFFF, (i == 0 ? 0.85F : 0.55F) * (1.0F - k3));
            }
            ps.popPose();
            ps.popPose();
        }
        buffers.endBatch(ModRenderTypes.glow());
    }
}
