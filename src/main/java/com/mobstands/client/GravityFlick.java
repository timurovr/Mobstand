package com.mobstands.client;

import com.mobstands.network.GravityPickPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Crescent Shell's gravity controls: hold the key and tap a movement key - no menus, no aiming.
 * You don't walk while you're holding it.
 *
 *   C (Gravity Turn, your own gravity): W the wall in front, A the left wall, D the right wall, Space the ceiling,
 *     S back to the normal floor. Just tapping C (no direction) also puts you back on the floor.
 *   X (Gravity Punch, your next punch): W away from you, A to your left, D to your right, Space up into the sky,
 *     S back toward you. Just tapping X = away from you.
 */
public final class GravityFlick {
    private GravityFlick() {}

    private static final int SKY = 0, LEFT = 1, RIGHT = 2, BACK = 3, FRONT = 4;

    /** 0 = not picking, 1 = Gravity Punch (X), 2 = Gravity Turn (C). */
    private static int mode;
    private static boolean picked;
    /** Which direction keys were already held when you started (so walking into it doesn't pick anything). */
    private static final boolean[] wasDown = new boolean[5];

    public static boolean active() {
        return mode != 0;
    }

    private static KeyMapping[] dirKeys(Minecraft mc) {
        // Same order as the picks: Space = sky, A = left, D = right, S = back, W = front.
        return new KeyMapping[]{mc.options.keyJump, mc.options.keyLeft, mc.options.keyRight, mc.options.keyDown, mc.options.keyUp};
    }

    public static void start(int which) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mode != 0) return;
        mode = which;
        picked = false;
        KeyMapping[] keys = dirKeys(mc);
        for (int i = 0; i < 5; i++) wasDown[i] = keys[i].isDown();
        hint(mc);
    }

    private static void hint(Minecraft mc) {
        boolean self = mode == 2;
        mc.player.displayClientMessage(Component.literal(self
                ? "Gravity Turn:  W front wall · A left · D right · Space ceiling · S floor"
                : "Gravity Punch:  W away · A left · D right · Space up · S toward you")
                .withStyle(s -> s.withColor(0xB38BC9).withBold(true)), true);
    }

    public static void tick() {
        if (mode == 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            mode = 0;
            return;
        }
        boolean self = mode == 2;
        KeyMapping[] keys = dirKeys(mc);
        for (int i = 0; i < 5; i++) {
            boolean d = keys[i].isDown();
            if (d && !wasDown[i]) {
                send(self, i); // a fresh tap: that's the pick
                picked = true;
                mc.player.displayClientMessage(Component.literal((self ? "Gravity Turn: " : "Gravity Punch: ") + label(i, self))
                        .withStyle(s -> s.withColor(0xB38BC9).withBold(true)), true);
                if (!self) {
                    mode = 0; // the punch is armed: done
                    return;
                }
            }
            wasDown[i] = d;
        }
        boolean held = mc.screen == null && (self ? StandKeys.ULTIMATE : StandKeys.ABILITY2).isDown();
        if (!held) {
            // Let go without picking: C = back to the floor, X = away from you.
            if (!picked) send(self, self ? BACK : FRONT);
            mode = 0;
        }
    }

    private static void send(boolean self, int choice) {
        PacketDistributor.sendToServer(new GravityPickPayload(self ? 10 + choice : choice));
    }

    /** You don't walk, jump or sneak while picking. */
    @SubscribeEvent
    public static void onInput(MovementInputUpdateEvent event) {
        if (mode == 0) return;
        net.minecraft.client.player.Input in = event.getInput();
        in.forwardImpulse = 0.0F;
        in.leftImpulse = 0.0F;
        in.up = in.down = in.left = in.right = false;
        in.jumping = false;
        in.shiftKeyDown = false;
    }

    private static String label(int c, boolean self) {
        if (self) {
            return switch (c) {
                case SKY -> "the ceiling";
                case LEFT -> "the left wall";
                case RIGHT -> "the right wall";
                case BACK -> "back to the floor";
                default -> "the wall in front";
            };
        }
        return switch (c) {
            case SKY -> "up into the sky";
            case LEFT -> "to your left";
            case RIGHT -> "to your right";
            case BACK -> "back toward you";
            default -> "away from you";
        };
    }
}
