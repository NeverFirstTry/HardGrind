package com.rene.noop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Per-world policy stored in {@code <world>/hardgrind.marker}. The first time the mod sees a world it records
 * whether Survival should be enforced, the locked gamerules and the difficulty; after that, any drift
 * (e.g. from editing level.dat) is put back.
 *
 * <p>It also keeps the run's history: notes about anything that happened outside HardGrind's control (play
 * without the mod, tampering, rollbacks) and the milestone timeline. {@link Ledger} keeps a copy of the
 * essentials outside the world folder so backups and copies can't undo them.
 */
final class Policy {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String MARKER_FILE = "hardgrind.marker";

    // 1: 1.1.x, whose name-based reflection only worked in the dev environment, so its markers are unusable.
    // 2: 1.2.0, first marker with real gamerule values. 3: adds play-time tracking and notes.
    // 4: adds the world lock (see WorldLock) and ironman. 5: world id, difficulty lock, milestones.
    // 6: build worlds.
    private static final int SCHEMA = 6;

    // The game test server builds a throwaway world; the lock, ledger and difficulty lock stay out of it.
    private static final boolean LOCK_ENABLED = System.getProperty("fabric-api.gametest") == null;

    static final int SEEN_INTERVAL_TICKS = 1200;
    // The world can't get further ahead of lastSeenGameTime than one save interval while HardGrind runs.
    private static final long OFFLINE_SLACK_TICKS = 2L * SEEN_INTERVAL_TICKS;
    // The ledger is written on every save, so a world (even after a crash) is never behind it by more than this.
    private static final long ROLLBACK_SLACK_TICKS = 1200;
    // A world whose marker is gone is matched back to its ledger entry only once it's clearly not brand new.
    private static final long ADOPT_MIN_TICKS = 6000;

    private static Marker cached;
    private static Path cachedPath;

    private Policy() {
    }

    static void initOrLoad(MinecraftServer server) {
        Path path = server.getWorldPath(LevelResource.ROOT).resolve(MARKER_FILE);
        long now = gameTime(server);
        boolean existed = Files.exists(path);
        Marker marker = read(path);
        boolean markerValid = marker != null && marker.schema >= 2 && marker.gamerules != null;
        // Without a usable marker, the lock's datapack or its scoreboard still show the world was locked.
        boolean lockWasInstalled = markerValid ? marker.lockInstalled
                : WorldLock.packExists(server) || WorldLock.score(server, "#ticks").isPresent();

        if (!markerValid) {
            boolean unreadable = existed && marker == null;
            marker = captureCurrentPolicy(server, now);
            if (unreadable) {
                marker.notes.add(dayLabel(now) + ": hardgrind.marker was unreadable and had to be recreated.");
            }
        } else {
            if (marker.notes == null) {
                marker.notes = new ArrayList<>();
            }
            if (marker.milestones == null) {
                marker.milestones = new LinkedHashMap<>();
            }
            if (marker.schema >= 3 && now > marker.lastSeenGameTime + OFFLINE_SLACK_TICKS) {
                long minutes = (now - marker.lastSeenGameTime) / 1200;
                marker.notes.add(dayLabel(now) + ": the world was played for about " + minutes + " min without HardGrind.");
            }
            if (marker.schema < 3) {
                marker.recordedAtGameTime = -1;
            }
            if (marker.schema < 6) {
                marker.buildWorld = isCreative(server, marker);
            }
            if (marker.running) {
                // Last session never shut down properly. Crashes happen, so this is only a note; dying or
                // dropping to low health saves at once (NoOpMod), so force-quitting doesn't undo those.
                long lost = marker.lastSeenGameTime - now;
                marker.notes.add(dayLabel(now) + ": the game didn't shut down properly last time (crash or force-quit)"
                        + (lost > 600 ? "; about " + Math.max(1, lost / 1200) + " min of play was lost" : "") + ".");
            }
            // Rules added in a newer HardGrind (or Minecraft) get locked at whatever they are now.
            for (LockedRule rule : LockedRule.values()) {
                String value = GameRuleAccess.get(server, rule);
                if (value != null) {
                    marker.gamerules.putIfAbsent(rule.key, value);
                }
            }
        }

        // Build worlds can never be locked, so a marker that calls a locked world one is wrong: the lock wins.
        if (marker.buildWorld && lockWasInstalled) {
            marker.buildWorld = false;
            marker.notes.add(dayLabel(now) + ": hardgrind.marker called this locked world a build world; it stays locked.");
        }

        if (marker.enforceSurvival && !marker.buildWorld) {
            // The difficulty is locked in every Survival world while the mod runs (see Hooks); only locked worlds
            // also get vanilla's own lock, which stays after the mod is gone.
            if (parseDifficulty(marker.difficulty) == null) {
                marker.difficulty = server.getWorldData().getDifficulty().name();
            }
            // A world that was locked stays locked; otherwise it's locked by /hardgrind lock, or by autoLock.
            if (LOCK_ENABLED && (lockWasInstalled || Config.get().autoLock)) {
                applyLock(server, marker, lockWasInstalled, markerValid, now);
            }
            server.setDifficulty(parseDifficulty(marker.difficulty), true);
        }

        marker.schema = SCHEMA;
        marker.version = modVersion();
        marker.lastSeenGameTime = now;
        marker.running = true;
        cached = marker;
        cachedPath = path;
        write(path, marker);
    }

