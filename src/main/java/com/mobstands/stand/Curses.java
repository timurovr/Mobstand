package com.mobstands.stand;

import com.mobstands.entity.CurseEntity;
import com.mobstands.network.RpsPayload;
import com.mobstands.registry.ModEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cursed Stands: Stands nobody would choose. You never get them from an arrow - something happens to you, and one
 * latches on. They ride along beside your real Stand (if you have one) and mess with you until you get rid of them.
 * <ul>
 * <li><b>Cheap Trick</b> (Vex) - a Vex hits you and one stays, clinging to your back. It whispers, it gives you away
 * to monsters, it grabs your hotbar. Let a villager or another player see your back and it jumps onto them.</li>
 * <li><b>Survivor</b> (Drowned) - struck by lightning while you're in water (or hit by a Drowned in a thunderstorm).
 * Every so often a jolt goes through everyone around you and they all turn on each other. Fizzles out after 10 minutes.</li>
 * <li><b>Notorious B.I.G.</b> (Magma Cube) - killed by a Magma Cube, and something comes back with you: a burning blob
 * that chases whatever is moving fastest, eats it and grows. It can't be hurt. Stand still or sneak and it loses
 * interest. Sleep through a night and it's gone.</li>
 * <li><b>Boy II Man</b> (Witch) - a Witch's potion gets you, and a little Witch moves in. Every two minutes it makes
 * you play rock-paper-scissors. Lose and it takes a heart; win and you get one back. Win three times and it leaves,
 * giving everything back.</li>
 * </ul>
 * While a curse has you, your own Stand is smothered: it can't be summoned or used until the curse is gone.
 * One curse at a time. Dying gets rid of any of them (Cheap Trick hops onto whoever killed you, if they're a player).
 */
public final class Curses {
    private Curses() {}

    public static final String[] NAMES = {"Cheap Trick", "Survivor", "Notorious B.I.G.", "Boy II Man"};
    public static final int[] COLORS = {0x9FB6CF, 0x3FA79A, 0xFF7A1C, 0x8F5FC4};
    /** For /curse: the command id, the mob, how you catch it, what it does, and how you get rid of it. */
    public static final String[] IDS = {"cheap_trick", "survivor", "notorious_big", "boy_ii_man"};
    public static final String[] MOBS = {"Vex", "Drowned", "Magma Cube", "Witch"};
    public static final String[] CATCH = {
            "A Vex hits you (1 in 20).",
            "Struck by lightning while you're in water, or hit by a Drowned in a thunderstorm (1 in 4).",
            "Killed by a Magma Cube: it's waiting where you died.",
            "A Witch's potion gets you (1 in 5).",
    };
    public static final String[] DOES = {
            "Clings to your back, whispers in chat, fakes mob noises behind you (creeper hisses!), calls monsters on you and yanks your hotbar.",
            "Sits on your shoulder. Every 20 seconds a jolt makes every mob near you turn on each other (or on you). Players nearby get Strength.",
            "A burning blob that chases whatever moves fastest, phases through walls, eats it, sets it on fire and grows. It can't be hurt.",
            "Floats at your side and makes you play rock-paper-scissors every 2 minutes. Lose and it takes a heart; win and you get one back.",
    };
    public static final String[] LOSE = {
            "Let a villager or another player see your back for 2 seconds: it jumps onto them.",
            "Wears off after 10 minutes.",
            "Sneak or stand still and it loses interest. Sleep through a night (or wait 20 minutes) and it's gone.",
            "Win 3 rounds.",
    };

    public static int idOf(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return -1;
    }
    private static final String KEY = "mobstands:curse", BIG_PENDING = "mobstands:big_pending";
    private static final ResourceLocation HEART_THIEF = ResourceLocation.fromNamespaceAndPath("mobstands", "boy_ii_man");

    public static final int SURVIVOR_TICKS = 20 * 60 * 10;
    public static final int RPS_EVERY = 20 * 120;
    public static final int RPS_TIMEOUT = 20 * 15;
    public static final int RPS_WINS = 3;
    public static final int BIG_LIFE = 20 * 60 * 20;

    private static final class State {
        int kind;
        CurseEntity ent;
        long since, next, next2, next3;
        // Cheap Trick: how long each onlooker has been looking at your back
        final Map<Integer, Integer> seen = new HashMap<>();
        // Boy II Man
        int wins, losses, stolen;
        boolean stolenAll;
        final List<Integer> order = new ArrayList<>();
        boolean waiting;
        long askedAt;
    }

    private static final Map<UUID, State> CURSES = new HashMap<>();
    /** Cheap Trick riding a villager after it jumped off a player: the vex and when it lets go. */
    private static final Map<CurseEntity, Long> ON_VILLAGERS = new HashMap<>();

    // ------------------------------------------------------------------ queries

    public static boolean owns(CurseEntity e) {
        if (ON_VILLAGERS.containsKey(e)) return true;
        for (State s : CURSES.values()) if (s.ent == e) return true;
        return false;
    }

    public static int kindOf(Player p) {
        State s = CURSES.get(p.getUUID());
        return s == null ? -1 : s.kind;
    }

    /** A cursed Stand is smothering your own: it can't be summoned or used until the curse is gone. */
    public static boolean suppressed(ServerPlayer p) {
        return !CURSES.isEmpty() && CURSES.containsKey(p.getUUID());
    }

    /** "Cheap Trick won't let your Stand out." */
    public static Component suppressedMessage(ServerPlayer p) {
        State s = CURSES.get(p.getUUID());
        String who = s != null ? NAMES[s.kind] : "A cursed Stand";
        return Component.literal(who + " is smothering your Stand. Get rid of it first.").withStyle(st -> st.withColor(0xC04060));
    }

    // ------------------------------------------------------------------ getting cursed

    /** Latch a curse onto a player (if they don't already have one). */
    public static boolean curse(ServerPlayer p, int kind) {
        if (CURSES.containsKey(p.getUUID())) return false;
        ServerLevel sl = p.serverLevel();
        State s = new State();
        s.kind = kind;
        s.since = sl.getGameTime();
        s.next = s.since + 200 + sl.random.nextInt(200);
        s.next2 = s.since + 600 + sl.random.nextInt(600);
        s.next3 = s.since + 300 + sl.random.nextInt(300);
        if (kind == CurseEntity.BOY_II_MAN) s.next = s.since + 200;
        if (kind == CurseEntity.BIG) {
            spawnBig(p, s, p.position());
        } else {
            s.ent = spawn(sl, kind, p, p);
        }
        CURSES.put(p.getUUID(), s);
        save(p, s);
        smother(p);
        announce(p, kind);
        return true;
    }

    /** Your own Stand is forced back in while the curse has you. */
    private static void smother(ServerPlayer p) {
        if (StandManager.data(p).isSummoned()) StandManager.dismiss(p);
    }

    private static CurseEntity spawn(ServerLevel sl, int kind, Entity host, ServerPlayer owner) {
        CurseEntity e = ModEntities.CURSE.get().create(sl);
        e.setup(kind, host.getId(), owner != null ? owner.getId() : -1);
        e.setPos(host.getX(), host.getY() + 1.0D, host.getZ());
        sl.addFreshEntity(e);
        return e;
    }

    private static void spawnBig(ServerPlayer p, State s, Vec3 at) {
        ServerLevel sl = p.serverLevel();
        CurseEntity e = ModEntities.CURSE.get().create(sl);
        e.setup(CurseEntity.BIG, -1, p.getId());
        e.setSize(0.7F);
        e.setPos(at.x, at.y + 0.2D, at.z);
        sl.addFreshEntity(e);
        sl.sendParticles(ParticleTypes.FLAME, at.x, at.y + 0.5D, at.z, 30, 0.4D, 0.4D, 0.4D, 0.1D);
        sl.playSound(null, at.x, at.y, at.z, SoundEvents.MAGMA_CUBE_SQUISH, SoundSource.HOSTILE, 1.2F, 0.5F);
        s.ent = e;
    }

    private static void announce(ServerPlayer p, int kind) {
        String how = switch (kind) {
            case CurseEntity.CHEAP_TRICK -> "Something is clinging to your back. Don't let anyone see it... or do. Let a villager or player see your back to get rid of it.";
            case CurseEntity.SURVIVOR -> "A jolt runs through you. Everyone around you is going to want to fight. (Wears off in 10 minutes.)";
            case CurseEntity.BIG -> "Something burning came back with you. It chases whatever moves fastest. Don't run. Sleep through a night to lose it.";
            default -> "A little Witch wants to play a game. Lose, and it takes a heart. Win " + RPS_WINS + " times to get rid of it.";
        };
        p.sendSystemMessage(Component.literal("☠ Cursed Stand: ").withStyle(ChatFormatting.DARK_RED)
                .append(Component.literal(NAMES[kind]).withStyle(st -> st.withColor(COLORS[kind]).withBold(true))));
        p.sendSystemMessage(Component.literal(how).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.PLAYERS, 0.5F, 1.4F);
    }

    // ------------------------------------------------------------------ getting rid of them

    public static void lift(ServerPlayer p, String why) {
        State s = CURSES.remove(p.getUUID());
        if (s == null) return;
        if (s.ent != null && !s.ent.isRemoved()) s.ent.discard();
        if (s.kind == CurseEntity.BOY_II_MAN) restoreAll(p, s);
        p.getPersistentData().remove(KEY);
        if (why != null) {
            p.sendSystemMessage(Component.literal(NAMES[s.kind] + " ").withStyle(st -> st.withColor(COLORS[s.kind]))
                    .append(Component.literal(why).withStyle(ChatFormatting.GRAY)));
        }
        if (StandManager.data(p).getType() != null) {
            p.displayClientMessage(Component.literal("Your Stand is free again.").withStyle(ChatFormatting.GREEN), true);
        }
    }

    /** Leaving the game: the curse is kept for next time, its body isn't. */
    public static void logout(ServerPlayer p) {
        State s = CURSES.remove(p.getUUID());
        if (s == null) return;
        if (s.ent != null && !s.ent.isRemoved()) s.ent.discard();
        if (s.kind == CurseEntity.BIG) p.getPersistentData().remove(KEY); // B.I.G. doesn't wait for you
        else save(p, s);
    }

    public static void login(ServerPlayer p) {
        CompoundTag t = p.getPersistentData().getCompound(KEY);
        if (t.isEmpty() || CURSES.containsKey(p.getUUID())) return;
        State s = new State();
        s.kind = t.getInt("kind");
        long now = p.serverLevel().getGameTime();
        s.since = now - t.getLong("age");
        s.next = now + 200;
        s.next2 = now + 600;
        s.next3 = now + 300;
        s.wins = t.getInt("wins");
        s.losses = t.getInt("losses");
        s.stolen = t.getInt("stolen");
        s.stolenAll = t.getBoolean("all");
        for (int i : t.getIntArray("order")) s.order.add(i);
        s.ent = spawn(p.serverLevel(), s.kind, p, p);
        CURSES.put(p.getUUID(), s);
        if (s.kind == CurseEntity.BOY_II_MAN) {
            applyHearts(p, s);
            syncData(s);
        }
    }

    private static void save(ServerPlayer p, State s) {
        if (s.kind == CurseEntity.BIG) return;
        CompoundTag t = new CompoundTag();
        t.putInt("kind", s.kind);
        t.putLong("age", p.serverLevel().getGameTime() - s.since);
        t.putInt("wins", s.wins);
        t.putInt("losses", s.losses);
        t.putInt("stolen", s.stolen);
        t.putBoolean("all", s.stolenAll);
        t.putIntArray("order", s.order);
        p.getPersistentData().put(KEY, t);
    }

    /** Dying: the curse lets go. Cheap Trick jumps onto the player who killed you; a Magma Cube killing you starts B.I.G. */
    public static void onDeath(ServerPlayer p, DamageSource src) {
        State s = CURSES.get(p.getUUID());
        if (s != null) {
            boolean trick = s.kind == CurseEntity.CHEAP_TRICK;
            lift(p, null);
            if (trick && src.getEntity() instanceof ServerPlayer killer && killer != p && curse(killer, CurseEntity.CHEAP_TRICK)) {
                killer.sendSystemMessage(Component.literal("<Cheap Trick> ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("New back! Nice and wide. Let's be friends.").withStyle(ChatFormatting.ITALIC)));
            }
        }
        if (src.getEntity() instanceof net.minecraft.world.entity.monster.MagmaCube) {
            CompoundTag persisted = p.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
            CompoundTag b = new CompoundTag();
            b.putDouble("x", p.getX());
            b.putDouble("y", p.getY());
            b.putDouble("z", p.getZ());
            b.putString("dim", p.level().dimension().location().toString());
            persisted.put(BIG_PENDING, b);
            p.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        }
    }

    /** Back from the dead after a Magma Cube got you: B.I.G. is waiting where you died. */
    public static void onRespawn(ServerPlayer p) {
        CompoundTag persisted = p.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        if (!persisted.contains(BIG_PENDING)) return;
        CompoundTag b = persisted.getCompound(BIG_PENDING);
        persisted.remove(BIG_PENDING);
        p.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        if (CURSES.containsKey(p.getUUID())) return;
        ResourceKey<Level> dim = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.parse(b.getString("dim")));
        ServerLevel sl = p.server.getLevel(dim);
        if (sl == null) return;
        State s = new State();
        s.kind = CurseEntity.BIG;
        s.since = sl.getGameTime();
        CurseEntity e = ModEntities.CURSE.get().create(sl);
        e.setup(CurseEntity.BIG, -1, p.getId());
        e.setSize(0.7F);
        e.setPos(b.getDouble("x"), b.getDouble("y") + 0.2D, b.getDouble("z"));
        sl.addFreshEntity(e);
        s.ent = e;
        CURSES.put(p.getUUID(), s);
        smother(p);
        announce(p, CurseEntity.BIG);
    }

    /** Sleeping through the night: B.I.G. loses you. */
    public static void onSlept(ServerPlayer p) {
        State s = CURSES.get(p.getUUID());
        if (s != null && s.kind == CurseEntity.BIG) lift(p, "lost track of you while you slept.");
    }

    public static void shutdown() {
        CURSES.clear();
        ON_VILLAGERS.clear();
    }

    // ------------------------------------------------------------------ every tick

    public static void tick(MinecraftServer server) {
        if (!ON_VILLAGERS.isEmpty()) tickVillagers();
        if (CURSES.isEmpty()) return;
        Iterator<Map.Entry<UUID, State>> it = CURSES.entrySet().iterator();
        List<Runnable> after = new ArrayList<>();
        while (it.hasNext()) {
            Map.Entry<UUID, State> en = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            State s = en.getValue();
            if (p == null) continue;
            if (s.ent == null || s.ent.isRemoved()) {
                if (s.kind == CurseEntity.BIG) {
                    after.add(() -> lift(p, null));
                    continue;
                }
                s.ent = spawn(p.serverLevel(), s.kind, p, p);
            }
            if (s.kind != CurseEntity.BIG) {
                if (s.ent.level() != p.level()) {
                    s.ent.discard();
                    s.ent = spawn(p.serverLevel(), s.kind, p, p);
                }
                s.ent.setPos(p.getX(), p.getY() + 1.0D, p.getZ());
                s.ent.setOwner(p.getId());
                s.ent.setHost(p.getId());
            } else {
                s.ent.setOwner(p.getId());
            }
            if (p.tickCount % 10 == 0) smother(p);
            long now = p.serverLevel().getGameTime();
            switch (s.kind) {
                case CurseEntity.CHEAP_TRICK -> tickCheapTrick(p, s, now, after);
                case CurseEntity.SURVIVOR -> tickSurvivor(p, s, now, after);
                case CurseEntity.BOY_II_MAN -> tickBoy(p, s, now);
                default -> { }
            }
        }
        for (Runnable r : after) r.run();
    }

    // ------------------------------------------------------------------ Cheap Trick

    private static final String[] WHISPERS = {
            "Don't turn around.",
            "Hey. Hey. Your shoelace is untied.",
            "I'd love to see your back. I mean, I'm ON it, but you know what I mean.",
            "Psst. There's a creeper behind you. Kidding. Or am I?",
            "Wow, you're really walking like that? In public?",
            "I told everyone your bed is just a respawn point with extra steps.",
            "Show someone your back~ go on~",
            "You missed a diamond back there. Don't check.",
            "Nice armour. Does it come in a less embarrassing colour?",
            "If a villager looks at your back, I'll leave. Probably. Maybe.",
            "Your Stand's cool and all. I'm cooler.",
            "I can hear your heartbeat. It's off-beat.",
    };

    private static void tickCheapTrick(ServerPlayer p, State s, long now, List<Runnable> after) {
        ServerLevel sl = p.serverLevel();
        // Whispering in your ear.
        if (now >= s.next) {
            s.next = now + 500 + sl.random.nextInt(700);
            whisper(p, WHISPERS[sl.random.nextInt(WHISPERS.length)]);
            p.playNotifySound(SoundEvents.VEX_AMBIENT, SoundSource.HOSTILE, 0.35F, 1.5F);
        }
        // Mischief: giving you away to monsters, or yanking your hotbar.
        if (now >= s.next2) {
            s.next2 = now + 700 + sl.random.nextInt(900);
            if (sl.random.nextBoolean()) {
                List<Monster> ms = sl.getEntitiesOfClass(Monster.class, p.getBoundingBox().inflate(20.0D), m -> m.isAlive() && m.getTarget() == null);
                if (!ms.isEmpty()) {
                    whisper(p, "HEY! OVER HERE! THEY'RE RIGHT HERE!");
                    sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.VEX_CHARGE, SoundSource.HOSTILE, 1.0F, 1.3F);
                    for (Monster m : ms) m.setTarget(p);
                }
            } else {
                int slot = sl.random.nextInt(9);
                p.getInventory().selected = slot;
                p.connection.send(new ClientboundSetCarriedItemPacket(slot));
                whisper(p, "Oops. My hand slipped. On your hotbar.");
            }
        }
        // Phantom noises only you can hear, right behind you: a creeper hissing, footsteps, something breathing...
        if (now >= s.next3) {
            s.next3 = now + 300 + sl.random.nextInt(600);
            prank(p, sl);
        }
        // Someone looking at your back for two seconds: it jumps onto them.
        Vec3 look = p.getLookAngle();
        Vec3 face = new Vec3(look.x, 0.0D, look.z).normalize();
        for (LivingEntity v : sl.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(10.0D),
                x -> x != p && x.isAlive() && (x instanceof ServerPlayer || x instanceof AbstractVillager))) {
            Vec3 to = v.position().subtract(p.position());
            Vec3 toH = new Vec3(to.x, 0.0D, to.z);
            boolean behind = toH.lengthSqr() > 0.01D && toH.normalize().dot(face) < -0.45D;
            boolean watching = v instanceof AbstractVillager
                    || v.getLookAngle().dot(p.getEyePosition().subtract(v.getEyePosition()).normalize()) > 0.7D;
            if (behind && watching && v.hasLineOfSight(p) && to.length() < (v instanceof AbstractVillager ? 6.0D : 10.0D)) {
                int n = s.seen.merge(v.getId(), 1, Integer::sum);
                if (n == 10) whisper(p, "Oh? OH? Someone's looking at your back...");
                if (n >= 40) {
                    after.add(() -> jump(p, s, v));
                    return;
                }
            } else s.seen.remove(v.getId());
        }
    }

    private static void jump(ServerPlayer from, State s, LivingEntity to) {
        ServerLevel sl = from.serverLevel();
        sl.playSound(null, to.getX(), to.getY(), to.getZ(), SoundEvents.VEX_CHARGE, SoundSource.HOSTILE, 1.0F, 1.0F);
        sl.sendParticles(ParticleTypes.SOUL, to.getX(), to.getY() + 1.2D, to.getZ(), 12, 0.3D, 0.4D, 0.3D, 0.02D);
        if (to instanceof ServerPlayer tp) {
            lift(from, "saw someone better and jumped off your back.");
            curse(tp, CurseEntity.CHEAP_TRICK);
            whisper(tp, "Hi! I live here now.");
            return;
        }
        // A villager: it rides them for a minute, then wanders off.
        CurseEntity e = s.ent;
        CURSES.remove(from.getUUID());
        from.getPersistentData().remove(KEY);
        from.sendSystemMessage(Component.literal("Cheap Trick ").withStyle(st -> st.withColor(COLORS[0]))
                .append(Component.literal("jumped onto the villager's back. Poor guy.").withStyle(ChatFormatting.GRAY)));
        if (e != null && !e.isRemoved()) {
            e.setHost(to.getId());
            e.setOwner(-1);
            ON_VILLAGERS.put(e, sl.getGameTime() + 1200);
        }
        if (to instanceof Mob m) m.getNavigation().stop();
    }

    private static void tickVillagers() {
        Iterator<Map.Entry<CurseEntity, Long>> it = ON_VILLAGERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<CurseEntity, Long> en = it.next();
            CurseEntity e = en.getKey();
            Entity host = e.level().getEntity(e.host());
            if (e.isRemoved() || host == null || !host.isAlive() || e.level().getGameTime() > en.getValue()) {
                it.remove();
                if (!e.isRemoved()) {
                    ((ServerLevel) e.level()).sendParticles(ParticleTypes.SOUL, e.getX(), e.getY(), e.getZ(), 8, 0.2D, 0.2D, 0.2D, 0.02D);
                    e.discard();
                }
                continue;
            }
            e.setPos(host.getX(), host.getY() + 1.0D, host.getZ());
            if (host.tickCount % 60 == 0) host.playSound(SoundEvents.VILLAGER_NO, 1.0F, 1.0F);
        }
    }

    /** Sounds Cheap Trick fakes (the creeper hiss most of all). */
    private static final net.minecraft.sounds.SoundEvent[] FAKES = {
            SoundEvents.CREEPER_PRIMED, SoundEvents.CREEPER_PRIMED, SoundEvents.CREEPER_PRIMED, SoundEvents.CREEPER_PRIMED,
            SoundEvents.ZOMBIE_AMBIENT, SoundEvents.SKELETON_AMBIENT, SoundEvents.SPIDER_AMBIENT, SoundEvents.ENDERMAN_STARE,
            SoundEvents.ENDERMAN_TELEPORT, SoundEvents.TNT_PRIMED, SoundEvents.ARROW_SHOOT, SoundEvents.WARDEN_HEARTBEAT,
            SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundEvents.PHANTOM_AMBIENT, SoundEvents.WITCH_CELEBRATE,
    };
    private static final String[] GIGGLES = {"hehehe", "...did you hear that?", "Behind you. No, the OTHER behind you.",
            "Pfft. You flinched.", "That one was real. Probably.", "Hssssss... hehe."};

    private static void prank(ServerPlayer p, ServerLevel sl) {
        net.minecraft.sounds.SoundEvent snd = FAKES[sl.random.nextInt(FAKES.length)];
        // just behind you, a little off to one side - only you hear it
        float yr = p.getYRot() * Mth.DEG_TO_RAD + (sl.random.nextFloat() - 0.5F) * 1.4F;
        double d = 1.5D + sl.random.nextDouble() * 2.5D;
        double x = p.getX() + Mth.sin(yr) * d, z = p.getZ() - Mth.cos(yr) * d, y = p.getY() + 0.5D;
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(snd), SoundSource.HOSTILE,
                x, y, z, 1.0F, 0.9F + sl.random.nextFloat() * 0.2F, sl.random.nextLong()));
        // Sometimes it can't help itself.
        if (sl.random.nextFloat() < 0.3F) {
            TickScheduler.later(40 + sl.random.nextInt(30), () -> {
                if (p.isAlive() && kindOf(p) == CurseEntity.CHEAP_TRICK) whisper(p, GIGGLES[sl.random.nextInt(GIGGLES.length)]);
            });
        }
    }

    private static void whisper(ServerPlayer p, String line) {
        p.sendSystemMessage(Component.literal("<Cheap Trick> ").withStyle(st -> st.withColor(COLORS[0]))
                .append(Component.literal(line).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
    }

    // ------------------------------------------------------------------ Survivor

    private static void tickSurvivor(ServerPlayer p, State s, long now, List<Runnable> after) {
        ServerLevel sl = p.serverLevel();
        if (now - s.since > SURVIVOR_TICKS) {
            after.add(() -> lift(p, "fizzled out. Everyone calms down."));
            return;
        }
        // A jolt every 20 seconds: everyone nearby wants to fight.
        if (now >= s.next) {
            s.next = now + 400;
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TRIDENT_THUNDER.value(), SoundSource.HOSTILE, 0.35F, 2.0F);
            sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 1.0D, p.getZ(), 40, 1.5D, 1.0D, 1.5D, 0.4D);
            List<Mob> mobs = sl.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(16.0D),
                    m -> m.isAlive() && !(m instanceof AbstractVillager));
            for (Mob m : mobs) {
                sl.sendParticles(ParticleTypes.ANGRY_VILLAGER, m.getX(), m.getY() + m.getBbHeight() + 0.3D, m.getZ(), 2, 0.2D, 0.1D, 0.2D, 0.0D);
                if (m instanceof Animal && !(m instanceof NeutralMob)) {
                    // the harmless ones just panic and scatter
                    m.getNavigation().moveTo(m.getX() + sl.random.nextInt(13) - 6, m.getY(), m.getZ() + sl.random.nextInt(13) - 6, 1.6D);
                    continue;
                }
                LivingEntity pick = null;
                if (sl.random.nextFloat() < 0.25F) pick = p;
                else {
                    List<LivingEntity> others = new ArrayList<>();
                    for (Mob o : mobs) if (o != m && o.distanceToSqr(m) < 144.0D) others.add(o);
                    if (!others.isEmpty()) pick = others.get(sl.random.nextInt(others.size()));
                }
                if (pick != null) {
                    m.setTarget(pick);
                    if (m instanceof NeutralMob nm) {
                        nm.setPersistentAngerTarget(pick.getUUID());
                        nm.startPersistentAngerTimer();
                    }
                }
            }
            for (ServerPlayer o : sl.getEntitiesOfClass(ServerPlayer.class, p.getBoundingBox().inflate(16.0D))) {
                o.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 160, 0));
                if (o != p) o.sendSystemMessage(Component.literal("A jolt runs through you. You really feel like hitting something.")
                        .withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.ITALIC));
            }
        }
    }

    // ------------------------------------------------------------------ Notorious B.I.G.

    private static final Map<CurseEntity, Map<Integer, Vec3>> SEEN = new java.util.WeakHashMap<>();

    public static void tickBig(CurseEntity e, ServerLevel sl) {
        State s = null;
        for (State st : CURSES.values()) if (st.ent == e) s = st;
        if (s == null || sl.getGameTime() - s.since > BIG_LIFE || e.size() < 0.25F) {
            sl.sendParticles(ParticleTypes.FLAME, e.getX(), e.getY() + 0.3D, e.getZ(), 20, 0.3D, 0.3D, 0.3D, 0.1D);
            e.discard();
            return;
        }
        // Chase whatever is moving fastest within 24 blocks (it remembers where everything was last tick).
        Map<Integer, Vec3> last = SEEN.computeIfAbsent(e, k -> new HashMap<>());
        Map<Integer, Vec3> now = new HashMap<>();
        Entity best = null;
        double bestSpeed = 0.06D;
        for (Entity x : sl.getEntities(e, e.getBoundingBox().inflate(24.0D),
                x -> x.isAlive() && !(x instanceof CurseEntity) && !(x instanceof com.mobstands.entity.StandEntity)
                        && (x instanceof LivingEntity || x instanceof ItemEntity || x instanceof Projectile))) {
            if (x instanceof Player pl && (pl.isCreative() || pl.isSpectator())) continue;
            now.put(x.getId(), x.position());
            Vec3 was = last.get(x.getId());
            double sp = was == null ? 0.0D : x.position().distanceTo(was);
            if (sp > 4.0D) sp = 0.0D; // (teleported)
            if (x instanceof Player pl && pl.isShiftKeyDown()) sp *= 0.3D;
            if (sp > bestSpeed) {
                bestSpeed = sp;
                best = x;
            }
        }
        SEEN.put(e, now);
        Vec3 pos = e.position();
        if (e.tickCount % 4 == 0) sl.sendParticles(ParticleTypes.LAVA, pos.x, pos.y + e.size() * 0.6D, pos.z, 1, e.size() * 0.3D, 0.1D, e.size() * 0.3D, 0.0D);
        if (best != null) {
            Vec3 tgt = best.position().add(0.0D, best.getBbHeight() * 0.3D, 0.0D);
            Vec3 d = tgt.subtract(pos);
            double step = Math.min(d.length(), Mth.clamp(bestSpeed * 1.12D, 0.12D, 1.3D));
            Vec3 np = pos.add(d.normalize().scale(step));
            e.setPos(np.x, np.y, np.z);
            if (e.tickCount % 10 == 0) sl.playSound(null, np.x, np.y, np.z, SoundEvents.MAGMA_CUBE_SQUISH, SoundSource.HOSTILE, 0.6F, 0.6F);
            // Eat it.
            if (e.getBoundingBox().inflate(0.4D).intersects(best.getBoundingBox())) {
                if (best instanceof ItemEntity || best instanceof Projectile) {
                    sl.sendParticles(ParticleTypes.FLAME, best.getX(), best.getY(), best.getZ(), 6, 0.1D, 0.1D, 0.1D, 0.05D);
                    sl.sendParticles(ParticleTypes.LARGE_SMOKE, best.getX(), best.getY(), best.getZ(), 3, 0.1D, 0.1D, 0.1D, 0.02D);
                    sl.playSound(null, best.getX(), best.getY(), best.getZ(), SoundEvents.GENERIC_EAT, SoundSource.HOSTILE, 0.8F, 0.7F);
                    best.discard();
                    e.setSize(Math.min(3.0F, e.size() + 0.05F));
                } else if (best instanceof LivingEntity le && e.tickCount % 10 == 0) {
                    le.invulnerableTime = 0;
                    le.hurt(sl.damageSources().magic(), 3.0F);
                    le.igniteForSeconds(4.0F); // it's molten
                    sl.playSound(null, le.getX(), le.getY(), le.getZ(), SoundEvents.GENERIC_EAT, SoundSource.HOSTILE, 1.0F, 0.6F);
                    e.setSize(Math.min(3.0F, e.size() + (le.isAlive() ? 0.04F : 0.25F)));
                }
            }
        } else {
            // Nothing moving: it sits there, slowly shrinking.
            e.setSize(e.size() - 0.0008F);
            // and settles to the ground
            BlockPos below = BlockPos.containing(pos.x, pos.y - 0.1D, pos.z);
            if (sl.getBlockState(below).getCollisionShape(sl, below).isEmpty()) e.setPos(pos.x, pos.y - 0.08D, pos.z);
        }
    }

    public static void bigHit(CurseEntity e, DamageSource src) {
        ServerLevel sl = (ServerLevel) e.level();
        e.setSize(Math.min(3.0F, e.size() + 0.08F));
        sl.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.MAGMA_CUBE_HURT, SoundSource.HOSTILE, 1.0F, 0.5F);
        sl.sendParticles(ParticleTypes.FLAME, e.getX(), e.getY() + e.size() * 0.5D, e.getZ(), 10, 0.3D, 0.3D, 0.3D, 0.1D);
        if (src.getEntity() instanceof ServerPlayer p) {
            p.displayClientMessage(Component.literal("It can't be hurt. It just got bigger.").withStyle(ChatFormatting.RED), true);
        }
    }

    // ------------------------------------------------------------------ Boy II Man

    private static final String[] TAUNTS = {
            "Rock, paper, scissors! Winner takes something!",
            "Let's play~ I already know what you'll pick.",
            "One round. If you lose, I'm taking something shiny.",
            "Rock paper scissors, shoot! ...Well? Pick!",
            "You look like a scissors person.",
    };

    private static void tickBoy(ServerPlayer p, State s, long now) {
        if (s.waiting) {
            if (now - s.askedAt > RPS_TIMEOUT) pick(p, -1); // too slow: it picks for you
            return;
        }
        if (now >= s.next) ask(p, s, TAUNTS[p.getRandom().nextInt(TAUNTS.length)]);
    }

    private static void ask(ServerPlayer p, State s, String taunt) {
        s.waiting = true;
        s.askedAt = p.serverLevel().getGameTime();
        p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WITCH_AMBIENT, SoundSource.HOSTILE, 1.0F, 1.5F);
        PacketDistributor.sendToPlayer(p, new RpsPayload(s.wins, s.losses, taunt, RPS_TIMEOUT));
    }

    private static final String[] HANDS = {"Rock", "Paper", "Scissors"};

    /** Your hand (0 rock, 1 paper, 2 scissors; -1 = you didn't pick, so it picks for you). */
    public static void pick(ServerPlayer p, int choice) {
        State s = CURSES.get(p.getUUID());
        if (s == null || s.kind != CurseEntity.BOY_II_MAN || !s.waiting) return;
        s.waiting = false;
        ServerLevel sl = p.serverLevel();
        if (choice < 0 || choice > 2) choice = sl.random.nextInt(3);
        // It plays fair... mostly. One time in four it reads your mind.
        int its = sl.random.nextFloat() < 0.25F ? (choice + 1) % 3 : sl.random.nextInt(3);
        MutableComponent line = Component.literal("<Boy II Man> ").withStyle(st -> st.withColor(COLORS[3]))
                .append(Component.literal("You: " + HANDS[choice] + "  Me: " + HANDS[its] + ". ").withStyle(ChatFormatting.WHITE));
        if (its == choice) {
            p.sendSystemMessage(line.append(Component.literal("A draw! Again!").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
            s.next = sl.getGameTime() + 20;
            return;
        }
        boolean youWin = (choice + 2) % 3 == its; // rock beats scissors, paper beats rock, scissors beats paper
        s.next = sl.getGameTime() + RPS_EVERY;
        if (youWin) {
            s.wins++;
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WITCH_HURT, SoundSource.HOSTILE, 1.0F, 1.3F);
            if (s.wins >= RPS_WINS) {
                p.sendSystemMessage(line.append(Component.literal("NO! Fine! Take it all back!").withStyle(ChatFormatting.GREEN)));
                lift(p, "gave everything back and left in a huff.");
                return;
            }
            String back = giveBack(p, s);
            p.sendSystemMessage(line.append(Component.literal("Hmph. " + back + " (" + s.wins + "/" + RPS_WINS + " wins)")
                    .withStyle(ChatFormatting.GREEN)));
        } else {
            s.losses++;
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WITCH_CELEBRATE, SoundSource.HOSTILE, 1.0F, 1.2F);
            sl.sendParticles(ParticleTypes.WITCH, p.getX(), p.getY() + 1.0D, p.getZ(), 20, 0.4D, 0.6D, 0.4D, 0.1D);
            String took = steal(p, s);
            p.sendSystemMessage(line.append(Component.literal("I win! " + took).withStyle(ChatFormatting.RED)));
        }
        syncData(s);
        save(p, s);
    }

    /** It takes something: your Stand's already smothered, so it takes hearts (one per loss, up to five). */
    private static String steal(ServerPlayer p, State s) {
        applyHearts(p, s);
        int hearts = Math.min(5, s.losses);
        return "I'll take a heart! (" + hearts + (hearts == 1 ? " heart" : " hearts") + " gone)";
    }

    private static String giveBack(ServerPlayer p, State s) {
        if (s.losses > 0) {
            s.losses--;
            applyHearts(p, s);
            return "Here's a heart back.";
        }
        return "Lucky.";
    }

    private static void applyHearts(ServerPlayer p, State s) {
        AttributeInstance a = p.getAttribute(Attributes.MAX_HEALTH);
        if (a == null) return;
        int lost = Math.min(10, s.losses * 2);
        a.removeModifier(HEART_THIEF);
        if (lost > 0) a.addTransientModifier(new AttributeModifier(HEART_THIEF, -lost, AttributeModifier.Operation.ADD_VALUE));
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
    }

    private static void restoreAll(ServerPlayer p, State s) {
        AttributeInstance a = p.getAttribute(Attributes.MAX_HEALTH);
        if (a != null) a.removeModifier(HEART_THIEF);
    }

    private static void syncData(State s) {
        if (s.ent == null) return;
        int d = (s.stolen & 0x3F) | (Math.min(15, s.wins) << 8) | (Math.min(15, s.losses) << 12) | (s.stolenAll ? 1 << 16 : 0);
        s.ent.setData(d);
    }

    // ------------------------------------------------------------------ catching them

    /** Damage you just took: maybe a curse latches on. */
    public static void onHurt(ServerPlayer p, DamageSource src) {
        if (CURSES.containsKey(p.getUUID())) return;
        Entity by = src.getEntity();
        var r = p.getRandom();
        if (by instanceof net.minecraft.world.entity.monster.Vex && r.nextFloat() < 0.05F) curse(p, CurseEntity.CHEAP_TRICK);
        else if (by instanceof net.minecraft.world.entity.monster.Drowned && p.level().isThundering() && r.nextFloat() < 0.25F)
            curse(p, CurseEntity.SURVIVOR);
        else if (by instanceof net.minecraft.world.entity.monster.Witch && r.nextFloat() < 0.2F) curse(p, CurseEntity.BOY_II_MAN);
    }

    /** A Witch's potion took effect on you. */
    public static void onWitchPotion(ServerPlayer p) {
        if (!CURSES.containsKey(p.getUUID()) && p.getRandom().nextFloat() < 0.2F) curse(p, CurseEntity.BOY_II_MAN);
    }

    /** Struck by lightning: if you're in water, Survivor wakes up in you. */
    public static void onLightning(ServerPlayer p) {
        if (p.isInWaterOrBubble()) {
            if (CURSES.containsKey(p.getUUID())) return;
            curse(p, CurseEntity.SURVIVOR);
        }
    }

    /** /stand curse ... (testing). */
    public static void force(ServerPlayer p, int kind) {
        lift(p, null);
        curse(p, kind);
        if (kind == CurseEntity.BOY_II_MAN) {
            State s = CURSES.get(p.getUUID());
            if (s != null) s.next = p.serverLevel().getGameTime() + 40;
        }
    }
}
