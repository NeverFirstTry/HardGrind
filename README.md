# HardGrind No-OP (Fabric 1.21.9)

**Singleplayer discipline mod.** Blocks common cheat commands, enforces Survival, and hard-denies OP at the permission source.
Optionally disables the **Open to LAN** screen.

## Features
- OP is effectively dead: permission checks always return `false`.
- Blocks: `/op`, `/deop`, `/gamemode`, `/give`, `/tp`, `/summon`, `/effect`, `/xp`, `/enchant`, `/locate`, `/gamerule`.
- Enforces `SURVIVAL` on join and every tick (cheap guard).
- Optional client mixin disables **Open to LAN**.

## Compatibility
- Minecraft **1.21.9**
- Fabric Loader **0.17.2**
- Fabric API **0.134.0+1.21.9**
- Java **21**

## Build
```bash
# Generate wrapper (if you don't have it yet)
gradle wrapper --gradle-version 9.1.0

# Build with wrapper
./gradlew build
```

The jar will appear under `build/libs/` (non-plain artifact).

> If the build fails with a client class not found: the client mixin is already under `src/client/java` and
> Loom `splitEnvironmentSourceSets()` is enabled. As a quick workaround, remove `OpenToLanScreenMixin` from
> `hardgrind.mixins.json`'s `client` block.

## Install
Drop the built `.jar` into your `mods/` directory for a Fabric 1.21.9 profile.

## License
MIT