    /**
     * Checks the world for cheating since HardGrind last saw it (tampering, rollbacks, copies, a changed
     * difficulty), kills it if it's armed and anything turned up, then puts the lock back in place.
     */
    private static void applyLock(MinecraftServer server, Marker marker, boolean lockWasInstalled, boolean markerValid, long now) {
        Path folder = worldFolder(server);
        long seed = server.overworld().getSeed();
        boolean armed = marker.ironman || WorldLock.score(server, "#ironman").orElse(0) == 1;
        // problems kill an armed world; minor ones are only noted.
        List<String> problems = new ArrayList<>();
        List<String> minor = new ArrayList<>();

        // Who is this world? A lost marker means a new id, unless the ledger knows a world at this spot.
        if (marker.worldId == null) {
            Map.Entry<String, Ledger.Entry> known = now > ADOPT_MIN_TICKS ? Ledger.findAt(folder, seed) : null;
            marker.worldId = known != null ? known.getKey() : UUID.randomUUID().toString();
        }
        // A world may never go backwards. Copies are fine (e.g. moving to a new instance) as long as they're
        // up to date; a backup or old copy that's behind the furthest save is caught, and only that copy pays
        // for it (it gets its own id), so opening a leftover old copy by accident can't end the real run.
        Ledger.Entry entry = Ledger.get(marker.worldId);
        if (entry != null && !marker.dead) {
            armed |= entry.ironman;
            if (entry.dead) {
                problems.add("this world already died (" + entry.deathReason + ")");
            } else if (entry.maxGameTime - now > ROLLBACK_SLACK_TICKS) {
                problems.add("this is a backup or old copy, about " + (entry.maxGameTime - now) / 1200 + " min behind the world's latest save");
                marker.worldId = UUID.randomUUID().toString();
            }
        }

        if (lockWasInstalled) {
            // A marker that's missing or unreadable while the lock exists was deleted or tampered with:
            // writes are atomic, so a crash can't leave it half-written.
            WorldLock.inspect(server, markerValid ? marker : null, now, problems, minor);
        }

        Difficulty difficulty = server.getWorldData().getDifficulty();
        if (lockWasInstalled && parseDifficulty(marker.difficulty) != difficulty) {
            problems.add("the difficulty was changed to " + difficulty.name().toLowerCase(Locale.ROOT));
        }

        for (String problem : problems) {
            marker.notes.add(dayLabel(now) + ": " + problem + ".");
            NoOpMod.LOG.warn("[HardGrind] {}", problem);
        }
        for (String note : minor) {
            marker.notes.add(dayLabel(now) + ": " + note + ".");
            NoOpMod.LOG.info("[HardGrind] {}", note);
        }
        if (!problems.isEmpty() && armed && !marker.dead) {
            marker.dead = true;
            marker.deathReason = String.join(", ", problems);
            NoOpMod.LOG.warn("[HardGrind] Ironman world killed: {}", marker.deathReason);
        }
        marker.ironman = armed;
        if (!marker.dead && WorldLock.score(server, "#dead").orElse(0) == 1) {
            marker.dead = true;
            marker.deathReason = "it died earlier";
        }

        // Put drift back first, or the lock's very first tick would record it as cheating.
        restoreGamerules(server, marker);
        server.setDifficulty(parseDifficulty(marker.difficulty), true);
        // Vanilla's own difficulty lock: greys out the button, and still holds when the mod is removed.
        server.setDifficultyLocked(true);

        marker.lockHash = WorldLock.install(server, marker);
        marker.lockInstalled = true;
        // Everything above has been recorded; start counting afresh.
        WorldLock.setScore(server, "#cheat", 0);
        WorldLock.setScore(server, "#ironman", marker.ironman ? 1 : 0);
        WorldLock.setScore(server, "#dead", marker.dead ? 1 : 0);
        marker.lastSeenAuditTicks = WorldLock.score(server, "#ticks").orElse(0);

        Ledger.record(marker.worldId, server.getWorldData().getLevelName(), folder, seed, now, marker.ironman, marker.dead, marker.deathReason);
    }

