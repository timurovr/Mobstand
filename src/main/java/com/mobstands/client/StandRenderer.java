package com.mobstands.client;

import com.mobstands.entity.StandEntity;
import com.mobstands.stand.StandType;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.Map;

/**
 * Draws a Stand as a solid, fully coloured copy of the vanilla mob model that fades in when summoned,
 * with a gentle hover bob and a lunge on basic attacks.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class StandRenderer extends EntityRenderer<StandEntity> {
    /** The Stands' attack animations (ghost fists, MUDA bursts, flying barrage fists, combo wind-ups, impact shells). */
    public static final boolean ATTACK_FX = true;

    // Built once, not every frame.
    private static final ResourceLocation CHARGE_SWIRL = ResourceLocation.withDefaultNamespace("textures/entity/creeper/creeper_armor.png");
    private static final ResourceLocation REQUIEM_SWIRL = ResourceLocation.fromNamespaceAndPath("mobstands", "textures/entity/requiem_swirl.png");
    private static final ResourceLocation PLACEHOLDER_TEX = ResourceLocation.withDefaultNamespace("textures/entity/creeper/creeper.png");

    private final EntityRendererProvider.Context context;
    private final Map<StandType, StandVisual> visuals = new EnumMap<>(StandType.class);

    public StandRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.context = context;
        this.shadowRadius = 0.0F; // spirits cast no shadow
    }

    @Override
    public ResourceLocation getTextureLocation(StandEntity entity) {
        return PLACEHOLDER_TEX;
    }

    @Override
    public boolean shouldRender(StandEntity entity, Frustum frustum, double x, double y, double z) {
        return true; // our bounding box is smaller than some models (Phantom wings), so skip frustum culling
    }

    @Override
    public void render(StandEntity stand, float yaw, float partial, PoseStack ps, MultiBufferSource buffers, int light) {
        StandType type = stand.getStandType();
        if (type == null || stand.isHidden() ) return;
        if (type == StandType.SLIME && stand.disguise() >= 0 && stand.getOwnerPlayer() != Minecraft.getInstance().player) return; // (in disguise)
        if (type == StandType.ILLUSIONER && stand.isVanished() && stand.getOwnerPlayer() != Minecraft.getInstance().player) return; // (vanished)
        if (type == StandType.SCARY_MONSTERS) return; // (it's drawn as its user's own body: RavagerForm)
        if (type == StandType.SILVERFISH) {
            SilverSwarm.render(stand, partial, ps, buffers);
            return;
        }
        StandVisual vis = visuals.computeIfAbsent(type, t -> {
            StandVisual v = new StandVisual(t, context);
            MODEL_OWNERS.put(v.main, v);
            return v;
        });
        if (type == StandType.HORSE) {
            renderHorseHead(stand, vis, partial, ps, buffers, light);
            return;
        }
        if (type.isCart()) {
            // Its rails (fading in under the rider and out behind them), then the cart itself if nobody's in it.
            stand.getOwnerUUID().ifPresent(u -> CartRide.renderRails(u, stand, partial, ps, buffers));
            Minecraft mcr = Minecraft.getInstance();
            if (stand.isCartRide() && mcr.player != null && stand.getOwnerPlayer() == mcr.player && mcr.options.getCameraType().isFirstPerson()) {
                // First person: you can't see yourself, but look down and your cart is there (right under you).
                ps.pushPose();
                ps.translate(Mth.lerp(partial, mcr.player.xo, mcr.player.getX()) - Mth.lerp(partial, stand.xo, stand.getX()),
                        Mth.lerp(partial, mcr.player.yo, mcr.player.getY()) - Mth.lerp(partial, stand.yo, stand.getY()),
                        Mth.lerp(partial, mcr.player.zo, mcr.player.getZ()) - Mth.lerp(partial, stand.zo, stand.getZ()));
                CartRide.drawCart(ps, buffers, light, CartRide.heading(), 1.0F);
                ps.popPose();
            }
            if (!stand.isCartRide()) {
                float fade = Mth.clamp((stand.tickCount + partial) / (float) StandEntity.fadeTicks(type), 0.0F, 1.0F) * (1.0F - stand.dismissProgress(partial));
                float yawR = Mth.rotLerp(partial, stand.yRotO, stand.getYRot());
                ps.pushPose();
                ps.translate(0.0D, Mth.sin((stand.tickCount + partial) * 0.08F) * 0.05D, 0.0D);
                CartRide.drawCart(ps, buffers, light, yawR, fade);
                ps.popPose();
            }
            return;
        }
        if (type == StandType.SHULKER && stand.animProgress(partial) < 0.0F) {
            // On your own shoulder in first person it would sit in your face, like a parrot it stays out of view.
            Minecraft mcs = Minecraft.getInstance();
            if (stand.getOwnerPlayer() == mcs.player && mcs.options.getCameraType().isFirstPerson()) return;
        }
        Entity dummy = vis.dummy(stand.level());
        if (dummy == null) return;

        // Smooth out every snap and stutter (see Smooth): draw it where the smoothing says, not where the raw
        // position is this frame.
        Smooth sm = smooth(stand, type, partial);
        ps.translate(sm.ox, sm.oy, sm.oz);
        float bodyYaw = sm.yaw;
        // (Piloted: the whole body pitches instead - the head stays put on the body.)
        float pitch = stand.isPiloting() ? 0.0F : Mth.clamp(Mth.lerp(partial, stand.xRotO, stand.getXRot()), -35.0F, 35.0F);
        float age = stand.tickCount + partial;
        float limbAmount = Mth.lerp(partial, stand.limbSpeedO, stand.limbSpeed);
        float limbSwing = stand.limbPos - stand.limbSpeed * (1.0F - partial);
        float progress = stand.animProgress(partial);
        int slot = stand.animSlot();
        // (No attack animations: no lurch forward, no straightening up - the Stand holds its pose.)
        float pulse = ATTACK_FX && progress >= 0.0F ? Mth.sin(progress * Mth.PI) : 0.0F;

        vis.comboElapsed = slot >= 0 ? stand.animElapsed(partial) : 0.0F;
        vis.punchSide = stand.punchSide();
        vis.comboHit2 = stand.comboHit2();
        vis.fakeDead = stand.isFakeDead();
        vis.animate(dummy, stand.tickCount, limbSwing, limbAmount, age, pitch, partial, progress, slot, stand.animStart());

        if (type == StandType.ALLAY) {
            // The duplicates are part of the Stand: they fade in and out with it.
            float fIn = Mth.clamp((stand.tickCount + partial) / (float) StandEntity.fadeTicks(type), 0.0F, 1.0F);
            float fOut = stand.dismissProgress(partial);
            renderAllayWorld(stand, vis, partial, ps, buffers, fIn * fIn * (3.0F - 2.0F * fIn) * (1.0F - fOut * fOut * (3.0F - 2.0F * fOut)));
        }
        // (Requiems arrive nonchalantly: no entrance effects, they just fade in.)
        ps.pushPose();
        if (!stand.isPiloting()) firstPersonShift(stand, type, progress, partial, ps);
        ps.mulPose(Axis.YP.rotationDegrees(180.0F - bodyYaw));
        float bob = Mth.sin(age * 0.1F) * 0.04F;
        float lunge = slot == 0 ? pulse * 0.35F : 0.0F;
        ps.translate(0.0F, bob, -lunge);

        float scale = (float) type.scale() * (1.0F + Mth.sin(age * 0.08F) * 0.012F * (1.0F - pulse)); // breathing
        if (type == StandType.AXOLOTL && age < 6.0F) {
            scale *= 1.0F + 0.22F * Mth.sin(Mth.clamp(age / 6.0F, 0.0F, 1.0F) * Mth.PI); // pops in
        }
        if (type == StandType.SQUID) {
            // Swells up while it rains so the rain under it covers the area (grows upward from the tentacle tips).
            float g = Mth.lerp(partial, stand.rainGrowO, stand.rainGrow);
            g = g * g * (3.0F - 2.0F * g);
            scale *= 1.0F + (com.mobstands.stand.StandAbilities.RAIN_GROW - 1.0F) * g;
        }
        // Summon-punch combo wind-up: lean back and tremble with built-up force, snap forward on release.
        if (ATTACK_FX && (slot == StandEntity.ANIM_SUMMON_COMBO || slot == StandEntity.ANIM_CHARGED_COMBO)) {
            float tc = stand.animElapsed(partial);
            float charge = StandVisual.comboCharge(tc, StandVisual.dashTick(slot), StandVisual.hit1Tick(slot));
            ps.translate(Mth.sin(tc * 2.7F) * 0.025F * charge, 0.0F, 0.15F * charge);
            ps.mulPose(Axis.XP.rotationDegrees(10.0F * charge));
        }

        // Stylish idle stance: leaning back with a slight tilt and a slow sway; straightens up while attacking.
        float stance = 1.0F - pulse;
        int side = stand.side();
        vis.side = side;
        vis.posing = stand.isPosing();
        boolean requiemStill = type == StandType.ENDER_WORLD && stand.isPosing(); // dead upright: no stylish lean
        if (type == StandType.SPIDER) {
            // On your back the spider turns upright: head up by your shoulders, belly against you.
            Player owner = stand.getOwnerPlayer();
            float cling = 0.0F;
            if (owner != null) {
                double d = stand.position().distanceTo(StandEntity.homePosition(owner, type, stand.tickCount, side));
                cling = Mth.clamp(1.0F - (float) (d - 0.25D) / 0.9F, 0.0F, 1.0F);
            }
            ps.mulPose(Axis.XP.rotationDegrees(90.0F * cling));
        }
        if (stand.isPiloting() && type == StandType.SQUID) {
            // The Squid stays upright while you steer it: no tipping over when you look up or down, no banking.
        } else if (stand.isPiloting()) {
            // Flying level, banking into turns: turn left and it tips its left wing down, turn right and the right.
            float turnRate = Mth.wrapDegrees(stand.getYRot() - stand.yRotO);
            stand.pilotBank += (Mth.clamp(-turnRate * 3.0F, -40.0F, 40.0F) - stand.pilotBank) * 0.15F;
            ps.mulPose(Axis.ZP.rotationDegrees(stand.pilotBank));
            // Nose follows your aim: look down and it dives head-first, look up and it climbs.
            ps.mulPose(Axis.XP.rotationDegrees(-Mth.clamp(Mth.lerp(partial, stand.xRotO, stand.getXRot()), -60.0F, 60.0F)));
        } else if (!requiemStill) {
            ps.mulPose(Axis.YP.rotationDegrees(-vis.turn * side * stance));
            ps.mulPose(Axis.XP.rotationDegrees(vis.lean * stance));
            ps.mulPose(Axis.ZP.rotationDegrees((vis.tilt * side + Mth.sin(age * 0.05F) * 1.5F) * stance));
        }
        // Squid Rain Shot: it tips itself over 90 degrees so its tentacles point forward, at the enemy, while it fires.
        if (type == StandType.SQUID && slot == 3 && progress >= 0.0F) {
            float k = stand.rainShotBlend(partial);
            float pivot = 0.9F * scale; // about the middle of its body
            ps.translate(0.0F, pivot, 0.0F);
            ps.mulPose(Axis.XP.rotationDegrees(90.0F * k));
            ps.translate(0.0F, -pivot, 0.0F);
        }
        // Axolotl faking its death: the real vanilla death animation - it tips over onto its side and turns red.
        float deadT = stand.fakeDeadTime(partial);
        if (deadT >= 0.0F) {
            float f = Mth.sqrt(Mth.clamp((deadT - 1.0F) / 20.0F * 1.6F, 0.0F, 1.0F));
            ps.mulPose(Axis.ZP.rotationDegrees(f * 90.0F));
        }
        int overlay = deadT >= 0.0F || stand.hurtFlash(partial) ? OverlayTexture.pack(OverlayTexture.u(0.0F), OverlayTexture.v(true)) : OverlayTexture.NO_OVERLAY;
        ps.scale(-1.0F, -1.0F, 1.0F);
        ps.scale(scale, scale, scale);
        if (type == StandType.SLIME) {
            // Real slime movement: hops while you walk and squishes like vanilla (flat on landing, tall mid-hop),
            // with a gentle jiggle when idle. Scales around the feet so it stays on the ground.
            // Squish from its own hops: tall while airborne, flat on landing, gentle jiggle when sitting.
            float dtHop = (float) (stand.level().getGameTime() - stand.hopStart()) + partial;
            float squish;
            if (dtHop >= 0.0F && dtHop <= 10.0F) squish = -0.2F * Mth.sin(dtHop / 10.0F * Mth.PI);
            else if (dtHop > 10.0F && dtHop <= 16.0F) squish = 0.35F * (1.0F - (dtHop - 10.0F) / 6.0F);
            else squish = Mth.sin(age * 0.11F) * 0.035F;
            float f = 1.0F / (squish + 1.0F);
            ps.scale(f, 1.0F / f, f);
        }
        if (type == StandType.PHANTOM) ps.translate(0.0F, 1.3125F, 0.1875F);
        ps.translate(0.0F, -1.501F, 0.0F);

        // Fade in while emerging from the owner, then draw fully opaque in the mob's real colours.
        float fade = Mth.clamp((stand.isMobStand() ? stand.mobAppearAge(partial) : age) / (float) StandEntity.fadeTicks(type), 0.0F, 1.0F);
        float eased = fade * fade * fade * (fade * (fade * 6.0F - 15.0F) + 10.0F);
        float out = stand.dismissProgress(partial);
        eased *= 1.0F - out * out * (3.0F - 2.0F * out);
        if (eased <= 0.01F) {
            ps.popPose();
            return;
        }
        // Rain Shot: the squid goes see-through while it's down in front of you, so you can still see past it.
        if (type == StandType.SQUID) eased *= 1.0F - 0.6F * stand.rainShotBlend(partial);
        // Wonder of Deep: a little see-through, like it isn't entirely there.
        if (type == StandType.WARDEN_REQUIEM) eased *= 0.6F;
        int aura = StandHud.auraColor(type);
        int packedLight = light; // real world lighting (sky + block light at the Stand), like any other mob
        int color = ((int) (255.0F * eased) << 24) | 0xFFFFFF;
        RenderType bodyType = eased < 0.999F ? RenderType.entityTranslucent(vis.texture) : RenderType.entityCutoutNoCull(vis.texture);
        VertexConsumer body = buffers.getBuffer(bodyType);
        vis.main.renderToBuffer(ps, body, packedLight, overlay, color);
        if (type == StandType.ALLAY) {
            renderAllayRush(stand, vis, slot, progress, partial, ps, buffers, packedLight, eased);
        }
        // Summon-rush / charged combo wind-up for Stands without arms: a slow charge - the Stand's aura swells and
        // brightens (pulsing faster and faster) until it dashes in and strikes.
        if (ATTACK_FX && (slot == StandEntity.ANIM_SUMMON_COMBO || slot == StandEntity.ANIM_CHARGED_COMBO) && vis.rightArm == null && type.meleeBasic()) {
            float tc = stand.animElapsed(partial);
            float charge = StandVisual.comboCharge(tc, StandVisual.dashTick(slot), StandVisual.hit1Tick(slot));
            if (charge > 0.01F) {
                float throb = 0.75F + 0.25F * Mth.sin(tc * (0.8F + charge * 1.6F));
                glow(ps, buffers, vis.main, 0.45F * charge * throb, 1.05F + 0.12F * charge, aura, bodyCenterY(type), true);
                glow(ps, buffers, vis.main, 0.25F * charge * charge, 1.02F, 0xFFFFFF, bodyCenterY(type), true);
            }
        }
        if (vis.chargeShell != null) {
            // The charged-creeper swirl, exactly like vanilla's: the energy texture scrolling over a slightly bigger shell.
            float scroll = (stand.tickCount + partial) * 0.004F; // slow, lazy crawl
            vis.chargeShell.prepareMobModel(dummy, limbSwing, limbAmount, partial);
            vis.chargeShell.setupAnim(dummy, limbSwing, limbAmount, age, 0.0F, pitch);
            VertexConsumer swirl = buffers.getBuffer(RenderType.energySwirl(
                    CHARGE_SWIRL, scroll % 1.0F, scroll % 1.0F));
            vis.chargeShell.renderToBuffer(ps, swirl, packedLight, OverlayTexture.NO_OVERLAY, ((int) (255.0F * eased) << 24) | 0x555555);
        } else if (type.requiem()) {
            // Every Requiem wears a charged-creeper aura: the same crawling energy (in neutral white, so the tint is its true
            // colour - vanilla's swirl is blue, which turned gold into green) over a slightly bigger copy of its
            // body - tinted its own colour (gold for The Ender World and GER, amethyst for Pigstep).
            float scroll = (stand.tickCount + partial) * 0.004F; // slow, lazy crawl
            VertexConsumer swirl = buffers.getBuffer(RenderType.energySwirl(
                    REQUIEM_SWIRL, scroll % 1.0F, scroll % 1.0F));
            float cy = bodyCenterY(type);
            ps.pushPose();
            ps.translate(0.0F, cy, 0.0F);
            ps.scale(1.06F, 1.04F, 1.06F);
            ps.translate(0.0F, -cy, 0.0F);
            int c = type == StandType.GER ? 0x3CFF8A : type.color(); // GER: emerald green
            int tint = (((c >> 16) & 0xFF) / 2 << 16) | (((c >> 8) & 0xFF) / 2 << 8) | ((c & 0xFF) / 2); // half strength: a glow, not paint
            if (type == StandType.ENDER_WORLD) tint = 0xD9A000; // a rich, proper gold
            // ...faded down a bit more: it should hang around them, not coat them.
            tint = (((tint >> 16) & 0xFF) * 13 / 20 << 16) | (((tint >> 8) & 0xFF) * 13 / 20 << 8) | ((tint & 0xFF) * 13 / 20);
            // Wonder of Deep: barely there - a faint shimmer you half-notice, not a glow.
            if (type == StandType.WARDEN_REQUIEM) {
                tint = (((tint >> 16) & 0xFF) * 3 / 20 << 16) | (((tint >> 8) & 0xFF) * 3 / 20 << 8) | ((tint & 0xFF) * 3 / 20);
            }
            if (type == StandType.GER) {
                // GER burns brighter than the others: a strong emerald swirl that breathes in and out.
                float breathe = 0.8F + 0.2F * Mth.sin((stand.tickCount + partial) * 0.09F);
                tint = scaleRgb(0x3CFF8A, 0.6F * breathe);
            }
            vis.main.renderToBuffer(ps, swirl, packedLight, OverlayTexture.NO_OVERLAY, ((int) (255.0F * eased) << 24) | tint);
            ps.popPose();
            if (type == StandType.GER) gerAura(stand, vis, partial, eased, cy, ps, buffers, packedLight);
        }
        if (vis.outer != null) {
            VertexConsumer shell = buffers.getBuffer(RenderType.entityTranslucent(vis.texture));
            vis.outer.renderToBuffer(ps, shell, packedLight, OverlayTexture.NO_OVERLAY, ((int) (255.0F * eased) << 24) | 0xFFFFFF); // the texture itself carries the jelly transparency, like vanilla
        }

        // Rare and rarer Stands flare up with a bright aura while they materialize, then settle to solid.
        int tier = type.requiem() ? 4 : 3;
        if (type.summonEffects()
                && age < StandEntity.fadeTicks(type) + 10 && !stand.isDismissing()) {
            float k = age / (StandEntity.fadeTicks(type) + 10.0F);
            float flare = Mth.sin(k * Mth.PI) * (0.35F + 0.1F * (tier - 2));
            glow(ps, buffers, vis.main, flare, 1.03F + 0.1F * k, StandHud.auraColor(type), bodyCenterY(type), true);
            glow(ps, buffers, vis.main, flare * 0.5F, 1.01F, 0xFFFFFF, bodyCenterY(type), true);
        }

        // The mob's own glowing eyes (Enderman, Spider, Phantom) stay as in vanilla.
        if (vis.eyes != null) {
            VertexConsumer eyes = buffers.getBuffer(RenderType.eyes(vis.eyes));
            int glowCol = ((int) (255.0F * eased) << 24) | 0xFFFFFF;
            if (type == StandType.GER || type == StandType.PIGLIN_REQUIEM) {
                // GER's emeralds and eyes - and Pigstep's amethyst eyes, crown gem, fists and pauldrons -
                // pulse slowly, like a heartbeat of light.
                int lv = (int) (255.0F * (0.3F + 0.45F * (0.5F + 0.5F * Mth.sin(age * 0.11F))));
                glowCol = ((int) (255.0F * eased) << 24) | (lv << 16) | (lv << 8) | lv;
            }
            vis.main.renderToBuffer(ps, eyes, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, glowCol);
        }
        // ORA barrage (the Barrage ability): big fists keep flying out of the Stand for the whole move.
        if (ATTACK_FX && slot == 1 && progress >= 0.0F && vis.rightArm != null && type.barrage()) {
            drawBarrage(ps, buffers, vis, stand.animElapsed(partial), packedLight, aura);
        }

        // Summon-punch combo: two big ghost fists, one per impact in the sound (right, then left).
        if (ATTACK_FX && (slot == StandEntity.ANIM_SUMMON_COMBO || slot == StandEntity.ANIM_CHARGED_COMBO) && vis.rightArm != null) {
            float t = stand.animElapsed(partial);
            int[] hits = {StandVisual.hit1Tick(slot), stand.comboHit2()};
            for (int k = 0; k < 2; k++) {
                if (hits[k] < 0) continue; // punched only once: no second fist
                float e = StandVisual.comboPunch(t, hits[k]);
                if (e <= 0.02F) continue;
                ps.pushPose();
                ps.translate(k == 0 ? -0.35F : 0.35F, 0.35F, -(0.3F + e * 2.4F));
                float sc = 1.0F + e * 0.7F;
                ps.scale(sc, sc, sc);
                // Fetch the buffer right before drawing: drawing a glow in between switches buffers and closes the old one.
                StandVisual.ArmPiece armPiece = vis.arm(k == 1);
                drawArm(ps, buffers.getBuffer(RenderType.entityCutoutNoCull(vis.texture)), armPiece, packedLight, 0xFFFFFFFF, 1.0F);
                armGlow(ps, buffers, armPiece, 0.4F * e, 1.15F, aura);
                ps.popPose();
            }
            for (int k = 0; k < 2; k++) {
                if (hits[k] >= 0) impactBurst(ps, buffers, vis.arm(k == 1), t - hits[k], k == 0 ? -0.35F : 0.35F, 2.4F, 1.7F, aura);
            }
        }

        // Regular punch for fisted Stands: right ghost fist, plus a left one on a double-click.
        if (ATTACK_FX && slot == 0 && progress >= 0.0F && vis.rightArm != null) {
            float tp = stand.animElapsed(partial);
            int[] hits = {com.mobstands.stand.StandAbilities.PUNCH_HIT_TICK, stand.comboHit2()};
            boolean leftOnly = stand.punchSide() == 1; // follow-up punch: just the left fist
            for (int k = 0; k < 2; k++) {
                if (hits[k] < 0 || (leftOnly && k == 1)) continue; // single click: right fist only
                if (type == StandType.ZOMBIFIED_PIGLIN && k == 0 && !leftOnly) continue; // pokes with its real sword instead of a ghost fist
                float punchX = k == 0 && !leftOnly ? -0.35F : 0.35F;
                drawPunch(ps, buffers, vis, tp - hits[k], punchX, ((k == 1) != leftOnly) ? 1 : 0, k * 3 + (leftOnly ? 1 : 0), packedLight, aura);
                if (type == StandType.GER) zeroRings(ps, buffers, tp - hits[k], punchX * 0.25F);
            }
        }

        // Body-striking Stands (Creeper, Spider, Phantom, Slime): a glowing shell of the whole mob bursts on impact.
        if (ATTACK_FX && (slot == 0 || slot == StandEntity.ANIM_SUMMON_COMBO || slot == StandEntity.ANIM_CHARGED_COMBO) && progress >= 0.0F && type.meleeBasic() && vis.rightArm == null && type != StandType.PHANTOM) {
            float tb = stand.animElapsed(partial);
            int first = slot == 0 ? com.mobstands.stand.StandAbilities.PUNCH_HIT_TICK : StandVisual.hit1Tick(slot);
            int[] hits = {first, stand.comboHit2()};
            for (int h : hits) {
                if (h < 0) continue;
                float dt = tb - h;
                if (dt < -2.0F || dt > 9.0F) continue;
                float k = Mth.clamp(dt / 9.0F, 0.0F, 1.0F);
                float a = dt < 0.0F ? 0.25F * (1.0F + dt / 2.0F) : 0.55F * (1.0F - k) * (1.0F - k);
                glow(ps, buffers, vis.main, a, 1.04F + k * 0.22F, aura, bodyCenterY(type), true);
            }
        }

        // The Ender World: a clock set into its chest (it stops ticking while time is stopped).
        if (type == StandType.ENDER_WORLD && vis.main instanceof net.minecraft.client.model.HumanoidModel<?> hm && dummy instanceof LivingEntity holder && eased > 0.4F) {
            ps.pushPose();
            hm.body.translateAndRotate(ps);
            ps.translate(0.0F, 4.5F / 16.0F, -2.3F / 16.0F);
            ps.mulPose(Axis.XP.rotationDegrees(180.0F));
            ps.scale(0.42F, 0.42F, 0.42F);
            Minecraft.getInstance().getItemRenderer().renderStatic(TimeStopFx.frozen() ? null : holder, CLOCK, ItemDisplayContext.FIXED, false, ps, buffers,
                    stand.level(), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, stand.getId());
            if (stand.auraOn()) clockGlow(ps, buffers, age);
            if (stand.getOwnerPlayer() == Minecraft.getInstance().player) {
                TimeStopFx.clockSeen(ps.last().pose().transformPosition(0.0F, 0.0F, 0.0F, new org.joml.Vector3f()));
            }
            ps.popPose();
        }
        // GER: a real Totem of Undying set into its chest.
        if (type == StandType.GER && eased > 0.4F) {
            ps.pushPose();
            vis.root.getChild("body").translateAndRotate(ps);
            ps.translate(0.0F, 3.5F / 16.0F, -6.9F / 16.0F);
            ps.mulPose(Axis.XP.rotationDegrees(180.0F));
            ps.scale(0.55F, 0.55F, 0.55F);
            Minecraft.getInstance().getItemRenderer().renderStatic(TOTEM, ItemDisplayContext.FIXED, LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY, ps, buffers, stand.level(), stand.getId());
            totemStar(ps, buffers, age, eased);
            ps.popPose();
        }
        // Held item (the Skeleton's bow), placed exactly like vanilla's held-item layer.
        if (vis.main instanceof ArmedModel armed && dummy instanceof LivingEntity holder && eased > 0.6F) {
            ItemStack held = holder.getMainHandItem();
            if (!held.isEmpty()) {
                ps.pushPose();
                armed.translateToHand(HumanoidArm.RIGHT, ps);
                ps.mulPose(Axis.XP.rotationDegrees(-90.0F));
                ps.mulPose(Axis.YP.rotationDegrees(180.0F));
                ps.translate(1.0F / 16.0F, 0.125F, -0.625F);
                if (vis.swordPoke > 0.0F) ps.mulPose(Axis.XP.rotationDegrees(-90.0F * vis.swordPoke)); // blade points ahead to stab
                Minecraft.getInstance().getEntityRenderDispatcher().getItemInHandRenderer()
                        .renderItem(holder, held, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false, ps, buffers, packedLight);
                ps.popPose();
            }
        }
        ps.popPose();
    }

    private static final ItemStack CLOCK = new ItemStack(net.minecraft.world.item.Items.CLOCK);
    private static final ItemStack TOTEM = new ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING);

    private static final ResourceLocation GOLD_TEX = ResourceLocation.withDefaultNamespace("textures/block/gold_block.png");
    private static final ResourceLocation EMERALD_TEX = ResourceLocation.withDefaultNamespace("textures/block/emerald_block.png");

    /**
     * GER's halo, vanilla style: a ring of little gold-block cubes, wound round the middle with thin emerald wire.
     * Drawn in model space (y points down), centred on the current pose; the caller spins it.
     */
    private static void halo(PoseStack ps, MultiBufferSource buffers, float alpha) {
        int segs = 20;
        float r = 5.5F / 16.0F, size = 1.5F / 16.0F;
        VertexConsumer gold = buffers.getBuffer(RenderType.entityCutoutNoCull(GOLD_TEX));
        for (int i = 0; i < segs; i++) {
            ps.pushPose();
            ps.mulPose(Axis.YP.rotation(i * Mth.TWO_PI / segs));
            ps.translate(r, 0.0F, 0.0F);
            cube(ps, gold, size * 0.8F, size, size * 1.25F, (i * 3) % 14);
            ps.popPose();
        }
        VertexConsumer emerald = buffers.getBuffer(RenderType.entityCutoutNoCull(EMERALD_TEX));
        for (int i = 0; i < segs; i += 4) {
            // a thin wrap of wire round the middle of the ring
            ps.pushPose();
            ps.mulPose(Axis.YP.rotation((i + 0.5F) * Mth.TWO_PI / segs));
            ps.translate(r, 0.0F, 0.0F);
            cube(ps, emerald, size * 1.15F, size * 1.4F, 0.45F / 16.0F, (i * 5) % 14);
            ps.popPose();
        }
    }

    /** A small textured cube (w along x, h along y, d along z) centred on the pose, full-bright, pixel-scale UVs. */
    private static void cube(PoseStack ps, VertexConsumer vc, float w, float h, float d, int uvOff) {
        PoseStack.Pose pose = ps.last();
        float x0 = -w / 2, x1 = w / 2, y0 = -h / 2, y1 = h / 2, z0 = -d / 2, z1 = d / 2;
        float u0 = uvOff / 16.0F, u1 = (uvOff + 2) / 16.0F, v0 = 0.25F, v1 = 0.375F;
        face(vc, pose, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, 0, 0, -1, u0, v0, u1, v1);
        face(vc, pose, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, 0, 0, 1, u0, v0, u1, v1);
        face(vc, pose, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, -1, 0, 0, u0, v0, u1, v1);
        face(vc, pose, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1, 0, 0, u0, v0, u1, v1);
        face(vc, pose, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, 0, -1, 0, u0, v0, u1, v1);
        face(vc, pose, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0, 1, 0, u0, v0, u1, v1);
    }

    private static void face(VertexConsumer vc, PoseStack.Pose pose, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, float nx, float ny, float nz,
                             float u0, float v0, float u1, float v1) {
        vc.addVertex(pose, ax, ay, az).setColor(-1).setUv(u0, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, nx, ny, nz);
        vc.addVertex(pose, bx, by, bz).setColor(-1).setUv(u1, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, nx, ny, nz);
        vc.addVertex(pose, cx, cy, cz).setColor(-1).setUv(u1, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, nx, ny, nz);
        vc.addVertex(pose, dx, dy, dz).setColor(-1).setUv(u0, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, nx, ny, nz);
    }

    /** A two-sided coloured quad. */
    private static void quad(VertexConsumer vc, org.joml.Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3, int r, int g, int b, int a) {
        vc.addVertex(m, x0, y0, z0).setColor(r, g, b, a);
        vc.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(m, x2, y2, z2).setColor(r, g, b, a);
        vc.addVertex(m, x3, y3, z3).setColor(r, g, b, a);
        vc.addVertex(m, x3, y3, z3).setColor(r, g, b, a);
        vc.addVertex(m, x2, y2, z2).setColor(r, g, b, a);
        vc.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(m, x0, y0, z0).setColor(r, g, b, a);
    }

    /** Which Stand visual a model belongs to (so whole-body glows can hide see-through parts). */
    private static final java.util.Map<Object, StandVisual> MODEL_OWNERS = new java.util.IdentityHashMap<>();

    /** The ghost fist box spans z = -11..0 pixels; glow shells scale around its middle. */
    private static final float FIST_CENTER_Z = -5.5F / 16.0F;

    private static float bodyCenterY(StandType type) {
        // Model space here is upside down with the feet at y = 1.5 (24 px): centre of each mob's body.
        return switch (type) {
            case CREEPER, CREEPER_REQUIEM -> 0.65F;
            case SPIDER -> 1.15F;
            case SLIME -> 1.25F;
            case PHANTOM -> 1.2F;
            default -> 0.6F;
        };
    }

    /** Every arm/rod is drawn this long (pixels) as a fist, whatever its real length (golem arm 30, blaze rod 8). */
    private static final float ARM_LENGTH = 18.6F;

    /**
     * Draws a mob's real arm as a flying fist: centred on the fist slot, pointing forward with the hand leading,
     * in its own texture. {@code grow} scales it around its centre (used for the glow shells).
     */
    private static void drawArm(PoseStack ps, VertexConsumer vc, StandVisual.ArmPiece arm, int light, int color, float grow) {
        if (arm == null) return;
        net.minecraft.client.model.geom.ModelPart part = arm.part();
        ps.pushPose();
        ps.translate(0.0F, 0.0F, FIST_CENTER_Z);
        // Glow shells hug the arm: they thicken around it but only lengthen a little, so a thin Enderman arm
        // gets a slim aura and a thick Golem arm a wide one.
        float lengthGrow = 1.0F + (grow - 1.0F) * 0.3F;
        float armScale = ARM_LENGTH / arm.length();
        // Short arms (GER, Piglins) are stretched to fist length but keep their real slim thickness.
        float thick = Math.min(armScale, 1.0F);
        ps.scale(thick * grow, thick * grow, armScale * lengthGrow);
        ps.mulPose(Axis.XP.rotation(-Mth.HALF_PI)); // arm hangs along +y (down); turn it to point forward, hand first
        ps.translate(-arm.cx() / 16.0F, -arm.cy() / 16.0F, -arm.cz() / 16.0F);
        float px = part.x, py = part.y, pz = part.z, rx = part.xRot, ry = part.yRot, rz = part.zRot;
        part.setPos(0.0F, 0.0F, 0.0F);
        part.setRotation(0.0F, 0.0F, 0.0F);
        part.render(ps, vc, light, OverlayTexture.NO_OVERLAY, color);
        part.setPos(px, py, pz);
        part.setRotation(rx, ry, rz);
        ps.popPose();
    }

    private static void armGlow(PoseStack ps, MultiBufferSource buffers, StandVisual.ArmPiece arm, float alpha, float scale, int rgb) {
        if (alpha <= 0.01F) return;
        int color = ((int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24) | (rgb & 0xFFFFFF);
        drawArm(ps, buffers.getBuffer(ModRenderTypes.glow()), arm, LightTexture.FULL_BRIGHT, color, scale);
    }

    /**
     * The Ender World's chest clock, made magical: a soft radial glow behind it, a ring of light around it with the
     * twelve hour marks, and an inner ring turning the other way. While time is stopped the rings freeze, the glow
     * flares, and its colour slowly drifts.
     */
    private static void clockGlow(PoseStack ps, MultiBufferSource buffers, float age) {
        org.joml.Matrix4f m = ps.last().pose();
        VertexConsumer vc = buffers.getBuffer(ModRenderTypes.glow());
        boolean stopped = TimeStopFx.frozen();
        float pulse = 0.5F + 0.5F * Mth.sin(age * (stopped ? 0.35F : 0.12F));
        int r = 255, g = 214, b = 74; // gold
        if (stopped) {
            int c = Mth.hsvToRgb((age * 0.008F) % 1.0F, 0.45F, 1.0F);
            r = c >> 16 & 255;
            g = c >> 8 & 255;
            b = c & 255;
        }
        float k = (stopped ? 1.6F : 1.0F) * daylightDim();
        // Soft radial glow: bright at the middle, fading to nothing at the edge.
        disc(vc, m, 0.0F, 0.95F + 0.08F * pulse, 255, 245, 210, r, g, b, 0.35F * k * (0.7F + 0.3F * pulse), 0.0F);
        disc(vc, m, 0.45F, 1.6F + 0.15F * pulse, r, g, b, r, g, b, 0.16F * k, 0.0F);
        // Turning rings (frozen while time is stopped).
        float spin = stopped ? 0.0F : age * 1.2F;
        ring(vc, m, 0.66F, 0.71F, spin, r, g, b, 0.55F * k, 12, 0.13F);
        ring(vc, m, 0.52F, 0.545F, -spin * 1.7F, r, g, b, 0.35F * k, 4, 0.08F);
    }

    /**
     * Additive glows wash out into a white blob against a bright daytime sky: full strength at night, faint by day.
     */
    private static float daylightDim() {
        var level = Minecraft.getInstance().level;
        if (level == null) return 1.0F;
        float sky = level.getSkyDarken(1.0F); // ~0.2 at night, 1.0 in full daylight
        float day = Mth.clamp((sky - 0.2F) / 0.8F, 0.0F, 1.0F);
        return 1.0F - 0.75F * day;
    }

    /** GER's totem: a soft gold glow behind it and a slowly turning eight-pointed star of light, gently pulsing. */
    private static void totemStar(PoseStack ps, MultiBufferSource buffers, float age, float fade) {
        org.joml.Matrix4f m = ps.last().pose();
        VertexConsumer vc = buffers.getBuffer(ModRenderTypes.glow());
        float pulse = 0.5F + 0.5F * Mth.sin(age * 0.11F);
        fade *= daylightDim();
        int r = 255, g = 214, b = 74;
        disc(vc, m, 0.0F, 0.75F + 0.06F * pulse, 255, 245, 210, r, g, b, 0.3F * fade * (0.7F + 0.3F * pulse), 0.0F);
        float spin = age * 0.6F * Mth.DEG_TO_RAD;
        int al = (int) (Mth.clamp(0.5F * fade * (0.7F + 0.3F * pulse), 0.0F, 1.0F) * 255.0F);
        for (int i = 0; i < 8; i++) {
            float t = spin + i * Mth.TWO_PI / 8;
            float len = (i % 2 == 0 ? 1.15F : 0.72F) + 0.06F * pulse; // long points at the cross, short between
            float w = i % 2 == 0 ? 0.07F : 0.05F;
            float c = Mth.cos(t), s = Mth.sin(t), px = -s * w, py = c * w;
            float in = 0.28F;
            // a tapering point: wide at the middle, sharp at the tip
            vc.addVertex(m, c * in - px, s * in - py, 0.0F).setColor(255, 240, 180, al);
            vc.addVertex(m, c * len, s * len, 0.0F).setColor(r, g, b, 0);
            vc.addVertex(m, c * len, s * len, 0.0F).setColor(r, g, b, 0);
            vc.addVertex(m, c * in + px, s * in + py, 0.0F).setColor(255, 240, 180, al);
            vc.addVertex(m, c * in + px, s * in + py, 0.0F).setColor(255, 240, 180, al);
            vc.addVertex(m, c * len, s * len, 0.0F).setColor(r, g, b, 0);
            vc.addVertex(m, c * len, s * len, 0.0F).setColor(r, g, b, 0);
            vc.addVertex(m, c * in - px, s * in - py, 0.0F).setColor(255, 240, 180, al);
        }
    }

    /** A filled circle (as a fan of quads) whose colour/alpha blend from the inner radius to the outer one. */
    private static void disc(VertexConsumer vc, org.joml.Matrix4f m, float r0, float r1, int ir, int ig, int ib, int or, int og, int ob, float a0, float a1) {
        int n = 32;
        int al0 = (int) (Mth.clamp(a0, 0.0F, 1.0F) * 255.0F), al1 = (int) (Mth.clamp(a1, 0.0F, 1.0F) * 255.0F);
        for (int i = 0; i < n; i++) {
            float t0 = i * Mth.TWO_PI / n, t1 = (i + 1) * Mth.TWO_PI / n;
            float c0 = Mth.cos(t0), s0 = Mth.sin(t0), c1 = Mth.cos(t1), s1 = Mth.sin(t1);
            vc.addVertex(m, c0 * r0, s0 * r0, 0.0F).setColor(ir, ig, ib, al0);
            vc.addVertex(m, c0 * r1, s0 * r1, 0.0F).setColor(or, og, ob, al1);
            vc.addVertex(m, c1 * r1, s1 * r1, 0.0F).setColor(or, og, ob, al1);
            vc.addVertex(m, c1 * r0, s1 * r0, 0.0F).setColor(ir, ig, ib, al0);
            vc.addVertex(m, c1 * r0, s1 * r0, 0.0F).setColor(ir, ig, ib, al0);
            vc.addVertex(m, c1 * r1, s1 * r1, 0.0F).setColor(or, og, ob, al1);
            vc.addVertex(m, c0 * r1, s0 * r1, 0.0F).setColor(or, og, ob, al1);
            vc.addVertex(m, c0 * r0, s0 * r0, 0.0F).setColor(ir, ig, ib, al0);
        }
    }

    /** A thin glowing ring, rotated by {@code deg}, with {@code marks} tick marks pointing outward. */
    private static void ring(VertexConsumer vc, org.joml.Matrix4f m, float r0, float r1, float deg, int r, int g, int b, float a, int marks, float markLen) {
        disc(vc, m, r0, r1, r, g, b, r, g, b, a, a);
        float rot = deg * Mth.DEG_TO_RAD;
        float w = 0.025F;
        int al = (int) (Mth.clamp(a, 0.0F, 1.0F) * 255.0F);
        for (int i = 0; i < marks; i++) {
            float t = rot + i * Mth.TWO_PI / marks;
            float c = Mth.cos(t), s = Mth.sin(t), px = -s * w, py = c * w;
            float in = r1, out = r1 + markLen * (i % 3 == 0 ? 1.0F : 0.55F); // longer marks at 12, 3, 6, 9
            vc.addVertex(m, c * in - px, s * in - py, 0.0F).setColor(r, g, b, al);
            vc.addVertex(m, c * out - px, s * out - py, 0.0F).setColor(r, g, b, al);
            vc.addVertex(m, c * out + px, s * out + py, 0.0F).setColor(r, g, b, al);
            vc.addVertex(m, c * in + px, s * in + py, 0.0F).setColor(r, g, b, al);
            vc.addVertex(m, c * in + px, s * in + py, 0.0F).setColor(r, g, b, al);
            vc.addVertex(m, c * out + px, s * out + py, 0.0F).setColor(r, g, b, al);
            vc.addVertex(m, c * out - px, s * out - py, 0.0F).setColor(r, g, b, al);
            vc.addVertex(m, c * in - px, s * in - py, 0.0F).setColor(r, g, b, al);
        }
    }

    /** A flat, double-sided glowing square (for additive glows). */
    private static void square(VertexConsumer vc, org.joml.Matrix4f m, float x, float y, float s, int r, int g, int b, float a) {
        int al = (int) (Mth.clamp(a, 0.0F, 1.0F) * 255.0F);
        if (al < 3) return;
        float h = s / 2.0F;
        vc.addVertex(m, x - h, y - h, 0.0F).setColor(r, g, b, al);
        vc.addVertex(m, x + h, y - h, 0.0F).setColor(r, g, b, al);
        vc.addVertex(m, x + h, y + h, 0.0F).setColor(r, g, b, al);
        vc.addVertex(m, x - h, y + h, 0.0F).setColor(r, g, b, al);
        vc.addVertex(m, x - h, y + h, 0.0F).setColor(r, g, b, al);
        vc.addVertex(m, x + h, y + h, 0.0F).setColor(r, g, b, al);
        vc.addVertex(m, x + h, y - h, 0.0F).setColor(r, g, b, al);
        vc.addVertex(m, x - h, y - h, 0.0F).setColor(r, g, b, al);
    }

    // ---- Requiem summoning ----

    /** How long the summoning effects play on after the Requiem has fully faded in (ticks). */
    private static final int REQ_SPAWN_TAIL = 22;
    private static final java.util.Map<Integer, Integer> REQ_SPAWN_TICK = new java.util.HashMap<>();

    /**
     * A Requiem arriving - each in its own way (only effects around it: its pose and fade-in are untouched):
     * <ul>
     *   <li>The Ender World: a giant golden clock face opens on the ground, its hands racing round... and stopping
     *   dead the moment it arrives, with a flash of negative light.</li>
     *   <li>GER: golden rings close in from far away and shrink down to nothing on it - returned to zero - then a
     *   ring of gold and emerald blasts back out.</li>
     *   <li>Pigstep: a giant amethyst music disc spins up under its feet, notes rising off it, and amethyst shards
     *   burst up out of the ground around it.</li>
     *   <li>Green Detonator: a jagged bolt of charged lightning cracks down onto the spot, with sparks and arcs.</li>
     *   <li>Wonder of Deep: no light at all - darkness and sculk spreading over the ground, souls drifting up.</li>
     * </ul>
     */
    private static void requiemSpawn(StandEntity stand, StandType type, float t, PoseStack ps, MultiBufferSource buffers) {
        float fade = StandEntity.fadeTicks(type);
        float end = fade + REQ_SPAWN_TAIL;
        float h = stand.getBbHeight();
        float size = Math.max(0.8F, stand.getBbWidth()) * 1.3F;
        int aura = StandHud.auraColor(type);
        float out = Mth.clamp((end - t) / 8.0F, 0.0F, 1.0F);
        boolean newTick = spawnTick(stand);
        var lv = stand.level();
        var rnd = lv.random;
        switch (type) {
            case ENDER_WORLD -> {
                int gold = 0xF2C744;
                float ck = Mth.clamp(t / 7.0F, 0.0F, 1.0F);
                float cr = size * (0.4F + 1.4F * (1.0F - (1.0F - ck) * (1.0F - ck) * (1.0F - ck)));
                ps.pushPose();
                flat(ps, 0.03F);
                ring(ps, buffers, cr, 0.06F, gold, 0.9F * out);
                ring(ps, buffers, cr * 0.93F, 0.02F, 0xFFFFFF, 0.5F * out);
                for (int i = 0; i < 12; i++) {
                    float a = Mth.TWO_PI * i / 12.0F;
                    ps.pushPose();
                    ps.translate(Mth.cos(a) * cr * 0.82F, Mth.sin(a) * cr * 0.82F, 0.0F);
                    disc(ps, buffers, i % 3 == 0 ? 0.14F : 0.08F, i % 3 == 0 ? 0xFFFFFF : gold, 0.85F * out);
                    ps.popPose();
                }
                // the hands race round... and stop dead when it arrives
                float spin = Math.min(t, fade);
                float k = Mth.clamp(spin / fade, 0.0F, 1.0F);
                float minute = (float) (Math.PI * 2.0D) * (3.0F * k - 1.2F * k * k);
                bar(ps, buffers, minute, cr * 0.75F, 0.06F, 0xFFFFFF, 0.9F * out);
                bar(ps, buffers, minute / 12.0F + 1.0F, cr * 0.45F, 0.09F, gold, 0.9F * out);
                disc(ps, buffers, 0.18F, 0xFFFFFF, 0.9F * out);
                disc(ps, buffers, cr, gold, 0.15F * out);
                ps.popPose();
                flash(ps, buffers, t, fade, h, 0x8C6CFF, 0xFFFFFF); // a flash of negative (violet) light
            }
            case GER -> {
                ps.pushPose();
                flat(ps, 0.04F);
                // golden rings closing in from far away, shrinking to nothing on it
                for (int i = 0; i < 4; i++) {
                    float k = Mth.clamp((t + i * 2.0F) / (fade + 6.0F), 0.0F, 1.0F);
                    if (k >= 1.0F) continue;
                    float r = size * 5.0F * (1.0F - k) * (1.0F - k);
                    ring(ps, buffers, r, 0.08F * (1.0F - k) + 0.02F, i % 2 == 0 ? 0xF2C744 : 0x3CFF8A, 0.85F * Mth.sin(k * Mth.PI));
                }
                // ...then it's at zero: a ring of gold and emerald blasts back out
                float sk = Mth.clamp((t - fade) / 12.0F, 0.0F, 1.0F);
                if (t >= fade && sk < 1.0F) {
                    float se = 1.0F - (1.0F - sk) * (1.0F - sk);
                    ring(ps, buffers, size * (0.5F + 3.2F * se), 0.22F * (1.0F - sk) + 0.03F, 0xF2C744, 0.9F * (1.0F - sk));
                    ring(ps, buffers, size * (0.3F + 2.4F * se), 0.1F * (1.0F - sk) + 0.02F, 0x3CFF8A, 0.8F * (1.0F - sk));
                }
                ps.popPose();
                // a column of gold standing on it while it forms
                float pk = Mth.clamp(t / fade, 0.0F, 1.0F);
                pillar(ps, buffers, 0.0F, h * 2.5F, size * 0.45F * pk * (1.0F - pk) * 4.0F * 0.5F, 0xF2C744, 0.5F * Mth.sin(pk * Mth.PI));
                flash(ps, buffers, t, fade, h, 0xF2C744, 0xFFFFFF);
                if (newTick && t < fade) {
                    for (int i = 0; i < 6; i++) {
                        double a = rnd.nextDouble() * Math.PI * 2.0D, rr = size * 4.0D * (1.0D - t / fade);
                        lv.addParticle(net.minecraft.core.particles.ParticleTypes.END_ROD, stand.getX() + Math.cos(a) * rr, stand.getY() + 0.1D,
                                stand.getZ() + Math.sin(a) * rr, -Math.cos(a) * 0.15D, 0.02D, -Math.sin(a) * 0.15D);
                    }
                }
            }
            case PIGLIN_REQUIEM -> {
                int am = 0xB46BFF;
                float dk = Mth.clamp(t / 6.0F, 0.0F, 1.0F);
                float dr = size * (0.3F + 1.2F * (1.0F - (1.0F - dk) * (1.0F - dk)));
                ps.pushPose();
                flat(ps, 0.03F);
                ps.mulPose(Axis.ZP.rotationDegrees(t * 14.0F)); // spinning like a record
                // the grooves, the label, the hole
                for (int i = 0; i < 4; i++) ring(ps, buffers, dr * (0.55F + i * 0.13F), 0.015F, 0xDDCCFF, 0.35F * out);
                ring(ps, buffers, dr, 0.05F, am, 0.9F * out);
                disc(ps, buffers, dr * 0.42F, am, 0.7F * out);
                ring(ps, buffers, dr * 0.42F, 0.03F, 0xF2C744, 0.8F * out);
                // a glint sweeping round
                ps.pushPose();
                ps.translate(dr * 0.75F, 0.0F, 0.0F);
                disc(ps, buffers, 0.22F, 0xFFFFFF, 0.6F * out);
                ps.popPose();
                ps.popPose();
                // amethyst shards bursting up round it
                float sk = Mth.clamp((t - 2.0F) / 6.0F, 0.0F, 1.0F);
                for (int i = 0; i < 7; i++) {
                    float a = Mth.TWO_PI * i / 7.0F + 0.4F;
                    float sh = (0.6F + 0.5F * ((i * 37) % 5) / 5.0F) * sk * h * 0.6F;
                    ps.pushPose();
                    ps.translate(Mth.cos(a) * size * 1.3F, 0.0F, Mth.sin(a) * size * 1.3F);
                    pillar(ps, buffers, 0.0F, sh, 0.12F * (1.0F - 0.3F * sk), am, 0.75F * out);
                    pillar(ps, buffers, 0.0F, sh * 0.8F, 0.04F, 0xFFFFFF, 0.7F * out);
                    ps.popPose();
                }
                flash(ps, buffers, t, fade, h, am, 0xFFFFFF);
                if (newTick && t < end - 6) {
                    // notes rising off the record
                    double a = rnd.nextDouble() * Math.PI * 2.0D, rr = size * rnd.nextDouble();
                    lv.addParticle(net.minecraft.core.particles.ParticleTypes.NOTE, stand.getX() + Math.cos(a) * rr, stand.getY() + 0.3D,
                            stand.getZ() + Math.sin(a) * rr, rnd.nextDouble(), 0.0D, 0.0D);
                }
            }
            case CREEPER_REQUIEM -> {
                int blue = 0x7FD4FF;
                // the bolt: a jagged line of charged light cracking down from the sky, flickering out
                float bk = Mth.clamp(t / (fade + 4.0F), 0.0F, 1.0F);
                if (bk < 1.0F) {
                    float flick = ((int) (t * 2.0F) % 3 == 0) ? 0.5F : 1.0F;
                    long seed = stand.getId() * 31L + (int) (t / 3.0F);
                    java.util.Random r = new java.util.Random(seed);
                    float y = 26.0F, x = 0.0F, z = 0.0F;
                    float a = (1.0F - bk) * flick;
                    while (y > 0.0F) {
                        float ny = Math.max(0.0F, y - 1.5F - r.nextFloat() * 1.5F);
                        float nx = ny <= 0.0F ? 0.0F : (r.nextFloat() - 0.5F) * 1.4F;
                        float nz = ny <= 0.0F ? 0.0F : (r.nextFloat() - 0.5F) * 1.4F;
                        bolt(ps, buffers, x, y, z, nx, ny, nz, 0.35F, blue, 0.45F * a);
                        bolt(ps, buffers, x, y, z, nx, ny, nz, 0.1F, 0xFFFFFF, 0.95F * a);
                        x = nx; y = ny; z = nz;
                    }
                }
                ps.pushPose();
                flat(ps, 0.04F);
                float sk = Mth.clamp(t / 10.0F, 0.0F, 1.0F);
                ring(ps, buffers, size * (0.4F + 2.6F * (1.0F - (1.0F - sk) * (1.0F - sk))), 0.18F * (1.0F - sk) + 0.03F, blue, 0.9F * (1.0F - sk));
                disc(ps, buffers, size * 1.2F, blue, 0.4F * (1.0F - sk));
                ps.popPose();
                flash(ps, buffers, t, 1.0F, h, blue, 0xFFFFFF);
                if (newTick && t < end - 4) {
                    for (int i = 0; i < 4; i++) lv.addParticle(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK,
                            stand.getX() + (rnd.nextDouble() - 0.5D) * size * 2.0D, stand.getY() + rnd.nextDouble() * h, stand.getZ() + (rnd.nextDouble() - 0.5D) * size * 2.0D,
                            (rnd.nextDouble() - 0.5D) * 0.3D, 0.05D, (rnd.nextDouble() - 0.5D) * 0.3D);
                }
            }
            case WARDEN_REQUIEM -> {
                // No light. Darkness and sculk spread across the ground; souls drift up out of it.
                ps.pushPose();
                flat(ps, 0.03F);
                float k = Mth.clamp(t / (fade + 8.0F), 0.0F, 1.0F);
                float r = size * (0.5F + 2.2F * (1.0F - (1.0F - k) * (1.0F - k)));
                ring(ps, buffers, r, 0.05F, 0x0F5A6B, 0.5F * out);
                ring(ps, buffers, r * 0.7F, 0.03F, 0x0A3A47, 0.4F * out);
                ps.popPose();
                if (newTick && t < end - 4) {
                    for (int i = 0; i < 5; i++) {
                        double a = rnd.nextDouble() * Math.PI * 2.0D, rr = r * rnd.nextDouble();
                        double px = stand.getX() + Math.cos(a) * rr, pz = stand.getZ() + Math.sin(a) * rr;
                        lv.addParticle(net.minecraft.core.particles.ParticleTypes.SQUID_INK, px, stand.getY() + 0.1D, pz, 0.0D, 0.01D, 0.0D);
                        if (i == 0) lv.addParticle(net.minecraft.core.particles.ParticleTypes.SCULK_SOUL, px, stand.getY() + 0.2D, pz, 0.0D, 0.04D, 0.0D);
                        if (i == 1) lv.addParticle(net.minecraft.core.particles.ParticleTypes.SCULK_CHARGE_POP, px, stand.getY() + 0.1D, pz, 0.0D, 0.0D, 0.0D);
                    }
                }
                return; // no flash, no motes of light
            }
            default -> { }
        }
        // Motes of its colour spiralling up round it.
        if (newTick) {
            org.joml.Vector3f col = new org.joml.Vector3f(((aura >> 16) & 0xFF) / 255.0F, ((aura >> 8) & 0xFF) / 255.0F, (aura & 0xFF) / 255.0F);
            var dust = new net.minecraft.core.particles.DustParticleOptions(col, 1.1F);
            for (int i = 0; i < 2; i++) {
                double a = stand.tickCount * 0.5D + i * Math.PI;
                double rr = size * (1.0D - Math.min(1.0D, t / end) * 0.5D);
                double y = (stand.tickCount * 0.12D + i * 0.4D) % Math.max(1.0D, h);
                lv.addParticle(dust, stand.getX() + Math.cos(a) * rr, stand.getY() + y, stand.getZ() + Math.sin(a) * rr, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    /** True once per game tick per Stand (so particles spawn per tick, not per frame). */
    private static boolean spawnTick(StandEntity stand) {
        Integer last = REQ_SPAWN_TICK.get(stand.getId());
        if (last != null && last == stand.tickCount) return false;
        REQ_SPAWN_TICK.put(stand.getId(), stand.tickCount);
        if (REQ_SPAWN_TICK.size() > 64) REQ_SPAWN_TICK.clear();
        return true;
    }

    /** Lays the pose flat on the ground (XY plane becomes the ground), just above it. */
    private static void flat(PoseStack ps, float lift) {
        ps.translate(0.0F, lift, 0.0F);
        ps.mulPose(Axis.XP.rotationDegrees(90.0F));
    }

    /** The flash as it arrives (bright for a moment round its body), in two colours. */
    private static void flash(PoseStack ps, MultiBufferSource buffers, float t, float at, float h, int outer, int inner) {
        float fk = (t - (at - 2.0F)) / 6.0F;
        if (fk <= 0.0F || fk >= 1.0F) return;
        ps.pushPose();
        ps.translate(0.0F, h * 0.55F, 0.0F);
        ps.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        float bell = Mth.sin(fk * Mth.PI);
        disc(ps, buffers, h * (0.6F + 0.6F * fk), outer, 0.6F * bell);
        disc(ps, buffers, h * (0.3F + 0.3F * fk), inner, 0.9F * bell);
        ps.popPose();
    }

    /** A clock hand in the XY plane: from the middle out at angle a (0 = twelve o'clock, clockwise). */
    private static void bar(PoseStack ps, MultiBufferSource buffers, float a, float len, float w, int rgb, float alpha) {
        if (alpha <= 0.01F) return;
        VertexConsumer vc = buffers.getBuffer(ModRenderTypes.glow());
        org.joml.Matrix4f m = ps.last().pose();
        int al = (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        float dx = Mth.sin(a), dy = Mth.cos(a), px = -dy * w * 0.5F, py = dx * w * 0.5F;
        float ex = dx * len, ey = dy * len;
        vc.addVertex(m, px, py, 0.0F).setColor(r, g, b, al);
        vc.addVertex(m, -px, -py, 0.0F).setColor(r, g, b, al);
        vc.addVertex(m, ex - px, ey - py, 0.0F).setColor(r, g, b, al);
        vc.addVertex(m, ex + px, ey + py, 0.0F).setColor(r, g, b, al);
        vc.addVertex(m, ex + px, ey + py, 0.0F).setColor(r, g, b, al);
        vc.addVertex(m, ex - px, ey - py, 0.0F).setColor(r, g, b, al);
        vc.addVertex(m, -px, -py, 0.0F).setColor(r, g, b, al);
        vc.addVertex(m, px, py, 0.0F).setColor(r, g, b, al);
    }

    /** One straight piece of a lightning bolt (crossed quads) between two points. */
    private static void bolt(PoseStack ps, MultiBufferSource buffers, float x0, float y0, float z0, float x1, float y1, float z1, float w, int rgb, float alpha) {
        if (alpha <= 0.01F) return;
        VertexConsumer vc = buffers.getBuffer(ModRenderTypes.glow());
        org.joml.Matrix4f m = ps.last().pose();
        int al = (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        for (int k = 0; k < 2; k++) {
            float dx = k == 0 ? w : 0.0F, dz = k == 0 ? 0.0F : w;
            vc.addVertex(m, x0 - dx, y0, z0 - dz).setColor(r, g, b, al);
            vc.addVertex(m, x0 + dx, y0, z0 + dz).setColor(r, g, b, al);
            vc.addVertex(m, x1 + dx, y1, z1 + dz).setColor(r, g, b, al);
            vc.addVertex(m, x1 - dx, y1, z1 - dz).setColor(r, g, b, al);
            vc.addVertex(m, x1 - dx, y1, z1 - dz).setColor(r, g, b, al);
            vc.addVertex(m, x1 + dx, y1, z1 + dz).setColor(r, g, b, al);
            vc.addVertex(m, x0 + dx, y0, z0 + dz).setColor(r, g, b, al);
            vc.addVertex(m, x0 - dx, y0, z0 - dz).setColor(r, g, b, al);
        }
    }

    /** A vertical beam of light (crossed quads, so it's round from any side) from y0 to y1, w wide. */
    private static void pillar(PoseStack ps, MultiBufferSource buffers, float y0, float y1, float w, int rgb, float alpha) {
        if (alpha <= 0.01F || w <= 0.001F || y1 <= y0) return;
        VertexConsumer vc = buffers.getBuffer(ModRenderTypes.glow());
        org.joml.Matrix4f m = ps.last().pose();
        int a = (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        for (int k = 0; k < 2; k++) {
            float dx = k == 0 ? w : 0.0F, dz = k == 0 ? 0.0F : w;
            // faint at the top, bright at the bottom
            vc.addVertex(m, -dx, y0, -dz).setColor(r, g, b, a);
            vc.addVertex(m, dx, y0, dz).setColor(r, g, b, a);
            vc.addVertex(m, dx, y1, dz).setColor(r, g, b, 0);
            vc.addVertex(m, -dx, y1, -dz).setColor(r, g, b, 0);
            vc.addVertex(m, -dx, y1, -dz).setColor(r, g, b, 0);
            vc.addVertex(m, dx, y1, dz).setColor(r, g, b, 0);
            vc.addVertex(m, dx, y0, dz).setColor(r, g, b, a);
            vc.addVertex(m, -dx, y0, -dz).setColor(r, g, b, a);
        }
    }

    // ---- smoothing ----

    /** Per Stand: where it was drawn last frame, how it's moving, and the offset applied this frame. */
    private static final class Smooth {
        double x, y, z, lx, ly, lz;
        float yaw, lyaw;
        long nanos, lastLunge;
        boolean init;
        double ox, oy, oz;
    }

    private static final java.util.Map<Integer, Smooth> SMOOTH = new java.util.HashMap<>();

    /**
     * Stands were jumpy: whenever one attacked it switched from following you on your own screen to following the
     * server for its lunge (and back again afterwards) - each switch a visible snap - and the server's positions
     * arrive in uneven steps, so lunges stuttered; its facing jumped at the same moments. This draws each Stand
     * through a smoothing layer:
     * - following you, it moves exactly with you (no lag), but any sudden jump is eased out instead of snapping;
     * - lunging (and just after), it glides smoothly through the server's uneven steps;
     * - its facing eases the same way.
     * Stands that sit on you (on your back or shoulder, ridden, piloted) are left exactly where they are.
     */
    private static Smooth smooth(StandEntity stand, StandType type, float partial) {
        Smooth s = SMOOTH.computeIfAbsent(stand.getId(), k -> new Smooth());
        if (SMOOTH.size() > 128) SMOOTH.keySet().removeIf(id -> stand.level().getEntity(id) == null);
        double tx = Mth.lerp(partial, stand.xo, stand.getX()), ty = Mth.lerp(partial, stand.yo, stand.getY()), tz = Mth.lerp(partial, stand.zo, stand.getZ());
        float tyaw = Mth.rotLerp(partial, stand.yRotO, stand.getYRot());
        long now = System.nanoTime();
        double dt = s.init ? Math.min(0.1D, (now - s.nanos) / 1.0E9D) : 0.0D;
        boolean attached = type == StandType.SPIDER || type == StandType.SHULKER || stand.isPiloting() || stand.isCartRide();
        boolean lunging = stand.isLunging();
        if (lunging) s.lastLunge = now;
        boolean afterLunge = (now - s.lastLunge) < 300_000_000L;
        double jump = Math.sqrt((tx - s.lx) * (tx - s.lx) + (ty - s.ly) * (ty - s.ly) + (tz - s.lz) * (tz - s.lz));
        if (!s.init || jump > 24.0D || (attached && !lunging)) {
            // First frame, a real teleport, or riding on you: exactly where it is.
            s.x = tx; s.y = ty; s.z = tz; s.yaw = tyaw;
        } else if (dt > 0.0D) {
            double pull;
            if (lunging || afterLunge) {
                // Lunging: glide after the server's steps.
                pull = 1.0D - Math.exp(-dt * 16.0D);
            } else {
                // Following: move with it exactly (unless this frame's move was a snap), then close any gap left.
                if (jump < 0.6D) {
                    s.x += tx - s.lx; s.y += ty - s.ly; s.z += tz - s.lz;
                }
                pull = 1.0D - Math.exp(-dt * 14.0D);
            }
            s.x += (tx - s.x) * pull;
            s.y += (ty - s.y) * pull;
            s.z += (tz - s.z) * pull;
            // Facing: turn with it, but ease out any sudden swing.
            float dyaw = Mth.wrapDegrees(tyaw - s.lyaw);
            if (Math.abs(dyaw) < 25.0F) s.yaw += dyaw;
            s.yaw += Mth.wrapDegrees(tyaw - s.yaw) * (float) (1.0D - Math.exp(-dt * 18.0D));
        }
        s.lx = tx; s.ly = ty; s.lz = tz; s.lyaw = tyaw;
        s.nanos = now;
        s.init = true;
        s.ox = s.x - tx;
        s.oy = s.y - ty;
        s.oz = s.z - tz;
        return s;
    }

    private static int scaleRgb(int rgb, float k) {
        return ((int) (((rgb >> 16) & 0xFF) * k) << 16) | ((int) (((rgb >> 8) & 0xFF) * k) << 8) | (int) ((rgb & 0xFF) * k);
    }

    private static final java.util.Map<Integer, Integer> GER_MOTE_TICK = new java.util.HashMap<>();

    /**
     * GER's aura, on top of the Requiem swirl: a second, bigger swirl of gold crawling the other way, a soft golden
     * glow breathing round its whole body, and gold and emerald motes drifting up off it.
     */
    private static void gerAura(StandEntity stand, StandVisual vis, float partial, float eased, float cy, PoseStack ps, MultiBufferSource buffers, int light) {
        float t = stand.tickCount + partial;
        float breathe = 0.5F + 0.5F * Mth.sin(t * 0.09F);
        // The outer swirl: bigger, gold, crawling the other way.
        float scroll = t * 0.006F;
        VertexConsumer swirl = buffers.getBuffer(RenderType.energySwirl(REQUIEM_SWIRL, (-scroll) % 1.0F + 1.0F, scroll % 1.0F));
        ps.pushPose();
        ps.translate(0.0F, cy, 0.0F);
        float sc = 1.11F + 0.02F * breathe;
        ps.scale(sc, sc * 0.98F, sc);
        ps.translate(0.0F, -cy, 0.0F);
        vis.main.renderToBuffer(ps, swirl, light, OverlayTexture.NO_OVERLAY, ((int) (255.0F * eased) << 24) | scaleRgb(0xF2C744, 0.35F + 0.15F * breathe));
        ps.popPose();
        // A soft golden glow round the whole body.
        glow(ps, buffers, vis.main, (0.1F + 0.08F * breathe) * eased, 1.05F + 0.03F * breathe, 0xF2C744, cy, true);
        // Motes drifting up off it (a few each tick).
        Integer last = GER_MOTE_TICK.get(stand.getId());
        if (eased > 0.6F && (last == null || last != stand.tickCount)) {
            GER_MOTE_TICK.put(stand.getId(), stand.tickCount);
            if (GER_MOTE_TICK.size() > 64) GER_MOTE_TICK.clear();
            var lv = stand.level();
            var rnd = lv.random;
            double w = stand.getBbWidth() * 0.6D, h = stand.getBbHeight();
            for (int i = 0; i < 2; i++) {
                boolean gold = rnd.nextBoolean();
                var dust = new net.minecraft.core.particles.DustParticleOptions(gold ? new org.joml.Vector3f(1.0F, 0.83F, 0.27F) : new org.joml.Vector3f(0.24F, 1.0F, 0.54F), 0.8F);
                lv.addParticle(dust, stand.getX() + (rnd.nextDouble() - 0.5D) * w * 2.0D, stand.getY() + rnd.nextDouble() * h, stand.getZ() + (rnd.nextDouble() - 0.5D) * w * 2.0D, 0.0D, 0.03D, 0.0D);
            }
            if (rnd.nextInt(4) == 0) lv.addParticle(net.minecraft.core.particles.ParticleTypes.END_ROD, stand.getX() + (rnd.nextDouble() - 0.5D) * w * 2.0D,
                    stand.getY() + rnd.nextDouble() * h * 0.6D, stand.getZ() + (rnd.nextDouble() - 0.5D) * w * 2.0D, 0.0D, 0.025D, 0.0D);
        }
    }

    /** A flat ring (annulus) in the XY plane of the current pose: radius r, band width w. Additive glow. */
    static void ring(PoseStack ps, MultiBufferSource buffers, float r, float w, int rgb, float alpha) {
        if (alpha <= 0.01F || r <= 0.0F) return;
        VertexConsumer vc = buffers.getBuffer(ModRenderTypes.glow());
        org.joml.Matrix4f m = ps.last().pose();
        int a = (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        int cr = (rgb >> 16) & 0xFF, cg = (rgb >> 8) & 0xFF, cb = rgb & 0xFF;
        float ri = Math.max(0.0F, r - w), ro = r + w * 0.4F;
        final int seg = 32;
        for (int i = 0; i < seg; i++) {
            float a0 = Mth.TWO_PI * i / seg, a1 = Mth.TWO_PI * (i + 1) / seg;
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            // inner (faint) -> middle (bright) -> outer (faint): a soft-edged band, both sides
            float rm = (ri + ro) * 0.5F;
            for (int side = 0; side < 2; side++) {
                float[][] q = side == 0
                        ? new float[][]{{ri, 0}, {rm, a}}
                        : new float[][]{{rm, a}, {ro, 0}};
                float rA = q[0][0], rB = q[1][0];
                int aA = (int) q[0][1], aB = (int) q[1][1];
                vc.addVertex(m, c0 * rA, s0 * rA, 0.0F).setColor(cr, cg, cb, aA);
                vc.addVertex(m, c1 * rA, s1 * rA, 0.0F).setColor(cr, cg, cb, aA);
                vc.addVertex(m, c1 * rB, s1 * rB, 0.0F).setColor(cr, cg, cb, aB);
                vc.addVertex(m, c0 * rB, s0 * rB, 0.0F).setColor(cr, cg, cb, aB);
                vc.addVertex(m, c0 * rB, s0 * rB, 0.0F).setColor(cr, cg, cb, aB);
                vc.addVertex(m, c1 * rB, s1 * rB, 0.0F).setColor(cr, cg, cb, aB);
                vc.addVertex(m, c1 * rA, s1 * rA, 0.0F).setColor(cr, cg, cb, aA);
                vc.addVertex(m, c0 * rA, s0 * rA, 0.0F).setColor(cr, cg, cb, aA);
            }
        }
    }

    /** A soft round glow (bright middle fading to nothing at the edge) in the XY plane of the current pose. */
    static void disc(PoseStack ps, MultiBufferSource buffers, float r, int rgb, float alpha) {
        if (alpha <= 0.01F || r <= 0.0F) return;
        VertexConsumer vc = buffers.getBuffer(ModRenderTypes.glow());
        org.joml.Matrix4f m = ps.last().pose();
        int a = (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        int cr = (rgb >> 16) & 0xFF, cg = (rgb >> 8) & 0xFF, cb = rgb & 0xFF;
        final int seg = 24;
        for (int i = 0; i < seg; i++) {
            float a0 = Mth.TWO_PI * i / seg, a1 = Mth.TWO_PI * (i + 1) / seg;
            float x0 = Mth.cos(a0) * r, y0 = Mth.sin(a0) * r, x1 = Mth.cos(a1) * r, y1 = Mth.sin(a1) * r;
            vc.addVertex(m, 0.0F, 0.0F, 0.0F).setColor(cr, cg, cb, a);
            vc.addVertex(m, 0.0F, 0.0F, 0.0F).setColor(cr, cg, cb, a);
            vc.addVertex(m, x0, y0, 0.0F).setColor(cr, cg, cb, 0);
            vc.addVertex(m, x1, y1, 0.0F).setColor(cr, cg, cb, 0);
            vc.addVertex(m, x1, y1, 0.0F).setColor(cr, cg, cb, 0);
            vc.addVertex(m, x0, y0, 0.0F).setColor(cr, cg, cb, 0);
            vc.addVertex(m, 0.0F, 0.0F, 0.0F).setColor(cr, cg, cb, a);
            vc.addVertex(m, 0.0F, 0.0F, 0.0F).setColor(cr, cg, cb, a);
        }
    }

    private static final ResourceLocation REQUIEM_AURA = ResourceLocation.fromNamespaceAndPath("mobstands", "textures/misc/requiem_aura.png");
    private static final int AURA_FRAMES = 16;

    /**
     * Requiem aura: an animated, blocky flame (the classic Minecraft-animation aura) burning up behind the Stand in
     * its colour, so it licks out around its whole silhouette. Always faces the camera.
     */
    private static void requiemFlames(StandEntity stand, StandType type, float partial, PoseStack ps, MultiBufferSource buffers) {
        float fade = Mth.clamp((stand.tickCount + partial) / 12.0F, 0.0F, 1.0F) * (1.0F - stand.dismissProgress(partial));
        if (fade <= 0.02F) return;
        float scale = (float) type.scale();
        float h = 3.7F * scale, w = h * 32.0F / 48.0F; // the sprite is 32x48 pixels
        int frame = (int) ((stand.tickCount + partial) / 2.0F) % AURA_FRAMES;
        float v0 = frame / (float) AURA_FRAMES, v1 = (frame + 1) / (float) AURA_FRAMES;
        int rgb = StandHud.auraColor(type);
        int color = ((int) (fade * 0.5F * 255.0F) << 24) | (rgb & 0xFFFFFF); // see-through, in the Stand's own colour
        ps.pushPose();
        ps.translate(0.0F, h * 0.5F - 0.15F, 0.0F);
        ps.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        ps.translate(0.0F, 0.0F, -0.55F); // just behind the Stand, so it burns out around its edges
        PoseStack.Pose pose = ps.last();
        org.joml.Matrix4f m = pose.pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucentEmissive(REQUIEM_AURA));
        float hw = w / 2.0F, hh = h / 2.0F;
        // front
        vc.addVertex(m, -hw, -hh, 0.0F).setColor(color).setUv(0.0F, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 0.0F, 1.0F);
        vc.addVertex(m, hw, -hh, 0.0F).setColor(color).setUv(1.0F, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 0.0F, 1.0F);
        vc.addVertex(m, hw, hh, 0.0F).setColor(color).setUv(1.0F, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 0.0F, 1.0F);
        vc.addVertex(m, -hw, hh, 0.0F).setColor(color).setUv(0.0F, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 0.0F, 1.0F);
        // back (in case it ends up facing the other way)
        vc.addVertex(m, -hw, hh, 0.0F).setColor(color).setUv(0.0F, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 0.0F, -1.0F);
        vc.addVertex(m, hw, hh, 0.0F).setColor(color).setUv(1.0F, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 0.0F, -1.0F);
        vc.addVertex(m, hw, -hh, 0.0F).setColor(color).setUv(1.0F, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 0.0F, -1.0F);
        vc.addVertex(m, -hw, -hh, 0.0F).setColor(color).setUv(0.0F, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 0.0F, -1.0F);
        ps.popPose();
    }

    /** Whole-model aura shell (scaled around the body centre), additive and see-through like an aura. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void glow(PoseStack ps, MultiBufferSource buffers, net.minecraft.client.model.EntityModel model,
                             float alpha, float scale, int rgb, float centerY, boolean whole) {
        if (alpha <= 0.01F) return;
        ps.pushPose();
        ps.translate(0.0F, centerY, 0.0F);
        ps.scale(scale, scale, scale);
        ps.translate(0.0F, -centerY, 0.0F);
        StandVisual vis = MODEL_OWNERS.get(model);
        if (vis != null) vis.hideForGlow(true);
        model.renderToBuffer(ps, buffers.getBuffer(ModRenderTypes.glow()), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                ((int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24) | (rgb & 0xFFFFFF));
        if (vis != null) vis.hideForGlow(false);
        ps.popPose();
    }

    /**
     * Impact aura: at the spot the fist landed, a glowing copy of the arm flashes and expands while fading away.
     * {@code dt} = ticks since the hit; {@code reach}/{@code size} = the fist's full extension and scale.
     */
    private static void impactBurst(PoseStack ps, MultiBufferSource buffers, StandVisual.ArmPiece arm,
                                    float dt, float side, float reach, float size, int rgb) {
        if (dt < 0.0F || dt > 8.0F || arm == null) return;
        float k = dt / 8.0F;
        ps.pushPose();
        ps.translate(side, 0.35F, -(0.3F + reach));
        ps.scale(size, size, size);
        armGlow(ps, buffers, arm, 0.7F * (1.0F - k) * (1.0F - k), 1.2F + k * 0.9F, rgb);
        armGlow(ps, buffers, arm, 0.35F * (1.0F - k), 1.1F + k * 0.4F, 0xFFFFFF);
        ps.popPose();
    }

    // ---- Regular punch ----

    /**
     * One punch, {@code dt} ticks from the moment it lands: the main ghost fist rockets out of the shoulder with
     * motion-blur afterimages, two smaller fists chase it in, it lands with a pop and a flash of light, and a ring
     * of aura blows outward.
     */
    private static void drawPunch(PoseStack ps, MultiBufferSource buffers, StandVisual vis, float dt, float side,
                                  int armSide, int seed, int light, int aura) {
        if (dt < -3.0F || dt > 7.0F) return;
        StandVisual.ArmPiece arm = vis.arm(armSide == 1);
        float tx = side * 0.25F, ty = 0.34F, tz = -2.45F;
        // Two smaller fists fired just ahead of the main one, converging on the same spot (the MUDA flavour).
        for (int j = 0; j < 2; j++) {
            float s = -3.0F + j * 1.4F;
            float u = (dt - s) / 5.0F;
            if (u <= 0.0F || u >= 1.0F) continue;
            float e = Mth.sin(u * Mth.PI);
            float fade = u < 0.5F ? 1.0F : 1.0F - (u - 0.5F) * 2.0F;
            float ox = side + (j == 0 ? -0.18F : 0.18F), oy = 0.3F + (j == 0 ? 0.12F : -0.1F);
            float px = tx + (j == 0 ? -0.22F : 0.2F) + (rand(seed + j, 4) - 0.5F) * 0.15F;
            float py = ty + (j == 0 ? 0.18F : -0.14F);
            ps.pushPose();
            punchPose(ps, ox, oy, -0.2F, px, py, tz + 0.25F, e, (seed + j) * 53.0F + e * 40.0F, (0.7F + 0.3F * e) * (float) Math.sqrt(fade));
            StandVisual.ArmPiece a2 = vis.arm((j == 1) != (armSide == 1));
            if (fade > 0.3F) drawArm(ps, buffers.getBuffer(RenderType.entityCutoutNoCull(vis.texture)), a2, light, 0xFFFFFFFF, 1.0F);
            armGlow(ps, buffers, a2, 0.32F * fade, 1.18F, aura);
            ps.popPose();
        }
        // The main fist: driven out smoothly over the 3 ticks before impact (like the arm), then eases back and fades.
        float e, pop = 0.0F, fade = 1.0F;
        if (dt < -3.0F) e = 0.0F;
        else if (dt < 0.0F) {
            float x = (dt + 3.0F) / 3.0F;
            e = x * x * (3.0F - 2.0F * x);
        } else {
            pop = Mth.sin(Mth.clamp(dt / 2.5F, 0.0F, 1.0F) * Mth.PI); // swells a little on impact
            float k = Mth.clamp((dt - 1.0F) / 5.0F, 0.0F, 1.0F);
            k = k * k * (3.0F - 2.0F * k);
            e = 1.0F - 0.5F * k;
            fade = 1.0F - k;
        }
        if (dt >= -3.0F && fade > 0.0F) {
            // Afterimages trailing it while it flies.
            if (dt > -2.5F && dt < 1.0F) {
                float trail = dt < 0.0F ? 1.0F : 1.0F - dt;
                for (int g = 3; g >= 1; g--) {
                    float x = Mth.clamp((dt - g * 0.35F + 3.0F) / 3.0F, 0.0F, 1.0F);
                    if (x <= 0.0F) continue;
                    float eg = x * x * (3.0F - 2.0F * x);
                    ps.pushPose();
                    punchPose(ps, side, 0.36F, -0.2F, tx, ty, tz, eg, seed * 31.0F + eg * 25.0F, 1.2F);
                    ps.scale(1.0F, 1.0F, 1.3F);
                    armGlow(ps, buffers, arm, (0.3F / g) * trail, 1.12F, aura);
                    ps.popPose();
                }
            }
            ps.pushPose();
            punchPose(ps, side, 0.36F, -0.2F, tx, ty, tz, e, seed * 31.0F + e * 25.0F, (1.2F + 0.15F * pop) * (float) Math.sqrt(fade));
            if (fade > 0.3F) drawArm(ps, buffers.getBuffer(RenderType.entityCutoutNoCull(vis.texture)), arm, light, 0xFFFFFFFF, 1.0F);
            armGlow(ps, buffers, arm, (0.4F + 0.25F * pop) * fade, 1.16F + 0.08F * pop, aura);
            armGlow(ps, buffers, arm, 0.18F * fade, 1.55F, aura);
            ps.popPose();
        }
        // Impact: a white-hot flash at the knuckles, then a wide ring of aura blowing outward.
        if (dt >= 0.0F && dt < 5.0F) {
            float k = dt / 5.0F;
            ps.pushPose();
            punchPose(ps, side, 0.36F, -0.2F, tx, ty, tz, 1.0F, seed * 31.0F, 1.2F);
            armGlow(ps, buffers, arm, 0.75F * (1.0F - k) * (1.0F - k), 1.25F + k * 0.8F, 0xFFFFFF);
            armGlow(ps, buffers, arm, 0.45F * (1.0F - k), 1.6F + k * 2.2F, aura);
            ps.popPose();
        }
        impactBurst(ps, buffers, arm, dt * 1.2F, tx, 2.15F, 1.9F, aura);
    }

    /**
     * GER's punches land with rings of light - a golden ring and a thin white one chasing it, blasting outward
     * round the fist, flat to the punch, like the blow is being returned to zero - and an emerald ring behind.
     */
    private static void zeroRings(PoseStack ps, MultiBufferSource buffers, float dt, float x) {
        if (dt < 0.0F || dt > 10.0F) return;
        ps.pushPose();
        ps.translate(x, 0.34F, -2.45F);
        for (int i = 0; i < 3; i++) {
            float d = dt - i * 1.3F;
            if (d < 0.0F) continue;
            float k = Mth.clamp(d / 8.0F, 0.0F, 1.0F);
            float e = 1.0F - (1.0F - k) * (1.0F - k) * (1.0F - k);
            float fade = (1.0F - k) * (1.0F - k);
            int rgb = i == 0 ? 0xF2C744 : i == 1 ? 0xFFFFFF : 0x3CFF8A;
            ring(ps, buffers, 0.15F + e * (1.6F - i * 0.25F), 0.16F * (1.0F - k) + 0.03F, rgb, (i == 1 ? 0.6F : 0.85F) * fade);
        }
        // a hot flash at the centre for a moment
        disc(ps, buffers, 0.5F + dt * 0.08F, 0xFFF4C0, 0.7F * Math.max(0.0F, 1.0F - dt / 3.0F));
        ps.popPose();
    }

    /** Places a punch fist on its flight from (ox,oy,oz) to (tx,ty,tz), aimed along its path. */
    private static void punchPose(PoseStack ps, float ox, float oy, float oz, float tx, float ty, float tz, float e, float spin, float scale) {
        float dx = tx - ox, dy = ty - oy, dz = tz - oz;
        ps.translate(Mth.lerp(e, ox, tx), Mth.lerp(e, oy, ty), Mth.lerp(e, oz, tz));
        ps.mulPose(Axis.YP.rotation((float) -Math.atan2(dx, -dz)));
        ps.mulPose(Axis.XP.rotation((float) Math.atan2(dy, -dz)));
        ps.mulPose(Axis.ZP.rotationDegrees(spin));
        ps.scale(scale, scale, scale);
    }

    // ---- Barrage ----

    /** A new fist flies out this often (ticks), alternating sides; each one lives this long. */
    private static final float BARRAGE_GAP = 0.6F, BARRAGE_LIFE = 5.0F;
    /** Fraction of a fist's life spent flying out (the rest it's snapping back and fading). */
    private static final float BARRAGE_OUT = 0.5F;

    private static float rand(int n, int salt) {
        return Mth.frac(Mth.sin(n * 12.9898F + salt * 78.233F) * 43758.547F);
    }

    /** One flurry fist: where it's thrown from and to, how far out it is, and how it's turned. */
    private static void barrageFist(PoseStack ps, int n, float e, float scale) {
        float side = n % 2 == 0 ? -1.0F : 1.0F;
        // Thrown from all round the shoulders, landing all over a wide area in front (not all in one spot).
        float ox = side * (0.28F + rand(n, 5) * 0.3F), oy = 0.38F + (rand(n, 6) - 0.5F) * 0.35F, oz = -0.15F;
        float tx = side * 0.2F + (rand(n, 1) - 0.5F) * 1.6F;
        float ty = 0.35F + (rand(n, 2) - 0.5F) * 1.25F;
        float tz = -(1.8F + rand(n, 3) * 1.0F);
        float dx = tx - ox, dy = ty - oy, dz = tz - oz;
        ps.translate(Mth.lerp(e, ox, tx), Mth.lerp(e, oy, ty), Mth.lerp(e, oz, tz));
        // Aimed along its own path, so the fists converge on the target like real punches.
        ps.mulPose(Axis.YP.rotation((float) -Math.atan2(dx, -dz)));
        ps.mulPose(Axis.XP.rotation((float) Math.atan2(dy, -dz)));
        ps.mulPose(Axis.ZP.rotationDegrees(n * 47.0F + e * 35.0F));
        float sc = (0.8F + e * 0.45F) * scale;
        ps.scale(sc, sc, sc);
    }

    /** How far out a flurry fist is (0..1) at life fraction u: a smooth arc out and back, like the original fists. */
    private static float barrageReach(float u) {
        return Mth.sin(Mth.clamp(u, 0.0F, 1.0F) * Mth.PI);
    }

    /**
     * The Barrage: a storm of fists converging on the spot in front of the Stand, each trailing motion-blur
     * afterimages and flashing where it lands; then a beat of build-up and one huge finishing fist with a
     * shockwave of light.
     */
    private static void drawBarrage(PoseStack ps, MultiBufferSource buffers, StandVisual vis, float el, int light, int aura) {
        float fin = com.mobstands.stand.StandAbilities.BARRAGE_TICKS - 1;
        float flurryEnd = fin - 5.0F;
        int first = Math.max(0, (int) ((el - BARRAGE_LIFE - 3.0F) / BARRAGE_GAP));
        int last = (int) ((Math.min(el, flurryEnd) - 1.0F) / BARRAGE_GAP);
        for (int n = first; n <= last; n++) {
            float start = 1.0F + n * BARRAGE_GAP;
            StandVisual.ArmPiece arm = vis.arm(n % 2 == 1);
            // Afterimages: fading glow copies a moment behind, stretched along the punch.
            for (int g = 2; g >= 1; g--) {
                float u = (el - start - g * 0.28F) / BARRAGE_LIFE;
                if (u <= 0.0F || u >= BARRAGE_OUT + 0.15F) continue; // only while it's flying out
                float e = barrageReach(u);
                ps.pushPose();
                barrageFist(ps, n, e, 1.0F);
                ps.scale(1.0F, 1.0F, 1.35F);
                armGlow(ps, buffers, arm, (0.3F / g) * (1.0F - u), 1.12F, aura);
                ps.popPose();
            }
            // The fist itself.
            float u = (el - start) / BARRAGE_LIFE;
            if (u > 0.0F && u < 1.0F) {
                float e = barrageReach(u);
                float fade = u < BARRAGE_OUT ? 1.0F : 1.0F - (u - BARRAGE_OUT) / (1.0F - BARRAGE_OUT);
                ps.pushPose();
                barrageFist(ps, n, e, (float) Math.sqrt(fade));
                if (fade > 0.25F) {
                    // Fetch the buffer right before drawing: drawing a glow in between switches buffers and closes the old one.
                    drawArm(ps, buffers.getBuffer(RenderType.entityCutoutNoCull(vis.texture)), arm, light, 0xFFFFFFFF, 1.0F);
                }
                armGlow(ps, buffers, arm, 0.38F * fade, 1.16F, aura);
                armGlow(ps, buffers, arm, 0.16F * fade, 1.5F, aura);
                ps.popPose();
            }
            // Impact flash where it lands.
            float dt = el - (start + BARRAGE_OUT * BARRAGE_LIFE);
            if (dt >= 0.0F && dt < 3.0F) {
                float k = dt / 3.0F;
                ps.pushPose();
                barrageFist(ps, n, 1.0F, 1.0F);
                armGlow(ps, buffers, arm, 0.55F * (1.0F - k) * (1.0F - k), 1.3F + k * 1.1F, 0xFFFFFF);
                armGlow(ps, buffers, arm, 0.35F * (1.0F - k), 1.7F + k * 1.6F, aura);
                ps.popPose();
            }
        }

        // The finisher: the right fist charges up, then one huge punch.
        StandVisual.ArmPiece big = vis.arm(false);
        if (el >= flurryEnd) {
            float x = -0.3F, y = 0.36F;
            if (el < fin) {
                // Charging: a fist of light gathering at the shoulder, pulsing faster and brighter.
                float u = (el - flurryEnd) / (fin - flurryEnd);
                float throb = 0.75F + 0.25F * Mth.sin(el * (6.0F + 10.0F * u));
                ps.pushPose();
                ps.translate(x, y, 0.15F * u);
                float sc = 1.0F + 0.5F * u;
                ps.scale(sc, sc, sc);
                armGlow(ps, buffers, big, 0.25F + 0.45F * u * throb, 1.2F + 0.4F * u * throb, aura);
                armGlow(ps, buffers, big, 0.3F * u * u, 1.05F, 0xFFFFFF);
                ps.popPose();
            } else {
                float after = el - fin;
                float hit = Mth.clamp(after / 1.5F, 0.0F, 1.0F);
                float e = hit * hit * (3.0F - 2.0F * hit);
                float fade = Mth.clamp(1.0F - (after - 2.0F) / 4.0F, 0.0F, 1.0F);
                if (fade > 0.0F) {
                    // Afterimages of the big punch.
                    for (int g = 3; g >= 1; g--) {
                        float eg = Mth.clamp((after - g * 0.3F) / 1.5F, 0.0F, 1.0F);
                        if (eg <= 0.0F || after > 2.2F) continue;
                        eg = eg * eg * (3.0F - 2.0F * eg);
                        ps.pushPose();
                        ps.translate(x * (1.0F - eg), y, 0.15F - eg * 2.75F);
                        ps.scale(1.8F, 1.8F, 1.8F * 1.4F);
                        armGlow(ps, buffers, big, 0.35F / g, 1.15F, aura);
                        ps.popPose();
                    }
                    ps.pushPose();
                    ps.translate(x * (1.0F - e), y, 0.15F - e * 2.75F);
                    float sc = 1.8F * (float) Math.sqrt(fade);
                    ps.scale(sc, sc, sc);
                    if (fade > 0.3F) drawArm(ps, buffers.getBuffer(RenderType.entityCutoutNoCull(vis.texture)), big, light, 0xFFFFFFFF, 1.0F);
                    armGlow(ps, buffers, big, 0.5F * fade, 1.18F, aura);
                    armGlow(ps, buffers, big, 0.25F * fade, 1.6F, aura);
                    ps.popPose();
                }
                // The shockwave of light where it lands: a white flash, then a big ring of aura blowing outward.
                if (after >= 1.2F) {
                    impactBurst(ps, buffers, big, (after - 1.2F) * 1.2F, 0.0F, 2.6F, 2.6F, aura);
                    impactBurst(ps, buffers, big, (after - 1.2F) * 0.8F, 0.0F, 2.6F, 4.2F, aura);
                }
            }
        }
    }

    /** Horse Stand: just the horse's head, sitting on top of the player's head and turning with it. */
    private void renderHorseHead(StandEntity stand, StandVisual vis, float partial, PoseStack ps, MultiBufferSource buffers, int light) {
        net.minecraft.client.model.geom.ModelPart head = vis.horseHead();
        if (head == null) return;
        Player owner = stand.getOwnerPlayer();
        // Your own horse head would sit right on the camera in first person: only others (and third person) see it.
        if (owner == Minecraft.getInstance().player && Minecraft.getInstance().options.getCameraType().isFirstPerson()) return;
        float yaw = owner != null ? Mth.rotLerp(partial, owner.yHeadRotO, owner.getYHeadRot()) : Mth.rotLerp(partial, stand.yRotO, stand.getYRot());
        float age = stand.tickCount + partial;
        float fade = Mth.clamp(age / (float) StandEntity.FADE_TICKS, 0.0F, 1.0F);
        float out = stand.dismissProgress(partial);
        float alpha = fade * (1.0F - out);
        if (alpha <= 0.01F) return;
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
        ps.scale(-1.0F, -1.0F, 1.0F);
        float sc = (float) StandType.HORSE.scale();
        ps.scale(sc, sc, sc);
        ps.translate(0.0F, 6.0F / 16.0F, 0.0F); // bottom of the horse head rests on the player's head
        float px = head.x, py = head.y, pz = head.z, rx = head.xRot, ry = head.yRot, rz = head.zRot;
        head.setPos(0.0F, 0.0F, 1.0F);
        head.setRotation(0.0F, 0.0F, 0.0F);
        RenderType rt = alpha < 0.999F ? RenderType.entityTranslucent(vis.texture) : RenderType.entityCutoutNoCull(vis.texture);
        head.render(ps, buffers.getBuffer(rt), light, OverlayTexture.NO_OVERLAY, ((int) (alpha * 255.0F) << 24) | 0xFFFFFF);
        head.setPos(px, py, pz);
        head.setRotation(rx, ry, rz);
        ps.popPose();
        if (owner != null) renderHorseTail(owner, vis, partial, age, ps, buffers, light, alpha);
    }

    /** Horse Stand: a horse tail on your lower back that swishes, and lifts as you run. */
    private void renderHorseTail(Player owner, StandVisual vis, float partial, float age, PoseStack ps, MultiBufferSource buffers,
                                 int light, float alpha) {
        net.minecraft.client.model.geom.ModelPart tail = vis.horseTail();
        if (tail == null) return;
        float bodyYaw = Mth.rotLerp(partial, owner.yBodyRotO, owner.yBodyRot);
        float speed = Mth.clamp(owner.walkAnimation.speed(partial), 0.0F, 1.0F);
        ps.pushPose();
        // The Stand sits on top of the head; the tail hangs from the lower back.
        float down = owner.getBbHeight() + 0.02F - (owner.isCrouching() ? 0.62F : 0.78F);
        ps.translate(0.0F, -down, 0.0F);
        ps.mulPose(Axis.YP.rotationDegrees(180.0F - bodyYaw));
        ps.translate(0.0F, 0.0F, owner.isCrouching() ? 0.3F : 0.15F); // behind you (+z is behind here)
        ps.scale(-1.0F, -1.0F, 1.0F);
        ps.scale(0.7F, 0.7F, 0.7F);
        float px = tail.x, py = tail.y, pz = tail.z, rx = tail.xRot, ry = tail.yRot, rz = tail.zRot;
        tail.setPos(0.0F, 0.0F, -1.0F);
        tail.setRotation(0.45F + speed * 0.7F + Mth.sin(age * 0.11F) * 0.05F, 0.0F, Mth.sin(age * 0.17F) * (0.12F + speed * 0.1F));
        RenderType rt = alpha < 0.999F ? RenderType.entityTranslucent(vis.texture) : RenderType.entityCutoutNoCull(vis.texture);
        tail.render(ps, buffers.getBuffer(rt), light, OverlayTexture.NO_OVERLAY, ((int) (alpha * 255.0F) << 24) | 0xFFFFFF);
        tail.setPos(px, py, pz);
        tail.setRotation(rx, ry, rz);
        ps.popPose();
    }

    /** One thin crescent blade (tapered at both ends), drawn double-sided and fully bright. */
    private static void drawCrescent(PoseStack ps, MultiBufferSource buffers, float radius, float width, float sweep, int rgb, float alpha) {
        if (alpha <= 0.01F || sweep <= 0.01F) return;
        VertexConsumer vc = buffers.getBuffer(ModRenderTypes.glow());
        org.joml.Matrix4f m = ps.last().pose();
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255, a = (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        final int segs = 14;
        float arc = 2.2F; // radians of the full blade
        float from = -arc / 2.0F;
        float to = from + arc * sweep;
        for (int s = 0; s < segs; s++) {
            float u0 = s / (float) segs, u1 = (s + 1) / (float) segs;
            float a0 = Mth.lerp(u0, from, to), a1 = Mth.lerp(u1, from, to);
            // Taper: sharp at the tail and at the tip of the blade.
            float w0 = width * Mth.sin(Mth.PI * u0);
            float w1 = width * Mth.sin(Mth.PI * u1);
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            float ix0 = c0 * (radius - w0), iy0 = s0 * (radius - w0), ox0 = c0 * (radius + w0), oy0 = s0 * (radius + w0);
            float ix1 = c1 * (radius - w1), iy1 = s1 * (radius - w1), ox1 = c1 * (radius + w1), oy1 = s1 * (radius + w1);
            vc.addVertex(m, ix0, iy0, 0.0F).setColor(r, g, b, a);
            vc.addVertex(m, ox0, oy0, 0.0F).setColor(r, g, b, a);
            vc.addVertex(m, ox1, oy1, 0.0F).setColor(r, g, b, a);
            vc.addVertex(m, ix1, iy1, 0.0F).setColor(r, g, b, a);
            // back face
            vc.addVertex(m, ix1, iy1, 0.0F).setColor(r, g, b, a);
            vc.addVertex(m, ox1, oy1, 0.0F).setColor(r, g, b, a);
            vc.addVertex(m, ox0, oy0, 0.0F).setColor(r, g, b, a);
            vc.addVertex(m, ix0, iy0, 0.0F).setColor(r, g, b, a);
        }
    }

    /**
     * Allay strike: two more Allays rush out from either side of yours and ram the target in front, one after the
     * other (right, then left), then bounce off and fade. Drawn in the Stand's model space (feet at y = 1.5,
     * forward = -z, before the -1 flip... i.e. "up" is -y here).
     */
    private static void renderAllayRush(StandEntity stand, StandVisual vis, int slot, float progress, float partial,
                                        PoseStack ps, MultiBufferSource buffers, int light, float eased) {
        if (progress < 0.0F) return;
        // The Stand itself lands the first hit; every Allay you have then rushes in for one more.
        int[] hits;
        if (slot == 0) {
            if (stand.freeAllays() > 1) return; // the real duplicates fly in and punch (renderAllayWorld)
            int n = com.mobstands.stand.StandAbilities.rushFollowUps(stand.freeAllays());
            int gap = com.mobstands.stand.StandAbilities.allayGap(n);
            hits = new int[n];
            for (int k = 0; k < n; k++) hits[k] = com.mobstands.stand.StandAbilities.ALLAY_HIT_1 + (k + 1) * gap;
        } else if (slot == StandEntity.ANIM_SUMMON_COMBO || slot == StandEntity.ANIM_CHARGED_COMBO) {
            hits = new int[]{stand.comboHit2()};
        } else return;
        float t = stand.animElapsed(partial);
        int aura = StandHud.auraColor(StandType.ALLAY);
        for (int k = 0; k < hits.length; k++) {
            if (hits[k] < 0) continue;
            float dt = t - hits[k];
            if (dt < -4.0F || dt > 6.0F) continue;
            float side = k % 2 == 0 ? 1.0F : -1.0F;
            // Model space is flipped: x mirrored, +y is down, -z is forward. Distances are in model pixels/16 at scale 1.
            float x, y, z, alpha;
            if (dt < 0.0F) { // rushing in from beside the Stand
                float e = 1.0F + dt / 4.0F;
                e = e * e;
                x = Mth.lerp(e, 1.1F, 0.2F) * side;
                y = Mth.lerp(e, -0.3F, -0.2F);
                z = Mth.lerp(e, 1.0F, -0.45F);
                alpha = Mth.clamp((dt + 4.0F) / 2.0F, 0.0F, 1.0F);
            } else { // impact, then bounce back and fade
                float e = dt / 6.0F;
                x = Mth.lerp(e, 0.2F, 0.8F) * side;
                y = Mth.lerp(e, -0.2F, -0.7F);
                z = Mth.lerp(e, -0.45F, 0.1F);
                alpha = 1.0F - e;
            }
            alpha *= eased * 0.85F;
            if (alpha <= 0.02F) continue;
            ps.pushPose();
            ps.translate(x, y, z);
            // Leaning hard into the charge.
            ps.translate(0.0F, 1.5F, 0.0F);
            ps.mulPose(Axis.XP.rotationDegrees(dt < 0.0F ? 35.0F : 35.0F * (1.0F - dt / 6.0F)));
            ps.mulPose(Axis.ZP.rotationDegrees(-12.0F * side));
            ps.translate(0.0F, -1.5F, 0.0F);
            vis.main.renderToBuffer(ps, buffers.getBuffer(RenderType.entityTranslucent(vis.texture)), light, OverlayTexture.NO_OVERLAY,
                    ((int) (alpha * 255.0F) << 24) | 0xFFFFFF);
            glow(ps, buffers, vis.main, 0.35F * alpha, 1.08F, aura, bodyCenterY(StandType.ALLAY), true);
            if (dt >= 0.0F && dt < 3.0F) glow(ps, buffers, vis.main, 0.5F * (1.0F - dt / 3.0F), 1.2F + dt * 0.1F, 0xFFFFFF, bodyCenterY(StandType.ALLAY), true);
            ps.popPose();
        }
    }

    /**
     * The duplicate Allays, drawn in the world: free ones flutter around their owner on lazy wandering loops;
     * busy ones hover above the enemies they're holding up.
     */
    private static void renderAllayWorld(StandEntity stand, StandVisual vis, float partial, PoseStack ps, MultiBufferSource buffers, float alpha) {
        if (alpha <= 0.01F) return;
        Player owner = stand.getOwnerPlayer();
        if (owner == null) return;
        net.minecraft.world.level.Level level = stand.level();
        Vec3 base = new Vec3(Mth.lerp(partial, stand.xo, stand.getX()), Mth.lerp(partial, stand.yo, stand.getY()), Mth.lerp(partial, stand.zo, stand.getZ()));
        float t = stand.tickCount + partial;
        java.util.List<int[]> holds = stand.allayHolds();
        int heldDupes = 0;
        for (int[] h : holds) {
            Entity e = level.getEntity(h[0]);
            int n = h[1] - h[2]; // your own Allay draws itself
            heldDupes += n;
            if (e == null) continue;
            Vec3 ep = e.getPosition(partial);
            for (int i = 0; i < n; i++) {
                double a = i * (Math.PI * 2.0D / Math.max(1, n)) + t * 0.05D;
                double r = n > 1 ? 0.45D : 0.0D;
                Vec3 at = ep.add(Math.cos(a) * r, e.getBbHeight() + 0.15D + Math.sin(t * 0.2D + i) * 0.04D, Math.sin(a) * r);
                drawAllayAt(vis, ps, buffers, level, at, base, e.getYRot(), alpha);
            }
        }
        int freeDupes = Math.max(0, stand.allayCount() - 1 - heldDupes);
        Vec3 o = owner.getPosition(partial);
        if (stand.isLifting() && freeDupes > 0) {
            // Allay Lift: every free Allay grabs on above you with yours, in a little ring, wings beating.
            float yaw = Mth.rotLerp(partial, owner.yRotO, owner.getYRot());
            for (int i = 0; i < freeDupes; i++) {
                double a = Math.toRadians(yaw) + (i + 1) * (Math.PI * 2.0D / (freeDupes + 1));
                Vec3 at = o.add(Math.cos(a) * 0.45D, owner.getBbHeight() + 0.25D + Math.sin(t * 0.3D + i * 1.7D) * 0.04D, Math.sin(a) * 0.45D);
                drawAllayAt(vis, ps, buffers, level, at, base, yaw, alpha);
            }
            return;
        }
        // Free duplicates wander around you like the real one: glide to a spot, hover there a moment, pick another.
        dupePositions.clear();
        Vec3 roamAnchor = ROAM_ANCHOR.getOrDefault(stand.getId(), o);
        double away = roamAnchor.distanceTo(o);
        if (away > 24.0D) roamAnchor = o;                                   // fell far behind: catch up
        else if (away > 5.0D) roamAnchor = roamAnchor.add(o.subtract(roamAnchor).scale((away - 5.0D) / away * 0.06D));
        ROAM_ANCHOR.put(stand.getId(), roamAnchor);
        for (int i = 0; i < freeDupes; i++) {
            Vec3 rel;
            float yaw;
            if (!stand.isRoaming()) {
                // Roam off: in formation with your Allay (circle / shoulders + head / either side).
                int count = freeDupes + 1;
                float by = Mth.rotLerp(partial, owner.yBodyRotO, owner.yBodyRot);
                rel = StandEntity.allayFormation(o, by, owner.getBbHeight(), count, i + 1, t).subtract(o);
                yaw = StandEntity.allayFormationYaw(by, count, i + 1, t);
            } else {
                float seg = 70.0F + i * 13.0F;            // ticks per hop (each Allay on its own rhythm)
                float tt = t + i * 37.0F;
                int n = (int) Math.floor(tt / seg);
                float f = (tt - n * seg) / seg;
                float move = Mth.clamp(f / 0.7F, 0.0F, 1.0F); // gliding for 70% of the hop, hovering for the rest
                float e = move * move * (3.0F - 2.0F * move);
                Vec3 a = waypoint(stand, i, n), b = waypoint(stand, i, n + 1);
                rel = a.add(b.subtract(a).scale(e)).add(0.0D, Math.sin(tt * 0.1D) * 0.06D, 0.0D);
                // Faces where it's heading, turning smoothly at the start of each hop.
                float yawTo = yawOf(b.subtract(a));
                float yawFrom = yawOf(a.subtract(waypoint(stand, i, n - 1)));
                yaw = Mth.rotLerp(Mth.clamp(f / 0.25F, 0.0F, 1.0F), yawFrom, yawTo);
            }
            // Roaming duplicates wander around a spot in the world (that only drifts after you when you get far),
            // instead of being dragged along with you.
            Vec3 at = (stand.isRoaming() ? roamAnchor : o).add(rel);
            // Allay Rush: this duplicate dashes in from where it is, punches, and flies back.
            if (stand.animSlot() == 0 && stand.animProgress(partial) >= 0.0F) {
                int follow = com.mobstands.stand.StandAbilities.rushFollowUps(stand.freeAllays());
                if (i < follow) {
                    float hit = com.mobstands.stand.StandAbilities.ALLAY_HIT_1 + (i + 1) * com.mobstands.stand.StandAbilities.allayGap(follow);
                    float dt = stand.animElapsed(partial) - hit;
                    float w = dt < 0.0F ? Mth.clamp(1.0F + dt / 4.0F, 0.0F, 1.0F) : Mth.clamp(1.0F - dt / 6.0F, 0.0F, 1.0F);
                    w = w * w * (3.0F - 2.0F * w);
                    if (w > 0.0F) {
                        double sy = Math.toRadians(Mth.rotLerp(partial, stand.yRotO, stand.getYRot()));
                        Vec3 fwd = new Vec3(-Math.sin(sy), 0.0D, Math.cos(sy));
                        Vec3 sideV = new Vec3(-fwd.z, 0.0D, fwd.x).scale(i % 2 == 0 ? 0.45D : -0.45D);
                        Vec3 strike = base.add(fwd.scale(0.5D)).add(sideV).add(0.0D, 0.5D, 0.0D);
                        at = at.add(strike.subtract(at).scale(w));
                        yaw = Mth.rotLerp(w, yaw, yawOf(strike.subtract(o.add(rel))));
                        if (dt >= 0.0F && dt < 3.0F) {
                            float flash = 1.0F - dt / 3.0F;
                            drawAllayAt(vis, ps, buffers, level, at, base, yaw, alpha);
                            glowAllayAt(vis, ps, buffers, at, base, yaw, 0.5F * flash * alpha);
                            continue;
                        }
                    }
                }
            }
            dupePositions.add(at);
            if (AllayDupeHits.flashing(i)) {
                // Just got hit: flashes red.
                drawAllayAt(vis, ps, buffers, level, at.add(AllayDupeHits.knock(i, partial)), base, yaw, alpha, true);
                continue;
            }
            drawAllayAt(vis, ps, buffers, level, at.add(AllayDupeHits.knock(i, partial)), base, yaw, alpha);
        }
    }

    /** Client: the patch of world each Allay Stand's roaming duplicates hang around. */
    private static final java.util.Map<Integer, Vec3> ROAM_ANCHOR = new java.util.HashMap<>();

    /** Where each free duplicate was drawn this frame (so you can punch them). */
    public static final java.util.List<Vec3> dupePositions = new java.util.ArrayList<>();

    /** A bright flash around an Allay at the moment it punches. */
    private static void glowAllayAt(StandVisual vis, PoseStack ps, MultiBufferSource buffers, Vec3 at, Vec3 base, float yaw, float a) {
        if (a <= 0.01F) return;
        Vec3 off = at.subtract(base);
        ps.pushPose();
        ps.translate(off.x, off.y, off.z);
        ps.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
        ps.scale(-1.0F, -1.0F, 1.0F);
        float sc = (float) StandType.ALLAY.scale() * 0.9F;
        ps.scale(sc, sc, sc);
        ps.translate(0.0F, -1.501F, 0.0F);
        glow(ps, buffers, vis.main, a, 1.15F, StandHud.auraColor(StandType.ALLAY), bodyCenterY(StandType.ALLAY), true);
        ps.popPose();
    }

    /** A duplicate's n-th wander spot around its owner (stable for that Allay and hop). */
    private static Vec3 waypoint(StandEntity stand, int i, int n) {
        long seed = stand.getId() * 341873128712L + i * 132897987541L + n * 2654435761L;
        java.util.Random r = new java.util.Random(seed);
        double a = r.nextDouble() * Math.PI * 2.0D;
        double d = 1.6D + r.nextDouble() * 2.4D;
        return new Vec3(Math.cos(a) * d, 1.0D + r.nextDouble() * 1.2D, Math.sin(a) * d);
    }

    private static float yawOf(Vec3 dir) {
        return (float) (Mth.atan2(dir.z, dir.x) * (180.0D / Math.PI)) - 90.0F;
    }

    private static void drawAllayAt(StandVisual vis, PoseStack ps, MultiBufferSource buffers, net.minecraft.world.level.Level level,
                                    Vec3 at, Vec3 base, float yaw, float alpha) {
        drawAllayAt(vis, ps, buffers, level, at, base, yaw, alpha, false);
    }

    private static void drawAllayAt(StandVisual vis, PoseStack ps, MultiBufferSource buffers, net.minecraft.world.level.Level level,
                                    Vec3 at, Vec3 base, float yaw, float alpha, boolean hurt) {
        Vec3 off = at.subtract(base);
        ps.pushPose();
        ps.translate(off.x, off.y, off.z);
        ps.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
        ps.scale(-1.0F, -1.0F, 1.0F);
        float sc = (float) StandType.ALLAY.scale() * 0.9F;
        ps.scale(sc, sc, sc);
        ps.translate(0.0F, -1.501F, 0.0F);
        int light = net.minecraft.client.renderer.LevelRenderer.getLightColor(level, net.minecraft.core.BlockPos.containing(at));
        RenderType rt = alpha < 0.999F ? RenderType.entityTranslucent(vis.texture) : RenderType.entityCutoutNoCull(vis.texture);
        int overlay = hurt ? OverlayTexture.pack(OverlayTexture.u(0.0F), OverlayTexture.v(true)) : OverlayTexture.NO_OVERLAY;
        vis.main.renderToBuffer(ps, buffers.getBuffer(rt), light, overlay, ((int) (alpha * 255.0F) << 24) | 0xFFFFFF);
        ps.popPose();
    }

    /**
     * First person only, for your own Stand: while it attacks it slides from behind your shoulder into view
     * (lower right/left of the screen, just in front of you), then back. Third person and other players see
     * it exactly where it really is.
     */
    private static void firstPersonShift(StandEntity stand, StandType type, float progress, float partial, PoseStack ps) {
        if (progress < 0.0F) return;
        Minecraft mc = Minecraft.getInstance();
        Player owner = stand.getOwnerPlayer();
        if (owner == null || owner != mc.player || !mc.options.getCameraType().isFirstPerson()) return;
        if (type == StandType.HORSE || type == StandType.SQUID || type == StandType.SPIDER) return;
        float w = Mth.clamp(Math.min(progress, 1.0F - progress) * 6.0F, 0.0F, 1.0F);
        w = w * w * (3.0F - 2.0F * w);
        double yaw = Math.toRadians(Mth.rotLerp(partial, owner.yRotO, owner.getYRot()));
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = -Math.cos(yaw) * stand.side(), rz = -Math.sin(yaw) * stand.side();
        Vec3 standPos = new Vec3(Mth.lerp(partial, stand.xo, stand.getX()), Mth.lerp(partial, stand.yo, stand.getY()),
                Mth.lerp(partial, stand.zo, stand.getZ()));
        Vec3 feet = owner.getPosition(partial);
        // Already out in front (it rushed at a target): it's on screen anyway.
        double ahead = (standPos.x - feet.x) * fx + (standPos.z - feet.z) * fz;
        if (ahead > 1.2D) return;
        Vec3 eye = owner.getEyePosition(partial);
        double down = 1.25D + 0.25D * type.scale();
        Vec3 front = new Vec3(eye.x + fx * 1.15D + rx * 0.55D, eye.y - down, eye.z + fz * 1.15D + rz * 0.55D);
        Vec3 d = front.subtract(standPos).scale(w);
        ps.translate(d.x, d.y, d.z);
    }
}
