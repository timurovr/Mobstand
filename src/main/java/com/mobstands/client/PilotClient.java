package com.mobstands.client;

import com.mobstands.entity.StandEntity;
import com.mobstands.network.StandActionPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Aerosmith, on the pilot's side: the camera rides the Phantom, your body stands still, the mouse steers,
 * left click (held) fires, Shift brings it back.
 */
public final class PilotClient {
    private PilotClient() {}

    private static StandEntity flying;
    private static int fireCooldown;
    private static boolean landSent;
    /** Third person behind the Phantom while you fly it; F5 steps through these camera distances instead. */
    private static final float[] ZOOMS = {4.0F, 7.0F, 11.0F, 2.5F};
    private static int zoom;
    private static net.minecraft.client.CameraType savedCamera;

    private static int heatLeft = com.mobstands.stand.StandManager.PILOT_AMMO;
    private static boolean overheated;

    public static void setHeat(int left, boolean hot) {
        heatLeft = left;
        overheated = hot;
    }

    /**
     * The gun's heat gauge on the right edge of the screen: one solid bar that drains as you fire and fills back up
     * as it cools - blue when cool, through orange, to red. Overheated, it flashes red until it's cooled right down.
     */
    public static void render(net.minecraft.client.gui.GuiGraphics g, net.minecraft.client.DeltaTracker delta) {
        if (flying == null || flying.getStandType() != com.mobstands.stand.StandType.PHANTOM) return; // (only the Phantom has a gun)
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        // Crosshair: a single dot dead centre, where your shots go.
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        g.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFFFFFF); // plain white
        int h = 110, w = 7;
        int x = g.guiWidth() - 18, y = g.guiHeight() / 2 - h / 2;
        float k = net.minecraft.util.Mth.clamp(heatLeft / (float) com.mobstands.stand.StandManager.PILOT_AMMO, 0.0F, 1.0F);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFFFFFFFF); // white border
        g.fill(x, y, x + w, y + h, 0xFF14142A);                 // empty
        int fill = Math.round(h * k);
        int color;
        if (overheated) {
            boolean on = (mc.level != null ? mc.level.getGameTime() : 0L) / 4L % 2L == 0L;
            color = on ? 0xFFFF3B2E : 0xFF8A1E18;
        } else {
            color = 0xFF2F6BFF; // one flat blue
        }
        g.fill(x, y + h - fill, x + w, y + h, color);
        String label = overheated ? "HOT" : "GUN";
        g.drawString(mc.font, label, x + w / 2 - mc.font.width(label) / 2, y + h + 5, overheated ? 0xFF5533 : 0x9FB4FF, true);
    }

    /** Your body's heading when you took off: it stays facing that way while the mouse steers the Phantom. */
    private static float frozenYaw, frozenPitch;

    /**
     * Every frame: whatever the mouse just turned your player by goes to the Phantom instead, and your player is
     * put back - so your character stands still instead of looking around with you.
     */
    public static void onFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        transferLook();
    }

    private static void transferLook() {
        Minecraft mc = Minecraft.getInstance();
        if (flying == null || mc.player == null) return;
        var p = mc.player;
        float dy = p.getYRot() - frozenYaw, dx = p.getXRot() - frozenPitch;
        if (dy == 0.0F && dx == 0.0F) return;
        StandEntity.pilotYaw += dy;
        StandEntity.pilotPitch = net.minecraft.util.Mth.clamp(StandEntity.pilotPitch + dx, -90.0F, 90.0F);
        p.setYRot(frozenYaw);
        p.yRotO = frozenYaw;
        p.setXRot(frozenPitch);
        p.xRotO = frozenPitch;
        p.setYHeadRot(frozenYaw);
        p.yHeadRotO = frozenYaw;
    }

    public static boolean active() {
        return flying != null;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            flying = null;
            return;
        }
        StandEntity found = null;
        for (StandEntity s : mc.level.getEntitiesOfClass(StandEntity.class, mc.player.getBoundingBox().inflate(StandEntity.PILOT_RANGE + 16.0D),
                s -> s.isPiloting() && s.getOwnerPlayer() == mc.player && !s.isRemoved())) {
            found = s;
        }
        boolean was = flying != null;
        flying = found;
        if (was && found != null) transferLook();
        if (found != null) {
            if (mc.getCameraEntity() != found) mc.setCameraEntity(found);
            if (!was) {
                frozenYaw = mc.player.getYRot();
                frozenPitch = mc.player.getXRot();
                StandEntity.pilotYaw = frozenYaw;
                StandEntity.pilotPitch = frozenPitch;
                savedCamera = mc.options.getCameraType();
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
            } else if (mc.options.getCameraType() != net.minecraft.client.CameraType.THIRD_PERSON_BACK) {
                // F5 was pressed: zoom instead of switching view.
                zoom = (zoom + 1) % ZOOMS.length;
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("Camera distance: " + (int) ZOOMS[zoom] + " blocks")
                        .withStyle(s -> s.withColor(0x6F8CFF)), true);
            }
            if (fireCooldown > 0) fireCooldown--;
            if (mc.screen == null && mc.options.keyUse.isDown() && fireCooldown == 0 && found.getStandType() == com.mobstands.stand.StandType.PHANTOM) {
                PacketDistributor.sendToServer(new StandActionPayload(33));
                fireCooldown = 3;
            }
        } else {
            landSent = false;
            if (mc.getCameraEntity() instanceof StandEntity) mc.setCameraEntity(mc.player);
            if (was && savedCamera != null) {
                mc.options.setCameraType(savedCamera); // back to however you had it
                savedCamera = null;
            }
        }
    }

    /** How far behind the Phantom the camera sits. */
    public static void onCameraDistance(net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent event) {
        if (flying != null) event.setDistance(ZOOMS[zoom]);
    }

    /** Your body stays put while you fly; your keys fly the Phantom. */
    public static void onInput(net.neoforged.neoforge.client.event.MovementInputUpdateEvent event) {
        if (flying == null) return;
        var in = event.getInput();
        // Your keys fly the Phantom instead of you.
        StandEntity.pilotForward = in.forwardImpulse;
        StandEntity.pilotStrafe = in.leftImpulse;
        StandEntity.pilotUp = in.jumping;
        StandEntity.pilotDown = in.shiftKeyDown;
        StandEntity.pilotSprint = Minecraft.getInstance().options.keySprint.isDown();
        in.forwardImpulse = 0.0F;
        in.leftImpulse = 0.0F;
        in.up = in.down = in.left = in.right = false;
        in.jumping = false;
        in.shiftKeyDown = false;
    }
}
