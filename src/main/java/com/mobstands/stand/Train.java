package com.mobstands.stand;

import com.mobstands.entity.StandEntity;
import com.mobstands.entity.TrainCarEntity;
import com.mobstands.registry.ModEntities;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Iron Express is a transport Stand: while you're driving your Minecart, other players can right-click you to climb
 * aboard. Each one gets a car coupled on behind, and the cars follow the exact path you drive, like a train. Shift
 * to hop off; when you get out of your cart, everyone does.
 */
public final class Train {
    private Train() {}

    /** Up to this many cars behind you, each this far apart (blocks along your path). */
    public static final int MAX_CARS = 4;
    public static final double SPACING = 1.5D;

    private record Point(Vec3 pos, float yaw) {}

    private static final class Line {
        ServerLevel level;
        final List<TrainCarEntity> cars = new ArrayList<>();
        final ArrayDeque<Point> path = new ArrayDeque<>(); // newest first
    }

    private static final Map<UUID, Line> LINES = new HashMap<>();

    /** Someone right-clicked a player (or their Stand): if they're driving their cart, climb aboard. */
    public static boolean board(ServerPlayer rider, Entity clicked) {
        ServerPlayer driver = null;
        if (clicked instanceof ServerPlayer sp) driver = sp;
        else if (clicked instanceof StandEntity s && s.getOwnerPlayer() instanceof ServerPlayer sp) driver = sp;
        if (driver == null || driver == rider || rider.isPassenger() || rider.isSpectator()) return false;
        StandData d = StandManager.data(driver);
        if (!d.cartRiding) return false;
        Line line = LINES.computeIfAbsent(driver.getUUID(), k -> new Line());
        line.level = driver.serverLevel();
        line.cars.removeIf(c -> c.isRemoved() || c.getPassengers().isEmpty() && c.tickCount > 5);
        for (TrainCarEntity c : line.cars) if (c.hasPassenger(rider)) return true;
        if (line.cars.size() >= MAX_CARS) {
            rider.displayClientMessage(Component.literal("The train's full."), true);
            return true;
        }
        TrainCarEntity car = ModEntities.TRAIN_CAR.get().create(line.level);
        if (car == null) return false;
        Point at = place(line, driver, line.cars.size() + 1);
        car.moveTo(at.pos().x, at.pos().y, at.pos().z, at.yaw(), 0.0F);
        line.level.addFreshEntity(car);
        rider.startRiding(car, true);
        line.cars.add(car);
        line.level.playSound(null, car.getX(), car.getY(), car.getZ(), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1.0F, 0.9F);
        line.level.playSound(null, car.getX(), car.getY(), car.getZ(), SoundEvents.MINECART_INSIDE, SoundSource.PLAYERS, 0.5F, 1.3F);
        driver.displayClientMessage(Component.literal(rider.getGameProfile().getName() + " climbed aboard."), true);
        return true;
    }

    public static void tick(MinecraftServer server) {
        if (LINES.isEmpty()) return;
        Iterator<Map.Entry<UUID, Line>> it = LINES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Line> en = it.next();
            Line line = en.getValue();
            ServerPlayer driver = server.getPlayerList().getPlayer(en.getKey());
            line.cars.removeIf(c -> c.isRemoved());
            boolean driving = driver != null && driver.isAlive() && driver.serverLevel() == line.level && StandManager.data(driver).cartRiding;
            if (!driving || line.cars.isEmpty()) {
                // out of the cart (or gone): the whole train stops and everyone gets off
                for (TrainCarEntity c : line.cars) {
                    c.ejectPassengers();
                    c.discard();
                }
                if (!line.cars.isEmpty() && line.level != null) {
                    Vec3 p = line.cars.get(0).position();
                    line.level.playSound(null, p.x, p.y, p.z, SoundEvents.CHAIN_BREAK, SoundSource.PLAYERS, 0.8F, 1.0F);
                }
                it.remove();
                continue;
            }
            // Where the cart has been (newest first), long enough for every car.
            Vec3 now = driver.position();
            if (line.path.isEmpty() || line.path.peekFirst().pos().distanceToSqr(now) > 0.0025D) {
                line.path.addFirst(new Point(now, driver.getYRot()));
            }
            double need = (line.cars.size() + 2) * SPACING + 4.0D, have = 0.0D;
            Vec3 prev = null;
            int keep = 0;
            for (Point p : line.path) {
                if (prev != null) have += p.pos().distanceTo(prev);
                prev = p.pos();
                keep++;
                if (have > need) break;
            }
            while (line.path.size() > keep + 1) line.path.removeLast();
            // Lay each car along it.
            for (int i = 0; i < line.cars.size(); i++) {
                TrainCarEntity c = line.cars.get(i);
                Point at = place(line, driver, i + 1);
                c.setPos(at.pos().x, at.pos().y, at.pos().z);
                c.setYRot(at.yaw());
                for (Entity r : c.getPassengers()) r.resetFallDistance();
            }
        }
    }

    /** The spot {@code n} cars behind the driver along their path (and which way it faces). */
    private static Point place(Line line, ServerPlayer driver, int n) {
        double want = n * SPACING, walked = 0.0D;
        Vec3 prev = driver.position();
        Vec3 facingFrom = prev;
        for (Point p : line.path) {
            double seg = p.pos().distanceTo(prev);
            if (walked + seg >= want && seg > 1.0E-4D) {
                double t = (want - walked) / seg;
                Vec3 at = prev.add(p.pos().subtract(prev).scale(t));
                Vec3 dir = prev.subtract(p.pos());
                float yaw = dir.horizontalDistanceSqr() > 1.0E-5D ? (float) Math.toDegrees(Math.atan2(-dir.x, dir.z)) : driver.getYRot();
                return new Point(at, yaw);
            }
            walked += seg;
            facingFrom = prev;
            prev = p.pos();
        }
        // Not driven that far yet: line up straight behind the cart.
        float yr = driver.getYRot();
        Vec3 back = new Vec3(Math.sin(Math.toRadians(yr)), 0.0D, -Math.cos(Math.toRadians(yr))).scale(want - walked);
        return new Point(prev.add(back), yr);
    }

    public static void shutdown() {
        LINES.clear();
    }
}
