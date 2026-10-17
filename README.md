# HardGrind No-OP

**Singleplayer discipline mod for Fabric.** Every player's OP is gone, Survival is enforced, the difficulty and cheat-relevant gamerules are locked, and once you lock a world, removing the mod doesn't unlock it.

## Supported versions

| Minecraft | Jar | Java |
|---|---|---|
| 1.21.9 | `hardgrind-noop-1.9.1+1.21.9.jar` | 21 |
| 1.21.10 | `hardgrind-noop-1.9.1+1.21.10.jar` | 21 |
| 1.21.11 | `hardgrind-noop-1.9.1+1.21.11.jar` | 21 |
| 26.1 - 26.1.2 | `hardgrind-noop-1.9.1+26.1.2.jar` | 25 |
| 26.2 | `hardgrind-noop-1.9.1+26.2.jar` | 25 |
| 26.3 and newer | `hardgrind-noop-1.9.1+26.3.jar` | 25 |

Use the jar for your Minecraft version, together with Fabric API for that version. The 26.3 jar also loads on newer versions, so a Minecraft update doesn't leave your worlds without HardGrind (checked against 26.4-snapshot-3).

## What it does in every Survival world
- **OP is dead for players** (except in [build worlds](#build-worlds-hardgrind-build-confirm)). A player's permissions are always the lowest level, whether they're in `ops.json`, in a singleplayer world with cheats on, or opened to LAN with "Allow Cheats". That blocks every op command (`/gamemode`, `/give`, `/tp`, `/gamerule`, `/op`, ...) and the F3+F4 / F3+N game mode switcher. The server console and datapack functions keep their permissions.
- **Survival is enforced.** Players in any other mode are put back on the next tick. In Hardcore, dead players are left in Spectator.
- **Difficulty is locked** to what the world had when HardGrind first saw it. Minecraft lets the singleplayer owner change difficulty without OP, so switching to Peaceful mid-fight was a real way to cheat; HardGrind blocks the Difficulty button.
- **Gamerules are locked** to the values they had when HardGrind first saw the world: `keepInventory`, `doDaylightCycle`, `doWeatherCycle`, `doMobSpawning`, `spawnMonsters` (1.21.11+), `mobGriefing`, `naturalRegeneration`, `doImmediateRespawn`, `randomTickSpeed`, `playersSleepingPercentage`, `fallDamage`, `fireDamage`, `drowningDamage`, `freezeDamage`. Changes (for example from editing `level.dat`) are reverted within 5 seconds.
- **Run log and milestones.** The big steps of a run (Stone Age, Acquire Hardware, Diamonds!, the Nether, blaze rods, Eye Spy, the End, the dragon, elytra, ancient debris, full netherite, a full beacon) are announced when you get them and stamped with the day and your play time. Milestones you had before HardGrind kept the log are listed without a date.
- **Clean-run notes.** If the world was played without HardGrind, the game crashed or was force-quit, or anything below was caught, a permanent note goes into the run log, and players are told on join.

Creative worlds are [build worlds](#build-worlds-hardgrind-build-confirm). Other worlds whose default game mode isn't Survival (e.g. Adventure maps) are left alone, apart from OP being blocked.

## Locking a world (`/hardgrind lock confirm`)
Without a lock, everything above stops when you remove the mod. Locking makes it stick:
- **The world won't open without HardGrind.** A small datapack goes into the world's `datapacks` folder (`hardgrind_lock`). It points at a sound only HardGrind registers, so without the mod Minecraft stops with "data pack validation failed" (the error mentions `hardgrind_noop:this_world_needs_hardgrind`). No world means no Open to LAN cheats.
- The only way in without the mod is Minecraft's **Safe Mode** button, which switches the lock off. HardGrind catches that when it's back. **If you see that screen for a locked world, click Back to Title, not Safe Mode.** (Clicking Safe Mode and quitting right away is only a note.)
- As a backup, if the datapack runs without the mod anyway, everyone is frozen (Adventure mode, blind, can't move); Creative/Spectator, changed gamerules and a changed difficulty are reverted and recorded; and vanilla's own "Lock Difficulty" is set.
- **Save-scum guard.** HardGrind remembers how far each locked world has been saved, in `hardgrind/ledger.json` in the game folder. A world may never go backwards: a restored backup, or an old copy behind the latest save, is caught. Only that copy pays for it, so a leftover old copy you open by accident can't end your real run, and up-to-date copies are fine. A dead world stays dead even if you restore a backup of it.
- **Force-quit protection.** Locked worlds save every 2 minutes, and right away when someone dies or drops to 3 hearts, so killing the game can't take back a death or a lost fight.
- **Cheat-mod guard.** A locked world refuses to load while known cheat mods are installed: X-ray mods, Baritone, and hacked clients (Wurst, Meteor, Aristois, BleachHack, LiquidBounce, ThunderHack). Minimaps, inventory tweaks, recipe viewers and the like are fine.
- **Freecam, fair mode.** [Freecam](https://modrinth.com/mod/freecam) is allowed, but in a locked world HardGrind keeps its collision on (no flying through blocks, glass or doors) and makes it look-only (no interacting from the camera). Fullbright and fog settings stay up to you. HardGrind tests this against the installed Freecam version (1.4 and 1.5 are supported) when you join; if a future Freecam can't be forced into fair mode, locked worlds won't load with it until HardGrind is updated. On a server or as a LAN guest, the server tells your game the world is locked, so fair mode holds there too.
- When the mod comes back after the world was opened without it, HardGrind checks what happened: the datapack deleted, switched off or edited, `hardgrind.marker` deleted, recorded cheats, a changed difficulty, a rollback, or play time where the datapack wasn't running. All of it goes into the run log.

`/hardgrind unlock confirm` takes the lock out again, so the world opens without the mod, and leaves a permanent note in the run log. Ironman worlds can't be unlocked.

## Ironman (`/hardgrind ironman confirm`)
Locks the world (if it isn't already) and arms it, for good. From then on, anything HardGrind catches kills the world: it stays on disk, but HardGrind kicks you every time you load it, and without the mod it doesn't open (or stays frozen). Don't add datapacks or mods that change the locked gamerules to an ironman world: that counts as cheating.

## Build worlds (`/hardgrind build confirm`)
A world for building, not for a run. HardGrind steps aside there:
- `/gamemode`, F3+F4, `/gamerule`, `/time` and the other commands work again. In singleplayer (and for the host of a LAN world) they work even if the world was made with "Allow Cheats" off; everyone else gets vanilla's permissions, so ops are ops.
- Survival isn't enforced, and the difficulty and gamerules aren't locked. There are no milestones, and `/hardgrind log` says it's not a run.

Creative worlds are build worlds automatically. `/hardgrind build confirm` turns any other world into one, for good, with a note in the run log. A build world can never be locked or armed, and a locked world has to be unlocked first; ironman worlds can never become build worlds.

## Hardcore second chance (`/hardgrind revive confirm`)
Died in a hardcore world but want to keep going? After dying (click "Spectate World", then type it), `/hardgrind revive confirm` turns hardcore off for that world, for good:
- Everyone who died respawns at their bed or the world spawn with an empty inventory, like a normal death. Your dropped items are where you died.
- The run log gets a permanent note, and `/hardgrind` and the run line say "was hardcore". There's no way to turn hardcore back on.
- Not in ironman worlds: there, your death ends the run.
- Rejoin the world afterwards to get normal hearts back. Minecraft's world list keeps calling the world "Hardcore".

## Commands
Any player can run them.
- `/hardgrind`: status: OP, Survival, difficulty, locked gamerules, lock/ironman, save-scum guard, cheat mods, Freecam, and the run.
- `/hardgrind log`: the run log: day, play time, deaths, mobs killed, the milestone timeline, and any notes.
- `/hardgrind lock`, then `/hardgrind lock confirm`: lock the world.
- `/hardgrind unlock`, then `/hardgrind unlock confirm`: unlock it (not ironman worlds).
- `/hardgrind ironman`, then `/hardgrind ironman confirm`: arm ironman.
- `/hardgrind build`, then `/hardgrind build confirm`: make it a build world (not locked or ironman worlds).
- `/hardgrind revive`, then `/hardgrind revive confirm`: after dying in a hardcore world, turn hardcore off and come back (not ironman worlds).

When you join, one line sums up the run, e.g. `HardGrind · Day 12 · Run: Ironman · clean · watching since Day 1`.

## Config (`config/hardgrind.json`)
- `autoLock` (default `false`): lock every Survival world automatically the first time HardGrind loads it, instead of only after `/hardgrind lock confirm`.
- `sharedLedger` (default `false`): keep the save-scum ledger in your user folder (`~/.hardgrind/ledger.json`), shared by every launcher and instance, instead of in this game folder. Then a world copied into another instance is still recognised.

Everything else is stored per world in `<world>/hardgrind.marker`. The rule names in it don't change between Minecraft versions, so a world keeps its policy when you update it.

Multiplayer: a server with locked worlds needs HardGrind on every player's game too, because the lock datapack refers to HardGrind's sound.

## Upgrading
- **From 1.7:** Creative worlds become build worlds, so `/gamemode` and the other commands work there again. Survival worlds are unchanged.
- **From 1.5 or 1.6:** worlds that are already locked stay locked, and ironman stays armed. New worlds are no longer locked automatically: use `/hardgrind lock confirm`, or set `autoLock` to `true`. The ledger moves into the game folder (its history is copied over); set `sharedLedger` to `true` to keep using the shared one.
- **From 1.4.0:** the world's current difficulty gets locked, and the milestones you already have are logged without a date. Nothing about the world is judged retroactively.
- **From 1.3.0:** worlds get the lock only through `/hardgrind lock confirm` or `autoLock`.
- **From 1.2.0:** the world keeps its recorded gamerules. Newer rules are locked at their current values the first time the world loads.
- **From 1.1.x:** those markers were incomplete, so the policy is re-recorded from the world's current state.

## Build
```bash
./gradlew build
```
This builds and tests every version and puts all the jars in `build/libs/`. The first run needs internet: Gradle downloads each Minecraft version and, if you don't have one, a Java 25 JDK. You don't need to install Java 25 yourself.

- One version only: `./gradlew :26.3:build` (jar in `versions/26.3/build/libs/`)
- Game tests only: `./gradlew :1.21.11:runGameTest` (they also run as part of `build`)
- Try it in a dev client: `./gradlew :26.3:runClient`

GitHub Actions (`.github/workflows/build.yml`) runs the same build on every push and attaches the jars to the run.

## Project layout
- `src/main`, `src/gametest`: code shared by every version, written with Mojang's names.
- `compat/1.21.9`: what differs on 1.21.9 - 1.21.10 (old gamerule API, int permission levels, ResourceLocation ids).
- `compat/1.21.11`: what differs on 1.21.11 and 26.x (registry gamerules, permission sets, Identifier ids).
- `versions/<minecraft>/gradle.properties`: Minecraft, Fabric API and Java versions for each jar, plus which `compat` folder it uses.

To add a Minecraft version: copy a `versions/` folder, update its numbers, and add it to the list in `settings.gradle`. If it doesn't compile, give it its own `compat/` folder.

## License
MIT
