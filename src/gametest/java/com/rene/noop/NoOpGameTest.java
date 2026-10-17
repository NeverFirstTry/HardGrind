package com.rene.noop;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.level.storage.LevelResource;

public class NoOpGameTest {
    @GameTest
    public void opIsDeadForPlayersButNotConsole(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        loadRunWorld(server);
        ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
        PermissionTestAccess.opAtOwnerLevel(server, player);
        try {
            helper.assertFalse(PermissionTestAccess.hasCheatPermissions(player), Component.literal("opped player still has cheat permissions"));

            server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "gamemode creative");
            helper.assertValueEqual(GameType.SURVIVAL, mode(player), Component.literal("game mode after /gamemode creative"));

            helper.assertTrue(PermissionTestAccess.consoleIsOwner(server), Component.literal("console lost its admin commands"));
        } finally {
            server.getPlayerList().deop(player.nameAndId());
        }
        helper.succeed();
    }

    @GameTest
    public void survivalIsEnforcedIncludingSpectator(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.assertFalse(server.isHardcore(), Component.literal("test world is unexpectedly hardcore"));

        ServerPlayer creative = mockPlayer(helper, GameType.CREATIVE);
        ServerPlayer spectator = mockPlayer(helper, GameType.SPECTATOR);
        NoOpMod.enforceSurvival(creative, server);
        NoOpMod.enforceSurvival(spectator, server);

        helper.assertValueEqual(GameType.SURVIVAL, mode(creative), Component.literal("creative player's game mode"));
        helper.assertValueEqual(GameType.SURVIVAL, mode(spectator), Component.literal("spectator's game mode outside hardcore"));
        helper.succeed();
    }

    @GameTest
    public void everyLockedGameruleIsRestored(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        loadRunWorld(server);
        // Line the world up with the recorded policy first, so the values read below are the policy's.
        Policy.enforceGamerules(server);

        int checked = 0;
        for (LockedRule rule : LockedRule.values()) {
            String locked = GameRuleAccess.get(server, rule);
            if (locked == null) {
                continue;
            }
            String changed = locked.equals("true") ? "false" : locked.equals("false") ? "true" : String.valueOf(Integer.parseInt(locked) + 7);
            helper.assertTrue(GameRuleAccess.set(server, rule, changed), Component.literal("could not change " + rule.key));
            helper.assertValueEqual(changed, GameRuleAccess.get(server, rule), Component.literal(rule.key + " after changing it"));

            helper.assertTrue(Policy.enforceGamerules(server), Component.literal("drift in " + rule.key + " was not detected"));
            helper.assertValueEqual(locked, GameRuleAccess.get(server, rule), Component.literal(rule.key + " after restore"));
            checked++;
        }
        helper.assertTrue(checked >= 13, Component.literal("only " + checked + " locked rules exist on this version"));
        helper.assertFalse(Policy.enforceGamerules(server), Component.literal("a second pass still found drift"));
        helper.succeed();
    }

    @GameTest
    public void staleOrCorruptMarkerIsRecreated(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String[] badMarkers = {
                "{ this is not json",
                // What 1.1.1 wrote in a real install: no schema, no gamerules.
                "{\"modId\": \"hardgrind_noop\", \"version\": \"1.1.1\", \"gamerules\": {}, \"enforceSurvival\": true}",
        };
        for (String bad : badMarkers) {
            writeMarker(server, bad);
            Policy.initOrLoad(server);
            String rewritten = readMarker(server);
            helper.assertTrue(rewritten.contains("\"schema\": 6") && rewritten.contains("\"keepInventory\""),
                    Component.literal("marker was not recreated from: " + bad));
        }
        helper.succeed();
    }

    @GameTest
    public void markerFrom120KeepsItsValues(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String current = GameRuleAccess.get(server, LockedRule.KEEP_INVENTORY);
        String recorded = current.equals("true") ? "false" : "true";
        writeMarker(server, "{\"modId\": \"hardgrind_noop\", \"version\": \"1.2.0\", \"schema\": 2, "
                + "\"gamerules\": {\"keepInventory\": \"" + recorded + "\"}, \"enforceSurvival\": true}");

        Policy.initOrLoad(server);
        helper.assertValueEqual(recorded, Policy.lockedRules().get("keepInventory"), Component.literal("keepInventory from the 1.2.0 marker"));
        helper.assertTrue(Policy.lockedRules().containsKey("fallDamage"), Component.literal("new rules were not added to the old marker"));
        helper.assertTrue(Policy.notes().isEmpty(), Component.literal("upgrading a 1.2.0 marker added a note"));
        helper.succeed();
    }

    @GameTest
    public void playWithoutTheModIsNoted(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        long now = Policy.gameTime(server);
        String marker = "{\"modId\": \"hardgrind_noop\", \"schema\": 3, \"gamerules\": {}, \"enforceSurvival\": true, "
                + "\"recordedAtGameTime\": 0, \"lastSeenGameTime\": %d, \"notes\": []}";

        // Saved recently: a normal reload, nothing to note.
        writeMarker(server, String.format(marker, now));
        Policy.initOrLoad(server);
        helper.assertTrue(Policy.notes().isEmpty(), Component.literal("a normal reload added a note: " + Policy.notes()));

        // The world has 10 minutes more play time than HardGrind saw.
        writeMarker(server, String.format(marker, now - 12000));
        Policy.initOrLoad(server);
        helper.assertValueEqual(1, Policy.notes().size(), Component.literal("notes after offline play"));
        helper.assertTrue(Policy.notes().get(0).contains("10 min without HardGrind"), Component.literal("note text: " + Policy.notes().get(0)));
        helper.assertTrue(readMarker(server).contains("without HardGrind"), Component.literal("note was not saved to the marker"));
        helper.succeed();
    }

    @GameTest
    public void statusCommandWorksForNonOps(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        loadRunWorld(server);
        ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
        try {
            int result = server.getCommands().getDispatcher().execute("hardgrind", player.createCommandSourceStack());
            helper.assertValueEqual(1, result, Component.literal("/hardgrind result"));
        } catch (CommandSyntaxException e) {
            helper.fail(Component.literal("/hardgrind failed for a normal player: " + e.getMessage()));
        }
        helper.assertTrue(HardGrindCommand.statusLines(server).size() >= 5, Component.literal("status is missing lines"));
        helper.succeed();
    }

    @GameTest
    public void lockDatapackIsValid(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Policy.Marker marker = new Policy.Marker();
        for (LockedRule rule : LockedRule.values()) {
            String value = GameRuleAccess.get(server, rule);
            if (value != null) {
                marker.gamerules.put(rule.key, value);
            }
        }
        Map<String, String> files = WorldLock.files(server, marker);

        // Every command must parse on this Minecraft version; a broken line would silently switch part of the lock off.
        CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
        int commands = 0;
        for (Map.Entry<String, String> file : files.entrySet()) {
            if (!file.getKey().endsWith(".mcfunction")) {
                continue;
            }
            for (String line : file.getValue().lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                // Same checks Minecraft applies when it loads a function file.
                ParseResults<CommandSourceStack> parsed = server.getCommands().getDispatcher().parse(line, source);
                CommandSyntaxException error = Commands.getParseException(parsed);
                boolean complete = error == null && ContextChain.tryFlatten(parsed.getContext().build(line)).isPresent();
                helper.assertTrue(complete, Component.literal(file.getKey() + " has a command that doesn't parse: " + line
                        + (error == null ? "" : " (" + error.getMessage() + ")")));
                commands++;
            }
        }
        helper.assertTrue(commands > 40, Component.literal("only " + commands + " lock commands"));

        JsonObject pack = JsonParser.parseString(files.get("pack.mcmeta")).getAsJsonObject().getAsJsonObject("pack");
        DataResult<?> format = PackFormat.packCodec(PackType.SERVER_DATA).codec().parse(JsonOps.INSTANCE, pack);
        helper.assertTrue(format.result().isPresent(), Component.literal("pack.mcmeta format is rejected: " + format));

        // The load blocker: with HardGrind's sound registered the song loads; pointing at a sound that doesn't
        // exist (what a game without HardGrind sees) is an error, and that's what stops the world from loading.
        String song = files.get("data/hardgrind_lock/jukebox_song/requires_hardgrind.json");
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        DataResult<JukeboxSong> withMod = JukeboxSong.DIRECT_CODEC.parse(ops, JsonParser.parseString(song));
        helper.assertTrue(withMod.result().isPresent(), Component.literal("lock jukebox song is rejected: " + withMod));
        DataResult<JukeboxSong> withoutMod = JukeboxSong.DIRECT_CODEC.parse(ops,
                JsonParser.parseString(song.replace(WorldLock.LOCK_SOUND, "hardgrind_noop:not_registered")));
        helper.assertTrue(withoutMod.result().isEmpty(), Component.literal("a song with a missing sound still loads, so the load blocker wouldn't block"));
        helper.succeed();
    }

    @GameTest
    public void buildWorldGivesOpsTheirCommandsBack(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        loadBuildWorld(server);
        ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
        PermissionTestAccess.opAtOwnerLevel(server, player);
        try {
            server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "gamemode creative");
            helper.assertValueEqual(GameType.CREATIVE, mode(player), Component.literal("game mode after /gamemode creative in a build world"));
        } finally {
            server.getPlayerList().deop(player.nameAndId());
            loadRunWorld(server);
        }
        helper.succeed();
    }

    @GameTest
    public void buildWorldEnforcesNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        loadBuildWorld(server);
        String recorded = GameRuleAccess.get(server, LockedRule.KEEP_INVENTORY);
        String changed = recorded.equals("true") ? "false" : "true";
        try {
            helper.assertFalse(Policy.shouldEnforceSurvival(server), Component.literal("Survival is enforced in a build world"));
            helper.assertFalse(Hooks.difficultyLocked(), Component.literal("the difficulty is locked in a build world"));
            helper.assertTrue(GameRuleAccess.set(server, LockedRule.KEEP_INVENTORY, changed), Component.literal("could not change keepInventory"));
            helper.assertFalse(Policy.enforceGamerules(server), Component.literal("a gamerule was put back in a build world"));
            helper.assertValueEqual(changed, GameRuleAccess.get(server, LockedRule.KEEP_INVENTORY), Component.literal("keepInventory in a build world"));
        } finally {
            GameRuleAccess.set(server, LockedRule.KEEP_INVENTORY, recorded);
            loadRunWorld(server);
        }
        helper.succeed();
    }

    @GameTest
    public void creativeWorldsAreBuildWorlds(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameType original = server.getDefaultGameType();
        ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
        PermissionTestAccess.opAtOwnerLevel(server, player);
        try {
            server.setDefaultGameType(GameType.CREATIVE);
            deleteMarker(server);
            Policy.initOrLoad(server);
            helper.assertTrue(PermissionTestAccess.hasCheatPermissions(player), Component.literal("op has no commands in a new Creative world"));

            // A Creative world HardGrind already knew before build worlds existed.
            writeMarker(server, "{\"modId\": \"hardgrind_noop\", \"schema\": 5, \"gamerules\": {}, \"enforceSurvival\": false}");
            Policy.initOrLoad(server);
            helper.assertTrue(PermissionTestAccess.hasCheatPermissions(player), Component.literal("op has no commands in a Creative world from 1.7"));

            server.setDefaultGameType(GameType.SURVIVAL);
            deleteMarker(server);
            Policy.initOrLoad(server);
            helper.assertFalse(PermissionTestAccess.hasCheatPermissions(player), Component.literal("op has commands in a new Survival world"));
        } finally {
            server.getPlayerList().deop(player.nameAndId());
            server.setDefaultGameType(original);
            loadRunWorld(server);
        }
        helper.succeed();
    }

    @GameTest
    public void buildCommandMakesARunWorldABuildWorldForGood(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        loadRunWorld(server);
        ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
        PermissionTestAccess.opAtOwnerLevel(server, player);
        try {
            helper.assertValueEqual(1, run(helper, player, "hardgrind build confirm"), Component.literal("/hardgrind build confirm result"));
            helper.assertTrue(PermissionTestAccess.hasCheatPermissions(player), Component.literal("op has no commands after /hardgrind build confirm"));
            helper.assertValueEqual(1, Policy.notes().size(), Component.literal("run log notes after /hardgrind build confirm"));

            Policy.initOrLoad(server);
            helper.assertTrue(PermissionTestAccess.hasCheatPermissions(player), Component.literal("the build world was gone after reloading"));
        } finally {
            server.getPlayerList().deop(player.nameAndId());
            loadRunWorld(server);
        }
        helper.succeed();
    }

    @GameTest
    public void lockedWorldsCantBecomeBuildWorlds(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
        PermissionTestAccess.opAtOwnerLevel(server, player);
        try {
            for (String world : new String[] {"locked", "ironman"}) {
                loadMarker(server, "\"enforceSurvival\": true, \"lockInstalled\": true, \"ironman\": " + world.equals("ironman"));
                helper.assertValueEqual(0, run(helper, player, "hardgrind build confirm"), Component.literal("/hardgrind build confirm result in a " + world + " world"));
                helper.assertFalse(PermissionTestAccess.hasCheatPermissions(player), Component.literal("op has commands in a " + world + " world"));
            }
        } finally {
            server.getPlayerList().deop(player.nameAndId());
            loadRunWorld(server);
        }
        helper.succeed();
    }

    @GameTest
    public void editedMarkerCantMakeALockedWorldABuildWorld(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        loadMarker(server, "\"enforceSurvival\": true, \"lockInstalled\": true, \"buildWorld\": true");
        ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
        PermissionTestAccess.opAtOwnerLevel(server, player);
        try {
            helper.assertFalse(PermissionTestAccess.hasCheatPermissions(player), Component.literal("op has commands in a locked world"));
            helper.assertValueEqual(1, Policy.notes().size(), Component.literal("run log notes after the edited marker"));
        } finally {
            server.getPlayerList().deop(player.nameAndId());
            loadRunWorld(server);
        }
        helper.succeed();
    }

    @GameTest
    public void lockPackFromAnOlderVersionIsNotCheating(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Policy.Marker marker = lockMarker(server);
        // What 1.4 installed: the difficulty function (1.5) and the jukebox song (1.6) didn't exist yet.
        Map<String, String> pack = new LinkedHashMap<>(WorldLock.files(server, marker));
        pack.remove("data/hardgrind_lock/function/difficulty.mcfunction");
        pack.remove("data/hardgrind_lock/jukebox_song/requires_hardgrind.json");
        marker.lockHash = installedHash(pack);
        try {
            writePack(server, pack);
            helper.assertTrue(inspectPack(server, marker).isEmpty(),
                    Component.literal("updating HardGrind counted as cheating: " + inspectPack(server, marker)));
        } finally {
            deletePack(server);
        }
        helper.succeed();
    }

    @GameTest
    public void tamperingWithTheLockPackIsCaught(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Policy.Marker marker = lockMarker(server);
        Map<String, String> pack = WorldLock.files(server, marker);
        marker.lockHash = installedHash(pack);
        String rules = "data/hardgrind_lock/function/rules.mcfunction";
        try {
            writePack(server, pack);
            helper.assertTrue(inspectPack(server, marker).isEmpty(), Component.literal("an untouched pack counted as cheating: " + inspectPack(server, marker)));

            Files.writeString(WorldLock.packDir(server).resolve(rules), "# gamerules are whatever you like now\n");
            helper.assertFalse(inspectPack(server, marker).isEmpty(), Component.literal("an edited rules function wasn't caught"));

            Files.delete(WorldLock.packDir(server).resolve(rules));
            helper.assertFalse(inspectPack(server, marker).isEmpty(), Component.literal("a deleted rules function wasn't caught"));

            writePack(server, pack);
            Files.delete(WorldLock.packDir(server).resolve("pack.mcmeta"));
            helper.assertFalse(inspectPack(server, marker).isEmpty(), Component.literal("a deleted pack.mcmeta wasn't caught"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deletePack(server);
        }
        helper.succeed();
    }

    @GameTest
    public void recordedReviveIsPermanentAndNoted(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        loadRunWorld(server);
        try {
            Policy.recordHardcoreRevive(server, "Steve");
            helper.assertTrue(Hooks.hardcoreUnlocked(), Component.literal("the revive wasn't recorded"));
            helper.assertValueEqual(1, Policy.notes().size(), Component.literal("notes after a revive"));
            helper.assertTrue(Policy.notes().get(0).endsWith("Steve died and hardcore was turned off with /hardgrind revive. No longer a hardcore run."),
                    Component.literal("note: " + Policy.notes().get(0)));
            Policy.initOrLoad(server);
            helper.assertTrue(Hooks.hardcoreUnlocked(), Component.literal("the revive was gone after reloading"));
        } finally {
            loadRunWorld(server);
        }
        helper.succeed();
    }

    @GameTest
    public void reviveIsRefusedOutsideHardcore(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        loadRunWorld(server);
        ServerPlayer player = mockPlayer(helper, GameType.SPECTATOR);
        String refusal = Revive.refusal(server, player);
        helper.assertTrue("This isn't a hardcore world.".equals(refusal), Component.literal("refusal: " + refusal));
        helper.assertValueEqual(0, run(helper, player, "hardgrind revive confirm"), Component.literal("/hardgrind revive confirm result"));
        helper.assertFalse(Hooks.hardcoreUnlocked(), Component.literal("the revive was recorded anyway"));
        helper.assertTrue(Policy.notes().isEmpty(), Component.literal("notes: " + Policy.notes()));
        helper.succeed();
    }

    @GameTest
    public void reviveIsRefusedInIronmanWorlds(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        loadMarker(server, "\"enforceSurvival\": true, \"lockInstalled\": true, \"ironman\": true");
        ServerPlayer player = mockPlayer(helper, GameType.SPECTATOR);
        try {
            String refusal = Revive.refusal(server, player);
            helper.assertTrue("Ironman worlds can't be revived. Your death ends the run.".equals(refusal),
                    Component.literal("refusal: " + refusal));
        } finally {
            loadRunWorld(server);
        }
        helper.succeed();
    }

    @GameTest
    public void statusShowsARevivedWorld(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        loadRunWorld(server);
        try {
            Policy.recordHardcoreRevive(server, "Steve");
            String status = HardGrindCommand.statusLines(server).stream().map(Component::getString).collect(Collectors.joining("\n"));
            helper.assertTrue(status.contains("Survival: enforced (was hardcore, turned off after a death)"), Component.literal(status));
            String run = HardGrindCommand.runLine().getString();
            helper.assertTrue(run.contains(" · was hardcore"), Component.literal(run));
        } finally {
            loadRunWorld(server);
        }
        helper.succeed();
    }

    @GameTest
    public void reviveRespawnsTheDeadOnTheirOwnConnection(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        loadRunWorld(server);
        ServerPlayer dead = mockPlayer(helper, GameType.SPECTATOR);
        ServerPlayer alive = mockPlayer(helper, GameType.SURVIVAL);
        try {
            Revive.revive(server, dead);
            ServerPlayer revived = dead.connection.player;
            helper.assertTrue(revived != dead, Component.literal("the connection still drives the removed spectator"));
            helper.assertValueEqual(GameType.SURVIVAL, mode(revived), Component.literal("revived player's game mode"));
            helper.assertTrue(server.getPlayerList().getPlayers().contains(revived), Component.literal("revived player isn't in the player list"));
            helper.assertTrue(alive.connection.player == alive, Component.literal("a living player was respawned too"));
            helper.assertValueEqual(GameType.SURVIVAL, mode(alive), Component.literal("living player's game mode"));
            helper.assertValueEqual(1, Policy.notes().size(), Component.literal("notes after a revive"));
        } finally {
            loadRunWorld(server);
        }
        helper.succeed();
    }

    @GameTest
    public void deadPlayerJoiningAfterAReviveIsRespawnedNotRevivedInPlace(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        loadMarker(server, "\"enforceSurvival\": true, \"hardcoreUnlocked\": true");
        ServerPlayer dead = mockPlayer(helper, GameType.SPECTATOR);  // died before the revive, offline during it
        try {
            ServerPlayConnectionEvents.JOIN.invoker().onPlayReady(dead.connection, ServerPlayNetworking.getSender(dead), server);
            ServerPlayer joined = dead.connection.player;
            helper.assertTrue(joined != dead, Component.literal("the spectator was turned to Survival where it was flying, not respawned"));
            helper.assertValueEqual(GameType.SURVIVAL, mode(joined), Component.literal("joined player's game mode"));
        } finally {
            loadRunWorld(server);
        }
        helper.succeed();
    }

    @SuppressWarnings("removal") // Still the only server-side mock player helper in these versions.
    private static ServerPlayer mockPlayer(GameTestHelper helper, GameType mode) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(mode);
        helper.assertValueEqual(mode, mode(player), Component.literal("mock player's starting game mode"));
        return player;
    }

    // makeMockServerPlayerInLevel's player always reports CREATIVE from gameMode(); the interaction manager is real.
    private static GameType mode(ServerPlayer player) {
        return player.gameMode.getGameModeForPlayer();
    }

    private static Path markerPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("hardgrind.marker");
    }

    private static void writeMarker(MinecraftServer server, String json) {
        try {
            Files.writeString(markerPath(server), json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int run(GameTestHelper helper, ServerPlayer player, String command) {
        try {
            return helper.getLevel().getServer().getCommands().getDispatcher().execute(command, player.createCommandSourceStack());
        } catch (CommandSyntaxException e) {
            helper.fail(Component.literal("/" + command + " failed: " + e.getMessage()));
            return -1;
        }
    }

    // The test world itself is Creative, so tests that need a Survival run or a build world load one explicitly.
    // Saved just now, so loading it adds no notes.
    private static void loadMarker(MinecraftServer server, String fields) {
        writeMarker(server, String.format("{\"modId\": \"hardgrind_noop\", \"schema\": 6, \"gamerules\": {}, "
                + "\"lastSeenGameTime\": %d, \"notes\": [], %s}", Policy.gameTime(server), fields));
        Policy.initOrLoad(server);
    }

    private static void loadRunWorld(MinecraftServer server) {
        loadMarker(server, "\"enforceSurvival\": true");
    }

    private static void loadBuildWorld(MinecraftServer server) {
        loadMarker(server, "\"enforceSurvival\": true, \"buildWorld\": true");
    }

    private static void deleteMarker(MinecraftServer server) {
        try {
            Files.deleteIfExists(markerPath(server));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // A locked world HardGrind saw a moment ago, with nothing on its scoreboard: only the pack itself is judged.
    private static Policy.Marker lockMarker(MinecraftServer server) {
        Policy.Marker marker = new Policy.Marker();
        for (LockedRule rule : LockedRule.values()) {
            String value = GameRuleAccess.get(server, rule);
            if (value != null) {
                marker.gamerules.put(rule.key, value);
            }
        }
        marker.lockInstalled = true;
        marker.lastSeenGameTime = Policy.gameTime(server);
        return marker;
    }

    /** What WorldLock.inspect counts as cheating (it would kill an ironman world). */
    private static List<String> inspectPack(MinecraftServer server, Policy.Marker marker) {
        List<String> fatal = new ArrayList<>();
        WorldLock.inspect(server, marker, Policy.gameTime(server), fatal, new ArrayList<>());
        return fatal;
    }

    // The lockHash every HardGrind since 1.4 recorded for the pack it installed: name, 0, content, 0, in order.
    private static String installedHash(Map<String, String> files) {
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

    private static void writePack(MinecraftServer server, Map<String, String> files) {
        deletePack(server);
        try {
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path path = WorldLock.packDir(server).resolve(file.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deletePack(MinecraftServer server) {
        Path dir = WorldLock.packDir(server);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readMarker(MinecraftServer server) {
        try {
            return Files.readString(markerPath(server));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
