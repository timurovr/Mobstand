package com.mobstands.stand;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Requiem Trials: a Requiem is never rolled - it's earned. Once you've reached the End and your Stand is level 10,
 * its trial opens (shown in the Stand menu). Finish every step and your Stand evolves on the spot.
 *
 * <p>Progress is kept in {@link StandData#trialFlags} (steps done) and {@link StandData#trialCount} (a counter).
 */
public final class RequiemTrials {
    private RequiemTrials() {}

    public static final int TRIAL_LEVEL = 10;
    /** The Ender World: a full Minecraft day spent in the End. */
    public static final int END_DAY_TICKS = 24000;
    /** Gilded Swine: things stolen with Snatch. */
    public static final int SNATCH_GOAL = 30;

    public static final int F_DRAGON = 1, F_BRUTE = 2, F_WITHER = 4;

    /** The Requiem this Stand can become through a trial (null if none). */
    public static StandType evolution(StandType t) {
        if (t == StandType.ENDERMAN) return StandType.ENDER_WORLD;
        if (t == StandType.PIGLIN) return StandType.PIGLIN_REQUIEM;
        return null;
    }

    /** Is this player's trial open right now? */
    public static boolean open(StandData d) {
        return d.getType() != null && evolution(d.getType()) != null && d.getLevel() >= TRIAL_LEVEL && d.reachedEnd;
    }

    /** The trial steps, for the Stand menu: each line starts with "+" (done) or "-" (not yet). */
    public static List<String> lines(StandData d) {
        List<String> out = new ArrayList<>();
        StandType t = d.getType();
        if (t == StandType.ENDERMAN) {
            out.add(mark(d.trialFlags, F_DRAGON) + "Defeat the Ender Dragon with your Stand out");
            int mins = Math.min(END_DAY_TICKS, d.trialCount) / 1200;
            out.add((d.trialCount >= END_DAY_TICKS ? "+ " : "- ") + "Endure a full day in the End (" + mins + " / 20 min)");
            out.add("- Strike a pose at the exact moment of midnight");
        } else if (t == StandType.PIGLIN) {
            out.add((d.trialCount >= SNATCH_GOAL ? "+ " : "- ") + "Snatch " + SNATCH_GOAL + " things (" + Math.min(SNATCH_GOAL, d.trialCount) + " / " + SNATCH_GOAL + ")");
            out.add(mark(d.trialFlags, F_BRUTE) + "Snatch the axe of a Piglin Brute");
            out.add(mark(d.trialFlags, F_WITHER) + "Defeat the Wither with your Stand out");
        }
        return out;
    }

    private static String mark(int flags, int f) {
        return (flags & f) != 0 ? "+ " : "- ";
    }

    /** Once a second per player. */
    public static void tick(ServerPlayer p) {
        if (p.tickCount % 20 != 0) return;
        StandData d = StandManager.data(p);
        boolean end = StandManager.reached(p, StandType.Pool.END);
        boolean nether = StandManager.reached(p, StandType.Pool.NETHER);
        if (end != d.reachedEnd || nether != d.reachedNether) {
            d.reachedEnd = end;
            d.reachedNether = nether;
            StandManager.sync(p);
        }
        if (!open(d)) return;
        StandType t = d.getType();
        if (t == StandType.ENDERMAN) {
            if (p.level().dimension() == Level.END && d.trialCount < END_DAY_TICKS) {
                d.trialCount = Math.min(END_DAY_TICKS, d.trialCount + 20);
                if (d.trialCount % 1200 == 0 || d.trialCount >= END_DAY_TICKS) StandManager.sync(p);
                if (d.trialCount >= END_DAY_TICKS) step(p, "Time has passed you by in the End...");
            }
            // Midnight: posing with your Stand out, in the 10 seconds after the Overworld clock strikes 18000.
            if ((d.trialFlags & F_DRAGON) != 0 && d.trialCount >= END_DAY_TICKS && d.isSummoned()) {
                long tod = p.server.overworld().getDayTime() % 24000L;
                var s = StandManager.getStand(p);
                if (tod >= 18000L && tod < 18200L && s != null && s.isPosing()) evolve(p);
            }
        } else if (t == StandType.PIGLIN) {
            if (d.trialCount >= SNATCH_GOAL && (d.trialFlags & (F_BRUTE | F_WITHER)) == (F_BRUTE | F_WITHER)) evolve(p);
        }
    }

    /** Something died: bosses count for whoever stood nearby with their Stand out. */
    public static void onDeath(LivingEntity e) {
        if (!(e.level() instanceof ServerLevel sl)) return;
        int flag;
        StandType need;
        if (e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) {
            flag = F_DRAGON;
            need = StandType.ENDERMAN;
        } else if (e instanceof net.minecraft.world.entity.boss.wither.WitherBoss) {
            flag = F_WITHER;
            need = StandType.PIGLIN;
        } else {
            return;
        }
        for (ServerPlayer p : sl.players()) {
            StandData d = StandManager.data(p);
            if (d.getType() != need || !d.isSummoned() || !open(d) || (d.trialFlags & flag) != 0) continue;
            if (p.distanceToSqr(e) > 160.0D * 160.0D) continue;
            d.trialFlags |= flag;
            StandManager.sync(p);
            step(p, flag == F_DRAGON ? "The Dragon falls... the void remembers your Stand." : "The Wither falls... your Stand hungers for more.");
        }
    }

    /** Gilded Swine stole something with Snatch (from {@code victim}). */
    public static void onSnatch(ServerPlayer p, LivingEntity victim) {
        StandData d = StandManager.data(p);
        if (d.getType() != StandType.PIGLIN || !open(d)) return;
        boolean was = d.trialCount >= SNATCH_GOAL;
        d.trialCount++;
        if (!was && d.trialCount >= SNATCH_GOAL) step(p, "Your Stand's greed grows...");
        if (victim instanceof net.minecraft.world.entity.monster.piglin.PiglinBrute && (d.trialFlags & F_BRUTE) == 0) {
            d.trialFlags |= F_BRUTE;
            step(p, "You took the Brute's axe right out of its hands.");
        }
        StandManager.sync(p);
    }

    private static void step(ServerPlayer p, String msg) {
        p.sendSystemMessage(Component.literal(msg).withStyle(s -> s.withColor(0xD9A8FF).withItalic(true)));
        p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.0F, 0.7F);
    }

    /** Every step done: the Stand evolves into its Requiem, right here. */
    public static void evolve(ServerPlayer p) {
        StandData d = StandManager.data(p);
        StandType to = evolution(d.getType());
        if (to == null) return;
        awaken(p, to);
    }

    /** The awakening: a Stand becomes a Requiem (also used by GER's raid awakening). */
    public static void awaken(ServerPlayer p, StandType to) {
        StandData d = StandManager.data(p);
        ServerLevel sl = p.serverLevel();
        StandManager.assign(p, to);
        d.trialFlags = 0;
        d.trialCount = 0;
        d.timeSkipAt = sl.getGameTime(); // an inverted flash
        StandManager.sync(p);
        int col = to.color();
        p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
        p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("REQUIEM").withStyle(s -> s.withColor(col).withBold(true))));
        p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(to.standName()).withStyle(s -> s.withColor(col).withItalic(true))));
        if (to == StandType.ENDER_WORLD) {
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), com.mobstands.registry.ModSounds.TIME_STOP.get(), SoundSource.PLAYERS, 2.0F, 1.0F);
        }
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.5F, 0.6F);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0F, 0.8F);
        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, p.getX(), p.getY() + 1.0D, p.getZ(), 80, 0.6D, 1.0D, 0.6D, 0.2D);
        p.server.getPlayerList().broadcastSystemMessage(Component.literal(p.getName().getString() + "'s Stand has reached Requiem: " + to.standName())
                .withStyle(s -> s.withColor(col).withBold(true)), false);
    }
}
