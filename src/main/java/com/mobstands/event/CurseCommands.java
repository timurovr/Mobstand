package com.mobstands.event;

import com.mobstands.stand.Curses;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;

/**
 * /curse - everything about the cursed Stands.
 * <ul>
 * <li>/curse list - the four cursed Stands, how you catch them and how you lose them (anyone)</li>
 * <li>/curse info &lt;curse&gt; - one of them in full (anyone)</li>
 * <li>/curse check [player] - which curse has you (or someone else: operators)</li>
 * <li>/curse give &lt;curse&gt; [player] - put a curse on someone (operators)</li>
 * <li>/curse lift [player] - take it off (operators)</li>
 * </ul>
 */
public final class CurseCommands {
    private CurseCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("curse")
                .executes(CurseCommands::list)
                .then(Commands.literal("list").executes(CurseCommands::list))
                .then(Commands.literal("info")
                        .then(Commands.argument("curse", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Curses.IDS), b))
                                .executes(CurseCommands::info)))
                .then(Commands.literal("check")
                        .executes(ctx -> check(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(s -> s.hasPermission(2))
                                .executes(ctx -> check(ctx, EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("give")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("curse", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Curses.IDS), b))
                                .executes(ctx -> give(ctx, ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> give(ctx, EntityArgument.getPlayer(ctx, "player"))))))
                .then(Commands.literal("lift")
                        .requires(s -> s.hasPermission(2))
                        .executes(ctx -> lift(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> lift(ctx, EntityArgument.getPlayer(ctx, "player"))))));
    }

    private static MutableComponent name(int k) {
        return Component.literal(Curses.NAMES[k]).withStyle(st -> st.withColor(Curses.COLORS[k]).withBold(true));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSystemMessage(Component.literal("☠ Cursed Stands ☠").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        src.sendSystemMessage(Component.literal("No arrow gives these. Something happens to you, and one latches on. "
                + "While it has you, your own Stand is smothered.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        for (int k = 0; k < Curses.NAMES.length; k++) {
            src.sendSystemMessage(Component.literal(" ")
                    .append(name(k))
                    .append(Component.literal(" (" + Curses.MOBS[k] + ")").withStyle(ChatFormatting.DARK_GRAY)));
            src.sendSystemMessage(Component.literal("   Catch: ").withStyle(ChatFormatting.RED)
                    .append(Component.literal(Curses.CATCH[k]).withStyle(ChatFormatting.GRAY)));
            src.sendSystemMessage(Component.literal("   Lose it: ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(Curses.LOSE[k]).withStyle(ChatFormatting.GRAY)));
        }
        src.sendSystemMessage(Component.literal("/curse info <curse> for more.").withStyle(ChatFormatting.DARK_GRAY));
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        int k = Curses.idOf(StringArgumentType.getString(ctx, "curse"));
        CommandSourceStack src = ctx.getSource();
        if (k < 0) {
            src.sendFailure(Component.literal("No such curse. Try: " + String.join(", ", Curses.IDS)));
            return 0;
        }
        src.sendSystemMessage(Component.literal("☠ ").withStyle(ChatFormatting.DARK_RED).append(name(k))
                .append(Component.literal(" (" + Curses.MOBS[k] + ")").withStyle(ChatFormatting.DARK_GRAY)));
        src.sendSystemMessage(Component.literal("Catch: ").withStyle(ChatFormatting.RED)
                .append(Component.literal(Curses.CATCH[k]).withStyle(ChatFormatting.GRAY)));
        src.sendSystemMessage(Component.literal("What it does: ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(Curses.DOES[k]).withStyle(ChatFormatting.GRAY)));
        src.sendSystemMessage(Component.literal("Lose it: ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(Curses.LOSE[k]).withStyle(ChatFormatting.GRAY)));
        src.sendSystemMessage(Component.literal("Your own Stand is smothered the whole time. Dying gets rid of it too.")
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        return 1;
    }

    private static int check(CommandContext<CommandSourceStack> ctx, ServerPlayer p) {
        int k = Curses.kindOf(p);
        boolean self = ctx.getSource().getEntity() == p;
        if (k < 0) {
            ctx.getSource().sendSystemMessage(Component.literal((self ? "You're" : p.getGameProfile().getName() + " is")
                    + " not cursed. For now.").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        ctx.getSource().sendSystemMessage(Component.literal((self ? "You have " : p.getGameProfile().getName() + " has "))
                .withStyle(ChatFormatting.GRAY).append(name(k))
                .append(Component.literal(". Lose it: " + Curses.LOSE[k]).withStyle(ChatFormatting.GRAY)));
        return 1;
    }

    private static int give(CommandContext<CommandSourceStack> ctx, ServerPlayer p) throws CommandSyntaxException {
        int k = Curses.idOf(StringArgumentType.getString(ctx, "curse"));
        if (k < 0) {
            ctx.getSource().sendFailure(Component.literal("No such curse. Try: " + String.join(", ", Curses.IDS)));
            return 0;
        }
        Curses.force(p, k);
        ctx.getSource().sendSuccess(() -> Component.literal("Cursed " + p.getGameProfile().getName() + " with ")
                .append(name(k)).append("."), true);
        return 1;
    }

    private static int lift(CommandContext<CommandSourceStack> ctx, ServerPlayer p) {
        if (Curses.kindOf(p) < 0) {
            ctx.getSource().sendFailure(Component.literal(p.getGameProfile().getName() + " isn't cursed."));
            return 0;
        }
        Curses.lift(p, "was lifted.");
        ctx.getSource().sendSuccess(() -> Component.literal("Lifted the curse on " + p.getGameProfile().getName() + "."), true);
        return 1;
    }
}