    /** Called after every world save: the ledger remembers how far this world has been saved. */
    static void onSaved(MinecraftServer server) {
        if (cached != null && cached.lockInstalled && cached.worldId != null) {
            Ledger.record(cached.worldId, server.getWorldData().getLevelName(), worldFolder(server), server.overworld().getSeed(),
                    gameTime(server), cached.ironman, cached.dead, cached.deathReason);
        }
    }

    /** Locks the loaded world now (/hardgrind lock). False if it isn't a Survival world, or is a build world. */
    static boolean lock(MinecraftServer server) {
        if (cached == null || !cached.enforceSurvival || cached.buildWorld || !LOCK_ENABLED) {
            return false;
        }
        if (!cached.lockInstalled) {
            applyLock(server, cached, false, true, gameTime(server));
            write(cachedPath, cached);
        }
        return true;
    }

    /**
     * Removes the lock from the loaded world (/hardgrind unlock), so it opens without the mod again. Not for
     * ironman worlds. Leaves a note, since the world was unprotected from then on.
     */
    static boolean unlock(MinecraftServer server) {
        if (cached == null || !cached.lockInstalled || cached.ironman || cached.dead) {
            return false;
        }
        WorldLock.uninstall(server);
        server.setDifficultyLocked(false);
        cached.lockInstalled = false;
        cached.lockHash = null;
        cached.notes.add(dayLabel(gameTime(server)) + ": the lock was removed with /hardgrind unlock.");
        write(cachedPath, cached);
        return true;
    }

    /** Arms ironman for the loaded world, locking it first if needed. False if it isn't a Survival world. */
    static boolean armIronman(MinecraftServer server) {
        if (!lock(server)) {
            return false;
        }
        cached.ironman = true;
        WorldLock.setScore(server, "#ironman", 1);
        // Only what happens from now on counts.
        WorldLock.setScore(server, "#cheat", 0);
        write(cachedPath, cached);
        onSaved(server);
        return true;
    }

    /**
     * Makes the loaded world a build world for good (/hardgrind build): HardGrind steps aside, so commands and
     * game modes work and nothing is locked. False if the world is locked; it has to be unlocked first.
     */
    static boolean makeBuildWorld(MinecraftServer server) {
        if (cached == null || cached.lockInstalled) {
            return false;
        }
        if (!cached.buildWorld) {
            cached.buildWorld = true;
            cached.notes.add(dayLabel(gameTime(server)) + ": made a build world with /hardgrind build.");
            write(cachedPath, cached);
        }
        return true;
    }

    static boolean loaded() {
        return cached != null;
    }

    static boolean buildWorld() {
        return cached != null && cached.buildWorld;
    }

