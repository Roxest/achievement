package com.pixelclub.achievement.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.pixelclub.achievement.PxcAchievementsMod;
import com.pixelclub.achievement.model.Achievement;
import com.pixelclub.achievement.model.AchievementCategory;
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

                // /ach reload
                .then(Commands.literal("reload")
                    .requires(s -> s.hasPermission(2))
                    .executes(AchievementCommands::reloadConfigAndSchema))

                // /ach category [list | enable <id> | disable <id>]
                .then(Commands.literal("category")
                    .requires(s -> s.hasPermission(2))
                    .then(Commands.literal("list")
                        .executes(AchievementCommands::listCategories))
                    .then(Commands.literal("enable")
                        .then(Commands.argument("category", StringArgumentType.word())
                            .suggests((ctx, builder) -> {
                                for (AchievementCategory cat : PxcAchievementsMod.getInstance().getRegistry().getCategories()) {
                                    builder.suggest(cat.getId());
                                }
                                return builder.buildFuture();
                            })
                            .executes(ctx -> setCategoryState(ctx, true))))
                    .then(Commands.literal("disable")
                        .then(Commands.argument("category", StringArgumentType.word())
                            .suggests((ctx, builder) -> {
                                for (AchievementCategory cat : PxcAchievementsMod.getInstance().getRegistry().getCategories()) {
                                    builder.suggest(cat.getId());
                                }
                                return builder.buildFuture();
                            })
                            .executes(ctx -> setCategoryState(ctx, false)))))
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

    private static int reloadConfigAndSchema(CommandContext<CommandSourceStack> ctx) {
        try {
            PxcAchievementsMod.getInstance().loadConfigAndSchema();
            int total = PxcAchievementsMod.getInstance().getRegistry().getTotalCount();
            int cats = PxcAchievementsMod.getInstance().getRegistry().getCategories().size();
            ctx.getSource().sendSuccess(() -> Component.literal(String.format("§a✔ Reloaded achievements and config! Loaded %d achievements across %d categories.",
                    total, cats)), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§cFailed to reload achievements: " + e.getMessage()));
            return 0;
        }
    }

    private static int listCategories(CommandContext<CommandSourceStack> ctx) {
        try {
            var registry = PxcAchievementsMod.getInstance().getRegistry();
            var config = PxcAchievementsMod.getInstance().getConfig();
            var categories = registry.getCategories();
            if (categories.isEmpty()) {
                ctx.getSource().sendSuccess(() -> Component.literal("No achievement categories registered."), false);
                return 1;
            }

            ctx.getSource().sendSuccess(() -> Component.literal("=== Achievement Categories ==="), false);
            for (AchievementCategory cat : categories) {
                boolean enabled = config.isCategoryEnabled(cat.getId());
                String status = enabled ? "§a[ENABLED]" : "§c[DISABLED]";
                int count = cat.getAchievements().size();
                ctx.getSource().sendSuccess(() -> Component.literal(
                        String.format("%s §e%s §7(%s) - %d achievements", status, cat.getName(), cat.getId(), count)), false);
            }
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int setCategoryState(CommandContext<CommandSourceStack> ctx, boolean enable) {
        try {
            String catId = StringArgumentType.getString(ctx, "category").toLowerCase();
            var registry = PxcAchievementsMod.getInstance().getRegistry();
            AchievementCategory cat = registry.getCategory(catId);
            if (cat == null) {
                ctx.getSource().sendFailure(Component.literal("Category not found: " + catId));
                return 0;
            }

            var config = PxcAchievementsMod.getInstance().getConfig();
            boolean currentlyEnabled = config.isCategoryEnabled(catId);
            if (currentlyEnabled == enable) {
                ctx.getSource().sendSuccess(() -> Component.literal(String.format("Category '%s' is already %s.",
                        cat.getName(), enable ? "enabled" : "disabled")), false);
                return 1;
            }

            config.setCategoryEnabled(catId, enable);
            boolean saved = config.save();
            String stateStr = enable ? "§aenabled" : "§cdisabled";
            ctx.getSource().sendSuccess(() -> Component.literal(String.format("Category '%s' (%s) is now %s.%s",
                    cat.getName(), catId, stateStr, saved ? " Saved to config.json." : " (Failed to save config file)")), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }
}
