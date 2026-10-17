package com.rene.noop;

import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

/** Gamerules for Minecraft 1.21.11 and 26.x (registry-based GameRule API). */
final class GameRuleAccess {
    private GameRuleAccess() {
    }

    /** The rule's current value as text, or null if this Minecraft version doesn't have the rule. */
    static String get(MinecraftServer server, LockedRule rule) {
        GameRule<?> gameRule = gameRule(rule);
        return gameRule == null ? null : asString(server.overworld().getGameRules(), gameRule);
    }

    /** Sets the rule from text as returned by {@link #get}, updating clients like /gamerule does. */
    static boolean set(MinecraftServer server, LockedRule rule, String text) {
        GameRule<?> gameRule = gameRule(rule);
        return gameRule != null && set(server, gameRule, text);
    }

    /** The rule's name in the /gamerule command, or null if this Minecraft version doesn't have it. */
    static String commandName(LockedRule rule) {
        GameRule<?> gameRule = gameRule(rule);
        return gameRule == null ? null : gameRule.id();
    }

    private static <T> String asString(GameRules rules, GameRule<T> rule) {
        return rule.serialize(rules.get(rule));
    }

    private static <T> boolean set(MinecraftServer server, GameRule<T> rule, String text) {
        Optional<T> value = rule.deserialize(text).result();
        value.ifPresent(v -> server.overworld().getGameRules().set(rule, v, server));
        return value.isPresent();
    }

    private static GameRule<?> gameRule(LockedRule rule) {
        return switch (rule) {
            case KEEP_INVENTORY -> GameRules.KEEP_INVENTORY;
            case DAYLIGHT_CYCLE -> GameRules.ADVANCE_TIME;
            case WEATHER_CYCLE -> GameRules.ADVANCE_WEATHER;
            case MOB_SPAWNING -> GameRules.SPAWN_MOBS;
            case MONSTER_SPAWNING -> GameRules.SPAWN_MONSTERS;
            case MOB_GRIEFING -> GameRules.MOB_GRIEFING;
            case NATURAL_REGENERATION -> GameRules.NATURAL_HEALTH_REGENERATION;
            case IMMEDIATE_RESPAWN -> GameRules.IMMEDIATE_RESPAWN;
            case RANDOM_TICK_SPEED -> GameRules.RANDOM_TICK_SPEED;
            case PLAYERS_SLEEPING_PERCENTAGE -> GameRules.PLAYERS_SLEEPING_PERCENTAGE;
            case FALL_DAMAGE -> GameRules.FALL_DAMAGE;
            case FIRE_DAMAGE -> GameRules.FIRE_DAMAGE;
            case DROWNING_DAMAGE -> GameRules.DROWNING_DAMAGE;
            case FREEZE_DAMAGE -> GameRules.FREEZE_DAMAGE;
        };
    }
}
