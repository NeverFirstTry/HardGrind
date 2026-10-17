package com.rene.noop;

import com.mojang.brigadier.CommandDispatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.Difficulty;

/** {@code /hardgrind}: status, the run log, and arming ironman. Anyone can run it. */
final class HardGrindCommand {
    private HardGrindCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("hardgrind")
                .executes(context -> send(context.getSource(), statusLines(context.getSource().getServer())))
                .then(Commands.literal("log")
                        .executes(context -> send(context.getSource(), logLines(context.getSource().getServer(), context.getSource().getPlayer()))))
                .then(Commands.literal("lock")
                        .executes(context -> {
                            if (Policy.lockActive()) {
                                return send(context.getSource(), List.of(Component.literal("This world is already locked.").withStyle(ChatFormatting.GREEN)));
                            }
                            return send(context.getSource(), List.of(
                                    Component.literal("Locking makes HardGrind stick to this world.").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                                    Component.literal("A small datapack goes into the world, so it won't open without the HardGrind mod "
                                            + "anymore, the difficulty stays locked even without the mod, restored backups are "
                                            + "caught, and cheat mods keep it from loading. /hardgrind unlock confirm takes it "
                                            + "out again, but leaves a note in the run log.").withStyle(ChatFormatting.GRAY),
                                    Component.literal("Type /hardgrind lock confirm to lock it.").withStyle(ChatFormatting.GOLD)));
                        })
                        .then(Commands.literal("confirm").executes(context -> {
                            CommandSourceStack source = context.getSource();
                            if (Policy.lockActive()) {
                                return send(source, List.of(Component.literal("This world is already locked.").withStyle(ChatFormatting.GREEN)));
                            }
                            if (Policy.buildWorld()) {
                                source.sendFailure(Component.literal("Build worlds can't be locked."));
                                return 0;
                            }
                            if (!Policy.lock(source.getServer())) {
                                source.sendFailure(Component.literal("Only worlds whose default game mode is Survival can be locked."));
                                return 0;
                            }
                            FairModeSync.sendAll(source.getServer());
                            source.getServer().getPlayerList().broadcastSystemMessage(
                                    Component.literal("[HardGrind] World locked. It now only opens with HardGrind installed.").withStyle(ChatFormatting.GOLD), false);
                            return 1;
                        })))
                .then(Commands.literal("unlock")
                        .executes(context -> send(context.getSource(), List.of(
                                Component.literal("Unlocking takes HardGrind's datapack out of this world, so it opens without the mod again.").withStyle(ChatFormatting.GRAY),
                                Component.literal("It leaves a permanent note in the run log. Ironman worlds can't be unlocked.").withStyle(ChatFormatting.GRAY),
                                Component.literal("Type /hardgrind unlock confirm to unlock it.").withStyle(ChatFormatting.GOLD))))
                        .then(Commands.literal("confirm").executes(context -> {
                            CommandSourceStack source = context.getSource();
                            if (Policy.ironman() || Policy.dead()) {
                                source.sendFailure(Component.literal("Ironman worlds can't be unlocked."));
                                return 0;
                            }
                            if (!Policy.unlock(source.getServer())) {
                                source.sendFailure(Component.literal("This world isn't locked."));
                                return 0;
                            }
                            FairModeSync.sendAll(source.getServer());
                            source.getServer().getPlayerList().broadcastSystemMessage(
                                    Component.literal("[HardGrind] World unlocked. It opens without HardGrind again.").withStyle(ChatFormatting.GRAY), false);
                            if (Config.get().autoLock) {
                                source.sendSystemMessage(Component.literal("autoLock is on in config/hardgrind.json, so it will be locked again the next time it loads.")
                                        .withStyle(ChatFormatting.YELLOW));
                            }
                            return 1;
                        })))
                .then(Commands.literal("build")
                        .executes(context -> {
                            if (Policy.buildWorld()) {
                                return send(context.getSource(), List.of(Component.literal("This is already a build world.").withStyle(ChatFormatting.AQUA)));
                            }
                            return send(context.getSource(), List.of(
                                    Component.literal("A build world is for building, not for a run.").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                                    Component.literal("HardGrind steps aside for good: /gamemode, F3+F4 and the other commands work again, "
                                            + "and Survival, the difficulty and the gamerules aren't locked anymore. It can never be locked "
                                            + "or armed, and the run log keeps a note. A locked world has to be unlocked first; ironman "
                                            + "worlds can't become build worlds.").withStyle(ChatFormatting.GRAY),
                                    Component.literal("Type /hardgrind build confirm to make this a build world.").withStyle(ChatFormatting.AQUA)));
                        })
                        .then(Commands.literal("confirm").executes(context -> {
                            CommandSourceStack source = context.getSource();
                            if (Policy.buildWorld()) {
                                return send(source, List.of(Component.literal("This is already a build world.").withStyle(ChatFormatting.AQUA)));
                            }
                            if (Policy.ironman() || Policy.dead()) {
                                source.sendFailure(Component.literal("Ironman worlds can't become build worlds."));
                                return 0;
                            }
                            if (!Policy.makeBuildWorld(source.getServer())) {
                                source.sendFailure(Component.literal("Locked worlds can't become build worlds. Use /hardgrind unlock confirm first."));
                                return 0;
                            }
                            // Ops get their commands and F3+F4 back right away, without rejoining.
                            for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
                                source.getServer().getPlayerList().sendPlayerPermissionLevel(player);
                            }
                            source.getServer().getPlayerList().broadcastSystemMessage(Component.literal(
                                    "[HardGrind] This is a build world now: commands and game modes work, and nothing is locked.")
                                    .withStyle(ChatFormatting.AQUA), false);
                            return 1;
                        })))
                .then(Commands.literal("revive")
                        .executes(context -> send(context.getSource(), List.of(
                                Component.literal("A second chance after dying in a hardcore world.").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                                Component.literal("Hardcore goes off for this world, for good. Everyone who died respawns at their bed "
                                        + "or the world spawn with an empty inventory, like a normal death, and the run log says so "
                                        + "forever. Only after dying, and never in ironman worlds.").withStyle(ChatFormatting.GRAY),
                                Component.literal("Type /hardgrind revive confirm to do it.").withStyle(ChatFormatting.GOLD))))
                        .then(Commands.literal("confirm").executes(context -> {
                            CommandSourceStack source = context.getSource();
                            String refusal = Revive.refusal(source.getServer(), source.getPlayer());
                            if (refusal != null) {
                                source.sendFailure(Component.literal(refusal));
                                return 0;
                            }
                            Revive.revive(source.getServer(), source.getPlayer());
                            return 1;
                        })))
                .then(Commands.literal("ironman")
                        .executes(context -> {
                            if (Policy.ironman()) {
                                return send(context.getSource(), List.of(Component.literal("Ironman is already armed for this world.").withStyle(ChatFormatting.RED)));
                            }
                            return send(context.getSource(), List.of(
                                    Component.literal("Ironman makes cheating fatal for this world.").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                                    Component.literal("It locks the world (it won't open without HardGrind) for good. If HardGrind ever catches "
                                            + "Creative/Spectator, a changed gamerule or difficulty, the lock datapack deleted, disabled or "
                                            + "edited, or the world rolled back to a backup, it dies and can never be played again. There is "
                                            + "no way to disarm or unlock it.").withStyle(ChatFormatting.GRAY),
                                    Component.literal("Type /hardgrind ironman confirm to arm it.").withStyle(ChatFormatting.GOLD)));
                        })
                        .then(Commands.literal("confirm").executes(context -> {
                            CommandSourceStack source = context.getSource();
                            if (Policy.ironman()) {
                                return send(source, List.of(Component.literal("Ironman is already armed for this world.").withStyle(ChatFormatting.RED)));
                            }
                            if (Policy.buildWorld()) {
                                source.sendFailure(Component.literal("Build worlds can't be armed."));
                                return 0;
                            }
                            if (!Policy.armIronman(source.getServer())) {
                                source.sendFailure(Component.literal("Only worlds whose default game mode is Survival can be armed."));
                                return 0;
                            }
                            // armIronman locks the world first if it wasn't locked yet.
                            FairModeSync.sendAll(source.getServer());
                            source.getServer().getPlayerList().broadcastSystemMessage(
                                    Component.literal("[HardGrind] Ironman armed. No way back.").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), false);
                            return 1;
                        }))));
    }

    private static int send(CommandSourceStack source, List<Component> lines) {
        for (Component line : lines) {
            source.sendSystemMessage(line);
        }
        return 1;
    }

    static List<Component> statusLines(MinecraftServer server) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("HardGrind " + Policy.modVersion()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        if (Policy.buildWorld()) {
            lines.add(line("Build world", "commands and game modes work, nothing is locked. Not a run.", ChatFormatting.AQUA));
            return lines;
        }
        lines.add(line("OP", "blocked for every player", ChatFormatting.GREEN));

        if (!Policy.shouldEnforceSurvival(server)) {
            lines.add(line("Survival", "not enforced (this world's default mode isn't Survival)", ChatFormatting.GRAY));
        } else if (server.isHardcore()) {
            lines.add(line("Survival", "enforced (dead Hardcore players stay spectators)", ChatFormatting.GREEN));
        } else if (Policy.hardcoreUnlocked()) {
            lines.add(line("Survival", "enforced (was hardcore, turned off after a death)", ChatFormatting.GREEN));
        } else {
            lines.add(line("Survival", "enforced", ChatFormatting.GREEN));
        }

        Difficulty difficulty = Policy.difficulty();
        if (difficulty != null) {
            lines.add(line("Difficulty", difficulty.name().toLowerCase(Locale.ROOT) + (Policy.lockActive()
                    ? ", locked (even without the mod)" : ", locked while HardGrind runs"), ChatFormatting.GREEN));
        }

        StringJoiner rules = new StringJoiner(", ");
        for (Map.Entry<String, String> rule : Policy.lockedRules().entrySet()) {
            rules.add(rule.getKey() + "=" + rule.getValue());
        }
        lines.add(line("Locked gamerules", rules.length() == 0 ? "none recorded yet" : rules.toString(), ChatFormatting.WHITE));

        if (Policy.dead()) {
            lines.add(line("World", "DEAD: " + Policy.deathReason(), ChatFormatting.DARK_RED));
        } else if (!Policy.shouldEnforceSurvival(server)) {
            lines.add(line("Lock", "off (this world's default mode isn't Survival)", ChatFormatting.GRAY));
        } else if (!Policy.lockActive()) {
            lines.add(line("Lock", "off: /hardgrind lock makes it stick even without the mod", ChatFormatting.YELLOW));
        } else {
            lines.add(Policy.ironman()
                    ? line("Lock", "on, IRONMAN armed: cheating kills this world", ChatFormatting.RED)
                    : line("Lock", "on, even without the mod. /hardgrind ironman to make cheating fatal", ChatFormatting.GREEN));
            lines.add(line("Save-scum guard", "on (restored backups and old copies are caught)", ChatFormatting.GREEN));
        }

        List<String> cheats = CheatMods.installed();
        lines.add(cheats.isEmpty()
                ? line("Cheat mods", "none installed", ChatFormatting.GREEN)
                : line("Cheat mods", String.join(", ", cheats), ChatFormatting.RED));
        if (FreecamGuard.installed()) {
            lines.add(FreecamGuard.verified()
                    ? line("Freecam", "allowed in fair mode: collision on, look only", ChatFormatting.GREEN)
                    : line("Freecam", "this version can't be forced into fair mode, so locked worlds won't load", ChatFormatting.RED));
        }

        lines.add(runLine());
        return lines;
    }

    static List<Component> logLines(MinecraftServer server, ServerPlayer player) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Run log: " + server.getWorldData().getLevelName()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        if (Policy.buildWorld()) {
            lines.add(line("Build world", "not a run, so nothing is logged", ChatFormatting.AQUA));
        } else {
            long now = Policy.gameTime(server);
            StringJoiner stats = new StringJoiner(" · ");
            stats.add(Policy.dayLabel(now));
            if (player != null) {
                stats.add(Milestones.duration(player.getStats().getValue(Stats.CUSTOM, Stats.PLAY_TIME)) + " played");
                int deaths = player.getStats().getValue(Stats.CUSTOM, Stats.DEATHS);
                stats.add(deaths + (deaths == 1 ? " death" : " deaths"));
                stats.add(player.getStats().getValue(Stats.CUSTOM, Stats.MOB_KILLS) + " mobs killed");
            }
            lines.add(Component.literal(stats.toString()).withStyle(ChatFormatting.WHITE));
            lines.add(runLine());

            lines.add(Component.literal("Milestones").withStyle(ChatFormatting.GOLD));
            for (Milestones.Milestone milestone : Milestones.ALL) {
                Policy.MilestoneRecord record = Policy.milestone(milestone.advancement());
                if (record == null) {
                    lines.add(Component.literal("  · " + milestone.title()).withStyle(ChatFormatting.DARK_GRAY));
                } else {
                    String when = record.earlier ? "before the log started" : Policy.dayLabel(record.gameTime) + " · " + Milestones.duration(record.playTicks);
                    lines.add(Component.literal("  ✔ " + milestone.title()).withStyle(ChatFormatting.GREEN)
                            .append(Component.literal("  " + when).withStyle(ChatFormatting.GRAY)));
                }
            }
        }

        List<String> notes = Policy.notes();
        if (!notes.isEmpty()) {
            lines.add(Component.literal("Notes").withStyle(ChatFormatting.RED));
            for (String note : notes) {
                lines.add(Component.literal("  - " + note).withStyle(ChatFormatting.RED));
            }
        }
        return lines;
    }

    /** One line summing up the run, also shown when you join. */
    static Component runLine() {
        long since = Policy.recordedAtGameTime();
        String watching = since < 0 ? "watching since before 1.3.0" : "watching since " + Policy.dayLabel(since);
        String mode = Policy.dead() ? "DEAD" : Policy.ironman() ? "Ironman" : Policy.lockActive() ? "Locked" : "Unlocked";
        if (Policy.hardcoreUnlocked()) {
            mode += " · was hardcore";
        }
        int notes = Policy.notes().size();
        return notes == 0
                ? line("Run", mode + " · clean · " + watching, ChatFormatting.GREEN)
                : line("Run", mode + " · " + notes + (notes == 1 ? " note" : " notes") + " (/hardgrind log) · " + watching, ChatFormatting.RED);
    }

    private static Component line(String label, String value, ChatFormatting valueColor) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(valueColor));
    }
}
