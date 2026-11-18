package com.mobstands.registry;

import com.mobstands.MobStands;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Custom particles. */
public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLES = DeferredRegister.create(Registries.PARTICLE_TYPE, MobStands.MODID);

    /** Glowing golden pixel stars (the Spin's energy) bursting off whatever it hits. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> HAMON_SPARK =
            PARTICLES.register("hamon_spark", () -> new SimpleParticleType(true));

    private ModParticles() {}
}
