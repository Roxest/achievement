package com.pixelclub.achievement;

import com.pixelclub.achievement.config.AchievementConfig;
import com.pixelclub.achievement.reward.RewardDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class RewardDispatcherTest {

    @Test
    public void testResolveItemIdentifier() {
        // Poké balls
        assertEquals("cobblemon:poke_ball", RewardDispatcher.resolveItemIdentifier("pok_balls"));
        assertEquals("cobblemon:poke_ball", RewardDispatcher.resolveItemIdentifier("pok_ball"));
        assertEquals("cobblemon:poke_ball", RewardDispatcher.resolveItemIdentifier("poke_balls"));
        assertEquals("cobblemon:great_ball", RewardDispatcher.resolveItemIdentifier("great_balls"));
        assertEquals("cobblemon:ultra_ball", RewardDispatcher.resolveItemIdentifier("ultra_balls"));
        assertEquals("cobblemon:net_ball", RewardDispatcher.resolveItemIdentifier("net_balls"));
        assertEquals("cobblemon:master_ball", RewardDispatcher.resolveItemIdentifier("master_ball"));

        // Vanilla items
        assertEquals("minecraft:iron_ingot", RewardDispatcher.resolveItemIdentifier("iron_ingots"));
        assertEquals("minecraft:gold_ingot", RewardDispatcher.resolveItemIdentifier("gold_ingots"));
        assertEquals("minecraft:diamond", RewardDispatcher.resolveItemIdentifier("diamonds"));
        assertEquals("minecraft:emerald", RewardDispatcher.resolveItemIdentifier("emeralds"));
        assertEquals("minecraft:torch", RewardDispatcher.resolveItemIdentifier("torches"));
        assertEquals("minecraft:stone_bricks", RewardDispatcher.resolveItemIdentifier("stone_bricks"));
        assertEquals("minecraft:experience_bottle", RewardDispatcher.resolveItemIdentifier("bottles_o_enchanting"));
        assertEquals("minecraft:glass_bottle", RewardDispatcher.resolveItemIdentifier("empty_bottles"));
        assertEquals("minecraft:lapis_block", RewardDispatcher.resolveItemIdentifier("lapis_lazuli_blocks"));

        // Cobblemon specific items
        assertEquals("cobblemon:link_cable", RewardDispatcher.resolveItemIdentifier("linking_cords"));
        assertEquals("cobblemon:pasture", RewardDispatcher.resolveItemIdentifier("pasture_blocks"));
        assertEquals("cobblemon:rare_candy", RewardDispatcher.resolveItemIdentifier("rare_candies"));
        assertEquals("cobblemon:exp_candy_xl", RewardDispatcher.resolveItemIdentifier("exp_candies_xl"));
        assertEquals("cobblemon:potion", RewardDispatcher.resolveItemIdentifier("potions"));
        assertEquals("cobblemon:revive", RewardDispatcher.resolveItemIdentifier("revives"));

        // Already namespaced
        assertEquals("cobblemon:poke_ball", RewardDispatcher.resolveItemIdentifier("cobblemon:poke_ball"));
        assertEquals("minecraft:apple", RewardDispatcher.resolveItemIdentifier("minecraft:apple"));
    }

    @Test
    public void testConfigSaveAndLoad(@TempDir Path tempDir) {
        Path configFile = tempDir.resolve("config.json");
        AchievementConfig config = AchievementConfig.loadOrCreate(configFile);
        assertNotNull(config);
        assertEquals("money give {player} {amount}", config.getCurrencyCommand());
        assertEquals("eco give {player} {amount}", config.getCurrencyFallbackCommand());
        assertTrue(config.isNotifyPlayerOnPayout());

        // Modify and reload
        config.save(configFile);
        AchievementConfig reloaded = AchievementConfig.loadOrCreate(configFile);
        assertEquals(config.getCurrencyCommand(), reloaded.getCurrencyCommand());
    }
}
