package com.rene.noop;

/** What HardGrind's mixins ask, kept in one public place (the policy itself isn't public). */
public final class Hooks {
    private Hooks() {
    }

    /**
     * Freecam is forced into fair mode (collision on, look only) while the world being played is locked: the world
     * this game runs itself, or a server's (FairModeSync). Also for the config FreecamGuard.verified() tests.
     */
    public static boolean freecamFairMode(Object freecamConfig) {
        return Policy.lockActive() || FairModeSync.serverLocked || FreecamGuard.isProbe(freecamConfig);
    }

    /** A build world: HardGrind steps aside, so players get their normal permissions (see Policy.makeBuildWorld). */
    public static boolean buildWorld() {
        return Policy.buildWorld();
    }

    /** A hardcore world revived with /hardgrind revive, which isn't hardcore anymore (see the PrimaryLevelData mixin). */
    public static boolean hardcoreUnlocked() {
        return Policy.hardcoreUnlocked();
    }

    /** Players can't change the difficulty (the Options button) in a world whose difficulty HardGrind locked. */
    public static boolean difficultyLocked() {
        return Policy.difficulty() != null;
    }
}
