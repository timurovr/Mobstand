package com.mobstands.client;

import com.mobstands.network.GravitySwitchPayload;
import com.mobstands.stand.GravityFlip;
import com.mobstands.stand.StandType;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * C-Moon on your own game: creatures whose gravity is turned are drawn standing on their wall or ceiling, and when
 * it's you, you walk on it - your movement, your mouse look and your camera all turn with your new "down".
 */
public final class GravityClient {
    private GravityClient() {}

    /** Which way "down" is for each turned creature (entity id -> direction). */
    private static final Map<Integer, Direction> FLIPS = new HashMap<>();
    /** Last way each turned mob was facing on its surface (its own yaw, in its turned world). */
    private static final Map<Integer, Float> FACING = new HashMap<>();

    // ------------------------------------------------------------------ you
    private static Direction localG;
    /** True while your look/camera are run here (until the turn back to normal has finished easing). */
    private static boolean overriding;
    /** Your turned world, eased: the camera and controls swing round smoothly when your gravity changes. */
    private static final Quaternionf qs = new Quaternionf();
    /** Your look inside your own turned world. */
    private static float ly, lp;
    private static Vec3 vel = Vec3.ZERO;
    private static boolean physics;
    private static float inFwd, inStrafe;
    private static boolean inJump, inSneak;
    private static int switchCooldown;
    private static boolean sprinting;
    /** Were you standing on your floor last tick? (Walking off its edge wraps you round the corner.) */
    private static boolean wasLanded;

    public static void onPayload(int entityId, int dir) {
        Minecraft mc = Minecraft.getInstance();
        Direction d = dir < 0 ? null : Direction.from3DDataValue(dir);
        if (d == null) {
            FLIPS.remove(entityId);
            FACING.remove(entityId);
            com.mobstands.stand.GravityBox.CLIENT.remove(entityId);
        } else {
            FLIPS.put(entityId, d);
            com.mobstands.stand.GravityBox.CLIENT.put(entityId, d);
        }
        if (mc.level != null && mc.level.getEntity(entityId) != null) com.mobstands.stand.GravityBox.refresh(mc.level.getEntity(entityId));
        if (mc.player != null && entityId == mc.player.getId()) setLocal(d);
    }

