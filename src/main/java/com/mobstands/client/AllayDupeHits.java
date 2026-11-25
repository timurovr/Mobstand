package com.mobstands.client;

import com.mobstands.network.StandActionPayload;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Punching your duplicate Allays: they're drawn by the Stand, so the client works out which one your swing hit
 * (ray against where it was drawn), flashes it red, knocks it back, and tells the server.
 */

//timur gay
public final class AllayDupeHits {
    private AllayDupeHits() {}

    private static final Map<Integer, Long> HIT_AT = new HashMap<>();
    private static final Map<Integer, Vec3> KNOCK = new HashMap<>();

    /** Left click: did it land on a duplicate (closer than whatever block or mob you're aiming at)? */
    public static boolean tryHit() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || StandRenderer.dupePositions.isEmpty()) return false;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(mc.player.getLookAngle().scale(4.5D));
        double limit = 4.5D;
        if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) limit = mc.hitResult.getLocation().distanceTo(eye);
        int best = -1;
        double bestD = limit;
        for (int i = 0; i < StandRenderer.dupePositions.size(); i++) {
            Vec3 p = StandRenderer.dupePositions.get(i);
            AABB box = new AABB(p.x - 0.35D, p.y, p.z - 0.35D, p.x + 0.35D, p.y + 0.75D, p.z + 0.35D);
            Optional<Vec3> hit = box.clip(eye, end);
            if (hit.isPresent() && hit.get().distanceTo(eye) < bestD) {
                bestD = hit.get().distanceTo(eye);
                best = i;
            }
        }
        if (best < 0) return false;
        Vec3 p = StandRenderer.dupePositions.get(best);
        HIT_AT.put(best, Util.getMillis());
        Vec3 away = p.subtract(mc.player.position()).multiply(1.0D, 0.0D, 1.0D);
        KNOCK.put(best, away.lengthSqr() < 1.0E-4D ? Vec3.ZERO : away.normalize().scale(0.6D).add(0.0D, 0.2D, 0.0D));
        mc.player.playSound(SoundEvents.ALLAY_HURT, 0.8F, 1.0F);
        mc.level.addParticle(ParticleTypes.DAMAGE_INDICATOR, p.x, p.y + 0.5D, p.z, 0.0D, 0.0D, 0.0D);
        PacketDistributor.sendToServer(new StandActionPayload(200 + best));
        return true;
    }

    public static boolean flashing(int i) {
        Long t = HIT_AT.get(i);
        return t != null && Util.getMillis() - t < 400L;
    }

    /** The knocked-back offset, easing back to its spot over half a second. */
    public static Vec3 knock(int i, float partial) {
        Long t = HIT_AT.get(i);
        Vec3 k = KNOCK.get(i);
        if (t == null || k == null) return Vec3.ZERO;
        float f = (Util.getMillis() - t) / 500.0F;
        if (f >= 1.0F) return Vec3.ZERO;
        double s = Math.sin(f * Math.PI) ; // out and back
        return k.scale(s);
    }
}
