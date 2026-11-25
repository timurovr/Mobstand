package com.mobstands.client;

import com.mobstands.entity.StandEntity;
import com.mobstands.mixin.RavagerAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Scary Monsters' herd on screen, and the dinosaur's eyes:
 * <ul>
 *   <li>an infected creature shakes and sheds skin, then a Ravager pushes out of it - the same size the creature was -
 *       and once it has turned, only the dinosaur is drawn (walking with the creature's legs, snapping when it bites);</li>
 *   <li>while you're a Ravager, anything that stands still disappears from your view - a dinosaur only sees what moves.
 *       Your own herd stays visible.</li>
 * </ul>
 */
public final class DinoClient {
    private DinoClient() {}

    /** Height of a real Ravager (blocks): a dinosaur is scaled so it stands as tall as the creature it was. */
    private static final float RAVAGER_HEIGHT = 2.2F;
    /** Ticks something must stand still before a transformed user stops seeing it. */
    private static final int STILL_TICKS = 10;

    private record Infection(long start, int dur, UUID owner) {}

    private static final Map<Integer, Infection> INFECTED = new HashMap<>();
    private static final Map<Integer, Ravager> BODIES = new HashMap<>();
    private static final Map<Integer, Boolean> WAS_SWINGING = new HashMap<>();
    /** Entity id -> client tick it last moved. */
    private static final Map<Integer, Long> LAST_MOVED = new HashMap<>();
    private static final Set<Integer> PUSHED = new HashSet<>();
    private static long ticks;

    public static void set(int id, long start, int dur, UUID owner) {
        if (dur <= 0) {
            INFECTED.remove(id);
            BODIES.remove(id);
        } else {
            INFECTED.put(id, new Infection(start, dur, owner));
        }
    }

    /** [finished dinosaurs, still turning] of this player's within view range. */
    public static int[] herdCount(UUID owner) {
        int done = 0, turning = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return new int[]{0, 0};
        for (Map.Entry<Integer, Infection> en : INFECTED.entrySet()) {
            if (!en.getValue().owner().equals(owner)) continue;
            Entity e = mc.level.getEntity(en.getKey());
            if (e == null || !e.isAlive()) continue;
            if (progress(e, 0.0F) >= 1.0F) done++;
            else turning++;
        }
        return new int[]{done, turning};
    }

    /** 0..1 how far it has turned (1 = a dinosaur), or -1 if it isn't infected. */
    private static float progress(Entity e, float partial) {
        Infection in = INFECTED.get(e.getId());
        if (in == null || e.level() == null) return -1.0F;
        return Mth.clamp((e.level().getGameTime() - in.start() + partial) / in.dur(), 0.0F, 1.0F);
    }

    private static float smooth(float x) {
        x = Mth.clamp(x, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            INFECTED.clear();
            BODIES.clear();
            WAS_SWINGING.clear();
            LAST_MOVED.clear();
            return;
        }
        ticks++;
        boolean sight = mc.player != null && RavagerForm.morph(mc.player, 0.0F) >= 1.0F;
        if (sight) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (!(e instanceof LivingEntity)) continue;
                double dx = e.getX() - e.xo, dy = e.getY() - e.yo, dz = e.getZ() - e.zo;
                if (dx * dx + dy * dy + dz * dz > 1.0E-4D || !LAST_MOVED.containsKey(e.getId())) LAST_MOVED.put(e.getId(), ticks);
            }
            if (ticks % 200 == 0) LAST_MOVED.values().removeIf(t -> ticks - t > 400);
        } else if (!LAST_MOVED.isEmpty()) {
            LAST_MOVED.clear();
        }
        INFECTED.keySet().removeIf(id -> mc.level.getEntity(id) == null);
        BODIES.keySet().removeIf(id -> !INFECTED.containsKey(id));
        for (Map.Entry<Integer, Infection> en : INFECTED.entrySet()) {
            Entity e = mc.level.getEntity(en.getKey());
            if (!(e instanceof LivingEntity le)) continue;
            float p = progress(e, 0.0F);
            if (p < 1.0F && le.getRandom().nextFloat() < 0.3F) { // shedding
                mc.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MUD.defaultBlockState()),
                        e.getX() + (le.getRandom().nextDouble() - 0.5D) * e.getBbWidth(), e.getY() + le.getRandom().nextDouble() * e.getBbHeight(),
                        e.getZ() + (le.getRandom().nextDouble() - 0.5D) * e.getBbWidth(), 0.0D, 0.0D, 0.0D);
            }
            Ravager body = BODIES.get(e.getId());
            if (body == null) continue;
            body.walkAnimation.update(le.walkAnimation.speed(), 1.0F);
            RavagerAccessor jaw = (RavagerAccessor) body;
            boolean was = WAS_SWINGING.getOrDefault(e.getId(), false);
            if (le.swinging && !was) jaw.mobstands$setAttackTick(10); // it bites
            else if (jaw.mobstands$getAttackTick() > 0) jaw.mobstands$setAttackTick(jaw.mobstands$getAttackTick() - 1);
            WAS_SWINGING.put(e.getId(), le.swinging);
        }
        WAS_SWINGING.keySet().removeIf(id -> !INFECTED.containsKey(id));
    }

    /** A transformed user doesn't see what stands still (except their own herd and themselves). */
    private static boolean unseen(LivingEntity e) {
        Minecraft mc = Minecraft.getInstance();
        if (LAST_MOVED.isEmpty() || mc.player == null || e == mc.player) return false;
        Infection in = INFECTED.get(e.getId());
        if (in != null && in.owner().equals(mc.player.getUUID())) return false;
        Long t = LAST_MOVED.get(e.getId());
        return t != null && ticks - t > STILL_TICKS;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderPre(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity e = event.getEntity();
        if (unseen(e)) {
            event.setCanceled(true);
            return;
        }
        if (e instanceof Player) return;
        float partial = event.getPartialTick();
        float p = progress(e, partial);
        if (p < 0.0F) return;
        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = event.getPoseStack();
        float yaw = Mth.rotLerp(partial, e.yBodyRotO, e.yBodyRot);
        float age = e.tickCount + partial;
        float j = p >= 1.0F ? 0.0F : Mth.sin(p * Mth.PI);      // shaking: builds and settles
        float em = smooth((p - 0.4F) / 0.6F);                   // the dinosaur pushing out
        float h = 1.0F - smooth((p - 0.6F) / 0.35F);            // what's left of the creature

        if (em > 0.01F && !e.isInvisible()) {
            Ravager r = BODIES.computeIfAbsent(e.getId(), k -> {
                Ravager x = EntityType.RAVAGER.create(mc.level);
                if (x != null) {
                    x.setSilent(true);
                    x.setNoGravity(true);
                }
                return x;
            });
            if (r != null) {
                copyMotion(r, e);
                float size = e.getBbHeight() / RAVAGER_HEIGHT; // as tall as it was
                ps.pushPose();
                ps.mulPose(Axis.YP.rotationDegrees(-yaw));
                ps.scale(size * Mth.lerp(em, 0.3F, 1.0F), size * Mth.lerp(em, 0.35F, 1.0F), size * Mth.lerp(smooth(em * 1.3F), 0.25F, 1.0F));
                ps.mulPose(Axis.YP.rotationDegrees(yaw));
                @SuppressWarnings("unchecked")
                EntityRenderer<Entity> renderer = (EntityRenderer<Entity>) mc.getEntityRenderDispatcher().getRenderer(r);
                renderer.render(r, Mth.rotLerp(partial, e.yRotO, e.getYRot()), partial, ps, event.getMultiBufferSource(), event.getPackedLight());
                ps.popPose();
            }
        }
        if (h <= 0.01F) {
            event.setCanceled(true); // only the dinosaur now
            return;
        }
        if (j <= 0.0F && h >= 1.0F) return;
        ps.pushPose();
        ps.translate(Mth.sin(age * 5.3F) * 0.03F * j, 0.0F, Mth.sin(age * 4.1F + 1.0F) * 0.03F * j);
        float thin = Mth.lerp(1.0F - h, 1.0F, 0.6F);
        ps.mulPose(Axis.YP.rotationDegrees(-yaw));
        ps.scale(thin, h, 1.0F);
        ps.mulPose(Axis.YP.rotationDegrees(yaw));
        PUSHED.add(e.getId());
    }

    @SubscribeEvent
    public static void onRenderPost(RenderLivingEvent.Post<?, ?> event) {
        if (PUSHED.remove(event.getEntity().getId())) event.getPoseStack().popPose();
    }

    private static void copyMotion(Ravager r, LivingEntity e) {
        r.setPos(e.getX(), e.getY(), e.getZ());
        r.xo = e.xo;
        r.yo = e.yo;
        r.zo = e.zo;
        r.setYRot(e.getYRot());
        r.yRotO = e.yRotO;
        r.setXRot(e.getXRot());
        r.xRotO = e.xRotO;
        r.yBodyRot = e.yBodyRot;
        r.yBodyRotO = e.yBodyRotO;
        r.yHeadRot = e.yHeadRot;
        r.yHeadRotO = e.yHeadRotO;
        r.tickCount = e.tickCount;
        r.hurtTime = e.hurtTime;
        r.deathTime = e.deathTime;
        r.setOnGround(e.onGround());
    }
}
