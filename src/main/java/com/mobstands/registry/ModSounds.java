package com.mobstands.registry;

import com.mobstands.MobStands;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Custom sounds. The audio files and their random variants are listed in assets/mobstands/sounds.json. */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MobStands.MODID);

    /** A Stand appears (picks randomly between its variants). */
    public static final DeferredHolder<SoundEvent, SoundEvent> STAND_SUMMON = register("stand.summon");
    /** Intimidating Stands (Warden, Enderman, Iron Golem) appear with the blade sound instead. */
    public static final DeferredHolder<SoundEvent, SoundEvent> STAND_SUMMON_MENACING = register("stand.summon_menacing");
    /** Summon-rush combo: one sound per punch (punch 2 only plays if the player clicks again). */
    public static final DeferredHolder<SoundEvent, SoundEvent> COMBO_PUNCH_1 = register("stand.combo_punch1");
    /** Second punch (left hit): the original, uncut two-punch clip. */
    public static final DeferredHolder<SoundEvent, SoundEvent> COMBO_PUNCH_2 = register("stand.combo_punch2");

    /** Double-click: the full combo (charge + punch 1 + punch 2). */
    public static final DeferredHolder<SoundEvent, SoundEvent> COMBO_FULL = register("stand.combo_full");
    /** Skeleton: only when you hit the Double Shot timing. */
    public static final DeferredHolder<SoundEvent, SoundEvent> DOUBLE_SHOT = register("stand.double_shot");

    /** Spin: the hamon crackle + glass shatter (spin starts, and an enemy gets hit by it). */
    public static final DeferredHolder<SoundEvent, SoundEvent> SPIN_HAMON = register("stand.spin_hamon");
    /** Spin: just the hamon crackle (a thrown ball turning around and flying home). */
    public static final DeferredHolder<SoundEvent, SoundEvent> SPIN_CRACKLE = register("stand.spin_crackle");
    public static final DeferredHolder<SoundEvent, SoundEvent> TIME_STOP = register("stand.time_stop");
    public static final DeferredHolder<SoundEvent, SoundEvent> MANDOM = register("stand.mandom");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(MobStands.id(name)));
    }

    private ModSounds() {}
}
