package com.rene.noop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** {@code config/hardgrind.json}. Created with the defaults the first time the game starts with HardGrind. */
final class Config {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("hardgrind.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Values values;

    private Config() {
    }

    static final class Values {
        String help = "autoLock: lock every Survival world automatically, instead of only after /hardgrind lock confirm. "
                + "sharedLedger: keep the save-scum ledger in your user folder (~/.hardgrind), shared by every launcher and "
                + "instance, instead of in this game folder; then a world copied to another instance is still recognised.";
        boolean autoLock = false;
        boolean sharedLedger = false;
    }

    static Values get() {
        if (values == null) {
            values = load();
        }
        return values;
    }

    private static Values load() {
        Values loaded = null;
        if (Files.exists(FILE)) {
            try {
                loaded = GSON.fromJson(Files.readString(FILE), Values.class);
            } catch (IOException | JsonParseException e) {
                NoOpMod.LOG.warn("[HardGrind] Could not read {}, using the defaults: {}", FILE, e.toString());
                return new Values();
            }
        }
        if (loaded == null) {
            loaded = new Values();
        }
        // Rewrite so new options show up in older files; values the user set are kept.
        loaded.help = new Values().help;
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(loaded));
        } catch (IOException e) {
            NoOpMod.LOG.warn("[HardGrind] Could not write {}", FILE, e);
        }
        return loaded;
    }
}
