package com.pixelclub.achievement.listener;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.evolution.variants.TradeEvolution;
import com.pixelclub.achievement.api.PxcAchievementApi;
import kotlin.Unit;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.UUID;

/**
 * Isolated bridge to Cobblemon events API.
 * Provides event bindings for Captures, Evolutions, Trades, Battles, Breeding, Fossils, and Apricorns.
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

            // Net Ball on Water or Bug type
            EmptyPokeBallEntity ballEntity = event.getPokeBallEntity();
            if (ballEntity != null && ballEntity.getPokeBall() != null) {
                String ball = ballEntity.getPokeBall().getName().getPath().toLowerCase();
                if ("net_ball".equals(ball)) {
                    String t1 = pokemon.getPrimaryType().getName().toLowerCase();
                    String t2 = pokemon.getSecondaryType() != null ? pokemon.getSecondaryType().getName().toLowerCase() : "";
                    if ("water".equals(t1) || "bug".equals(t1) || "water".equals(t2) || "bug".equals(t2)) {
                        PxcAchievementApi.setMax(player, "catching_right_tool_right_job", 1);
                    }
                }
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

                // Trade evolution verification
                if (event.getEvolution() instanceof TradeEvolution) {
                    PxcAchievementApi.increment(player, "evolving_long_distance", 1);
                }
            }
            return Unit.INSTANCE;
        });

        // 3. Trade Event
        CobblemonEvents.TRADE_EVENT_POST.subscribe(Priority.NORMAL, event -> {
            UUID uuid1 = event.getTradeParticipant1() != null ? event.getTradeParticipant1().getUuid() : null;
            UUID uuid2 = event.getTradeParticipant2() != null ? event.getTradeParticipant2().getUuid() : null;

            if (uuid1 != null) {
                PxcAchievementApi.increment(uuid1, "social_fair_trade", 1);
                PxcAchievementApi.increment(uuid1, "social_wheeler_dealer", 1);
                PxcAchievementApi.increment(uuid1, "social_master_trader", 1);
            }
            if (uuid2 != null) {
                PxcAchievementApi.increment(uuid2, "social_fair_trade", 1);
                PxcAchievementApi.increment(uuid2, "social_wheeler_dealer", 1);
                PxcAchievementApi.increment(uuid2, "social_master_trader", 1);
            }
            return Unit.INSTANCE;
        });

        // 4. Battle Victory Event
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL, event -> {
            if (event.getWasWildCapture()) return Unit.INSTANCE;

            for (BattleActor winnerActor : event.getWinners()) {
                for (UUID uuid : winnerActor.getPlayerUUIDs()) {
                    if (event.getBattle().isPvW()) {
                        PxcAchievementApi.increment(uuid, "battling_first_blood", 1);
                        PxcAchievementApi.increment(uuid, "battling_seasoned_fighter", 1);

                        // Untouchable check: zero fainted Pokémon on winner team
                        boolean anyFainted = winnerActor.getPokemonList().stream().anyMatch(bp -> bp.getHealth() <= 0);
                        if (!anyFainted) {
                            PxcAchievementApi.setMax(uuid, "battling_untouchable", 1);
                        }

                        // Level difference calculation
                        int maxWildLevel = event.getLosers().stream()
                                .flatMap(a -> a.getPokemonList().stream())
                                .mapToInt(bp -> bp.getEffectedPokemon().getLevel())
                                .max().orElse(0);

                        int minWinnerLevel = winnerActor.getPokemonList().stream()
                                .mapToInt(bp -> bp.getEffectedPokemon().getLevel())
                                .min().orElse(100);

                        int diff = maxWildLevel - minWinnerLevel;
                        if (diff >= 10) PxcAchievementApi.setMax(uuid, "battling_punching_up", 1);
                        if (diff >= 25) PxcAchievementApi.setMax(uuid, "battling_giant_slayer", 1);
                    } else if (event.getBattle().isPvP()) {
                        PxcAchievementApi.increment(uuid, "battling_en_garde", 1);
                        PxcAchievementApi.increment(uuid, "battling_arena_veteran", 1);
                        PxcAchievementApi.increment(uuid, "battling_hundred_duels", 1);

                        String battleType = event.getBattle().getFormat().getBattleType().getName().toLowerCase();
                        if (battleType.contains("doubles") || event.getBattle().getFormat().getBattleType().getSlotsPerActor() == 2) {
                            PxcAchievementApi.setMax(uuid, "battling_two_on_two", 1);
                        } else if (battleType.contains("triples") || event.getBattle().getFormat().getBattleType().getSlotsPerActor() == 3) {
                            PxcAchievementApi.setMax(uuid, "battling_triple_threat", 1);
                        }
                    }
                }
            }
            return Unit.INSTANCE;
        });

        // 5. Egg Hatching Event
        CobblemonEvents.HATCH_EGG_POST.subscribe(Priority.NORMAL, event -> {
            ServerPlayer player = event.getPlayer();
            Pokemon pokemon = event.getPokemon();
            if (player == null || pokemon == null) return Unit.INSTANCE;

            PxcAchievementApi.increment(player, "breeding_it_hatched", 1);
            PxcAchievementApi.increment(player, "breeding_egg_factory", 1);

            // Shiny hatch
            if (pokemon.getShiny()) {
                PxcAchievementApi.increment(player, "shinies_hatched_not_caught", 1);
            }

            // Perfect 6x31 IV hatch
            boolean isHundo = Arrays.asList(
                    com.cobblemon.mod.common.api.pokemon.stats.Stats.HP,
                    com.cobblemon.mod.common.api.pokemon.stats.Stats.ATTACK,
                    com.cobblemon.mod.common.api.pokemon.stats.Stats.DEFENCE,
                    com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_ATTACK,
                    com.cobblemon.mod.common.api.pokemon.stats.Stats.SPECIAL_DEFENCE,
                    com.cobblemon.mod.common.api.pokemon.stats.Stats.SPEED
            ).stream().allMatch(stat -> pokemon.getIvs().getOrDefault(stat) == 31);

            if (isHundo) {
                PxcAchievementApi.increment(player, "breeding_hundo", 1);
                PxcAchievementApi.increment(player, "training_perfect_hatch", 1);
                PxcAchievementApi.increment(player, "breeding_hundo_hatchery", 1);
            }

            return Unit.INSTANCE;
        });

        // 6. Fossil Revived Event
        CobblemonEvents.FOSSIL_REVIVED.subscribe(Priority.NORMAL, event -> {
            ServerPlayer player = event.getPlayer();
            if (player != null) {
                PxcAchievementApi.increment(player, "evolving_back_to_life", 1);
                PxcAchievementApi.increment(player, "evolving_jurassic_collector", 1);
            }
            return Unit.INSTANCE;
        });

        // 7. Apricorn Harvest Event
        CobblemonEvents.APRICORN_HARVESTED.subscribe(Priority.NORMAL, event -> {
            ServerPlayer player = event.getPlayer();
            if (player != null) {
                PxcAchievementApi.increment(player, "mining_apricorn_farmer", 1);
            }
            return Unit.INSTANCE;
        });
    }
}
