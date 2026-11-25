package com.mobstands.client;

import com.mobstands.entity.StandEntity;
import com.mobstands.stand.StandAbilities;
import com.mobstands.stand.StandType;
import net.minecraft.client.model.AxolotlModel;
import net.minecraft.client.model.BlazeModel;
import net.minecraft.client.model.HorseModel;
import net.minecraft.client.model.AllayModel;
import net.minecraft.client.model.PigModel;
import net.minecraft.client.model.PiglinModel;
import net.minecraft.client.model.CreeperModel;
import net.minecraft.client.model.EndermanModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.IronGolemModel;
import net.minecraft.client.model.PhantomModel;
import net.minecraft.client.model.SkeletonModel;
import net.minecraft.client.model.SlimeModel;
import net.minecraft.client.model.SpiderModel;
import net.minecraft.client.model.SquidModel;
import net.minecraft.client.model.WardenModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * Client-side model set for one Stand type. We bake the vanilla mob model once and animate it with a
 * hidden "dummy" vanilla entity so the real vanilla walk/idle/aim animations are reused, then layer our own
 * punch / ability poses on top.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
final class StandVisual {
    final StandType type;
    final EntityModel main;
    final EntityModel outer; // only Slime has an outer shell layer
    final ModelPart root;
    /** Oversized fist used for the punch barrage; shares the mob's texture. */
    final ModelPart fist;
    /** The mob's real arms (own copy, so posing them never disturbs the Stand's body). Null for armless mobs. */
    final ArmPiece rightArm, leftArm;

    /** A real arm model plus the centre of its boxes (pixels, in the arm's own space) for placing it as a fist. */
    record ArmPiece(ModelPart part, float cx, float cy, float cz, float length) {
        static ArmPiece of(ModelPart part) {
            if (part == null || part.cubes.isEmpty()) return null;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (ModelPart.Cube c : part.cubes) {
                minX = Math.min(minX, c.minX); minY = Math.min(minY, c.minY); minZ = Math.min(minZ, c.minZ);
                maxX = Math.max(maxX, c.maxX); maxY = Math.max(maxY, c.maxY); maxZ = Math.max(maxZ, c.maxZ);
            }
            return new ArmPiece(part, (minX + maxX) / 2.0F, (minY + maxY) / 2.0F, (minZ + maxZ) / 2.0F, Math.max(1.0F, maxY - minY));
        }
    }

    /**
     * Parts that are mostly see-through in the texture (the Warden's tendrils). The glow shell ignores texture
     * transparency, so these would glow as solid squares ("Mickey Mouse ears"); they're hidden for glow passes only.
     */
    private java.util.List<ModelPart> glowHidden;

    void hideForGlow(boolean hide) {
        if (glowHidden == null) {
            glowHidden = new java.util.ArrayList<>();
            if ((type == StandType.WARDEN || type == StandType.WARDEN_REQUIEM)) {
                ModelPart r = part("bone", "body", "head", "right_tendril");
                ModelPart l = part("bone", "body", "head", "left_tendril");
                if (r != null) glowHidden.add(r);
                if (l != null) glowHidden.add(l);
            }
        }
        for (ModelPart p : glowHidden) p.visible = !hide;
    }

    /** Horse Stand: only the head is shown (no neck, no saddle straps), sitting on the player's head. */
    ModelPart horseHead() {
        if (type != StandType.HORSE || !root.hasChild("head_parts")) return null;
        ModelPart head = root.getChild("head_parts");
        head.skipDraw = true; // just the head: the neck box belongs to head_parts itself
        for (String strap : new String[]{"left_saddle_mouth", "right_saddle_mouth", "left_saddle_line", "right_saddle_line",
                "head_saddle", "mouth_saddle_wrap"}) {
            if (head.hasChild(strap)) head.getChild(strap).visible = false;
        }
        return head;
    }

    /** Horse Stand: the horse's tail (drawn on your lower back). */
    ModelPart horseTail() {
        ModelPart body = type == StandType.HORSE && root.hasChild("body") ? root.getChild("body") : null;
        return body != null && body.hasChild("tail") ? body.getChild("tail") : null;
    }

    ArmPiece arm(boolean left) {
        return left && leftArm != null ? leftArm : rightArm;
    }
    final ResourceLocation texture;
    /** Creeper Requiem: the charged-creeper energy shell (null for everyone else). */
    EntityModel chargeShell;
    final ResourceLocation eyes;

    private Entity dummy;
    private Level dummyLevel;
    int lastAnimStart = Integer.MIN_VALUE;
    /** Set by the renderer each frame for the summon-rush combo. */
    float comboElapsed;
    /** Set by the renderer: the Stand is holding its pose (J). */
    boolean posing;
    /** Zombified Piglin: 0..1 how far the sword is turned to point straight ahead for a poke. */
    float swordPoke;
    /** Set by the renderer: which fist the current regular punch uses (0 right, 1 left). */
    int punchSide;
    /** Set by the renderer: the Axolotl is playing dead. */
    boolean fakeDead;
    /** Set by the renderer: 1 = Stand behind your right shoulder, -1 = your left. */
    int side = 1;
    int comboHit2 = -1;
    private float wardenSwingAt;