    static boolean hardcoreUnlocked() {
        return cached != null && cached.hardcoreUnlocked;
    }

    /**
     * After a death in a hardcore world (/hardgrind revive): from now on the world isn't hardcore (see
     * Hooks.hardcoreUnlocked), for good, and the run log says so. Revive checks whether it's allowed.
     */
    static void recordHardcoreRevive(MinecraftServer server, String playerName) {
        cached.hardcoreUnlocked = true;
        cached.notes.add(dayLabel(gameTime(server)) + ": " + playerName
                + " died and hardcore was turned off with /hardgrind revive. No longer a hardcore run.");
        write(cachedPath, cached);
    }

    /** Rewrites the lock datapack for the world as it is now (e.g. no longer hardcore). */
    static void refreshLock(MinecraftServer server) {
        if (cached != null && cached.lockInstalled && LOCK_ENABLED) {
            cached.lockHash = WorldLock.install(server, cached);
            write(cachedPath, cached);
        }
    }

    static boolean lockActive() {
        return cached != null && cached.lockInstalled;
    }

    static boolean ironman() {
        return cached != null && cached.ironman;
    }

    static boolean dead() {
        return cached != null && cached.dead;
    }

    static String deathReason() {
        return cached == null || cached.deathReason == null ? "cheating was caught" : cached.deathReason;
    }

    /** The locked difficulty, or null if this world has none. */
    static Difficulty difficulty() {
        return cached == null || !cached.enforceSurvival || cached.buildWorld ? null : parseDifficulty(cached.difficulty);
    }

