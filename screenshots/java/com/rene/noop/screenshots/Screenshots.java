package com.rene.noop.screenshots;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;

/**
 * Dev-only: real screenshots of HardGrind for the mod page, taken in the actual game by a Fabric client game test.
 * Run: ./gradlew :26.3:runClientGameTest -Pscreenshots (see build.gradle). Not part of the mod or the normal build.
 */
public class Screenshots implements FabricClientGameTest {
    private static final String SEED = "hardgrind";
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    // Milestones are checked every 100 ticks; the first check in a world only notes what was done before.
    private static final int MILESTONE_CHECK = 110;
    // Chat lines fade 10 seconds after they arrive.
    private static final int CHAT_FADE = 210;
    // Where every shot stands (same seed, so the same spot in every world): the lagoon, with the badlands behind.
    // Picked from 8 trial shots around it.
    private static final BlockPos VIEWPOINT = new BlockPos(-55, 68, 100);
    private static final int YAW = 290;
    private static final int PITCH = 3;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            client.options.renderDistance().set(12);
            client.options.guiScale().set(3);  // applied by the resize below
        });
        // Lay the HUD out for the screenshot size, not the small test window.
        context.getInput().resizeWindow(WIDTH, HEIGHT);
        context.waitTicks(5);
        survivalRun(context);
        hardcoreSecondChance(context);
        buildWorld(context);
    }

    private void survivalRun(ClientGameTestContext context) {
        TestSingleplayerContext first = create(context, "Survival Run", SelectedGameMode.SURVIVAL);
        command(context, "hardgrind lock confirm");
        context.waitTicks(60);
        first.close();

        // Back in: the line HardGrind shows on joining now reads "Locked".
        try (TestSingleplayerContext world = first.getWorldSave().open()) {
            TestServerContext server = world.getServer();
            server.runCommand("time set 1000");
            server.runCommand("give @a minecraft:iron_sword");
            server.runCommand("give @a minecraft:torch 32");
            server.runCommand("give @a minecraft:bread 16");
            context.waitTicks(140);  // chunks render and the item toasts go away
            shot(context, "1-locked-world", false);

            command(context, "hardgrind");
            context.waitTicks(10);
            openChat(context);
            shot(context, "2-status", false);
            closeChat(context);

            server.runCommand("advancement grant @a only minecraft:story/mine_stone");
            server.runCommand("advancement grant @a only minecraft:story/smelt_iron");
            context.waitTicks(MILESTONE_CHECK);
            server.runCommand("advancement grant @a only minecraft:story/mine_diamond");
            context.waitTicks(MILESTONE_CHECK);
            shot(context, "3-milestone", true);

            command(context, "hardgrind log");
            context.waitTicks(10);
            openChat(context);
            shot(context, "4-run-log", false);
            closeChat(context);

            context.waitTicks(CHAT_FADE);  // let the run log fade, so the ironman message stands alone
            command(context, "hardgrind ironman confirm");
            context.waitTicks(20);
            shot(context, "5-ironman", false);
        }
    }

    private void hardcoreSecondChance(ClientGameTestContext context) {
        try (TestSingleplayerContext world = create(context, "Hardcore", SelectedGameMode.HARDCORE)) {
            world.getServer().runCommand("kill @a");
            context.waitTicks(40);
            shot(context, "6a-hardcore-death", false);
            // What the death screen's "Spectate World" button does; the screen closes when the server answers.
            context.runOnClient(client -> client.player.respawn());
            context.waitTicks(40);
            command(context, "hardgrind revive confirm");
            context.waitTicks(20);
            lookOut(world.getServer());  // a respawn faces wherever the spawn point says; face the view again
            context.waitTicks(40);
            shot(context, "6b-second-chance", false);
        }
    }

    private void buildWorld(ClientGameTestContext context) {
        try (TestSingleplayerContext world = create(context, "Build World", SelectedGameMode.CREATIVE)) {
            command(context, "hardgrind");
            context.waitTicks(10);
            openChat(context);
            shot(context, "7-build-world", false);
            closeChat(context);
        }
    }

    /** A new world from the same seed with the player at the viewpoint, after the first milestone check has run. */
    private static TestSingleplayerContext create(ClientGameTestContext context, String name, SelectedGameMode mode) {
        TestSingleplayerContext world = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(settings -> {
                    settings.setName(name);
                    settings.setSeed(SEED);
                    settings.setGameMode(mode);
                    if (mode != SelectedGameMode.HARDCORE) {
                        settings.setDifficulty(Difficulty.HARD);
                    }
                    settings.setAllowCommands(mode == SelectedGameMode.CREATIVE);
                })
                .create();
        scenery(world.getServer());
        context.waitTicks(MILESTONE_CHECK + 40);  // chunks at the new spot render meanwhile
        return world;
    }

    /**
     * Puts the player at the viewpoint, facing the view, and makes it the spawn so a respawn lands there too. Then a
     * clear morning and a quiet chat. The console keeps its commands under HardGrind.
     */
    private static void scenery(TestServerContext server) {
        silent(server, "gamerule show_advancement_messages false");
        String at = VIEWPOINT.getX() + " " + VIEWPOINT.getY() + " " + VIEWPOINT.getZ();
        silent(server, "setworldspawn " + at);
        silent(server, "spawnpoint @a " + at);
        lookOut(server);
        silent(server, "time set 1000");
        silent(server, "weather clear");
    }

    /** Stands the player on the viewpoint, facing the lagoon and the badlands. */
    private static void lookOut(TestServerContext server) {
        silent(server, "tp @a " + (VIEWPOINT.getX() + 0.5) + " " + VIEWPOINT.getY() + " " + (VIEWPOINT.getZ() + 0.5) + " " + YAW + " " + PITCH);
    }

    /** A console command with no feedback at all, so nothing shows up in chat (in a build world the player is an op). */
    private static void silent(TestServerContext server, String command) {
        server.runOnServer(minecraft -> minecraft.getCommands().performPrefixedCommand(
                minecraft.createCommandSourceStack().withSuppressedOutput(), command));
    }

    /** Runs a command as the player, the way typing it in chat does. */
    private static void command(ClientGameTestContext context, String command) {
        context.runOnClient(client -> client.player.connection.sendCommand(command));
    }

    private static void openChat(ClientGameTestContext context) {
        context.setScreen(() -> new ChatScreen("", false));
    }

    private static void closeChat(ClientGameTestContext context) {
        context.setScreen(() -> null);
    }

    private static void shot(ClientGameTestContext context, String name, boolean keepToasts) {
        if (!keepToasts) {
            context.runOnClient(client -> client.gui.toastManager().clear());
            context.waitTick();
        }
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(WIDTH, HEIGHT).disableCounterPrefix());
    }
}