    StandVisual(StandType type, EntityRendererProvider.Context ctx) {
        this.type = type;
        EntityModel outerModel = null;
        ResourceLocation eyesTex = null;
        switch (type) {
            case CREEPER -> {
                root = ctx.bakeLayer(ModelLayers.CREEPER);
                main = new CreeperModel(root);
                texture = tex("textures/entity/creeper/creeper.png");
            }
            case CREEPER_REQUIEM -> {
                // A charged creeper: the vanilla creeper wrapped in its crackling blue energy shell.
                root = ctx.bakeLayer(ModelLayers.CREEPER);
                main = new CreeperModel(root);
                // Teal-tinged skin with blue lightning cracks down its body and legs; its face and the cracks glow.
                texture = ResourceLocation.fromNamespaceAndPath("mobstands", "textures/entity/creeper_requiem.png");
                eyesTex = ResourceLocation.fromNamespaceAndPath("mobstands", "textures/entity/creeper_requiem_glow.png");
                chargeShell = new CreeperModel(ctx.bakeLayer(ModelLayers.CREEPER_ARMOR));
            }
            case ENDERMAN, ENDER_WORLD -> {
                root = ctx.bakeLayer(ModelLayers.ENDERMAN);
                main = new EndermanModel(root);
                texture = tex("textures/entity/enderman/enderman.png");
                eyesTex = tex("textures/entity/enderman/enderman_eyes.png");
            }
            case IRON_GOLEM -> {
                root = ctx.bakeLayer(ModelLayers.IRON_GOLEM);
                main = new IronGolemModel(root);
                texture = tex("textures/entity/iron_golem/iron_golem.png");
            }
            case BLAZE -> {
                root = ctx.bakeLayer(ModelLayers.BLAZE);
                main = new BlazeModel(root);
                texture = tex("textures/entity/blaze.png");
            }
            case WARDEN, WARDEN_REQUIEM -> {
                root = ctx.bakeLayer(ModelLayers.WARDEN);
                main = new WardenModel(root);
                texture = tex("textures/entity/warden/warden.png");
            }
            case PHANTOM -> {
                root = ctx.bakeLayer(ModelLayers.PHANTOM);
                main = new PhantomModel(root);
                texture = tex("textures/entity/phantom.png");
                eyesTex = tex("textures/entity/phantom_eyes.png");
            }
            case ALLAY -> {
                root = ctx.bakeLayer(ModelLayers.ALLAY);
                main = new AllayModel(root);
                texture = tex("textures/entity/allay/allay.png");
            }
            case PIG -> {
                root = ctx.bakeLayer(ModelLayers.PIG);
                main = new PigModel(root);
                texture = tex("textures/entity/pig/pig.png");
            }
            case GER -> {
                // Guardian Experience Requiem: a humanoid golem - iron and gold, emerald hands and brow, a totem in its chest.
                // The real Iron Golem model, as a Requiem: golden-warm iron, emeralds in its wrists, knees and brow,
                // and the totem framed in gold in its chest.
                root = ctx.bakeLayer(ModelLayers.IRON_GOLEM);
                main = new IronGolemModel(root);
                texture = ResourceLocation.fromNamespaceAndPath("mobstands", "textures/entity/ger.png");
                eyesTex = ResourceLocation.fromNamespaceAndPath("mobstands", "textures/entity/ger_glow.png"); // eyes and emeralds glow
            }
            case PIGLIN_REQUIEM -> {
                // Pigstep: a Piglin Brute in amethyst-purple leather, amethyst set into its fists.
                root = ctx.bakeLayer(ModelLayers.PIGLIN_BRUTE);
                main = new PiglinModel(root);
                texture = ResourceLocation.fromNamespaceAndPath("mobstands", "textures/entity/gilded_silence.png");
                // Amethyst eyes, the gem in its gold circlet, its fists and shoulders glow.
                eyesTex = ResourceLocation.fromNamespaceAndPath("mobstands", "textures/entity/gilded_silence_glow.png");
            }
            case PIGLIN -> {
                root = ctx.bakeLayer(ModelLayers.PIGLIN);
                main = new PiglinModel(root);
                texture = tex("textures/entity/piglin/piglin.png");
            }
            case ZOMBIFIED_PIGLIN -> {
                root = ctx.bakeLayer(ModelLayers.ZOMBIFIED_PIGLIN);
                main = new PiglinModel(root);
                texture = tex("textures/entity/piglin/zombified_piglin.png");
            }
            case HORSE -> {
                root = ctx.bakeLayer(ModelLayers.HORSE);
                main = new HorseModel(root);
                texture = tex("textures/entity/horse/horse_brown.png");
            }
            case AXOLOTL -> {
                root = ctx.bakeLayer(ModelLayers.AXOLOTL);
                main = new AxolotlModel(root);
                texture = tex("textures/entity/axolotl/axolotl_lucy.png");
            }
            case SQUID -> {
                root = ctx.bakeLayer(ModelLayers.SQUID);
                main = new SquidModel(root);
                texture = tex("textures/entity/squid/squid.png");
            }
            case SKELETON -> {
                root = ctx.bakeLayer(ModelLayers.SKELETON);
                main = new SkeletonModel(root);
                texture = tex("textures/entity/skeleton/skeleton.png");
            }
            case SPIDER -> {
                root = ctx.bakeLayer(ModelLayers.SPIDER);
                main = new SpiderModel(root);
                texture = tex("textures/entity/spider/spider.png");
                eyesTex = tex("textures/entity/spider_eyes.png");
            }
            case SLIME -> {
                root = ctx.bakeLayer(ModelLayers.SLIME);
                main = new SlimeModel(root);
                outerModel = new SlimeModel(ctx.bakeLayer(ModelLayers.SLIME_OUTER));
                texture = tex("textures/entity/slime/slime.png");
            }
            case SNIFFER -> {
                root = ctx.bakeLayer(ModelLayers.SNIFFER);
                main = new net.minecraft.client.model.SnifferModel<>(root);
                texture = tex("textures/entity/sniffer/sniffer.png");
            }
            case DOLPHIN -> {
                root = ctx.bakeLayer(ModelLayers.DOLPHIN);
                main = new net.minecraft.client.model.DolphinModel(root);
                texture = tex("textures/entity/dolphin.png");
            }
            case SHULKER -> {
                root = ctx.bakeLayer(ModelLayers.SHULKER);
                ModelPart sBase = root.getChild("base"), sLid = root.getChild("lid"), sHead = root.getChild("head");
                // The vanilla model leaves the head to a separate layer: draw it with the shell.
                main = new net.minecraft.client.model.ShulkerModel(root) {
                    @Override
                    public Iterable<ModelPart> parts() {
                        return com.google.common.collect.ImmutableList.of(sBase, sLid, sHead);
                    }
                };
                texture = tex("textures/entity/shulker/shulker.png"); // the normal purple shulker
            }
            case MINECART -> {
                root = ctx.bakeLayer(ModelLayers.MINECART);
                main = new net.minecraft.client.model.MinecartModel<>(root);
                texture = tex("textures/entity/minecart.png");
            }
            case LLAMA -> {
                root = ctx.bakeLayer(ModelLayers.LLAMA);
                main = new net.minecraft.client.model.LlamaModel<>(root);
                texture = tex("textures/entity/llama/creamy.png");
            }
            case GUARDIAN -> {
                root = ctx.bakeLayer(ModelLayers.GUARDIAN);
                main = new net.minecraft.client.model.GuardianModel(root);
                texture = tex("textures/entity/guardian.png");
            }
            case ILLUSIONER -> {
                root = ctx.bakeLayer(ModelLayers.ILLUSIONER);
                main = new net.minecraft.client.model.IllagerModel<>(root);
                texture = tex("textures/entity/illager/illusioner.png");
            }
            case SILVERFISH -> { // (drawn as a whole swarm by SilverSwarm - this is just a fallback)
                root = ctx.bakeLayer(ModelLayers.SILVERFISH);
                main = new net.minecraft.client.model.SilverfishModel<>(root);
                texture = tex("textures/entity/silverfish.png");
            }
            default -> throw new IllegalStateException("No model for " + type);
        }
        this.outer = outerModel;
        this.eyes = eyesTex;
        this.fist = bakeFist(type);
        ModelPart armSource = switch (type) {
            case IRON_GOLEM -> ctx.bakeLayer(ModelLayers.IRON_GOLEM);
            case ENDERMAN, ENDER_WORLD -> ctx.bakeLayer(ModelLayers.ENDERMAN);
            case WARDEN, WARDEN_REQUIEM -> ctx.bakeLayer(ModelLayers.WARDEN);
            case BLAZE -> ctx.bakeLayer(ModelLayers.BLAZE);
            case PIGLIN -> ctx.bakeLayer(ModelLayers.PIGLIN);
            case PIGLIN_REQUIEM -> ctx.bakeLayer(ModelLayers.PIGLIN_BRUTE);
            case GER -> ctx.bakeLayer(ModelLayers.IRON_GOLEM);
            case ZOMBIFIED_PIGLIN -> ctx.bakeLayer(ModelLayers.ZOMBIFIED_PIGLIN);
            case ILLUSIONER -> ctx.bakeLayer(ModelLayers.ILLUSIONER);
            default -> null;
        };
        // The Blaze has no arms: it jabs with two of its own rods instead.
        String rightName = type == StandType.BLAZE ? "part0" : "right_arm";
        String leftName = type == StandType.BLAZE ? "part1" : "left_arm";
        this.rightArm = armSource == null ? null : ArmPiece.of(findArm(armSource, rightName));
        this.leftArm = armSource == null ? null : ArmPiece.of(findArm(armSource, leftName));
        // Models default to "young"; vanilla renderers reset it every frame. Without this Skeleton and
        // Enderman (humanoid models) render baby-sized.
        initStance();
        this.main.young = false;
        if (this.outer != null) this.outer.young = false;
    }

