package com.mobstands.stand;

import com.mobstands.network.PossessPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/**
 * Axolotl Stand: Soul Control. Your soul slips into the mob you're looking at - you see through its eyes and walk
 * it around (WASD, Space to jump, left click to attack). Walk it off a cliff, into lava, into a crowd... when its
 * body dies, your soul snaps back into yours. Press the key again to come back any time. Your own body just stands
 * there meanwhile - defenceless.
 */
public final class SoulControl {
    private SoulControl() {}

    public static final double RANGE = 24.0D;
    public static final double LEASH = 64.0D;

    /** How long you can stay inside: 2 seconds when you first get it (level 6), 2 more every level, up to 10 at level 10. */
    public static int durationTicks(int standLevel) {
        return 20 * Mth.clamp(2 + (standLevel - 6) * 2, 2, 10);
    }

    private static final class Ctl {
        final UUID owner;
        final Mob mob;
        final boolean wasNoAi;
        float forward, strafe, yaw, pitch;
        boolean jump, down;
        long lastAbility = -1000L;
        /** Game time your soul gets pulled back. */
        long until;
        Vec3 vel = Vec3.ZERO;
        long lastAttack;

        Ctl(UUID owner, Mob mob) {
            this.owner = owner;
            this.mob = mob;
            this.wasNoAi = mob.isNoAi();
            this.yaw = mob.getYRot();
        }
    }

    private static final Map<UUID, Ctl> CTL = new HashMap<>();

    public static boolean active(ServerPlayer p) {
        return CTL.containsKey(p.getUUID());
    }

