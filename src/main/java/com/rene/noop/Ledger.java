package com.rene.noop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * HardGrind's memory of every locked world, kept outside the world folders: {@code hardgrind/ledger.json} in the
 * game folder, or {@code ~/.hardgrind/ledger.json} (shared by every launcher and instance) with the
 * {@code sharedLedger} config option. A backup restored over a world, or a copy of it, carries its own old
 * hardgrind.marker, but not this file, so this is what catches save-scumming and keeps dead worlds dead.
 */
final class Ledger {
    private static final Path SHARED_FILE = Path.of(System.getProperty("user.home"), ".hardgrind", "ledger.json");
    private static final Path LOCAL_FILE = FabricLoader.getInstance().getGameDir().resolve("hardgrind").resolve("ledger.json");
    private static final Path FILE = file();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path file() {
        if (Config.get().sharedLedger) {
            return SHARED_FILE;
        }
        // HardGrind 1.5 - 1.6 always used the shared file; carry its history over once.
        if (!Files.exists(LOCAL_FILE) && Files.exists(SHARED_FILE)) {
            try {
                Files.createDirectories(LOCAL_FILE.getParent());
                Files.copy(SHARED_FILE, LOCAL_FILE);
            } catch (IOException e) {
                NoOpMod.LOG.warn("[HardGrind] Could not copy the ledger from {}", SHARED_FILE, e);
            }
        }
        return LOCAL_FILE;
    }

    private Ledger() {
    }

    static final class Entry {
        String name;
        String path;
        long seed;
        /** Furthest saved game time HardGrind has seen for this world. */
        long maxGameTime;
        boolean ironman;
        boolean dead;
        String deathReason;
    }

    private static final class Data {
        Map<String, Entry> worlds = new LinkedHashMap<>();
    }

    static synchronized Entry get(String worldId) {
        return read().worlds.get(worldId);
    }

    /** The world last seen at this folder with this seed, if any: used when a world's marker is gone. */
    static synchronized Map.Entry<String, Entry> findAt(Path folder, long seed) {
        for (Map.Entry<String, Entry> world : read().worlds.entrySet()) {
            if (world.getValue().seed == seed && samePlace(world.getValue().path, folder)) {
                return world;
            }
        }
        return null;
    }

    /** Saves this world's entry; maxGameTime only ever goes up, and ironman/dead only ever turn on. */
    static synchronized void record(String worldId, String name, Path folder, long seed, long gameTime, boolean ironman, boolean dead, String deathReason) {
        Data data = read();
        Entry entry = data.worlds.computeIfAbsent(worldId, id -> new Entry());
        entry.name = name;
        entry.path = folder.toString();
        entry.seed = seed;
        entry.maxGameTime = Math.max(entry.maxGameTime, gameTime);
        entry.ironman |= ironman;
        if (dead && !entry.dead) {
            entry.dead = true;
            entry.deathReason = deathReason;
        }
        write(data);
    }

    static boolean samePlace(String recorded, Path folder) {
        return recorded != null && Path.of(recorded).toAbsolutePath().normalize().equals(folder.toAbsolutePath().normalize());
    }

    private static Data read() {
        if (!Files.exists(FILE)) {
            return new Data();
        }
        try {
            Data data = GSON.fromJson(Files.readString(FILE), Data.class);
            return data == null || data.worlds == null ? new Data() : data;
        } catch (IOException | JsonParseException e) {
            NoOpMod.LOG.warn("[HardGrind] Could not read {}: {}", FILE, e.toString());
            return new Data();
        }
    }

    private static void write(Data data) {
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling("ledger.json.tmp");
            Files.writeString(tmp, GSON.toJson(data));
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            NoOpMod.LOG.warn("[HardGrind] Could not write {}", FILE, e);
        }
    }
}
