package com.rene.noop;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.StringJoiner;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A datapack HardGrind writes into the world's own datapacks folder. Minecraft runs it with or without the mod,
 * so the world stays locked after the mod is removed:
 * <ul>
 *   <li>anyone in Creative (or Spectator outside Hardcore) is put back in Survival, and it's recorded;</li>
 *   <li>locked gamerules and the locked difficulty are put back, and it's recorded;</li>
 *   <li>if the mod isn't there (no heartbeat), or the world is dead, everyone is frozen;</li>
 *   <li>a tick counter shows the mod later whether the pack was switched off.</li>
 * </ul>
 * State lives in the "hardgrind" scoreboard objective, which is saved with the world.
 */
final class WorldLock {
    static final String PACK_DIR = "hardgrind_lock";
    private static final String PACK_ID = "file/" + PACK_DIR;
    private static final String NS = "hardgrind_lock";

    /**
     * A sound event only the mod registers. The pack's jukebox song points at it, so without the mod Minecraft
     * can't load the world at all ("data pack validation failed"), and the error names this id. The only way
     * in is Safe Mode, which switches the pack off, and that's caught when the mod is back.
     */
    static final String LOCK_SOUND = "hardgrind_noop:this_world_needs_hardgrind";

    // Ticks the pack may miss (e.g. while datapacks reload) before it counts as switched off.
    private static final long AUDIT_SLACK_TICKS = 1200;
    // A switched-off pack (e.g. Safe Mode) only counts as cheating if the world was played longer than this.
    private static final long DISABLED_SLACK_TICKS = 100;

    private WorldLock() {
    }

