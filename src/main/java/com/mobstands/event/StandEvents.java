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
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) StandManager.removeEntity(p);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            StandManager.data(p).activeStand = null;
            StandManager.sync(p);
        }
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
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        TickScheduler.tick();
        for (net.minecraft.server.level.ServerLevel l : event.getServer().getAllLevels()) {
            StandManager.twirlTick(l);
            com.mobstands.stand.TimeStop.tick(l);
            com.mobstands.stand.AxolotlPowers.tick(l);
            com.mobstands.stand.Mandom.tick(l);
            com.mobstands.stand.HeartAttack.tick(l);
            com.mobstands.stand.HorseSpin.tick(l);
            com.mobstands.stand.Ger.tick(l);
            com.mobstands.stand.SoundSteal.tick(l);
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

    /** Before the world saves: any Steel Balls spinning under a horse go back to their owners. */
    @SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        com.mobstands.stand.HorseSpin.shutdown();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TickScheduler.clear();
        com.mobstands.stand.TimeStop.clear();
        com.mobstands.stand.AxolotlPowers.clear();
        com.mobstands.stand.Mandom.clear();
        com.mobstands.stand.HeartAttack.clear();
        com.mobstands.stand.HorseSpin.clear();
        com.mobstands.stand.Ger.clear();
        com.mobstands.stand.SoundSteal.clear();
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (!victim.level().isClientSide) com.mobstands.stand.DreamWalk.onDeath(victim, event.getSource().getEntity());
        com.mobstands.stand.Mandom.onDeath(victim);
        com.mobstands.stand.RequiemTrials.onDeath(victim);
        if (victim instanceof net.minecraft.world.entity.monster.Evoker || victim instanceof net.minecraft.world.entity.monster.Vindicator) {
            StandManager.pillagerDied((net.minecraft.world.entity.Mob) victim, event.getSource());
        }
        if (victim instanceof ServerPlayer p) {
            StandManager.removeEntity(p); // Stand data survives death (copyOnDeath); the entity is re-created on respawn
        }
        if (event.getSource().getEntity() instanceof ServerPlayer killer && killer != victim) {
            StandData d = StandManager.data(killer);
            if (d.getType() != null && d.isSummoned() && (victim instanceof Mob || victim instanceof Player)) {
                int xp = Math.max(2, Math.min(40, (int) (victim.getMaxHealth() / 3.0F)));
                StandManager.addXp(killer, xp);
            }
        }
    }

    // ---- passives that need an event ----

    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && StandPassives.active(p, StandType.SLIME)) {
            event.setDamageMultiplier(0.0F);
        }
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
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
                && StandPassives.active(p, StandType.CREEPER)) {
            event.setAmount(event.getAmount() * 0.4F);
        }
    }

    /** Player actually took damage: feeds the Iron Golem Stand's rage (3 hits in a row). */
    @SubscribeEvent
    public static void onPlayerDamaged(LivingDamageEvent.Post event) {
        if (!event.getEntity().level().isClientSide) StandManager.misfireHurt(event.getEntity(), event.getSource());
        if (event.getEntity() instanceof ServerPlayer p && event.getNewDamage() > 0.0F) {
            StandManager.onOwnerHurt(p);
        }
    }

    /** Player hits something: feeds the panic-summon (spam hits and your Stand jumps in to help). */
    @SubscribeEvent
    public static void onPlayerAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && event.getTarget() instanceof LivingEntity target
                && com.mobstands.stand.StandCombat.valid(p, target)) {
            StandManager.onPlayerAttack(p);
        }
    }

    /** Frozen in stopped time: nothing can knock it around. */
    @SubscribeEvent
    public static void onKnockback(net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent event) {
        if (!event.getEntity().level().isClientSide && com.mobstands.stand.TimeStop.isFrozen(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        StandCommands.register(event.getDispatcher());
    }

    /** A Pig Stand (you or the pig itself) struck by lightning turns into a Piglin form. */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onLightning(net.neoforged.neoforge.event.entity.EntityStruckByLightningEvent event) {
        net.minecraft.world.entity.Entity e = event.getEntity();
        if (e.level().isClientSide) return;
        if (e instanceof ServerPlayer p) {
            StandManager.pigStruck(p);
        } else if (e instanceof com.mobstands.entity.StandEntity s && s.getStandType() == com.mobstands.stand.StandType.PIG
                && s.getOwnerPlayer() instanceof ServerPlayer owner) {
            event.setCanceled(true); // the pig itself doesn't burn
            StandManager.pigStruck(owner);
        }
    }

    /** Monsters lured by the Pig's Oink can't lock onto anyone until the distraction wears off. */
    @SubscribeEvent
    public static void onChangeTarget(net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() != null
                && (StandManager.isDistracted(event.getEntity()) || StandManager.isSpinConfused(event.getEntity())
                || com.mobstands.stand.AxolotlPowers.isBubbled(event.getEntity()))) event.setCanceled(true);
        else if (StandManager.misfireBlocksTarget(event.getEntity(), event.getNewAboutToBeSetTarget())) event.setCanceled(true);
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
        if (!event.getLevel().isClientSide() && !event.loadedFromDisk() && com.mobstands.stand.SoundSteal.fizzles(event.getEntity())) {
            event.setCanceled(true); // a silenced mob's shot fizzles out
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
    private static final java.util.Map<String, Float> ARROW_CHESTS = java.util.Map.ofEntries(
            java.util.Map.entry("chests/simple_dungeon", 0.05F),
            java.util.Map.entry("chests/abandoned_mineshaft", 0.03F),
            java.util.Map.entry("chests/desert_pyramid", 0.04F),
            java.util.Map.entry("chests/jungle_temple", 0.05F),
            java.util.Map.entry("chests/stronghold_corridor", 0.04F),
            java.util.Map.entry("chests/stronghold_library", 0.05F),
            java.util.Map.entry("chests/woodland_mansion", 0.06F),
            java.util.Map.entry("chests/pillager_outpost", 0.06F),
            java.util.Map.entry("chests/ancient_city", 0.06F),
            java.util.Map.entry("chests/nether_bridge", 0.04F),
            java.util.Map.entry("chests/bastion_treasure", 0.06F),
            java.util.Map.entry("chests/end_city_treasure", 0.05F),
            java.util.Map.entry("chests/buried_treasure", 0.04F),
            java.util.Map.entry("chests/shipwreck_treasure", 0.03F),
            java.util.Map.entry("chests/ruined_portal", 0.03F),
            java.util.Map.entry("chests/igloo_chest", 0.04F),
            java.util.Map.entry("chests/trial_chambers/reward_rare", 0.05F));

    @SubscribeEvent
    public static void onLootLoad(net.neoforged.neoforge.event.LootTableLoadEvent event) {
        net.minecraft.resources.ResourceLocation id = event.getName();
        if (!"minecraft".equals(id.getNamespace())) return;
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
    public static void onBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof net.minecraft.world.level.Level l) com.mobstands.stand.Mandom.onBlockChange(l, event.getPos(), event.getState());
    }

    @SubscribeEvent
    public static void onPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof net.minecraft.world.level.Level l) {
            com.mobstands.stand.Mandom.onBlockChange(l, event.getPos(), event.getBlockSnapshot().getState());
        }
    }

    @SubscribeEvent
    public static void onExplode(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event) {
        net.minecraft.world.level.Level l = event.getLevel();
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