    /** GER's body: a person with slim (3-pixel, Alex-style) arms and an Iron Golem's long nose. */
    private static ModelPart gerRoot() {
        MeshDefinition gm = net.minecraft.client.model.PlayerModel.createMesh(net.minecraft.client.model.geom.builders.CubeDeformation.NONE, true);
        var r = gm.getRoot();
        r.getChild("head").addOrReplaceChild("nose",
                CubeListBuilder.create().texOffs(56, 16).addBox(-1.0F, -3.0F, -6.0F, 2.0F, 4.0F, 2.0F), PartPose.ZERO);
        // One clean crest ridge over the top of the head, an emerald at its front.
        r.getChild("head").addOrReplaceChild("crest",
                CubeListBuilder.create().texOffs(0, 32).addBox(-1.0F, -9.0F, -4.0F, 2.0F, 1.0F, 8.0F), PartPose.ZERO);
        return LayerDefinition.create(gm, 64, 64).bakeRoot();
    }

    private static ResourceLocation tex(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    /** Hidden vanilla entity whose only job is to feed vanilla model animation code. */
    Entity dummy(Level level) {
        if (dummy == null || dummyLevel != level) {
            dummy = type.vanilla().create(level);
            dummyLevel = level;
            if (dummy != null) {
                dummy.setSilent(true);
                dummy.setNoGravity(true);
                if (type == StandType.SKELETON && dummy instanceof Mob mob) {
                    mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                }
                if (type == StandType.DOLPHIN) dummy.setDeltaMovement(0.05D, 0.0D, 0.0D); // so its tail keeps swimming
                if (type == StandType.ZOMBIFIED_PIGLIN && dummy instanceof Mob mob) {
                    mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_SWORD)); // its rusty gold sword
                }
            }
        }
        return dummy;
    }

    void animate(Entity d, int tick, float limbSwing, float limbAmount, float age, float pitch, float partial,
                 float progress, int slot, int animStart) {
        d.tickCount = tick;
        if (type == StandType.SKELETON && d instanceof Mob mob) {
            mob.setAggressive(progress >= 0.0F); // raises the bow arms while attacking
            driveBow(mob, progress, slot);
        }
        if (d instanceof Warden w) driveWarden(w, progress, slot, animStart, age);
        if (d instanceof net.minecraft.world.entity.animal.axolotl.Axolotl ax) ax.setPlayingDead(false); // the renderer plays the vanilla death animation instead

        try {
            main.prepareMobModel(d, limbSwing, limbAmount, partial);
            // The squid model reads "age" as its tentacle angle: give it a slow, eerie sway instead.
            float animAge = type == StandType.SQUID ? 0.35F + Mth.sin(age * 0.09F) * 0.25F : age;
            main.setupAnim(d, limbSwing, limbAmount, animAge, 0.0F, pitch);
            if (type == StandType.SHULKER) shulkerPose(progress, age);
            if (outer != null) {
                outer.prepareMobModel(d, limbSwing, limbAmount, partial);
                outer.setupAnim(d, limbSwing, limbAmount, age, 0.0F, pitch);
            }
        } catch (RuntimeException ignored) {
            // Never crash the renderer because of an animation edge case; the model just stays in its rest pose.
        }
        main.young = false;
        boolean meleeAnim = slot == 0 || slot == 1 || slot == StandEntity.ANIM_SUMMON_COMBO || slot == StandEntity.ANIM_CHARGED_COMBO;
        if (progress < 0.0F || (meleeAnim && type != StandType.SKELETON)) idlePose(age); // punches blend from the idle pose
        applyArmPose(progress, slot, age);
    }

    /**
     * C-Moon: the shell sits a little open with the head peeking out, and pops wide open while it attacks.
     * (The dummy is never really "peeking", so the lid is placed by hand, the same way vanilla does it.)
     */
    private void shulkerPose(float progress, float age) {
        ModelPart lid = root.getChild("lid"), head = root.getChild("head");
        float peek = 0.3F + Mth.sin(age * 0.05F) * 0.05F;
        if (progress >= 0.0F) peek = Math.max(peek, Mth.sin(Mth.clamp(progress * 1.4F, 0.0F, 1.0F) * Mth.PI) * 0.7F + 0.3F);
        lid.setPos(0.0F, 16.0F + Mth.cos(peek * Mth.PI) * 8.0F, 0.0F);
        lid.yRot = 0.0F;
        head.yRot = 0.0F; // faces the way you look (vanilla turns it via the shulker's own odd body rotation)
    }

    /**
     * Makes the hidden dummy Skeleton "use" its bow so the vanilla bow model shows the real pulling stages.
     * Basic attack: draws over BOW_DRAW_TICKS, then relaxes on release. Abilities: held at full draw.
     */
    private static void driveBow(Mob mob, float progress, int slot) {
        ItemStack bow = mob.getMainHandItem();
        boolean drawing = false;
        int pullTicks = 0;
        if (progress >= 0.0F) {
            if (slot == 0) {
                float elapsed = progress * StandEntity.animDuration(StandType.SKELETON, 0);
                drawing = elapsed < StandType.BOW_DRAW_TICKS;
                pullTicks = (int) (20.0F * Math.min(1.0F, elapsed / StandType.BOW_DRAW_TICKS));
            } else {
                drawing = true;
                pullTicks = 20;
            }
        }
        if (drawing && !bow.isEmpty()) {
            mob.useItem = bow;
            mob.useItemRemaining = bow.getUseDuration(mob) - pullTicks;
            mob.setLivingEntityFlag(1, true);
        } else {
            mob.setLivingEntityFlag(1, false);
            mob.useItem = ItemStack.EMPTY;
            mob.useItemRemaining = 0;
        }
    }

    /**
     * Vanilla Warden keyframe animations are only used where they fit: the Sonic Boom (X) and the roar (C).
     * Punches, Barrage and the combos are driven by applyArmPose instead, so the real arms stay in sync with
     * the ghost arms and the sounds rather than swinging on their own.
     */
    private void driveWarden(Warden w, float progress, int slot, int animStart, float age) {
        boolean vanilla = progress >= 0.0F && (slot == 2 || slot == 3);
        if (!vanilla) {
            w.attackAnimationState.stop();
            w.sonicBoomAnimationState.stop();
            w.roarAnimationState.stop();
            lastAnimStart = Integer.MIN_VALUE;
            return;
        }
        if (lastAnimStart != animStart) {
            lastAnimStart = animStart;
            wardenSwingAt = age;
            AnimationState state = slot == 2 ? w.sonicBoomAnimationState : w.roarAnimationState;
            w.attackAnimationState.stop();
            w.sonicBoomAnimationState.stop();
            w.roarAnimationState.stop();
            state.start((int) age);
        }
    }

    /**
     * A straight jab: the arm is eased to horizontal (pointing forward) by {@code level} and its shoulder pushed
     * {@code reach} pixels along its length (negative = forward), instead of swinging up in an arc like a player.
     */
    private static void jab(ModelPart arm, float level, float reach) {
        if (arm == null) return;
        blendArm(arm, level, -Mth.HALF_PI, 0.0F, 0.0F);
        arm.z += reach * Math.min(1.0F, level + 0.0001F);
    }

    /** Arms keep their resting shoulder position unless a jab moves them this frame. */
    private static void resetArmReach(ModelPart arm) {
        if (arm != null) arm.z = arm.getInitialPose().z;
    }

    /** Eases an arm from whatever pose it's in (the idle pose) toward a target pose by {@code w} (0..1). */
    private static void blendArm(ModelPart arm, float w, float x, float y, float z) {
        if (arm == null || w <= 0.0F) return;
        w = Math.min(1.0F, w);
        arm.xRot = Mth.lerp(w, arm.xRot, x);
        arm.yRot = Mth.lerp(w, arm.yRot, y);
        arm.zRot = Mth.lerp(w, arm.zRot, z);
    }

    /** Right/left arm of any model (the Warden keeps its arms inside bone/body). */
    private ModelPart armPart(boolean left) {
        String name = left ? "left_arm" : "right_arm";
        if (root.hasChild(name)) return root.getChild(name);
        return part("bone", "body", name);
    }

    /** Our own punch / cast pose, applied after vanilla animation. */
    private void applyArmPose(float progress, int slot, float age) {
        ModelPart rArm = armPart(false);
        ModelPart lArm = armPart(true);
        resetArmReach(rArm);
        resetArmReach(lArm);
        swordPoke = 0.0F;
        if (type == StandType.PHANTOM && progress >= 0.0F) {
            phantomAttack(progress, slot, age);
            return;
        }
        if (progress < 0.0F || rArm == null) return;
        if (!StandRenderer.ATTACK_FX) return; // no punching arm moves: the Stand keeps its pose
        if ((type == StandType.WARDEN || type == StandType.WARDEN_REQUIEM) && (slot == 2 || slot == 3)) return; // vanilla Sonic Boom / roar animation
        if (slot == StandEntity.ANIM_SUMMON_COMBO || slot == StandEntity.ANIM_CHARGED_COMBO) {
            float t = comboElapsed;
            float charge = comboCharge(t, dashTick(slot), hit1Tick(slot));
            float shake = Mth.sin(t * 3.1F) * 0.4F * charge;
            float p1 = comboPunch(t, hit1Tick(slot));
            float p2 = comboHit2 >= 0 ? comboPunch(t, comboHit2) : 0.0F;
            // Fists raised level and pulled back (trembling) while charging, then driven straight forward.
            jab(rArm, Math.min(1.0F, charge + p1 * 2.5F), 4.0F * charge + shake - 7.0F * p1);
            jab(lArm, Math.min(1.0F, charge + p2 * 2.5F), 4.0F * charge - shake - 7.0F * p2);
            return;
        }
        if (slot == 1 && type.barrage()) {
            float t = comboElapsed;
            float fin = StandAbilities.BARRAGE_TICKS - 1; // the finisher lands here
            float wind = fin - 5.0F;
            float in = Mth.clamp(t / 2.0F, 0.0F, 1.0F);
            if (t < wind) {
                // Flurry: both arms held level, pistoning forward and back, alternating fast (and a touch of tremble).
                float a = t * 1.5F;
                float shake = 0.0F;
                jab(rArm, in, -7.5F * in * (0.5F + 0.5F * Mth.sin(a)) + shake);
                jab(lArm, in, -7.5F * in * (0.5F - 0.5F * Mth.sin(a)) - shake);
            } else if (t < fin) {
                // Wind-up: the right fist pulls way back, trembling with the build-up; the left stays out on guard.
                float u = (t - wind) / (fin - wind);
                float pull = u * u * (3.0F - 2.0F * u);
                float tremble = Mth.sin(t * 2.5F) * 0.4F * pull;
                jab(rArm, 1.0F, 4.0F * pull + tremble);
                jab(lArm, 1.0F, -3.0F);
            } else {
                // Finisher: the right fist is driven all the way through, then everything eases back.
                float after = t - fin;
                float hit = Mth.clamp(after / 1.5F, 0.0F, 1.0F);
                hit = hit * hit * (3.0F - 2.0F * hit);
                float out = Mth.clamp(1.0F - (after - 3.0F) / 4.0F, 0.0F, 1.0F);
                out = out * out * (3.0F - 2.0F * out);
                jab(rArm, out, (4.0F - 15.0F * hit) * out);
                jab(lArm, out, 1.5F * out);
            }
            return;
        }
        if (slot == 0 && type == StandType.ZOMBIFIED_PIGLIN) {
            // Sword poke: the sword arm comes up level, the blade turns to point dead ahead, and it stabs straight out.
            float stab = comboPunch(comboElapsed, StandAbilities.PUNCH_HIT_TICK);
            float raise = Mth.clamp(comboElapsed / 3.0F, 0.0F, 1.0F) * Mth.clamp((14.0F - comboElapsed) / 4.0F, 0.0F, 1.0F);
            float level = Math.max(raise, Math.min(1.0F, stab * 2.5F));
            jab(rArm, level, 2.0F * raise * (1.0F - stab) - 10.0F * stab); // pulls back a bit, then thrusts
            swordPoke = level;
            return;
        }
        if (slot == 0 && (type.barrage() || rightArm != null)) {
            // Regular punch: the arm snaps level, then drives straight out (timed with its ghost arm and sound).
            // The arm snaps level, then drives straight out (timed with its ghost arm and sound).
            int h = StandAbilities.PUNCH_HIT_TICK;
            if (punchSide == 1) {
                punchArm(lArm, comboElapsed, h); // follow-up: left only
            } else {
                punchArm(rArm, comboElapsed, h);
                if (comboHit2 >= 0) punchArm(lArm, comboElapsed, comboHit2);
                else if (comboElapsed < h + 4) jab(lArm, guard(comboElapsed, h), 1.5F); // the other fist up on guard
            }
            return;
        }
        if (type == StandType.SKELETON || (type == StandType.WARDEN || type == StandType.WARDEN_REQUIEM)) return; // bow pose / nothing else to do
        if (type == StandType.ENDER_WORLD && slot == 3) return; // Time Stop: it just stands there - no gesture
        float s = Mth.sin(progress * Mth.PI);
        boolean bothArms = slot > 0 || type == StandType.IRON_GOLEM;
        rArm.xRot = -1.7F * s - (bothArms ? 1.0F * s : 0.0F);
        if (lArm != null && bothArms) lArm.xRot = -2.7F * s;
    }

    /** 0..1 how wound-up the Stand is: builds over the summon part of the sound, released as it dashes in. */
    static int dashTick(int slot) {
        return slot == StandEntity.ANIM_CHARGED_COMBO ? StandAbilities.CHARGED_DASH : StandAbilities.COMBO_DASH;
    }

    static int hit1Tick(int slot) {
        return slot == StandEntity.ANIM_CHARGED_COMBO ? StandAbilities.CHARGED_HIT_1 : StandAbilities.COMBO_HIT_1;
    }

    static float comboCharge(float t, int dash, int hit1) {
        if (t >= hit1 - 2) return 0.0F;
        float up = Math.min(1.0F, t / Math.max(1.0F, Math.min(10.0F, dash)));
        up = up * up * (3.0F - 2.0F * up);
        if (t > dash) {
            up *= 1.0F - (t - dash) / Math.max(1.0F, hit1 - 2 - dash);
        }
        return up;
    }

    /** 0..1 extension of a punch that lands at tick {@code hit}: quick 3-tick wind-in, 6-tick pull back. */
    /** A regular punch's arm: the original smooth drive out and ease back. {@code hit} = the tick it lands. */
    private static void punchArm(ModelPart arm, float t, int hit) {
        float p = comboPunch(t, hit);
        jab(arm, Math.min(1.0F, p * 2.5F), -7.0F * p);
    }

    /** How high the off hand is held on guard during a single punch. */
    private static float guard(float t, int hit) {
        return Mth.clamp(t / 2.0F, 0.0F, 1.0F) * Mth.clamp((hit + 4.0F - t) / 3.0F, 0.0F, 1.0F);
    }

    static float comboPunch(float t, int hit) {
        if (t < hit - 3 || t > hit + 6) return 0.0F;
        if (t <= hit) {
            float x = (t - (hit - 3)) / 3.0F;
            return x * x * (3.0F - 2.0F * x);
        }
        return 1.0F - (t - hit) / 6.0F;
    }

    /** Finds an arm by name anywhere in the model tree (the Warden keeps its arms inside bone/body). */
    private static ModelPart findArm(ModelPart root, String name) {
        if (root.hasChild(name)) return root.getChild(name);
        for (String path : new String[]{"bone", "body"}) {
            if (root.hasChild(path)) {
                ModelPart found = findArm(root.getChild(path), name);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Texture sheet size and the patch of skin the fist samples, per mob (the patch is 32x16 pixels). */
    private static ModelPart bakeFist(StandType type) {
        int tw = 64, th = 32, u = 0, v = 16;
        switch (type) {
            case CREEPER, CREEPER_REQUIEM -> { u = 16; v = 16; }
            case ENDERMAN, ENDER_WORLD -> { u = 0; v = 16; }
            case IRON_GOLEM -> { tw = 128; th = 128; u = 60; v = 21; }
            case BLAZE -> { u = 0; v = 16; }
            case WARDEN, WARDEN_REQUIEM -> { tw = 128; th = 128; u = 48; v = 0; }
            case PHANTOM -> { th = 64; u = 0; v = 20; }
            case SHULKER -> { th = 64; u = 0; v = 28; }
            case DOLPHIN -> { th = 64; u = 22; v = 0; }
            case SKELETON -> { u = 32; v = 16; }
            case SPIDER -> { u = 0; v = 12; }
            case SLIME -> { u = 0; v = 16; }
            case PIGLIN, ZOMBIFIED_PIGLIN, PIGLIN_REQUIEM -> { th = 64; u = 40; v = 16; }
            case GER -> { tw = 128; th = 128; u = 60; v = 21; }
            default -> { }
        }
        MeshDefinition mesh = new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("fist",
                CubeListBuilder.create().texOffs(u, v).addBox(-2.5F, -2.5F, -11.0F, 5.0F, 5.0F, 11.0F), PartPose.ZERO);
        return LayerDefinition.create(mesh, tw, th).bakeRoot().getChild("fist");
    }

    // ------------------------------------------------------------------ per-mob idle stances

    /** Whole-body stance in degrees: lean back (+) / forward (-), sideways tilt, turn away from the camera. */
    float lean, tilt, turn;

    private void initStance() {
        switch (type) {
            case SKELETON -> { lean = 0; tilt = 4; turn = 0; }      // pressed behind you, leaning in
            case ENDERMAN, ENDER_WORLD -> { lean = -3; tilt = 5; turn = 0; }     // looming over you, hand on your shoulder
            case IRON_GOLEM, GER -> { lean = 0; tilt = 0; turn = 0; }    // standing normally
            case CREEPER, CREEPER_REQUIEM -> { lean = 0; tilt = 5; turn = 0; }       // pressed behind you, leaning in
            case BLAZE -> { lean = 0; tilt = 4; turn = 0; }         // pressed behind you, leaning in
            case WARDEN -> { lean = -5; tilt = -3; turn = 0; }      // hunched predator
            case WARDEN_REQUIEM -> { lean = -9; tilt = 0; turn = 0; }   // bent over you from behind
            case PHANTOM -> { lean = -10; tilt = 14; turn = 0; }    // banking glide
            case SPIDER -> { lean = 0; tilt = 0; turn = 0; }        // clinging to your back
            case SLIME -> { lean = 0; tilt = 0; turn = 0; }         // sits flat like a real slime
            case SQUID -> { lean = 0; tilt = 0; turn = 0; }         // hangs perfectly straight above you
            case PIG -> { lean = 0; tilt = 0; turn = 0; }           // just a pig
            case ALLAY -> { lean = 0; tilt = 0; turn = 0; }         // just an allay
            default -> { lean = 7; tilt = 4; turn = 0; }
        }
    }

    /** Walks a path of child names from the model root; null if this model doesn't have it. */
    private ModelPart part(String... path) {
        ModelPart p = root;
        for (String name : path) {
            if (!p.hasChild(name)) return null;
            p = p.getChild(name);
        }
        return p;
    }

    /** Head offsets: pitch/yaw add to whatever the base animation set (plus a slow look-around), roll is set. */
    private void headRot(ModelPart p, float x, float y, float z) {
        if (p == null) return;
        p.xRot += x;
        p.yRot += y + lookAround;
        p.zRot = z;
    }

    private float lookAround;
    /** GER: 0..1 how far into its pose it is (the arm rises smoothly), and the last age seen. */
    private float gerPose, gerLastAge = -1.0F;
    /** Pigstep's pose, faded in and out (0..1). */
    private float pigPose;
    private float pigLastAge = -1.0F;

    private static void rot(ModelPart p, float x, float y, float z) {
        if (p == null) return;
        p.xRot = x;
        p.yRot = y;
        p.zRot = z;
    }

    /** A different idle pose for every mob (placeholders until the specifics are decided). */
    /**
     * Looks this way, then that way: holds a random direction for a few seconds, then turns to a new one
     * (pitch in x, yaw in y), with a quick smooth turn in between.
     */
    private static float[] glance(float age, int seed) {
        float seg = 50.0F + (seed & 7) * 6.0F;
        int n = (int) Math.floor(age / seg);
        float f = Mth.clamp((age - n * seg) / 12.0F, 0.0F, 1.0F);
        f = f * f * (3.0F - 2.0F * f);
        java.util.Random a = new java.util.Random(seed * 31L + n), b = new java.util.Random(seed * 31L + n + 1);
        float yawA = (a.nextFloat() - 0.5F) * 1.6F, yawB = (b.nextFloat() - 0.5F) * 1.6F;
        float pitA = (a.nextFloat() - 0.5F) * 0.5F, pitB = (b.nextFloat() - 0.5F) * 0.5F;
        return new float[]{Mth.lerp(f, pitA, pitB), Mth.lerp(f, yawA, yawB)};
    }

    /**
     * The Phantom's wings: a slow, gliding beat - the tips trail behind the bases like real wings, and the tail
     * undulates with them. {@code lift} raises both wings (+) or sweeps them down (-) on top of the beat.
     */
    private void phantomWings(float age, float speed, float amp, float lift) {
        float t = age * speed;
        float beat = Mth.sin(t);
        ModelPart lb = part("body", "left_wing_base"), rb = part("body", "right_wing_base");
        ModelPart lt = part("body", "left_wing_base", "left_wing_tip"), rt = part("body", "right_wing_base", "right_wing_tip");
        rot(lb, 0.0F, 0.0F, 0.12F + beat * amp + lift);
        rot(rb, 0.0F, 0.0F, -0.12F - beat * amp - lift);
        float tip = Mth.sin(t - 0.9F) * amp * 1.3F + lift * 0.6F;
        rot(lt, 0.0F, 0.0F, tip);
        rot(rt, 0.0F, 0.0F, -tip);
        rot(part("body", "tail_base"), 0.12F + Mth.sin(t + 1.3F) * 0.09F, 0.0F, 0.0F);
        rot(part("body", "tail_base", "tail_tip"), 0.1F + Mth.sin(t + 2.1F) * 0.13F, 0.0F, 0.0F);
    }

    /** Phantom attacks: wings wind up high, then whip down hard as it strikes; Wing Lift beats fast; the Swarm spreads them wide. */
    private void phantomAttack(float progress, int slot, float age) {
        ModelPart head = part("body", "head");
        // The vanilla model never resets the head, so start from its rest pose every frame (otherwise each frame's
        // nod piles on top of the last and the head spins off).
        if (head != null) head.resetPose();
        if (slot == 2) { // Wing Lift: big, fast wingbeats
            phantomWings(age, 0.55F, 0.75F, 0.0F);
            return;
        }
        if (slot == 3) { // Midnight Swarm: wings flung wide and trembling, tail raised, head thrown back
            float spread = Mth.sin(Math.min(1.0F, progress * 3.0F) * Mth.HALF_PI);
            phantomWings(age, 0.9F, 0.08F, 0.55F * spread);
            if (head != null) head.xRot -= 0.35F * spread;
            rot(part("body", "tail_base"), 0.45F * spread, 0.0F, 0.0F);
            return;
        }
        // Talon Swipe / Sky Rush / combos: wind up (wings high), whip down on the hit, glide back.
        float lift;
        if (progress < 0.3F) {
            float k = progress / 0.3F;
            lift = 0.75F * (k * k * (3.0F - 2.0F * k));
        } else if (progress < 0.5F) {
            float k = (progress - 0.3F) / 0.2F;
            lift = Mth.lerp(k * k, 0.75F, -0.85F);
        } else {
            float k = (progress - 0.5F) / 0.5F;
            lift = Mth.lerp(k * k * (3.0F - 2.0F * k), -0.85F, 0.0F);
        }
        phantomWings(age, 0.11F, 0.1F, lift);
        if (head != null) head.xRot += Math.max(0.0F, -lift) * 0.45F; // bites down into the strike
        rot(part("body", "tail_base"), 0.12F + Math.max(0.0F, -lift) * 0.4F, 0.0F, 0.0F);
    }

    /**
     * The Ender World's pose (J): it stands dead still right behind you, arms hanging straight down at its sides,
     * staring straight ahead over your head. Unsettling. Only its breathing moves.
     */
    private void requiemPose(float age, float b) {
        headRot(part("head"), 0.0F, -lookAround, 0.0F);
        rot(part("right_arm"), b * 0.3F, 0.0F, 0.03F);
        rot(part("left_arm"), -b * 0.3F, 0.0F, -0.03F);
        rot(part("right_leg"), 0.0F, 0.0F, 0.0F);
        rot(part("left_leg"), 0.0F, 0.0F, 0.0F);
    }

    /**
     * Wonder of Deep: wrong, not cool. It stands right behind you, bent over you, completely still - no breathing,
     * no glancing around. Its head hangs tipped too far to one side, as if the neck doesn't hold it up, staring
     * down at the back of your head. Arms hang dead straight, a little too long, fingers almost at its knees.
     * Every few seconds the head jerks - a sharp, tiny twitch - and then it's still again. Now and then it
     * slowly rolls its head to the other side.
     */
    private void wonderOfDeepPose(float age) {
        lookAround = 0.0F;
        rot(part("bone", "body"), 0.32F, 0.0F, 0.0F);
        // Which side its head lolls to: changes very slowly, once in a long while.
        float roll = Mth.clamp(Mth.sin(age * 0.004F) * 3.0F, -1.0F, 1.0F);
        float twitch = 0.0F;
        int beat = (int) age % 83;
        if (beat < 2) twitch = 0.16F;              // snap
        else if (beat < 4) twitch = -0.06F;        // settle
        int beat2 = (int) (age + 31) % 137;
        float jerk = beat2 < 2 ? 0.12F : 0.0F;     // a second, out-of-step twitch so it never feels like a loop
        // Set outright (not added on top of the walk or your look): the head never bobs or follows, it only twitches.
        ModelPart head = part("bone", "body", "head");
        if (head != null) {
            head.xRot = 0.55F + jerk;
            head.yRot = 0.12F * roll;
            head.zRot = 0.62F * roll + twitch;
        }
        rot(part("bone", "body", "right_arm"), -0.32F, 0.0F, 0.04F);
        rot(part("bone", "body", "left_arm"), -0.32F, 0.0F, -0.04F);
        ModelPart ra = part("bone", "body", "right_arm");
        ModelPart la = part("bone", "body", "left_arm");
        if (ra != null) ra.y = ra.getInitialPose().y + 1.5F; // hanging a little lower than they should
        if (la != null) la.y = la.getInitialPose().y + 1.5F;
        // Legs far too long and thin: stretched down to the ground from where it's been lifted to.
        float legPx = 13.0F;
        float extra = (float) (com.mobstands.entity.StandEntity.WOD_RAISE * 16.0D / type.scale());
        float stretch = (legPx + extra) / legPx;
        for (String leg : new String[]{"right_leg", "left_leg"}) {
            ModelPart p = part("bone", leg);
            if (p == null) continue;
            rot(p, 0.0F, 0.0F, 0.0F); // it doesn't walk after you: it just glides along, legs dead straight
            p.yScale = stretch;
            p.xScale = 0.8F;
            p.zScale = 0.8F;
        }
    }

    private void idlePose(float age) {
        if (type == StandType.PIG || type == StandType.ALLAY) {
            // Looking around at random, like the real animal.
            float[] g = glance(age, type.ordinal() * 7919);
            ModelPart head = type == StandType.PIG ? part("head") : part("root", "head");
            if (head == null) head = part("head");
            if (head != null) {
                head.xRot += g[0];
                head.yRot += g[1];
            }
            return;
        }
        float b = Mth.sin(age * 0.06F) * 0.04F; // slow breathing
        lookAround = (type.closeBehind() || type == StandType.ENDERMAN || type == StandType.ENDER_WORLD) ? 0.0F
                : Mth.sin(age * 0.021F) * 0.28F + Mth.sin(age * 0.047F) * 0.08F; // idly glancing around
        if (type == StandType.ENDER_WORLD && posing) {
            requiemPose(age, b);
            return;
        }
        if (type == StandType.GER) {
            // The pose fades in: the arm sweeps up from its side.
            float step = gerLastAge < 0.0F ? 0.0F : Mth.clamp(age - gerLastAge, 0.0F, 2.0F);
            gerLastAge = age;
            gerPose = Mth.clamp(gerPose + (posing ? 0.07F : -0.1F) * step, 0.0F, 1.0F);
            float w = gerPose * gerPose * (3.0F - 2.0F * gerPose);
            float s = side; // 1: you're on its left
            // Like your reference: head leaning in toward yours, the long arm on your side raised up in front of
            // its body toward you, the other hanging at its side.
            headRot(part("head"), 0.06F * w, -0.15F * s * w, 0.22F * s * w);
            ModelPart near = s > 0 ? part("left_arm") : part("right_arm");
            ModelPart far = s > 0 ? part("right_arm") : part("left_arm");
            rot(near, Mth.lerp(w, -b * 0.5F, -1.05F + b * 0.3F), 0.0F, 0.0F);
            rot(far, b * 0.5F, 0.0F, 0.0F);
            rot(part("right_leg"), 0.0F, 0.0F, 0.0F);
            rot(part("left_leg"), 0.0F, 0.0F, 0.0F);
            return;
        }
        if (type == StandType.PIGLIN_REQUIEM) {
            // Pigstep: the boss of the Bastion. Arms folded high across its chest, chin up, looking down its snout
            // at whoever's in front of you; feet planted wide, leaning back a touch, shoulders rising with each
            // slow breath. Fades in as you strike your pose.
            float step = pigLastAge < 0.0F ? 0.0F : Mth.clamp(age - pigLastAge, 0.0F, 2.0F);
            pigLastAge = age;
            pigPose = Mth.clamp(pigPose + (posing ? 0.07F : -0.1F) * step, 0.0F, 1.0F);
            float w = pigPose * pigPose * (3.0F - 2.0F * pigPose);
            if (w > 0.001F) {
            headRot(part("head"), -0.2F * w, -lookAround * (1.0F - w), 0.0F);
            rot(part("body"), -0.06F * w, 0.0F, 0.0F);
            rot(part("right_arm"), Mth.lerp(w, b * 0.3F, -1.3F + b * 0.4F), Mth.lerp(w, 0.0F, -0.8F), Mth.lerp(w, 0.03F, 0.05F));
            rot(part("left_arm"), Mth.lerp(w, -b * 0.3F, -1.15F + b * 0.4F), Mth.lerp(w, 0.0F, 0.8F), Mth.lerp(w, -0.03F, -0.05F));
            rot(part("right_leg"), 0.0F, 0.0F, 0.14F * w);
            rot(part("left_leg"), 0.0F, 0.0F, -0.14F * w);
            return;
            }
        }
        switch (type) {
            case SKELETON -> {
                // Right behind you: still, calm glare straight ahead, one arm laid across the chest,
                // bow hanging at its side in the other hand.
                headRot(part("head"), 0.1F, 0.0F, 0.0F);
                rot(part("left_arm"), -1.1F + b, 0.85F, 0.0F);
                rot(part("right_arm"), 0.05F, 0.0F, 0.04F);
                rot(part("right_leg"), 0.0F, 0.0F, 0.0F);
                rot(part("left_leg"), 0.0F, 0.0F, 0.0F);
            }
            case ENDERMAN, ENDER_WORLD -> {
                // Looming right behind you: its long left arm reaches forward and down to rest on your shoulder
                // (you stand to its front-left), the right arm hangs loose, head tipped down, staring ahead.
                headRot(part("head"), 0.18F, 0.0F, -0.08F * side);
                // The reaching arm is whichever one is on your side.
                ModelPart reach = part(side > 0 ? "left_arm" : "right_arm");
                ModelPart loose = part(side > 0 ? "right_arm" : "left_arm");
                rot(reach, -0.85F + b * 0.5F, 0.15F * side, -0.42F * side);
                rot(loose, 0.12F - b, 0.0F, 0.08F * side);
                rot(part("left_leg"), 0.0F, 0.0F, 0.0F);
                rot(part("right_leg"), 0.0F, 0.0F, 0.0F);
            }
            case IRON_GOLEM -> {
                // Standing normally: the vanilla Iron Golem stance (arms hanging), just a slow breath.
                ModelPart ra = part("right_arm"), la = part("left_arm");
                if (ra != null) ra.xRot += b * 0.5F;
                if (la != null) la.xRot -= b * 0.5F;
            }
            case CREEPER, CREEPER_REQUIEM -> {
                // Right behind you: dead still, chin slightly down, staring straight ahead.
                headRot(part("head"), 0.12F, 0.0F, 0.0F);
            }
            case BLAZE -> {
                // Right behind you: glaring straight ahead, chin slightly down.
                headRot(part("head"), 0.12F, 0.0F, 0.0F);
            }
            case WARDEN_REQUIEM -> wonderOfDeepPose(age);
            case WARDEN -> {
                // Hunched body, arms spread wide and low, head pushed forward.
                rot(part("bone", "body"), 0.25F, 0.0F, 0.0F);
                headRot(part("bone", "body", "head"), -0.25F, 0.0F, 0.0F);
                rot(part("bone", "body", "right_arm"), -0.2F + b, 0.0F, 0.45F);
                rot(part("bone", "body", "left_arm"), -0.2F - b, 0.0F, -0.45F);
            }
            case PHANTOM -> phantomWings(age, posing ? 0.075F : 0.11F, posing ? 0.5F : 0.3F, 0.0F);
            case SPIDER -> {
                // Clinging to your back: every leg folded in to wrap around you (keeps the walk animation's
                // forward/back swing so they still crawl), head tucked in.
                headRot(part("head"), 0.1F, 0.0F, 0.0F);
                for (String leg : new String[]{"right_hind_leg", "right_middle_hind_leg", "right_middle_front_leg", "right_front_leg"}) {
                    ModelPart p = part(leg);
                    if (p != null) p.zRot = -1.25F + b;
                }
                for (String leg : new String[]{"left_hind_leg", "left_middle_hind_leg", "left_middle_front_leg", "left_front_leg"}) {
                    ModelPart p = part(leg);
                    if (p != null) p.zRot = 1.25F - b;
                }
            }
            default -> { }
        }
    }
}
