package com.mobstands.client;

import com.mobstands.entity.StandPresence;
import com.mobstands.stand.StandType;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Third-person player animations layered on top of vanilla:
 *  - livelier sprinting, airborne pose and walking sway for every player;
 *  - a signature stance per Stand type while that player's Stand is out and they're standing still.
 */
public final class PlayerPoses {
    /** Per-player smoothing state: [stance weight, last ageInTicks]. */
    private static final Map<UUID, float[]> STATE = new HashMap<>();
    /** The pose each player was last holding, so it can fade out smoothly after they drop it. */
    private static final Map<UUID, StandType> LAST_POSE = new HashMap<>();

    /** Your character's extra sprint swing, jump/fall arms and walking sway: off - plain vanilla movement. */
    private static final boolean PLAYER_MOVE_FX = false;

    private PlayerPoses() {}

    public static void apply(HumanoidModel<?> m, LivingEntity e, float limbSwing, float amount, float age, float headPitch) {
        if (!(e instanceof Player p)) return;
        m.head.zRot = 0.0F; // vanilla never resets the head's roll, and the model is shared by every player
        if (age == 0.0F) return; // first-person hand rendering: leave the arms alone
        if (CartRide.riding(p) || CartRide.ridingDolphin(p)) {
            // Sitting in the Minecart Stand (or astride the Dolphin): vanilla's riding pose, hands on the rim.
            m.rightLeg.xRot = -1.4137167F;
            m.rightLeg.yRot = 0.31415927F;
            m.rightLeg.zRot = 0.07853982F;
            m.leftLeg.xRot = -1.4137167F;
            m.leftLeg.yRot = -0.31415927F;
            m.leftLeg.zRot = -0.07853982F;
            if (m.attackTime <= 0.0F && !p.isUsingItem()) {
                m.rightArm.xRot = -0.55F;
                m.leftArm.xRot = -0.55F;
                m.rightArm.zRot = 0.25F;
                m.leftArm.zRot = -0.25F;
            }
            return;
        }
        if (p.isSleeping() || p.isSwimming() || p.isFallFlying() || p.isPassenger() || p.isSpectator()) return;

        boolean busy = m.attackTime > 0.0F || p.isUsingItem();
        // Standing on a wall or ceiling (C-Moon) counts as standing: vanilla walking, not the falling pose.
        boolean turned = com.mobstands.stand.GravityBox.CLIENT.containsKey(p.getId());
        boolean grounded = p.onGround() || p.getAbilities().flying || p.isInWater() || turned;

        // ---- everyone: sprint, jump/fall, walking sway (switched off: plain vanilla movement)
        if (PLAYER_MOVE_FX && !busy) {
            if (p.isSprinting() && grounded) {
                m.rightArm.xRot *= 1.35F;
                m.leftArm.xRot *= 1.35F;
                m.rightArm.zRot += 0.12F;
                m.leftArm.zRot -= 0.12F;
                m.head.xRot += 0.08F;
            } else if (amount > 0.1F && grounded) {
                float sway = Mth.sin(limbSwing * 0.6662F) * 0.07F * amount;
                m.rightArm.zRot += 0.04F + sway;
                m.leftArm.zRot -= 0.04F - sway;
            }
            if (!grounded && !p.isCrouching()) {
                m.rightArm.zRot += 0.45F;
                m.leftArm.zRot -= 0.45F;
                m.rightArm.xRot -= 0.25F;
                m.leftArm.xRot -= 0.25F;
                m.rightLeg.xRot = -0.45F;
                m.leftLeg.xRot = 0.25F;
            }
        }

        // ---- Carried by the Allay: both hands up holding on to it, legs dangling
        if (StandPresence.lifting(p.getUUID(), p.level().getGameTime())) {
            float dangle = Mth.sin(age * 0.12F) * 0.12F;
            m.rightArm.xRot = -Mth.PI + 0.15F;
            m.rightArm.yRot = 0.0F;
            m.rightArm.zRot = 0.22F;
            m.leftArm.xRot = -Mth.PI + 0.15F;
            m.leftArm.yRot = 0.0F;
            m.leftArm.zRot = -0.22F;
            m.rightLeg.xRot = 0.15F + dangle;
            m.leftLeg.xRot = 0.05F - dangle;
            m.rightLeg.zRot = 0.04F;
            m.leftLeg.zRot = -0.04F;
            m.hat.copyFrom(m.head);
            return;
        }

        // ---- Spin: a hand holding a spinning Steel Ball is held out at your side for as long as it spins - walking,
        // running, whatever - and both hands if both are spinning. For the first 2 seconds you also show it off like
        // Gyro: the free hand comes up in front and your head tips down to watch the ball.
        if (p == net.minecraft.client.Minecraft.getInstance().player && !busy) {
            float part = Mth.frac(age);
            float main = SpinFx.elapsed(part, false), off = SpinFx.elapsed(part, true);
            boolean mainOn = main >= 0.0F, offOn = off >= 0.0F;
            if (mainOn || offOn) {
                float br = Mth.sin(age * 0.2F) * 0.03F;
                if (mainOn) arm(m.rightArm, 0.25F, 0.0F, 0.55F + br, Mth.clamp(main / 5.0F, 0.0F, 1.0F));
                if (offOn) arm(m.leftArm, 0.25F, 0.0F, -0.55F - br, Mth.clamp(off / 5.0F, 0.0F, 1.0F));
                if (mainOn != offOn) {
                    float t = mainOn ? main : off;
                    if (t < 40.0F) {
                        float sw = Mth.clamp(t / 5.0F, 0.0F, 1.0F) * Mth.clamp((40.0F - t) / 8.0F, 0.0F, 1.0F);
                        sw = sw * sw * (3.0F - 2.0F * sw);
                        ModelPart show = mainOn ? m.leftArm : m.rightArm;
                        float side = mainOn ? -1.0F : 1.0F;
                        arm(show, -1.45F + br, -0.18F * side, 0.0F, sw);
                        head(m, 0.3F, 0.0F, sw);
                        if (amount < 0.1F) {
                            leg(m.rightLeg, -0.06F, 0.08F, sw);
                            leg(m.leftLeg, 0.08F, -0.08F, sw);
                        }
                    }
                }
                m.hat.copyFrom(m.head);
            }
        }

        // ---- Stand stance, blended in smoothly when standing still with the Stand out
        StandType type = StandPresence.posing(p.getUUID(), p.level().getGameTime())
                ? StandPresence.get(p.getUUID(), p.level().getGameTime()) : null; // only after pressing the pose key (J)
        float target = (type != null && !busy && grounded && !p.isCrouching()) ? Mth.clamp(1.0F - amount * 4.0F, 0.0F, 1.0F) : 0.0F;
        float[] st = STATE.computeIfAbsent(p.getUUID(), u -> new float[]{0.0F, age});
        float dt = age - st[1];
        if (dt < 0.0F || dt > 20.0F) dt = 0.0F;
        st[1] = age;
        st[0] += (target - st[0]) * (1.0F - (float) Math.exp(-dt * 0.22F));
        float w = st[0];
        if (type != null) LAST_POSE.put(p.getUUID(), type);
        else type = LAST_POSE.get(p.getUUID()); // pose dropped: keep easing out of the last one
        if (type == null || w < 0.01F) {
            if (w < 0.01F) LAST_POSE.remove(p.getUUID());
            m.hat.copyFrom(m.head);
            return;
        }

        float br = Mth.sin(age * 0.067F) * 0.03F; // breathing
        switch (type) {
            case IRON_GOLEM -> { // arms crossed, chin up, feet apart
                arm(m.rightArm, -1.2F, -0.7F, br, w);
                arm(m.leftArm, -1.2F, 0.7F, -br, w);
                head(m, -0.15F, 0.0F, w);
                leg(m.rightLeg, 0.0F, 0.1F, w);
                leg(m.leftLeg, 0.0F, -0.1F, w);
            }
            case ENDER_WORLD -> { } // no player pose: you just stand normally
            case GER -> {
                // Your arm reaches out at 45 degrees toward your Stand standing beside you.
                int s = standSide(p);
                if (s > 0) arm(m.rightArm, -0.25F, 0.0F, 0.785F + br, w); // it's on your right
                else arm(m.leftArm, -0.25F, 0.0F, -0.785F - br, w);       // it's on your left
            }
            case ENDERMAN -> { // pointing straight at you
                arm(m.rightArm, -1.6F, -0.15F, br, w);
                arm(m.leftArm, 0.1F, 0.0F, -0.1F - br, w);
                head(m, 0.1F, 0.12F, w);
                leg(m.rightLeg, -0.15F, 0.0F, w);
                leg(m.leftLeg, 0.1F, 0.0F, w);
            }
            case WARDEN_REQUIEM -> {
                // Wonder of Deep is right behind you... and you can't look at it. Everything goes still: arms hang
                // dead at your sides, feet together, no breathing - only your head, bowed down and turned away to
                // one side, like you're ashamed. Or scared.
                still(m.rightArm, w);
                still(m.leftArm, w);
                still(m.rightLeg, w);
                still(m.leftLeg, w);
                m.head.xRot = Mth.lerp(w, m.head.xRot, 0.62F);
                m.head.yRot = Mth.lerp(w, m.head.yRot, 0.32F);
                m.head.zRot = 0.28F * w;
            }
            case WARDEN -> { // menacing hunch, head down, arms loose
                arm(m.rightArm, -0.35F, 0.0F, 0.2F + br, w);
                arm(m.leftArm, -0.35F, 0.0F, -0.2F - br, w);
                head(m, 0.35F, 0.0F, w);
                leg(m.rightLeg, 0.0F, 0.15F, w);
                leg(m.leftLeg, 0.0F, -0.15F, w);
            }
            case CREEPER, CREEPER_REQUIEM -> { // hand over the face, other hand on the hip
                arm(m.rightArm, -2.4F, -0.55F, 0.2F, w);
                arm(m.leftArm, -0.25F, 0.0F, -0.65F - br, w);
                head(m, -0.1F, 0.25F, w);
                leg(m.rightLeg, 0.0F, 0.06F, w);
                leg(m.leftLeg, -0.2F, 0.0F, w);
            }
            case BLAZE -> { // fist to the sky, looking up
                arm(m.rightArm, -3.0F, -0.1F, 0.15F, w);
                arm(m.leftArm, 0.15F, 0.0F, -0.25F - br, w);
                head(m, -0.4F, -0.1F, w);
                leg(m.rightLeg, 0.0F, 0.08F, w);
                leg(m.leftLeg, 0.0F, -0.08F, w);
            }
            case SKELETON -> { // arm thrown out to the side, hand on hip
                arm(m.rightArm, -0.05F, 0.0F, 1.45F, w);
                arm(m.leftArm, -0.25F, 0.0F, -0.65F - br, w);
                head(m, 0.0F, 0.15F, w);
                leg(m.rightLeg, 0.0F, 0.1F, w);
                leg(m.leftLeg, 0.0F, -0.05F, w);
            }
            case SPIDER -> { // low predator crouch, arms out
                arm(m.rightArm, -0.4F, 0.0F, 0.55F + br, w);
                arm(m.leftArm, -0.4F, 0.0F, -0.55F - br, w);
                head(m, 0.25F, 0.0F, w);
                leg(m.rightLeg, 0.0F, 0.2F, w);
                leg(m.leftLeg, 0.0F, -0.2F, w);
            }
            case PHANTOM -> { // "Nightfall": one hand raised to the sky calling it down, the other swept back, chin up
                arm(m.rightArm, -2.95F, -0.15F, 0.12F + br, w);
                arm(m.leftArm, 0.45F, 0.0F, -0.35F - br, w);
                head(m, -0.45F, 0.15F, w);
                leg(m.rightLeg, -0.12F, 0.08F, w);
                leg(m.leftLeg, 0.22F, -0.06F, w);
            }
            case HORSE -> { // ready for the race: right hand shading your eyes, scanning the course; left hand out at your side
                arm(m.rightArm, -2.45F + br, -0.6F, 0.05F, w);
                arm(m.leftArm, 0.25F, 0.0F, -0.55F, w);
                head(m, -0.12F, 0.0F, w);
                leg(m.rightLeg, -0.06F, 0.08F, w);
                leg(m.leftLeg, 0.08F, -0.08F, w);
            }
            case AXOLOTL -> { // relaxed, hands loosely clasped in front, head tilted
                arm(m.rightArm, -0.55F, -0.45F, 0.0F, w);
                arm(m.leftArm, -0.55F, 0.45F, 0.0F, w);
                head(m, 0.1F, 0.0F, w);
            }
            case SQUID -> { // both palms raised to the sky, calling the rain
                arm(m.rightArm, -2.5F, -0.2F, 0.45F + br, w);
                arm(m.leftArm, -2.5F, 0.2F, -0.45F - br, w);
                head(m, -0.35F, 0.0F, w);
            }
            case PIGLIN_REQUIEM -> { // Pigstep: pointing down at your enemy like they owe you gold, other fist on the hip, wide stance, chin up
                arm(m.rightArm, -1.15F + br, -0.3F, 0.3F, w);
                arm(m.leftArm, -0.3F, 0.0F, -0.7F - br, w);
                head(m, -0.22F, 0.0F, w);
                leg(m.rightLeg, -0.05F, 0.16F, w);
                leg(m.leftLeg, 0.08F, -0.16F, w);
            }
            case SLIME -> { // hands on hips, casual head tilt
                arm(m.rightArm, -0.25F, 0.0F, 0.65F + br, w);
                arm(m.leftArm, -0.25F, 0.0F, -0.65F - br, w);
                head(m, 0.0F, -0.18F, w);
                leg(m.rightLeg, 0.0F, 0.15F, w);
            }
        }
        m.hat.copyFrom(m.head);
    }