    private static void setLocal(Direction g) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (g != null && !overriding) {
            overriding = true;
            qs.identity();
            ly = p.getYRot();
            lp = p.getXRot();
        }
        if (g != null && localG == null) vel = p.getDeltaMovement();
        localG = g;
    }

    public static boolean overriding() {
        return overriding;
    }

    private static Quaternionf look(float yaw, float pitch) {
        return new Quaternionf().rotationYXZ((float) Math.PI - yaw * Mth.DEG_TO_RAD, -pitch * Mth.DEG_TO_RAD, 0.0F);
    }

    private static Vec3 v(Vector3f f) {
        return new Vec3(f.x, f.y, f.z);
    }

    /** Your real look (what you aim and mine with) follows your look inside the turned world. */
    private static void apply(LocalPlayer p) {
        Quaternionf q = new Quaternionf(qs).mul(look(ly, lp));
        Vector3f f = q.transform(new Vector3f(0.0F, 0.0F, -1.0F));
        float yaw = (float) Math.toDegrees(Math.atan2(-f.x, f.z));
        float pitch = (float) Math.toDegrees(Math.asin(Mth.clamp(-f.y, -1.0F, 1.0F)));
        p.setYRot(yaw);
        p.yRotO = yaw;
        p.setXRot(pitch);
        p.xRotO = pitch;
        p.setYHeadRot(yaw);
        p.yHeadRotO = yaw;
    }

    /** Called by the mouse (Entity.turn): turning happens around your own "up". */
    public static boolean onTurn(double dYaw, double dPitch) {
        Minecraft mc = Minecraft.getInstance();
        if (!overriding || mc.player == null) return false;
        ly += (float) dYaw * 0.15F;
        lp = Mth.clamp(lp + (float) dPitch * 0.15F, -90.0F, 90.0F);
        apply(mc.player);
        return true;
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (!overriding) return;
        if (mc.player == null) {
            overriding = false;
            return;
        }
        Quaternionf target = GravityFlip.frame(localG);
        qs.slerp(target, 0.18F).normalize();
        if (localG == null && 2.0D * Math.acos(Math.min(1.0D, Math.abs(qs.w))) < 0.01D) {
            qs.identity();
            apply(mc.player);
            overriding = false;
            return;
        }
        apply(mc.player);
    }

    /** The camera rolls so your new floor is down on screen. */
    @SubscribeEvent
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (!overriding || mc.player == null || event.getCamera().getEntity() != mc.player
                || mc.options.getCameraType() == CameraType.THIRD_PERSON_FRONT) return;
        Quaternionf q = new Quaternionf(qs).mul(look(ly, lp));
        Vector3f f = q.transform(new Vector3f(0.0F, 0.0F, -1.0F));
        float yaw = (float) Math.toDegrees(Math.atan2(-f.x, f.z));
        float pitch = (float) Math.toDegrees(Math.asin(Mth.clamp(-f.y, -1.0F, 1.0F)));
        Quaternionf q0 = look(yaw, pitch);
        Vector3f up0 = q0.transform(new Vector3f(0.0F, 1.0F, 0.0F));
        Vector3f right0 = q0.transform(new Vector3f(1.0F, 0.0F, 0.0F));
        Vector3f up = q.transform(new Vector3f(0.0F, 1.0F, 0.0F));
        float roll = (float) Math.toDegrees(Math.atan2(up.dot(right0), up.dot(up0)));
        event.setYaw(yaw);
        event.setPitch(pitch);
        event.setRoll(roll);
    }

    /** Where your eyes really are when you stand on a wall or ceiling (null = the normal place). */
    public static Vec3 eye(float partial) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!overriding || p == null) return null;
        double w = p.getBbWidth(), h = p.getBbHeight();
        Vec3 c = p.getPosition(partial).add(0.0D, h * 0.5D, 0.0D);
        Vec3 up = v(qs.transform(new Vector3f(0.0F, 1.0F, 0.0F)));
        double horiz = 1.0D - Math.abs(up.y);
        Vec3 shift = up.scale(-(w * 0.5D - h * 0.5D) * horiz);
        return c.add(shift).add(up.scale(p.getEyeHeight() - h * 0.5D));
    }

    /** Camera mixin: how far to move the camera from where the game put it. */
    public static Vec3 cameraShift(Entity entity, float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (!overriding || entity != mc.player) return null;
        Vec3 ours = eye(partial);
        if (ours == null) return null;
        Vec3 vanilla = mc.player.getPosition(partial).add(0.0D, mc.player.getEyeHeight(), 0.0D);
        return ours.subtract(vanilla);
    }

    // ------------------------------------------------------------------ walking on it

    @SubscribeEvent
    public static void onInput(MovementInputUpdateEvent event) {
        if (localG == null || event.getEntity() != Minecraft.getInstance().player) return;
        var in = event.getInput();
        inFwd = in.forwardImpulse;
        inStrafe = in.leftImpulse;
        inJump = in.jumping;
        inSneak = in.shiftKeyDown;
        in.forwardImpulse = 0.0F;
        in.leftImpulse = 0.0F;
        in.jumping = false;
        // No crouch pose while you're on a wall or ceiling: crouching shrinks your hitbox away from your floor, and
        // standing back up grew it into the block (stuck, pushed out, hurt). Sneaking still slows you and stops you
        // at edges - that's handled here, from inSneak.
        in.shiftKeyDown = false;
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!(event.getEntity() instanceof LocalPlayer p) || p != mc.player) return;
        if (switchCooldown > 0) switchCooldown--;
        if (localG == null || p.isSpectator() || p.isPassenger()) {
            if (physics) {
                physics = false;
                p.setNoGravity(false);
                p.setDeltaMovement(vel);
            }
            return;
        }
        physics = true;
        p.setNoGravity(true);
        Vec3 g = GravityFlip.vec(localG);
        vel = vel.add(p.getDeltaMovement());
        p.setDeltaMovement(Vec3.ZERO);
        double vg = vel.dot(g);
        Vec3 vp = vel.subtract(g.scale(vg));
        boolean landed = !p.level().noCollision(p, p.getBoundingBox().move(g.scale(0.04D)));
        // (Corner wrapping is switched off: walking off an edge just falls off it. Hold Shift to stop at edges.
        // wrap() below is kept for later.)

        // Your walking directions, flat against your floor.
        Quaternionf qm = new Quaternionf(qs).mul(look(ly, 0.0F));
        Vec3 fwd = flat(v(qm.transform(new Vector3f(0.0F, 0.0F, -1.0F))), g);
        Vec3 left = flat(v(qm.transform(new Vector3f(-1.0F, 0.0F, 0.0F))), g);
        Vec3 wish = fwd.scale(inFwd).add(left.scale(inStrafe));
        if (wish.lengthSqr() > 1.0D) wish = wish.normalize();
        // Sprinting sticks like vanilla: start it with the sprint key, and it lasts until you stop going forward
        // (letting go of the key, or jumping, doesn't drop it).
        if (inFwd <= 0.0F || inSneak || p.getFoodData().getFoodLevel() <= 6) sprinting = false;
        else if (mc.options.keySprint.isDown()) sprinting = true;
        boolean sprint = sprinting;
        if (landed) {
            double a = 0.18D * (sprint ? 1.3D : 1.0D) * (inSneak ? 0.3D : 1.0D);
            vp = vp.add(wish.scale(a)).scale(0.546D);
            vg = inJump ? -0.42D : 0.0D;
            // Sprint-jumping: the same forward boost vanilla gives you, so you can clear gaps.
            if (inJump && sprint) vp = vp.add(fwd.scale(0.2D));
        } else {
            vp = vp.add(wish.scale(sprint ? 0.026D : 0.02D)).scale(0.91D);
            vg = (vg + 0.08D) * 0.98D;
        }
        if (localG == Direction.UP && p.getY() > p.level().getMaxBuildHeight() + 32) vg = Math.min(vg, 0.0D);
        // Sneaking keeps you from walking off the edge of your wall or ceiling, just like on the ground.
        if (landed && inSneak && vg >= 0.0D) vp = backOffEdge(p, vp, g);
        vel = vp.add(g.scale(vg));

        Vec3 before = p.position();
        Vec3 wanted = vel;
        float walkBefore = p.walkDist;
        p.move(MoverType.SELF, vel);
        Vec3 moved = p.position().subtract(before);
        vel = new Vec3(Math.abs(moved.x - vel.x) > 1.0E-4D ? moved.x : vel.x,
                Math.abs(moved.y - vel.y) > 1.0E-4D ? moved.y : vel.y,
                Math.abs(moved.z - vel.z) > 1.0E-4D ? moved.z : vel.z);
        p.fallDistance = 0.0F;
        p.setSprinting(sprint);
        // Walk animation along your own floor (vanilla only counts flat ground, so on walls you'd just slide).
        Vec3 along = moved.subtract(g.scale(moved.dot(g)));
        p.walkAnimation.update((float) Math.min(1.0D, along.length() * 4.0D), 0.4F);
        // Camera bobbing, like walking on the ground: count the distance along your own floor (vanilla only counts
        // flat ground, so on a wall you'd glide), and bob while you're standing on something.
        boolean onIt = !p.level().noCollision(p, p.getBoundingBox().move(g.scale(0.04D)));
        p.walkDist = walkBefore + (float) along.length() * 0.6F;
        p.bob = p.oBob + ((onIt ? (float) Math.min(0.1D, along.length()) : 0.0F) - p.oBob) * 0.4F;

        // (No automatic wall-switching: your gravity only ever changes when you choose it - Z or the C wheel.)
    }

    /** Other turned creatures (players, punched mobs): vanilla only counts flat-ground movement, so walk them too. */
    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused() || FLIPS.isEmpty()) return;
        for (Map.Entry<Integer, Direction> en : FLIPS.entrySet()) {
            if (mc.player != null && en.getKey() == mc.player.getId()) continue; // you're walked in onPlayerTick
            if (!(mc.level.getEntity(en.getKey()) instanceof LivingEntity e)) continue;
            Vec3 g = GravityFlip.vec(en.getValue());
            Vec3 m = e.position().subtract(e.xo, e.yo, e.zo);
            Vec3 along = m.subtract(g.scale(m.dot(g)));
            e.walkAnimation.update((float) Math.min(1.0D, along.length() * 4.0D), 0.4F);
        }
    }

    /**
     * Round the corner: you've just walked past the edge of the block you were standing on. Your new floor is that
     * block's next face (the one you walked past), and the world turns with you, like walking round a cube.
     */
    private static boolean wrap(LocalPlayer p, Direction oldG, Vec3 move) {
        Vec3 g = GravityFlip.vec(oldG);
        Vec3 mdir = move.normalize();
        Direction newG = Direction.getNearest(-mdir.x, -mdir.y, -mdir.z);
        if (newG.getAxis() == oldG.getAxis()) return false;
        Vec3 nG = GravityFlip.vec(newG);
        Vec3 mAxis = nG.scale(-1.0D); // the way you were walking
        net.minecraft.world.phys.AABB b = p.getBoundingBox();
        double halfG = size(b, oldG.getAxis()) * 0.5D, halfM = size(b, newG.getAxis()) * 0.5D;
        Vec3 feet = b.getCenter().add(g.scale(halfG));
        double fm = feet.dot(mAxis);
        double edge = Math.floor(fm - halfM + 1.0E-3D); // the block edge you just stepped past
        double w = p.getBbWidth(), h = p.getBbHeight();
        // Your feet go onto the next face, half a body in from the edge.
        Vec3 newFeet = feet.add(mAxis.scale(edge - fm)).add(g.scale(w * 0.5D + 0.05D));
        Vec3 offset = newG == Direction.DOWN ? Vec3.ZERO
                : newG == Direction.UP ? new Vec3(0.0D, h, 0.0D)
                : nG.scale(w * 0.5D).add(0.0D, h * 0.5D, 0.0D);
        Vec3 pos = newFeet.subtract(offset).subtract(nG.scale(0.01D));

        // Try it: you must fit there, with the new floor right under you. Otherwise you just fall off.
        Vec3 oldPos = p.position();
        com.mobstands.stand.GravityBox.CLIENT.put(p.getId(), newG);
        p.setPos(pos.x, pos.y, pos.z);
        boolean fits = p.level().noCollision(p) && !p.level().noCollision(p, p.getBoundingBox().move(nG.scale(0.3D)));
        if (!fits) {
            com.mobstands.stand.GravityBox.CLIENT.put(p.getId(), oldG);
            p.setPos(oldPos.x, oldPos.y, oldPos.z);
            return false;
        }
        p.xo = p.xOld = pos.x;
        p.yo = p.yOld = pos.y;
        p.zo = p.zOld = pos.z;

        // The view turns over the edge with you: the same look, rotated round the corner (eased by onFrame).
        Quaternionf turn = new Quaternionf().rotationTo(g.toVector3f(), nG.toVector3f());
        Vector3f d = new Quaternionf(qs).mul(look(ly, lp)).transform(new Vector3f(0.0F, 0.0F, -1.0F));
        Quaternionf start = new Quaternionf(turn).conjugate().mul(GravityFlip.frame(newG));
        Vector3f dl = new Quaternionf(start).conjugate().transform(new Vector3f(d));
        ly = (float) Math.toDegrees(Math.atan2(-dl.x, dl.z));
        lp = (float) Math.toDegrees(Math.asin(Mth.clamp(-dl.y, -1.0F, 1.0F)));
        qs.set(start);
        apply(p);

        FLIPS.put(p.getId(), newG);
        localG = newG;
        vel = g.scale(move.length() * 0.6D); // keep walking, now across the new face
        switchCooldown = 10;
        PacketDistributor.sendToServer(new GravitySwitchPayload(newG.get3DDataValue()));
        return true;
    }

    private static double size(net.minecraft.world.phys.AABB b, Direction.Axis a) {
        return a == Direction.Axis.X ? b.getXsize() : a == Direction.Axis.Y ? b.getYsize() : b.getZsize();
    }

    /** Vanilla's sneak edge-check, for any floor: shrink each step until there's still something under you. */
    private static Vec3 backOffEdge(LocalPlayer p, Vec3 move, Vec3 g) {
        // The two directions along your floor.
        Vec3 a, b;
        if (Math.abs(g.y) > 0.5D) { a = new Vec3(1, 0, 0); b = new Vec3(0, 0, 1); }
        else if (Math.abs(g.x) > 0.5D) { a = new Vec3(0, 1, 0); b = new Vec3(0, 0, 1); }
        else { a = new Vec3(1, 0, 0); b = new Vec3(0, 1, 0); }
        double da = move.dot(a), db = move.dot(b);
        final double step = 0.05D;
        Vec3 down = g.scale(p.maxUpStep() + 0.01D);
        while (da != 0.0D && !supported(p, a.scale(da), down)) da = Math.abs(da) < step ? 0.0D : da - Math.signum(da) * step;
        while (db != 0.0D && !supported(p, b.scale(db), down)) db = Math.abs(db) < step ? 0.0D : db - Math.signum(db) * step;
        while (da != 0.0D && db != 0.0D && !supported(p, a.scale(da).add(b.scale(db)), down)) {
            da = Math.abs(da) < step ? 0.0D : da - Math.signum(da) * step;
            db = Math.abs(db) < step ? 0.0D : db - Math.signum(db) * step;
        }
        Vec3 rest = move.subtract(a.scale(move.dot(a))).subtract(b.scale(move.dot(b)));
        return a.scale(da).add(b.scale(db)).add(rest);
    }

    private static boolean supported(LocalPlayer p, Vec3 offset, Vec3 down) {
        return !p.level().noCollision(p, p.getBoundingBox().move(offset).move(down));
    }

    private static Vec3 flat(Vec3 dir, Vec3 g) {
        Vec3 f = dir.subtract(g.scale(dir.dot(g)));
        return f.lengthSqr() < 1.0E-6D ? Vec3.ZERO : f.normalize();
    }

    // ------------------------------------------------------------------ drawing turned creatures

    private record Saved(LivingEntity e, float body, float bodyO, float head, float headO, float x, float xO) {}
    private static final Deque<Saved> PUSHED = new ArrayDeque<>();

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderPre(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity e = event.getEntity();
        Direction g = FLIPS.get(e.getId());
        if (g == null) return;
        Minecraft mc = Minecraft.getInstance();
        float partial = event.getPartialTick();
        Quaternionf r = e == mc.player && overriding ? new Quaternionf(qs) : GravityFlip.frame(g);
        Quaternionf inv = new Quaternionf(r).conjugate();

        float yaw, pitch = 0.0F;
        if (e instanceof Player) {
            Vector3f f = inv.transform(e.getViewVector(partial).toVector3f());
            yaw = (float) Math.toDegrees(Math.atan2(-f.x, f.z));
            pitch = (float) Math.toDegrees(Math.asin(Mth.clamp(-f.y, -1.0F, 1.0F)));
        } else {
            Vec3 m = e.position().subtract(e.xo, e.yo, e.zo);
            Vec3 gv = GravityFlip.vec(g);
            m = m.subtract(gv.scale(m.dot(gv)));
            if (m.lengthSqr() > 1.0E-5D) {
                Vector3f f = inv.transform(m.toVector3f());
                float target = (float) Math.toDegrees(Math.atan2(-f.x, f.z));
                Float last = FACING.get(e.getId());
                FACING.put(e.getId(), last == null ? target : Mth.rotLerp(0.25F, last, target));
            }
            yaw = FACING.getOrDefault(e.getId(), 0.0F);
        }

        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        double w = e.getBbWidth(), h = e.getBbHeight();
        Vec3 down = v(r.transform(new Vector3f(0.0F, -1.0F, 0.0F)));
        double horiz = 1.0D - Math.abs(down.y);
        ps.translate(down.x * (w * 0.5D - h * 0.5D) * horiz, down.y * (w * 0.5D - h * 0.5D) * horiz + h * 0.5D,
                down.z * (w * 0.5D - h * 0.5D) * horiz);
        ps.mulPose(r);
        ps.translate(0.0D, -h * 0.5D, 0.0D);

        PUSHED.push(new Saved(e, e.yBodyRot, e.yBodyRotO, e.yHeadRot, e.yHeadRotO, e.getXRot(), e.xRotO));
        e.yBodyRot = yaw;
        e.yBodyRotO = yaw;
        e.yHeadRot = yaw;
        e.yHeadRotO = yaw;
        e.setXRot(pitch);
        e.xRotO = pitch;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderPost(RenderLivingEvent.Post<?, ?> event) {
        Saved s = PUSHED.peek();
        if (s == null || s.e != event.getEntity()) return;
        PUSHED.pop();
        LivingEntity e = s.e;
        e.yBodyRot = s.body;
        e.yBodyRotO = s.bodyO;
        e.yHeadRot = s.head;
        e.yHeadRotO = s.headO;
        e.setXRot(s.x);
        e.xRotO = s.xO;
        event.getPoseStack().popPose();
    }

    /**
     * Something left your view (out of range, unloaded, dead): forget its gravity. Entity numbers get reused - a
     * stale entry would make some random new mob walk on the wall. The server tells you again if you see it later.
     */
    @SubscribeEvent
    public static void onLeave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) return;
        Minecraft mc = Minecraft.getInstance();
        int id = event.getEntity().getId();
        if (mc.player != null && id == mc.player.getId()) return; // you: kept across respawns, cleared on logout
        FLIPS.remove(id);
        FACING.remove(id);
        com.mobstands.stand.GravityBox.CLIENT.remove(id);
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        FLIPS.clear();
        com.mobstands.stand.GravityBox.CLIENT.clear();
        FACING.clear();
        PUSHED.clear();
        localG = null;
        overriding = false;
        physics = false;
        qs.identity();
    }
}
