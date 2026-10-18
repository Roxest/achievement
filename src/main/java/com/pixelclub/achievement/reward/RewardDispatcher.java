package com.pixelclub.achievement.reward;

import com.pixelclub.achievement.model.Achievement;
import com.pixelclub.achievement.model.AchievementReward;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

/**
 * Executes reward disbursements on the main server thread upon achievement claim.
 * Supports Impactor economy currency deposit, console command templates, items, and crate keys.
 */
public final class RewardDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("PxcAchievements-Reward");
    private static Boolean impactorLoaded = null;

    private RewardDispatcher() {}

    private static boolean isImpactorAvailable() {
        if (impactorLoaded == null) {
            impactorLoaded = FabricLoader.getInstance().isModLoaded("impactor");
        }
        return impactorLoaded;
    }

    public static void dispatchRewards(ServerPlayer player, Achievement achievement) {
        if (player == null || achievement == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;

        // Ensure rewards run on the main server thread
        if (!server.isSameThread()) {
            server.execute(() -> dispatchRewards(player, achievement));
            return;
        }

        String playerName = player.getGameProfile().getName();
        LOGGER.info("[REWARD PAYOUT] Payout for player {} ({}) - Achievement {} ({})",
                playerName, player.getUUID(), achievement.getName(), achievement.getId());

        for (AchievementReward reward : achievement.getRewards()) {
            try {
                payoutIndividualReward(server, player, playerName, reward);
            } catch (Exception e) {
                LOGGER.error("[REWARD ERROR] Failed to payout reward for player {}: {}", playerName, reward.getDisplay(), e);
            }
        }
    }

    private static void payoutIndividualReward(MinecraftServer server, ServerPlayer player, String playerName, AchievementReward reward) {
        String type = reward.getType();

        if ("CURRENCY".equalsIgnoreCase(type)) {
            payoutCurrency(server, player, playerName, reward);
        } else if ("COMMAND".equalsIgnoreCase(type) || reward.getCommand() != null) {
            String cmd = reward.getCommand();
            if (cmd != null && !cmd.isEmpty()) {
                executeCommand(server, cmd.replace("{player}", playerName));
            }
        } else if ("ITEM".equalsIgnoreCase(type)) {
            payoutItem(server, playerName, reward);
        } else if ("CRATE_KEY".equalsIgnoreCase(type)) {
            payoutCrateKey(server, playerName, reward);
        } else {
            if (reward.getCommand() != null && !reward.getCommand().isEmpty()) {
                executeCommand(server, reward.getCommand().replace("{player}", playerName));
            }
        }
    }

    private static void payoutCurrency(MinecraftServer server, ServerPlayer player, String playerName, AchievementReward reward) {
        int amount = reward.getAmount();
        if (amount <= 0) return;

        boolean deposited = false;
        if (isImpactorAvailable()) {
            try {
                net.impactdev.impactor.api.economy.EconomyService service = net.impactdev.impactor.api.economy.EconomyService.instance();
                if (service != null) {
                    CompletableFuture<net.impactdev.impactor.api.economy.accounts.Account> future = service.account(player.getUUID());
                    net.impactdev.impactor.api.economy.accounts.Account acc = future != null ? future.join() : null;
                    if (acc != null) {
                        net.impactdev.impactor.api.economy.transactions.EconomyTransaction tx = acc.deposit(BigDecimal.valueOf(amount));
                        if (tx != null && tx.successful()) {
                            deposited = true;
                            LOGGER.info("[REWARD PAYOUT/IMPACTOR] Deposited {} {} to {}", amount, reward.getCurrency(), playerName);
                        }
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[REWARD WARN] Impactor deposit failed for {}, falling back to console command", playerName, t);
            }
        }

        if (!deposited) {
            String cmd = "eco give " + playerName + " " + amount;
            executeCommand(server, cmd);
            LOGGER.info("[REWARD PAYOUT/COMMAND] Executed fallback currency command: '{}'", cmd);
        }
    }

    private static void payoutItem(MinecraftServer server, String playerName, AchievementReward reward) {
        if (reward.getCommand() != null && !reward.getCommand().isEmpty()) {
            executeCommand(server, reward.getCommand().replace("{player}", playerName));
            return;
        }

        String id = reward.getId();
        int amount = Math.max(1, reward.getAmount());
        if (id == null || id.isEmpty()) return;

        String itemTarget = id;
        if (!itemTarget.contains(":")) {
            if (itemTarget.contains("ball") || itemTarget.contains("apricorn") || itemTarget.contains("candy") || itemTarget.contains("mint")) {
                itemTarget = "cobblemon:" + itemTarget;
            } else {
                itemTarget = "minecraft:" + itemTarget;
            }
        }

        String cmd = "give " + playerName + " " + itemTarget + " " + amount;
        executeCommand(server, cmd);
        LOGGER.info("[REWARD PAYOUT/ITEM] Executed item give command: '{}'", cmd);
    }

    private static void payoutCrateKey(MinecraftServer server, String playerName, AchievementReward reward) {
        String key = reward.getId() != null ? reward.getId() : "common";
        int amount = Math.max(1, reward.getAmount());
        String cmd = "crate key give " + playerName + " " + key + " " + amount;
        executeCommand(server, cmd);
        LOGGER.info("[REWARD PAYOUT/KEY] Executed crate key command: '{}'", cmd);
    }

    private static void executeCommand(MinecraftServer server, String command) {
        String trimmed = command.startsWith("/") ? command.substring(1) : command;
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), trimmed);
    }
}