    /** Which side of you your Stand is standing on: 1 = your right, -1 = your left. */
    private static int standSide(Player p) {
        for (com.mobstands.entity.StandEntity s : p.level().getEntitiesOfClass(com.mobstands.entity.StandEntity.class, p.getBoundingBox().inflate(4.0D),
                x -> x.getOwnerPlayer() == p)) {
            return s.side();
        }
        return 1;
    }

    private static void arm(ModelPart p, float x, float y, float z, float w) {
        p.xRot = Mth.lerp(w, p.xRot, x);
        p.yRot = Mth.lerp(w, p.yRot, y);
        p.zRot = Mth.lerp(w, p.zRot, z);
    }

    private static void leg(ModelPart p, float x, float z, float w) {
        p.xRot = Mth.lerp(w, p.xRot, x);
        p.zRot = Mth.lerp(w, p.zRot, z);
    }

    /** Adds a pitch offset, keeping the head following where the player looks. Never tilts it sideways. */
    /** A limb hanging perfectly still and straight (vanilla's idle sway and walk swing faded out). */
    private static void still(ModelPart p, float w) {
        p.xRot = Mth.lerp(w, p.xRot, 0.0F);
        p.yRot = Mth.lerp(w, p.yRot, 0.0F);
        p.zRot = Mth.lerp(w, p.zRot, 0.0F);
    }

    private static void head(HumanoidModel<?> m, float pitch, float tilt, float w) {
        m.head.xRot += pitch * w;
    }
}
