package com.mobstands.client;

import com.mobstands.entity.StandEntity;
import com.mobstands.mixin.RavagerAccessor;
import com.mobstands.stand.StandType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Scary Monsters on screen: its user IS the dinosaur. The change is not a swap but a body turning into another one:
 * <ol>
 *   <li><b>Seizure</b> - the human convulses: head thrown back, hands clawed, spine bending forward onto all fours;</li>
 *   <li><b>Emergence</b> - the Ravager pushes out of that hunched body, long and narrow at first (stretched out of the
 *       human's own silhouette), filling out to full size while the human is drawn in and absorbed;</li>
 *   <li><b>Roar</b> - the jaw opens wide as the change completes.</li>
 * </ol>
 * Dismissing plays it backwards. Once turned, only the Ravager is drawn: it walks with the player's legs and bites.
 */
public final class RavagerForm {
    private RavagerForm() {}

    /** How big the user's Ravager body is next to a real Ravager. */
    public static final float SCALE = 0.85F;

    /** Player entity id -> their Scary Monsters Stand (refreshed every client tick). */
    private static final Map<Integer, StandEntity> FORMS = new HashMap<>();
    /** Player entity id -> the client-only Ravager body drawn for them. */
    private static final Map<Integer, Ravager> BODIES = new HashMap<>();
    /** Player entity id -> start time of the last bite we animated. */
    private static final Map<Integer, Integer> LAST_BITE = new HashMap<>();
    /** Player entity id -> morph last tick (to catch the moment it crosses into the roar). */
    private static final Map<Integer, Float> LAST_MORPH = new HashMap<>();
    /** Players whose human body we transformed this frame: the pose is popped again in Post. */
    private static final Set<Integer> PUSHED = new HashSet<>();

    // ------------------------------------------------------------------ the stages (all 0..1 from the morph 0..1)

    private static float smooth(float x) {
        x = Mth.clamp(x, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    /** Stage 1: how far the human has bent over into the seizure. */
    private static float hunch(float f) { return smooth(f / 0.4F); }

    /** Stage 2: how far out of the body the Ravager has grown. */
    private static float emerge(float f) { return smooth((f - 0.25F) / 0.75F); }

    /** How much of the human is left (absorbed into the Ravager as it fills out). */
    private static float human(float f) { return 1.0F - smooth((f - 0.5F) / 0.4F); }

    /** Convulsion strength: builds, peaks mid-change, then settles. */
    private static float spasm(float f) { return f >= 1.0F ? 0.0F : Mth.sin(Mth.clamp(f / 0.9F, 0.0F, 1.0F) * Mth.PI); }

    /** 0 = human, 1 = fully a Ravager. */
    public static float morph(Player p, float partial) {
        StandEntity s = FORMS.get(p.getId());
        return s == null || s.isRemoved() ? 0.0F : s.morph(partial);
    }

    // ------------------------------------------------------------------ ticking: jaw, roar, shed particles

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        FORMS.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            BODIES.clear();
            LAST_BITE.clear();
            LAST_MORPH.clear();
            return;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof StandEntity s) || s.getStandType() != StandType.SCARY_MONSTERS || s.isHidden()) continue;
            Player o = s.getOwnerPlayer();
            if (o == null) continue;
            FORMS.put(o.getId(), s);
            Ravager body = body(o, mc);
            if (body == null) continue;
            // Legs: once per tick (not per frame), so they keep pace with the player's own stride at any frame rate.
            body.walkAnimation.update(o.walkAnimation.speed(), 1.0F);

            RavagerAccessor jaw = (RavagerAccessor) body;
            float f = s.morph(0.0F);
            float before = LAST_MORPH.getOrDefault(o.getId(), 0.0F);
            LAST_MORPH.put(o.getId(), f);
            if (before < 0.8F && f >= 0.8F && !s.isDismissing()) jaw.mobstands$setRoarTick(20); // the change ends in a roar
            else if (jaw.mobstands$getRoarTick() > 0) jaw.mobstands$setRoarTick(jaw.mobstands$getRoarTick() - 1);
            // A fresh basic attack (the bite) starts the Ravager's own jaw-snap.
            if (s.animSlot() == 0 && !Integer.valueOf(s.animStart()).equals(LAST_BITE.get(o.getId()))) {
                LAST_BITE.put(o.getId(), s.animStart());
                jaw.mobstands$setAttackTick(10);
            } else if (jaw.mobstands$getAttackTick() > 0) {
                jaw.mobstands$setAttackTick(jaw.mobstands$getAttackTick() - 1); // (the body never ticks on its own)
            }

            // Mid-change: bits of skin and bone flake off the body as it stretches.
            if (f > 0.05F && f < 0.95F && o.getRandom().nextFloat() < spasm(f)) {
                double w = 0.3D + emerge(f) * 0.6D;
                mc.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MUD.defaultBlockState()),
                        o.getX() + (o.getRandom().nextDouble() - 0.5D) * w * 2.0D, o.getY() + 0.4D + o.getRandom().nextDouble() * 1.2D,
                        o.getZ() + (o.getRandom().nextDouble() - 0.5D) * w * 2.0D, 0.0D, 0.0D, 0.0D);
            }
        }
        BODIES.keySet().removeIf(id -> !FORMS.containsKey(id));
        LAST_BITE.keySet().removeIf(id -> !FORMS.containsKey(id));
        LAST_MORPH.keySet().removeIf(id -> !FORMS.containsKey(id));
        lockCamera(mc);
    }

    /** Did we take you out of first person (so we put you back when you turn human again)? */
    private static boolean tookFirstPerson;

    /** Once you've turned, the camera stays in third person (F5 still flips between behind and in front). */
    private static void lockCamera(Minecraft mc) {
        if (mc.player == null) return;
        float f = morph(mc.player, 0.0F);
        if (f >= 0.999F && mc.options.getCameraType().isFirstPerson()) {
            mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
            tookFirstPerson = true;
        } else if (f <= 0.0F && tookFirstPerson) {
            mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
            tookFirstPerson = false;
        }
    }

    private static Ravager body(Player p, Minecraft mc) {
        Ravager r = BODIES.get(p.getId());
        if (r == null || r.level() != mc.level) {
            r = EntityType.RAVAGER.create(mc.level);
            if (r == null) return null;
            r.setSilent(true);
            r.setNoGravity(true);
            BODIES.put(p.getId(), r);
        }
        return r;
    }

    // ------------------------------------------------------------------ stage 1: the human's seizure (model pose)

    /** Called from PlayerPoses after vanilla posed the player. True = we posed it (mid-change). */
    public static boolean pose(HumanoidModel<?> m, Player p, float age) {
        float f = morph(p, Mth.frac(age));
        if (f <= 0.0F || f >= 1.0F) return false;
        float a = hunch(f), j = spasm(f);
        // Fast, irregular twitches (two out-of-step waves so it never looks like a loop).
        float tw1 = (Mth.sin(age * 2.3F) + Mth.sin(age * 3.7F + 1.0F)) * 0.5F * j;
        float tw2 = (Mth.sin(age * 2.9F + 2.0F) + Mth.sin(age * 4.3F)) * 0.5F * j;
        // Head thrown back, jaw to the sky - the face is where it starts.
        m.head.xRot = Mth.lerp(a, m.head.xRot, -0.75F) + tw1 * 0.25F;
        m.head.yRot = Mth.lerp(a, m.head.yRot, 0.0F) + tw2 * 0.2F;
        m.head.zRot = tw1 * 0.15F;
        // Arms drop forward to become forelegs, fingers clawed out to the sides.
        m.rightArm.xRot = Mth.lerp(a, m.rightArm.xRot, -1.35F) + tw2 * 0.3F;
        m.leftArm.xRot = Mth.lerp(a, m.leftArm.xRot, -1.35F) - tw1 * 0.3F;
        m.rightArm.yRot = Mth.lerp(a, m.rightArm.yRot, 0.0F);
        m.leftArm.yRot = Mth.lerp(a, m.leftArm.yRot, 0.0F);
        m.rightArm.zRot = Mth.lerp(a, m.rightArm.zRot, 0.35F) + tw1 * 0.1F;
        m.leftArm.zRot = Mth.lerp(a, m.leftArm.zRot, -0.35F) - tw2 * 0.1F;
        // Legs buckle.
        m.rightLeg.xRot = Mth.lerp(a, m.rightLeg.xRot, -0.35F);
        m.leftLeg.xRot = Mth.lerp(a, m.leftLeg.xRot, 0.25F);
        m.rightLeg.zRot = Mth.lerp(a, m.rightLeg.zRot, 0.12F);
        m.leftLeg.zRot = Mth.lerp(a, m.leftLeg.zRot, -0.12F);
        m.hat.copyFrom(m.head);
        return true;
    }

    // ------------------------------------------------------------------ stage 2: drawing both bodies

    @SubscribeEvent
    public static void onPlayerPre(RenderPlayerEvent.Pre event) {
        Player p = event.getEntity();
        float partial = event.getPartialTick();
        float f = morph(p, partial);
        if (f <= 0.0F) return;
        Minecraft mc = Minecraft.getInstance();
        Ravager r = body(p, mc);
        if (r == null) return;
        PoseStack ps = event.getPoseStack();
        float yaw = Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot);
        float age = p.tickCount + partial;
        float j = spasm(f);
        float e = emerge(f);

        // The Ravager grows out of the human: at first narrow and short (inside the human's own outline), then it
        // stretches out lengthwise and fills out. Scaled along the body's own axes (forward / up / sideways).
        if (!p.isInvisible() && e > 0.01F) {
            copyMotion(r, p);
            ps.pushPose();
            ps.mulPose(Axis.YP.rotationDegrees(-yaw));
            float sx = SCALE * Mth.lerp(e, 0.3F, 1.0F) * (1.0F + Mth.sin(age * 3.1F) * 0.04F * j); // its flanks heave
            float sy = SCALE * Mth.lerp(e, 0.35F, 1.0F);
            float sz = SCALE * Mth.lerp(smooth(e * 1.3F), 0.25F, 1.0F); // it lengthens first, then fills out
            ps.scale(sx, sy, sz);
            ps.mulPose(Axis.YP.rotationDegrees(yaw));
            @SuppressWarnings("unchecked")
            EntityRenderer<Entity> renderer = (EntityRenderer<Entity>) mc.getEntityRenderDispatcher().getRenderer(r);
            renderer.render(r, Mth.rotLerp(partial, p.yRotO, p.getYRot()), partial, ps, event.getMultiBufferSource(), event.getPackedLight());
            ps.popPose();
        }

        float h = human(f);
        if (h <= 0.01F) {
            event.setCanceled(true); // fully turned: no human left
            return;
        }
        // The human: bent forward over the hips onto all fours, shaking, and drawn into the Ravager as it fills out.
        float a = hunch(f);
        ps.pushPose();
        ps.translate(Mth.sin(age * 5.3F) * 0.025F * j, 0.0F, Mth.sin(age * 4.1F + 1.0F) * 0.025F * j);
        ps.mulPose(Axis.YP.rotationDegrees(-yaw));
        ps.translate(0.0F, 0.75F, 0.0F);
        ps.mulPose(Axis.XP.rotationDegrees(55.0F * a + Mth.sin(age * 2.7F) * 4.0F * j)); // the spine bends forward
        ps.translate(0.0F, -0.75F, 0.0F);
        float thin = Mth.lerp(1.0F - h, 1.0F, 0.6F);
        ps.scale(thin, h, Mth.lerp(a, 1.0F, 1.15F)); // stretched front to back, collapsing as it's absorbed
        ps.mulPose(Axis.YP.rotationDegrees(yaw));
        PUSHED.add(p.getId());
    }

    @SubscribeEvent
    public static void onPlayerPost(RenderPlayerEvent.Post event) {
        if (PUSHED.remove(event.getEntity().getId())) event.getPoseStack().popPose();
    }

    // ------------------------------------------------------------------ first person

    /** A dinosaur has no hands: in first person an empty hand isn't drawn once you've turned. */
    @SubscribeEvent
    public static void onHand(RenderHandEvent event) {
        Player p = Minecraft.getInstance().player;
        if (p != null && event.getItemStack().isEmpty() && morph(p, event.getPartialTick()) >= 0.6F) event.setCanceled(true);
    }

    /** Your own view convulses with the body while it changes. */
    @SubscribeEvent
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        Player p = Minecraft.getInstance().player;
        if (p == null) return;
        float partial = (float) event.getPartialTick();
        float j = spasm(morph(p, partial));
        if (j <= 0.0F) return;
        float age = p.tickCount + partial;
        event.setRoll(event.getRoll() + Mth.sin(age * 3.3F) * 3.0F * j);
        event.setPitch(event.getPitch() + Mth.sin(age * 4.7F + 1.0F) * 1.5F * j);
    }

    private static void copyMotion(Ravager r, Player p) {
        r.setPos(p.getX(), p.getY(), p.getZ());
        r.xo = p.xo;
        r.yo = p.yo;
        r.zo = p.zo;
        r.setYRot(p.getYRot());
        r.yRotO = p.yRotO;
        r.setXRot(p.getXRot());
        r.xRotO = p.xRotO;
        r.yBodyRot = p.yBodyRot;
        r.yBodyRotO = p.yBodyRotO;
        r.yHeadRot = p.yHeadRot;
        r.yHeadRotO = p.yHeadRotO;
        r.tickCount = p.tickCount;
        r.hurtTime = p.hurtTime; // red flash when you're hit
        r.setOnGround(p.onGround());
    }
}
