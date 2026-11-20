package com.mobstands.client;

import com.mobstands.network.GravityPickPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * C-Moon's Gravity Punch wheel: held open by the ability key. Move the mouse toward an arrow and let go -
 * your next punch turns that creature's gravity that way. Letting go in the middle = away from you.
 */
public final class GravityPickScreen extends Screen {
    public static final int SKY = 0, LEFT = 1, RIGHT = 2, BACK = 3, AWAY = 4;
    private static final int GREEN = 0x7ED957;
    private static final int RADIUS = 46, DEAD = 16;

    private int choice = AWAY;
    private boolean sent;
    /** True: Gravity Turn (your own gravity, on C) - false: Gravity Punch (on X). */
    private final boolean self;

    public GravityPickScreen() {
        this(false);
    }

    /**
     * {@code self}: the same wheel for your own gravity - up = the ceiling, left / right = the walls beside you,
     * down = back to the normal floor, middle = the wall in front of you.
     */
    public GravityPickScreen(boolean self) {
        super(Component.literal(self ? "Gravity Turn" : "Gravity Punch"));
        this.self = self;
    }

    private net.minecraft.client.KeyMapping key() {
        return self ? StandKeys.ULTIMATE : StandKeys.ABILITY2;
    }

    private int pickAt(double mx, double my) {
        double dx = mx - width / 2.0D, dy = my - height / 2.0D;
        if (dx * dx + dy * dy < DEAD * DEAD) return AWAY;
        if (Math.abs(dx) > Math.abs(dy)) return dx < 0 ? LEFT : RIGHT;
        return dy < 0 ? SKY : BACK;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // No blur: the world stays visible behind the wheel.
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        choice = pickAt(mouseX, mouseY);
        int cx = width / 2, cy = height / 2;
        arrow(g, "↑", cx, cy - RADIUS, choice == SKY);
        arrow(g, "←", cx - RADIUS, cy, choice == LEFT);
        arrow(g, "→", cx + RADIUS, cy, choice == RIGHT);
        arrow(g, "↓", cx, cy + RADIUS, choice == BACK);
        // Middle: straight away from you.
        int c = choice == AWAY ? 0xFF000000 | GREEN : 0x90FFFFFF;
        g.fill(cx - 5, cy - 5, cx + 5, cy + 5, choice == AWAY ? 0x707ED957 : 0x40000000);
        g.renderOutline(cx - 6, cy - 6, 12, 12, c);
    }

    private void arrow(GuiGraphics g, String s, int x, int y, boolean on) {
        g.fill(x - 13, y - 13, x + 13, y + 13, on ? 0x707ED957 : 0x40000000);
        g.renderOutline(x - 14, y - 14, 28, 28, on ? 0xFF000000 | GREEN : 0x90FFFFFF);
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(2.0F, 2.0F, 1.0F);
        g.drawString(font, s, -font.width(s) / 2, -4, on ? 0xFFFFFFFF : 0xFFBBBBBB, true);
        g.pose().popPose();
    }

    private void finish() {
        if (sent) return;
        sent = true;
        PacketDistributor.sendToServer(new GravityPickPayload(self ? 10 + choice : choice));
        onClose();
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (key().matches(keyCode, scanCode)) {
            finish();
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (key().matchesMouse(button)) {
            choice = pickAt(mx, my);
            finish();
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        choice = pickAt(mx, my); // clicking an arrow picks it too
        finish();
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
