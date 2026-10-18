package com.pixelclub.achievement.listener;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.pixelclub.achievement.api.PxcAchievementApi;
import kotlin.Unit;
import net.minecraft.server.level.ServerPlayer;

/**
 * Isolated bridge to Cobblemon events API.
 */
public class CobblemonBridge {

    public static void register() {
        // 1. Capture Event
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.NORMAL, event -> {
            ServerPlayer player = event.getPlayer();
            Pokemon pokemon = event.getPokemon();
            if (player == null || pokemon == null) return Unit.INSTANCE;

            // First throw & total
            PxcAchievementApi.increment(player, "catching_gotcha", 1);
            PxcAchievementApi.increment(player, "catching_seasoned_catcher", 1);
            PxcAchievementApi.increment(player, "catching_true_dedication", 1);

            // Low health catch (<25% HP)
            if (pokemon.getCurrentHealth() <= (pokemon.getMaxHealth() * 0.25f)) {
                PxcAchievementApi.setMax(player, "catching_soften_the_blow", 1);
            }

            // Status afflicted catch
            if (pokemon.getStatus() != null) {
                PxcAchievementApi.setMax(player, "catching_sleep_on_it", 1);
            }

            // Shiny catch
            if (pokemon.getShiny()) {
                PxcAchievementApi.increment(player, "shinies_it_sparkled", 1);
            }

            // Legendary catch
            if (pokemon.isLegendary()) {
                PxcAchievementApi.increment(player, "shinies_the_stuff_of_legends", 1);
            }

            return Unit.INSTANCE;
        });

        // 2. Evolution Complete Event
        CobblemonEvents.EVOLUTION_COMPLETE.subscribe(Priority.NORMAL, event -> {
            Pokemon pokemon = event.getPokemon();
            ServerPlayer player = pokemon.getOwnerPlayer();
            if (player != null) {
                PxcAchievementApi.increment(player, "evolving_growing_up", 1);
                PxcAchievementApi.increment(player, "evolving_evolutionary", 1);
            }
            return Unit.INSTANCE;
        });

        // 3. Trade Event
        CobblemonEvents.TRADE_EVENT_POST.subscribe(Priority.NORMAL, event -> {
            java.util.UUID uuid1 = event.getTradeParticipant1() != null ? event.getTradeParticipant1().getUuid() : null;
            java.util.UUID uuid2 = event.getTradeParticipant2() != null ? event.getTradeParticipant2().getUuid() : null;
            if (uuid1 != null) PxcAchievementApi.increment(uuid1, "evolving_long_distance", 1);
            if (uuid2 != null) PxcAchievementApi.increment(uuid2, "evolving_long_distance", 1);
            return Unit.INSTANCE;
        });
    }
}
