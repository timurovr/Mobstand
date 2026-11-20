package com.mobstands.stand;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * C-Moon: a creature standing on a wall lies sideways, so its hitbox has to lie sideways too - otherwise its body
 * sticks out of the narrow upright box and slides through leaves and walls. Same box on both sides of the game.
 */
public final class GravityBox {
    private GravityBox() {}

    /** Client side: which way "down" is for each turned creature (entity id -> direction). */
    public static final Map<Integer, Direction> CLIENT = new ConcurrentHashMap<>();

    /** The box for a creature whose gravity points at a wall (null = the normal upright box). */
    public static AABB box(Entity e) {
        boolean client = e.level().isClientSide;
        if (client ? CLIENT.isEmpty() : !GravityFlip.anyFlipped()) return null; // the usual case: nothing to do
        Direction g = client ? CLIENT.get(e.getId()) : GravityFlip.dir(e);
        if (g == null || g.getAxis() == Direction.Axis.Y) return null; // floor or ceiling: upright box is right
        double w = e.getBbWidth(), h = e.getBbHeight();
        Vec3 down = GravityFlip.vec(g);
        // Same place the body is drawn: feet flat on the wall, body sticking straight out from it.
        Vec3 c = e.position().add(0.0D, h * 0.5D, 0.0D).add(down.scale(w * 0.5D - h * 0.5D));
        double ex = g.getAxis() == Direction.Axis.X ? h * 0.5D : w * 0.5D;
        double ez = g.getAxis() == Direction.Axis.Z ? h * 0.5D : w * 0.5D;
        double ey = w * 0.5D;
        return new AABB(c.x - ex, c.y - ey, c.z - ez, c.x + ex, c.y + ey, c.z + ez);
    }

    /** Rebuild a creature's box right away after its gravity turned. */
    public static void refresh(Entity e) {
        e.setPos(e.getX(), e.getY(), e.getZ());
    }
}
