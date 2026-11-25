package com.mobstands.event;

import com.mobstands.stand.StandData;
import com.mobstands.stand.StandManager;
import com.mobstands.stand.StandPassives;
import com.mobstands.stand.StandType;
import com.mobstands.stand.TickScheduler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** All gameplay events. Every handler is static and registered through NeoForge.EVENT_BUS.register(Class). */
public final class StandEvents {
    private StandEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            StandManager.data(p).activeStand = null;
            StandManager.sync(p); // the Stand entity itself is re-created by StandManager.tick
            com.mobstands.stand.GravityFlip.onLogin(p);
            com.mobstands.stand.Curses.login(p);
            com.mobstands.stand.Hermit.login(p);
            com.mobstands.stand.StandTools.login(p);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            StandManager.removeEntity(p);
            com.mobstands.stand.HeartStop.clear(p);
            com.mobstands.stand.Zipper.clear(p);
            com.mobstands.stand.Curses.logout(p);
            com.mobstands.stand.Encounters.logout(p);
            com.mobstands.stand.Goo.clear(p);
            com.mobstands.stand.Illusion.clear(p);
            com.mobstands.stand.StandTools.logout(p);
            com.mobstands.stand.Signatures.logout(p);
            com.mobstands.stand.Mobius.logout(p);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            StandManager.data(p).activeStand = null;
            StandManager.sync(p);
            com.mobstands.stand.Curses.onRespawn(p);
            com.mobstands.stand.RewindEvoker.onRespawn(p);
            com.mobstands.stand.Mandom.onRespawn(p);
        }
    }

    // ---- Caught in stopped time: you can't do anything at all ----

    @SubscribeEvent
    public static void onHeldAttack(net.neoforged.neoforge.event.entity.player.AttackEntityEvent event) {
        if (com.mobstands.stand.TimeStop.held(event.getEntity())) event.setCanceled(true);
        // Green Temperance / Smoke and Mirrors: hitting something gives you away.
        else if (event.getEntity() instanceof ServerPlayer ap) {
            if (com.mobstands.stand.Goo.disguised(ap)) com.mobstands.stand.Goo.undisguise(ap, true);
            if (com.mobstands.stand.Illusion.vanished(ap)) com.mobstands.stand.Illusion.unvanish(ap);
        }
    }

    @SubscribeEvent
    public static void onHeldRightClickBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (com.mobstands.stand.TimeStop.held(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onHeldRightClickItem(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
        if (com.mobstands.stand.TimeStop.held(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onHeldEntityInteract(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (com.mobstands.stand.TimeStop.held(event.getEntity())) {
            event.setCanceled(true);
            return;
        }
        // Iron Express: right-click someone driving their Minecart to climb aboard their train.
        if (event.getEntity() instanceof ServerPlayer rider && event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                && com.mobstands.stand.Train.board(rider, event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public static void onHeldEntityInteractSpecific(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific event) {
        if (com.mobstands.stand.TimeStop.held(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onHeldLeftClickBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event) {
        if (com.mobstands.stand.TimeStop.held(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onHeldBreak(net.neoforged.neoforge.event.entity.player.PlayerEvent.BreakSpeed event) {
        if (com.mobstands.stand.TimeStop.held(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onHeldUse(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof Player hp && com.mobstands.stand.TimeStop.held(hp)) event.setCanceled(true);
    }

    /** A Stand tool can't be put in a chest (or any other container). */
    @SubscribeEvent
    public static void onToolContainer(net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Close event) {
        for (net.minecraft.world.inventory.Slot s : event.getContainer().slots) {
            if (!(s.container instanceof net.minecraft.world.entity.player.Inventory)) com.mobstands.stand.StandTools.scrub(s.container);
        }
    }

    @SubscribeEvent
    public static void onHeldToss(net.neoforged.neoforge.event.entity.item.ItemTossEvent event) {
        // A Stand tool can't be dropped: Q just puts it away.
        if (com.mobstands.stand.StandTools.isTool(event.getEntity().getItem())) {
            event.setCanceled(true);
            if (event.getPlayer() instanceof ServerPlayer tp) com.mobstands.stand.StandTools.away(tp);
            return;
        }
        if (com.mobstands.stand.TimeStop.held(event.getPlayer())) {
            event.setCanceled(true);
            event.getPlayer().getInventory().add(event.getEntity().getItem()); // back into your pocket
        }
    }

    // ---- Cursed Stands: how they latch on ----

    @SubscribeEvent
    public static void onCurseEffect(net.neoforged.neoforge.event.entity.living.MobEffectEvent.Added event) {
        if (event.getEntity() instanceof ServerPlayer p && event.getEffectSource() instanceof net.minecraft.world.entity.monster.Witch)
            com.mobstands.stand.Curses.onWitchPotion(p);
    }

    @SubscribeEvent
    public static void onCurseLightning(net.neoforged.neoforge.event.entity.EntityStruckByLightningEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) com.mobstands.stand.Curses.onLightning(p);
    }

    @SubscribeEvent
    public static void onCurseWake(net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && p.getSleepTimer() >= 90) com.mobstands.stand.Curses.onSlept(p);
    }

    @SubscribeEvent
    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            StandManager.data(p).activeStand = null; // old-dimension Stand discards itself; a new one is spawned
            StandManager.sync(p);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            StandManager.tick(p);
            com.mobstands.stand.DreamWalk.tick(p);
            com.mobstands.stand.RequiemTrials.tick(p);
            com.mobstands.stand.Sniff.tick(p);
            com.mobstands.stand.Hermit.tick(p);
            com.mobstands.stand.TimeEvoker.playerTick(p);
            com.mobstands.stand.GravityFlip.checkOwner(p);
            com.mobstands.stand.RainPowers.playerTick(p);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        TickScheduler.tick();
        com.mobstands.stand.SoulControl.tick(event.getServer());
        com.mobstands.stand.OtherWorld.tick(event.getServer());
        com.mobstands.stand.Zipper.tick(event.getServer());
        com.mobstands.stand.Curses.tick(event.getServer());
        com.mobstands.stand.Encounters.tick(event.getServer());
        com.mobstands.stand.TimeEvoker.tick(event.getServer());
        com.mobstands.stand.RewindEvoker.tick(event.getServer());
        com.mobstands.stand.Train.tick(event.getServer());
        com.mobstands.stand.Goo.tick(event.getServer());
        com.mobstands.stand.Illusion.tick(event.getServer());
        com.mobstands.stand.StandTools.tick(event.getServer());
        com.mobstands.stand.HeartStop.tick(event.getServer());
        com.mobstands.stand.GravityFlip.tick(event.getServer());
        com.mobstands.stand.Calamity.tick(event.getServer());
        com.mobstands.stand.Clash.tick(event.getServer());
        com.mobstands.stand.Signatures.tick(event.getServer());
        com.mobstands.stand.Mobius.tick(event.getServer());
        for (net.minecraft.server.level.ServerLevel l : event.getServer().getAllLevels()) {
            StandManager.twirlTick(l);
            com.mobstands.stand.TimeStop.tick(l);
            com.mobstands.stand.AxolotlPowers.tick(l);
            com.mobstands.stand.CreeperRequiem.tick(l);
            com.mobstands.stand.Plunder.tick(l);
            com.mobstands.stand.PropertySwap.tick(l);
            com.mobstands.stand.Mandom.tick(l);
            com.mobstands.stand.HeartAttack.tick(l);
            com.mobstands.stand.MoonPowers.tick(l);
            com.mobstands.stand.AllaySlam.tick(l);
            com.mobstands.stand.HorseSpin.tick(l);
            com.mobstands.stand.Ger.tick(l);
            com.mobstands.stand.SoundSteal.tick(l);
            com.mobstands.stand.RainPowers.tick(l);
            com.mobstands.stand.NullBubble.tick(l);
        }
    }

    /** GER: whatever a mob lost in the loop was carrying goes straight to you. */
    @SubscribeEvent
    public static void onDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event) {
        com.mobstands.stand.Ger.onDrops(event.getEntity(), event.getDrops());
    }

    /** First time into the Nether / the End: new Stands stir in the arrows. */
    @SubscribeEvent
    public static void onAdvancement(net.neoforged.neoforge.event.entity.player.AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        String id = event.getAdvancement().id().toString();
        String msg;
        int color;
        if (id.equals("minecraft:nether/root")) {
            msg = "The heat of the Nether seeps into the arrows... New Stands stir.";
            color = 0xFF7A3A;
        } else if (id.equals("minecraft:end/root")) {
            msg = "Something at the edge of the world answers... Requiem has become possible.";
            color = 0xD9A8FF;
        } else {
            return;
        }
        sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg).withStyle(s -> s.withColor(color).withItalic(true)));
        sp.serverLevel().playSound(null, sp.getX(), sp.getY(), sp.getZ(), net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE,
                net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.5F);
    }

    /**
     * Aerosmith's arrows: every one of them lands (no invulnerability window between hits), and they vanish when
     * they hit the ground instead of littering it.
     */
    @SubscribeEvent
    public static void onProjectileImpact(net.neoforged.neoforge.event.entity.ProjectileImpactEvent event) {
        if (event.getProjectile().level().isClientSide) return;
        // Wonder of Deep's Misdirection: it misses.
        if (event.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult mh
                && com.mobstands.stand.Calamity.onProjectileHit(event.getProjectile(), mh.getEntity())) {
            event.setCanceled(true);
            return;
        }
        if (!event.getProjectile().getTags().contains(StandManager.AERO_TAG)) return;
        if (event.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult eh) {
            eh.getEntity().invulnerableTime = 0;
        } else {
            var pr = event.getProjectile();
            ((net.minecraft.server.level.ServerLevel) pr.level()).sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT,
                    pr.getX(), pr.getY(), pr.getZ(), 2, 0.05D, 0.05D, 0.05D, 0.05D);
            pr.discard();
            event.setCanceled(true);
        }
    }

    /** Before the world saves: any Steel Balls spinning under a horse go back to their owners. */
    @SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        com.mobstands.stand.HorseSpin.shutdown();
        com.mobstands.stand.SoulControl.shutdown();
        com.mobstands.stand.OtherWorld.shutdown();
        com.mobstands.stand.Zipper.shutdown();
        com.mobstands.stand.GravityFlip.shutdown();
        com.mobstands.stand.RainPowers.shutdown();
        com.mobstands.stand.Calamity.shutdown();
        com.mobstands.stand.NullBubble.clear();
        com.mobstands.stand.Signatures.shutdown();
        com.mobstands.stand.Mobius.shutdown();
    }

    /** C-Moon: someone new sees a creature whose gravity is turned. */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer viewer) com.mobstands.stand.GravityFlip.onStartTracking(viewer, event.getTarget());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TickScheduler.clear();
        com.mobstands.stand.Curses.shutdown();
        com.mobstands.stand.Encounters.shutdown();
        com.mobstands.stand.TimeEvoker.shutdown();
        com.mobstands.stand.RewindEvoker.shutdown();
        com.mobstands.stand.Train.shutdown();
        com.mobstands.stand.Goo.shutdown();
        com.mobstands.stand.Illusion.shutdown();
        com.mobstands.stand.StandTools.shutdown();
        com.mobstands.stand.CreeperRequiem.clear();
        com.mobstands.stand.Plunder.clear();
        com.mobstands.stand.PropertySwap.clear();
        com.mobstands.stand.TimeStop.clear();
        com.mobstands.stand.AxolotlPowers.clear();
        com.mobstands.stand.Mandom.clear();
        com.mobstands.stand.Lazarus.clear();
        com.mobstands.stand.Clash.clear();
        com.mobstands.stand.HeartAttack.clear();
        com.mobstands.stand.GolemLaunch.clear();
        com.mobstands.stand.MoonPowers.clear();
        com.mobstands.stand.AllaySlam.clear();
        com.mobstands.stand.HorseSpin.clear();
        com.mobstands.stand.Ger.clear();
        com.mobstands.stand.SoundSteal.clear();
    }

    /** Guardian Experience Requiem erases what it kills: a slime or magma cube it finishes doesn't split - it's just gone. */
    @SubscribeEvent
    public static void onMobSplit(net.neoforged.neoforge.event.entity.living.MobSplitEvent event) {
        net.minecraft.world.entity.Mob parent = event.getParent();
        net.minecraft.world.damagesource.DamageSource src = parent.getLastDamageSource();
        if (src != null && src.getEntity() instanceof ServerPlayer gp
                && StandManager.data(gp).getType() == com.mobstands.stand.StandType.GER) {
            event.setCanceled(true);
            if (parent.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                double y = parent.getY() + parent.getBbHeight() * 0.5D, r = parent.getBbWidth() * 0.5D;
                sl.sendParticles(new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(1.0F, 0.83F, 0.27F), 1.2F),
                        parent.getX(), y, parent.getZ(), 25, r, r, r, 0.0D);
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, parent.getX(), y, parent.getZ(), 8, r, r, r, 0.02D);
            }
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        // The test Bites the Dust Evoker: you die... and then the clock winds back.
        if (victim instanceof ServerPlayer rp) com.mobstands.stand.RewindEvoker.onPlayerDeath(rp, event.getSource());
        if (victim instanceof ServerPlayer cp) com.mobstands.stand.Curses.onDeath(cp, event.getSource());
        if (victim instanceof ServerPlayer tp) com.mobstands.stand.StandTools.away(tp); // (a Stand tool never drops)
        if (!victim.level().isClientSide) com.mobstands.stand.DreamWalk.onDeath(victim, event.getSource().getEntity());
        com.mobstands.stand.Mandom.onDeath(victim);
        com.mobstands.stand.Lazarus.onDeath(victim);
        if (!victim.level().isClientSide) com.mobstands.stand.GravityFlip.onDeath(victim);
        if (!victim.level().isClientSide) com.mobstands.stand.Calamity.onDeath(victim);
        com.mobstands.stand.RequiemTrials.onDeath(victim);
        if (victim instanceof net.minecraft.world.entity.monster.Evoker || victim instanceof net.minecraft.world.entity.monster.Vindicator) {
            StandManager.pillagerDied((net.minecraft.world.entity.Mob) victim, event.getSource());
            com.mobstands.stand.Encounters.onDeath((net.minecraft.world.entity.Mob) victim, event.getSource());
            com.mobstands.stand.TimeEvoker.onDeath(victim, event.getSource());
        }
        if (victim instanceof ServerPlayer p) {
            StandManager.removeEntity(p); // Stand data survives death (copyOnDeath); the entity is re-created on respawn
        }
        // Stand XP for the kill - also when an ability finished it off indirectly (rain, drowning, fire, a fall, a
        // possessed mob, an explosion...): whoever's Stand last hurt it in the past 10 seconds gets the credit.
        ServerPlayer killer = event.getSource().getEntity() instanceof ServerPlayer sp ? sp : null;
        ServerPlayer credited = victim.level().isClientSide ? null : com.mobstands.stand.StandCombat.creditFor(victim);
        if (killer == null) killer = credited;
        if (killer != null && killer != victim) {
            StandData d = StandManager.data(killer);
            if (d.getType() != null && d.isSummoned() && (victim instanceof Mob || victim instanceof Player)) {
                int xp = Math.max(3, Math.min(50, (int) (victim.getMaxHealth() / 2.5F)));
                // Killed by your Stand's abilities (rain, slams, punches...) rather than your own weapon: double XP.
                if (credited == killer) xp *= 2;
                StandManager.addXp(killer, xp);
            }
        }
    }

    // ---- passives that need an event ----

    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        // Iron Launch: you come down like a mace - no fall damage, and a smash where you land.
        if (event.getEntity() instanceof ServerPlayer gp && com.mobstands.stand.GolemLaunch.onLand(gp, event.getDistance())) {
            event.setDamageMultiplier(0.0F);
            return;
        }
        if (event.getEntity() instanceof ServerPlayer p && StandPassives.active(p, StandType.SLIME)) {
            event.setDamageMultiplier(0.0F);
            com.mobstands.stand.Signatures.bounce(p, event.getDistance());
            return;
        }
        // Weaver of Webs: you always land softly after a Silk Swing.
        if (event.getEntity() instanceof ServerPlayer sp && com.mobstands.stand.Signatures.softLanding(sp)) event.setDamageMultiplier(0.0F);
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        // Being wound back by Bites the Dust: nothing touches you until you snap back.
        if (com.mobstands.stand.RewindEvoker.shielded(event.getEntity())) {
            event.setCanceled(true);
            return;
        }
        // Silver Fingers: nothing reaches you through a zipper (going in, inside, or coming out).
        if (com.mobstands.stand.Zipper.blocks(event.getEntity(), event.getSource())) {
            event.setCanceled(true);
            return;
        }
        // Smoke and Mirrors: it hit one of your mirror images instead.
        if (event.getEntity() instanceof ServerPlayer mp && com.mobstands.stand.Illusion.intercept(mp, event.getSource())) {
            event.setCanceled(true);
            return;
        }
        // Nothing one-taps a player: big Stand hits on players are capped.
        event.setAmount(com.mobstands.stand.StandCombat.pvpCap(event.getEntity(), event.getSource(), event.getAmount()));
        // Weaver of Webs' Möbius Strip: closes when you're badly hurt, and sends most of every hit back round the loop.
        if (event.getEntity() instanceof ServerPlayer msp) event.setAmount(com.mobstands.stand.Mobius.onHurt(msp, event.getSource(), event.getAmount()));
        // Green Temperance's Temperance Armor: the goo soaks up most of it, and eats whatever touched it.
        if (event.getEntity() instanceof ServerPlayer gp && com.mobstands.stand.Goo.armored(gp)) {
            event.setAmount(com.mobstands.stand.Goo.armorHit(gp, event.getSource(), event.getAmount()));
        }
        // Oh! Lonesome Llama's Thick Wool, Reef Boy's Spiked Shell.
        if (event.getEntity() instanceof ServerPlayer wp) {
            com.mobstands.stand.StandData wd = StandManager.data(wp);
            if (wd.isSummoned() && wd.getLevel() >= 5) {
                if (wd.getType() == com.mobstands.stand.StandType.LLAMA && event.getSource().is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE))
                    event.setAmount(event.getAmount() * 0.8F);
                if (wd.getType() == com.mobstands.stand.StandType.GUARDIAN && event.getSource().getDirectEntity() instanceof LivingEntity spiked
                        && spiked == event.getSource().getEntity() && spiked != wp) {
                    spiked.hurt(wp.damageSources().thorns(wp), 2.0F);
                }
            }
        }
        // Silver Fingers' Living Armour: the silverfish clinging to you take part of the hit.
        if (event.getEntity() instanceof ServerPlayer sfp && !sfp.level().isClientSide) {
            com.mobstands.stand.StandData sd = StandManager.data(sfp);
            if (sd.getType() == com.mobstands.stand.StandType.SILVERFISH && sd.isSummoned() && sd.getLevel() >= 5)
                event.setAmount(event.getAmount() * 0.8F);
        }
        // Another World: nothing crosses between the real world and the one next door.
        if (com.mobstands.stand.OtherWorld.separated(event.getSource().getEntity(), event.getEntity())
                || com.mobstands.stand.OtherWorld.separated(event.getSource().getDirectEntity(), event.getEntity())) {
            event.setCanceled(true);
            return;
        }
        // Guardian Experience Requiem: no projectile ever reaches its user - arrows, tridents, fireballs, shulker bullets,
        // all of it simply stops short and drops away with a dull iron clink.
        if (event.getEntity() instanceof ServerPlayer gp && !gp.level().isClientSide
                && StandManager.data(gp).getType() == com.mobstands.stand.StandType.GER
                && event.getSource().is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE)) {
            event.setCanceled(true);
            net.minecraft.world.entity.Entity proj = event.getSource().getDirectEntity();
            if (gp.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                net.minecraft.world.phys.Vec3 at = proj != null ? proj.position() : gp.getEyePosition();
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT, at.x, at.y, at.z, 6, 0.1D, 0.1D, 0.1D, 0.15D);
                sl.playSound(null, at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.IRON_GOLEM_HURT,
                        net.minecraft.sounds.SoundSource.PLAYERS, 0.5F, 1.6F);
            }
            if (proj instanceof net.minecraft.world.entity.projectile.Projectile && !(proj instanceof net.minecraft.world.entity.player.Player))
                proj.discard();
            return;
        }
        if (event.getEntity() instanceof ServerPlayer hurtP) com.mobstands.stand.CreeperRequiem.onOwnerHurt(hurtP, event.getSource());
        // Stop Heart: striking something gets your heart going again.
        if (event.getSource().getEntity() instanceof ServerPlayer hp && hp != event.getEntity() && com.mobstands.stand.HeartStop.stopped(hp)) {
            com.mobstands.stand.HeartStop.restart(hp);
        }
        // Wonder of Deep: hurting its user makes the universe take note; its user striking ends Unseen Presence.
        if (!event.getEntity().level().isClientSide) {
            if (event.getEntity() instanceof ServerPlayer wr && com.mobstands.stand.Calamity.isUser(wr)) {
                com.mobstands.stand.Calamity.onUserHurt(wr, event.getSource().getEntity());
                // ...and whoever struck it is struck instead.
                if (com.mobstands.stand.Calamity.reflect(wr, event.getSource(), event.getAmount())) {
                    event.setCanceled(true);
                    return;
                }
            }
            if (event.getSource().getEntity() instanceof ServerPlayer atk && com.mobstands.stand.Calamity.isUser(atk))
                com.mobstands.stand.Calamity.onUserAttacks(atk);
        }
        // Catch the Rainbow: in the rain, the hit passes through a body of raindrops.
        if (!event.getEntity().level().isClientSide && com.mobstands.stand.RainPowers.dodge(event.getEntity(), event.getSource())) {
            event.setCanceled(true);
            return;
        }
        // GER: Return to Zero, the Golden Barrier, and mobs caught in the loop.
        if (com.mobstands.stand.Ger.onHurt(event.getEntity(), event.getSource())) {
            event.setCanceled(true);
            return;
        }
        // Stopped time: nothing frozen can hurt anyone (frozen mobs, frozen arrows)...
        if (!event.getEntity().level().isClientSide && com.mobstands.stand.TimeStop.fromFrozen(event.getEntity(), event.getSource())) {
            event.setCanceled(true);
            return;
        }
        // ...and hits on a frozen enemy pile up and land when time moves again.
        if (!event.getEntity().level().isClientSide && com.mobstands.stand.TimeStop.absorb(event.getEntity(), event.getSource(), event.getAmount())) {
            event.setCanceled(true);
            return;
        }
        // Axolotl Stand: a killing blow is taken by the Axolotl, which plays dead in front of you.
        if (event.getEntity() instanceof ServerPlayer saved && StandManager.axolotlSave(saved, event.getSource(), event.getAmount())) {
            event.setCanceled(true);
            return;
        }
        // A Stand never hurts its own user: cancel explosions the player caused themself (Creeper Stand blasts).
        if (event.getEntity() instanceof ServerPlayer self && event.getSource().is(DamageTypeTags.IS_EXPLOSION)
                && event.getSource().getEntity() == self && StandManager.data(self).getType() != null) {
            event.setCanceled(true);
            return;
        }
        if (event.getEntity() instanceof ServerPlayer p
                && event.getSource().is(DamageTypeTags.IS_EXPLOSION)
                && (StandPassives.active(p, StandType.CREEPER) || StandPassives.active(p, StandType.CREEPER_REQUIEM))) {
            event.setAmount(event.getAmount() * 0.4F);
        }
    }

    /** Player actually took damage: feeds the Iron Golem Stand's rage (3 hits in a row). */
    @SubscribeEvent
    public static void onPlayerDamaged(LivingDamageEvent.Post event) {
        if (!event.getEntity().level().isClientSide) StandManager.misfireHurt(event.getEntity(), event.getSource());
        if (event.getEntity() instanceof ServerPlayer p && event.getNewDamage() > 0.0F) {
            StandManager.onOwnerHurt(p);
            com.mobstands.stand.Curses.onHurt(p, event.getSource());
            com.mobstands.stand.Illusion.lastResort(p);
        }
    }

    /** Player hits something: feeds the panic-summon (spam hits and your Stand jumps in to help). */
    @SubscribeEvent
    public static void onPlayerAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && event.getTarget() instanceof LivingEntity target
                && com.mobstands.stand.StandCombat.valid(p, target)) {
            com.mobstands.stand.GolemLaunch.onAttack(p, target); // Iron Launch: hit them on the way down - mace smash
            StandManager.onPlayerAttack(p);
        }
    }

    /** Frozen in stopped time: nothing can knock it around. */
    @SubscribeEvent
    public static void onKnockback(net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent event) {
        if (!event.getEntity().level().isClientSide && com.mobstands.stand.TimeStop.isFrozen(event.getEntity())) event.setCanceled(true);
        else if (!event.getEntity().level().isClientSide && com.mobstands.stand.Mobius.active(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        StandCommands.register(event.getDispatcher());
        CurseCommands.register(event.getDispatcher());
    }

    /** A Pig Stand (you or the pig itself) struck by lightning turns into a Piglin form. */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onLightning(net.neoforged.neoforge.event.entity.EntityStruckByLightningEvent event) {
        net.minecraft.world.entity.Entity e = event.getEntity();
        if (e.level().isClientSide) return;
        if (e instanceof ServerPlayer p) {
            if (com.mobstands.stand.PigActs.totemStruck(p)) {
                event.setCanceled(true); // the totem took the bolt
                return;
            }
            StandManager.pigStruck(p);
            com.mobstands.stand.CreeperRequiem.struck(p);
        } else if (e instanceof com.mobstands.entity.StandEntity cs && cs.getStandType() == com.mobstands.stand.StandType.CREEPER
                && cs.getOwnerPlayer() instanceof ServerPlayer cOwner) {
            event.setCanceled(true); // a charged creeper - the lightning feeds it
            com.mobstands.stand.CreeperRequiem.struck(cOwner);
        } else if (e instanceof com.mobstands.entity.StandEntity s && s.getStandType() == com.mobstands.stand.StandType.PIG
                && s.getOwnerPlayer() instanceof ServerPlayer owner) {
            event.setCanceled(true); // the pig itself doesn't burn
            StandManager.pigStruck(owner);
        }
    }

    /**
     * Crescent Shell: standing on a wall or ceiling counts as standing for mining. (Vanilla mines 5x slower whenever
     * you're "not on the ground" - and on a ceiling the game never thinks you are.) Runs on both sides, so the
     * crack animation and the real break agree.
     */
    @SubscribeEvent
    public static void onBreakSpeed(net.neoforged.neoforge.event.entity.player.PlayerEvent.BreakSpeed event) {
        net.minecraft.world.entity.player.Player p = event.getEntity();
        if (p.onGround()) return;
        net.minecraft.core.Direction g = p.level().isClientSide ? com.mobstands.stand.GravityBox.CLIENT.get(p.getId())
                : com.mobstands.stand.GravityFlip.dir(p);
        if (g == null || g == net.minecraft.core.Direction.DOWN) return;
        boolean standing = !p.level().noCollision(p, p.getBoundingBox().move(com.mobstands.stand.GravityFlip.vec(g).scale(0.04D)));
        if (standing) event.setNewSpeed(event.getNewSpeed() * 5.0F);
    }

    /** Pig line ACT 2 -> 3: an Enchanted Golden Apple restores the Stand's soul. */
    @SubscribeEvent
    public static void onItemEaten(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Finish event) {
        if (event.getEntity() instanceof ServerPlayer p) com.mobstands.stand.PigActs.ateApple(p, event.getItem());
    }

    /** Monsters lured by the Pig's Oink can't lock onto anyone until the distraction wears off. */
    @SubscribeEvent
    public static void onChangeTarget(net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() != null
                && (StandManager.isDistracted(event.getEntity()) || StandManager.isSpinConfused(event.getEntity())
                || com.mobstands.stand.AxolotlPowers.isBubbled(event.getEntity()))) event.setCanceled(true);
        else if (StandManager.misfireBlocksTarget(event.getEntity(), event.getNewAboutToBeSetTarget())) event.setCanceled(true);
        else if (event.getNewAboutToBeSetTarget() != null && com.mobstands.stand.Plunder.blind(event.getEntity())) event.setCanceled(true);
        else if (event.getNewAboutToBeSetTarget() != null && com.mobstands.stand.Ger.stunned(event.getEntity())) event.setCanceled(true);
        else if (com.mobstands.stand.Calamity.onChangeTarget(event.getEntity(), event.getNewAboutToBeSetTarget())) event.setCanceled(true);
        // Stop Heart: you're dead, as far as anything can tell - nothing comes for you.
        else if (com.mobstands.stand.HeartStop.stopped(event.getNewAboutToBeSetTarget())) event.setCanceled(true);
        // Green Temperance: you look like one of them - nothing comes for you (unless you've hit it).
        else if (com.mobstands.stand.Goo.disguised(event.getNewAboutToBeSetTarget())
                && event.getEntity().getLastHurtByMob() != event.getNewAboutToBeSetTarget()) event.setCanceled(true);
        // Silver Fingers: you're in a pocket dimension behind a zipper - there's nobody to go after.
        else if (com.mobstands.stand.Zipper.busy(event.getNewAboutToBeSetTarget())) event.setCanceled(true);
        // Sheer Heart Attack only hunts its user's enemies; Lullaby's sleepers can't go after anyone.
        else if (com.mobstands.stand.Signatures.blocksTarget(event.getEntity(), event.getNewAboutToBeSetTarget())) event.setCanceled(true);
        // Pigstep's Another World: you can't go after someone who isn't in your world.
        else if (com.mobstands.stand.OtherWorld.separated(event.getEntity(), event.getNewAboutToBeSetTarget())) event.setCanceled(true);
    }

    /** A spinning item picked up off the floor stops spinning (and stacks normally again). */
    @SubscribeEvent
    public static void onItemPickup(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Pre event) {
        net.minecraft.world.item.ItemStack stack = event.getItemEntity().getItem();
        net.minecraft.world.item.component.CustomData cd = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (cd != null && cd.contains(StandManager.SPIN_TAG)) {
            net.minecraft.world.item.ItemStack copy = stack.copy();
            StandManager.clearSpinTag(copy);
            event.getItemEntity().setItem(copy);
        }
    }

    /** A mob hit by a thrown spin shoots itself: its new projectiles turn right back at it. */
    @SubscribeEvent
    public static void onEntityJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && !event.loadedFromDisk()) com.mobstands.stand.TimeStop.onShot(event.getEntity());
        if (!event.getLevel().isClientSide()) {
            com.mobstands.stand.TimeEvoker.onJoin(event.getEntity());
            com.mobstands.stand.RewindEvoker.onJoin(event.getEntity());
        }
        if (!event.getLevel().isClientSide() && !event.loadedFromDisk() && com.mobstands.stand.SoundSteal.fizzles(event.getEntity())) {
            event.setCanceled(true); // a silenced mob's shot fizzles out
            return;
        }
        if (!event.getLevel().isClientSide() && event.loadedFromDisk() && com.mobstands.stand.Signatures.onLoad(event.getEntity())) {
            event.setCanceled(true); // a signature trick's leftovers (Sheer Heart Attack, a Cattle Car) don't outlive it
            return;
        }
        if (!event.getLevel().isClientSide() && event.loadedFromDisk() && event.getEntity().getTags().contains(com.mobstands.stand.Ger.LIFE_TAG)) {
            event.setCanceled(true); // GER's Life Giver golems only live for a little while
            return;
        }
        if (!event.getLevel().isClientSide() && event.loadedFromDisk() && com.mobstands.stand.HeartAttack.isLeftover(event.getEntity())) {
            event.setCanceled(true); // a Heart Attack saved mid-hunt: gone
            return;
        }
        if (!event.getLevel().isClientSide() && (event.getEntity() instanceof net.minecraft.world.entity.monster.Pillager
                || event.getEntity() instanceof net.minecraft.world.entity.monster.Vindicator
                || event.getEntity() instanceof net.minecraft.world.entity.monster.Evoker)) {
            StandManager.pillagerJoin((net.minecraft.world.entity.Mob) event.getEntity());
        }
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile proj) {
            if (proj instanceof net.minecraft.world.entity.projectile.AbstractArrow arrow && StandManager.orbitSpinArrow(arrow)) return;
            StandManager.reverseSpinShot(proj);
        }
    }

    /** Chests that can (very rarely) hold a Stand Arrow: most dungeons and structures. */
    /** Spin Scrolls: jungles, snow, the sea and the road. */
    private static final java.util.Map<String, Float> SCROLL_CHESTS = java.util.Map.ofEntries(
            java.util.Map.entry("chests/jungle_temple", 0.12F),
            java.util.Map.entry("chests/igloo_chest", 0.12F),
            java.util.Map.entry("chests/shipwreck_treasure", 0.08F),
            java.util.Map.entry("chests/buried_treasure", 0.10F),
            java.util.Map.entry("chests/underwater_ruin_big", 0.08F),
            java.util.Map.entry("chests/village/village_taiga_house", 0.04F),
            java.util.Map.entry("chests/village/village_snowy_house", 0.04F),
            java.util.Map.entry("chests/woodland_mansion", 0.06F));

    /** Echoing Arrows: deserts, the dark underground, and the Nether and the End. */
    private static final java.util.Map<String, Float> ARROW_CHESTS = java.util.Map.ofEntries(
            java.util.Map.entry("chests/simple_dungeon", 0.05F),
            java.util.Map.entry("chests/abandoned_mineshaft", 0.03F),
            java.util.Map.entry("chests/desert_pyramid", 0.20F), // the hot, dry desert: where Stand arrows turn up
            java.util.Map.entry("chests/stronghold_corridor", 0.04F),
            java.util.Map.entry("chests/stronghold_library", 0.05F),
            java.util.Map.entry("chests/woodland_mansion", 0.06F),
            java.util.Map.entry("chests/pillager_outpost", 0.06F),
            java.util.Map.entry("chests/ancient_city", 0.06F),
            java.util.Map.entry("chests/nether_bridge", 0.04F),
            java.util.Map.entry("chests/bastion_treasure", 0.06F),
            java.util.Map.entry("chests/end_city_treasure", 0.05F),
            java.util.Map.entry("chests/trial_chambers/reward_rare", 0.05F));

    @SubscribeEvent
    public static void onLootLoad(net.neoforged.neoforge.event.LootTableLoadEvent event) {
        net.minecraft.resources.ResourceLocation id = event.getName();
        if (!"minecraft".equals(id.getNamespace())) return;
        // The Spin comes from the opposite kind of place to the Stands: the green, wet, cold, open world - jungles,
        // snow, the sea and the road. Rarely, and one scroll at a time. (Woodland mansions have both.)
        Float scroll = SCROLL_CHESTS.get(id.getPath());
        if (scroll != null) {
            event.getTable().addPool(net.minecraft.world.level.storage.loot.LootPool.lootPool()
                    .name("mobstands:spin_scroll")
                    .setRolls(net.minecraft.world.level.storage.loot.providers.number.ConstantValue.exactly(1.0F))
                    .add(net.minecraft.world.level.storage.loot.entries.LootItem.lootTableItem(com.mobstands.registry.ModItems.SPIN_SCROLL.get()))
                    .when(net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition.randomChance(scroll))
                    .build());
        }
        Float chance = ARROW_CHESTS.get(id.getPath());
        if (chance == null) return;
        event.getTable().addPool(net.minecraft.world.level.storage.loot.LootPool.lootPool()
                .name("mobstands:stand_arrow")
                .setRolls(net.minecraft.world.level.storage.loot.providers.number.ConstantValue.exactly(1.0F))
                .add(net.minecraft.world.level.storage.loot.entries.LootItem.lootTableItem(com.mobstands.registry.ModItems.ECHO_ARROW.get()))
                .when(net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition.randomChance(chance))
                .build());
    }

    // ---- Neoteny (Mandom) remembers block changes near Axolotl users, so they can be undone ----

    @SubscribeEvent
    public static void onUseBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        net.minecraft.world.level.Level l = event.getLevel();
        if (l.isClientSide) return;
        if (l.getBlockEntity(event.getPos()) instanceof net.minecraft.world.Container) {
            com.mobstands.stand.Mandom.onBlockChange(l, event.getPos(), l.getBlockState(event.getPos()));
        }
    }

    @SubscribeEvent
    public static void onBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof net.minecraft.world.level.Level l) com.mobstands.stand.Mandom.onBlockChange(l, event.getPos(), event.getState());
    }

    @SubscribeEvent
    public static void onPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof net.minecraft.world.level.Level l) {
            com.mobstands.stand.Mandom.onBlockChange(l, event.getPos(), event.getBlockSnapshot().getState());
        }
    }

    /** Bites the Dust: anything leaving the world for good is remembered, so a rewind can bring it back. */
    @SubscribeEvent
    public static void onLeaveLevel(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        var reason = event.getEntity().getRemovalReason();
        if (reason == net.minecraft.world.entity.Entity.RemovalReason.KILLED || reason == net.minecraft.world.entity.Entity.RemovalReason.DISCARDED) {
            com.mobstands.stand.Mandom.onVanish(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onExplodeStart(net.neoforged.neoforge.event.level.ExplosionEvent.Start event) {
        // Pigstep stole this creeper's Volatility: even lit with flint and steel, it just fizzles.
        if (com.mobstands.stand.PropertySwap.defused(event.getExplosion().getDirectSourceEntity())) event.setCanceled(true);
        // Sheer Heart Attack: its own blast instead (never breaks blocks, never hurts its user).
        else if (com.mobstands.stand.Signatures.onExplode(event.getExplosion().getDirectSourceEntity())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onExplode(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event) {
        net.minecraft.world.level.Level l = event.getLevel();
        // Pigstep's Another World: a blast from one world doesn't touch anyone in the other.
        net.minecraft.world.entity.Entity boom = event.getExplosion().getIndirectSourceEntity();
        if (boom == null) boom = event.getExplosion().getDirectSourceEntity();
        if (boom != null) {
            final net.minecraft.world.entity.Entity src = boom;
            event.getAffectedEntities().removeIf(e -> com.mobstands.stand.OtherWorld.separated(src, e));
        }
        for (net.minecraft.core.BlockPos pos : event.getAffectedBlocks()) {
            net.minecraft.world.level.block.state.BlockState s = l.getBlockState(pos);
            if (!s.isAir()) com.mobstands.stand.Mandom.onBlockChange(l, pos, s);
        }
    }

    /** Swapping hands (F) carries a spinning item's spin over to the other hand. */
    @SubscribeEvent
    public static void onSwapHands(net.neoforged.neoforge.event.entity.living.LivingSwapItemsEvent.Hands event) {
        if (event.getEntity() instanceof ServerPlayer p) StandManager.spinSwapHands(p);
    }

    /** Left-click the ground (at or below your feet) with a spinning sword: the ground slash, instead of breaking the block. */
    @SubscribeEvent
    public static void onLeftClickBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event) {
        Player p = event.getEntity();
        if (event.getPos().getY() > p.getBlockY()) return; // only the ground, not walls or things above you
        com.mobstands.stand.StandData d = p.level().isClientSide ? com.mobstands.network.ClientStandCache.get() : StandManager.data((ServerPlayer) p);
        if (!StandManager.spinningSword(d, p.getMainHandItem())) return;
        event.setCanceled(true); // don't dig: slash
        if (p instanceof ServerPlayer sp && event.getAction() == net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action.START) {
            StandManager.spinSwordSlash(sp);
        }
    }
}
