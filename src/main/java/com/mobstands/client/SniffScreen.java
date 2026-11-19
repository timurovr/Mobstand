package com.mobstands.client;

import com.mobstands.network.SniffPickPayload;
import com.mobstands.network.SniffRollPayload;
import com.mobstands.stand.Sniff;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Sniff Out: three slot reels spin through the structures of this world and land one by one. Pick one, or pass
 * ("None of these"). With a trained nose (three passes) it's a search box instead - guaranteed.
 */
public class SniffScreen extends Screen {
    private static final long[] STOP_MS = {1100L, 1700L, 2300L};
    private static final long SPIN_STEP_MS = 70L;

    private final List<String> options;
    private final List<String> pool;
    private final boolean search;
    private final int misses;
    private final long start = Util.getMillis();
    private final Random rng = new Random();
    private final boolean[] landed = new boolean[3];
    private boolean sent;
    private EditBox box;
    private final List<Button> results = new ArrayList<>();
    /** The three reels: real Minecraft buttons - greyed out while they spin, clickable once they land. */
    private final Button[] reels = new Button[3];
    private Button none;

    private SniffScreen(SniffRollPayload p) {
        super(Component.literal("Sniff Out"));
        this.options = p.options();
        this.pool = p.pool();
        this.search = p.search();
        this.misses = p.misses();
    }

    public static void open(SniffRollPayload p) {
        Minecraft.getInstance().setScreen(new SniffScreen(p));
    }

    @Override
    protected void init() {
        results.clear();
        if (search) {
            box = new EditBox(font, width / 2 - 100, height / 2 - 70, 200, 18, Component.literal("Search"));
            box.setHint(Component.literal("Search for a structure..."));
            box.setResponder(s -> refreshResults());
            addRenderableWidget(box);
            setInitialFocus(box);
            refreshResults();
        } else {
            int cx = width / 2;
            int n = Math.max(1, Math.min(3, options.size())); // the Nether: just one reel
            for (int i = 0; i < n; i++) {
                final int k = i;
                reels[i] = addRenderableWidget(Button.builder(Component.literal("..."), b -> {
                    if (k < options.size()) choose(options.get(k));
                }).bounds(cx - (n * 118 - 10) / 2 + i * 118, height / 2 - 30, 108, 40).build());
                reels[i].active = false;
            }
            none = addRenderableWidget(Button.builder(Component.literal("None of these (" + (misses + 1) + "/" + Sniff.MISSES_FOR_SEARCH + ")"),
                    b -> choose("")).bounds(cx - 75, height / 2 + 24, 150, 20).build());
            none.active = false;
            updateReels();
        }
    }

    private boolean allLanded() {
        return Util.getMillis() - start >= STOP_MS[Math.max(0, Math.min(3, options.size()) - 1)];
    }

    /** Spinning reels flick through random structures; landed ones show their pick and light up. */
    private void updateReels() {
        long el = Util.getMillis() - start;
        for (int i = 0; i < 3; i++) {
            if (reels[i] == null) continue;
            boolean stopped = el >= STOP_MS[i];
            String name;
            if (stopped && i < options.size()) {
                name = Sniff.pretty(options.get(i));
            } else if (stopped) {
                name = "-";
            } else {
                rng.setSeed((el / SPIN_STEP_MS + i * 7L) * 31L + i);
                name = pool.isEmpty() ? "?" : Sniff.pretty(pool.get(rng.nextInt(pool.size())));
            }
            reels[i].setMessage(Component.literal(name));
            reels[i].active = stopped && i < options.size();
        }
        if (none != null) none.active = allLanded();
    }

    private void refreshResults() {
        for (Button b : results) removeWidget(b);
        results.clear();
        String q = box == null ? "" : box.getValue().toLowerCase(Locale.ROOT).trim();
        int y = height / 2 - 46;
        int shown = 0;
        for (String id : pool) {
            String name = Sniff.pretty(id);
            if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q) && !id.contains(q)) continue;
            Button b = Button.builder(Component.literal(name), x -> choose(id)).bounds(width / 2 - 100, y, 200, 18).build();
            results.add(addRenderableWidget(b));
            y += 20;
            if (++shown >= 8) break;
        }
    }

    private void choose(String id) {
        if (sent) return;
        sent = true;
        PacketDistributor.sendToServer(new SniffPickPayload(id));
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void onClose() {
        choose(""); // backing out = none of these
    }

    @Override
    public void tick() {
        if (search) return;
        long el = Util.getMillis() - start;
        int n = Math.max(1, Math.min(3, options.size()));
        for (int i = 0; i < n; i++) {
            if (!landed[i] && el >= STOP_MS[i]) {
                landed[i] = true;
                var mc = Minecraft.getInstance();
                if (mc.player != null) mc.player.playSound(i == n - 1 ? SoundEvents.SNIFFER_HAPPY : SoundEvents.NOTE_BLOCK_BIT.value(), 0.7F, 1.0F + i * 0.2F);
            }
        }
        updateReels();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int cx = width / 2;
        if (search) {
            g.drawCenteredString(font, "Your Sniffer's nose is trained: sniff out anything", cx, height / 2 - 88, 0x5FD18A);
            if (results.isEmpty()) g.drawCenteredString(font, "No structure by that name here", cx, height / 2 - 40, 0x888888);
            return;
        }
        g.drawCenteredString(font, "Sniff Out", cx, height / 2 - 62, 0xC9A27A);
        g.drawCenteredString(font, "Pick one, or wait 10 minutes for the next roll", cx, height / 2 - 50, 0x999999);
        updateReels();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