    static Path packDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_DIR);
    }

    static boolean packExists(MinecraftServer server) {
        return Files.exists(packDir(server).resolve("pack.mcmeta"));
    }

    /**
     * Looks for cheating since HardGrind last saw this world. Clear signs go into {@code fatal} (they kill an
     * ironman world); things that can happen innocently go into {@code minor} (only a note).
     *
     * @param marker the policy from last time, or null if hardgrind.marker is gone
     */
    static void inspect(MinecraftServer server, Policy.Marker marker, long now, List<String> fatal, List<String> minor) {
        if (marker == null) {
            fatal.add("hardgrind.marker was deleted or broken");
        }

        // Play time during which the pack didn't run.
        long unlocked = 0;
        if (marker != null) {
            long played = now - marker.lastSeenGameTime;
            long audited = score(server, "#ticks").orElse(0) - marker.lastSeenAuditTicks;
            unlocked = played - audited;
        }

        String onDisk = diskHash(server);
        boolean enabled = server.getPackRepository().getSelectedIds().contains(PACK_ID);
        if (onDisk == null) {
            fatal.add("the lock datapack was deleted");
        } else {
            boolean known = marker != null && (onDisk.equals(marker.lockHash) || onDisk.equals(hash(files(server, marker))));
            if (marker != null && !known) {
                fatal.add("the lock datapack was edited");
            }
            if (!enabled) {
                // Minecraft's Safe Mode switches it off too. That alone is harmless; playing like that isn't.
                if (unlocked > DISABLED_SLACK_TICKS) {
                    fatal.add("the lock datapack was switched off (Safe Mode?) and the world was played for " + duration(unlocked));
                } else {
                    minor.add("the lock datapack was switched off (Safe Mode?), but the world wasn't played like that");
                }
            }
        }

        int cheats = score(server, "#cheat").orElse(0);
        if (cheats > 0) {
            fatal.add("Creative/Spectator or a changed gamerule or difficulty was caught " + cheats + (cheats == 1 ? " time" : " times"));
        }

        if (onDisk != null && enabled && unlocked > AUDIT_SLACK_TICKS) {
            fatal.add("the lock wasn't running for " + duration(unlocked) + " of play");
        }
    }

    private static String duration(long ticks) {
        return ticks < 1200 ? ticks / 20 + " s" : "about " + ticks / 1200 + " min";
    }

    /** Writes the pack (only files that changed), makes sure it's enabled, and returns the hash of what's on disk. */
    static String install(MinecraftServer server, Policy.Marker marker) {
        run(server, "scoreboard objectives add hardgrind dummy");
        Map<String, String> files = files(server, marker);
        Path dir = packDir(server);
        boolean changed = false;
        try {
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path path = dir.resolve(file.getKey());
                if (Files.exists(path) && Files.readString(path).equals(file.getValue())) {
                    continue;
                }
                Files.createDirectories(path.getParent());
                Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
                Files.writeString(tmp, file.getValue());
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                changed = true;
            }
        } catch (IOException e) {
            NoOpMod.LOG.error("[HardGrind] Could not write the lock datapack to {}", dir, e);
        }

        if (changed || !server.getPackRepository().getSelectedIds().contains(PACK_ID)) {
            server.getPackRepository().reload();
            List<String> selected = new ArrayList<>(server.getPackRepository().getSelectedIds());
            if (!selected.contains(PACK_ID)) {
                selected.add(PACK_ID);
            }
            server.reloadResources(selected).exceptionally(e -> {
                NoOpMod.LOG.error("[HardGrind] Could not enable the lock datapack", e);
                return null;
            });
        }
        return hash(files);
    }

    /** Takes the pack out of the world again (/hardgrind unlock), so it opens without the mod. */
    static void uninstall(MinecraftServer server) {
        Path dir = packDir(server);
        if (Files.exists(dir)) {
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException e) {
                NoOpMod.LOG.error("[HardGrind] Could not delete the lock datapack at {}", dir, e);
            }
        }
        server.getPackRepository().reload();
        List<String> selected = new ArrayList<>(server.getPackRepository().getSelectedIds());
        selected.remove(PACK_ID);
        server.reloadResources(selected).exceptionally(e -> {
            NoOpMod.LOG.error("[HardGrind] Could not unload the lock datapack", e);
            return null;
        });
        run(server, "scoreboard objectives remove hardgrind");
    }

    /** Tells the pack the mod is running. Must be called at the start of every tick, before functions run. */
    static void heartbeat(MinecraftServer server) {
        run(server, "scoreboard players set #mod hardgrind 1");
    }

    static OptionalInt score(MinecraftServer server, String holder) {
        try {
            return OptionalInt.of(server.getCommands().getDispatcher().execute("scoreboard players get " + holder + " hardgrind", source(server)));
        } catch (CommandSyntaxException | RuntimeException e) {
            return OptionalInt.empty();
        }
    }

    static void setScore(MinecraftServer server, String holder, int value) {
        run(server, "scoreboard players set " + holder + " hardgrind " + value);
    }

    private static void run(MinecraftServer server, String command) {
        try {
            server.getCommands().getDispatcher().execute(command, source(server));
        } catch (CommandSyntaxException | RuntimeException e) {
            // e.g. the objective already exists, or doesn't yet
        }
    }

    private static CommandSourceStack source(MinecraftServer server) {
        return server.createCommandSourceStack().withSuppressedOutput();
    }

    // Only ever add files here (anywhere in the list); never remove, rename or reorder one. Worlds locked by an
    // older HardGrind are recognised by the files they have, in this order (see diskHash).
    static Map<String, String> files(MinecraftServer server, Policy.Marker marker) {
        PackFormat format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA);
        Map<String, String> files = new LinkedHashMap<>();
        // max_format is open-ended so the lock keeps loading in newer Minecraft versions, even without the mod.
        files.put("pack.mcmeta", """
                {
                  "pack": {
                    "description": "HardGrind lock: this world needs the HardGrind mod. Removing, disabling or editing this pack counts as cheating.",
                    "min_format": [%d, %d],
                    "max_format": 9999
                  }
                }
                """.formatted(format.major(), format.minor()));
        files.put("data/minecraft/tags/function/load.json", tag("load"));
        // Separate, optional entries: if one function stops parsing in some future version, the others still run.
        files.put("data/minecraft/tags/function/tick.json", tag("audit", "rules", "difficulty", "guard"));
        files.put(function("load"), "scoreboard objectives add hardgrind dummy\n");
        // Never playable (no disc uses it); it exists so the world can't load without the mod. See LOCK_SOUND.
        files.put("data/" + NS + "/jukebox_song/requires_hardgrind.json", """
                {
                  "sound_event": "%s",
                  "description": "HardGrind lock",
                  "length_in_seconds": 1.0,
                  "comparator_output": 0
                }
                """.formatted(LOCK_SOUND));

        StringBuilder audit = new StringBuilder("""
                # Counts ticks so HardGrind can tell if this pack was switched off.
                scoreboard players add #ticks hardgrind 1
                execute if entity @a[gamemode=creative] run scoreboard players add #cheat hardgrind 1
                gamemode survival @a[gamemode=creative]
                """);
        // From the marker, not just the server: a revived world loads before HardGrind's hook says it isn't hardcore.
        if (!server.isHardcore() || marker.hardcoreUnlocked) {
            audit.append("""
                    execute if entity @a[gamemode=spectator] run scoreboard players add #cheat hardgrind 1
                    gamemode survival @a[gamemode=spectator]
                    """);
        }
        files.put(function("audit"), audit.toString());

        StringBuilder rules = new StringBuilder("# Puts locked gamerules back and records the change.\n");
        for (LockedRule rule : LockedRule.values()) {
            String name = GameRuleAccess.commandName(rule);
            String value = marker.gamerules.get(rule.key);
            if (name == null || value == null) {
                continue;
            }
            String number = value.equals("true") ? "1" : value.equals("false") ? "0" : value;
            rules.append("execute store result score #rule hardgrind run gamerule ").append(name).append('\n')
                    .append("execute unless score #rule hardgrind matches ").append(number).append(" run scoreboard players add #cheat hardgrind 1\n")
                    .append("execute unless score #rule hardgrind matches ").append(number).append(" run gamerule ").append(name).append(' ').append(value).append('\n');
        }
        files.put(function("rules"), rules.toString());

        StringBuilder difficultyCheck = new StringBuilder("# Puts the locked difficulty back and records the change.\n");
        Difficulty difficulty = Policy.parseDifficulty(marker.difficulty);
        if (difficulty != null) {
            String key = difficulty.name().toLowerCase(Locale.ROOT);
            int id = difficulty.getId();
            difficultyCheck.append("execute store result score #diff hardgrind run difficulty\n")
                    .append("execute unless score #diff hardgrind matches ").append(id).append(" run scoreboard players add #cheat hardgrind 1\n")
                    .append("execute unless score #diff hardgrind matches ").append(id).append(" run difficulty ").append(key).append('\n');
        }
        files.put(function("difficulty"), difficultyCheck.toString());

        files.put(function("guard"), """
                # The mod sets #mod to 1 at the start of every tick. Without it, or once the world is dead, nobody plays.
                execute if score #dead hardgrind matches 1 run function hardgrind_lock:frozen_dead
                execute unless score #dead hardgrind matches 1 unless score #mod hardgrind matches 1 run function hardgrind_lock:frozen_no_mod
                scoreboard players set #mod hardgrind 0
                """);
        files.put(function("frozen_no_mod"), frozen("HardGrind isn't installed. This world stays locked until it's back."));
        files.put(function("frozen_dead"), frozen("This world is dead: cheating was caught. It can't be played anymore."));
        return files;
    }

    private static String frozen(String message) {
        return """
                gamemode adventure @a[gamemode=survival]
                effect give @a minecraft:blindness 2 0 true
                effect give @a minecraft:slowness 2 255 true
                effect give @a minecraft:mining_fatigue 2 255 true
                effect give @a minecraft:weakness 2 255 true
                effect give @a minecraft:resistance 2 255 true
                title @a actionbar {"text":"%s","color":"red"}
                """.formatted(message);
    }

    private static String function(String name) {
        return "data/" + NS + "/function/" + name + ".mcfunction";
    }

    private static String tag(String... functions) {
        StringJoiner values = new StringJoiner(", ");
        for (String f : functions) {
            values.add("{\"id\": \"" + NS + ":" + f + "\", \"required\": false}");
        }
        return "{\"values\": [" + values + "]}\n";
    }

    /**
     * Hash of the pack as currently on disk, in the form of the lockHash recorded when it was installed, or null if
     * the pack is gone. Files that are missing are left out: a pack an older HardGrind installed doesn't have the
     * files added since, and still matches its own hash. A file taken out of a pack that had it doesn't.
     */
    private static String diskHash(MinecraftServer server) {
        if (!packExists(server)) {
            return null;
        }
        Path dir = packDir(server);
        Map<String, String> onDisk = new LinkedHashMap<>();
        try {
            for (String name : files(server, new Policy.Marker()).keySet()) {
                Path path = dir.resolve(name);
                if (Files.exists(path)) {
                    onDisk.put(name, Files.readString(path));
                }
            }
        } catch (IOException e) {
            return null;
        }
        return hash(onDisk);
    }

    private static String hash(Map<String, String> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> file : files.entrySet()) {
                digest.update(file.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(file.getValue().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