    /** The ability: possess the mob you're looking at - or, if you're already in one, come back. */
    public static boolean use(AbilityContext c) {
        ServerPlayer p = c.owner;
        if (active(p)) {
            end(p, "Your soul slips back into your body.");
            return true;
        }
        LivingEntity t = c.aim(RANGE);
        if (!(t instanceof Mob m) || m instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon
                || m instanceof net.minecraft.world.entity.boss.wither.WitherBoss) {
            p.displayClientMessage(Component.literal("Look at a mob to take control of it.").withStyle(s -> s.withColor(0xF7A1C4)), true);
            return false;
        }
        for (Ctl other : CTL.values()) if (other.mob == m) return false; // someone's already in there
        Ctl ctl = new Ctl(p.getUUID(), m);
        ctl.pitch = m.getXRot();
        ctl.until = p.level().getGameTime() + durationTicks(StandManager.data(p).getLevel());
        m.setNoAi(true);
        m.setTarget(null);
        m.getNavigation().stop();
        CTL.put(p.getUUID(), ctl);
        ServerLevel sl = p.serverLevel();
        sl.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 1.2F, 0.8F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AXOLOTL_SPLASH, SoundSource.PLAYERS, 0.8F, 0.6F);
        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SOUL, m.getX(), m.getY() + m.getBbHeight() * 0.6D, m.getZ(), 20, 0.3D, 0.4D, 0.3D, 0.04D);
        PacketDistributor.sendToPlayer(p, new PossessPayload(m.getId()));
        p.displayClientMessage(Component.literal("Soul Control! WASD to walk, Space to jump, left click to attack. Press again to return.")
                .withStyle(s -> s.withColor(0xF7A1C4)), true);
        return true;
    }

    /** What the possessing player is pressing (sent from their game every tick). */
    public static void input(ServerPlayer p, float forward, float strafe, boolean jump, boolean down, float yaw, float pitch) {
        Ctl c = CTL.get(p.getUUID());
        if (c == null) return;
        c.forward = Mth.clamp(forward, -1.0F, 1.0F);
        c.strafe = Mth.clamp(strafe, -1.0F, 1.0F);
        c.jump = jump;
        c.down = down;
        c.yaw = yaw;
        c.pitch = Mth.clamp(pitch, -90.0F, 90.0F);
    }

    /** Left click while possessing: the mob attacks whatever's right in front of it. */
    public static void attack(ServerPlayer p) {
        Ctl c = CTL.get(p.getUUID());
        if (c == null) return;
        Mob m = c.mob;
        long now = m.level().getGameTime();
        if (now - c.lastAttack < 10L) return;
        c.lastAttack = now;
        m.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        if (m instanceof net.minecraft.world.entity.monster.Creeper creeper) {
            creeper.ignite(); // a creeper's only move
            return;
        }
        Vec3 eye = m.getEyePosition();
        Vec3 look = Vec3.directionFromRotation(c.pitch, c.yaw);
        Vec3 end = eye.add(look.scale(3.5D));
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : m.level().getEntitiesOfClass(LivingEntity.class, new AABB(eye, end).inflate(1.0D),
                x -> x != m && x.isAlive() && !x.isSpectator())) {
            var hit = e.getBoundingBox().inflate(0.3D).clip(eye, end);
            if (hit.isEmpty()) continue;
            double d = hit.get().distanceToSqr(eye);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        if (best == null) return;
        if (m.getAttribute(Attributes.ATTACK_DAMAGE) != null) m.doHurtTarget(best);
        else best.hurt(m.damageSources().mobAttack(m), 2.0F);
    }

    /** Every server tick: walk each possessed mob by its pilot's keys; when it dies, its pilot comes home. */
    public static void tick(MinecraftServer server) {
        if (CTL.isEmpty()) return;
        Iterator<Map.Entry<UUID, Ctl>> it = CTL.entrySet().iterator();
        while (it.hasNext()) {
            Ctl c = it.next().getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(c.owner);
            Mob m = c.mob;
            String why = null;
            if (p == null) why = "";
            else if (!m.isAlive() || m.isRemoved()) why = "Its body died... your soul snaps back into yours.";
            else if (!p.isAlive() || m.level() != p.level() || m.distanceTo(p) > LEASH) why = "Your soul snaps back into your body.";
            else if (StandManager.data(p).getType() != StandType.AXOLOTL || StandManager.getStand(p) == null) why = "Your soul snaps back into your body.";
            else if (m.level().getGameTime() >= c.until) why = "Time's up - your soul snaps back into your body.";
            if (why != null) {
                it.remove();
                release(c, p, why);
                continue;
            }
            if (!m.level().tickRateManager().runsNormally()) continue; // stopped time
            // Inside a Warden you're as blind as it is.
            if (m instanceof net.minecraft.world.entity.monster.warden.Warden && m.tickCount % 20 == 0) {
                p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.DARKNESS, 60, 0, false, false));
                p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.BLINDNESS, 60, 0, false, false));
            }
            drive(c);
            long left = c.until - m.level().getGameTime();
            if (left % 5L == 0L) {
                float secs = left / 20.0F;
                p.displayClientMessage(Component.literal(String.format("Soul Control: %.1fs", secs))
                        .withStyle(s -> s.withColor(secs <= 1.5F ? 0xFF6E6E : 0xF7A1C4)), true);
            }
        }
    }

    private static void drive(Ctl c) {
        Mob m = c.mob;
        float yr = c.yaw * Mth.DEG_TO_RAD;
        Vec3 fwd = new Vec3(-Mth.sin(yr), 0.0D, Mth.cos(yr));
        Vec3 left = new Vec3(Mth.cos(yr), 0.0D, Mth.sin(yr));
        Vec3 wish = fwd.scale(c.forward).add(left.scale(c.strafe));
        if (wish.lengthSqr() > 1.0D) wish = wish.normalize();
        var speedAttr = m.getAttribute(Attributes.MOVEMENT_SPEED);
        double speed = Mth.clamp(speedAttr != null ? speedAttr.getValue() : 0.25D, 0.12D, 0.4D);
        boolean fluid = m.isInWater() || m.isInLava();
        if (fluid) speed *= 0.5D;
        double vy = c.vel.y;
        if (flies(m)) {
            // Fliers: Space up, Shift down, and they hang in the air otherwise.
            vy = c.jump ? 0.3D : c.down ? -0.3D : vy * 0.6D;
            speed = Math.max(speed, 0.3D);
        } else if (m instanceof net.minecraft.world.entity.monster.Spider && m.horizontalCollision && c.forward > 0.0F) {
            vy = 0.2D; // spiders walk up walls
        } else if (fluid) {
            vy = c.jump ? 0.08D : vy * 0.8D - 0.01D;
        } else if (m.onGround()) {
            vy = c.jump ? 0.42D : 0.0D;
        } else {
            vy = (vy - 0.08D) * 0.98D;
        }
        Vec3 v = new Vec3(wish.x * speed, vy, wish.z * speed);
        m.setDeltaMovement(v);
        m.move(MoverType.SELF, v);
        c.vel = m.getDeltaMovement();
        if (m.verticalCollision && c.vel.y < 0.0D) c.vel = new Vec3(c.vel.x, 0.0D, c.vel.z);
        m.setYRot(c.yaw);
        m.setYHeadRot(c.yaw);
        m.yBodyRot = c.yaw;
        m.setXRot(c.pitch);
        m.hasImpulse = true;
    }

    private static boolean flies(Mob m) {
        return m instanceof net.minecraft.world.entity.FlyingMob || m instanceof net.minecraft.world.entity.animal.FlyingAnimal
                || m instanceof net.minecraft.world.entity.ambient.Bat || m instanceof net.minecraft.world.entity.monster.Blaze
                || m instanceof net.minecraft.world.entity.monster.Vex || m instanceof net.minecraft.world.entity.animal.allay.Allay
                || m instanceof net.minecraft.world.entity.boss.wither.WitherBoss;
    }

    /**
     * Right click: the possessed mob's own power. Creepers blow, Wardens Sonic Boom, skeletons and pillagers shoot,
     * Blazes and Ghasts throw fire, Endermen teleport, Snow Golems throw snowballs, Llamas spit, Evokers raise fangs,
     * Breezes fire wind charges, Witches throw harming potions, Shulkers fire homing bullets, Goats ram.
     */
    public static void ability(ServerPlayer p) {
        Ctl c = CTL.get(p.getUUID());
        if (c == null) return;
        Mob m = c.mob;
        ServerLevel sl = (ServerLevel) m.level();
        long now = sl.getGameTime();
        int cd = m instanceof net.minecraft.world.entity.monster.warden.Warden ? 60 : m instanceof net.minecraft.world.entity.monster.Ghast ? 40 : 16;
        if (now - c.lastAbility < cd) return;
        Vec3 look = Vec3.directionFromRotation(c.pitch, c.yaw);
        Vec3 eye = m.getEyePosition();
        boolean used = true;
        if (m instanceof net.minecraft.world.entity.monster.Creeper creeper) {
            creeper.ignite();
        } else if (m instanceof net.minecraft.world.entity.monster.warden.Warden w) {
            // Sonic Boom: straight through everything along your look.
            sl.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 3.0F, 1.0F);
            java.util.Set<LivingEntity> hit = new java.util.HashSet<>();
            for (int i = 1; i <= 15; i++) {
                Vec3 at = eye.add(look.scale(i));
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SONIC_BOOM, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(1.2D), x -> x != w && x.isAlive())) {
                    if (!hit.add(e)) continue;
                    e.hurt(sl.damageSources().sonicBoom(w), 10.0F);
                    e.push(look.x * 2.5D, 0.5D, look.z * 2.5D);
                }
            }
        } else if (m instanceof net.minecraft.world.entity.monster.AbstractSkeleton || m instanceof net.minecraft.world.entity.monster.Pillager
                || m instanceof net.minecraft.world.entity.monster.piglin.Piglin) {
            var arrow = new net.minecraft.world.entity.projectile.Arrow(sl, eye.x, eye.y - 0.1D, eye.z,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
            arrow.setOwner(m);
            arrow.setBaseDamage(2.0D);
            arrow.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.DISALLOWED;
            arrow.shoot(look.x, look.y, look.z, 2.4F, 1.0F);
            sl.addFreshEntity(arrow);
            sl.playSound(null, m.getX(), m.getY(), m.getZ(), m instanceof net.minecraft.world.entity.monster.Pillager
                    ? SoundEvents.CROSSBOW_SHOOT : SoundEvents.SKELETON_SHOOT, SoundSource.HOSTILE, 1.0F, 1.0F);
        } else if (m instanceof net.minecraft.world.entity.monster.Blaze) {
            for (int i = 0; i < 3; i++) {
                final int k = i;
                TickScheduler.later(k * 6 + 1, () -> {
                    if (!m.isAlive()) return;
                    Vec3 lk = Vec3.directionFromRotation(c.pitch, c.yaw);
                    var fb = new net.minecraft.world.entity.projectile.SmallFireball(sl, m, lk.scale(1.0D));
                    fb.setPos(m.getEyePosition().add(lk));
                    sl.addFreshEntity(fb);
                    sl.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 1.0F, 1.0F);
                });
            }
        } else if (m instanceof net.minecraft.world.entity.monster.Ghast g) {
            var fb = new net.minecraft.world.entity.projectile.LargeFireball(sl, g, look, 1);
            fb.setPos(eye.add(look.scale(2.0D)));
            sl.addFreshEntity(fb);
            sl.playSound(null, g.getX(), g.getY(), g.getZ(), SoundEvents.GHAST_SHOOT, SoundSource.HOSTILE, 3.0F, 1.0F);
        } else if (m instanceof net.minecraft.world.entity.monster.EnderMan) {
            var bh = sl.clip(new net.minecraft.world.level.ClipContext(eye, eye.add(look.scale(24.0D)),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, m));
            Vec3 to = bh.getLocation().subtract(look.scale(0.8D));
            sl.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 1.0F);
            m.teleportTo(to.x, to.y, to.z);
            sl.playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 1.0F);
        } else if (m instanceof net.minecraft.world.entity.animal.SnowGolem) {
            var sb = new net.minecraft.world.entity.projectile.Snowball(sl, m);
            sb.setPos(eye);
            sb.shoot(look.x, look.y, look.z, 1.6F, 2.0F);
            sl.addFreshEntity(sb);
            sl.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.SNOW_GOLEM_SHOOT, SoundSource.NEUTRAL, 1.0F, 1.0F);
        } else if (m instanceof net.minecraft.world.entity.animal.horse.Llama llama) {
            var spit = new net.minecraft.world.entity.projectile.LlamaSpit(sl, llama);
            spit.shoot(look.x, look.y, look.z, 1.5F, 1.0F);
            sl.addFreshEntity(spit);
            sl.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.LLAMA_SPIT, SoundSource.NEUTRAL, 1.0F, 1.0F);
        } else if (m instanceof net.minecraft.world.entity.monster.Evoker) {
            for (int i = 1; i <= 12; i++) {
                Vec3 at = m.position().add(look.multiply(1.0D, 0.0D, 1.0D).normalize().scale(i * 1.25D));
                var fang = new net.minecraft.world.entity.projectile.EvokerFangs(sl, at.x, m.getY(), at.z, c.yaw * Mth.DEG_TO_RAD, i, m);
                sl.addFreshEntity(fang);
            }
            sl.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.0F, 1.0F);
        } else if (m instanceof net.minecraft.world.entity.monster.breeze.Breeze) {
            var wc = new net.minecraft.world.entity.projectile.windcharge.BreezeWindCharge(m instanceof net.minecraft.world.entity.monster.breeze.Breeze b ? b : null, sl);
            wc.setPos(eye.add(look));
            wc.shoot(look.x, look.y, look.z, 1.2F, 0.0F);
            sl.addFreshEntity(wc);
            sl.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.BREEZE_SHOOT, SoundSource.HOSTILE, 1.0F, 1.0F);
        } else if (m instanceof net.minecraft.world.entity.monster.Witch) {
            var potion = new net.minecraft.world.entity.projectile.ThrownPotion(sl, m);
            potion.setItem(net.minecraft.world.item.alchemy.PotionContents.createItemStack(net.minecraft.world.item.Items.SPLASH_POTION,
                    net.minecraft.world.item.alchemy.Potions.HARMING));
            potion.shoot(look.x, look.y + 0.15D, look.z, 0.9F, 2.0F);
            sl.addFreshEntity(potion);
            sl.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.WITCH_THROW, SoundSource.HOSTILE, 1.0F, 1.0F);
        } else if (m instanceof net.minecraft.world.entity.monster.Shulker) {
            LivingEntity target = lookTarget(m, eye, look, 32.0D);
            if (target == null) used = false;
            else {
                sl.addFreshEntity(new net.minecraft.world.entity.projectile.ShulkerBullet(sl, m, target, net.minecraft.core.Direction.Axis.Y));
                sl.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.SHULKER_SHOOT, SoundSource.HOSTILE, 1.0F, 1.0F);
            }
        } else if (m instanceof net.minecraft.world.entity.animal.goat.Goat) {
            c.vel = new Vec3(look.x * 1.4D, 0.2D, look.z * 1.4D); // ram!
            sl.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.GOAT_RAM_IMPACT, SoundSource.NEUTRAL, 1.0F, 1.0F);
            LivingEntity target = lookTarget(m, eye, look, 4.0D);
            if (target != null) {
                target.hurt(sl.damageSources().mobAttack(m), 4.0F);
                target.push(look.x * 2.0D, 0.6D, look.z * 2.0D);
            }
        } else {
            used = false;
        }
        if (used) c.lastAbility = now;
    }

    private static LivingEntity lookTarget(Mob m, Vec3 eye, Vec3 look, double range) {
        Vec3 end = eye.add(look.scale(range));
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : m.level().getEntitiesOfClass(LivingEntity.class, new AABB(eye, end).inflate(1.5D), x -> x != m && x.isAlive())) {
            var hit = e.getBoundingBox().inflate(0.6D).clip(eye, end);
            if (hit.isEmpty()) continue;
            double d = hit.get().distanceToSqr(eye);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    public static void end(ServerPlayer p, String why) {
        Ctl c = CTL.remove(p.getUUID());
        if (c != null) release(c, p, why);
    }

    private static void release(Ctl c, ServerPlayer p, String why) {
        if (c.mob.isAlive() && !c.mob.isRemoved()) c.mob.setNoAi(c.wasNoAi);
        if (p == null) return;
        PacketDistributor.sendToPlayer(p, new PossessPayload(-1));
        p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 1.0F, 1.3F);
        if (!why.isEmpty()) p.displayClientMessage(Component.literal(why).withStyle(s -> s.withColor(0xF7A1C4)), true);
    }

    /** The server's stopping: give every possessed mob its own mind back before it's saved. */
    public static void shutdown() {
        for (Ctl c : CTL.values()) if (c.mob.isAlive()) c.mob.setNoAi(c.wasNoAi);
        CTL.clear();
    }
}
