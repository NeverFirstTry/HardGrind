package com.rene.noop;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

/**
 * The big steps of a survival run, taken from vanilla advancements. Each is stamped with the in-game day and
 * the player's play time the first time anyone gets it, so the run has a timeline you earned.
 */
final class Milestones {
    record Milestone(String advancement, String title) {
    }

    static final List<Milestone> ALL = List.of(
            new Milestone("minecraft:story/mine_stone", "Stone Age"),
            new Milestone("minecraft:story/smelt_iron", "Acquire Hardware"),
            new Milestone("minecraft:story/mine_diamond", "Diamonds!"),
            new Milestone("minecraft:story/enter_the_nether", "We Need to Go Deeper"),
            new Milestone("minecraft:nether/obtain_blaze_rod", "Into Fire"),
            new Milestone("minecraft:story/follow_ender_eye", "Eye Spy"),
            new Milestone("minecraft:story/enter_the_end", "The End?"),
            new Milestone("minecraft:end/kill_dragon", "Free the End"),
            new Milestone("minecraft:end/elytra", "Sky's the Limit"),
            new Milestone("minecraft:nether/obtain_ancient_debris", "Hidden in the Depths"),
            new Milestone("minecraft:nether/netherite_armor", "Cover Me in Debris"),
            new Milestone("minecraft:nether/create_full_beacon", "Beaconator"));

    private Milestones() {
    }

    /** Records milestones reached since the last check and announces them. */
    static void check(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty() || !Policy.loaded() || !Policy.shouldEnforceSurvival(server)) {
            return;
        }
        // The first look at a world only notes what was already done before HardGrind kept the log.
        boolean first = !Policy.milestonesStarted();
        for (Milestone milestone : ALL) {
            if (Policy.milestone(milestone.advancement()) != null) {
                continue;
            }
            for (ServerPlayer player : players) {
                if (AdvancementAccess.isDone(server, player, milestone.advancement())) {
                    long playTicks = player.getStats().getValue(Stats.CUSTOM, Stats.PLAY_TIME);
                    Policy.recordMilestone(milestone.advancement(), Policy.gameTime(server), playTicks, first);
                    if (!first) {
                        announce(server, milestone, player, playTicks);
                    }
                    break;
                }
            }
        }
        Policy.startMilestones();
    }

    private static void announce(MinecraftServer server, Milestone milestone, ServerPlayer player, long playTicks) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("✦ ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(milestone.title()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal("  " + Policy.dayLabel(Policy.gameTime(server)) + " · " + duration(playTicks) + " played")
                        .withStyle(ChatFormatting.GRAY)), false);
    }

    static String duration(long ticks) {
        long minutes = ticks / 1200;
        return minutes / 60 + "h " + String.format("%02d", minutes % 60) + "m";
    }
}
