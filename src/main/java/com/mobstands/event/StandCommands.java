package com.mobstands.event;

import com.mobstands.stand.StandData;
import com.mobstands.stand.StandManager;
import com.mobstands.stand.StandType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;

/** Operator/testing commands: /stand set <type>, /stand level <n>, /stand clear. */
public final class StandCommands {
    private StandCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("stand")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("set")
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(StandType.values()).map(StandType::id), b))
                                .executes(StandCommands::set)))
                .then(Commands.literal("level")
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, StandData.MAX_LEVEL))
                                .executes(StandCommands::level)))
                .then(Commands.literal("clear").executes(StandCommands::clear))
                // Requiem Stands: show or hide the rising aura.
                .then(Commands.literal("aura")
                        .then(Commands.literal("on").executes(ctx -> setAura(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setAura(ctx, false))))
                // The Spin technique (separate from your Stand).
                .then(Commands.literal("spin")
                        .then(Commands.literal("learn").executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            if (!StandManager.learnSpin(p)) ctx.getSource().sendFailure(Component.literal("You already know the Spin."));
                            return 1;
                        }))
                        .then(Commands.literal("forget").executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            StandData d = StandManager.data(p);
                            if (d.spinning) StandManager.spinDrop(p, false);
                            d.spinLearned = false;
                            d.spinning = false;
                            d.offSpinning = false;
                            d.spinLevel = 1;
                            d.spinXp = 0;
                            StandManager.sync(p);
                            ctx.getSource().sendSuccess(() -> Component.literal("You forgot the Spin."), false);
                            return 1;
                        }))
                        .then(Commands.literal("level")
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, StandData.SPIN_MAX_LEVEL)).executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    StandData d = StandManager.data(p);
                                    if (!d.spinLearned) StandManager.learnSpin(p);
                                    d.spinLevel = IntegerArgumentType.getInteger(ctx, "level");
                                    d.spinXp = 0;
                                    StandManager.sync(p);
                                    ctx.getSource().sendSuccess(() -> Component.literal("Spin level set to " + d.spinLevel), false);
                                    return 1;
                                })))));
    }

    private static int setAura(CommandContext<CommandSourceStack> ctx, boolean on) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        StandData d = StandManager.data(p);
        d.auraOff = !on;
        StandManager.sync(p);
        boolean requiem = d.getType() != null && d.getType().requiem();
        ctx.getSource().sendSuccess(() -> Component.literal("Requiem aura " + (on ? "on" : "off")
                + (requiem ? "" : " (only Requiem Stands have one)")), false);
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        StandType t = StandType.byId(StringArgumentType.getString(ctx, "type"));
        if (t == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown Stand type."));
            return 0;
        }
        StandManager.assign(p, t);
        ctx.getSource().sendSuccess(() -> Component.literal("Stand set to " + t.standName() + " (" + t.mobName() + ")"), false);
        return 1;
    }

    private static int level(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        StandData d = StandManager.data(p);
        if (d.getType() == null) {
            ctx.getSource().sendFailure(Component.literal("You have no Stand."));
            return 0;
        }
        d.setLevel(IntegerArgumentType.getInteger(ctx, "level"));
        d.setXp(0);
        StandManager.sync(p);
        ctx.getSource().sendSuccess(() -> Component.literal("Stand level set to " + d.getLevel()), false);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        StandData d = StandManager.data(p);
        StandManager.removeEntity(p);
        d.setType(null);
        d.resetProgress();
        d.setSummoned(false);
        StandManager.sync(p);
        ctx.getSource().sendSuccess(() -> Component.literal("Stand removed."), false);
        return 1;
    }
}
