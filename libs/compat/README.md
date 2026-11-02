# Optional compatibility patches — compile-time jars

The patches under `src/main/java/com/extrarawstyle/veinminerplus/mixin/` reach into four other
mods. `build.gradle` references their jars from this folder as `compileOnly`, so they are needed to
compile the project but **none of them is bundled into the built mod** and **none of them becomes a
requirement for players**.

Each patch is gated in `VeinMinerPlusMixinPlugin` to the exact version it was written against. If a
player runs a different build of the target mod, the patch simply stays inactive and the game behaves
as if it were not installed.

## Which jars to put here

Download these exact versions and drop them into this folder with these names:

| File name | Mod | Version | Where to get it |
| --- | --- | --- | --- |
| `architectury-13.0.11-neoforge.jar` | Architectury API | 13.0.11 | Modrinth / CurseForge / [GitHub releases](https://github.com/architectury/architectury-api/releases) |
| `curios-neoforge-9.5.1+1.21.1.jar` | Curios API | 9.5.1+1.21.1 | CurseForge / Modrinth |
| `hostilenetworks-6.5.1.jar` | Hostile Neural Networks | 6.5.1 | CurseForge |
| `fastleafdecay-35.jar` | FastLeafDecay | 35 | CurseForge |

The names matter: `build.gradle` refers to these paths literally.

Without them, `./gradlew build` fails while compiling the `mixin` and `compat` packages. If you only
want to work on the chain logic itself, delete the four `compileOnly files(...)` lines for
`libs/compat/` and the `mixin` / `compat` packages, and drop the `[[mixins]]` block from
`src/main/templates/META-INF/neoforge.mods.toml`.

## What each patch does

| Patch | Target | Change |
| --- | --- | --- |
| `ArchitecturyDispatchMixin` | Architectury 13.0.11 `EventFactory$2.handleInvocation` | Calls the BREAK listener directly instead of preparing a reflective handle per listener per block. |
| `HostileCuriosLookupMixin` | Hostile 6.5.1 `CuriosCompat.applyToEquippedDeepLearner` | Uses Curios' predicate lookup so the cache key is not rebuilt for every block. |
| `FastLeafDecaySchedulerMixin` | FastLeafDecay 35 `FldScheduler.tick` | Skips the neighbourhood load check for scheduled positions already known not to be leaves. |

Behaviour, listener order, cancellation and random-tick semantics are unchanged by all three.

## Why these jars are not committed

Redistributing other mods' binaries from this repository is both a licensing question and needless
weight. Only the compile step needs them.
