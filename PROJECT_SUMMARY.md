# VeinMinerPlus Project Handoff

Last updated: 2026-08-27

## Read First

This repository contains two independent builds of the same Mod. Do not merge their Gradle files, source roots, or output artifacts.

- NeoForge build: Minecraft `1.21.1`, NeoForge `21.1.233`, Java `21` at `G:\java21`.
- Forge build: Minecraft `1.20.1`, Forge `47.4.13`, Java `17` at `G:\java17`.
- Mod ID: `veinminerplus`.
- Java package: `com.extrarawstyle.veinminerplus`.
- Author: `Extra_RawStyle`.
- License: `MIT`.
- Gradle executable: `G:\gradle-8.8\bin\gradle.bat`.
- Gradle cache: `G:\GradleCache`.

The user manually installs JARs into test instances. Do not start Minecraft or copy artifacts into a test instance unless the user explicitly changes this instruction.

## Current Artifacts

All released artifacts are retained under `G:\Codex项目\连锁mod\mods`.

| Target | Current version | Artifact | SHA-256 |
| --- | --- | --- | --- |
| NeoForge 1.21.1 | `1.1.6` | `veinminerplus-1.1.6.jar` | `BEFC1126F67FD9A03D374026EB38169D5A1D8E68E4B9417FFFFBF41E7063EA9B` |
| Forge 1.20.1 | `1.1.8-forge` | `veinminerplus-1.1.8-forge.jar` | `AC30F0F7C29BDED34F375C8D1849906BF5BE6DCEA438701E1E1B3A89F4DDCF57` |

Increment the relevant `mod_version` for every further functional or metadata change. Preserve old JARs and do not overwrite them.

## Project Layout

| Target | Project root | Server source | Client source |
| --- | --- | --- | --- |
| NeoForge | `G:\Codex项目\连锁mod` | `src\main\java\com\extrarawstyle\veinminerplus\ChainEvents.java` | `VeinMinerPlusClient.java` in the same package |
| Forge | `G:\Codex项目\连锁mod\forge-1.20.1` | `src\main\java\com\extrarawstyle\veinminerplus\ChainEvents.java` | `VeinMinerPlusClient.java` plus `VeinMinerPlusClientEvents.java` |

Both builds also contain `ChainMode.java`, `Config.java`, `NetworkHandler.java`, and `VeinMinerPlus.java` under their respective source roots.

## Shared Behavior

- Normal chain mining searches all 26 neighboring directions. Default limit: `1024` blocks.
- Area modes are `1x1` and `3x3`; `2x2` was deliberately removed.
- Area mining determines the plane from the hit face and advances away from the player into the target.
- Blast modes: same block, ores, any harvestable block, and logs only.
- Logs mode targets `minecraft:logs` and does not chain leaves.
- Containers, menu blocks, and blocks exposing an item-handler capability are excluded.
- A chain starts only after the player actually breaks the first eligible block while the grave-accent key is held.
- Releasing the key ends the current job and settles its buffered drops.
- Hold `~` to show the current mode. Hold `Shift + ~` to show all modes and use the mouse wheel to select one; the selected mode is highlighted in the HUD.
- All chained blocks use `player.gameMode.destroyBlock(pos)` to preserve the normal player-break path, including drops, enchantments, durability, experience, and events.
- Searches and destruction are tick-bounded to avoid blocking server TPS.
- Unbreakable blocks such as bedrock are excluded from every chain mode.
- Blast jobs warn and throttle below 12 TPS, pause below 8 TPS, and stop after 20 consecutive critical ticks while settling buffered drops.
- `noHungerCost` is disabled by default; when enabled, automatically chained blocks do not add hunger exhaustion while the manually mined first block keeps vanilla behavior.

### Empty Hand and Wood Changes

- Empty hand may now start and continue normal, area, and blast chain modes.
- This does not bypass normal harvest checks. A block still needs to be breakable by the player under the active loader's normal rules.
- Blast logs no longer requires an axe. Any tool, including an empty hand, can chain a log when it can normally break that log.

### Drop Settlement

- Drops and experience are buffered during a chain and settle at the player's current position when the job finishes, reaches its limit, the key is released, or the player leaves.
- Item stacks are merged by item plus components/tags before spawning. This avoids item entities first appearing at remote block positions on map mods.
- NeoForge captures drops with `BlockDropsEvent` before they join the world.
- Forge captures item entities and experience orbs with `EntityJoinLevelEvent`; active chained breaks use a thread-local capture context and the initial block uses a pending origin window.

## Ore Tags

| Target | Blast ores tags |
| --- | --- |
| NeoForge 1.21.1 | `minecraft:ores`, `c:ores` |
| Forge 1.20.1 | `minecraft:ores`, `forge:ores`, `c:ores` |

The Forge support for `forge:ores` was added for Monifactory compatibility.