    static Difficulty parseDifficulty(String name) {
        try {
            return name == null ? null : Difficulty.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Records how far the world has been played with HardGrind running. */
    static void markSeen(MinecraftServer server) {
        if (cached == null) {
            return;
        }
        cached.lastSeenGameTime = gameTime(server);
        if (cached.lockInstalled) {
            cached.lastSeenAuditTicks = WorldLock.score(server, "#ticks").orElse((int) cached.lastSeenAuditTicks);
        }
        write(cachedPath, cached);
    }

    /** A clean shutdown: final play-time check, and the next load knows the game didn't crash. */
    static void onStopping(MinecraftServer server) {
        if (cached != null) {
            cached.running = false;
            markSeen(server);
        }
    }

    static void unload() {
        cached = null;
        cachedPath = null;
    }

    static boolean shouldEnforceSurvival(MinecraftServer server) {
        return cached != null ? cached.enforceSurvival && !cached.buildWorld : server.getDefaultGameType() == GameType.SURVIVAL;
    }

    static boolean enforceGamerules(MinecraftServer server) {
        if (cached == null || cached.buildWorld) {
            return false;
        }

        boolean changed = restoreGamerules(server, cached);
        Difficulty difficulty = difficulty();
        if (difficulty != null && server.getWorldData().getDifficulty() != difficulty) {
            server.setDifficulty(difficulty, true);
            changed = true;
        }
        if (changed && !server.getPlayerList().getPlayers().isEmpty()) {
            server.getPlayerList().broadcastSystemMessage(Component.literal("[HardGrind] Gamerules restored to policy."), false);
        }
        return changed;
    }

    private static boolean restoreGamerules(MinecraftServer server, Marker marker) {
        boolean changed = false;
        for (LockedRule rule : LockedRule.values()) {
            String expected = marker.gamerules.get(rule.key);
            String current = GameRuleAccess.get(server, rule);
            if (expected == null || current == null || current.equals(expected)) {
                continue;
            }
            // A hand-edited marker with an invalid value is left alone rather than guessed at.
            if (GameRuleAccess.set(server, rule, expected)) {
                changed = true;
            }
        }
        return changed;
    }

    static boolean milestonesStarted() {
        return cached != null && cached.milestonesStarted;
    }

    static void startMilestones() {
        if (cached != null && !cached.milestonesStarted) {
            cached.milestonesStarted = true;
            write(cachedPath, cached);
        }
    }

    static MilestoneRecord milestone(String advancement) {
        return cached == null ? null : cached.milestones.get(advancement);
    }

    static void recordMilestone(String advancement, long gameTime, long playTicks, boolean earlier) {
        MilestoneRecord record = new MilestoneRecord();
        record.gameTime = gameTime;
        record.playTicks = playTicks;
        record.earlier = earlier;
        cached.milestones.put(advancement, record);
        write(cachedPath, cached);
    }

    /** Locked rules and their values, in marker order. Empty until a world is loaded. */
    static Map<String, String> lockedRules() {
        return cached == null ? Map.of() : Collections.unmodifiableMap(cached.gamerules);
    }

    /** Things that happened to this world outside HardGrind's control. Empty means a clean run. */
    static List<String> notes() {
        return cached == null ? List.of() : Collections.unmodifiableList(cached.notes);
    }

    /** Game time when HardGrind started watching this world, or -1 if unknown (policy recorded by 1.2.0). */
    static long recordedAtGameTime() {
        return cached == null ? -1 : cached.recordedAtGameTime;
    }

    static long gameTime(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    static String dayLabel(long gameTime) {
        return "Day " + (gameTime / 24000 + 1);
    }

    static String modVersion() {
        return FabricLoader.getInstance().getModContainer(NoOpMod.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static Path worldFolder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    }

    private static Marker captureCurrentPolicy(MinecraftServer server, long now) {
        Marker marker = new Marker();
        marker.modId = NoOpMod.MOD_ID;
        marker.recordedAtGameTime = now;
        for (LockedRule rule : LockedRule.values()) {
            String value = GameRuleAccess.get(server, rule);
            if (value != null) {
                marker.gamerules.put(rule.key, value);
            }
        }
        // Hardcore worlds always default to Survival, so they are enforced too; NoOpMod.enforceSurvival
        // leaves their dead (spectating) players alone.
        marker.enforceSurvival = server.getDefaultGameType() == GameType.SURVIVAL;
        marker.buildWorld = isCreative(server, marker);
        return marker;
    }

    // Creative worlds are build worlds without asking. A world HardGrind recorded as Survival stays a run, even if
    // its default mode was switched to Creative later (e.g. by editing level.dat).
    private static boolean isCreative(MinecraftServer server, Marker marker) {
        return !marker.enforceSurvival && server.getDefaultGameType() == GameType.CREATIVE;
    }

    private static Marker read(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return GSON.fromJson(Files.readString(path), Marker.class);
        } catch (IOException | JsonParseException e) {
            NoOpMod.LOG.warn("[HardGrind] Could not read {}, recreating it: {}", path, e.toString());
            return null;
        }
    }

    // Write-then-rename, so a crash never leaves a half-written marker (which would look like tampering).
    private static void write(Path path, Marker marker) {
        try {
            Path tmp = path.resolveSibling(MARKER_FILE + ".tmp");
            Files.writeString(tmp, GSON.toJson(marker));
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            NoOpMod.LOG.warn("[HardGrind] Could not write {}", path, e);
        }
    }

    static final class Marker {
        String modId;
        String version;
        int schema;
        String worldId;
        Map<String, String> gamerules = new LinkedHashMap<>();
        String difficulty;
        boolean enforceSurvival;
        long recordedAtGameTime;
        long lastSeenGameTime;
        List<String> notes = new ArrayList<>();
        boolean lockInstalled;
        String lockHash;
        long lastSeenAuditTicks;
        boolean ironman;
        boolean dead;
        String deathReason;
        boolean milestonesStarted;
        /** A world for building, not a run: HardGrind steps aside (see makeBuildWorld). Never locked. */
        boolean buildWorld;
        /** Hardcore was turned off after a death (/hardgrind revive); the game treats the world as not hardcore. */
        boolean hardcoreUnlocked;
        Map<String, MilestoneRecord> milestones = new LinkedHashMap<>();
        /** True while the world is open; still true at the next load means the game crashed or was killed. */
        boolean running;
    }

    static final class MilestoneRecord {
        long gameTime;
        long playTicks;
        /** Already done when HardGrind started keeping the log, so there's no date for it. */
        boolean earlier;
    }
}
