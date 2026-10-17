package com.rene.noop;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;

/** Gamerules for Minecraft 1.21.9 - 1.21.10 (the pre-registry GameRules.Key API). */
final class GameRuleAccess {
    private GameRuleAccess() {
    }

    /** The rule's current value as text, or null if this Minecraft version doesn't have the rule. */
    static String get(MinecraftServer server, LockedRule rule) {
        GameRules.Key<?> key = key(rule);
        return key == null ? null : server.getGameRules().getRule(key).serialize();
    }

    /** Sets the rule from text as returned by {@link #get}, updating clients like /gamerule does. */
    static boolean set(MinecraftServer server, LockedRule rule, String text) {
        GameRules.Key<?> key = key(rule);
        if (key == null) {
            return false;
        }
        GameRules.Value<?> value = server.getGameRules().getRule(key);
        if (value instanceof GameRules.BooleanValue booleanValue && (text.equals("true") || text.equals("false"))) {
            booleanValue.set(Boolean.parseBoolean(text), server);
            return true;
        }
        if (value instanceof GameRules.IntegerValue integerValue) {
            try {
                integerValue.set(Integer.parseInt(text), server);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /** The rule's name in the /gamerule command, or null if this Minecraft version doesn't have it. */
    static String commandName(LockedRule rule) {
        GameRules.Key<?> key = key(rule);
        return key == null ? null : key.getId();
    }

    private static GameRules.Key<?> key(LockedRule rule) {
        return switch (rule) {
            case KEEP_INVENTORY -> GameRules.RULE_KEEPINVENTORY;
            case DAYLIGHT_CYCLE -> GameRules.RULE_DAYLIGHT;
            case WEATHER_CYCLE -> GameRules.RULE_WEATHER_CYCLE;
            case MOB_SPAWNING -> GameRules.RULE_DOMOBSPAWNING;
            case MONSTER_SPAWNING -> null;
            case MOB_GRIEFING -> GameRules.RULE_MOBGRIEFING;
            case NATURAL_REGENERATION -> GameRules.RULE_NATURAL_REGENERATION;
            case IMMEDIATE_RESPAWN -> GameRules.RULE_DO_IMMEDIATE_RESPAWN;
            case RANDOM_TICK_SPEED -> GameRules.RULE_RANDOMTICKING;
            case PLAYERS_SLEEPING_PERCENTAGE -> GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE;
            case FALL_DAMAGE -> GameRules.RULE_FALL_DAMAGE;
            case FIRE_DAMAGE -> GameRules.RULE_FIRE_DAMAGE;
            case DROWNING_DAMAGE -> GameRules.RULE_DROWNING_DAMAGE;
            case FREEZE_DAMAGE -> GameRules.RULE_FREEZE_DAMAGE;
        };
    }
}
