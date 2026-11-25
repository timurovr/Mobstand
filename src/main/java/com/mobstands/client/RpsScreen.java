package com.mobstands.client;

import com.mobstands.network.RpsPickPayload;
import com.mobstands.stand.Curses;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Boy II Man: rock, paper, scissors. It pops up whether you were busy or not. Close it or run out of time and it
 * picks your hand for you.
 */
public final class RpsScreen extends Screen {
    private final int wins, losses, timeout;
    private final String taunt;
    private final long openedAt;
    private boolean picked;

    private static final ItemStack[] ICONS = {new ItemStack(Items.COBBLESTONE), new ItemStack(Items.PAPER), new ItemStack(Items.SHEARS)};
    private static final String[] LABELS = {"Rock", "Paper", "Scissors"};

    private RpsScreen(int wins, int losses, String taunt, int timeout) {
        super(Component.literal("Boy II Man"));
        this.wins = wins;
        this.losses = losses;
        this.taunt = taunt;
        this.timeout = timeout;
        Minecraft mc = Minecraft.getInstance();
        this.openedAt = mc.level != null ? mc.level.getGameTime() : 0L;
    }

    public static void open(int wins, int losses, String taunt, int timeout) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        mc.setScreen(new RpsScreen(wins, losses, taunt, timeout));
    }

    @Override
    protected void init() {
        int cx = width / 2, y = height / 2 - 4;
        for (int i = 0; i < 3; i++) {
            int choice = i;
            addRenderableWidget(Button.builder(Component.literal("      " + LABELS[i]), b -> pick(choice))
                    .bounds(cx - 150 + i * 102, y, 96, 24).build());
        }
    }

    private void pick(int choice) {
        if (picked) return;
        picked = true;
        PacketDistributor.sendToServer(new RpsPickPayload(choice));
        onClose();
    }

    @Override
    public void tick() {
        super.tick();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getGameTime() - openedAt > timeout) onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int cx = width / 2, y = height / 2 - 4;
        for (int i = 0; i < 3; i++) g.renderItem(ICONS[i], cx - 150 + i * 102 + 8, y + 4);
        int col = Curses.COLORS[3];
        g.drawCenteredString(font, Component.literal("Boy II Man").withStyle(st -> st.withColor(col).withBold(true)), cx, y - 58, 0xFFFFFF);
        g.drawCenteredString(font, Component.literal("\"" + taunt + "\""), cx, y - 42, 0xE8D8FF);
        g.drawCenteredString(font, Component.literal("Wins: " + wins + "/" + Curses.RPS_WINS + "     Losses: " + losses), cx, y - 24, 0xB0B0B0);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            float left = Math.max(0.0F, (timeout - (mc.level.getGameTime() - openedAt) - partial) / 20.0F);
            g.drawCenteredString(font, String.format("%.0f", Math.ceil(left)), cx, y + 34, left < 4.0F ? 0xFF6060 : 0xFFFFFF);
        }
    }

    @Override
    public void removed() {
        if (!picked) {
            picked = true;
            PacketDistributor.sendToServer(new RpsPickPayload(-1));
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
