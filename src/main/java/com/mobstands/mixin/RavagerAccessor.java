package com.mobstands.mixin;

import net.minecraft.world.entity.monster.Ravager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Scary Monsters' form: drives the Ravager's bite (jaw) animation on the client-only body we draw for its user. */
@Mixin(Ravager.class)
public interface RavagerAccessor {
    @Accessor("attackTick")
    void mobstands$setAttackTick(int ticks);

    @Accessor("attackTick")
    int mobstands$getAttackTick();

    @Accessor("roarTick")
    void mobstands$setRoarTick(int ticks);

    @Accessor("roarTick")
    int mobstands$getRoarTick();
}
