package com.pixelclub.achievement.listener;

import com.pixelclub.achievement.PxcAchievementsMod;
import com.pixelclub.achievement.api.PxcAchievementApi;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles built-in Fabric and Vanilla event listeners.
 */
public class BuiltInListeners {
    private static final Logger LOGGER = LoggerFactory.getLogger("PxcAchievements-Listeners");

    public static void register() {
        // 1. Join Event: Triggers retroactive stat sync & welcome check
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            PxcAchievementsMod.getInstance().getPlayerStore().performRetroactiveSync(player);
        });

        // 2. Block Break Event: Mining & Gathering progression
        PlayerBlockBreakEvents.AFTER.register(BuiltInListeners::onBlockBreak);

        // 3. Optional Cobblemon Hook Registration
        registerCobblemonHooks();
    }

    private static void onBlockBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) return;

        // Diamond mining
        if (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)) {
            PxcAchievementApi.increment(serverPlayer, "mining_diamond_in_the_rough", 1);
            PxcAchievementApi.increment(serverPlayer, "mining_diamond_tycoon", 1);
        }
        // Netherite / Ancient debris
        else if (state.is(Blocks.ANCIENT_DEBRIS)) {
            PxcAchievementApi.increment(serverPlayer, "mining_netherite", 1);
            PxcAchievementApi.increment(serverPlayer, "mining_master_of_the_depths", 1);
        }
        // General Stone
        else if (state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE)) {
            PxcAchievementApi.increment(serverPlayer, "mining_quarry_worker", 1);
            PxcAchievementApi.increment(serverPlayer, "mining_strip_miner", 1);
        }

        // Deepslate Layer Check
        if (pos.getY() < 0) {
            PxcAchievementApi.setMax(serverPlayer, "mining_into_the_deep", 1);
        }
    }

    private static void registerCobblemonHooks() {
        try {
            Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents");
            CobblemonBridge.register();
            LOGGER.info("Successfully hooked into Cobblemon Events API.");
        } catch (ClassNotFoundException e) {
            LOGGER.info("Cobblemon not detected at runtime; built-in Cobblemon listeners disabled.");
        } catch (Throwable t) {
            LOGGER.warn("Could not bind Cobblemon listeners", t);
        }
    }
}
