package com.rene.noop;

import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * OP itself is removed by {@link com.rene.noop.mixin.MinecraftServerPermissionMixin}. This class polices
 * what OP would otherwise be used for: game mode, gamerules and difficulty. {@link WorldLock} keeps the world
 * locked when the mod isn't there, {@link Ledger} catches restored backups and copies, {@link CheatMods} keeps
 * client-side cheats out, and {@link Milestones} keeps the run's timeline.
 */
public class NoOpMod implements ModInitializer {
    static final String MOD_ID = "hardgrind_noop";
    static final Logger LOG = LogManager.getLogger("HardGrind");
    private static final int POLICY_CHECK_INTERVAL_TICKS = 100;
    // Locked worlds save more often than vanilla's 5 minutes, and right away when someone dies or is close to
    // it, so force-quitting the game can't take back a death or a lost fight.
    private static final int SAVE_INTERVAL_TICKS = 2400;
    private static final int DANGER_SAVE_COOLDOWN_TICKS = 600;
    private static final float LOW_HEALTH = 6.0F;

    private int lastSaveTick;

    @Override
    public void onInitialize() {
        Registration.registerLockSound();
        FairModeSync.register();
        Config.Values config = Config.get();
        LOG.info("[HardGrind] autoLock={}, sharedLedger={} (config/hardgrind.json)", config.autoLock, config.sharedLedger);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> HardGrindCommand.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            lastSaveTick = 0;
            Policy.initOrLoad(server);
            LOG.info("[HardGrind] Policy marker loaded for world {}", server.getWorldPath(LevelResource.ROOT));
            for (String note : Policy.notes()) {
                LOG.warn("[HardGrind] {}", note);
            }
        });
        ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> Policy.onSaved(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(Policy::onStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> Policy.unload());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (Policy.dead()) {
                handler.disconnect(Component.literal("HardGrind: this world is dead.\n\n" + Policy.deathReason()
                        + ".\n\nIt was armed with /hardgrind ironman, so it can't be played anymore."));
                return;
            }
            List<String> cheats = CheatMods.installed();
            if (Policy.lockActive() && !cheats.isEmpty()) {
                LOG.warn("[HardGrind] Refusing to load a locked world with cheat mods installed: {}", cheats);
                handler.disconnect(Component.literal("HardGrind: this world is locked, and cheat mods are installed:\n\n"
                        + String.join("\n", cheats) + "\n\nRemove them to keep playing."));
                return;
            }
            if (Policy.lockActive() && FreecamGuard.installed() && !FreecamGuard.verified()) {
                handler.disconnect(FreecamGuard.unsupportedMessage());
                return;
            }

            ServerPlayer player = handler.getPlayer();
            if (Revive.diedBeforeTheRevive(player)) {
                // Respawn like a normal death, not Survival right where the spectator was flying.
                player = Revive.respawnDead(server, player);
            }
            FairModeSync.send(player);
            if (Policy.shouldEnforceSurvival(server)) {
                enforceSurvival(player, server);
            }
            if (Policy.loaded() && Policy.shouldEnforceSurvival(server)) {
                player.sendSystemMessage(Component.literal("HardGrind · " + Policy.dayLabel(Policy.gameTime(server)) + " · ")
                        .withStyle(ChatFormatting.GOLD).append(HardGrindCommand.runLine()));
            }
        });

        // Before the tick's functions run, so the lock datapack sees the mod is here.
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (Policy.lockActive()) {
                WorldLock.heartbeat(server);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (Policy.shouldEnforceSurvival(server)) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    enforceSurvival(player, server);
                }
            }

            int tick = server.getTickCount();
            if (tick % POLICY_CHECK_INTERVAL_TICKS == 0) {
                if (Policy.enforceGamerules(server)) {
                    LOG.warn("[HardGrind] Gamerules restored to policy.");
                }
                Milestones.check(server);
            }
            if (tick % Policy.SEEN_INTERVAL_TICKS == 0) {
                Policy.markSeen(server);
            }

            if (Policy.lockActive()) {
                boolean danger = false;
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    danger |= player.isDeadOrDying() || player.getHealth() <= LOW_HEALTH;
                }
                int sinceSave = tick - lastSaveTick;
                if (sinceSave >= SAVE_INTERVAL_TICKS || (danger && sinceSave >= DANGER_SAVE_COOLDOWN_TICKS)) {
                    lastSaveTick = tick;
                    server.saveEverything(true, false, false);
                }
            }
        });
    }

    static void enforceSurvival(ServerPlayer player, MinecraftServer server) {
        // The interaction manager's mode, not player.gameMode(): test mock players override the latter.
        GameType mode = player.gameMode.getGameModeForPlayer();
        if (mode == GameType.SURVIVAL) {
            return;
        }
        // Hardcore turns dead players into spectators; forcing them back to Survival would revive them.
        if (mode == GameType.SPECTATOR && server.isHardcore()) {
            return;
        }
        if (player.setGameMode(GameType.SURVIVAL)) {
            player.sendSystemMessage(Component.literal("Gamemode enforced: SURVIVAL"));
        }
    }
}
