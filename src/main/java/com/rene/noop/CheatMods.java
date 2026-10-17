package com.rene.noop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Client-side cheats HardGrind can't otherwise see: X-ray, hacked clients, bots. In singleplayer the world runs
 * in the same game as these mods, so a locked world simply refuses to load while one is installed. Ordinary
 * helpers (minimaps, inventory tweaks, recipe viewers) are not on the list, and Freecam is allowed in a fair
 * mode instead (see {@link FreecamGuard}).
 */
final class CheatMods {
    private static final Set<String> IDS = Set.of(
            "wurst", "meteor-client", "aristois", "bleachhack", "liquidbounce", "thunderhack",
            "baritone");

    private CheatMods() {
    }

    /** Names of the cheat mods that are installed, e.g. "Freecam (freecam)". Empty if none. */
    static List<String> installed() {
        List<String> found = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId().toLowerCase(Locale.ROOT);
            if (IDS.contains(id) || id.contains("xray") || id.contains("x-ray")) {
                found.add(mod.getMetadata().getName() + " (" + id + ")");
            }
        }
        return found;
    }
}
