package com.mobstands.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Scary Monsters: an infected mob's own AI is swapped for a dinosaur's (follow, hunt what moves, bite). */
@Mixin(Mob.class)
public interface MobGoalsAccessor {
    @Accessor("goalSelector")
    GoalSelector mobstands$goals();

    @Accessor("targetSelector")
    GoalSelector mobstands$targets();
}
