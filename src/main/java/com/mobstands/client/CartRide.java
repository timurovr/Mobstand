package com.mobstands.client;

import com.mobstands.entity.StandEntity;
import com.mobstands.entity.StandPresence;
import com.mobstands.network.StandActionPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.MinecartModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Minecart Stand, client side: driving it (no rails needed), the rails that fade in under the cart and vanish once
 * it has passed, drawing the cart under its rider, and the rolling sound.
 */
public final class CartRide {
    private CartRide() {}

    // ------------------------------------------------------------------ driving (your own cart)

    /** Top speed (blocks per tick, ~11 m/s), how fast it picks up speed, how it rolls to a stop. */
    private static final double MAX_SPEED = 0.55D, ACCEL = 0.022D, COAST = 0.985D, BRAKE = 0.86D;
    /** Powered Rails: steady and long. Nitro: much faster but short. */
    private static final double POWERED_SPEED = 0.78D, NITRO_SPEED = 1.15D;

    /** For the FOV kick while boosting. */
    public static float fovScale() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !riding(mc.player)) return 1.0F;
        return 1.0F + (float) Math.max(0.0D, speed - MAX_SPEED) * 0.35F;
    }
    private static double speed;
    private static float heading;
    private static boolean wasRiding, shiftWasDown;

    /** Which way your own cart is facing. */
    public static float heading() {
        return heading;
    }

    public static boolean riding(Player p) {
        if (p.level() == null) return false;
        // Your own Stand dismissed: out of the cart that very tick (no waiting for the server to catch up).
        if (p.isLocalPlayer() && !com.mobstands.network.ClientStandCache.get().isSummoned()) return false;
        return StandPresence.riding(p.getUUID(), p.level().getGameTime());
    }

    /** Runs right after your keys are read: the cart takes over W/S, and your mouse steers. */
    @SubscribeEvent
    public static void onInput(MovementInputUpdateEvent event) {
        if (!(event.getEntity() instanceof LocalPlayer p)) return;
        boolean ride = riding(p);
        net.minecraft.client.player.Input in = event.getInput();
        if (!ride) {
            if (wasRiding) speed = 0.0D;
            wasRiding = false;
            return;
        }
        if (!wasRiding) {
            heading = p.getYRot();
            Vec3 v = p.getDeltaMovement();
            speed = Math.sqrt(v.x * v.x + v.z * v.z);
        }
        wasRiding = true;
        boolean up = in.up, down = in.down, shift = in.shiftKeyDown;
        // The cart drives itself: no walking, strafing, jumping or sneaking while you're in it.
        in.forwardImpulse = 0.0F;
        in.leftImpulse = 0.0F;
        in.up = in.down = in.left = in.right = false;
        in.jumping = false;
        in.shiftKeyDown = false;
        p.setSprinting(false);
        if (shift && !shiftWasDown) PacketDistributor.sendToServer(new StandActionPayload(23)); // hop out
        shiftWasDown = shift;

        int boost = StandPresence.cartBoost(p.getUUID(), p.level().getGameTime());
        if (boost == 2) {
            // Nitro: full throttle whether you hold W or not, way past normal top speed.
            speed = Math.min(NITRO_SPEED, speed + 0.09D);
            if (down) speed *= 0.95D;
        } else {
            double max = boost == 1 ? POWERED_SPEED : MAX_SPEED;
            double acc = boost == 1 ? ACCEL * 2.2D : ACCEL;
            if (speed > max) speed = Math.max(max, speed * 0.97D); // coming off a boost: ease back down
            else if (up || boost == 1) speed = Math.min(max, speed + acc * (1.0D - speed / (max * 1.15D)));
            else speed *= COAST;
            if (down) speed *= BRAKE;
        }
        if (speed < 0.003D) speed = 0.0D;
        // Steering follows your mouse, but a fast cart turns wider.
        // (Clamped: past normal top speed - Powered Rails, Nitro - it stays at the widest turn instead of going haywire.)
        float turn = (float) Mth.lerp(Mth.clamp(speed / MAX_SPEED, 0.0D, 1.0D), 14.0D, 5.0D);
        heading += Mth.clamp(Mth.wrapDegrees(p.getYRot() - heading), -turn, turn);
        if (p.horizontalCollision && speed > 0.15D) {
            speed *= 0.3D; // ran into a wall
            p.playSound(SoundEvents.MINECART_INSIDE, 0.4F, 0.6F);
        }
        double r = Math.toRadians(heading);
        Vec3 v = p.getDeltaMovement();
        p.setDeltaMovement(-Math.sin(r) * speed, v.y, Math.cos(r) * speed);
    }

    // ------------------------------------------------------------------ the rails

    private static final long FADE_IN_MS = 160L, FADE_OUT_MS = 650L;

    private static final class Rail {
        final BlockPos pos;
        final long added;
        long left = -1L;
        /** Laid while Powered Rails was on: golden rail with redstone under it. */
        boolean powered;
        int in = -1, out = -1; // 0 N, 1 E, 2 S, 3 W

        Rail(BlockPos pos, long added) {
            this.pos = pos;
            this.added = added;
        }
    }

    private static final class Trail {
        final ArrayDeque<Rail> rails = new ArrayDeque<>();
        float yaw;
        Vec3 last;
        float speed;
    }

    private static final Map<UUID, Trail> TRAILS = new HashMap<>();
    private static final Set<UUID> SOUNDING = new HashSet<>();
    /** The rumble playing for each rider, so it can be cut off the moment they stop riding. */
    private static final Map<UUID, RollSound> SOUNDS = new HashMap<>();

    private static int dirTo(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        if (dz < 0) return 0;
        if (dx > 0) return 1;
        if (dz > 0) return 2;
        return 3;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            TRAILS.clear();
            SOUNDING.clear();
            return;
        }
        long now = net.minecraft.Util.getMillis();
        // Anyone who stopped riding (got out, dismissed the Stand, logged off): cut their rumble right away.
        SOUNDS.entrySet().removeIf(en -> {
            Player rider = mc.level.getPlayerByUUID(en.getKey());
            if (rider != null && riding(rider) && !rider.isRemoved()) return false;
            mc.getSoundManager().stop(en.getValue());
            SOUNDING.remove(en.getKey());
            return true;
        });
        for (Player p : mc.level.players()) {
            boolean ride = riding(p);
            Trail t = TRAILS.get(p.getUUID());
            if (!ride) {
                if (t != null) for (Rail r : t.rails) if (r.left < 0) r.left = now;
                continue;
            }
            if (t == null) {
                t = new Trail();
                t.yaw = p.getYRot();
                TRAILS.put(p.getUUID(), t);
            }
            // Which way the cart faces: your own steering, or where other riders are rolling.
            Vec3 pos = p.position();
            if (t.last != null) {
                double dx = pos.x - t.last.x, dz = pos.z - t.last.z;
                t.speed = (float) Math.sqrt(dx * dx + dz * dz);
                if (p.isLocalPlayer()) t.yaw = heading;
                else if (dx * dx + dz * dz > 1.0E-4D) t.yaw = Mth.rotLerp(0.5F, t.yaw, (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F);
            }
            t.last = pos;
            BlockPos at = p.blockPosition();
            Rail cur = t.rails.peekLast();
            if (cur == null || cur.left >= 0 || !cur.pos.equals(at)) {
                if (cur != null && cur.left < 0 && !cur.pos.equals(at)) {
                    int dx = at.getX() - cur.pos.getX(), dz = at.getZ() - cur.pos.getZ();
                    if (Math.abs(dx) + Math.abs(dz) == 2 && Math.abs(dx) == 1) {
                        // Moved diagonally: lay a corner piece in between so the track stays connected.
                        BlockPos mid = cur.pos.offset(dx, 0, 0);
                        cur.out = dirTo(cur.pos, mid);
                        cur.left = now;
                        Rail m = new Rail(mid, now);
                        m.in = dirTo(mid, cur.pos);
                        t.rails.add(m);
                        cur = m;
                    }
                    if (Math.abs(at.getX() - cur.pos.getX()) + Math.abs(at.getZ() - cur.pos.getZ()) == 1) {
                        cur.out = dirTo(cur.pos, at);
                    }
                    cur.left = now;
                }
                Rail n = new Rail(at, now);
                n.powered = StandPresence.cartBoost(p.getUUID(), p.level().getGameTime()) == 1;
                if (cur != null && Math.abs(at.getX() - cur.pos.getX()) + Math.abs(at.getZ() - cur.pos.getZ()) == 1) n.in = dirTo(at, cur.pos);
                t.rails.add(n);
                if (cur != null && p.distanceToSqr(Vec3.atBottomCenterOf(cur.pos)) < 9.0D) {
                    p.level().playLocalSound(p.getX(), p.getY(), p.getZ(), SoundEvents.CHAIN_STEP, SoundSource.PLAYERS, 0.15F, 1.4F, false);
                }
            }
            if (SOUNDING.add(p.getUUID())) {
                RollSound s = new RollSound(p);
                SOUNDS.put(p.getUUID(), s);
                mc.getSoundManager().play(s);
            }
            boostParticles(p, t, StandPresence.cartBoost(p.getUUID(), p.level().getGameTime()));
        }
        // Rails that have fully faded are gone.
        Iterator<Map.Entry<UUID, Trail>> it = TRAILS.entrySet().iterator();
        while (it.hasNext()) {
            Trail t = it.next().getValue();
            t.rails.removeIf(r -> r.left >= 0 && now - r.left > FADE_OUT_MS);
            while (t.rails.size() > 64) t.rails.removeFirst();
            if (t.rails.isEmpty()) it.remove();
        }
    }

    /** Nitro: flames and smoke roaring out of the back. Powered Rails: redstone sparks off the wheels. */
    private static void boostParticles(Player p, Trail t, int boost) {
        if (boost == 0) return;
        double r = Math.toRadians(t.yaw);
        double bx = Math.sin(r) * 0.7D, bz = -Math.cos(r) * 0.7D; // behind the cart
        net.minecraft.util.RandomSource rnd = p.getRandom();
        if (boost == 2) {
            for (int i = 0; i < 3; i++) {
                p.level().addParticle(net.minecraft.core.particles.ParticleTypes.FLAME, p.getX() + bx + (rnd.nextDouble() - 0.5D) * 0.4D,
                        p.getY() + 0.35D + rnd.nextDouble() * 0.2D, p.getZ() + bz + (rnd.nextDouble() - 0.5D) * 0.4D, bx * 0.15D, 0.02D, bz * 0.15D);
            }
            p.level().addParticle(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, p.getX() + bx, p.getY() + 0.5D, p.getZ() + bz, bx * 0.05D, 0.04D, bz * 0.05D);
            if (rnd.nextInt(4) == 0) p.level().addParticle(net.minecraft.core.particles.ParticleTypes.LAVA, p.getX() + bx, p.getY() + 0.4D, p.getZ() + bz, 0.0D, 0.0D, 0.0D);
        } else if (rnd.nextInt(2) == 0) {
            p.level().addParticle(net.minecraft.core.particles.DustParticleOptions.REDSTONE, p.getX() + (rnd.nextDouble() - 0.5D) * 0.8D,
                    p.getY() + 0.1D, p.getZ() + (rnd.nextDouble() - 0.5D) * 0.8D, 0.0D, 0.0D, 0.0D);
        }
    }

    private static final ResourceLocation RAIL = ResourceLocation.withDefaultNamespace("textures/block/rail.png");
    private static final ResourceLocation POWERED_RAIL = ResourceLocation.withDefaultNamespace("textures/block/powered_rail_on.png");
    private static final ResourceLocation REDSTONE_BLOCK = ResourceLocation.withDefaultNamespace("textures/block/redstone_block.png");
    private static final ResourceLocation RAIL_CORNER = ResourceLocation.withDefaultNamespace("textures/block/rail_corner.png");

    /** Called from the Stand renderer (its pose is at the Stand's position). */
    public static void renderRails(UUID owner, StandEntity stand, float partial, PoseStack ps, MultiBufferSource buffers) {
        Trail t = TRAILS.get(owner);
        if (t == null || t.rails.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        long now = net.minecraft.Util.getMillis();
        double sx = Mth.lerp(partial, stand.xo, stand.getX());
        double sy = Mth.lerp(partial, stand.yo, stand.getY());
        double sz = Mth.lerp(partial, stand.zo, stand.getZ());
        List<Rail> straight = new ArrayList<>(), corner = new ArrayList<>(), powered = new ArrayList<>();
        for (Rail r : t.rails) {
            // Powered rails can't curve in vanilla either: corners stay plain rails.
            if (isCorner(r)) corner.add(r);
            else (r.powered ? powered : straight).add(r);
        }
        drawRails(straight, false, RAIL, now, sx, sy, sz, ps, buffers, mc, 0.0D);
        drawRails(corner, true, RAIL_CORNER, now, sx, sy, sz, ps, buffers, mc, 0.0D);
        // Powered: a block of redstone set into the ground under each golden rail.
        drawRails(powered, false, REDSTONE_BLOCK, now, sx, sy, sz, ps, buffers, mc, -0.0625D * 0.12D);
        drawRails(powered, false, POWERED_RAIL, now, sx, sy, sz, ps, buffers, mc, 0.0D);
    }

    private static boolean isCorner(Rail r) {
        return r.in >= 0 && r.out >= 0 && (r.in % 2) != (r.out % 2);
    }

    /** Clockwise turns (from above) to apply to the vanilla rail texture for this piece. */
    private static int rotation(Rail r, boolean corner) {
        if (!corner) {
            int d = r.out >= 0 ? r.out : r.in;
            return d >= 0 && d % 2 == 1 ? 1 : 0; // east-west rails are the north-south texture turned once
        }
        // The corner texture joins south and east; turn it until it joins this rail's two sides.
        boolean n = r.in == 0 || r.out == 0, e = r.in == 1 || r.out == 1, s = r.in == 2 || r.out == 2, w = r.in == 3 || r.out == 3;
        if (s && e) return 0;
        if (s && w) return 1;
        if (n && w) return 2;
        return 3; // north + east
    }

    private static void drawRails(List<Rail> rails, boolean corner, ResourceLocation tex, long now, double sx, double sy, double sz,
                                  PoseStack ps, MultiBufferSource buffers, Minecraft mc, double yOff) {
        if (rails.isEmpty()) return;
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(tex));
        for (Rail r : rails) {
            float a = Mth.clamp((now - r.added) / (float) FADE_IN_MS, 0.0F, 1.0F);
            if (r.left >= 0) a *= 1.0F - Mth.clamp((now - r.left) / (float) FADE_OUT_MS, 0.0F, 1.0F);
            if (a <= 0.02F) continue;
            int light = LevelRenderer.getLightColor(mc.level, r.pos);
            int color = FastColor.ARGB32.color((int) (a * 255.0F), 255, 255, 255);
            int rot = rotation(r, corner);
            ps.pushPose();
            ps.translate(r.pos.getX() - sx, r.pos.getY() - sy + 0.0625D * 0.25D + yOff, r.pos.getZ() - sz);
            PoseStack.Pose pose = ps.last();
            Matrix4f m = pose.pose();
            float[][] uv = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
            for (float[] c : uv) {
                // Turn the texture corner around the block's centre (clockwise seen from above).
                float x = c[0] - 0.5F, z = c[1] - 0.5F;
                for (int i = 0; i < rot; i++) {
                    float nx = -z;
                    z = x;
                    x = nx;
                }
                vc.addVertex(m, x + 0.5F, 0.0F, z + 0.5F).setColor(color).setUv(c[0], c[1])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0.0F, 1.0F, 0.0F);
            }
            ps.popPose();
        }
    }

    // ------------------------------------------------------------------ the cart

    private static MinecartModel<net.minecraft.world.entity.Entity> cartModel;
    private static final ResourceLocation CART_TEX = ResourceLocation.withDefaultNamespace("textures/entity/minecart.png");

    /** Draws a minecart at the current pose origin (its wheels on the ground), facing {@code yaw}. */
    public static void drawCart(PoseStack ps, MultiBufferSource buffers, int light, float yaw, float alpha) {
        if (alpha <= 0.02F) return;
        if (cartModel == null) cartModel = new MinecartModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.MINECART));
        ps.pushPose();
        // Same placement as vanilla's minecart renderer.
        ps.translate(0.0F, 0.375F, 0.0F);
        ps.mulPose(Axis.YP.rotationDegrees(180.0F - yaw + 90.0F));
        ps.scale(-1.0F, -1.0F, 1.0F);
        RenderType rt = alpha < 0.99F ? RenderType.entityTranslucent(CART_TEX) : cartModel.renderType(CART_TEX);
        cartModel.renderToBuffer(ps, buffers.getBuffer(rt), light, OverlayTexture.NO_OVERLAY,
                FastColor.ARGB32.color((int) (alpha * 255.0F), 255, 255, 255));
        ps.popPose();
    }

    /** The rider: draw the cart under them and sit them down in it. */
    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        Player p = event.getEntity();
        if (!riding(p)) return;
        Trail t = TRAILS.get(p.getUUID());
        float yaw = p.isLocalPlayer() ? heading : t != null ? t.yaw : p.getYRot();
        drawCart(event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight(), yaw, 1.0F);
        event.getPoseStack().pushPose();
        event.getPoseStack().translate(0.0F, -0.42F, 0.0F); // sitting down inside it
        p.yBodyRot = yaw;
        p.yBodyRotO = yaw;
    }

    @SubscribeEvent
    public static void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        if (riding(event.getEntity())) event.getPoseStack().popPose();
    }

    // ------------------------------------------------------------------ sound

    /** The rolling rumble, louder the faster the cart goes. */
    private static final class RollSound extends AbstractTickableSoundInstance {
        private final Player rider;

        RollSound(Player rider) {
            super(SoundEvents.MINECART_RIDING, SoundSource.PLAYERS, net.minecraft.client.resources.sounds.SoundInstance.createUnseededRandom());
            this.rider = rider;
            this.looping = true;
            this.delay = 0;
            this.volume = 0.0F;
            this.x = rider.getX();
            this.y = rider.getY();
            this.z = rider.getZ();
        }

        @Override
        public void tick() {
            if (rider.isRemoved() || !riding(rider)) {
                SOUNDING.remove(rider.getUUID());
                stop();
                return;
            }
            x = rider.getX();
            y = rider.getY();
            z = rider.getZ();
            Trail t = TRAILS.get(rider.getUUID());
            float sp = t == null ? 0.0F : t.speed;
            volume = Mth.clamp(sp * 1.6F, 0.0F, 0.75F);
            pitch = 0.7F + Mth.clamp(sp, 0.0F, 0.6F);
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }
}
