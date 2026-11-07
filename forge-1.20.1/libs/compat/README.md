# Optional compatibility patches — compile-time jars

The patches under `src/main/java/com/extrarawstyle/veinminerplus/mixin/` reach into two other mods.
`build.gradle` references their jars from this folder as `compileOnly`, so they are needed to compile
the project but **neither is bundled into the built mod** and **neither becomes a requirement for
players**.

Each patch is gated in `VeinMinerPlusMixinPlugin` to the exact version it was written against. If a
player runs a different build of the target mod, the patch stays inactive and the game behaves as if
it were not installed.

The removal observation (`ServerPlayerGameModeMixin`) is **not** one of these: it targets vanilla
Forge code, needs no jar here, and is always active.

## Which jars to put here

| File name | Mod | Version | Where to get it |
| --- | --- | --- | --- |
| `architectury-9.2.14-forge.jar` | Architectury API | 9.2.14 | CurseForge / Modrinth |
| `Delightful-1.20.1-3.7.6.jar` | Delightful | 3.7.6 | CurseForge |

The names matter: `build.gradle` refers to these paths literally.

## What each patch does

| Patch | Target | Change |
| --- | --- | --- |
| `ArchitecturyDispatchMixin` | Architectury 9.2.14 `EventFactory$2.handleInvocation` | Calls `BlockEvent.Break` and `EntityEvent.Add` listeners directly instead of building a reflective handle per listener per block. Every other shape keeps the original reflective path. |
| `DelightfulEnabledConditionMixin` | Delightful 3.7.6 `LootItemEnabledCondition.test` | Caches which registry entry an item name resolves to. The enabled/disabled answer is still read from live config on every call. |

Listener order, cancellation, experience edits and the reflective fallback for unrecognised calls are
unchanged by both.

## Why these jars are not committed

They are redistributable mod jars that the patches only read at compile time. Keeping them out of the
repository avoids shipping someone else's binary; the exact versions above are what the patches were
verified against.

`libs/compat/*.jar` is ignored by `.gitignore`. Without these files the build fails while compiling
the `mixin` and `compat` packages, so a clean checkout needs them added before building a release.
