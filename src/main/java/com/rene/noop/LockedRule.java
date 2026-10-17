package com.rene.noop;

/**
 * Gamerules that HardGrind locks to the values they had when it first saw a world. The Minecraft side of each
 * rule lives in the version-specific {@code GameRuleAccess}.
 */
public enum LockedRule {
    KEEP_INVENTORY("keepInventory"),
    DAYLIGHT_CYCLE("doDaylightCycle"),
    WEATHER_CYCLE("doWeatherCycle"),
    MOB_SPAWNING("doMobSpawning"),
    // Only exists as a separate rule from 1.21.11 on.
    MONSTER_SPAWNING("spawnMonsters"),
    MOB_GRIEFING("mobGriefing"),
    NATURAL_REGENERATION("naturalRegeneration"),
    IMMEDIATE_RESPAWN("doImmediateRespawn"),
    RANDOM_TICK_SPEED("randomTickSpeed"),
    PLAYERS_SLEEPING_PERCENTAGE("playersSleepingPercentage"),
    FALL_DAMAGE("fallDamage"),
    FIRE_DAMAGE("fireDamage"),
    DROWNING_DAMAGE("drowningDamage"),
    FREEZE_DAMAGE("freezeDamage");

    /**
     * Name used in hardgrind.marker. These are the pre-1.21.11 rule names, kept fixed so a world's marker keeps
     * working when the world is moved to a newer Minecraft version that renamed the rules.
     */
    public final String key;

    LockedRule(String key) {
        this.key = key;
    }
}
