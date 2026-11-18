package com.mobstands.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

/** A glowing golden pixel star: pops out, drifts and spins a little, then shrinks and fades. Always full bright. */
public class HamonSparkParticle extends TextureSheetParticle {
    private final float baseSize;

    protected HamonSparkParticle(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites) {
        super(level, x, y, z, dx, dy, dz);
        this.xd = dx + (random.nextDouble() - 0.5D) * 0.08D;
        this.yd = dy + (random.nextDouble() - 0.5D) * 0.08D;
        this.zd = dz + (random.nextDouble() - 0.5D) * 0.08D;
        this.friction = 0.82F;
        this.gravity = 0.0F;
        this.hasPhysics = false;
        this.lifetime = 12 + random.nextInt(14);
        this.baseSize = 0.12F + random.nextFloat() * 0.22F;
        this.quadSize = baseSize;
        this.roll = this.oRoll = (random.nextFloat() - 0.5F) * 0.6F;
        this.pickSprite(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        float k = age / (float) lifetime;
        this.oRoll = this.roll;
        this.roll += 0.05F;
        this.quadSize = baseSize * (k < 0.15F ? 0.6F + k / 0.15F * 0.4F : 1.0F - (k - 0.15F) * 0.7F);
        this.alpha = Mth.clamp(1.0F - (k - 0.5F) * 2.0F, 0.0F, 1.0F);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    protected int getLightColor(float partial) {
        return 0xF000F0; // glows
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
            return new HamonSparkParticle(level, x, y, z, dx, dy, dz, sprites);
        }
    }
}
