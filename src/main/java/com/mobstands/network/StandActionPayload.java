package com.mobstands.network;

import com.mobstands.MobStands;
import com.mobstands.stand.StandManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: 0 = toggle summon, 1 = basic attack, 2 = Z, 3 = X, 4 = C, 5 = toggle pose. */
public record StandActionPayload(int action) implements CustomPacketPayload {
    public static final Type<StandActionPayload> TYPE = new Type<>(MobStands.id("stand_action"));
    public static final StreamCodec<ByteBuf, StandActionPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, StandActionPayload::action, StandActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StandActionPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (payload.action() == 0) {
                StandManager.toggle(sp);
            } else if (payload.action() == 41) {
                com.mobstands.stand.StandTools.toggle(sp); // R with a tool Stand out: the tool in or out of your hand
            } else if (payload.action() >= 1 && payload.action() <= 4) {
                StandManager.use(sp, payload.action() - 1);
            } else if (payload.action() == 5) {
                StandManager.togglePose(sp);
            } else if (payload.action() == 6) {
                StandManager.horseRushToggle(sp);
            } else if (payload.action() == 7 || payload.action() == 8) {
                StandManager.spinPress(sp, payload.action() == 8); // 8 = Ctrl+Q (whole stack)
            } else if (payload.action() == 9 || payload.action() == 11) {
                StandManager.spinDrop(sp, payload.action() == 11); // a plain Q tap: drop (11 = whole stack)
            } else if (payload.action() == 10) {
                StandManager.spinStart(sp);
            } else if (payload.action() == 12) {
                StandManager.spinThrow(sp);
            } else if (payload.action() == 18) {
                StandManager.spinDualSteel(sp);     // Shift: a second Steel Ball jumps into your off hand, both spin
            } else if (payload.action() == 17) {
                StandManager.spinStart(sp);        // both hands at once
                StandManager.spinOffHand(sp, 15);
            } else if (payload.action() >= 13 && payload.action() <= 16) {
                StandManager.spinOffHand(sp, payload.action()); // the same Spin controls, for the off hand
            } else if (payload.action() == 36) {
                com.mobstands.stand.SoulControl.ability(sp); // Soul Control: the possessed mob's own power
            } else if (payload.action() == 35) {
                com.mobstands.stand.SoulControl.attack(sp); // Soul Control: the possessed mob attacks
            } else if (payload.action() == 33) {
                StandManager.pilotShoot(sp);  // Aerosmith: fire
            } else if (payload.action() == 34) {
                StandManager.pilotLand(sp);   // Aerosmith: bring it back
            } else if (payload.action() == 32) {
                com.mobstands.stand.StandManager.recallBall(sp); // right-click your ball from afar: it comes back
            } else if (payload.action() == 31) {
                com.mobstands.stand.HorseSpin.retrieve(sp); // take your Steel Balls back off your horse
            } else if (payload.action() == 30) {
                com.mobstands.stand.Ger.skip(sp); // skip GER's Return to Zero cutscene
            } else if (payload.action() == 25) {
                StandManager.use(sp, 5); // the 6th ability key (B)
            } else if (payload.action() == 24) {
                StandManager.use(sp, 4); // the 5th ability key (V)
            } else if (payload.action() == 23) {
                StandManager.cartEnd(sp, StandManager.data(sp)); // Shift: hop out of the Minecart
            } else if (payload.action() == 21 || payload.action() == 22) {
                StandManager.spinFumble(sp, payload.action() == 22); // missed the Spin timing: it slips out of your hand
            } else if (payload.action() == 20) {
                StandManager.allayRelease(sp); // Allay: spam X 3 times to let go
            } else if (payload.action() == 28) {
                com.mobstands.stand.PigActs.switchAct(sp); // Shift + summon: next Act (Pig line)
            } else if (payload.action() == 26) {
                StandManager.allayDragStart(sp); // Allay: hold X on a held enemy to drag it
            } else if (payload.action() == 27) {
                StandManager.allayDragEnd(sp);
            } else if (payload.action() >= 200 && payload.action() <= 203) {
                StandManager.allayDupeHit(sp, payload.action() - 200);
            } else if (payload.action() >= 100 && payload.action() <= 140) {
                StandManager.spinThrowFar(sp, payload.action() - 100); // right-click throw, charge 0..40
            }
        });
    }
}