## Configuration

The common config file is `config/veinminerplus-common.toml` in each running instance.

- `maxNormalBlocks`: normal and area limit, range `32-32767`, default `1024`.
- `maxNormalBlocksPerTick`: normal blocks broken per tick, range `1-384`, default `8`.
- `maxBlastBlocks`: blast limit, range `32-32767`, default `32767`.
- `maxBlastBlocksPerTick`: blast blocks broken per tick, range `1-512`, default `64`.
- `blastSearchDistance`: blast Euclidean search distance, range `3-128`, default `20`.
- `noHungerCost`: disable hunger exhaustion for automatically chained blocks, default `false`.

## Forge 1.20.1 Notes

- The target pack is `G:\MC\PCL\.minecraft\versions\Monifactory`.
- Its confirmed runtime is Minecraft `1.20.1`, Forge `47.4.13`, and Java `17`.
- Forge client key registration is on the Mod bus. Input, mouse-scroll, and HUD listeners are on the Forge game bus. Do not combine the two listener sets in one `@EventBusSubscriber` class.
- `src\main\resources\pack.mcmeta` is required. It uses pack format `15` so Forge loads `zh_cn.json` and `en_us.json` as a valid Mod resource pack.
- Version `1.1.2-forge` failed to load because client input listeners were wrongly scanned on the Mod bus. This was fixed in `1.1.3-forge`.
- Version `1.1.3-forge` lacked `pack.mcmeta`, causing missing translations and an invalid `ResourcePackInfo` warning. This was fixed in `1.1.4-forge`.
- `1.1.5-forge` adds empty-hand chaining and removes the axe-only restriction for blast logs.
- `1.1.8-forge` adds the current HUD, bedrock protection, TPS safety, optional no-hunger chain mining, and explicit Configured translations.
- FTB Ultimine is installed in Monifactory. The user will disable it or change its keybinding; do not add compatibility handling unless requested.

## Build Commands

### NeoForge 1.21.1

Run from `G:\Codex项目\连锁mod`:

```powershell
$env:JAVA_HOME = 'G:\java21'
$env:Path = 'G:\java21\bin;' + $env:Path
$env:GRADLE_USER_HOME = 'G:\GradleCache'
& 'G:\gradle-8.8\bin\gradle.bat' --no-daemon --console=plain build
```

Output: `build\libs\veinminerplus-<version>.jar`.

### Forge 1.20.1

Run from `G:\Codex项目\连锁mod\forge-1.20.1`:

```powershell
$env:JAVA_HOME = 'G:\java17'
$env:Path = 'G:\java17\bin;' + $env:Path
$env:GRADLE_USER_HOME = 'G:\GradleCache'
& 'G:\gradle-8.8\bin\gradle.bat' --no-daemon --console=plain build
```

The Forge build must complete `compileJava`, `processResources`, `jar`, and `reobfJar`. Its output is `build\libs\veinminerplus-<version>-forge.jar`.

After either build, copy only the new version to `G:\Codex项目\连锁mod\mods` and compare its SHA-256 to the source artifact. Do not copy it to a pack automatically.

## Validation Status

- NeoForge `1.1.6`: Java 21 full build completed and output hash matched; gameplay behavior has not been tested in an instance.
- Forge `1.1.8-forge`: Java 17 full build and `reobfJar` completed and output hash matched; it has not been launched or tested in Monifactory.
- A successful build is not evidence of in-game correctness.

The next manual Forge test should use only the latest VeinMinerPlus Forge JAR and verify:

1. Mod loads without a client event-bus or resource-pack error.
2. Chinese mode HUD and progress text render as translations.
3. Normal, area, blast same, blast ores, blast any, and blast logs work with an empty hand where the block is normally breakable.
4. Blast logs works with non-axe tools and with an empty hand.
5. Blast ores detects Monifactory ores tagged `forge:ores`.
6. Releasing the key, reaching a limit, and natural search completion settle merged drops and experience at the player without source-location item markers.
7. FTB Ultimine is disabled or rebound before key-conflict conclusions are drawn.
8. Blast any does not break bedrock or other unbreakable blocks.
9. Low-TPS warnings, throttling, pausing, and safe job termination behave as expected during a blast job.
10. `noHungerCost` defaults to false and suppresses exhaustion only for automatically chained blocks when enabled.

## Development Constraints

- State assumptions and ask when a requirement is ambiguous.
- Modify only files required by the request; do not refactor unrelated code.
- Prefer public Minecraft, Forge, and NeoForge APIs. Do not introduce Mixins, reflection, or direct third-party Mod internals without first explaining why they are necessary.
- Keep the two loader implementations separate and preserve their Mod ID, package, versions, and historical JARs.
- Build and artifact-hash verification are separate from gameplay validation. Report both honestly.
