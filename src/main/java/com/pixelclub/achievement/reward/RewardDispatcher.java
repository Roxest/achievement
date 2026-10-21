package com.pixelclub.achievement.reward;

import com.pixelclub.achievement.PxcAchievementsMod;
import com.pixelclub.achievement.config.AchievementConfig;
import com.pixelclub.achievement.model.Achievement;
import com.pixelclub.achievement.model.AchievementReward;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

        if ("TITLE".equalsIgnoreCase(type)) {
            payoutTitle(server, player, playerName, reward);
        } else if ("CURRENCY".equalsIgnoreCase(type)) {
            payoutCurrency(server, player, playerName, reward);
        } else if ("COMMAND".equalsIgnoreCase(type) || reward.getCommand() != null) {
            String cmd = reward.getCommand();
            if (cmd != null && !cmd.isEmpty()) {
                executeCommand(server, cmd.replace("{player}", playerName));
            }
        } else if ("ITEM".equalsIgnoreCase(type)) {
            payoutItem(server, player, playerName, reward);
        } else if ("CRATE_KEY".equalsIgnoreCase(type)) {
            payoutCrateKey(server, player, playerName, reward);
        } else {
            if (reward.getCommand() != null && !reward.getCommand().isEmpty()) {
                executeCommand(server, reward.getCommand().replace("{player}", playerName));
            }
        }
    }

    private static AchievementConfig getConfig() {
        PxcAchievementsMod mod = PxcAchievementsMod.getInstance();
        return mod != null && mod.getConfig() != null ? mod.getConfig() : new AchievementConfig();
    }

    private static void payoutCurrency(MinecraftServer server, ServerPlayer player, String playerName, AchievementReward reward) {
        int amount = reward.getAmount();
        if (amount <= 0) return;

        // Route RAID_COIN separately: never deposit into primary PokéCoin balance
        if ("RAID_COIN".equalsIgnoreCase(reward.getCurrency())) {
            payoutRaidCoin(server, player, playerName, amount);
            return;
        }

        if (isImpactorAvailable()) {
            try {
                net.impactdev.impactor.api.economy.EconomyService service = net.impactdev.impactor.api.economy.EconomyService.instance();
                if (service != null) {
                    CompletableFuture<net.impactdev.impactor.api.economy.accounts.Account> future = service.account(player.getUUID());
                    if (future != null) {
                        future.orTimeout(3, TimeUnit.SECONDS).whenComplete((acc, error) -> {
                            if (error != null || acc == null) {
                                if (error != null) {
                                    LOGGER.warn("[REWARD WARN] Impactor account lookup failed for {}, falling back to console command", playerName, error);
                                }
                                executeFallbackCurrencyCommand(server, player, playerName, amount, reward.getCurrency());
                                return;
                            }
                            try {
                                net.impactdev.impactor.api.economy.transactions.EconomyTransaction tx = acc.deposit(BigDecimal.valueOf(amount));
                                if (tx != null && tx.successful()) {
                                    LOGGER.info("[REWARD PAYOUT/IMPACTOR] Deposited {} {} to {}", amount, reward.getCurrency(), playerName);
                                    notifyCurrency(player, amount, reward.getCurrency());
                                } else {
                                    LOGGER.warn("[REWARD WARN] Impactor deposit unsuccessful for {}, falling back to console command", playerName);
                                    executeFallbackCurrencyCommand(server, player, playerName, amount, reward.getCurrency());
                                }
                            } catch (Throwable t) {
                                LOGGER.warn("[REWARD WARN] Impactor deposit failed for {}, falling back to console command", playerName, t);
                                executeFallbackCurrencyCommand(server, player, playerName, amount, reward.getCurrency());
                            }
                        });
                        return;
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[REWARD WARN] Impactor service access failed for {}, falling back to console command", playerName, t);
            }
        }

        executeFallbackCurrencyCommand(server, player, playerName, amount, reward.getCurrency());
    }

    private static void executeFallbackCurrencyCommand(MinecraftServer server, ServerPlayer player, String playerName, int amount, String currency) {
        Runnable task = () -> {
            AchievementConfig cfg = getConfig();
            String template = cfg.getCurrencyCommand();
            String cmd = template.replace("{player}", playerName)
                    .replace("{amount}", String.valueOf(amount))
                    .replace("{currency}", currency != null ? currency : "POKECOIN");
            try {
                executeCommand(server, cmd);
                LOGGER.info("[REWARD PAYOUT/COMMAND] Executed primary currency command: '{}'", cmd);
                notifyCurrency(player, amount, currency);
            } catch (Exception e) {
                LOGGER.warn("[REWARD WARN] Primary currency command '{}' failed, trying fallback: {}", cmd, e.getMessage());
                String fallbackTemplate = cfg.getCurrencyFallbackCommand();
                String fallbackCmd = fallbackTemplate.replace("{player}", playerName)
                        .replace("{amount}", String.valueOf(amount))
                        .replace("{currency}", currency != null ? currency : "POKECOIN");
                executeCommand(server, fallbackCmd);
                LOGGER.info("[REWARD PAYOUT/COMMAND] Executed fallback currency command: '{}'", fallbackCmd);
                notifyCurrency(player, amount, currency);
            }
        };
        if (server.isSameThread()) {
            task.run();
        } else {
            server.execute(task);
        }
    }

    private static void notifyCurrency(ServerPlayer player, int amount, String currency) {
        if (player != null && getConfig().isNotifyPlayerOnPayout()) {
            String currName = "RAID_COIN".equalsIgnoreCase(currency) ? "Raid Coins" : "PokéCoins";
            player.sendSystemMessage(Component.literal("§8[§bQuest§8] §a✔ Received: §6" + amount + " " + currName));
        }
    }

    private static void payoutRaidCoin(MinecraftServer server, ServerPlayer player, String playerName, int amount) {
        Runnable task = () -> {
            String template = getConfig().getRaidCoinCommand();
            String cmd = template.replace("{player}", playerName)
                    .replace("{amount}", String.valueOf(amount));
            executeCommand(server, cmd);
            LOGGER.info("[REWARD PAYOUT/RAID_COIN] Executed raid coin command: '{}'", cmd);
            notifyCurrency(player, amount, "RAID_COIN");
        };
        if (server.isSameThread()) {
            task.run();
        } else {
            server.execute(task);
        }
    }

    private static void payoutTitle(MinecraftServer server, ServerPlayer player, String playerName, AchievementReward reward) {
        if (reward.getCommand() != null && !reward.getCommand().isEmpty()) {
            executeCommand(server, reward.getCommand().replace("{player}", playerName));
            return;
        }
        String title = reward.getId() != null ? reward.getId() : reward.getDisplay();
        String template = getConfig().getTitleCommand();
        String cmd = template.replace("{player}", playerName)
                .replace("{title}", title != null ? title : "");
        executeCommand(server, cmd);
        LOGGER.info("[REWARD PAYOUT/TITLE] Granted title '{}' to {}", title, playerName);
        if (player != null && getConfig().isNotifyPlayerOnPayout()) {
            player.sendSystemMessage(Component.literal("§8[§bQuest§8] §a✔ Title unlocked: §e[" + title + "]"));
        }
    }

    private static void payoutItem(MinecraftServer server, ServerPlayer player, String playerName, AchievementReward reward) {
        if (reward.getCommand() != null && !reward.getCommand().isEmpty()) {
            executeCommand(server, reward.getCommand().replace("{player}", playerName));
            return;
        }

        String rawId = reward.getId();
        int amount = Math.max(1, reward.getAmount());
        if (rawId == null || rawId.isEmpty()) return;

        String itemTarget = resolveItemIdentifier(rawId);
        String template = getConfig().getItemGiveCommand();
        String cmd = template.replace("{player}", playerName)
                .replace("{item}", itemTarget)
                .replace("{amount}", String.valueOf(amount));

        executeCommand(server, cmd);
        LOGGER.info("[REWARD PAYOUT/ITEM] Executed item command: '{}'", cmd);

        if (player != null && getConfig().isNotifyPlayerOnPayout()) {
            String display = reward.getDisplay() != null && !reward.getDisplay().isEmpty()
                    ? reward.getDisplay()
                    : (amount + "x " + itemTarget);
            player.sendSystemMessage(Component.literal("§8[§bQuest§8] §a✔ Received: §f" + display));
        }
    }

    private static void payoutCrateKey(MinecraftServer server, ServerPlayer player, String playerName, AchievementReward reward) {
        String key = reward.getId() != null ? reward.getId() : "common";
        int amount = Math.max(1, reward.getAmount());
        String template = getConfig().getCrateKeyCommand();
        String cmd = template.replace("{player}", playerName)
                .replace("{key}", key)
                .replace("{amount}", String.valueOf(amount));
        executeCommand(server, cmd);
        LOGGER.info("[REWARD PAYOUT/KEY] Executed crate key command: '{}'", cmd);

        if (player != null && getConfig().isNotifyPlayerOnPayout()) {
            String display = reward.getDisplay() != null && !reward.getDisplay().isEmpty()
                    ? reward.getDisplay()
                    : (amount + "x " + key + " Crate Key");
            player.sendSystemMessage(Component.literal("§8[§bQuest§8] §a✔ Received: §e" + display));
        }
    }

    /**
     * Resolves raw/transcribed item identifiers to valid Minecraft or Cobblemon registry IDs.
     * Strips plurals, maps Cobblemon ball names, and attaches appropriate namespaces.
     */
    public static String resolveItemIdentifier(String rawId) {
        if (rawId == null || rawId.trim().isEmpty()) return "minecraft:air";
        String id = rawId.trim().toLowerCase();

        // 1. If already namespaced, return directly (cleaning plural if needed)
        if (id.contains(":")) {
            if (id.endsWith("_balls") || id.endsWith("_ingots")) {
                return id.substring(0, id.length() - 1);
            }
            return id;
        }

        // 2. Direct custom mappings for Cobblemon and Minecraft items
        switch (id) {
            case "pok_balls":
            case "pok_ball":
            case "poke_balls":
            case "poke_ball":
                return "cobblemon:poke_ball";
            case "linking_cords":
            case "linking_cord":
                return "cobblemon:link_cable";
            case "pasture_blocks":
            case "pasture_block":
                return "cobblemon:pasture";
            case "bottles_o_enchanting":
                return "minecraft:experience_bottle";
            case "empty_bottles":
                return "minecraft:glass_bottle";
            case "lapis_lazuli_blocks":
                return "minecraft:lapis_block";
            case "diamond_block":
                return "minecraft:diamond_block";
            case "diamonds":
                return "minecraft:diamond";
            case "emeralds":
                return "minecraft:emerald";
            case "ender_pearls":
                return "minecraft:ender_pearl";
            case "torches":
                return "minecraft:torch";
            case "stone_bricks":
                return "minecraft:stone_bricks";
            case "shulker_shell":
                return "minecraft:shulker_shell";
            case "name_tag":
                return "minecraft:name_tag";
            case "bread":
                return "minecraft:bread";
            case "compass":
                return "minecraft:compass";
            case "golden_shovel":
                return "minecraft:golden_shovel";
            case "magma_cream":
                return "minecraft:magma_cream";
            case "netherite_ingot":
                return "minecraft:netherite_ingot";
            case "netherite_scrap":
                return "minecraft:netherite_scrap";
            case "rare_candies":
                return "cobblemon:rare_candy";
            case "exp_candies_xl":
                return "cobblemon:exp_candy_xl";
            case "exp_candies_l":
                return "cobblemon:exp_candy_l";
            case "exp_candies_m":
                return "cobblemon:exp_candy_m";
            case "exp_candies_s":
                return "cobblemon:exp_candy_s";
            case "exp_candies_xs":
                return "cobblemon:exp_candy_xs";
            case "potions":
                return "cobblemon:potion";
            case "super_potions":
                return "cobblemon:super_potion";
            case "hyper_potions":
                return "cobblemon:hyper_potion";
            case "max_potions":
                return "cobblemon:max_potion";
            case "revives":
                return "cobblemon:revive";
            case "max_revives":
                return "cobblemon:max_revive";
        }

        // 3. Poké balls pluralization (e.g. great_balls -> cobblemon:great_ball)
        if (id.endsWith("_balls")) {
            return "cobblemon:" + id.substring(0, id.length() - 1);
        }
        if (id.endsWith("_ball")) {
            return "cobblemon:" + id;
        }

        // 4. Ingots pluralization (e.g. iron_ingots -> minecraft:iron_ingot)
        if (id.endsWith("_ingots")) {
            return "minecraft:" + id.substring(0, id.length() - 1);
        }

        // 5. Cobblemon domain prefixes
        if (id.contains("apricorn") || id.contains("candy") || id.contains("mint")
                || id.contains("mochi") || id.contains("fossil") || id.contains("stone")
                || id.contains("scarf") || id.contains("band") || id.contains("lens")
                || id.contains("herb") || id.contains("bell") || id.contains("scale")
                || id.contains("knot") || id.contains("coat") || id.contains("patch")
                || id.contains("capsule") || id.contains("bottle_cap") || id.contains("gem")
                || id.contains("mulch") || id.contains("potion") || id.contains("revive")
                || id.contains("leftovers") || id.contains("sash")) {
            String singular = id.endsWith("s") && !id.endsWith("glass") && !id.endsWith("grass")
                    ? id.substring(0, id.length() - 1) : id;
            return "cobblemon:" + singular;
        }

        // 6. Vanilla fallback with de-pluralization
        String singular = id.endsWith("s") && !id.endsWith("glass") && !id.endsWith("grass")
                ? id.substring(0, id.length() - 1) : id;
        return "minecraft:" + singular;
    }

    private static void executeCommand(MinecraftServer server, String command) {
        String trimmed = command.startsWith("/") ? command.substring(1) : command;
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), trimmed);
    }
}
