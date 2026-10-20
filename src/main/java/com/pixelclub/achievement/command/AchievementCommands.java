package com.pixelclub.achievement.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.pixelclub.achievement.PxcAchievementsMod;
import com.pixelclub.achievement.model.Achievement;
import com.pixelclub.achievement.model.ClaimState;
import com.pixelclub.achievement.model.PlayerProgress;
import com.pixelclub.achievement.ui.AchievementGui;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handles Brigadier command registration for /ach.
 * Enables RCON and console inspection to prove state.
 */
public class AchievementCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("ach")
                // /ach open [player]
                .then(Commands.literal("open")
                    .executes(AchievementCommands::openSelf)
                    .then(Commands.argument("player", EntityArgument.player())
                        .requires(s -> s.hasPermission(2))
                        .executes(AchievementCommands::openTarget)))

                // /ach progress <player> <id> <val>
                .then(Commands.literal("progress")
                    .requires(s -> s.hasPermission(2))
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("id", StringArgumentType.word())
                            .then(Commands.argument("val", IntegerArgumentType.integer(0))
                                .executes(AchievementCommands::setProgress)))))

                // /ach claim <player> <id>
                .then(Commands.literal("claim")
                    .requires(s -> s.hasPermission(2))
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("id", StringArgumentType.word())
                            .executes(AchievementCommands::claimTarget))))

                // /ach reset <player> [id|all]
                .then(Commands.literal("reset")
                    .requires(s -> s.hasPermission(2))
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("id", StringArgumentType.word())
                            .executes(AchievementCommands::resetTarget))))

                // /ach verify-sync <player>
                .then(Commands.literal("verify-sync")
                    .requires(s -> s.hasPermission(2))
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(AchievementCommands::verifySync)))
        );
    }

    private static int openSelf(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            AchievementGui.open(player, null);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Must be a player to execute this command."));
            return 0;
        }
    }

    private static int openTarget(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
            AchievementGui.open(player, null);
            ctx.getSource().sendSuccess(() -> Component.literal("Opened Quest Book for " + player.getName().getString()), false);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int setProgress(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
            String achId = StringArgumentType.getString(ctx, "id");
            int val = IntegerArgumentType.getInteger(ctx, "val");

            Achievement ach = PxcAchievementsMod.getInstance().getRegistry().getAchievement(achId);
            if (ach == null) {
                ctx.getSource().sendFailure(Component.literal("Achievement not found: " + achId));
                return 0;
            }

            boolean completed = PxcAchievementsMod.getInstance().getPlayerStore().setProgress(player.getUUID(), achId, val);
            ctx.getSource().sendSuccess(() -> Component.literal(String.format("Set %s progress for %s to %d/%d (Completed: %b)",
                    achId, player.getName().getString(), val, ach.getTarget(), completed)), false);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int claimTarget(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
            String achId = StringArgumentType.getString(ctx, "id");

            Achievement ach = PxcAchievementsMod.getInstance().getRegistry().getAchievement(achId);
            if (ach == null) {
                ctx.getSource().sendFailure(Component.literal("Achievement not found: " + achId));
                return 0;
            }

            PlayerProgress progress = PxcAchievementsMod.getInstance().getPlayerStore().getProgress(player.getUUID(), achId);
            if (progress.getState() == ClaimState.CLAIMED) {
                ctx.getSource().sendFailure(Component.literal("Already claimed: " + achId));
                return 0;
            }

            if (progress.getState() == ClaimState.LOCKED) {
                ctx.getSource().sendFailure(Component.literal("Cannot claim: achievement is still locked (" + progress.getProgress() + "/" + ach.getTarget() + ")"));
                return 0;
            }

            boolean claimed = PxcAchievementsMod.getInstance().getPlayerStore().claim(player, achId);
            if (claimed) {
                ctx.getSource().sendSuccess(() -> Component.literal("Successfully claimed rewards for: " + ach.getName()), false);
                return 1;
            } else {
                ctx.getSource().sendFailure(Component.literal("Failed to claim: duplicate or invalid state."));
                return 0;
            }
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int resetTarget(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
            String target = StringArgumentType.getString(ctx, "id");

            if ("all".equalsIgnoreCase(target)) {
                PxcAchievementsMod.getInstance().getPlayerStore().resetAll(player.getUUID());
                ctx.getSource().sendSuccess(() -> Component.literal("Reset ALL achievement progress for " + player.getName().getString()), false);
            } else {
                PxcAchievementsMod.getInstance().getPlayerStore().reset(player.getUUID(), target);
                ctx.getSource().sendSuccess(() -> Component.literal("Reset achievement " + target + " for " + player.getName().getString()), false);
            }
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int verifySync(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
            int synced = PxcAchievementsMod.getInstance().getPlayerStore().performRetroactiveSync(player);
            ctx.getSource().sendSuccess(() -> Component.literal(String.format("Retroactive sync verified for %s: %d achievements updated.",
                    player.getName().getString(), synced)), false);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }
}
