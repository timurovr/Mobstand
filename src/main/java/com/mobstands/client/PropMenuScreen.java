package com.mobstands.client;

import com.mobstands.network.PropPickPayload;
import com.mobstands.stand.PropertySwap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Pigstep: Property Swap's choice - take what makes it what it is, or one of the things every creature has
 * (its sight, its friction, its sound...). In singleplayer the world slows while you choose; on a server it doesn't.
 */
public final class PropMenuScreen extends Screen {
    private final String targetName;
    private final String own;
    private final int ownColor;
    private boolean picked;

    /** Singleplayer slow-down: the tick rate to go back to. */
    private static float savedRate = -1.0F;

    private PropMenuScreen(String targetName, String own, int ownColor) {
        super(Component.literal("Property Swap"));
        this.targetName = targetName;
        this.own = own;
        this.ownColor = ownColor;
    }

    public static void open(int entityId, String own, int color) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity e = mc.level.getEntity(entityId);
        mc.setScreen(new PropMenuScreen(e != null ? e.getName().getString() : "it", own, color));
    }

    private static IntegratedServer soloServer() {
        IntegratedServer s = Minecraft.getInstance().getSingleplayerServer();
        return s != null && !s.isPublished() ? s : null;
    }

    @Override
    protected void init() {
        IntegratedServer s = soloServer();
        if (s != null && savedRate < 0.0F) {
            s.execute(() -> {
                savedRate = s.tickRateManager().tickrate();
                s.tickRateManager().setTickRate(Math.max(1.0F, savedRate * 0.4F));
            });
        }
        int cx = width / 2, top = height / 2 - 60;
        addRenderableWidget(Button.builder(Component.literal(own).withStyle(st -> st.withColor(ownColor)), b -> pick(0))
                .bounds(cx - 120, top, 240, 20).build());
        // Its Sound first (held for later, like its property), then what it can just lose.
        String[] labels = new String[PropertySwap.Steal.values().length + 1];
        labels[0] = "Sound";
        for (int i = 0; i < PropertySwap.Steal.values().length; i++) labels[i + 1] = PropertySwap.Steal.values()[i].label;
        for (int i = 0; i < labels.length; i++) {
            int col = i % 2, row = i / 2;
            int choice = i + 1;
            addRenderableWidget(Button.builder(Component.literal(labels[i]), b -> pick(choice))
                    .bounds(cx - 120 + col * 122, top + 26 + row * 24, 118, 20).build());
        }
    }

    private void pick(int choice) {
        picked = true;
        PacketDistributor.sendToServer(new PropPickPayload(choice));
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(font, Component.literal("What do you take from " + targetName + "?").withStyle(st -> st.withColor(0xB07CFF)),
                width / 2, height / 2 - 80, 0xFFFFFF);
    }

    @Override
    public void removed() {
        if (!picked) PacketDistributor.sendToServer(new PropPickPayload(-1));
        IntegratedServer s = soloServer();
        if (s != null) {
            s.execute(() -> {
                if (savedRate > 0.0F) s.tickRateManager().setTickRate(savedRate);
                savedRate = -1.0F;
            });
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
